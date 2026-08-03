package com.example.lcb.app.trackactions

import com.example.lcb.app.analytics.MusicAnalytics
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackActionSurfaceTest {
    @Test
    fun `device deletion preserves the originating page surface`() {
        assertEquals(
            MusicAnalytics.Surface.LOCAL_MUSIC,
            parseTrackActionSurface(MusicAnalytics.Surface.LOCAL_MUSIC.value),
        )
        assertEquals(
            MusicAnalytics.Surface.PLAYLIST,
            parseTrackActionSurface(MusicAnalytics.Surface.PLAYLIST.value),
        )
    }

    @Test
    fun `missing or unknown surface uses compatibility fallback`() {
        assertEquals(MusicAnalytics.Surface.TRACK_ACTION_SHEET, parseTrackActionSurface(null))
        assertEquals(MusicAnalytics.Surface.TRACK_ACTION_SHEET, parseTrackActionSurface("unknown"))
    }
}
