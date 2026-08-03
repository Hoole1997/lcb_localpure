package com.example.lcb.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerArtworkCandidatesTest {
    @Test
    fun `thumbnail candidates are normalized before original artwork fallback`() {
        val track = track(
            artworkUrl = "https://cdn.example/full.jpg",
            thumbnails = listOf(
                " https://cdn.example/small.jpg ",
                "https://cdn.example/small.jpg",
                "",
                "https://mirror.example/small.jpg",
            ),
        )

        assertEquals(
            listOf(
                "https://cdn.example/small.jpg",
                "https://mirror.example/small.jpg",
                "https://cdn.example/full.jpg",
            ),
            track.artworkCandidates(),
        )
    }

    @Test
    fun `artwork candidate count is bounded for queue transport`() {
        val track = track(
            artworkUrl = "https://cdn.example/full.jpg",
            thumbnails = (1..5).map { "https://cdn.example/$it.jpg" },
        )

        assertEquals(
            listOf(
                "https://cdn.example/1.jpg",
                "https://cdn.example/2.jpg",
                "https://cdn.example/3.jpg",
                "https://cdn.example/full.jpg",
            ),
            track.artworkCandidates(),
        )
    }

    private fun track(artworkUrl: String?, thumbnails: List<String>) = PlayerTrack(
        id = "track-id",
        title = "Title",
        artist = "Artist",
        artworkUrl = artworkUrl,
        streamUrl = "https://cdn.example/audio.mp3",
        durationMs = 1_000L,
        artworkThumbnailUrls = thumbnails,
    )
}
