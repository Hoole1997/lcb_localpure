package com.example.lcb.app.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.lcb.app.R
import com.example.lcb.app.ui.AppLoadError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: HomeRepository,
    private val mode: HomeExperienceMode,
) : ViewModel() {
    // 这里只保存 MediaSession 的 UI 投影，不再在首页自行切换真假播放状态。
    private val playback = MutableStateFlow<PlaybackSnapshot?>(null)
    private val loading = MutableStateFlow(mode == HomeExperienceMode.ONLINE)
    private val loadError = MutableStateFlow<AppLoadError?>(null)
    private var refreshJob: Job? = null

    val uiState = combine(repository.content, playback, loading, loadError) { content, snapshot, isLoading, error ->
        val renderedContent = content.withPlayback(
            activeTrackId = snapshot?.track?.id,
            isActivelyPlaying = snapshot?.isActivelyPlaying == true,
        )
        val localLoading = renderedContent.localMusic is LocalHomeMusicState.Loading
        HomeUiState(
            mode = mode,
            items = buildHomeItems(mode, renderedContent, isLoading, error),
            miniPlayer = snapshot?.let { MiniPlayerUi(it.track, it.isPlaying) },
            isLoading = if (mode == HomeExperienceMode.LOCAL) localLoading else isLoading,
            loadError = error.takeIf { mode == HomeExperienceMode.ONLINE },
            canRequestBottomAd = when (mode) {
                HomeExperienceMode.ONLINE -> !isLoading
                HomeExperienceMode.LOCAL -> renderedContent.localMusic is LocalHomeMusicState.Loaded
            },
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        HomeUiState(mode = mode, isLoading = mode == HomeExperienceMode.ONLINE),
    )

    init {
        if (mode == HomeExperienceMode.ONLINE) refresh()
    }

    fun refresh() {
        // 错误 item 在状态切换前可能被快速连点，单飞请求避免重复消耗平台 Key。
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            if (mode == HomeExperienceMode.ONLINE) {
                loading.value = true
                loadError.value = null
            }
            try {
                repository.refresh()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (mode == HomeExperienceMode.ONLINE) loadError.value = AppLoadError.HOME
            } finally {
                if (mode == HomeExperienceMode.ONLINE) loading.value = false
            }
        }
    }

    fun setLocalMediaPermission(granted: Boolean) {
        if (mode == HomeExperienceMode.LOCAL) repository.setLocalMediaPermission(granted)
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

    fun localQueue(): List<HomeTrackUi> =
        uiState.value.items.filterIsInstance<HomeListItem.LocalTrack>().map { it.track }.take(MAX_PLAYER_QUEUE_SIZE)

    private data class PlaybackSnapshot(
        val track: HomeTrackUi,
        val isPlaying: Boolean,
        val isActivelyPlaying: Boolean,
    )

    class Factory(
        private val repository: HomeRepository,
        private val mode: HomeExperienceMode,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(repository, mode) as T
    }

    private companion object {
        const val MAX_PLAYER_QUEUE_SIZE = 100
    }
}

/** 列表结构作为纯函数生成，确保 A/B 面互斥且便于测试远端模式回归。 */
internal fun buildHomeItems(
    mode: HomeExperienceMode,
    content: HomeContent,
    isOnlineLoading: Boolean,
    onlineError: AppLoadError? = null,
): List<HomeListItem> = buildList {
    add(HomeListItem.Header(showSearch = mode == HomeExperienceMode.ONLINE))
    when (mode) {
        HomeExperienceMode.LOCAL -> addLocalHomeItems(content.localMusic)
        HomeExperienceMode.ONLINE -> addOnlineHomeItems(content, isOnlineLoading, onlineError)
    }
    add(HomeListItem.SectionTitle(HomeSectionId.MY_PLAYLIST, R.string.home_section_my_playlist))
    add(HomeListItem.Shortcuts(content.shortcuts))
    add(
        HomeListItem.SectionTitle(
            HomeSectionId.RECENTLY_PLAYED,
            R.string.home_section_recently_played,
            R.string.home_play_all.takeIf { content.recentlyPlayed.isNotEmpty() },
        ),
    )
    addAll(content.recentlyPlayed.map(HomeListItem::RecentTrack))
}

private fun MutableList<HomeListItem>.addOnlineHomeItems(
    content: HomeContent,
    isLoading: Boolean,
    error: AppLoadError?,
) {
    val hasRemoteContent = content.recommended.isNotEmpty() || content.mostPlayed.isNotEmpty()
    if (!isLoading && !hasRemoteContent && error != null) {
        add(HomeListItem.LoadError(error))
        return
    }
    if (content.recommended.isNotEmpty() || isLoading) {
        add(
            HomeListItem.SectionTitle(
                HomeSectionId.RECOMMENDED,
                R.string.home_section_recommended,
                R.string.home_section_more,
            ),
        )
        add(
            if (content.recommended.isEmpty()) HomeListItem.RecommendedSkeleton
            else HomeListItem.Recommended(content.recommended.chunked(4)),
        )
    }
    if (content.mostPlayed.isNotEmpty() || isLoading) {
        add(HomeListItem.SectionTitle(HomeSectionId.MOST_PLAYED, R.string.home_section_most_played))
        add(
            if (content.mostPlayed.isEmpty()) HomeListItem.MostPlayedSkeleton
            else HomeListItem.MostPlayed(content.mostPlayed),
        )
    }
}

private fun MutableList<HomeListItem>.addLocalHomeItems(localMusic: LocalHomeMusicState) {
    val tracks = (localMusic as? LocalHomeMusicState.Loaded)?.tracks.orEmpty()
    add(
        HomeListItem.SectionTitle(
            HomeSectionId.LOCAL_MUSIC,
            R.string.local_music_title,
            R.string.home_play_all.takeIf { tracks.isNotEmpty() },
        ),
    )
    when (localMusic) {
        LocalHomeMusicState.Hidden,
        LocalHomeMusicState.PermissionRequired -> add(
            HomeListItem.LocalState(
                titleRes = R.string.local_music_permission_title,
                messageRes = R.string.local_music_permission_message,
                actionRes = R.string.local_music_allow_access,
                action = HomeLocalStateAction.REQUEST_PERMISSION,
            ),
        )
        LocalHomeMusicState.Loading -> add(HomeListItem.LocalState(showProgress = true))
        LocalHomeMusicState.Empty -> add(
            HomeListItem.LocalState(
                titleRes = R.string.local_music_empty_title,
                messageRes = R.string.local_music_empty_message,
                actionRes = R.string.local_music_scan_again,
                action = HomeLocalStateAction.RETRY,
            ),
        )
        LocalHomeMusicState.Error -> add(
            HomeListItem.LocalState(
                titleRes = R.string.local_music_error_title,
                messageRes = AppLoadError.LOCAL_MUSIC.messageRes,
                actionRes = R.string.search_retry,
                action = HomeLocalStateAction.RETRY,
            ),
        )
        is LocalHomeMusicState.Loaded -> addAll(localMusic.tracks.map(HomeListItem::LocalTrack))
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
        localMusic = when (val local = localMusic) {
            is LocalHomeMusicState.Loaded -> local.copy(tracks = local.tracks.project())
            else -> local
        },
        recentlyPlayed = recentlyPlayed.project(),
    )
}
