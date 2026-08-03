package com.example.lcb.app.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.lcb.app.player.PlayerTrack
import com.example.lcb.app.ui.AppLoadError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class SearchViewModel internal constructor(
    private val repository: SearchRepository,
    private val savedStateHandle: SavedStateHandle,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) : ViewModel() {
    private val query = MutableStateFlow(savedStateHandle[QUERY_KEY] ?: "")
    private val mutableState = MutableStateFlow(SearchUiState(query = query.value))
    val state = mutableState.asStateFlow()

    private var loadMoreJob: Job? = null
    private var retryJob: Job? = null
    private var activeTrackId: String? = null
    private var isActiveTrackPlaying = false

    init {
        viewModelScope.launch {
            query
                .map(String::trim)
                .debounce { if (it.isEmpty()) 0L else debounceMs }
                .distinctUntilChanged()
                .collectLatest { normalizedQuery ->
                    if (normalizedQuery.isEmpty()) resetForEmptyQuery() else loadFirstPage(normalizedQuery)
                }
        }
    }

    fun onQueryChanged(rawQuery: String) {
        val safeQuery = rawQuery.take(MAX_QUERY_LENGTH)
        if (safeQuery == query.value) return
        val queryChanged = safeQuery.trim() != query.value.trim()
        savedStateHandle[QUERY_KEY] = safeQuery
        query.value = safeQuery
        loadMoreJob?.cancel()
        retryJob?.cancel()
        mutableState.update { old ->
            if (queryChanged) SearchUiState(query = safeQuery) else old.copy(query = safeQuery)
        }
    }

    fun loadNextPage() {
        val snapshot = mutableState.value
        val normalizedQuery = snapshot.query.trim()
        if (
            normalizedQuery.isEmpty() || snapshot.isInitialLoading || snapshot.isLoadingMore ||
            !snapshot.hasMore || snapshot.loadMoreError != null
        ) {
            return
        }
        val offset = nextOffset ?: return
        loadMoreJob = viewModelScope.launch {
            mutableState.update { it.copy(isLoadingMore = true, loadMoreError = null) }
            try {
                val page = repository.search(normalizedQuery, offset, PAGE_SIZE)
                if (mutableState.value.query.trim() != normalizedQuery) return@launch
                nextOffset = page.nextOffset
                mutableState.update { current ->
                    val merged = (current.tracks + page.tracks)
                        .distinctBy(SearchTrackUi::id)
                        .withPlayback()
                    current.copy(
                        tracks = merged,
                        isLoadingMore = false,
                        hasMore = page.nextOffset != null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (mutableState.value.query.trim() == normalizedQuery) {
                    mutableState.update {
                        it.copy(
                            isLoadingMore = false,
                            loadMoreError = AppLoadError.SEARCH_MORE,
                        )
                    }
                }
            }
        }
    }

    fun retry() {
        val snapshot = mutableState.value
        val normalizedQuery = snapshot.query.trim()
        if (normalizedQuery.isEmpty()) return
        if (snapshot.loadMoreError != null) {
            mutableState.update { it.copy(loadMoreError = null) }
            loadNextPage()
            return
        }
        if (snapshot.initialLoadError != null && !snapshot.isInitialLoading) {
            retryJob?.cancel()
            retryJob = viewModelScope.launch { loadFirstPage(normalizedQuery) }
        }
    }

    /** MediaSession 是播放状态唯一来源，搜索列表只保存它的 UI 投影。 */
    fun updatePlayback(trackId: String?, isPlaying: Boolean) {
        activeTrackId = trackId
        isActiveTrackPlaying = isPlaying
        mutableState.update { current -> current.copy(tracks = current.tracks.withPlayback()) }
    }

    fun playerQueue(): List<PlayerTrack> = mutableState.value.tracks.map(SearchTrackUi::toPlayerTrack)

    private suspend fun loadFirstPage(normalizedQuery: String) {
        nextOffset = null
        mutableState.update { current ->
            if (current.query.trim() != normalizedQuery) current else current.copy(
                tracks = emptyList(),
                isInitialLoading = true,
                isLoadingMore = false,
                hasMore = false,
                hasSearched = true,
                initialLoadError = null,
                loadMoreError = null,
            )
        }
        try {
            // debounceMs=0 的测试仍让出调度点，确保快速输入可以被 collectLatest 取消。
            if (debounceMs == 0L) delay(0)
            val page = repository.search(normalizedQuery, 0, PAGE_SIZE)
            if (mutableState.value.query.trim() != normalizedQuery) return
            nextOffset = page.nextOffset
            mutableState.update { current ->
                current.copy(
                    tracks = page.tracks.distinctBy(SearchTrackUi::id).withPlayback(),
                    isInitialLoading = false,
                    hasMore = page.nextOffset != null,
                    initialLoadError = null,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            if (mutableState.value.query.trim() == normalizedQuery) {
                mutableState.update {
                    it.copy(
                        tracks = emptyList(),
                        isInitialLoading = false,
                        hasMore = false,
                        initialLoadError = AppLoadError.SEARCH,
                    )
                }
            }
        }
    }

    private fun resetForEmptyQuery() {
        nextOffset = null
        mutableState.value = SearchUiState(query = query.value)
    }

    private fun List<SearchTrackUi>.withPlayback() = map { track ->
        track.copy(isPlaying = isActiveTrackPlaying && track.id == activeTrackId)
    }

    class Factory(private val repository: SearchRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            SearchViewModel(repository, extras.createSavedStateHandle()) as T
    }

    private companion object {
        const val QUERY_KEY = "search.query"
        const val DEFAULT_DEBOUNCE_MS = 350L
        const val MAX_QUERY_LENGTH = 100
        const val PAGE_SIZE = 20
    }

    private var nextOffset: Int? = null
}
