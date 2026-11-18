package com.xiao.feng.features.items.batch

import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.data.KvStore
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.items.contacts.startGroupCollection
import com.xiao.feng.ui.content.SingleContactSelector
import com.xiao.feng.ui.utils.showComposeDialog

/** 选择一个群聊, 把群里已经是好友的成员批量加入指定的归拢或分组。 */
object BatchCollectGroupMembers : ClickableFeature() {

    override val technicalId = "群成员批量归拢"
    override val nameRes = R.string.feature_batch_collect_group_members_name
    override val categoryIds = listOf(FeatureCategoryIds.BATCH)
    override val descriptionRes = R.string.feature_batch_collect_group_members_description
    override val noSwitchWidget = true

    private var intervalSeconds by KvStore.prefOption(KEY_INTERVAL, "1")

    override fun onClick(context: ComponentActivity) {
        val groups = WeDatabaseApi.getGroups()
        showComposeDialog(context) {
            SingleContactSelector(
                title = context.localizedBatchString(R.string.batch_group_members_select_group),
                contacts = groups,
                initialSelectedWxId = null,
                onDismiss = onDismiss,
                onConfirm = { groupWxId ->
                    onDismiss()
                    context.startGroupCollection(
                        title = context.localizedBatchString(R.string.feature_batch_collect_group_members_name),
                        groupWxId = groupWxId,
                        includeLabels = false,
                        includeCollections = true,
                        initialInterval = intervalSeconds,
                        onIntervalPicked = { intervalSeconds = it },
                    )
                },
            )
        }
    }

    private const val KEY_INTERVAL = "batch_collect_group_interval_seconds"
}
