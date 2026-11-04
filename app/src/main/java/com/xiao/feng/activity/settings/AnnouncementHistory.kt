package com.xiao.feng.activity.settings

import com.xiao.feng.R

/** One release entry shown in the in-app announcement history. */
data class AnnouncementEntry(val version: String, val notesRes: Int)

val announcementHistory = listOf(
    AnnouncementEntry("2026.11.04", R.string.announcement_notes_20261104),
    AnnouncementEntry("2026.11.03", R.string.announcement_notes_20261103),
    AnnouncementEntry("2026.10.31", R.string.announcement_notes_20261031),
)
