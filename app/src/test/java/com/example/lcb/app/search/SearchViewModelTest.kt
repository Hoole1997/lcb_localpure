package com.example.lcb.app.search

import androidx.lifecycle.SavedStateHandle
import com.example.lcb.app.ui.AppLoadError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @Test
    fun `search and next page preserve order without duplicate tracks`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val first = listOf(track("1"), track("2"))
            val second = listOf(track("2"), track("3"))
            val repository = FakeSearchRepository(
                pages = mapOf(
                    0 to SearchPage(first, nextOffset = 2),
                    2 to SearchPage(second, nextOffset = null),
                ),
            )
            val viewModel = SearchViewModel(repository, SavedStateHandle(), debounceMs = 0)

            viewModel.onQueryChanged("lofi")
            advanceUntilIdle()
            assertEquals(listOf("1", "2"), viewModel.state.value.tracks.map(SearchTrackUi::id))
            assertTrue(viewModel.state.value.hasMore)

            viewModel.loadNextPage()
            advanceUntilIdle()
            assertEquals(listOf("1", "2", "3"), viewModel.state.value.tracks.map(SearchTrackUi::id))
            assertFalse(viewModel.state.value.hasMore)
            assertEquals(listOf(0, 2), repository.requestedOffsets)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `new query cancels stale response`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = object : SearchRepository {
                override suspend fun search(query: String, offset: Int, limit: Int): SearchPage {
                    if (query == "old") delay(5_000)
                    return SearchPage(listOf(track(query)), nextOffset = null)
                }
            }
            val viewModel = SearchViewModel(repository, SavedStateHandle(), debounceMs = 0)

            viewModel.onQueryChanged("old")
            runCurrent()
            viewModel.onQueryChanged("new")
            advanceUntilIdle()

            assertEquals("new", viewModel.state.value.query)
            assertEquals(listOf("new"), viewModel.state.value.tracks.map(SearchTrackUi::id))
            assertFalse(viewModel.state.value.isInitialLoading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `repository exception is exposed as localized search error semantic`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = object : SearchRepository {
                override suspend fun search(query: String, offset: Int, limit: Int): SearchPage {
                    error("English SDK error that must not reach the UI")
                }
            }
            val viewModel = SearchViewModel(repository, SavedStateHandle(), debounceMs = 0)

            viewModel.onQueryChanged("lofi")
            advanceUntilIdle()

            assertEquals(AppLoadError.SEARCH, viewModel.state.value.initialLoadError)
            assertTrue(viewModel.state.value.tracks.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `pagination exception uses the localized load more semantic`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = SearchViewModel(
                FakeSearchRepository(mapOf(0 to SearchPage(listOf(track("1")), nextOffset = 2))),
                SavedStateHandle(),
                debounceMs = 0,
            )
            viewModel.onQueryChanged("lofi")
            advanceUntilIdle()

            viewModel.loadNextPage()
            advanceUntilIdle()

            assertEquals(AppLoadError.SEARCH_MORE, viewModel.state.value.loadMoreError)
            assertEquals(listOf("1"), viewModel.state.value.tracks.map(SearchTrackUi::id))
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeSearchRepository(
        private val pages: Map<Int, SearchPage>,
    ) : SearchRepository {
        val requestedOffsets = mutableListOf<Int>()

        override suspend fun search(query: String, offset: Int, limit: Int): SearchPage {
            requestedOffsets += offset
            return pages.getValue(offset)
        }
    }

    private fun track(id: String) = SearchTrackUi(
        id = id,
        title = "Track $id",
        artist = "Artist",
        artworkFallbackRes = 0,
        artworkUrl = null,
        artworkThumbnailUrls = emptyList(),
        streamUrl = "https://example.test/$id.mp3",
        durationMs = 60_000,
        lyrics = null,
        description = null,
    )
}
