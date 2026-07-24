package com.example.lcb.app.artist

import com.example.lcb.app.player.PlayerTrack
import com.example.lcb.app.R
import com.example.lcb.music.model.MusicArtist
import com.example.lcb.music.model.MusicArtistCapabilities
import com.example.lcb.music.model.MusicArtistDetails
import com.example.lcb.music.model.MusicArtistFeature
import com.example.lcb.music.model.MusicCollectionType
import com.example.lcb.music.model.MusicPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistViewModelTest {
    @Test
    fun `initial sections load independently and track pages deduplicate`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeRepository(
                pages = mapOf(
                    0 to ArtistTrackPage(listOf(track("1"), track("2")), 20),
                    20 to ArtistTrackPage(listOf(track("2"), track("3")), null),
                ),
                albumsFailure = IllegalStateException("Albums unavailable"),
            )
            val viewModel = ArtistViewModel(request, repository)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.isInitialLoading)
            assertEquals(listOf("1", "2"), viewModel.state.value.tracks.map(ArtistTrackUi::id))
            assertEquals(ArtistLoadError.RELEASES, viewModel.state.value.albumsError)
            assertTrue(viewModel.state.value.hasMoreTracks)

            viewModel.loadNextPage()
            advanceUntilIdle()

            assertEquals(listOf("1", "2", "3"), viewModel.state.value.tracks.map(ArtistTrackUi::id))
            assertFalse(viewModel.state.value.hasMoreTracks)
            assertEquals(listOf(0, 20), repository.offsets)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `collection loading emits a playable queue and clears progress state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeRepository(
                pages = mapOf(0 to ArtistTrackPage(listOf(track("1")), null)),
                collectionTracks = listOf(track("a").track, track("b").track),
            )
            val viewModel = ArtistViewModel(request, repository)
            advanceUntilIdle()
            val event = async { viewModel.events.first() }

            viewModel.playCollection(collection)
            advanceUntilIdle()

            val openQueue = event.await() as ArtistEvent.OpenQueue
            assertEquals(listOf("a", "b"), openQueue.queue.map(PlayerTrack::id))
            assertEquals("a", openQueue.currentTrackId)
            assertEquals(null, viewModel.state.value.loadingCollectionId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `empty collection emits localizable resource event`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = ArtistViewModel(
                request,
                FakeRepository(
                    pages = mapOf(0 to ArtistTrackPage(emptyList(), null)),
                    collectionTracks = emptyList(),
                ),
            )
            advanceUntilIdle()
            val event = async { viewModel.events.first() }

            viewModel.playCollection(collection)
            advanceUntilIdle()

            assertEquals(R.string.artist_collection_no_songs, (event.await() as ArtistEvent.Message).messageRes)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeRepository(
        private val pages: Map<Int, ArtistTrackPage>,
        private val albumsFailure: Throwable? = null,
        private val collectionTracks: List<PlayerTrack> = emptyList(),
    ) : ArtistRepository {
        val offsets = mutableListOf<Int>()

        override suspend fun loadDetails(request: ArtistRequest) = details

        override suspend fun loadTracks(request: ArtistRequest, offset: Int, limit: Int): ArtistTrackPage {
            offsets += offset
            return pages.getValue(offset)
        }

        override suspend fun loadAlbums(request: ArtistRequest, limit: Int): List<ArtistCollectionUi> {
            albumsFailure?.let { throw it }
            return emptyList()
        }

        override suspend fun loadPlaylists(request: ArtistRequest, limit: Int): List<ArtistCollectionUi> = emptyList()

        override suspend fun loadCollectionTracks(
            collection: ArtistCollectionUi,
            limit: Int,
        ): List<PlayerTrack> = collectionTracks
    }

    private companion object {
        val request = ArtistRequest(MusicPlatform.AUDIUS, "artist", "Artist")
        val details = MusicArtistDetails(
            artist = MusicArtist("artist", MusicPlatform.AUDIUS, "Artist"),
            capabilities = MusicArtistCapabilities(
                setOf(
                    MusicArtistFeature.TRACKS,
                    MusicArtistFeature.ALBUMS,
                    MusicArtistFeature.PLAYLISTS,
                ),
            ),
        )
        val collection = ArtistCollectionUi(
            id = "album",
            platform = MusicPlatform.AUDIUS,
            type = MusicCollectionType.ALBUM,
            title = "Album",
            subtitle = "2026",
            artworkUrls = emptyList(),
            artworkFallbackRes = 0,
            trackCount = 2,
        )

        fun track(id: String) = ArtistTrackUi(
            track = PlayerTrack(
                id = id,
                title = "Track $id",
                artist = "Artist",
                artworkUrl = null,
                streamUrl = "https://example.test/$id.mp3",
                durationMs = 60_000,
            ),
            artworkUrls = emptyList(),
            artworkFallbackRes = 0,
        )
    }
}
