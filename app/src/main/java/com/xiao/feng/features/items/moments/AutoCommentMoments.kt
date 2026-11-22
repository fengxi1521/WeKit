package com.xiao.feng.features.items.moments

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.net.models.protobuf.SnsCommentActionProto
import com.xiao.feng.features.api.ui.WeMomentsApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.BaseItemContainer
import com.xiao.feng.ui.content.m3.IntNumberPickerWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.content.m3.TextFieldDialogWidget
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.serialization.DefaultJson
import dev.ujhhgtg.reflekt.reflekt
import java.util.concurrent.ConcurrentHashMap

/**
 * Posts a configured comment on newly published Moments.
 *
 * The trigger is the host writing a post into `SnsInfo` storage, so the feature also works while
 * the timeline UI is closed. Posts older than the moment the feature was enabled are never
 * touched, and every commented post is remembered so a restart does not comment twice.
 */
object AutoCommentMoments : AutoMomentsBase() {

    override val technicalId = "朋友圈自动评论"
    override val nameRes = R.string.feature_auto_comment_moments_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_auto_comment_moments_description

    override val TAG = "AutoCommentMoments"

    private const val RETRY_INTERVAL_MS = 30_000L
    private const val MAX_HANDLED_IDS = 500
    private const val MAX_AI_COMMENT_META = 200
    private const val MAX_AI_TEXTS_PER_POST = 20
    private const val MAX_ANSWERED_REPLIES = 500
    private const val MAX_REPLY_AGE_SECONDS = 7L * 24 * 3600
    private const val SCAN_INTERVAL_MS = 60_000L
    private const val REPLY_POLL_INTERVAL_MS = 45_000L
    private const val REPLY_POLL_LIMIT = 200

    private var commentText by prefOption("moments_auto_comment_text", "")
    private var intervalSeconds by prefOption("moments_auto_comment_interval_seconds", 3)
    private var commentWhenBrowsing by prefOption("moments_auto_comment_when_browsing", false)
    private var whitelistRaw by prefOption("moments_auto_comment_whitelist", "")
    private var blacklistRaw by prefOption("moments_auto_comment_blacklist", "")
    private var savedHandledIds by prefOption("moments_auto_comment_handled", emptySet<String>())

    private var aiEnabled by prefOption("moments_auto_comment_ai_enabled", false)
    private var aiApiKey by prefOption("moments_auto_comment_ai_api_key", "")
    private var aiBaseUrl by prefOption("moments_auto_comment_ai_base_url", "https://api.deepseek.com")
    private var aiModel by prefOption("moments_auto_comment_ai_model", "deepseek-flash")
    private var aiPersona by prefOption("moments_auto_comment_ai_persona", AutoCommentAi.DEFAULT_PERSONA)
    private var aiMaxImages by prefOption("moments_auto_comment_ai_max_images", 3)
    private var aiReplyToReplies by prefOption("moments_auto_comment_ai_reply_replies", true)

    private var savedAiCommentTexts by prefOption("moments_auto_comment_ai_texts", "")
    private var savedAiCommentHistory by prefOption("moments_auto_comment_ai_history", "")
    private var savedAiCommentIds by prefOption("moments_auto_comment_ai_comment_ids", "")
    private var savedAnsweredReplies by prefOption("moments_auto_comment_ai_answered", emptySet<String>())

    private val handledSnsIds = ConcurrentHashMap.newKeySet<String>()
    private val lastAttemptAt = ConcurrentHashMap<String, Long>()
    private val lastScanAt = ConcurrentHashMap<String, Long>()
    private val actionLock = Any()

    /** snsTableId -> the exact text of the latest comment we posted, used to recognise our rows. */
    private val aiCommentTexts = ConcurrentHashMap<String, String>()

    /**
     * snsTableId -> every text the AI has written on that post. Matching a stored comment row
     * against this set is how a parent comment is proven to be ours, so the check still works when
     * the server id was never observed while the host wrote the row.
     */
    private val aiCommentHistory = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * snsTableId -> svrIds of every comment the AI wrote on that post, including replies to
     * replies. Any incoming comment whose parent is in this set gets answered, so the exchange can
     * continue for as many rounds as the other side keeps replying.
     */
    private val aiCommentIds = ConcurrentHashMap<String, MutableSet<Long>>()

    private val answeredReplies = ConcurrentHashMap.newKeySet<String>()
    private val aiReplyLock = Any()

    @Volatile
    private var active = false

    @Volatile
    private var replyHookInstalled = false

    @Volatile
    private var replyPollThread: Thread? = null

    @Volatile
    private var lastActionSentAt = 0L

    override fun onEnable() {
        startAutomation()
        active = true
        handledSnsIds.clear()
        handledSnsIds.addAll(savedHandledIds)
        lastAttemptAt.clear()
        loadAiReplyState()
        WeMomentsApi.methodReplaceUserBySnsId.hookAfter {
            if (result != true) return@hookAfter
            val snsInfo = args[1]!!
            processSnsInfoAsync(snsInfo, "storage")
            // The host rewrites the whole post when comments sync, which is the only chance to see
            // replies on a post the user never opens again.
            scanStoredCommentReplies(snsInfo)
        }
        installReplyHook()
        installTimelineHooks()
        startReplyPolling()
    }

    override fun onDisable() {
        active = false
        stopAutomation()
    }

    /**
     * 主动轮询 `SnsComment` 表兜底：评论同步只写评论表、不一定重写 `SnsInfo`，单靠 hook 会漏掉
     * 别人对 AI 评论的回复。这里只读最近 7 天的评论行，再按 AI 评论过的 snsId 过滤。
     */
    private fun startReplyPolling() {
        if (replyPollThread?.isAlive == true) return
        replyPollThread = kotlin.concurrent.thread(
            name = "moments-ai-reply-poll",
            isDaemon = true,
        ) {
            while (active) {
                try {
                    Thread.sleep(REPLY_POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                if (!active) continue
                trimAgedState()
                if (!aiReplyToReplies) continue
                runCatching { pollStoredReplies() }
                    .onFailure { WeLogger.w(TAG, "failed to poll stored Moments replies", it) }
            }
        }
    }

    /**
     * 长驻进程里只增不减的状态会拖慢微信: 已处理/已扫描记录和 AI 评论历史都随时间累积。
     * 超过 7 天的朋友圈已经不在自动评论/回复的时间窗口内, 可以安全丢弃。
     */
    private fun trimAgedState() {
        val cutoff = System.currentTimeMillis() - MAX_REPLY_AGE_SECONDS * 1000
        lastAttemptAt.entries.removeAll { it.value < cutoff }
        lastScanAt.entries.removeAll { it.value < cutoff }
        val live = lastAttemptAt.keys
        aiCommentTexts.keys.retainAll(live)
        aiCommentHistory.keys.retainAll(live)
        aiCommentIds.keys.retainAll(live)
        if (answeredReplies.size > MAX_ANSWERED_REPLIES) answeredReplies.clear()
    }

    private fun pollStoredReplies() {
        if (!active || !aiReplyToReplies) return
        val knownPosts = synchronized(aiReplyLock) { aiCommentHistory.keys.toSet() }
        if (knownPosts.isEmpty()) return
        val minCreateTime = System.currentTimeMillis() / 1000 - MAX_REPLY_AGE_SECONDS
        WeMomentsApi.rawQuerySnsInfo(
            "SELECT snsID, commentSvrID, parentID, talker, isSend, curActionBuf, createTime " +
                "FROM SnsComment WHERE createTime > ? ORDER BY createTime DESC LIMIT $REPLY_POLL_LIMIT",
            arrayOf(minCreateTime.toString()),
        ).use { cursor ->
            val snsIndex = cursor.getColumnIndex("snsID")
            val idIndex = cursor.getColumnIndex("commentSvrID")
            val parentIndex = cursor.getColumnIndex("parentID")
            val talkerIndex = cursor.getColumnIndex("talker")
            val sendIndex = cursor.getColumnIndex("isSend")
            val bufIndex = cursor.getColumnIndex("curActionBuf")
            val timeIndex = cursor.getColumnIndex("createTime")
            if (snsIndex < 0 || idIndex < 0 || parentIndex < 0 || talkerIndex < 0 || sendIndex < 0 || bufIndex < 0) return
            while (cursor.moveToNext()) {
                val snsId = cursor.getLong(snsIndex)
                if (snsId.toString() !in knownPosts) continue
                val buf = cursor.getBlob(bufIndex) ?: continue
                val createdRaw = if (timeIndex >= 0) cursor.getLong(timeIndex) else 0L
                handleCommentRow(
                    snsId = snsId,
                    commentId = cursor.getLong(idIndex),
                    parentId = cursor.getLong(parentIndex),
                    talker = cursor.getString(talkerIndex).orEmpty(),
                    isSend = cursor.getInt(sendIndex) != 0,
                    text = runCatching {
                        SnsCommentActionProto.decode(buf).content.orEmpty()
                    }.getOrDefault(""),
                    createdSeconds = if (createdRaw > 1_000_000_000_000L) createdRaw / 1000 else createdRaw,
                )
            }
        }
    }

    /**
     * Comment rows reach us through three host paths, all feeding [handleCommentRow]:
     * - `SnsComment.convertTo()` when a row is written (our own sends reveal their server id, and
     *   an incoming reply arrives with its `parentID`);
     * - `SnsComment.convertFrom(Cursor)` when the host loads a post's comments;
     * - a `SnsComment` table scan whenever the post itself is rewritten.
     * Hooked once because the hook framework keeps them installed after the feature is switched
     * off; [active] gates the callback.
     */
    private fun installReplyHook() {
        if (replyHookInstalled) return
        replyHookInstalled = true
        WeMomentsApi.methodSnsCommentConvertTo.hookAfter {
            if (!active || !aiReplyToReplies) return@hookAfter
            runCatching { inspectComment(thisObject!!) }
                .onFailure { WeLogger.w(TAG, "failed to inspect written Moments comment", it) }
        }
        WeMomentsApi.methodSnsCommentConvertFromCursor.hookAfter {
            if (!active || !aiReplyToReplies) return@hookAfter
            runCatching { inspectComment(thisObject!!) }
                .onFailure { WeLogger.w(TAG, "failed to inspect loaded Moments comment", it) }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var textInput by remember { mutableStateOf(commentText) }
            var intervalInput by remember { mutableIntStateOf(intervalSeconds) }
            var browsingInput by remember { mutableStateOf(commentWhenBrowsing) }
            var whitelistInput by remember { mutableStateOf(whitelistRaw) }
            var blacklistInput by remember { mutableStateOf(blacklistRaw) }
            var aiEnabledInput by remember { mutableStateOf(aiEnabled) }
            var apiKeyInput by remember { mutableStateOf(aiApiKey) }
            var baseUrlInput by remember { mutableStateOf(aiBaseUrl) }
            var modelInput by remember { mutableStateOf(aiModel) }
            var personaInput by remember { mutableStateOf(aiPersona) }
            var maxImagesInput by remember { mutableIntStateOf(aiMaxImages) }
            var replyRepliesInput by remember { mutableStateOf(aiReplyToReplies) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_auto_comment_moments_name)) },
                text = {
                    SegmentedColumn(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.moments_auto_comment_ai_title),
                                description = stringResource(R.string.moments_auto_comment_ai_description),
                                checked = aiEnabledInput,
                                onCheckedChange = {
                                    aiEnabledInput = it
                                    aiEnabled = it
                                },
                            )
                        }
                        if (aiEnabledInput) {
                            item {
                                SwitchWidget(
                                    title = stringResource(R.string.moments_auto_comment_ai_reply_replies_title),
                                    description = stringResource(R.string.moments_auto_comment_ai_reply_replies_description),
                                    checked = replyRepliesInput,
                                    onCheckedChange = {
                                        replyRepliesInput = it
                                        aiReplyToReplies = it
                                    },
                                )
                            }
                            item {
                                TextFieldDialogWidget(
                                    title = stringResource(R.string.moments_auto_comment_ai_api_key_title),
                                    value = apiKeyInput,
                                    onValueChange = {
                                        apiKeyInput = it
                                        aiApiKey = it
                                    },
                                    dialogTitle = stringResource(R.string.moments_auto_comment_ai_api_key_title),
                                    confirmLabel = stringResource(R.string.dialog_confirm),
                                    dismissLabel = stringResource(R.string.dialog_cancel),
                                    valueHint = stringResource(R.string.moments_auto_comment_ai_api_key_hint),
                                    password = true,
                                )
                            }
                            item {
                                TextFieldDialogWidget(
                                    title = stringResource(R.string.moments_auto_comment_ai_base_url_title),
                                    value = baseUrlInput,
                                    onValueChange = {
                                        baseUrlInput = it
                                        aiBaseUrl = it
                                    },
                                    dialogTitle = stringResource(R.string.moments_auto_comment_ai_base_url_title),
                                    confirmLabel = stringResource(R.string.dialog_confirm),
                                    dismissLabel = stringResource(R.string.dialog_cancel),
                                    valueHint = stringResource(R.string.moments_auto_comment_ai_base_url_hint),
                                )
                            }
                            item {
                                TextFieldDialogWidget(
                                    title = stringResource(R.string.moments_auto_comment_ai_model_title),
                                    value = modelInput,
                                    onValueChange = {
                                        modelInput = it
                                        aiModel = it
                                    },
                                    dialogTitle = stringResource(R.string.moments_auto_comment_ai_model_title),
                                    confirmLabel = stringResource(R.string.dialog_confirm),
                                    dismissLabel = stringResource(R.string.dialog_cancel),
                                    valueHint = stringResource(R.string.moments_auto_comment_ai_model_hint),
                                )
                            }
                            item {
                                TextFieldDialogWidget(
                                    title = stringResource(R.string.moments_auto_comment_ai_persona_title),
                                    value = personaInput,
                                    onValueChange = {
                                        personaInput = it
                                        aiPersona = it
                                    },
                                    dialogTitle = stringResource(R.string.moments_auto_comment_ai_persona_title),
                                    confirmLabel = stringResource(R.string.dialog_confirm),
                                    dismissLabel = stringResource(R.string.dialog_cancel),
                                    valueHint = stringResource(R.string.moments_auto_comment_ai_persona_hint),
                                    singleLine = false,
                                )
                            }
                            item {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.moments_auto_comment_ai_max_images_title),
                                        value = maxImagesInput,
                                        startInt = 0,
                                        endInt = 9,
                                        stepSize = 1,
                                        valueSuffix = "",
                                        onValueChange = {
                                            maxImagesInput = it
                                            aiMaxImages = it
                                        },
                                    )
                                }
                            }
                            val report = AutoCommentAi.lastReport
                            if (report != null) {
                                item {
                                    BaseItemContainer {
                                        Text(
                                            text = stringResource(
                                                R.string.moments_auto_comment_ai_report_title,
                                            ) + "\n" + report,
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.moments_auto_comment_text_title),
                                value = textInput,
                                onValueChange = {
                                    textInput = it
                                    commentText = it
                                },
                                dialogTitle = stringResource(R.string.moments_auto_comment_text_title),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.moments_auto_comment_text_hint),
                            )
                        }
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    title = stringResource(R.string.moments_auto_comment_interval_title),
                                    value = intervalInput,
                                    startInt = 0,
                                    endInt = 60,
                                    stepSize = 1,
                                    valueSuffix = "s",
                                    onValueChange = {
                                        intervalInput = it
                                        intervalSeconds = it
                                    },
                                )
                            }
                        }
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.moments_auto_comment_browsing_title),
                                description = stringResource(R.string.moments_auto_comment_browsing_description),
                                checked = browsingInput,
                                onCheckedChange = {
                                    browsingInput = it
                                    commentWhenBrowsing = it
                                },
                            )
                        }
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.moments_auto_comment_whitelist_title),
                                value = whitelistInput,
                                onValueChange = {
                                    whitelistInput = it
                                    whitelistRaw = it
                                },
                                dialogTitle = stringResource(R.string.moments_auto_comment_whitelist_title),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.moments_auto_comment_list_hint),
                            )
                        }
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.moments_auto_comment_blacklist_title),
                                value = blacklistInput,
                                onValueChange = {
                                    blacklistInput = it
                                    blacklistRaw = it
                                },
                                dialogTitle = stringResource(R.string.moments_auto_comment_blacklist_title),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.moments_auto_comment_list_hint),
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    override fun processVisibleItems(list: ViewGroup) {
        if (!commentWhenBrowsing) return
        for (index in 0 until list.childCount) {
            runCatching {
                locateSnsInfo(list.getChildAt(index))?.let { processSnsInfoAsync(it, "visible") }
            }.onFailure {
                WeLogger.w(TAG, "failed to process visible Moments item", it)
            }
        }
    }

    private fun processSnsInfoAsync(snsInfo: Any, source: String) {
        val scope = captureAutomationScope() ?: return
        submitItemWork {
            runCatching { processSnsInfo(snsInfo, source, scope) }
                .onFailure { WeLogger.w(TAG, "auto-comment processing failed", it) }
        }
    }

    private fun processSnsInfo(snsInfo: Any, source: String, scope: AutomationScope) {
        if (!isAutomationCurrent(scope)) return

        val owner = WeMomentsApi.getOwnerWxId(snsInfo)?.trim().orEmpty()
        if (owner.isBlank() || owner == WeApi.selfWxId) return
        if (!aiEnabled && commentText.isBlank()) return
        if (isOwnerFiltered(owner)) return
        val createTimeSeconds = WeMomentsApi.getCreateTimeSeconds(snsInfo)
        if (createTimeSeconds <= scope.publishedAfterSeconds) return
        if (WeMomentsApi.isDeleted(snsInfo) || WeMomentsApi.isAd(snsInfo)) return

        val snsTableId = WeMomentsApi.getSnsTableId(snsInfo) ?: return
        val snsId = WeMomentsApi.getSnsIdLong(snsInfo)
        // 数字 snsId 是同一条朋友圈在各入口下唯一稳定的标识; table id 在不同入口可能不一致。
        val handledKey = snsId?.toString() ?: snsTableId
        // 作者 + 发布时间是另一种稳定标识, 即使两种 id 都拿不到同一形态也能命中同一条帖子。
        val postKey = "$owner:$createTimeSeconds"
        if (isIntercepted(snsInfo)) return
        if (handledKey in handledSnsIds || postKey in handledSnsIds || snsTableId in handledSnsIds) return
        if (!canAttempt(postKey)) return
        // 本地评论表是最权威的去重依据: 即使内存标记丢失(重启、清缓存、超过 500 条被淘汰),
        // 只要这条朋友圈下已经有自己发出的评论, 就绝不能再评论一次。
        if (snsId != null && hasOwnComment(snsId)) {
            rememberHandled(handledKey, postKey)
            return
        }

        val template = commentText.trim()
        val result = sendWithDelay(intervalSeconds * 1000L) {
            if (!isAutomationCurrent(scope)) {
                return@sendWithDelay WeMomentsApi.ActionResult(
                    success = true, sent = false, message = "automation stopped",
                )
            }
            if (handledKey in handledSnsIds || postKey in handledSnsIds || snsTableId in handledSnsIds) {
                return@sendWithDelay WeMomentsApi.ActionResult(
                    success = true, sent = false, message = "already handled",
                )
            }
            if (WeMomentsApi.isDeleted(snsInfo)) {
                return@sendWithDelay WeMomentsApi.ActionResult(
                    success = true, sent = false, message = "deleted/recalled",
                )
            }
            val generated = if (aiEnabled) {
                AutoCommentAi.generate(snsInfo = snsInfo, config = aiConfig())
            } else {
                null
            }
            // Generation waits on the CDN and the model, so the feature may have been switched off.
            if (!isAutomationCurrent(scope)) {
                return@sendWithDelay WeMomentsApi.ActionResult(
                    success = true, sent = false, message = "automation stopped",
                )
            }
            // 开启 AI 后生成失败(例如视频/图片无法识别)说明这条内容不适合评论,
            // 此时不再退回固定文案, 否则同一帖子会被反复刷上同一句固定评论。
            if (aiEnabled && aiApiKey.isNotBlank() && generated.isNullOrBlank()) {
                return@sendWithDelay WeMomentsApi.ActionResult(
                    success = true, sent = false, message = "AI comment generation failed",
                )
            }
            val text = generated?.takeIf { it.isNotBlank() } ?: template
            if (text.isBlank()) {
                return@sendWithDelay WeMomentsApi.ActionResult(
                    success = false, sent = false, message = "empty AI comment",
                )
            }
            // 先记账再发送: 宿主即使在发送后抛出异常(评论其实已经发出), 也不会在下一轮扫描
            // 再发一条同样的评论。
            rememberHandled(handledKey, postKey)
            val snsKey = snsId?.toString()
            if (snsKey != null) rememberAiComment(snsKey, text)
            val action = WeMomentsApi.comment(snsInfo, text)
            if (!action.sent && snsKey != null) forgetAiComment(snsKey, text)
            action
        }

        if (result.success) {
            WeLogger.i(TAG, "auto-comment $source sent=${result.sent}, owner=$owner, sns=$snsTableId")
        } else {
            val message = "auto-comment $source failed, owner=$owner, sns=$snsTableId, message=${result.message}"
            result.error?.let { WeLogger.w(TAG, message, it) } ?: WeLogger.w(TAG, message)
        }
    }

    private fun isOwnerFiltered(owner: String): Boolean {
        if (owner in parseWxIds(blacklistRaw)) return true
        val whitelist = parseWxIds(whitelistRaw)
        return whitelist.isNotEmpty() && owner !in whitelist
    }

    private fun parseWxIds(raw: String): Set<String> =
        raw.split(',', '，', ';', '；', '\n', '\r', ' ', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun rememberHandled(vararg snsIds: String) {
        var added = false
        snsIds.forEach { if (it.isNotBlank() && handledSnsIds.add(it)) added = true }
        if (!added) return
        if (handledSnsIds.size > MAX_HANDLED_IDS) {
            val iterator = handledSnsIds.iterator()
            while (handledSnsIds.size > MAX_HANDLED_IDS && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        savedHandledIds = handledSnsIds.toSet()
    }

    /**
     * 本地评论表里是否已有自己发出的评论, 用于跨重启/跨入口的最终去重。
     *
     * 先走权威列 (isSend / talker)。但在 8.0.78 上, 通过 sendComment 发出的评论
     * isSend 可能为 0、talker 也未必能命中自己, 仅靠列判断会在每次浏览 / 重启后
     * 把同一条朋友圈再评论一遍。因此再用本模块实际发出并持久化记住的评论内容做
     * 兜底比对: 解码 SnsComment.curActionBuf, 只要该动态下有一条内容是我们记过的
     * 评论, 就视为已经评论过, 绝不再发。
     */
    private fun hasOwnComment(snsId: Long): Boolean {
        val snsKey = snsId.toString()
        if (WeMomentsApi.rawQuerySnsInfo(
                "SELECT 1 FROM SnsComment WHERE snsID = ? AND (isSend = 1 OR talker = ?) LIMIT 1",
                arrayOf(snsKey, WeApi.selfWxId),
            ).use { it.moveToFirst() }
        ) return true
        if (!hasAiCommentHistory(snsKey)) return false
        return WeMomentsApi.rawQuerySnsInfo(
            "SELECT curActionBuf FROM SnsComment WHERE snsID = ?",
            arrayOf(snsKey),
        ).use { cursor ->
            val bufIndex = cursor.getColumnIndex("curActionBuf")
            if (bufIndex < 0) return@use false
            while (cursor.moveToNext()) {
                val buf = cursor.getBlob(bufIndex) ?: continue
                val content = runCatching { SnsCommentActionProto.decode(buf).content.orEmpty() }
                    .getOrDefault("")
                if (content.isNotEmpty() && isOurAiText(snsKey, content)) return@use true
            }
            false
        }
    }

    fun aiConfig() = AutoCommentAi.Config(
        apiKey = aiApiKey,
        baseUrl = aiBaseUrl,
        model = aiModel,
        persona = aiPersona,
        maxImages = aiMaxImages,
    )

    /** 固定评论内容, 供「一键评论」在关闭 AI 时使用。 */
    fun fixedCommentText(): String = commentText

    /** 记录别处(一键评论)发出的评论, 让 AI 回复仍能认出并继续回复。 */
    fun rememberPostedComment(snsKey: String, text: String) = rememberAiComment(snsKey, text)

    /** 记录已处理的朋友圈, 避免自动评论对同一条再发一次。 */
    fun markCommentHandled(snsTableId: String) = rememberHandled(snsTableId)

    /**
     * 清空 AI 评论 / AI 回复的持久化缓存及内存镜像。
     *
     * 这些标记保存在 KvStore(即微信内部存储)中, 清理后模块会忘记评论过哪些朋友圈、
     * 回复过哪些人, 相当于重新开始。
     */
    fun clearAiCaches() {
        synchronized(aiReplyLock) {
            aiCommentTexts.clear()
            aiCommentHistory.clear()
            aiCommentIds.clear()
            answeredReplies.clear()
            savedAiCommentIds = ""
            savedAnsweredReplies = emptySet()
            persistAiCommentState()
        }
        handledSnsIds.clear()
        lastAttemptAt.clear()
        savedHandledIds = emptySet()
        WeLogger.i(TAG, "cleared AI comment and reply caches")
    }

    // ==================== Replies to our own AI comment ====================

    /** Remembers the comment we posted so its own DB row can be recognised later. */
    private fun rememberAiComment(snsTableId: String, text: String) {
        val normalized = text.trim()
        if (normalized.isEmpty()) return
        synchronized(aiReplyLock) {
            aiCommentTexts[snsTableId] = normalized
            val texts = aiCommentHistory.getOrPut(snsTableId) { ConcurrentHashMap.newKeySet() }
            texts.add(normalized)
            while (texts.size > MAX_AI_TEXTS_PER_POST) {
                val iterator = texts.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
            trimAiCommentTexts()
            persistAiCommentState()
        }
    }

    private fun forgetAiComment(snsTableId: String, text: String) {
        val normalized = text.trim()
        synchronized(aiReplyLock) {
            if (aiCommentTexts[snsTableId] == normalized) aiCommentTexts.remove(snsTableId)
            aiCommentHistory[snsTableId]?.let { texts ->
                texts.remove(normalized)
                if (texts.isEmpty()) aiCommentHistory.remove(snsTableId)
            }
            persistAiCommentState()
        }
    }

    /** Caller must hold [aiReplyLock]. */
    private fun persistAiCommentState() {
        savedAiCommentTexts = DefaultJson.encodeToString(aiCommentTexts.toMap())
        savedAiCommentHistory = DefaultJson.encodeToString(aiCommentHistory.mapValues { it.value.toList() })
    }

    private fun isOurAiText(snsTableId: String, text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) return false
        if (aiCommentTexts[snsTableId]?.trim() == normalized) return true
        return aiCommentHistory[snsTableId]?.any { it.trim() == normalized } == true
    }

    private fun hasAiCommentHistory(snsTableId: String): Boolean =
        aiCommentTexts.containsKey(snsTableId) || aiCommentHistory.containsKey(snsTableId)

    private fun recordAiCommentId(snsTableId: String, commentSvrId: Long) {
        if (commentSvrId == 0L) return
        synchronized(aiReplyLock) {
            val ids = aiCommentIds.getOrPut(snsTableId) { ConcurrentHashMap.newKeySet() }
            if (!ids.add(commentSvrId)) return
            trimAiCommentIds()
            savedAiCommentIds = DefaultJson.encodeToString(aiCommentIds.mapValues { it.value.toList() })
        }
        WeLogger.i(TAG, "recorded our Moments comment id, sns=$snsTableId, id=$commentSvrId")
    }

    /** Extracts the fields of a comment entity the host just wrote or loaded. */
    private fun inspectComment(comment: Any) {
        val isSend = readCommentFlag(comment, "field_isSend") ?: return
        val snsId = readCommentLong(comment, "field_snsID").takeIf { it != 0L }
            ?: readCommentLong(comment, "field_snsId")
        if (snsId == 0L) return
        val createdRaw = readCommentLong(comment, "field_createTime")
        handleCommentRow(
            snsId = snsId,
            commentId = readCommentLong(comment, "field_commentSvrID"),
            parentId = readCommentLong(comment, "field_parentID"),
            talker = comment.reflekt().getField("field_talker", true) as? String ?: return,
            isSend = isSend,
            text = commentText(comment),
            createdSeconds = if (createdRaw > 1_000_000_000_000L) createdRaw / 1000 else createdRaw,
        )
    }

    private fun readCommentLong(comment: Any, name: String): Long =
        (comment.reflekt().getField(name, true) as? Number)?.toLong() ?: 0L

    private fun readCommentFlag(comment: Any, name: String): Boolean? =
        when (val value = comment.reflekt().getField(name, true)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> null
        }

    /**
     * Decides what a single comment row means for the AI conversation:
     * - one of our sent rows contributes its server id;
     * - an incoming reply is answered when its parent is a comment the AI wrote.
     */
    private fun handleCommentRow(
        snsId: Long,
        commentId: Long,
        parentId: Long,
        talker: String,
        isSend: Boolean,
        text: String,
        createdSeconds: Long,
    ) {
        val snsKey = snsId.toString()
        if (isSend) {
            // 自己发出的评论 isSend 已经能说明是谁, 不再强依赖 talker 字段。
            if (commentId == 0L) return
            if (!isOurAiText(snsKey, text)) return
            recordAiCommentId(snsKey, commentId)
            return
        }
        if (parentId == 0L || commentId == 0L) return
        if (talker.isBlank() || talker == WeApi.selfWxId) return
        if (text.isBlank()) return
        // Only posts the AI has commented on can have a reply aimed at it.
        if (!hasAiCommentHistory(snsKey) && parentId !in aiCommentIds[snsKey].orEmpty()) return
        if (createdSeconds > 0 &&
            System.currentTimeMillis() / 1000 - createdSeconds > MAX_REPLY_AGE_SECONDS
        ) return
        if ("$snsKey:$commentId" in answeredReplies) return
        // 同一条回复可能先以本地 id、后以服务端 id 出现两次; 用内容指纹兜底避免回复两遍。
        val contentKey = replyContentKey(snsKey, talker, createdSeconds, text)
        if (contentKey != null && contentKey in answeredReplies) return
        WeLogger.i(
            TAG,
            "incoming Moments reply, sns=$snsKey, comment=$commentId, parent=$parentId, talker=$talker",
        )
        submitItemWork { answerIncomingReply(snsId, commentId, parentId, talker, text, contentKey) }
    }

    private fun replyContentKey(snsKey: String, talker: String, createdSeconds: Long, text: String): String? {
        val normalized = text.trim()
        if (normalized.isEmpty() || createdSeconds <= 0L) return null
        return "$snsKey:content:$talker:$createdSeconds:$normalized"
    }

    private fun answerIncomingReply(
        snsId: Long,
        commentSvrId: Long,
        parentId: Long,
        talker: String,
        replyText: String,
        contentKey: String?,
    ) {
        val replyKey = "$snsId:$commentSvrId"
        runCatching {
            if (!answeredReplies.add(replyKey)) return@runCatching
            if (contentKey != null && !answeredReplies.add(contentKey)) return@runCatching
            // 父评论还在本地就精确判断是不是 AI 写的；父评论 id 对不上（自己的评论可能先以 0
            // 存库、之后才补服务端 id）时，退回“这条动态上 AI 评论过”的判断。
            val parent = lookupParentComment(snsId, parentId)
            val ourComment = parent.text ?: if (!parent.found) aiCommentTexts[snsId.toString()] else null
            if (ourComment == null) {
                // The parent row may still be in flight; a later trigger re-inspects this reply.
                answeredReplies.remove(replyKey)
                if (contentKey != null) answeredReplies.remove(contentKey)
                return@runCatching
            }
            replyToAiComment(snsId, commentSvrId, talker, replyText, replyKey, contentKey, ourComment)
        }.onFailure {
            answeredReplies.remove(replyKey)
            if (contentKey != null) answeredReplies.remove(contentKey)
            WeLogger.w(TAG, "failed to reply to an AI comment", it)
        }
    }

    private class ParentComment(val found: Boolean, val text: String?)

    /**
     * 查询被回复的父评论。`found=false` 表示本地查不到这一行（id 尚未同步），此时由调用方
     * 退回按动态级 AI 评论历史判断。
     */
    private fun lookupParentComment(snsId: Long, commentSvrId: Long): ParentComment {
        if (commentSvrId == 0L) return ParentComment(false, null)
        return runCatching {
            WeMomentsApi.rawQuerySnsInfo(
                "SELECT * FROM SnsComment WHERE snsID = ? AND commentSvrID = ?",
                arrayOf(snsId.toString(), commentSvrId.toString()),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use ParentComment(false, null)
                val talkerIndex = cursor.getColumnIndex("talker")
                val sendIndex = cursor.getColumnIndex("isSend")
                val bufIndex = cursor.getColumnIndex("curActionBuf")
                if (bufIndex < 0) return@use ParentComment(true, null)
                val isSend = sendIndex >= 0 && cursor.getInt(sendIndex) != 0
                val talker = if (talkerIndex >= 0) cursor.getString(talkerIndex).orEmpty() else ""
                val ours = isSend || talker == WeApi.selfWxId
                if (!ours) return@use ParentComment(true, null)
                val buf = cursor.getBlob(bufIndex) ?: return@use ParentComment(true, null)
                val text = SnsCommentActionProto.decode(buf).content.orEmpty()
                ParentComment(true, text.takeIf { isOurAiText(snsId.toString(), it) })
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to query SnsComment row, sns=$snsId, comment=$commentSvrId", it)
        }.getOrElse { ParentComment(false, null) }
    }

    /** Re-reads a post's stored comments when the host rewrites the post after syncing. */
    private fun scanStoredCommentReplies(snsInfo: Any) {
        val snsId = WeMomentsApi.getSnsIdLong(snsInfo) ?: return
        val snsKey = snsId.toString()
        if (!hasAiCommentHistory(snsKey)) return
        val now = System.currentTimeMillis()
        val last = lastScanAt[snsKey] ?: 0L
        if (now - last < SCAN_INTERVAL_MS) return
        if (lastScanAt.size > MAX_AI_COMMENT_META) lastScanAt.clear()
        lastScanAt[snsKey] = now
        submitItemWork {
            runCatching { scanStoredComments(snsId) }
                .onFailure { WeLogger.w(TAG, "failed to scan stored Moments comments", it) }
        }
    }

    private fun scanStoredComments(snsId: Long) {
        if (!active || !aiReplyToReplies) return
        WeMomentsApi.rawQuerySnsInfo(
            "SELECT * FROM SnsComment WHERE snsID = ?",
            arrayOf(snsId.toString()),
        ).use { cursor ->
            val idIndex = cursor.getColumnIndex("commentSvrID")
            val parentIndex = cursor.getColumnIndex("parentID")
            val talkerIndex = cursor.getColumnIndex("talker")
            val sendIndex = cursor.getColumnIndex("isSend")
            val bufIndex = cursor.getColumnIndex("curActionBuf")
            val timeIndex = cursor.getColumnIndex("createTime")
            if (idIndex < 0 || parentIndex < 0 || talkerIndex < 0 || sendIndex < 0 || bufIndex < 0) return
            while (cursor.moveToNext()) {
                val buf = cursor.getBlob(bufIndex) ?: continue
                val createdRaw = if (timeIndex >= 0) cursor.getLong(timeIndex) else 0L
                handleCommentRow(
                    snsId = snsId,
                    commentId = cursor.getLong(idIndex),
                    parentId = cursor.getLong(parentIndex),
                    talker = cursor.getString(talkerIndex).orEmpty(),
                    isSend = cursor.getInt(sendIndex) != 0,
                    text = runCatching {
                        SnsCommentActionProto.decode(buf).content.orEmpty()
                    }.getOrDefault(""),
                    createdSeconds = if (createdRaw > 1_000_000_000_000L) createdRaw / 1000 else createdRaw,
                )
            }
        }
    }

    private fun replyToAiComment(
        snsId: Long,
        commentSvrId: Long,
        talker: String,
        replyText: String,
        replyKey: String,
        contentKey: String?,
        ourComment: String,
    ) {
        val scope = captureAutomationScope()
        if (scope == null || !isAutomationCurrent(scope) || !aiEnabled || !aiReplyToReplies) {
            unmarkReply(replyKey, contentKey)
            return
        }
        val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId)
        if (snsInfo == null || WeMomentsApi.isDeleted(snsInfo)) {
            unmarkReply(replyKey, contentKey)
            return
        }
        // 本地评论表里已经有针对这条评论的回复时绝不再发(标记丢失、重复触发都不会造成二次回复)。
        if (hasSentReplyTo(snsId, commentSvrId)) {
            persistAnsweredReplies()
            return
        }
        val replyAuthor = runCatching { WeDatabaseApi.getDisplayName(talker) }.getOrDefault(talker)
        val generated = AutoCommentAi.generateReply(
            config = aiConfig(),
            postText = WeMomentsApi.getContentText(snsInfo).orEmpty(),
            ourComment = ourComment,
            replyAuthor = replyAuthor,
            replyText = replyText,
        )
        if (generated == null) {
            unmarkReply(replyKey, contentKey)
            WeLogger.w(TAG, "no AI reply generated, sns=$snsId")
            return
        }
        val snsKey = snsId.toString()
        // Remember the text before sending, so the host-written row of this reply is recognised and
        // its server id joins aiCommentIds — that is what allows the next round to be answered.
        rememberAiComment(snsKey, generated)
        val result = WeMomentsApi.comment(
            snsInfo = snsInfo,
            content = generated,
            replyCommentId = commentSvrId,
            replyUsername = talker,
        )
        if (result.sent) {
            persistAnsweredReplies()
            WeLogger.i(TAG, "AI replied to comment, sns=$snsId, talker=$talker")
        } else {
            // 请求已经发出, 即使宿主回报失败也保持已回复标记, 避免下一轮扫描再补一条。
            persistAnsweredReplies()
            WeLogger.w(TAG, "failed to send AI comment reply, sns=$snsId, message=${result.message}")
        }
    }

    private fun hasSentReplyTo(snsId: Long, parentCommentId: Long): Boolean =
        WeMomentsApi.rawQuerySnsInfo(
            "SELECT 1 FROM SnsComment WHERE snsID = ? AND parentID = ? AND (isSend = 1 OR talker = ?) LIMIT 1",
            arrayOf(snsId.toString(), parentCommentId.toString(), WeApi.selfWxId),
        ).use { it.moveToFirst() }

    private fun unmarkReply(replyKey: String, contentKey: String?) {
        answeredReplies.remove(replyKey)
        if (contentKey != null) answeredReplies.remove(contentKey)
    }

    private fun commentText(comment: Any): String {
        val buf = comment.reflekt().getField("field_curActionBuf", true) as? ByteArray ?: return ""
        return runCatching { SnsCommentActionProto.decode(buf).content.orEmpty() }.getOrDefault("")
    }

    private fun loadAiReplyState() {
        synchronized(aiReplyLock) {
            aiCommentTexts.clear()
            aiCommentHistory.clear()
            aiCommentIds.clear()
            answeredReplies.clear()
            runCatching { DefaultJson.decodeFromString<Map<String, String>>(savedAiCommentTexts) }
                .getOrNull()?.let(aiCommentTexts::putAll)
            runCatching { DefaultJson.decodeFromString<Map<String, List<String>>>(savedAiCommentHistory) }
                .getOrNull()
                ?.forEach { (key, texts) ->
                    aiCommentHistory[key] = ConcurrentHashMap.newKeySet<String>().apply { addAll(texts) }
                }
            // Rows recorded before the per-post text history existed still need to match.
            if (aiCommentHistory.isEmpty()) {
                aiCommentTexts.forEach { (key, text) ->
                    aiCommentHistory[key] = ConcurrentHashMap.newKeySet<String>().apply { add(text) }
                }
            }
            runCatching { DefaultJson.decodeFromString<Map<String, List<Long>>>(savedAiCommentIds) }
                .getOrNull()
                ?.forEach { (key, ids) -> aiCommentIds[key] = ConcurrentHashMap.newKeySet<Long>().apply { addAll(ids) } }
                ?: runCatching { DefaultJson.decodeFromString<Map<String, Long>>(savedAiCommentIds) }
                    .getOrNull()
                    ?.forEach { (key, id) -> aiCommentIds[key] = ConcurrentHashMap.newKeySet<Long>().apply { add(id) } }
            answeredReplies.addAll(savedAnsweredReplies)
        }
    }

    private fun trimAiCommentTexts() {
        val overflow = aiCommentTexts.size - MAX_AI_COMMENT_META
        if (overflow > 0) {
            aiCommentTexts.keys.asSequence().take(overflow).toList().forEach { aiCommentTexts.remove(it) }
        }
        val historyOverflow = aiCommentHistory.size - MAX_AI_COMMENT_META
        if (historyOverflow > 0) {
            aiCommentHistory.keys.asSequence().take(historyOverflow).toList()
                .forEach { aiCommentHistory.remove(it) }
        }
    }

    private fun trimAiCommentIds() {
        val overflow = aiCommentIds.size - MAX_AI_COMMENT_META
        if (overflow <= 0) return
        aiCommentIds.keys.asSequence().take(overflow).toList().forEach { aiCommentIds.remove(it) }
    }

    private fun persistAnsweredReplies() {
        if (answeredReplies.size > MAX_ANSWERED_REPLIES) {
            val iterator = answeredReplies.iterator()
            while (answeredReplies.size > MAX_ANSWERED_REPLIES && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        savedAnsweredReplies = answeredReplies.toSet()
    }

    private fun canAttempt(snsTableId: String): Boolean = synchronized(lastAttemptAt) {
        val now = System.currentTimeMillis()
        val last = lastAttemptAt[snsTableId] ?: 0L
        if (now - last < RETRY_INTERVAL_MS) return@synchronized false
        lastAttemptAt[snsTableId] = now
        true
    }

    private fun sendWithDelay(
        delay: Long,
        block: () -> WeMomentsApi.ActionResult,
    ): WeMomentsApi.ActionResult = synchronized(actionLock) {
        if (delay > 0L) {
            val wait = delay - (System.currentTimeMillis() - lastActionSentAt)
            if (wait > 0L) Thread.sleep(wait)
        }
        val result = block()
        if (result.sent) lastActionSentAt = System.currentTimeMillis()
        result
    }
}
