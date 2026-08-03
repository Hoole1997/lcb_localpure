package com.example.lcb.app.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePlaybackProjectionTest {
    @Test
    fun `active playback is projected into every home track section`() {
        val active = track("active")
        val content = HomeContent(
            recommended = listOf(active),
            mostPlayed = listOf(active),
            localMusic = LocalHomeMusicState.Loaded(listOf(active)),
            recentlyPlayed = listOf(active),
        ).withPlayback(activeTrackId = active.id, isActivelyPlaying = true)

        assertTrue(content.recommended.single().isPlaying)
        assertTrue(content.mostPlayed.single().isPlaying)
        assertTrue((content.localMusic as LocalHomeMusicState.Loaded).tracks.single().isPlaying)
        assertTrue(content.recentlyPlayed.single().isPlaying)
    }

    @Test
    fun `paused playback removes animated playing state`() {
        val content = HomeContent(recommended = listOf(track("active")))
            .withPlayback(activeTrackId = "active", isActivelyPlaying = false)

        assertFalse(content.recommended.single().isPlaying)
    }

    private fun track(id: String) = HomeTrackUi(
        id = id,
        title = "Track",
        artist = "Artist",
        artworkRes = 0,
    )
}
