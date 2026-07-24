package com.example.lcb.app.trackactions

import com.example.lcb.music.model.MusicArtistRef
import com.example.lcb.music.model.MusicPlatform
import com.example.lcb.music.model.MusicTrack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackArtistResolverTest {
    @Test
    fun `explicit artist reference avoids another sdk request`() = runTest {
        val expected = MusicArtistRef("artist-1", MusicPlatform.AUDIUS, "Artist")
        var requestCount = 0
        val resolver = MusicSdkTrackArtistResolver { _, _ ->
            requestCount++
            sdkTrack(expected)
        }

        assertEquals(expected, resolver.resolve(actionTrack(artistRef = expected)))
        assertEquals(0, requestCount)
    }

    @Test
    fun `legacy track resolves artist from platform prefixed id`() = runTest {
        val expected = MusicArtistRef("artist-2", MusicPlatform.JAMENDO, "Legacy Artist")
        val calls = mutableListOf<Pair<MusicPlatform, String>>()
        val resolver = MusicSdkTrackArtistResolver { platform, id ->
            calls += platform to id
            sdkTrack(expected)
        }

        val result = resolver.resolve(actionTrack(id = "jamendo:track-9"))

        assertEquals(expected, result)
        assertEquals(listOf(MusicPlatform.JAMENDO to "track-9"), calls)
    }

    @Test
    fun `malformed local id is not guessed from artist name`() = runTest {
        val resolver = MusicSdkTrackArtistResolver { _, _ -> error("SDK should not be called") }

        assertNull(resolver.resolve(actionTrack(id = "local-track")))
    }

    private fun actionTrack(
        id: String = "AUDIUS:track-1",
        artistRef: MusicArtistRef? = null,
    ) = TrackActionUiModel(
        id = id,
        title = "Track",
        artist = "Artist",
        artworkUrls = emptyList(),
        artworkFallbackRes = 0,
        artistRef = artistRef,
    )

    private fun sdkTrack(artistRef: MusicArtistRef) = MusicTrack(
        id = "track",
        platform = artistRef.platform,
        title = "Track",
        artistName = artistRef.name,
        artworkUrl = null,
        durationMs = 60_000,
        streamUrl = "https://example.test/track.mp3",
        artist = artistRef,
    )
}
