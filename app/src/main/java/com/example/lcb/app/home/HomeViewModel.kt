package com.example.lcb.app.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.lcb.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val repository: HomeRepository) : ViewModel() {
    // 这里只保存 MediaSession 的 UI 投影，不再在首页自行切换真假播放状态。
    private val playback = MutableStateFlow<PlaybackSnapshot?>(null)
    private val loading = MutableStateFlow(true)
    private val error = MutableStateFlow<String?>(null)

    val uiState = combine(repository.content, playback, loading, error) { content, snapshot, isLoading, errorMessage ->
        val renderedContent = content.withPlayback(
            activeTrackId = snapshot?.track?.id,
            isActivelyPlaying = snapshot?.isActivelyPlaying == true,
        )
        HomeUiState(
            items = buildList {
                add(HomeListItem.Header)
                if (renderedContent.recommended.isNotEmpty() || isLoading) {
                    add(HomeListItem.SectionTitle(2L, R.string.home_section_recommended, R.string.home_section_more))
                    add(
                        if (renderedContent.recommended.isEmpty()) HomeListItem.RecommendedSkeleton
                        else HomeListItem.Recommended(renderedContent.recommended.chunked(4)),
                    )
                }
                if (renderedContent.mostPlayed.isNotEmpty() || isLoading) {
                    add(HomeListItem.SectionTitle(3L, R.string.home_section_most_played))
                    add(
                        if (renderedContent.mostPlayed.isEmpty()) HomeListItem.MostPlayedSkeleton
                        else HomeListItem.MostPlayed(renderedContent.mostPlayed),
                    )
                }
                add(HomeListItem.SectionTitle(4L, R.string.home_section_my_playlist))
                add(HomeListItem.Shortcuts(renderedContent.shortcuts))
                // 标题始终位于 My Playlist 下方；无历史时不展示无效的 Play all 操作。
                add(
                    HomeListItem.SectionTitle(
                        5L,
                        R.string.home_section_recently_played,
                        R.string.home_play_all.takeIf { renderedContent.recentlyPlayed.isNotEmpty() },
                    ),
                )
                addAll(renderedContent.recentlyPlayed.map(HomeListItem::RecentTrack))
            },
            miniPlayer = snapshot?.let { MiniPlayerUi(it.track, it.isPlaying) },
            isLoading = isLoading,
            errorMessage = errorMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            runCatching { repository.refresh() }
                .onFailure { error.value = it.message ?: "Unable to load music" }
            loading.value = false
        }
    }

    /** 由连接 MediaSession 的页面同步，ViewModel 不直接推测播放器状态。 */
    fun updatePlayback(track: HomeTrackUi, isPlaying: Boolean, isActivelyPlaying: Boolean = isPlaying) {
        playback.value = PlaybackSnapshot(track, isPlaying, isActivelyPlaying)
    }

    fun clearPlayback() {
        playback.value = null
    }

    fun recentQueue(): List<HomeTrackUi> =
        uiState.value.items.filterIsInstance<HomeListItem.RecentTrack>().map { it.track }

    private data class PlaybackSnapshot(
        val track: HomeTrackUi,
        val isPlaying: Boolean,
        val isActivelyPlaying: Boolean,
    )

    class Factory(private val repository: HomeRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(repository) as T
    }
}

/** 所有首页歌曲分区共用同一份播放投影，防止 Mini Player 与列表状态分裂。 */
internal fun HomeContent.withPlayback(activeTrackId: String?, isActivelyPlaying: Boolean): HomeContent {
    fun List<HomeTrackUi>.project() = map { track ->
        track.copy(isPlaying = isActivelyPlaying && track.id == activeTrackId)
    }
    return copy(
        recommended = recommended.project(),
        mostPlayed = mostPlayed.project(),
        recentlyPlayed = recentlyPlayed.project(),
    )
}
