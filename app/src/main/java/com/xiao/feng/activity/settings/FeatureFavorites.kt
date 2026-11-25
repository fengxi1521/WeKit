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

    var revision by mutableIntStateOf(0)
        private set

    private var raw: String by KvStore.prefOption(KEY, "")

    fun ids(): List<String> = raw.split('\n').filter { it.isNotBlank() }.distinct()

    fun contains(id: String): Boolean = id in ids()

    fun features(): List<SwitchFeature> {
        val byId = FeaturesProvider.ALL_FEATURES
            .filterIsInstance<SwitchFeature>()
            .associateBy { it.technicalId }
        return ids().mapNotNull { byId[it] }
    }

    fun add(item: SwitchFeature) {
        if (contains(item.technicalId)) return
        raw = (ids() + item.technicalId).joinToString("\n")
        revision++
    }

    fun remove(item: SwitchFeature) {
        raw = ids().filterNot { it == item.technicalId }.joinToString("\n")
        revision++
    }

    /** Swaps the favorite at [index] with its neighbour [delta] positions away. */
    fun move(index: Int, delta: Int) {
        val list = ids().toMutableList()
        val target = index + delta
        if (index !in list.indices || target !in list.indices) return
        val moved = list.removeAt(index)
        list.add(target, moved)
        raw = list.joinToString("\n")
        revision++
    }
}
