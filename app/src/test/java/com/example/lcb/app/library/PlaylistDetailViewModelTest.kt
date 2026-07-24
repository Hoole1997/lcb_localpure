package com.example.lcb.app.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
class PlaylistDetailViewModelTest {
    @Test
    fun `selection removes one or multiple tracks through repository`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeLibraryRepository(listOf(track("1"), track("2"), track("3")))
            val viewModel = PlaylistDetailViewModel(
                repository,
                LibraryCollection.Playlist(7L),
                "Favorites",
            )
            advanceUntilIdle()

            viewModel.enterSelection("1")
            viewModel.toggleSelection("3")
            advanceUntilIdle()
            assertEquals(2, viewModel.state.value.selectedCount)

            viewModel.removeSelected()
            advanceUntilIdle()

            assertEquals(setOf("1", "3"), repository.removedIds)
            assertFalse(viewModel.state.value.isSelectionMode)
            assertEquals(listOf("2"), viewModel.state.value.tracks.map(PlaylistTrackUi::id))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `favorite collection cannot delete a custom playlist`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeLibraryRepository(emptyList())
            val viewModel = PlaylistDetailViewModel(
                repository,
                LibraryCollection.Favorites,
                "Favorites",
            )

            viewModel.deletePlaylist()
            advanceUntilIdle()

            assertFalse(repository.deleteCalled)
            assertTrue(viewModel.state.value.isFavorites)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeLibraryRepository(initialTracks: List<LibraryTrack>) : MusicLibraryRepository {
        private val tracks = MutableStateFlow(initialTracks)
        var removedIds: Set<String> = emptySet()
        var deleteCalled = false

        override fun observePlaylists(): Flow<List<PlaylistSummary>> = MutableStateFlow(emptyList())
        override fun observeRecentlyPlayed(): Flow<List<LibraryTrack>> = MutableStateFlow(emptyList())
        override fun observePlaylistName(playlistId: Long): Flow<String?> = MutableStateFlow("Playlist")
        override fun observeTracks(collection: LibraryCollection): Flow<List<LibraryTrack>> = tracks
        override fun observeFavorite(trackId: String): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun isFavorite(trackId: String) = false
        override suspend fun createPlaylist(name: String) = Result.success(1L)
        override suspend fun addTrackToPlaylist(playlistId: Long, track: LibraryTrack) = AddTrackResult.ADDED
        override suspend fun recordRecentlyPlayed(track: LibraryTrack) = Unit
        override suspend fun setFavorite(track: LibraryTrack, favorite: Boolean) = Unit

        override suspend fun removeTracks(collection: LibraryCollection, trackIds: Set<String>): Int {
            removedIds = trackIds
            tracks.value = tracks.value.filterNot { it.id in trackIds }
            return trackIds.size
        }

        override suspend fun deletePlaylist(playlistId: Long): Boolean {
            deleteCalled = true
            return true
        }
    }

    private fun track(id: String) = LibraryTrack(
        id = id,
        title = "Track $id",
        artist = "Artist",
        artworkUrl = null,
        artworkThumbnailUrls = emptyList(),
        artworkFallbackRes = 0,
        streamUrl = "https://example.test/$id.mp3",
        durationMs = 60_000,
        lyrics = null,
        description = null,
    )
}
