package com.xiao.feng.activity.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.xiao.feng.features.core.BaseFeature
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeaturesProvider
import com.xiao.feng.features.core.NewFeatures
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.data.KvStore

object FeatureCategoryState {
    var revision by mutableIntStateOf(0)
        private set

    // The settings screens observe `revision`, so one snapshot per revision is enough. Rebuilding it
    // costs a single batched SQLite query instead of one query per feature when every row composes.
    private var snapshotRevision = -1
    private var snapshotValues: Map<String, Boolean> = emptyMap()

    private fun switchStates(): Map<String, Boolean> {
        val current = revision
        if (current == snapshotRevision) return snapshotValues
        val stored = KvStore.getBooleans(FeaturesProvider.ALL_FEATURES.map { it.technicalId })
        val values = HashMap<String, Boolean>(stored.size)
        for (feature in FeaturesProvider.ALL_FEATURES) {
            val storedValue = stored[feature.technicalId]
                ?: ((feature as? SwitchFeature)?.defaultEnabled == true)
            values[feature.technicalId] = storedValue ||
                    (feature is ClickableFeature && feature.alwaysEnabled)
        }
        snapshotValues = values
        snapshotRevision = current
        return values
    }

    val newItems: List<BaseFeature> by lazy {
        val visibleCategories = FEATURE_CATEGORIES.mapTo(mutableSetOf()) { it.id }
        FeaturesProvider.ALL_FEATURES.associateBy { it.technicalId }.values
            .mapNotNull { item ->
                FeaturesProvider.SOURCE_KEY_BY_FEATURE[item]
                    ?.let(NewFeatures.ADDED_AT_BY_SOURCE_KEY::get)
                    ?.let { addedAt -> item to addedAt }
            }
            .filter { (item, _) -> item.categoryIds.any { it in visibleCategories } }
            .sortedWith(
                compareByDescending<Pair<BaseFeature, Long>> { it.second }
                    .thenBy { it.first.technicalId },
            )
            .map { (item, _) -> item }
    }

    /** Current switch state of [feature], served from the per-revision snapshot. */
    fun isEnabled(feature: BaseFeature): Boolean =
        switchStates()[feature.technicalId]
            ?: ((feature as? SwitchFeature)?.defaultEnabled == true)

    fun enabledItems(): List<SwitchFeature> {
        val values = switchStates()
        return FeaturesProvider.ALL_FEATURES
            .filterIsInstance<SwitchFeature>()
            .filter { values[it.technicalId] == true }
            .sortedBy { it.technicalId }
    }

    fun notifyToggleChanged() {
        revision++
    }
}
