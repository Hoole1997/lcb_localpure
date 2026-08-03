package com.example.lcb.app.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class BusinessAdSwitchKeyTest {
    @Test
    fun `native ad switch keys match remote configuration contract`() {
        assertEquals(
            setOf(
                "home_bottom_native",
                "search_result_bottom_native",
                "recommended_music_bottom_native",
                "local_music_bottom_native",
                "artist_detail_bottom_native",
                "playlist_detail_bottom_native",
                "favorites_bottom_native",
                "settings_bottom_native",
            ),
            setOf(
                BusinessAdSwitchKey.HOME_BOTTOM_NATIVE,
                BusinessAdSwitchKey.SEARCH_RESULT_BOTTOM_NATIVE,
                BusinessAdSwitchKey.RECOMMENDED_MUSIC_BOTTOM_NATIVE,
                BusinessAdSwitchKey.LOCAL_MUSIC_BOTTOM_NATIVE,
                BusinessAdSwitchKey.ARTIST_DETAIL_BOTTOM_NATIVE,
                BusinessAdSwitchKey.PLAYLIST_DETAIL_BOTTOM_NATIVE,
                BusinessAdSwitchKey.FAVORITES_BOTTOM_NATIVE,
                BusinessAdSwitchKey.SETTINGS_BOTTOM_NATIVE,
            ),
        )
    }

    @Test
    fun `each interstitial entry maps to its own switch key`() {
        assertEquals(
            mapOf(
                InterstitialAdPlacement.HOME_RECOMMENDED_MORE_ENTRY to
                    "home_recommended_more_entry_interstitial",
                InterstitialAdPlacement.HOME_LOCAL_PLAYLISTS_ENTRY to
                    "home_local_playlists_entry_interstitial",
                InterstitialAdPlacement.HOME_FAVORITES_ENTRY to
                    "home_favorites_entry_interstitial",
                InterstitialAdPlacement.HOME_PLAYLIST_ENTRY to
                    "home_playlist_entry_interstitial",
                InterstitialAdPlacement.ARTIST_LIST_NAME_ENTRY to
                    "artist_list_name_entry_interstitial",
                InterstitialAdPlacement.PLAYER_ARTIST_NAME_ENTRY to
                    "player_artist_name_entry_interstitial",
                InterstitialAdPlacement.SONG_INFO_ARTIST_ENTRY to
                    "song_info_artist_entry_interstitial",
            ),
            InterstitialAdPlacement.entries.associateWith(InterstitialAdPlacement::switchKey),
        )
    }
}
