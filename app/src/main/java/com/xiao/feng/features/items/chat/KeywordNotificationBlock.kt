package com.xiao.feng.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiao.feng.R
import com.xiao.feng.data.KvStore
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.content.m3.TextFieldDialogWidget
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.TargetProcess
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.strings.stripWxId

/**
 * Suppresses WeChat's own notification for messages whose content matches a keyword.
 *
 * The hook runs inside the host notification pipeline, so the message is still stored and visible
 * in the chat window; only the notification is muted. Configuration is read directly from
 * [KvStore] on every notification because this feature also runs in the push process and a cached
 * preference would not observe changes written by the main process.
 */
object KeywordNotificationBlock : ClickableFeature(), IResolveDex {

    override val technicalId = "关键词屏蔽通知"
    override val nameRes = R.string.feature_keyword_notification_block_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_keyword_notification_block_description

    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.PUSH)

    private const val TAG = "KeywordNotificationBlock"
    private const val KEY_ENABLED = "keyword_notification_block_enabled"
    private const val KEY_KEYWORDS = "keyword_notification_block_keywords"

    private val methodDealNotify by dexMethod()

    override fun onEnable() {
        methodDealNotify.hookBefore(99) {
            if (!KvStore.getBoolOrDef(KEY_ENABLED, false)) return@hookBefore
            val content = args[2] as String
            if (!content.matchesBlockedKeyword()) return@hookBefore
            WeLogger.i(TAG, "suppressing keyword notification")
            result = null
        }
    }

    private fun String.matchesBlockedKeyword(): Boolean {
        val keywords = KvStore.getStringOrDef(KEY_KEYWORDS, "")
            .split(',', '，', '\n', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (keywords.isEmpty()) return false
        val stripped = stripWxId()
        return keywords.any { keyword ->
            contains(keyword, ignoreCase = true) || stripped.contains(keyword, ignoreCase = true)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var enabledInput by remember {
                mutableStateOf(KvStore.getBoolOrDef(KEY_ENABLED, false))
            }
            var keywordsInput by remember {
                mutableStateOf(KvStore.getStringOrDef(KEY_KEYWORDS, ""))
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_keyword_notification_block_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_keyword_notify_block_enable_title),
                                checked = enabledInput,
                                onCheckedChange = {
                                    enabledInput = it
                                    KvStore.putBool(KEY_ENABLED, it)
                                },
                            )
                        }
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.chat_keyword_notify_block_keywords_title),
                                value = keywordsInput,
                                onValueChange = {
                                    keywordsInput = it
                                    KvStore.putString(KEY_KEYWORDS, it)
                                },
                                dialogTitle = stringResource(R.string.chat_keyword_notify_block_keywords_title),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.chat_keyword_notify_block_keywords_hint),
                                enabled = enabledInput,
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
}
