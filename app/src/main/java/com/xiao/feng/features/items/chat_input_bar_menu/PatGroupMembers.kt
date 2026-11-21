package com.xiao.feng.features.items.chat_input_bar_menu

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 输入栏「+」菜单里的「拍一拍群友」。
 *
 * 支持一次多选群友, 也可以设置延迟和次数做定时拍: 每次拍之前都会等待设定的秒数,
 * 因此拍多人多次时不会瞬间连发。长按菜单项可以查看并停止正在执行的任务。
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

    /** 用户可配置的最小间隔 (秒)。 */
    private const val MIN_DELAY_SECONDS = 8

    /** 用户可配置的最大次数。 */
    private const val MAX_TIMES = 100

    /** 上次使用的次数与延迟, 下次打开对话框时沿用。 */
    private var times: Int by prefOption(KEY_TIMES, 1)
    private var delaySeconds: Int by prefOption(KEY_DELAY, MIN_DELAY_SECONDS)

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private data class PatTask(
        val id: Long,
        val talkerName: String,
        val sent: Int,
        val total: Int,
    )

    private val taskLock = Any()
    private val tasks = LinkedHashMap<Long, PatTask>()
    private val taskJobs = HashMap<Long, Job>()
    private val tasksState = MutableStateFlow<List<PatTask>>(emptyList())

    private fun publishTasks() {
        synchronized(taskLock) { tasksState.value = tasks.values.toList() }
    }

    private fun updateTask(id: Long, transform: (PatTask) -> PatTask) {
        synchronized(taskLock) {
            val task = tasks[id] ?: return
            tasks[id] = transform(task)
        }
        publishTasks()
    }

    private fun removeTask(id: Long) {
        synchronized(taskLock) {
            tasks.remove(id)
            taskJobs.remove(id)
        }
        publishTasks()
    }

    private fun stopTask(id: Long) {
        val job = synchronized(taskLock) { taskJobs[id] }
        job?.cancel()
    }

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "pat_group_members",
                icon = MaterialSymbols.Outlined.Touch_app,
                label = localizedChatInputString(R.string.feature_pat_group_members_name),
                isSupported = { _, _ -> WeCurrentConversationApi.value.isGroupChatWxId },
                onClick = { context, _ -> showMemberSelector(context) },
                onLongClick = { context, _ -> showRunningTasks(context) },
            ),
        )
    }

    override fun onEnable() {
        WeChatInputBarMenuApi.addProvider(provider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
        synchronized(taskLock) {
            taskJobs.values.toList().forEach { it.cancel() }
        }
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
            var timesInput by remember { mutableIntStateOf(times.coerceIn(1, MAX_TIMES)) }
            var delayInput by remember {
                mutableIntStateOf(delaySeconds.coerceIn(MIN_DELAY_SECONDS, 600))
            }
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
                                endInt = MAX_TIMES,
                                stepSize = 1,
                                onValueChange = { timesInput = it },
                            )
                        }
                        BaseItemContainer {
                            IntNumberPickerWidget(
                                title = stringResource(R.string.pat_group_members_delay_title),
                                description = stringResource(R.string.pat_group_members_delay_description),
                                value = delayInput,
                                startInt = MIN_DELAY_SECONDS,
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

    private fun showRunningTasks(context: Context) {
        showComposeDialog(context) {
            val running by tasksState.collectAsState()
            AlertDialogContent(
                title = { Text(stringResource(R.string.pat_group_members_tasks_title)) },
                text = {
                    if (running.isEmpty()) {
                        Text(stringResource(R.string.pat_group_members_tasks_empty))
                    } else {
                        LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(running, key = { it.id }) { task ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(task.talkerName)
                                        Text(
                                            stringResource(
                                                R.string.pat_group_members_task_progress,
                                                task.sent,
                                                task.total,
                                            )
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { stopTask(task.id) }) {
                                        Text(stringResource(R.string.pat_group_members_task_stop))
                                    }
                                }
                            }
                        }
                    }
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
        val gapMillis = delaySeconds.coerceAtLeast(MIN_DELAY_SECONDS) * 1000L
        val taskId = System.nanoTime()
        val total = members.size * times
        synchronized(taskLock) {
            tasks[taskId] = PatTask(taskId, WeDatabaseApi.getDisplayName(talker), 0, total)
        }
        publishTasks()

        val job = scope.launch(start = CoroutineStart.LAZY) {
            var sent = 0
            try {
                delay(gapMillis)
                repeat(times) { round ->
                    members.forEachIndexed { index, member ->
                        if (round > 0 || index > 0) {
                            delay(maxOf(gapMillis, MIN_PAT_INTERVAL_MS))
                        }
                        if (WeMessageApi.sendPat(talker, member.wxId)) sent++
                        updateTask(taskId) { it.copy(sent = sent) }
                    }
                }
                showToastSuspend(localizedChatInputString(R.string.pat_group_members_finished, sent))
            } finally {
                removeTask(taskId)
            }
        }
        synchronized(taskLock) { taskJobs[taskId] = job }
        job.start()
    }
}
