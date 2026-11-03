package com.xiao.feng.activity.theme

/**
 * Theme keys and image slots understood by the WeKit theme engine.
 *
 * These names are the on-disk contract: [com.xiao.feng.features.items.beautify.Themes] resolves
 * colors, strings and image files by these exact keys, so they must never be localized or renamed.
 */
object ThemeStudioData {

    val COLOR_KEYS: List<String> = listOf(
        "chat.actionbar.title_text",
        "chat.actionbar.unread_badge_text",
        "chat.input.send_text",
        "chat.input.bottom_text",
        "settings.actionbar.title_text",
        "home.actionbar.title_text",
        "home.tabs.selected_text",
        "home.tabs.unselected_text",
        "home.tabs.unread_badge_text",
        "home.tabs.unread_red_tip",
        "home.item.primary_text",
        "home.item.secondary_text",
        "home.conversation_item.primary_text",
        "home.conversation_item.secondary_text",
        "home.conversation_item.unread_badge_text",
        "home.conversation_item.red_tip",
        "home.taskbar.mask",
        "chat.long_press_menu.item_text",
        "chat.red_packet.open_text",
        "chat.input.hint_text",
        "chat.input.text",
        "chat.history_tongue.text",
        "chat.link_text",
        "chat.nickname_text",
        "chat.tips_text",
        "chat.plus_panel.icon_text",
        "chat.text_bubble.left_text",
        "chat.text_bubble.right_text",
        "chat.file_bubble.left_text",
        "chat.file_bubble.right_text",
        "chat.red_packet_bubble.left_text",
        "chat.red_packet_bubble.right_text",
        "chat.transfer_bubble.left_text",
        "chat.transfer_bubble.right_text",
        "chat.transfer_bubble.left_received_text",
        "chat.transfer_bubble.right_received_text",
        "settings.switch_thumb",
    )

    /** Color keys grouped by their first two dot-separated segments. */
    val COLOR_GROUPS: Map<String, List<String>> by lazy {
        COLOR_KEYS.groupBy { key ->
            val parts = key.split(".")
            if (parts.size >= 2) "${parts[0]}.${parts[1]}" else parts[0]
        }
    }

    val STRING_KEYS: List<String> = listOf(
        "home.tabs.wechat_title",
        "home.tabs.contact_title",
        "home.tabs.discovery_title",
        "home.tabs.me_title",
        "chat.input.hint",
    )

    data class ImageSlot(
        val path: String,
        val category: String,
    ) {
        val fileName: String get() = path.substringAfterLast('/')
    }

    private fun slot(path: String, category: String) = ImageSlot(path, category)

    val IMAGE_SLOTS: List<ImageSlot> = buildList {
        add(slot("splash/background.png", "splash"))

        add(slot("home/background.png", "home"))
        add(slot("home/bottom_tab_background.png", "home"))
        add(slot("home/conversation_background.png", "home"))
        add(slot("home/conversation_item_background.png", "home"))
        add(slot("home/conversation_item_unread_badge.png", "home"))
        add(slot("home/conversation_top_item_background.png", "home"))
        add(slot("home/plus_menu_background.png", "home"))
        add(slot("home/tab_contact_background.png", "home"))
        add(slot("home/tab_discovery_background.png", "home"))
        add(slot("home/tab_me_background.png", "home"))

        add(slot("home/actionbar/background.png", "home"))
        add(slot("home/actionbar/multitask.png", "home"))
        add(slot("home/actionbar/plus.png", "home"))
        add(slot("home/actionbar/search.png", "home"))

        add(slot("home/tabs/contact_selected.png", "home"))
        add(slot("home/tabs/contact_unselected.png", "home"))
        add(slot("home/tabs/conversation_selected.png", "home"))
        add(slot("home/tabs/conversation_unselected.png", "home"))
        add(slot("home/tabs/discovery_selected.png", "home"))
        add(slot("home/tabs/discovery_unselected.png", "home"))
        add(slot("home/tabs/me_selected.png", "home"))
        add(slot("home/tabs/me_unselected.png", "home"))
        add(slot("home/tabs/unread_badge.png", "home"))

        add(slot("home/items/group_chat.png", "home"))
        add(slot("home/items/label.png", "home"))
        add(slot("home/items/new_friend.png", "home"))
        add(slot("home/items/official_account.png", "home"))
        add(slot("home/items/only_chat_friends.png", "home"))
        add(slot("home/items/service_account.png", "home"))
        add(slot("home/items/wework_contact.png", "home"))

        add(slot("home/discovery/channels.png", "home"))
        add(slot("home/discovery/games.png", "home"))
        add(slot("home/discovery/listen.png", "home"))
        add(slot("home/discovery/live.png", "home"))
        add(slot("home/discovery/look.png", "home"))
        add(slot("home/discovery/mini_programs.png", "home"))
        add(slot("home/discovery/moments.png", "home"))
        add(slot("home/discovery/nearby.png", "home"))
        add(slot("home/discovery/scan.png", "home"))
        add(slot("home/discovery/search.png", "home"))
        add(slot("home/discovery/shopping.png", "home"))

        add(slot("home/me/cards.png", "home"))
        add(slot("home/me/emoji.png", "home"))
        add(slot("home/me/favorites.png", "home"))
        add(slot("home/me/moments.png", "home"))
        add(slot("home/me/services.png", "home"))
        add(slot("home/me/settings.png", "home"))

        add(slot("chat/background.png", "chat"))
        add(slot("chat/input_background.png", "chat"))
        add(slot("chat/plus_panel_background.png", "chat"))
        add(slot("chat/red_packet.png", "chat"))
        add(slot("chat/red_packet_background.png", "chat"))
        add(slot("chat/red_packet_open.png", "chat"))
        add(slot("chat/speech_speed.png", "chat"))
        add(slot("chat/public_account_switch.png", "chat"))
        add(slot("chat/history_tongue_arrow_down.png", "chat"))
        add(slot("chat/history_tongue_arrow_up.png", "chat"))
        add(slot("chat/history_tongue_background.png", "chat"))
        add(slot("chat/long_press_menu_arrow_down.png", "chat"))
        add(slot("chat/long_press_menu_arrow_up.png", "chat"))
        add(slot("chat/long_press_menu_background.png", "chat"))
        add(slot("chat/bottom_background.png", "chat"))
        add(slot("chat/bottom_emoji.png", "chat"))
        add(slot("chat/bottom_plus.png", "chat"))
        add(slot("chat/bottom_send.png", "chat"))
        add(slot("chat/bottom_voice.png", "chat"))

        add(slot("chat/actionbar/background.png", "chat"))
        add(slot("chat/actionbar/back.png", "chat"))
        add(slot("chat/actionbar/more.png", "chat"))
        add(slot("chat/actionbar/search.png", "chat"))
        add(slot("chat/actionbar/settings.png", "chat"))
        add(slot("chat/actionbar/unread_badge.png", "chat"))

        add(slot("chat/bubbles/text_left.png", "chat"))
        add(slot("chat/bubbles/text_right.png", "chat"))
        add(slot("chat/bubbles/file_left.png", "chat"))
        add(slot("chat/bubbles/file_right.png", "chat"))
        add(slot("chat/bubbles/red_packet_left.png", "chat"))
        add(slot("chat/bubbles/red_packet_left_received.png", "chat"))
        add(slot("chat/bubbles/red_packet_right.png", "chat"))
        add(slot("chat/bubbles/red_packet_right_received.png", "chat"))
        add(slot("chat/bubbles/transfer_left.png", "chat"))
        add(slot("chat/bubbles/transfer_left_icon.png", "chat"))
        add(slot("chat/bubbles/transfer_left_received.png", "chat"))
        add(slot("chat/bubbles/transfer_right.png", "chat"))
        add(slot("chat/bubbles/transfer_right_icon.png", "chat"))
        add(slot("chat/bubbles/transfer_right_received.png", "chat"))

        add(slot("chat/emoji_tabs/custom.png", "chat"))
        add(slot("chat/emoji_tabs/favorites.png", "chat"))
        add(slot("chat/emoji_tabs/search.png", "chat"))
        add(slot("chat/emoji_tabs/system.png", "chat"))

        add(slot("plus/album.png", "plus"))
        add(slot("plus/business_card.png", "plus"))
        add(slot("plus/camera.png", "plus"))
        add(slot("plus/card.png", "plus"))
        add(slot("plus/chain.png", "plus"))
        add(slot("plus/favorites.png", "plus"))
        add(slot("plus/file.png", "plus"))
        add(slot("plus/gift.png", "plus"))
        add(slot("plus/group_tools.png", "plus"))
        add(slot("plus/live.png", "plus"))
        add(slot("plus/location.png", "plus"))
        add(slot("plus/music.png", "plus"))
        add(slot("plus/red_packet.png", "plus"))
        add(slot("plus/transfer.png", "plus"))
        add(slot("plus/video_call.png", "plus"))
        add(slot("plus/voice_input.png", "plus"))

        add(slot("settings/background.png", "settings"))
        add(slot("settings/actionbar/back.png", "settings"))
        add(slot("settings/actionbar/background.png", "settings"))
        add(slot("settings/actionbar/more.png", "settings"))
        add(slot("settings/actionbar/search.png", "settings"))
    }

    val IMAGE_CATEGORIES: List<String> = listOf("splash", "home", "chat", "plus", "settings")

    val IMAGE_SLOTS_BY_CATEGORY: Map<String, List<ImageSlot>> by lazy {
        IMAGE_SLOTS.groupBy { it.category }
    }

    val IMAGE_SLOT_PATHS: Set<String> by lazy { IMAGE_SLOTS.mapTo(mutableSetOf()) { it.path } }
}
