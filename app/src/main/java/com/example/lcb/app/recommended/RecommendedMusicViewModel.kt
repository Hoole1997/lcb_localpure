package com.example.lcb.app.recommended

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.lcb.app.player.PlayerTrack
import com.example.lcb.app.player.artworkCandidates
import com.example.lcb.app.ui.AppLoadError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RecommendedMusicViewModel(
    private val repository: RecommendedMusicRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RecommendedMusicUiState())
    val state = mutableState.asStateFlow()

    private var nextOffset: Int? = null
    private var refreshJob: Job? = null
    private var loadMoreJob: Job? = null
    private var playback: PlaybackSnapshot? = null

    init {
        refresh()
    }

    fun refresh() {
        loadMoreJob?.cancel()
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            nextOffset = null
            mutableState.update {
                it.copy(
                    tracks = emptyList(),
                    isInitialLoading = true,
                    isLoadingMore = false,
                    hasMore = false,
                    initialLoadError = null,
                    loadMoreError = null,
                    isSelectionMode = false,
                ).withPlayback(playback)
            }
            try {
                val page = repository.load(offset = 0, limit = PAGE_SIZE)
                nextOffset = page.nextOffset
                mutableState.update { current ->
                    current.copy(
                        tracks = page.tracks.distinctBy(RecommendedTrackUi::id),
                        isInitialLoading = false,
                        hasMore = page.nextOffset != null,
                    ).withPlayback(playback)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(
                        isInitialLoading = false,
                        initialLoadError = AppLoadError.RECOMMENDED,
                    ).withPlayback(playback)
                }
            }
        }
    }

    fun loadNextPage() {
        val snapshot = mutableState.value
        val offset = nextOffset ?: return
        if (
            snapshot.isInitialLoading || snapshot.isLoadingMore || !snapshot.hasMore ||
            snapshot.loadMoreError != null
        ) {
            return
        }
        loadMoreJob = viewModelScope.launch {
            mutableState.update { it.copy(isLoadingMore = true, loadMoreError = null) }
            try {
                val page = repository.load(offset, PAGE_SIZE)
                nextOffset = page.nextOffset
                mutableState.update { current ->
                    current.copy(
                        tracks = (current.tracks + page.tracks).distinctBy(RecommendedTrackUi::id),
                        isLoadingMore = false,
                        hasMore = page.nextOffset != null,
                    ).withPlayback(playback)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(
                        isLoadingMore = false,
                        loadMoreError = AppLoadError.RECOMMENDED_MORE,
                    )
                }
            }
        }
    }

    fun retry() {
        when {
            mutableState.value.initialLoadError != null -> refresh()
            mutableState.value.loadMoreError != null -> {
                mutableState.update { it.copy(loadMoreError = null) }
                loadNextPage()
            }
        }
    }

    fun toggleSelectionMode() {
        mutableState.update { current ->
            val entering = !current.isSelectionMode
            current.copy(
                isSelectionMode = entering,
                tracks = if (entering) current.tracks else current.tracks.map { it.copy(isSelected = false) },
            )
        }
    }

    fun toggleSelection(trackId: String) {
        if (!mutableState.value.isSelectionMode) return
        mutableState.update { current ->
            current.copy(
                tracks = current.tracks.map { track ->
                    if (track.id == trackId) track.copy(isSelected = !track.isSelected) else track
                },
            )
        }
    }

    /** MediaSession 是播放状态唯一来源，ViewModel 只投影到列表与 Mini Player。 */
    fun updatePlayback(
        track: PlayerTrack?,
        isPlaying: Boolean,
        isActivelyPlaying: Boolean,
    ) {
        playback = track?.let { PlaybackSnapshot(it, isPlaying, isActivelyPlaying) }
        mutableState.update { it.withPlayback(playback) }
    }

    fun queueForTrack(): List<PlayerTrack> = mutableState.value.tracks.map { it.track }

    fun playAllQueue(): List<PlayerTrack> {
        val current = mutableState.value
        val selected = current.tracks.filter(RecommendedTrackUi::isSelected)
        return (if (current.isSelectionMode && selected.isNotEmpty()) selected else current.tracks)
            .map(RecommendedTrackUi::track)
    }

    private fun RecommendedMusicUiState.withPlayback(snapshot: PlaybackSnapshot?): RecommendedMusicUiState {
        val projectedTracks = tracks.map { item ->
            item.copy(isPlaying = snapshot?.isActivelyPlaying == true && item.id == snapshot.track.id)
        }
        val mini = snapshot?.let { current ->
            val listedTrack = projectedTracks.firstOrNull { it.id == current.track.id }
            RecommendedMiniPlayerUi(
                track = current.track,
                artworkThumbnailUrls = listedTrack?.artworkThumbnailUrls
                    ?: current.track.artworkCandidates(),
                artworkFallbackRes = listedTrack?.artworkFallbackRes
                    ?: com.example.lcb.app.R.drawable.home_cover_recommended_3,
                isPlaying = current.isPlaying,
            )
        }
        return copy(tracks = projectedTracks, miniPlayer = mini)
    }

    private data class PlaybackSnapshot(
        val track: PlayerTrack,
        val isPlaying: Boolean,
        val isActivelyPlaying: Boolean,
    )

    class Factory(private val repository: RecommendedMusicRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RecommendedMusicViewModel(repository) as T
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}
