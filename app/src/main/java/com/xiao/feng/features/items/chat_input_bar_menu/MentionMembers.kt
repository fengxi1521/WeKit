package com.xiao.feng.features.items.chat_input_bar_menu

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
import com.xiao.feng.features.api.ui.WeChatInputBarMenuApi
import com.xiao.feng.features.api.ui.WeCurrentConversationApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.i18n.LocalXiaoFengLocalizedContext
import com.xiao.feng.ui.content.ContactsSelector
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.showToast
import com.xiao.feng.utils.strings.isGroupChatWxId

/**
 * @所有人。
 *
 * 确认成员后把输入框正文改成与手动 @ 完全一致的形式
 * (`@显示名\u2005` 逐个前缀), 再走宿主原生发送通道; 消息入库前再把成员 wxid
 * 合并进 msgsource 的 `<atuserlist>`。
 *
 * 正文里的 @ 标记与 atuserlist 数量一一对应 —— 微信服务器只在正文存在 @ 标记时
 * 才会给对应成员推送「有人@我」, 只写 atuserlist 而正文无 @ 会被服务器忽略
 * (8.0.78 实测如此), 因此这里不再提供「正文无 @」的隐蔽模式。
 *
 * 全选时同样逐个列出成员 wxid, 而不是用 `notify@all` —— 后者会按发送者的群管理
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

    // 待注入的 (会话, atuserlist)。原生发送在点击发送后异步入库, 由入库钩子消费
    @Volatile
    private var pendingAtUserList: Pair<String, String>? = null

    // MsgSourceHelper 合并方法: static (msgInfo, String nodeXml, boolean) -> void
    private val methodMergeMsgSourceNode by dexMethod()

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

                    if (chatFooter.lastText.isEmpty()) {
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

                                val targets = allMembers
                                    .filter { it.wxId in selectedWxIds }
                                    .filter { it.wxId != "weixin" && it.wxId != "filehelper" }
                                    .take(MAX_AT_USERS)

                                if (targets.isEmpty()) {
                                    showToast(
                                        dialogContext,
                                        localizedContext.localizedChatInputString(
                                            R.string.mention_members_empty_group,
                                        ),
                                    )
                                    return@ContactsSelector
                                }

                                // 群昵称优先, 其次好友备注, 最后昵称 —— 与原生 @ 显示名一致
                                val groupDisplayNames =
                                    WeDatabaseApi.getGroupMemberDisplayNames(currentConv)
                                val prefix = targets.joinToString("") { contact ->
                                    val name = groupDisplayNames[contact.wxId]
                                        ?: contact.remarkName.ifEmpty { contact.nickname }
                                    "@${name}\u2005"
                                }

                                val content = chatFooter.lastText
                                onDismiss()

                                // 正文带可见 @ 前缀, 入库时再注入 atuserlist, 两者数量一致
                                pendingAtUserList =
                                    currentConv to targets.joinToString(",") { it.wxId }
                                chatFooter.lastText = prefix + content
                                WeChatInputBarMenuApi.performSend(chatFooter)
                            }
                        )
                    }
                },
            )
        )
    }

    override fun onEnable() {
        WeMessageApi.methodMsgInfoHandleApiInsertMessage.hookBefore {
            val msgInfo = MessageInfo(args[0]!!)
            if (msgInfo.isSend != 1 || msgInfo.typeCode != 1) return@hookBefore

            val pending = pendingAtUserList ?: return@hookBefore
            // 先消费标记: 发送失败时残留会污染下一条消息
            pendingAtUserList = null
            if (msgInfo.talker != pending.first) return@hookBefore
            // 宿主已经从正文解析出 atuserlist 时不再重复追加
            if (msgInfo.msgSource.contains("<atuserlist")) return@hookBefore

            WeLogger.i(TAG, "injecting atuserlist for ${pending.first}: ${pending.second}")
            // z=false: 只改内存 msgsource, 随本次入库写入
            methodMergeMsgSourceNode.method.invoke(
                null,
                args[0],
                "<atuserlist><![CDATA[${pending.second}]]></atuserlist>",
                false,
            )
        }

        WeChatInputBarMenuApi.addProvider(provider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }
}
