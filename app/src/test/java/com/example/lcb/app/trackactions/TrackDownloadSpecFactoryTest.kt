package com.example.lcb.app.trackactions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackDownloadSpecFactoryTest {
    @Test
    fun `http stream creates a stable safe mp3 destination`() {
        val track = actionTrack(
            id = "AUDIUS:track-1",
            title = "A:*?\"<>| Song",
            artist = "Artist / Name",
            streamUrl = "https://api.example.test/v1/tracks/1/stream",
        )

        val first = TrackDownloadSpecFactory.create(track)
        val second = TrackDownloadSpecFactory.create(track)

        assertNotNull(first)
        assertTrue(first!!.fileName.endsWith(".mp3"))
        assertFalse(first.fileName.any { it in "/\\:*?\"<>|" })
        assertTrue(first.fileName.startsWith("Artist _ Name - A_ Song-"))
        assertTrue(first == second)
    }

    @Test
    fun `different track ids do not collide when title and artist match`() {
        val first = TrackDownloadSpecFactory.create(actionTrack(id = "AUDIUS:1"))
        val second = TrackDownloadSpecFactory.create(actionTrack(id = "JAMENDO:1"))

        assertNotEquals(first?.fileName, second?.fileName)
    }

    @Test
    fun `blank malformed and non web stream urls are rejected`() {
        assertNull(TrackDownloadSpecFactory.create(actionTrack(streamUrl = "")))
        assertNull(TrackDownloadSpecFactory.create(actionTrack(streamUrl = "not a url")))
        assertNull(TrackDownloadSpecFactory.create(actionTrack(streamUrl = "file:///music/song.mp3")))
    }

    private fun actionTrack(
        id: String = "AUDIUS:track",
        title: String = "Song",
        artist: String = "Artist",
        streamUrl: String = "https://example.test/song.mp3",
    ) = TrackActionUiModel(
        id = id,
        title = title,
        artist = artist,
        artworkUrls = emptyList(),
        artworkFallbackRes = 0,
        streamUrl = streamUrl,
    )
}
