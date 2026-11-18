package com.xiao.feng.features.items.contacts

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import com.xiao.feng.R
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeContactApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.models.WeContact
import com.xiao.feng.features.api.net.WePacketHelper
import com.xiao.feng.features.api.net.models.protobuf.BeforeTransferReqProto
import com.xiao.feng.features.api.net.models.protobuf.BeforeTransferRespProto
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.data.KvStore
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.DefaultColumn
import com.xiao.feng.ui.content.IconButton
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.BaseWidget
import com.xiao.feng.ui.content.m3.DropDownMenuWidget
import com.xiao.feng.ui.content.m3.DropdownOption
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.TextFieldDialogWidget
import com.xiao.feng.ui.content.m3.lazySegmentedItems
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.copyToClipboard
import com.xiao.feng.utils.android.showToast
import com.xiao.feng.utils.android.showToastSuspend
import com.xiao.feng.utils.formatEpoch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object DetectDeletedFriends : ClickableFeature() {

    override val technicalId = "检测单向删除好友"
    override val nameRes = R.string.feature_detect_deleted_friends_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_detect_deleted_friends_description

    override val noSwitchWidget = true

    private const val TAG = "DetectDeletedFriends"

    private enum class DetectionMode(val labelRes: Int) {
        BEFORE_TRANSFER(R.string.contacts_detect_mode_before_transfer),
        VERIFY_USER(R.string.contacts_detect_mode_verify_user),
    }

    private enum class AbnormalFriendStatus(val labelRes: Int) {
        BEFORE_TRANSFER_ABNORMAL(R.string.contacts_detect_status_transfer_abnormal),
        DELETED(R.string.contacts_detect_status_deleted),
        BLACKLISTED(R.string.contacts_detect_status_blacklisted),
        ACCOUNT_RESTRICTED(R.string.contacts_detect_status_account_restricted),
    }

    private data class AbnormalFriend(
        val status: AbnormalFriendStatus,
        val contact: WeContact,
    )

    private sealed interface DetectionOutcome {
        data object Normal : DetectionOutcome
        data class Abnormal(val friend: AbnormalFriend) : DetectionOutcome
        data object Failed : DetectionOutcome
        data object RateLimited : DetectionOutcome
    }

    private var detectionModeName by KvStore.prefOption(
        "detect_deleted_friends_mode",
        DetectionMode.BEFORE_TRANSFER.name,
    )
    private var requestDelaySeconds by KvStore.prefOption(
        "detect_deleted_friends_delay_seconds",
        "2",
    )
    private var applyDelaySeconds by KvStore.prefOption(
        "detect_deleted_friends_apply_delay_seconds",
        "1",
    )

    private sealed class DialogPhase {
        data object Idle : DialogPhase()
        data class Scanning(
            val completed: MutableIntState,
            val total: Int,
            val mode: DetectionMode,
            val requestDelayMillis: Long,
            val abnormalFriends: MutableList<AbnormalFriend> = mutableListOf(),
        ) : DialogPhase()

        data class Done(val friends: List<AbnormalFriend>) : DialogPhase()
        data class SelectTarget(
            val friends: List<AbnormalFriend>,
            val suggestedLabelName: String
        ) : DialogPhase()

        data class Applying(
            val friends: List<AbnormalFriend>,
            val target: ContactCollector.Target,
            val completed: MutableIntState,
            val total: Int
        ) : DialogPhase()

        data class ConfirmDelete(
            val allFriends: List<AbnormalFriend>,
            val targets: List<AbnormalFriend>
        ) : DialogPhase()

        data class Deleting(
            val allFriends: List<AbnormalFriend>,
            val targets: List<AbnormalFriend>,
            val completed: MutableIntState,
            val total: Int,
            val failed: MutableList<AbnormalFriend> = mutableListOf()
        ) : DialogPhase()
    }

    private suspend fun detectWithBeforeTransfer(contact: WeContact): DetectionOutcome =
        withTimeoutOrNull(20.seconds) {
            suspendCancellableCoroutine { cont ->
                WePacketHelper.sendCgi(
                    "/cgi-bin/mmpay-bin/beforetransfer",
                    2783,
                    0,
                    0,
                    BeforeTransferReqProto(userName = contact.wxId).encode(),
                ) {
                    onSuccess { bytes ->
                        try {
                            val realName = bytes
                                ?.let { BeforeTransferRespProto.decode(it) }
                                ?.maskedRealName
                            WeLogger.d(TAG, "realName=$realName")
                            val result = if (realName == null) {
                                DetectionOutcome.Abnormal(
                                    AbnormalFriend(
                                        AbnormalFriendStatus.BEFORE_TRANSFER_ABNORMAL,
                                        contact,
                                    )
                                )
                            } else {
                                DetectionOutcome.Normal
                            }
                            if (cont.isActive) cont.resume(result)
                        } catch (e: Throwable) {
                            WeLogger.e(TAG, "failed to decode before-transfer response for ${contact.wxId}", e)
                            if (cont.isActive) cont.resume(DetectionOutcome.Failed)
                        }
                    }

                    onFailure { errType, errCode, errMsg ->
                        WeLogger.w(TAG, "failed friend ${contact.wxId}: $errType, $errCode, $errMsg")
                        val result = if (
                            errType == 4 && errCode == -34 || errMsg.contains("操作过于频繁")
                        ) {
                            DetectionOutcome.RateLimited
                        } else {
                            DetectionOutcome.Failed
                        }
                        if (cont.isActive) cont.resume(result)
                    }
                }
            }
        } ?: DetectionOutcome.Failed

    private fun filterPositiveDecimal(input: String): String = buildString {
        var hasDecimalPoint = false
        input.forEach { char ->
            when {
                char.isDigit() -> append(char)
                char == '.' && !hasDecimalPoint -> {
                    if (isEmpty()) append('0')
                    append(char)
                    hasDecimalPoint = true
                }
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        val friends = WeDatabaseApi.getFriends().filter { c ->
            c.type != 2051 && c.type != 2049 && c.wxId != WeApi.selfWxId && c.wxId != "filehelper"
        }

        showComposeDialog(context) {
            var phase by remember { mutableStateOf<DialogPhase>(DialogPhase.Idle) }
            var availableTargets by remember { mutableStateOf<List<ContactCollector.Target>?>(null) }
            var selectedMode by remember { mutableStateOf(DetectionMode.valueOf(detectionModeName)) }
            var requestDelayInput by remember { mutableStateOf(requestDelaySeconds) }
            var applyDelayInput by remember { mutableStateOf(applyDelaySeconds) }
            var unresolvedCount by remember { mutableIntStateOf(0) }

            LaunchedEffect(phase) {
                if (phase is DialogPhase.Scanning) {
                    dialog.setCancelable(false)
                    val scanningPhase = phase as DialogPhase.Scanning
                    var rateLimited = false
                    for ((index, friend) in friends.withIndex()) {
                        if (phase !== scanningPhase) break

                        val outcome = when (scanningPhase.mode) {
                            DetectionMode.BEFORE_TRANSFER -> detectWithBeforeTransfer(friend)
                            DetectionMode.VERIFY_USER -> when (
                                val result = WeContactApi.probeRelationship(friend.wxId)
                            ) {
                                WeContactApi.RelationshipProbeResult.Normal -> DetectionOutcome.Normal
                                WeContactApi.RelationshipProbeResult.Deleted ->
                                    DetectionOutcome.Abnormal(
                                        AbnormalFriend(
                                            AbnormalFriendStatus.DELETED,
                                            friend,
                                        )
                                    )
                                WeContactApi.RelationshipProbeResult.Blacklisted ->
                                    DetectionOutcome.Abnormal(
                                        AbnormalFriend(AbnormalFriendStatus.BLACKLISTED, friend)
                                    )
                                is WeContactApi.RelationshipProbeResult.AccountRestricted ->
                                    DetectionOutcome.Abnormal(
                                        AbnormalFriend(
                                            AbnormalFriendStatus.ACCOUNT_RESTRICTED,
                                            friend,
                                        )
                                    )
                                is WeContactApi.RelationshipProbeResult.RateLimited -> {
                                    WeLogger.w(TAG, "verify-user scan rate limited: ${result.message}")
                                    DetectionOutcome.RateLimited
                                }
                                is WeContactApi.RelationshipProbeResult.Failed -> {
                                    WeLogger.w(
                                        TAG,
                                        "verify-user probe failed for ${friend.wxId}: " +
                                            "${result.errType}, ${result.errCode}, ${result.message}",
                                    )
                                    DetectionOutcome.Failed
                                }
                                WeContactApi.RelationshipProbeResult.Timeout -> {
                                    WeLogger.w(TAG, "verify-user probe timed out for ${friend.wxId}")
                                    DetectionOutcome.Failed
                                }
                            }
                        }
                        when (outcome) {
                            DetectionOutcome.Normal -> Unit
                            is DetectionOutcome.Abnormal ->
                                scanningPhase.abnormalFriends += outcome.friend
                            DetectionOutcome.Failed -> unresolvedCount++
                            DetectionOutcome.RateLimited -> {
                                unresolvedCount += friends.size - index
                                rateLimited = true
                            }
                        }
                        scanningPhase.completed.intValue++

                        if (rateLimited) break
                        if (index != friends.lastIndex) {
                            delay(scanningPhase.requestDelayMillis.milliseconds)
                        }
                    }

                    if (phase === scanningPhase) {
                        if (rateLimited) {
                            showToast(
                                context,
                                context.localizedContactsString(R.string.contacts_detect_rate_limited),
                            )
                        }
                        phase = DialogPhase.Done(scanningPhase.abnormalFriends.toList())
                        dialog.setCancelable(true)
                    }
                } else if (phase is DialogPhase.SelectTarget) {
                    dialog.setCancelable(true)
                    availableTargets = null
                    CoroutineScope(Dispatchers.IO).launch {
                        availableTargets = ContactCollector.loadTargets(
                            context = context,
                            includeLabels = true,
                            includeCollections = true,
                        )
                    }
                } else if (phase is DialogPhase.Applying) {
                    dialog.setCancelable(false)
                    CoroutineScope(Dispatchers.IO).launch {
                        val applyingPhase = phase as DialogPhase.Applying
                        // 标签不存在时先按名称创建 (需要等待服务器下发 id), 归拢/分组必须事先存在。
                        if (!ContactCollector.prepare(applyingPhase.target)) {
                            if (phase is DialogPhase.Applying) {
                                phase = DialogPhase.Done(applyingPhase.friends)
                                dialog.setCancelable(true)
                                showToastSuspend(
                                    context,
                                    context.localizedContactsString(
                                        R.string.contact_collection_prepare_failed,
                                        applyingPhase.target.name,
                                    ),
                                )
                            }
                            return@launch
                        }

                        val interval = ContactCollector.intervalMillis(applyDelaySeconds)
                        for ((index, abnormalFriend) in applyingPhase.friends.withIndex()) {
                            // detect whether user quitted halfway
                            if (phase !is DialogPhase.Applying) {
                                break
                            }

                            ContactCollector.apply(applyingPhase.target, abnormalFriend.contact.wxId)
                            applyingPhase.completed.intValue++
                            if (index != applyingPhase.friends.lastIndex && interval > 0) {
                                delay(interval)
                            }
                        }

                        if (phase is DialogPhase.Applying) {
                            phase = DialogPhase.Done(applyingPhase.friends)
                            dialog.setCancelable(true)
                            showToastSuspend(
                                context,
                                context.localizedContactsString(R.string.contacts_detect_marking_done),
                            )
                        }
                    }
                } else if (phase is DialogPhase.Deleting) {
                    dialog.setCancelable(false)
                    CoroutineScope(Dispatchers.IO).launch {
                        val deletingPhase = phase as DialogPhase.Deleting
                        val deleted = mutableSetOf<String>()
                        for (abnormalFriend in deletingPhase.targets) {
                            // detect whether user quitted halfway
                            if (phase !is DialogPhase.Deleting) {
                                break
                            }

                            val friend = abnormalFriend.contact
                            val ok = WeContactApi.deleteContact(friend.wxId)
                            if (ok) {
                                deleted += friend.wxId
                            } else {
                                synchronized(deletingPhase.failed) {
                                    deletingPhase.failed += abnormalFriend
                                }
                            }
                            deletingPhase.completed.intValue++
                            // seems like WeChat's server rate limits requests
                            delay(1.seconds)
                        }

                        if (phase is DialogPhase.Deleting) {
                            // drop successfully deleted friends from the result list
                            val remaining = deletingPhase.allFriends.filter {
                                it.contact.wxId !in deleted
                            }
                            val failedCount = synchronized(deletingPhase.failed) { deletingPhase.failed.size }
                            phase = DialogPhase.Done(remaining)
                            dialog.setCancelable(true)
                            showToastSuspend(
                                context,
                                context.localizedContactsQuantity(
                                    R.plurals.contacts_detect_delete_done,
                                    deleted.size,
                                    deleted.size,
                                    failedCount,
                                ),
                            )
                        }
                    }
                }
            }

            AlertDialogContent(
                title = {
                    Text(
                        text = stringResource(
                            if (phase is DialogPhase.Idle) R.string.contacts_detect_warning_title
                            else R.string.feature_detect_deleted_friends_name,
                        ),
                    )
                },
                text = {
                    when (phase) {
                        is DialogPhase.Idle -> DefaultColumn {
                            Text(text = stringResource(R.string.contacts_detect_warning_message))
                            SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                                item(key = "detection_mode") {
                                    DropDownMenuWidget(
                                        title = stringResource(R.string.contacts_detect_mode),
                                        description = null,
                                        value = selectedMode,
                                        options = DetectionMode.entries.map { mode ->
                                            DropdownOption(mode, stringResource(mode.labelRes))
                                        },
                                        onValueChange = { mode ->
                                            selectedMode = mode
                                            detectionModeName = mode.name
                                        },
                                    )
                                }
                                item(key = "request_delay") {
                                    TextFieldDialogWidget(
                                        title = stringResource(R.string.contacts_detect_request_delay),
                                        value = requestDelayInput,
                                        onValueChange = { input ->
                                            val value = input.toDoubleOrNull()
                                            if (value != null && value > 0.0 && value.isFinite()) {
                                                requestDelayInput = input
                                                requestDelaySeconds = input
                                            }
                                        },
                                        dialogTitle = stringResource(R.string.contacts_detect_request_delay),
                                        confirmLabel = stringResource(android.R.string.ok),
                                        dismissLabel = stringResource(android.R.string.cancel),
                                        keyboardType = KeyboardType.Decimal,
                                        filter = ::filterPositiveDecimal,
                                    )
                                }
                                item(key = "apply_delay") {
                                    TextFieldDialogWidget(
                                        title = stringResource(R.string.contacts_detect_apply_delay),
                                        value = applyDelayInput,
                                        onValueChange = { input ->
                                            val value = input.toDoubleOrNull()
                                            if (value != null && value >= 0.0 && value.isFinite()) {
                                                applyDelayInput = input
                                                applyDelaySeconds = input
                                            }
                                        },
                                        dialogTitle = stringResource(R.string.contacts_detect_apply_delay),
                                        confirmLabel = stringResource(android.R.string.ok),
                                        dismissLabel = stringResource(android.R.string.cancel),
                                        keyboardType = KeyboardType.Decimal,
                                        filter = ::filterPositiveDecimal,
                                    )
                                }
                            }
                        }

                        is DialogPhase.Scanning -> {
                            val completed by (phase as DialogPhase.Scanning).completed
                            val total = (phase as DialogPhase.Scanning).total
                            DefaultColumn {
                                Text(
                                    pluralStringResource(
                                        R.plurals.contacts_detect_scanning,
                                        total,
                                        completed,
                                        total,
                                    ),
                                )
                                LinearWavyProgressIndicator(progress = { completed.toFloat() / total })
                            }
                        }

                        is DialogPhase.Done -> {
                            val abnormalFriends = (phase as DialogPhase.Done).friends
                            DefaultColumn {
                                Text(
                                    pluralStringResource(
                                        R.plurals.contacts_detect_scan_done,
                                        abnormalFriends.size,
                                        abnormalFriends.size,
                                    ),
                                )
                                if (unresolvedCount > 0) {
                                    Text(
                                        pluralStringResource(
                                            R.plurals.contacts_detect_unresolved,
                                            unresolvedCount,
                                            unresolvedCount,
                                        )
                                    )
                                }
                            }
                            LazyColumn {
                                lazySegmentedItems(
                                    abnormalFriends,
                                    key = { it.contact.wxId },
                                ) { abnormalFriend ->
                                    val friend = abnormalFriend.contact
                                    BaseWidget(
                                        title = friend.displayName,
                                        description = listOf(
                                            stringResource(abnormalFriend.status.labelRes),
                                            stringResource(R.string.contacts_detect_nickname, friend.nickname),
                                            stringResource(R.string.contacts_detect_remark, friend.remarkName),
                                            stringResource(R.string.contacts_wechat_id_value, friend.wxId),
                                            stringResource(R.string.contacts_detect_wechat_number, friend.customWxId),
                                        ).joinToString("\n"),
                                        onClick = {
                                            WeApi.openContact(context, friend.wxId, WeApi.OpenContactDestination.HOMEPAGE)
                                        },
                                        trailingContent = {
                                            IconButton(onClick = {
                                                phase = DialogPhase.ConfirmDelete(
                                                    allFriends = abnormalFriends,
                                                    targets = listOf(abnormalFriend)
                                                )
                                            }) {
                                                Icon(
                                                    MaterialSymbols.Outlined.Delete,
                                                    contentDescription = stringResource(R.string.action_delete),
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        is DialogPhase.ConfirmDelete -> {
                            val confirmPhase = phase as DialogPhase.ConfirmDelete
                            Text(
                                pluralStringResource(
                                    R.plurals.contacts_detect_confirm_delete,
                                    confirmPhase.targets.size,
                                    confirmPhase.targets.size,
                                ),
                            )
                        }

                        is DialogPhase.Deleting -> {
                            val completed by (phase as DialogPhase.Deleting).completed
                            val total = (phase as DialogPhase.Deleting).total
                            DefaultColumn {
                                Text(
                                    pluralStringResource(
                                        R.plurals.contacts_detect_deleting,
                                        total,
                                        completed,
                                        total,
                                    ),
                                )
                                LinearWavyProgressIndicator(progress = { completed.toFloat() / total })
                            }
                        }

                        is DialogPhase.SelectTarget -> {
                            val selectPhase = phase as DialogPhase.SelectTarget
                            val targets = availableTargets
                            DefaultColumn {
                                Text(
                                    pluralStringResource(
                                        R.plurals.contacts_detect_select_label,
                                        selectPhase.friends.size,
                                        selectPhase.friends.size,
                                    ),
                                )
                                if (targets == null) {
                                    LinearWavyProgressIndicator()
                                } else {
                                    ContactCollectionTargetList(
                                        targets = targets,
                                        allowNewLabel = true,
                                        intervalInput = applyDelayInput,
                                        onIntervalChange = { input ->
                                            applyDelayInput = input
                                            applyDelaySeconds = input
                                        },
                                        onPick = { target ->
                                            phase = DialogPhase.Applying(
                                                friends = selectPhase.friends,
                                                target = target,
                                                completed = mutableIntStateOf(0),
                                                total = selectPhase.friends.size,
                                            )
                                        },
                                        newLabelDefault = selectPhase.suggestedLabelName,
                                    )
                                }
                            }
                        }

                        is DialogPhase.Applying -> {
                            val applyingPhase = phase as DialogPhase.Applying
                            val completed by applyingPhase.completed
                            val total = applyingPhase.total
                            DefaultColumn {
                                Text(
                                    pluralStringResource(
                                        R.plurals.contacts_detect_marking,
                                        total,
                                        applyingPhase.target.name,
                                        completed,
                                        total,
                                    ),
                                )
                                LinearWavyProgressIndicator(progress = { completed.toFloat() / total })
                            }
                        }
                    }
                },
                dismissButton = when (phase) {
                    is DialogPhase.Idle -> {
                        {
                            TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                        }
                    }

                    is DialogPhase.Scanning -> {
                        {
                            TextButton(onClick = {
                                val scanningPhase = phase as DialogPhase.Scanning
                                // display current snapshot immediately
                                unresolvedCount +=
                                    scanningPhase.total - scanningPhase.completed.intValue
                                val foundSoFar = scanningPhase.abnormalFriends.toList()
                                phase = DialogPhase.Done(foundSoFar)
                                dialog.setCancelable(true)
                            }) { Text(stringResource(R.string.contacts_detect_stop)) }
                        }
                    }

                    is DialogPhase.SelectTarget -> {
                        {
                            TextButton(onClick = {
                                phase = DialogPhase.Done((phase as DialogPhase.SelectTarget).friends)
                            }) { Text(stringResource(R.string.contacts_detect_back)) }
                        }
                    }

                    is DialogPhase.Applying -> {
                        {
                            TextButton(onClick = {
                                phase = DialogPhase.Done((phase as DialogPhase.Applying).friends)
                                dialog.setCancelable(true)
                            }) { Text(stringResource(R.string.contacts_detect_stop)) }
                        }
                    }

                    is DialogPhase.ConfirmDelete -> {
                        {
                            TextButton(onClick = {
                                phase = DialogPhase.Done((phase as DialogPhase.ConfirmDelete).allFriends)
                            }) { Text(stringResource(R.string.dialog_cancel)) }
                        }
                    }

                    is DialogPhase.Deleting -> {
                        {
                            TextButton(onClick = {
                                // stop the loop; the running coroutine won't transition once phase changes,
                                // so flip to Done here with friends not yet deleted left in place
                                val deletingPhase = phase as DialogPhase.Deleting
                                phase = DialogPhase.Done(deletingPhase.allFriends)
                                dialog.setCancelable(true)
                            }) { Text(stringResource(R.string.contacts_detect_stop)) }
                        }
                    }

                    is DialogPhase.Done -> null
                },
                confirmButton = when (phase) {
                    is DialogPhase.Idle -> {
                        {
                            Button(onClick = {
                                unresolvedCount = 0
                                val requestDelayMillis =
                                    (requestDelayInput.toDouble() * 1_000.0)
                                        .toLong()
                                        .coerceAtLeast(1L)
                                phase = DialogPhase.Scanning(
                                    completed = mutableIntStateOf(0),
                                    total = friends.size,
                                    mode = selectedMode,
                                    requestDelayMillis = requestDelayMillis,
                                )
                            })
                            { Text(stringResource(R.string.dialog_confirm)) }
                        }
                    }

                    is DialogPhase.Done -> {
                        {
                            val abnormalFriends = (phase as DialogPhase.Done).friends
                            if (abnormalFriends.isNotEmpty()) {
                                TextButton(onClick = {
                                    availableTargets = null
                                    phase = DialogPhase.SelectTarget(
                                        friends = abnormalFriends,
                                        suggestedLabelName = context.localizedContactsString(
                                            R.string.contacts_detect_suggested_label,
                                            formatEpoch(System.currentTimeMillis(), includeDate = true),
                                        ),
                                    )
                                }) { Text(stringResource(R.string.contacts_detect_collect)) }
                                TextButton(onClick = {
                                    phase = DialogPhase.ConfirmDelete(
                                        allFriends = abnormalFriends,
                                        targets = abnormalFriends
                                    )
                                }) { Text(stringResource(R.string.contacts_detect_delete_all)) }
                            }
                            Button(onClick = {
                                val text = abnormalFriends.joinToString("\n\n") { abnormalFriend ->
                                    val friend = abnormalFriend.contact
                                    buildString {
                                        appendLine(
                                            context.localizedContactsString(
                                                abnormalFriend.status.labelRes
                                            )
                                        )
                                        appendLine(context.localizedContactsString(R.string.contacts_detect_nickname, friend.nickname))
                                        appendLine(context.localizedContactsString(R.string.contacts_detect_remark, friend.remarkName))
                                        appendLine(context.localizedContactsString(R.string.contacts_wechat_id_value, friend.wxId))
                                        appendLine(context.localizedContactsString(R.string.contacts_detect_wechat_number, friend.customWxId))
                                    }
                                }
                                copyToClipboard(context, text)
                                showToast(context, context.localizedContactsString(R.string.contacts_copied))
                            }) { Text(stringResource(R.string.contacts_copy)) }
                        }
                    }

                    is DialogPhase.ConfirmDelete -> {
                        {
                            Button(onClick = {
                                val confirmPhase = phase as DialogPhase.ConfirmDelete
                                phase = DialogPhase.Deleting(
                                    allFriends = confirmPhase.allFriends,
                                    targets = confirmPhase.targets,
                                    completed = mutableIntStateOf(0),
                                    total = confirmPhase.targets.size
                                )
                            }) { Text(stringResource(R.string.action_delete)) }
                        }
                    }

                    else -> null
                }
            )
        }
    }
}
