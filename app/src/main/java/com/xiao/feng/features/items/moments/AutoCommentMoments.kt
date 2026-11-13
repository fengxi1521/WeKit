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
    private const val MAX_ANSWERED_REPLIES = 500
    private const val MAX_REPLY_AGE_SECONDS = 7L * 24 * 3600

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
    private var savedAiCommentIds by prefOption("moments_auto_comment_ai_comment_ids", "")
    private var savedAnsweredReplies by prefOption("moments_auto_comment_ai_answered", emptySet<String>())

    private val handledSnsIds = ConcurrentHashMap.newKeySet<String>()
    private val lastAttemptAt = ConcurrentHashMap<String, Long>()
    private val actionLock = Any()

    /** snsTableId -> the exact text of the latest comment we posted, used to recognise our rows. */
    private val aiCommentTexts = ConcurrentHashMap<String, String>()

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
    private var lastActionSentAt = 0L

    override fun onEnable() {
        startAutomation()
        active = true
        handledSnsIds.clear()
        handledSnsIds.addAll(savedHandledIds)
        lastAttemptAt.clear()
        loadAiReplyState()
        WeMomentsApi.methodReplaceUserBySnsId.hookAfter {
            if (result == true) processSnsInfoAsync(args[1]!!, "storage")
        }
        installReplyHook()
        installTimelineHooks()
    }

    override fun onDisable() {
        active = false
        stopAutomation()
    }

    /**
     * `SnsComment.convertTo()` runs whenever the host writes a comment row — both our own sent
     * comments (which reveals their server id) and incoming replies. Hooked once because the hook
     * framework keeps it installed after the feature is switched off; [active] gates the callback.
     */
    private fun installReplyHook() {
        if (replyHookInstalled) return
        replyHookInstalled = true
        WeMomentsApi.methodSnsCommentConvertTo.hookAfter {
            if (!active || !aiReplyToReplies) return@hookAfter
            runCatching { onCommentWritten(thisObject!!) }
                .onFailure { WeLogger.w(TAG, "failed to inspect written Moments comment", it) }
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
        if (WeMomentsApi.getCreateTimeSeconds(snsInfo) <= scope.publishedAfterSeconds) return
        if (WeMomentsApi.isDeleted(snsInfo) || WeMomentsApi.isAd(snsInfo)) return

        val snsTableId = WeMomentsApi.getSnsTableId(snsInfo) ?: return
        if (isIntercepted(snsInfo) || snsTableId in handledSnsIds) return
        if (!canAttempt(snsTableId)) return

        val template = commentText.trim()
        val result = sendWithDelay(intervalSeconds * 1000L) {
            if (!isAutomationCurrent(scope)) {
                return@sendWithDelay WeMomentsApi.ActionResult(
                    success = true, sent = false, message = "automation stopped",
                )
            }
            if (snsTableId in handledSnsIds) {
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
            val text = generated?.takeIf { it.isNotBlank() } ?: template
            if (text.isBlank()) {
                return@sendWithDelay WeMomentsApi.ActionResult(
                    success = false, sent = false, message = "empty AI comment",
                )
            }
            // Remember the text before sending: the host may write the comment row synchronously
            // inside comment(), and that write is what reveals the comment's server id.
            val snsKey = WeMomentsApi.getSnsIdLong(snsInfo)?.toString()
            if (snsKey != null) rememberAiComment(snsKey, text)
            val action = WeMomentsApi.comment(snsInfo, text)
            if (!action.sent && snsKey != null) forgetAiComment(snsKey)
            action
        }

        if (result.success) {
            if (result.sent) rememberHandled(snsTableId)
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

    private fun rememberHandled(snsTableId: String) {
        if (!handledSnsIds.add(snsTableId)) return
        if (handledSnsIds.size > MAX_HANDLED_IDS) {
            val iterator = handledSnsIds.iterator()
            while (handledSnsIds.size > MAX_HANDLED_IDS && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        savedHandledIds = handledSnsIds.toSet()
    }

    private fun aiConfig() = AutoCommentAi.Config(
        apiKey = aiApiKey,
        baseUrl = aiBaseUrl,
        model = aiModel,
        persona = aiPersona,
        maxImages = aiMaxImages,
    )

    // ==================== Replies to our own AI comment ====================

    /** Remembers the comment we posted so its own DB row can be recognised later. */
    private fun rememberAiComment(snsTableId: String, text: String) {
        synchronized(aiReplyLock) {
            aiCommentTexts[snsTableId] = text
            trimAiCommentTexts()
            savedAiCommentTexts = DefaultJson.encodeToString(aiCommentTexts.toMap())
        }
    }

    private fun forgetAiComment(snsTableId: String) {
        synchronized(aiReplyLock) {
            if (aiCommentTexts.remove(snsTableId) == null) return
            savedAiCommentTexts = DefaultJson.encodeToString(aiCommentTexts.toMap())
        }
    }

    /**
     * Called for every comment row the host writes:
     * - our own sent row reveals the server id of the AI comment (`field_commentSvrID`);
     * - an incoming reply carries `field_parentID`, so a match with that id is a reply to it.
     */
    private fun onCommentWritten(comment: Any) {
        val isSend = comment.reflekt().getField("field_isSend", true) as? Boolean ?: return
        val snsId = (comment.reflekt().getField("field_snsID", true) as? Number)?.toLong() ?: return
        if (snsId == 0L) return
        val commentId = (comment.reflekt().getField("field_commentSvrID", true) as? Number)?.toLong() ?: 0L
        val talker = comment.reflekt().getField("field_talker", true) as? String ?: return
        val snsKey = snsId.toString()

        if (isSend) {
            if (commentId == 0L || talker != WeApi.selfWxId) return
            val expected = aiCommentTexts[snsKey] ?: return
            if (commentText(comment).trim() != expected.trim()) return
            synchronized(aiReplyLock) {
                val ids = aiCommentIds.getOrPut(snsKey) { ConcurrentHashMap.newKeySet() }
                if (ids.add(commentId)) {
                    trimAiCommentIds()
                    savedAiCommentIds = DefaultJson.encodeToString(
                        aiCommentIds.mapValues { it.value.toList() },
                    )
                }
            }
            return
        }

        val parentId = (comment.reflekt().getField("field_parentID", true) as? Number)?.toLong() ?: 0L
        if (parentId == 0L || commentId == 0L) return
        if (talker.isBlank() || talker == WeApi.selfWxId) return
        // Matches both a direct reply to the root AI comment and a reply to one of the AI's
        // follow-up replies, so the conversation keeps going as long as the other side does.
        if (parentId !in aiCommentIds[snsKey].orEmpty()) return
        val createdRaw = (comment.reflekt().getField("field_createTime", true) as? Number)?.toLong() ?: 0L
        val createdSeconds = if (createdRaw > 1_000_000_000_000L) createdRaw / 1000 else createdRaw
        if (createdSeconds > 0 && System.currentTimeMillis() / 1000 - createdSeconds > MAX_REPLY_AGE_SECONDS) return
        val replyText = commentText(comment)
        if (replyText.isBlank()) return
        val replyKey = "$snsKey:$commentId"
        if (!answeredReplies.add(replyKey)) return

        submitItemWork {
            runCatching { replyToAiComment(snsId, commentId, talker, replyText, replyKey) }
                .onFailure {
                    answeredReplies.remove(replyKey)
                    WeLogger.w(TAG, "failed to reply to an AI comment", it)
                }
        }
    }

    private fun replyToAiComment(
        snsId: Long,
        commentSvrId: Long,
        talker: String,
        replyText: String,
        replyKey: String,
    ) {
        val scope = captureAutomationScope()
        if (scope == null || !isAutomationCurrent(scope) || !aiEnabled || !aiReplyToReplies) {
            answeredReplies.remove(replyKey)
            return
        }
        val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId)
        if (snsInfo == null || WeMomentsApi.isDeleted(snsInfo)) {
            answeredReplies.remove(replyKey)
            return
        }
        val replyAuthor = runCatching { WeDatabaseApi.getDisplayName(talker) }.getOrDefault(talker)
        val generated = AutoCommentAi.generateReply(
            config = aiConfig(),
            postText = WeMomentsApi.getContentText(snsInfo).orEmpty(),
            ourComment = aiCommentTexts[snsId.toString()].orEmpty(),
            replyAuthor = replyAuthor,
            replyText = replyText,
        )
        if (generated == null) {
            answeredReplies.remove(replyKey)
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
            answeredReplies.remove(replyKey)
            WeLogger.w(TAG, "failed to send AI comment reply, sns=$snsId, message=${result.message}")
        }
    }

    private fun commentText(comment: Any): String {
        val buf = comment.reflekt().getField("field_curActionBuf", true) as? ByteArray ?: return ""
        return runCatching { SnsCommentActionProto.decode(buf).content.orEmpty() }.getOrDefault("")
    }

    private fun loadAiReplyState() {
        synchronized(aiReplyLock) {
            aiCommentTexts.clear()
            aiCommentIds.clear()
            answeredReplies.clear()
            runCatching { DefaultJson.decodeFromString<Map<String, String>>(savedAiCommentTexts) }
                .getOrNull()?.let(aiCommentTexts::putAll)
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
        if (overflow <= 0) return
        aiCommentTexts.keys.asSequence().take(overflow).toList().forEach { aiCommentTexts.remove(it) }
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
