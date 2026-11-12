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

    private val handledSnsIds = ConcurrentHashMap.newKeySet<String>()
    private val lastAttemptAt = ConcurrentHashMap<String, Long>()
    private val actionLock = Any()

    @Volatile
    private var lastActionSentAt = 0L

    override fun onEnable() {
        startAutomation()
        handledSnsIds.clear()
        handledSnsIds.addAll(savedHandledIds)
        lastAttemptAt.clear()
        WeMomentsApi.methodReplaceUserBySnsId.hookAfter {
            if (result == true) processSnsInfoAsync(args[1]!!, "storage")
        }
        installTimelineHooks()
    }

    override fun onDisable() {
        stopAutomation()
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
                AutoCommentAi.generate(
                    snsInfo = snsInfo,
                    config = AutoCommentAi.Config(
                        apiKey = aiApiKey,
                        baseUrl = aiBaseUrl,
                        model = aiModel,
                        persona = aiPersona,
                        maxImages = aiMaxImages,
                    ),
                )
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
            WeMomentsApi.comment(snsInfo, text)
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
