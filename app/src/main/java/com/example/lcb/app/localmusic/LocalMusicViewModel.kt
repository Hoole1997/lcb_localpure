package com.example.lcb.app.localmusic

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.lcb.app.player.PlayerTrack
import com.example.lcb.app.ui.AppLoadError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 权限、MediaStore 内容与 MediaSession 投影保持单向数据流，Activity 只渲染状态。 */
class LocalMusicViewModel(
    private val repository: LocalMusicRepository,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val content = MutableStateFlow(ContentState())
    private val playback = MutableStateFlow<PlaybackSnapshot?>(null)
    // 目录属于用户页面状态，交给 SavedStateHandle 保存，旋转或系统回收后不会跳回全部歌曲。
    private val selectedFolder = savedStateHandle.getStateFlow<String?>(KEY_SELECTED_FOLDER, null)
    private var observationJob: Job? = null

    val state = combine(content, playback, selectedFolder) { source, currentPlayback, folder ->
        val availableFolders = source.tracks
            .mapNotNull(LocalMusicTrack::folderName)
            .filter(String::isNotBlank)
            .groupingBy(String::trim)
            .eachCount()
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        val validSelection = folder?.takeIf(availableFolders::containsKey)
        val visibleTracks = if (validSelection == null) {
            source.tracks
        } else {
            source.tracks.filter { it.folderName?.trim()?.equals(validSelection, ignoreCase = true) == true }
        }
        LocalMusicUiState(
            hasPermission = source.hasPermission,
            isLoading = source.isLoading,
            tracks = visibleTracks.map { track ->
                LocalMusicTrackUi(
                    track = track,
                    isPlaying = currentPlayback?.isPlaying == true && currentPlayback.track.id == track.id,
                )
            },
            folders = buildList {
                add(LocalMusicFolderUi(name = null, trackCount = source.tracks.size, isSelected = validSelection == null))
                availableFolders.forEach { (name, count) ->
                    add(LocalMusicFolderUi(name = name, trackCount = count, isSelected = name == validSelection))
                }
            },
            totalTrackCount = source.tracks.size,
            folderCount = availableFolders.size,
            loadError = source.loadError,
            miniPlayer = currentPlayback?.let { LocalMusicMiniPlayerUi(it.track, it.isPlaying) },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LocalMusicUiState())

    fun setPermissionGranted(granted: Boolean) {
        if (!granted) {
            observationJob?.cancel()
            observationJob = null
            content.value = ContentState(hasPermission = false)
            return
        }
        if (content.value.hasPermission && observationJob?.isActive == true) return
        observeMediaStore()
    }

    fun retry() {
        if (content.value.hasPermission) observeMediaStore()
    }

    fun selectFolder(folderName: String?) {
        savedStateHandle[KEY_SELECTED_FOLDER] = folderName
    }

    fun updatePlayback(track: PlayerTrack?, isPlaying: Boolean) {
        playback.value = track?.let { PlaybackSnapshot(it, isPlaying) }
    }

    /** 大曲库只向播放器传 100 首，并从点击歌曲开始循环，保证当前歌曲不会被截掉。 */
    fun queueForTrack(trackId: String): List<PlayerTrack> {
        val tracks = state.value.tracks.map { it.track }
        val selectedIndex = tracks.indexOfFirst { it.id == trackId }
        if (selectedIndex < 0) return emptyList()
        val ordered = tracks.drop(selectedIndex) + tracks.take(selectedIndex)
        return ordered.asSequence().take(MAX_PLAYER_QUEUE_SIZE).map(LocalMusicTrack::toPlayerTrack).toList()
    }

    private fun observeMediaStore() {
        observationJob?.cancel()
        content.update { it.copy(hasPermission = true, isLoading = it.tracks.isEmpty(), loadError = null) }
        observationJob = viewModelScope.launch {
            repository.observeTracks()
                .catch { error ->
                    content.update {
                        ContentState(
                            hasPermission = error !is SecurityException,
                            tracks = if (error is SecurityException) emptyList() else it.tracks,
                            loadError = AppLoadError.LOCAL_MUSIC,
                        )
                    }
                }
                .collect { tracks ->
                    content.value = ContentState(hasPermission = true, tracks = tracks)
                }
        }
    }

    private data class ContentState(
        val hasPermission: Boolean = false,
        val isLoading: Boolean = false,
        val tracks: List<LocalMusicTrack> = emptyList(),
        val loadError: AppLoadError? = null,
    )

    private data class PlaybackSnapshot(val track: PlayerTrack, val isPlaying: Boolean)

    class Factory(private val repository: LocalMusicRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            LocalMusicViewModel(repository, extras.createSavedStateHandle()) as T
    }

    private companion object {
        const val KEY_SELECTED_FOLDER = "selected_local_music_folder"
        const val MAX_PLAYER_QUEUE_SIZE = 100
    }
}
