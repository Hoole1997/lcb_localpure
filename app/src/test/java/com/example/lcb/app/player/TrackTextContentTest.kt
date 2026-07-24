package com.example.lcb.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackTextContentTest {
    @Test
    fun `uses Audius description when lyrics are unavailable`() {
        val result = track(description = "  About this release  ").resolveDisplayText()

        assertEquals(TrackTextType.DESCRIPTION, result?.type)
        assertEquals("About this release", result?.rawText)
    }

    @Test
    fun `lyrics take priority over description`() {
        val result = track(lyrics = "First line", description = "About this release").resolveDisplayText()

        assertEquals(TrackTextType.LYRICS, result?.type)
        assertEquals("First line", result?.rawText)
    }

    @Test
    fun `blank platform text does not create an empty section`() {
        assertNull(track(lyrics = "  ", description = "\n").resolveDisplayText())
    }

    private fun track(lyrics: String? = null, description: String? = null) = PlayerTrack(
        id = "audius:track",
        title = "Track",
        artist = "Artist",
        artworkUrl = null,
        streamUrl = "https://example.test/stream",
        durationMs = 120_000,
        lyrics = lyrics,
        description = description,
    )
}
