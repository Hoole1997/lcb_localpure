package com.example.lcb.app.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaylistTrackUi(
    val track: LibraryTrack,
    val isPlaying: Boolean = false,
    val isSelected: Boolean = false,
    val isSelectionMode: Boolean = false,
) {
    val id: String get() = track.id
}

data class PlaylistDetailUiState(
    val title: String = "",
    val tracks: List<PlaylistTrackUi> = emptyList(),
    val isFavorites: Boolean = false,
    val isSelectionMode: Boolean = false,
    val isMissing: Boolean = false,
) {
    val selectedCount: Int get() = tracks.count(PlaylistTrackUi::isSelected)
}

sealed interface PlaylistDetailEvent {
    data object PlaylistDeleted : PlaylistDetailEvent
    data class TracksRemoved(val count: Int) : PlaylistDetailEvent
    data class Error(val message: String) : PlaylistDetailEvent
}

/**
 * 歌单页状态机。Room Flow、选择集合和 MediaSession 投影单向合并，Adapter 不直接修改数据。
 */
class PlaylistDetailViewModel(
    private val repository: MusicLibraryRepository,
    val collection: LibraryCollection,
    favoritesTitle: String,
) : ViewModel() {
    private val selectionMode = MutableStateFlow(false)
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val activeTrackId = MutableStateFlow<String?>(null)
    private val isActivelyPlaying = MutableStateFlow(false)
    private val mutableEvents = MutableSharedFlow<PlaylistDetailEvent>(extraBufferCapacity = 1)
    val events = mutableEvents.asSharedFlow()

    private val title: Flow<String?> = when (collection) {
        LibraryCollection.Favorites -> flowOf(favoritesTitle)
        is LibraryCollection.Playlist -> repository.observePlaylistName(collection.id)
    }

    val state = combine(
        title,
        repository.observeTracks(collection),
        selectionMode,
        selectedIds,
        combine(activeTrackId, isActivelyPlaying, ::Pair),
    ) { currentTitle, tracks, selecting, selected, playback ->
        PlaylistDetailUiState(
            title = currentTitle.orEmpty(),
            tracks = tracks.map { track ->
                PlaylistTrackUi(
                    track = track,
                    isPlaying = playback.second && playback.first == track.id,
                    isSelected = track.id in selected,
                    isSelectionMode = selecting,
                )
            },
            isFavorites = collection == LibraryCollection.Favorites,
            isSelectionMode = selecting,
            isMissing = collection is LibraryCollection.Playlist && currentTitle == null,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        PlaylistDetailUiState(isFavorites = collection == LibraryCollection.Favorites),
    )

    fun enterSelection(trackId: String? = null) {
        selectionMode.value = true
        if (trackId != null) selectedIds.update { it + trackId }
    }

    fun exitSelection() {
        selectionMode.value = false
        selectedIds.value = emptySet()
    }

    fun toggleSelection(trackId: String) {
        if (!selectionMode.value) selectionMode.value = true
        selectedIds.update { selected ->
            if (trackId in selected) selected - trackId else selected + trackId
        }
    }

    fun removeSelected() {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.removeTracks(collection, ids) }
                .onSuccess { count ->
                    exitSelection()
                    mutableEvents.emit(PlaylistDetailEvent.TracksRemoved(count))
                }
                .onFailure { error ->
                    mutableEvents.emit(PlaylistDetailEvent.Error(error.message ?: "Unable to remove songs"))
                }
        }
    }

    fun deletePlaylist() {
        val playlist = collection as? LibraryCollection.Playlist ?: return
        viewModelScope.launch {
            runCatching { repository.deletePlaylist(playlist.id) }
                .onSuccess { deleted ->
                    if (deleted) mutableEvents.emit(PlaylistDetailEvent.PlaylistDeleted)
                }
                .onFailure { error ->
                    mutableEvents.emit(PlaylistDetailEvent.Error(error.message ?: "Unable to delete playlist"))
                }
        }
    }

    fun updatePlayback(trackId: String?, playing: Boolean) {
        activeTrackId.value = trackId
        isActivelyPlaying.value = playing
    }

    fun playerQueue(): List<com.example.lcb.app.player.PlayerTrack> =
        state.value.tracks.map { it.track.toPlayerTrack() }

    class Factory(
        private val repository: MusicLibraryRepository,
        private val collection: LibraryCollection,
        private val favoritesTitle: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlaylistDetailViewModel(repository, collection, favoritesTitle) as T
    }
}
