package com.xiao.feng.features.items.moments

import com.xiao.feng.R
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.ui.WeMomentsApi
import com.xiao.feng.features.api.ui.WeMomentsContextMenuApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.ui.utils.ExposurePlus1Icon
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Adds a "like all their Moments" entry to the long-press menu of a Moment. Every cached Moment
 * owned by that author is liked, including pinned posts, while posts that are already liked,
 * deleted, or advertisements are skipped.
 */
object LikeAllAuthorMoments : SwitchFeature(), WeMomentsContextMenuApi.IMenuItemsProvider {

    override val technicalId = "一键点赞作者全部朋友圈"
    override val nameRes = R.string.feature_like_all_author_moments_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_like_all_author_moments_description
    override val defaultEnabled = true

    private const val TAG = "LikeAllAuthorMoments"

    /** Minimum gap between two like requests, so a big album is not blasted at the host at once. */
    private const val LIKE_INTERVAL_MS = 60L

    private val runningOwners = ConcurrentHashMap.newKeySet<String>()

    private data class LikeSummary(val liked: Int, val skipped: Int, val failed: Int)

    override fun onEnable() {
        WeMomentsContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeMomentsContextMenuApi.removeProvider(this)
        runningOwners.clear()
    }

    override fun getMenuItems(): List<WeMomentsContextMenuApi.MenuItem> {
        return listOf(
            WeMomentsContextMenuApi.MenuItem(
                777021,
                localizedMomentsString(R.string.moments_like_all_author_menu),
                ExposurePlus1Icon,
                { context, _ ->
                    val owner = WeMomentsApi.getOwnerWxId(context)
                    !owner.isNullOrEmpty() && owner != WeApi.selfWxId
                },
            ) { context ->
                likeAllMomentsOfOwner(context)
            }
        )
    }

    private fun likeAllMomentsOfOwner(context: WeMomentsContextMenuApi.MomentsContext) {
        val activity = context.activity
        val owner = WeMomentsApi.getOwnerWxId(context)
        if (owner.isNullOrEmpty()) {
            WeLogger.w(TAG, "failed to resolve the Moments author")
            return
        }
        if (!runningOwners.add(owner)) {
            CoroutineScope(Dispatchers.Main).launch {
                showToastSuspend(activity, activity.localizedMomentsString(R.string.moments_like_all_author_running))
            }
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                showToastSuspend(activity, activity.localizedMomentsString(R.string.moments_like_all_author_started))
                val summary = withContext(Dispatchers.IO) {
                    runCatching { likeAllMoments(owner) }
                        .onFailure { WeLogger.w(TAG, "failed to like all Moments of $owner", it) }
                        .getOrNull()
                }
                if (summary == null) {
                    showToastSuspend(activity, activity.localizedMomentsString(R.string.moments_like_all_author_failed))
                } else if (summary.liked + summary.skipped == 0) {
                    showToastSuspend(activity, activity.localizedMomentsString(R.string.moments_like_all_author_empty))
                } else {
                    showToastSuspend(
                        activity,
                        activity.localizedMomentsString(
                            R.string.moments_like_all_author_done,
                            summary.liked,
                            summary.skipped,
                            summary.failed,
                        ),
                    )
                }
            } finally {
                runningOwners.remove(owner)
            }
        }
    }

    private suspend fun likeAllMoments(owner: String): LikeSummary {
        var liked = 0
        var skipped = 0
        var failed = 0

        val seen = mutableSetOf<String>()
        for (snsInfo in collectOwnedSnsInfos(owner)) {
            val tableId = WeMomentsApi.getSnsTableId(snsInfo) ?: continue
            // A pinned Moment is also cached in the SnsInfo table on some hosts; like it once.
            if (!seen.add(tableId)) continue

            if (WeMomentsApi.isDeleted(snsInfo) || WeMomentsApi.isLiked(snsInfo)) {
                skipped++
                continue
            }

            val result = WeMomentsApi.like(snsInfo)
            when {
                !result.success -> failed++
                result.sent -> {
                    liked++
                    delay(LIKE_INTERVAL_MS)
                }
                else -> skipped++
            }
        }

        WeLogger.i(TAG, "like all Moments of $owner: liked=$liked skipped=$skipped failed=$failed")
        return LikeSummary(liked, skipped, failed)
    }

    /**
     * Pinned Moments live in the host's star storage rather than the SnsInfo table (the latter
     * only yields regular posts), so both sources have to be merged here.
     */
    private fun collectOwnedSnsInfos(owner: String): List<Any> =
        queryOwnedSnsIds(owner).mapNotNull { WeMomentsApi.getSnsInfoBySnsId(it) } +
                WeMomentsApi.getPinnedSnsInfos(owner)

    /** Enumerates the author's regular posts from the local SnsInfo cache. */
    private fun queryOwnedSnsIds(owner: String): List<Long> {
        val sql = """
            SELECT snsId FROM SnsInfo
            WHERE userName = ?
              AND snsId != 0
              AND (sourceType & ${WeMomentsApi.ACTIVE_SOURCE_MASK}) != 0
              AND (sourceType & ${WeMomentsApi.AD_SOURCE_FLAG}) = 0
            ORDER BY createTime DESC
        """.trimIndent()

        val snsIds = mutableListOf<Long>()
        WeMomentsApi.rawQuerySnsInfo(sql, arrayOf(owner)).use { cursor ->
            while (cursor.moveToNext()) {
                snsIds += cursor.getLong(0)
            }
        }
        return snsIds
    }
}
