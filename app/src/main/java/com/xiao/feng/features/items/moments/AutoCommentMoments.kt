package com.xiao.feng.features.items.moments

import android.content.ContentValues
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
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
 * The trigger is the host's own `SnsInfo` insert, so the feature does not need the timeline UI to
 * be open. Posts older than the moment the feature was enabled are never touched, and every
 * commented post is remembered so a restart does not comment twice.
 */
object AutoCommentMoments : AutoMomentsBase(), WeDatabaseListenerApi.IInsertListener {

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
    private var savedHandledIds by prefOption("moments_auto_comment_handled", emptySet<String>())

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
        WeDatabaseListenerApi.addListener(this)
        installTimelineHooks()
    }

    override fun onDisable() {
        stopAutomation()
        WeDatabaseListenerApi.removeListener(this)
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var textInput by remember { mutableStateOf(commentText) }
            var intervalInput by remember { mutableIntStateOf(intervalSeconds) }
            var browsingInput by remember { mutableStateOf(commentWhenBrowsing) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_auto_comment_moments_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
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
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "SnsInfo") return
        if (commentText.isBlank()) return

        val owner = values.getAsString("userName")?.trim().orEmpty()
        if (owner == WeApi.selfWxId) return

        val sourceType = values.getAsInteger("sourceType") ?: 0
        if (sourceType and WeMomentsApi.ACTIVE_SOURCE_MASK == 0 ||
            sourceType and WeMomentsApi.AD_SOURCE_FLAG != 0
        ) {
            return
        }

        val snsId = values.getAsLong("snsId") ?: return
        val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: return
        processSnsInfoAsync(snsInfo, "database")
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
        if (commentText.isBlank()) return
        if (WeMomentsApi.getCreateTimeSeconds(snsInfo) <= scope.publishedAfterSeconds) return
        if (WeMomentsApi.isDeleted(snsInfo)) return

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
            WeMomentsApi.comment(snsInfo, template)
        }

        if (result.success) {
            if (result.sent) rememberHandled(snsTableId)
            WeLogger.i(TAG, "auto-comment $source sent=${result.sent}, owner=$owner, sns=$snsTableId")
        } else {
            val message = "auto-comment $source failed, owner=$owner, sns=$snsTableId, message=${result.message}"
            result.error?.let { WeLogger.w(TAG, message, it) } ?: WeLogger.w(TAG, message)
        }
    }

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
