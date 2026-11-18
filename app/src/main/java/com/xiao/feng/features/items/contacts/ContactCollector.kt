package com.xiao.feng.features.items.contacts

import android.content.Context
import com.xiao.feng.data.JsonDataMigration
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeContactLabelApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.models.WeContact
import com.xiao.feng.features.items.chat.ConversationAggregation
import com.xiao.feng.features.items.chat.ConversationGrouping

/**
 * 把好友归入一个「目标」的公共逻辑。
 *
 * 目标是三种归宿之一:
 * - 微信标签 ([TargetKind.LABEL])
 * - 对话归拢 ([TargetKind.FOLDER])
 * - 对话分组 ([TargetKind.GROUP])
 *
 * 上层功能 (检测单向删除、加好友自动归拢、群成员批量操作) 共用这里的选取与执行逻辑,
 * 每次操作之间的间隔由调用方按需 delay, 以规避服务器风控。
 */
object ContactCollector {

    enum class TargetKind { LABEL, FOLDER, GROUP }

    data class Target(val kind: TargetKind, val id: String, val name: String)

    /** 读取所有可用目标。仅包含可以手动添加成员的手动归拢 / 手动分组。 */
    fun loadTargets(
        context: Context,
        includeLabels: Boolean,
        includeCollections: Boolean,
    ): List<Target> = buildList {
        if (includeLabels) {
            WeContactLabelApi.getAllLabels().forEach { label ->
                add(Target(TargetKind.LABEL, label.labelId.toString(), label.labelName))
            }
        }
        if (includeCollections) {
            // 归拢/分组必须在其功能已启用且迁移完成后才能读取, 否则读取会抛异常。
            if (ConversationAggregation.isActive && JsonDataMigration.isCompleted("chat", "folders")) {
                ConversationAggregation.aggregationFolders()
                    .filterNot { it.isAuto }
                    .forEach { folder ->
                        add(Target(TargetKind.FOLDER, folder.id, folder.name))
                    }
            }
            if (ConversationGrouping.isActive && JsonDataMigration.isCompleted("chat", "groups")) {
                ConversationGrouping.manualGroupChoices(context).forEach { group ->
                    add(Target(TargetKind.GROUP, group.id, group.name))
                }
            }
        }
    }

    /** 群内已经是好友的成员 (排除自己)。非好友没有好友位, 打标签/归拢会失败。 */
    fun friendMembersOf(groupWxId: String): List<WeContact> =
        WeDatabaseApi.getGroupMembers(groupWxId).filter { contact ->
            contact.wxId != WeApi.selfWxId && WeDatabaseApi.getFriend(contact.wxId) != null
        }

    /**
     * 批量操作前确认目标可用。标签在不存在时按名称创建 (需要等待服务器下发 id),
     * 归拢与分组必须事先存在。
     */
    suspend fun prepare(target: Target): Boolean = when (target.kind) {
        TargetKind.LABEL -> WeContactLabelApi.createLabel(target.name) != null
        TargetKind.FOLDER, TargetKind.GROUP -> true
    }

    /** 把 [wxId] 加入 [target]。返回 false 表示目标拒绝了该联系人 (不存在或不是手动类型)。 */
    suspend fun apply(target: Target, wxId: String): Boolean = when (target.kind) {
        TargetKind.LABEL -> {
            // 追加而不是替换, 保留联系人原有标签。
            val existing = WeContactLabelApi.getLabelNamesForContact(wxId)
            if (target.name !in existing) {
                WeContactLabelApi.modifyLabel(wxId, existing + target.name)
            }
            true
        }

        TargetKind.FOLDER -> ConversationAggregation.addToFolder(target.id, wxId)
        TargetKind.GROUP -> ConversationGrouping.addToGroup(target.id, wxId)
    }

    /** 把「间隔秒数」输入解析成毫秒; 非法或负数按 0 处理。 */
    fun intervalMillis(input: String): Long =
        ((input.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0) * 1000.0).toLong()
}
