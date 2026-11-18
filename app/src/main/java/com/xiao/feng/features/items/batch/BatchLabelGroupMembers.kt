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

/** 选择一个群聊, 给群里已经是好友的成员批量追加标签。 */
object BatchLabelGroupMembers : ClickableFeature() {

    override val technicalId = "群成员批量打标签"
    override val nameRes = R.string.feature_batch_label_group_members_name
    override val categoryIds = listOf(FeatureCategoryIds.BATCH)
    override val descriptionRes = R.string.feature_batch_label_group_members_description
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
                        title = context.localizedBatchString(R.string.feature_batch_label_group_members_name),
                        groupWxId = groupWxId,
                        includeLabels = true,
                        includeCollections = false,
                        initialInterval = intervalSeconds,
                        onIntervalPicked = { intervalSeconds = it },
                    )
                },
            )
        }
    }

    private const val KEY_INTERVAL = "batch_label_group_interval_seconds"
}
