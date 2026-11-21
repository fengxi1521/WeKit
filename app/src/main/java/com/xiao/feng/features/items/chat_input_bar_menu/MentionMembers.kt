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
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.api.core.models.MessageInfo
import com.xiao.feng.features.api.net.WePacketHelper
import com.xiao.feng.features.api.net.models.protobuf.NewSendMsgItemProto
import com.xiao.feng.features.api.net.models.protobuf.NewSendMsgReqProto
import com.xiao.feng.features.api.net.models.protobuf.UserNameProto
import com.xiao.feng.features.api.net.models.protobuf.WeProto
import com.xiao.feng.features.api.ui.WeChatInputBarMenuApi
import com.xiao.feng.features.api.ui.WeCurrentConversationApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.utils.WeLogger
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
 * 两种模式都走宿主原生发送通道。隐蔽@模式在消息入库前把
 * `<atuserlist><![CDATA[wxid1,wxid2,...]]></atuserlist>` 合并进 msgsource,
 * 正文保持用户原文, 接收方气泡里看不到 @ 文本; 接收方客户端的 at-me 判定
 * (msgInfo.s2) 只检查自己的 wxid 是否在 atuserlist 里, 因此仍会收到「有人@我」提醒。
 * 即使全选也逐个列出成员 wxid, 而不是用 `notify@all` —— 后者会按发送者的群管理
 * 身份校验。
 */
object MentionMembers : SwitchFeature(), IResolveDex {

    override val technicalId = "@所有人"
    override val nameRes = R.string.feature_mention_members_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_mention_members_description

    private const val TAG = "MentionMembers"

    /** 微信服务器对 atuserlist 人数的上限, 超出部分静默截断 */
    private const val MAX_AT_USERS = 200

    private var stealthMentionAll by KvStore.prefOption("mention_members_stealth_all", false)

    // 隐蔽@待注入的 (会话, atuserlist)。原生发送在点击发送后异步入库, 由入库钩子消费
    @Volatile
    private var pendingStealthAt: Pair<String, String>? = null

    // MsgSourceHelper 合并方法: static (msgInfo, String nodeXml, boolean) -> void
    private val methodMergeMsgSourceNode by dexMethod()

    // 发送成功后宿主把服务器回包的 MsgSource 交给该方法, 用于核对服务器是否保留 atuserlist
    private val methodUpdateSecMsgUuidNode by dexMethod()

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

                                val selectedContacts = allMembers.filter { it.wxId in selectedWxIds }
                                val content = chatFooter.lastText

                                onDismiss()

                                if (stealthMentionAll) {
                                    // 隐蔽@: 正文保持原文, 只把成员 wxid 预置给入库钩子,
                                    // 然后走原生发送 (保留 ClientMsgId、SendMsgTicket 等原生字段)。
                                    val atUserList = selectedContacts
                                        .map { it.wxId }
                                        .filter { it != "weixin" && it != "filehelper" }
                                        .take(MAX_AT_USERS)
                                        .joinToString(",")
                                    pendingStealthAt = currentConv to atUserList
                                    WeChatInputBarMenuApi.performSend(chatFooter)
                                    return@ContactsSelector
                                }

                                // 普通@全选时用 notify@all, 否则逐个列出成员 wxid。
                                // @ 标记必须带 U+2005 (与原生 ChatFooter 生成的标记一致)。
                                val atWxIds = if (selectedContacts.size == allMembers.size) {
                                    "notify@all"
                                } else {
                                    selectedContacts
                                        .map { it.wxId }
                                        .filter { it != "weixin" && it != "filehelper" }
                                        .take(MAX_AT_USERS)
                                        .joinToString(",")
                                }

                                val outgoingContent =
                                    selectedContacts.joinToString("") { "@${it.nickname}\u2005" } + content

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
        WeMessageApi.methodMsgInfoHandleApiInsertMessage.hookBefore {
            val msgInfo = MessageInfo(args[0]!!)
            if (msgInfo.isSend != 1 || msgInfo.typeCode != 1) return@hookBefore

            val pending = pendingStealthAt ?: return@hookBefore
            // 先消费标记: 发送失败时残留会污染下一条消息
            pendingStealthAt = null
            if (msgInfo.talker != pending.first) return@hookBefore

            WeLogger.i(TAG, "injecting atuserlist for ${pending.first}: ${pending.second}")
            // z=false: 只改内存 msgsource, 随本次入库写入
            methodMergeMsgSourceNode.method.invoke(
                null,
                args[0],
                "<atuserlist><![CDATA[${pending.second}]]></atuserlist>",
                false,
            )
        }

        // 发送成功后宿主把服务器回包的 MsgSource 交给 ma.Q。把回包中的 msgsource
        // 记入运行日志, 用于确认服务器是否保留了 atuserlist。
        methodUpdateSecMsgUuidNode.hookBefore {
            val msgInfo = MessageInfo(args[0] ?: return@hookBefore)
            if (!msgInfo.msgSource.contains("atuserlist")) return@hookBefore
            val echo = (args[1] as String?).orEmpty()
            WeLogger.i(
                TAG,
                "server echo for ${msgInfo.talker}, atuserlist=${echo.contains("atuserlist")}: $echo",
            )
        }

        WeChatInputBarMenuApi.addProvider(provider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }
}
