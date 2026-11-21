package com.xiao.feng.features.items.chat_input_bar_menu

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Alternate_email
import com.xiao.feng.R
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.api.net.WePacketHelper
import com.xiao.feng.features.api.net.models.protobuf.NewSendMsgItemProto
import com.xiao.feng.features.api.net.models.protobuf.NewSendMsgReqProto
import com.xiao.feng.features.api.net.models.protobuf.UserNameProto
import com.xiao.feng.features.api.net.models.protobuf.WeProto
import com.xiao.feng.features.api.ui.WeChatInputBarMenuApi
import com.xiao.feng.features.api.ui.WeCurrentConversationApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.i18n.LocalXiaoFengLocalizedContext
import com.xiao.feng.data.KvStore
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.ContactsSelector
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.android.runOnUiThread
import com.xiao.feng.utils.android.showToast
import com.xiao.feng.utils.strings.isGroupChatWxId

/**
 * @所有人 (含隐蔽@模式)。
 *
 * 隐蔽@模式: 走与普通@相同的 `newSendMsg` 直连请求, 区别是 content 保持用户原文,
 * 只把 `<atuserlist><![CDATA[wxid1,wxid2,...]]></atuserlist>` (全选时为 notify@all)
 * 放进 msgsource。微信服务器只看 atuserlist 里的成员/notify@all 推送"有人@我"提醒,
 * 不要求 content 中存在 @ 文本, 因此接收方气泡内看不到任何 @ 痕迹。
 *
 * 早前实现把 msgSource 交给宿主发送场景 `NetSceneSendMsg` 的构造参数, 但宿主在
 * 组装请求时会重建 msgsource, 传进去的 atuserlist 会被丢弃; 因此改为直接构造请求。
 */
object MentionMembers : SwitchFeature() {

    override val technicalId = "@所有人"
    override val nameRes = R.string.feature_mention_members_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_mention_members_description

    /** 微信服务器对 atuserlist 人数的上限, 超出部分静默截断 */
    private const val MAX_AT_USERS = 200

    private var stealthMentionAll by KvStore.prefOption("mention_members_stealth_all", false)

    private fun showSettingsDialog(context: Context) {
        showComposeDialog(context) {
            var stealthState by remember { mutableStateOf(stealthMentionAll) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.mention_members_settings_title)) },
                text = {
                    SwitchWidget(
                        title = stringResource(R.string.mention_members_stealth_label),
                        description = stringResource(R.string.mention_members_stealth_description),
                        checked = stealthState,
                        onCheckedChange = {
                            stealthState = it
                            stealthMentionAll = it
                        },
                    )
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                }
            )
        }
    }

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "mention_members",
                icon = MaterialSymbols.Outlined.Alternate_email,
                label = localizedChatInputString(R.string.mention_members_action_label),
                isSupported = { _, _ ->
                    WeCurrentConversationApi.value.isGroupChatWxId
                },
                onClick = { context, chatFooter ->
                    val currentConv = WeCurrentConversationApi.value
                    if (!currentConv.isGroupChatWxId) {
                        showToast(
                            context,
                            context.localizedChatInputString(R.string.mention_members_group_only),
                        )
                        return@ActionItem
                    }

                    if (stealthMentionAll && chatFooter.lastText.isEmpty()) {
                        showToast(
                            context,
                            context.localizedChatInputString(R.string.mention_members_input_empty),
                        )
                        return@ActionItem
                    }

                    val allMembers = WeDatabaseApi
                        .getGroupMembers(currentConv)
                        .filter { c -> c.wxId != WeApi.selfWxId }

                    if (allMembers.isEmpty()) {
                        showToast(
                            context,
                            context.localizedChatInputString(R.string.mention_members_empty_group),
                        )
                        return@ActionItem
                    }

                    showComposeDialog(context) {
                        val dialogContext = LocalContext.current
                        val localizedContext = LocalXiaoFengLocalizedContext.current
                        ContactsSelector(
                            title = stringResource(R.string.feature_mention_members_name),
                            contacts = allMembers,
                            initialSelectedWxIds = allMembers.map { it.wxId }.toSet(),
                            onDismiss = onDismiss,
                            onConfirm = { selectedWxIds ->
                                if (selectedWxIds.isEmpty()) {
                                    showToast(
                                        dialogContext,
                                        localizedContext.localizedChatInputString(
                                            R.string.mention_members_select_one,
                                        ),
                                    )
                                    return@ContactsSelector
                                }

                                onDismiss()

                                val selectedContacts = allMembers.filter { it.wxId in selectedWxIds }
                                val isStealth = stealthMentionAll
                                val content = chatFooter.lastText

                                // 全选用 notify@all; 部分选择列出 wxid。
                                val atWxIds = if (selectedContacts.size == allMembers.size) {
                                    "notify@all"
                                } else {
                                    selectedContacts
                                        .map { it.wxId }
                                        .filter { it != "weixin" && it != "filehelper" }
                                        .take(MAX_AT_USERS)
                                        .joinToString(",")
                                }

                                // 隐蔽@: 只把 atuserlist 放进 msgsource, content 保持用户原文,
                                // 接收方气泡里看不到任何 @ 痕迹, 但服务器仍会按 atuserlist 提醒。
                                val outgoingContent = if (isStealth) {
                                    content
                                } else {
                                    selectedContacts.joinToString("") { "@${it.nickname} " } + content
                                }

                                val item = NewSendMsgItemProto(
                                    toUser = UserNameProto(currentConv),
                                    content = outgoingContent,
                                    type = 1,
                                    msgSource = """<msgsource><atuserlist><![CDATA[$atWxIds]]></atuserlist><pua>1</pua><alnode><cf>5</cf><inlenlist>73</inlenlist></alnode><eggIncluded>1</eggIncluded></msgsource>"""
                                )
                                val reqProto = NewSendMsgReqProto(
                                    count = 1,
                                    items = listOf(item)
                                )
                                val reqBytes = WeProto.encodeWithDefaults(reqProto)

                                WePacketHelper.sendCgi(
                                    "/cgi-bin/micromsg-bin/newsendmsg",
                                    522,
                                    0,
                                    0,
                                    reqBytes = reqBytes
                                ) {
                                    onSuccess { _ ->
                                        val now = System.currentTimeMillis()
                                        if (isStealth) {
                                            showToast(
                                                context,
                                                context.localizedChatInputString(R.string.mention_members_sent_unseen),
                                            )
                                            WeMessageApi.createSimpleMsgInfoAndInsert(
                                                1,
                                                currentConv,
                                                content,
                                                now,
                                                isSend = 1,
                                            )
                                        } else {
                                            showToast(
                                                context,
                                                context.localizedChatInputString(R.string.mention_members_sent_unseen),
                                            )
                                            WeMessageApi.createSimpleMsgInfoAndInsert(
                                                10000,
                                                currentConv,
                                                context.localizedChatInputQuantity(
                                                    R.plurals.mention_members_message_count,
                                                    selectedContacts.size,
                                                    selectedContacts.size,
                                                ),
                                                now
                                            )
                                        }
                                        chatFooter.lastText = ""
                                    }
                                }
                            }
                        )
                    }
                },
                onLongClick = { context, _ ->
                    runOnUiThread {
                        showSettingsDialog(context)
                    }
                }
            )
        )
    }

    override fun onEnable() {
        WeChatInputBarMenuApi.addProvider(provider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }
}
