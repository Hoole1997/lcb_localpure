package com.example.lcb.app.recommended

import com.example.lcb.app.player.PlayerTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class RecommendedMusicViewModelTest {
    @Test
    fun `refresh and pagination merge pages without duplicate tracks`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = FakeRepository(
                pages = mapOf(
                    0 to RecommendedMusicPage(listOf(track("1"), track("2")), nextOffset = 20),
                    20 to RecommendedMusicPage(listOf(track("2"), track("3")), nextOffset = null),
                ),
            )
            val viewModel = RecommendedMusicViewModel(repository)
            advanceUntilIdle()

            assertEquals(listOf("1", "2"), viewModel.state.value.tracks.map(RecommendedTrackUi::id))
            assertTrue(viewModel.state.value.hasMore)

            viewModel.loadNextPage()
            advanceUntilIdle()

            assertEquals(listOf("1", "2", "3"), viewModel.state.value.tracks.map(RecommendedTrackUi::id))
            assertFalse(viewModel.state.value.hasMore)
            assertEquals(listOf(0, 20), repository.offsets)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `playback projection distinguishes play intent from active audio`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val item = track("1")
            val viewModel = RecommendedMusicViewModel(
                FakeRepository(mapOf(0 to RecommendedMusicPage(listOf(item), null))),
            )
            advanceUntilIdle()

            viewModel.updatePlayback(item.track, isPlaying = true, isActivelyPlaying = false)
            assertFalse(viewModel.state.value.tracks.single().isPlaying)
            assertTrue(viewModel.state.value.miniPlayer?.isPlaying == true)

            viewModel.updatePlayback(item.track, isPlaying = true, isActivelyPlaying = true)
            assertTrue(viewModel.state.value.tracks.single().isPlaying)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `play all uses selected tracks while selection mode is active`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = RecommendedMusicViewModel(
                FakeRepository(
                    mapOf(0 to RecommendedMusicPage(listOf(track("1"), track("2")), null)),
                ),
            )
            advanceUntilIdle()

            viewModel.toggleSelectionMode()
            viewModel.toggleSelection("2")

            assertEquals(listOf("2"), viewModel.playAllQueue().map(PlayerTrack::id))
            assertEquals(1, viewModel.state.value.selectedCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeRepository(
        private val pages: Map<Int, RecommendedMusicPage>,
    ) : RecommendedMusicRepository {
        val offsets = mutableListOf<Int>()

        override suspend fun load(offset: Int, limit: Int): RecommendedMusicPage {
            offsets += offset
            return pages.getValue(offset)
        }
    }

    private fun track(id: String) = RecommendedTrackUi(
        track = PlayerTrack(
            id = id,
            title = "Track $id",
            artist = "Artist",
            artworkUrl = null,
            streamUrl = "https://example.test/$id.mp3",
            durationMs = 60_000,
        ),
        artworkThumbnailUrls = emptyList(),
        artworkFallbackRes = 0,
    )
}
