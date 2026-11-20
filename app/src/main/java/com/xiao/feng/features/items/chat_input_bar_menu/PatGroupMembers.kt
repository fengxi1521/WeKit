package com.xiao.feng.features.items.chat_input_bar_menu

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Touch_app
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.api.core.models.WeContact
import com.xiao.feng.features.api.ui.WeChatInputBarMenuApi
import com.xiao.feng.features.api.ui.WeCurrentConversationApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.ContactsSelector
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.BaseItemContainer
import com.xiao.feng.ui.content.m3.IntNumberPickerWidget
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.showToast
import com.xiao.feng.utils.android.showToastSuspend
import com.xiao.feng.utils.strings.isGroupChatWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 输入栏「+」菜单里的「拍一拍群友」。
 *
 * 支持一次多选群友, 也可以设置延迟和次数做定时拍: 每次拍之前都会等待设定的秒数,
 * 因此拍多人多次时不会瞬间连发。
 */
object PatGroupMembers : SwitchFeature() {

    override val technicalId = "拍一拍群友"
    override val nameRes = R.string.feature_pat_group_members_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_pat_group_members_description

    private const val TAG = "PatGroupMembers"
    private const val KEY_TIMES = "pat_group_members_times"
    private const val KEY_DELAY = "pat_group_members_delay_seconds"

    /** 多次拍之间至少间隔这么久, 避免一次点击连发几十个拍一拍。 */
    private const val MIN_PAT_INTERVAL_MS = 1_000L

    /** 上次使用的次数与延迟, 下次打开对话框时沿用。 */
    private var times: Int by prefOption(KEY_TIMES, 1)
    private var delaySeconds: Int by prefOption(KEY_DELAY, 3)

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "pat_group_members",
                icon = MaterialSymbols.Outlined.Touch_app,
                label = localizedChatInputString(R.string.feature_pat_group_members_name),
                isSupported = { _, _ -> WeCurrentConversationApi.value.isGroupChatWxId },
                onClick = { context, _ -> showMemberSelector(context) },
            ),
        )
    }

    override fun onEnable() {
        WeChatInputBarMenuApi.addProvider(provider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    private fun showMemberSelector(context: Context) {
        val talker = WeCurrentConversationApi.value
        if (!talker.isGroupChatWxId) {
            showToast(context, localizedChatInputString(R.string.pat_group_members_group_only))
            return
        }
        val members = WeDatabaseApi.getGroupMembers(talker).filter { it.wxId != WeApi.selfWxId }
        if (members.isEmpty()) {
            showToast(context, localizedChatInputString(R.string.pat_group_members_empty))
            return
        }

        showComposeDialog(context) {
            ContactsSelector(
                title = stringResource(R.string.feature_pat_group_members_name),
                contacts = members,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedWxIds ->
                    if (selectedWxIds.isEmpty()) {
                        showToast(context, localizedChatInputString(R.string.pat_group_members_select_one))
                        return@ContactsSelector
                    }
                    onDismiss()
                    showScheduleDialog(context, talker, members.filter { it.wxId in selectedWxIds })
                },
            )
        }
    }

    private fun showScheduleDialog(context: Context, talker: String, members: List<WeContact>) {
        showComposeDialog(context) {
            var timesInput by remember { mutableIntStateOf(times) }
            var delayInput by remember { mutableIntStateOf(delaySeconds) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.pat_group_members_schedule_title)) },
                text = {
                    Column {
                        Text(
                            stringResource(
                                R.string.pat_group_members_selected_count,
                                members.size,
                            )
                        )
                        BaseItemContainer {
                            IntNumberPickerWidget(
                                title = stringResource(R.string.pat_group_members_times_title),
                                value = timesInput,
                                startInt = 1,
                                endInt = 20,
                                stepSize = 1,
                                onValueChange = { timesInput = it },
                            )
                        }
                        BaseItemContainer {
                            IntNumberPickerWidget(
                                title = stringResource(R.string.pat_group_members_delay_title),
                                description = stringResource(R.string.pat_group_members_delay_description),
                                value = delayInput,
                                startInt = 0,
                                endInt = 600,
                                stepSize = 1,
                                valueSuffix = stringResource(R.string.pat_group_members_seconds_suffix),
                                onValueChange = { delayInput = it },
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onDismiss()
                            times = timesInput
                            delaySeconds = delayInput
                            schedule(talker, members, timesInput, delayInput)
                        }
                    ) { Text(stringResource(R.string.pat_group_members_start)) }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun schedule(talker: String, members: List<WeContact>, times: Int, delaySeconds: Int) {
        WeLogger.i(
            TAG,
            "patting ${members.size} members x$times in $talker, delay=${delaySeconds}s",
        )
        val gapMillis = delaySeconds * 1000L
        scope.launch {
            if (gapMillis > 0) delay(gapMillis)
            var sent = 0
            repeat(times) { round ->
                members.forEachIndexed { index, member ->
                    if (round > 0 || index > 0) delay(maxOf(gapMillis, MIN_PAT_INTERVAL_MS))
                    if (WeMessageApi.sendPat(talker, member.wxId)) sent++
                }
            }
            showToastSuspend(localizedChatInputString(R.string.pat_group_members_finished, sent))
        }
    }
}
