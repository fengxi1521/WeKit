package com.xiao.feng.features.core

import com.xiao.feng.R
import com.xiao.feng.constants.Preferences
import com.xiao.feng.featurepoints.FeaturePointsTable
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.features.items.beautify.BeautifyConversationList
import com.xiao.feng.features.items.chat.ConversationGrouping
import com.xiao.feng.features.items.system.SafeMode
import com.xiao.feng.i18n.LocaleResourceMode
import com.xiao.feng.i18n.LocalizedContextFactory
import com.xiao.feng.i18n.XiaoFengLocaleController
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.TargetProcesses
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.showToast
import kotlin.time.measureTime

object FeaturesLoader {

    private const val TAG = "FeaturesLoader"

    fun loadFeatures() {
        val allFeatures = FeaturesProvider.ALL_FEATURES
        allFeatures.filterIsInstance<SwitchFeature>().forEach(SwitchFeature::loadPersistedState)
        if (TargetProcesses.isInMain) {
            // Migrate even when grouping is disabled or its legacy style needs reading. Reading
            // legacy style later could mistake a new beauty toggle for the user's pre-upgrade choice.
            ConversationGrouping.migrateTabStyle(BeautifyConversationList.isLayoutBeautificationEnabled)
        }

        val safeMode = SafeMode.isEnabled
        val featuresToStart = if (safeMode) {
            allFeatures.filterIsInstance<ApiFeature>()
        } else {
            allFeatures
        }
        if (safeMode) {
            WeLogger.i(
                TAG,
                "safe mode active: loading only ${featuresToStart.size} ApiFeature(s), " +
                    "skipping ${allFeatures.size - featuresToStart.size} feature(s)",
            )
        }

        val brokenItems = loadDescriptors(featuresToStart.filterIsInstance<IResolveDex>())

        val elapsed = measureTime {
            featuresToStart.forEach { feature ->
                val isBroken = feature is IResolveDex && brokenItems.contains(feature)

                if (isBroken) {
                    WeLogger.w(TAG, "skipping ${feature.technicalId} — bundled feature points are incomplete")
                    return@forEach
                }

                feature.startup()
            }
        }
        WeLogger.i(TAG, "loading all features took $elapsed")

        if (TargetProcesses.isInMain && Preferences.showStartupToast) {
            val context = LocalizedContextFactory.create(
                HostInfo.application,
                XiaoFengLocaleController.resolvedLocale,
                LocaleResourceMode.InjectedHost,
            )
            showToast(context, context.getString(R.string.noncompose_features_loaded))
        }
    }

    /**
     * Restores every delegate from the bundled feature-point table.
     *
     * The table is the only source of symbols: there is no runtime scanning and no cache rebuild.
     * A feature whose entry is absent or whose entry lacks any declared delegate is reported
     * loudly and skipped, while unrelated features keep loading normally.
     *
     * @return features that must not start
     */
    private fun loadDescriptors(items: List<IResolveDex>): List<IResolveDex> {
        if (items.isEmpty()) return emptyList()

        if (!FeaturePointsTable.load(HostInfo.application.assets)) {
            WeLogger.e(
                TAG,
                "no bundled feature points for this host; " +
                    "skipping ${items.size} symbol-dependent feature(s)",
            )
            return items
        }

        val brokenItems = mutableListOf<IResolveDex>()
        for (item in items) {
            val path = (item as BaseFeature).technicalPath
            val entry = FeaturePointsTable.descriptorsFor(item.technicalId)
            if (entry == null) {
                WeLogger.e(TAG, "$path: no bundled feature-point entry")
                brokenItems += item
                continue
            }

            val missingKeys = item.loadFromCache(entry)
            if (missingKeys.isNotEmpty()) {
                val loaded = item.dexDelegates.size - missingKeys.size
                WeLogger.e(
                    TAG,
                    "$path: loaded $loaded/${item.dexDelegates.size} delegates, missing: $missingKeys",
                )
                brokenItems += item
            }
        }
        return brokenItems
    }
}
