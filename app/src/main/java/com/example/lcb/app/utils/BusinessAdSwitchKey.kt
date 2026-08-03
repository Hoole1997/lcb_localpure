package com.example.lcb.app.utils

/**
 * 业务广告开关 key。它们只用于 AdSlotSwitchController，不能替代广告 SDK 的 position。
 * 集中维护可避免页面手写字符串导致远端开关失效。
 */
internal object BusinessAdSwitchKey {
    const val HOME_BOTTOM_NATIVE = "home_bottom_native"
    const val SEARCH_RESULT_BOTTOM_NATIVE = "search_result_bottom_native"
    const val RECOMMENDED_MUSIC_BOTTOM_NATIVE = "recommended_music_bottom_native"
    const val LOCAL_MUSIC_BOTTOM_NATIVE = "local_music_bottom_native"
    const val ARTIST_DETAIL_BOTTOM_NATIVE = "artist_detail_bottom_native"
    const val PLAYLIST_DETAIL_BOTTOM_NATIVE = "playlist_detail_bottom_native"
    const val FAVORITES_BOTTOM_NATIVE = "favorites_bottom_native"
    const val SETTINGS_BOTTOM_NATIVE = "settings_bottom_native"

    const val HOME_RECOMMENDED_MORE_ENTRY_INTERSTITIAL = "home_recommended_more_entry_interstitial"
    const val HOME_LOCAL_PLAYLISTS_ENTRY_INTERSTITIAL = "home_local_playlists_entry_interstitial"
    const val HOME_FAVORITES_ENTRY_INTERSTITIAL = "home_favorites_entry_interstitial"
    const val HOME_PLAYLIST_ENTRY_INTERSTITIAL = "home_playlist_entry_interstitial"
    const val ARTIST_LIST_NAME_ENTRY_INTERSTITIAL = "artist_list_name_entry_interstitial"
    const val PLAYER_ARTIST_NAME_ENTRY_INTERSTITIAL = "player_artist_name_entry_interstitial"
    const val SONG_INFO_ARTIST_ENTRY_INTERSTITIAL = "song_info_artist_entry_interstitial"
}
