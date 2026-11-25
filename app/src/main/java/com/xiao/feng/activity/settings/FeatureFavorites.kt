package com.xiao.feng.activity.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.xiao.feng.data.KvStore
import com.xiao.feng.features.core.FeaturesProvider
import com.xiao.feng.features.core.SwitchFeature

/**
 * User-curated, ordered list of favorite features shown at the top of the feature page.
 *
 * Persisted as newline-separated technical ids so the display order survives restarts; [revision]
 * lets the feature page and the favorites screen recompose after any modification.
 */
object FeatureFavorites {
    private const val KEY = "feature_favorites_order"

    /** Static id → feature lookup shared by every caller, built once instead of per read. */
    private val featuresById: Map<String, SwitchFeature> by lazy {
        FeaturesProvider.ALL_FEATURES.filterIsInstance<SwitchFeature>().associateBy { it.technicalId }
    }

    var revision by mutableIntStateOf(0)
        private set

    private var raw: String by KvStore.prefOption(KEY, "")

    fun ids(): List<String> = raw.split('\n').filter { it.isNotBlank() }.distinct()

    fun contains(id: String): Boolean = id in ids()

    fun features(): List<SwitchFeature> = ids().mapNotNull { featuresById[it] }

    fun add(item: SwitchFeature) {
        if (contains(item.technicalId)) return
        raw = (ids() + item.technicalId).joinToString("\n")
        revision++
    }

    /** Persists a fully reordered list in one write, so drag gestures do not hit the store per swap. */
    fun setOrder(order: List<String>) {
        val cleaned = order.filter { it.isNotBlank() }.distinct()
        if (cleaned == ids()) return
        raw = cleaned.joinToString("\n")
        revision++
    }
}
