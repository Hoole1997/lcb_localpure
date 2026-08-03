package com.example.lcb.app.home

import com.example.lcb.app.R
import com.example.lcb.app.library.LibraryTrack
import com.example.lcb.app.library.MusicLibraryRepository
import com.example.lcb.app.localmusic.LocalMusicRepository
import com.example.lcb.app.localmusic.LocalMusicIdentity
import com.example.lcb.app.localmusic.LocalMusicTrack
import com.example.lcb.music.MusicSdk
import com.example.lcb.music.model.MusicSort
import com.example.lcb.music.model.MusicTrack
import com.example.lcb.music.model.PageRequest
import com.example.lcb.music.model.TrackQuery
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update

interface HomeRepository {
    val content: Flow<HomeContent>
    suspend fun refresh()

    /** 在线首页不需要媒体权限，默认实现保持无操作。 */
    fun setLocalMediaPermission(granted: Boolean) = Unit
}

data class HomeContent(
    val recommended: List<HomeTrackUi> = emptyList(),
    val mostPlayed: List<HomeTrackUi> = emptyList(),
    val shortcuts: List<HomeShortcutUi> = defaultShortcuts(),
    val localMusic: LocalHomeMusicState = LocalHomeMusicState.Hidden,
    /** 最近播放由 Room 实时提供，最多保存 50 首且按最后播放时间倒序。 */
    val recentlyPlayed: List<HomeTrackUi> = emptyList(),
)

/** A 面直接观察 MediaStore；权限撤销、文件删除和系统扫描都会通过同一条 Flow 更新首页。 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalHomeRepository(
    private val localMusicRepository: LocalMusicRepository,
    libraryRepository: MusicLibraryRepository,
) : HomeRepository {
    private val permissionGranted = MutableStateFlow(false)
    private val refreshGeneration = MutableStateFlow(0L)

    private val localMusicState: Flow<LocalHomeMusicState> = combine(
        permissionGranted,
        refreshGeneration,
    ) { granted, generation -> granted to generation }
        .distinctUntilChanged()
        .flatMapLatest { (granted, _) ->
            if (!granted) {
                flowOf(LocalHomeMusicState.PermissionRequired)
            } else {
                localMusicRepository.observeTracks()
                    .map<List<LocalMusicTrack>, LocalHomeMusicState> { tracks ->
                        if (tracks.isEmpty()) {
                            LocalHomeMusicState.Empty
                        } else {
                            LocalHomeMusicState.Loaded(tracks.map(LocalMusicTrack::toHomeTrackUi))
                        }
                    }
                    .onStart { emit(LocalHomeMusicState.Loading) }
                    .catch { error ->
                        emit(
                            if (error is SecurityException) {
                                LocalHomeMusicState.PermissionRequired
                            } else {
                                LocalHomeMusicState.Error
                            },
                        )
                    }
            }
        }

    override val content: Flow<HomeContent> = combine(
        localMusicState,
        libraryRepository.observePlaylists(),
        libraryRepository.observeRecentlyPlayed(),
    ) { localMusic, playlists, recentlyPlayed ->
        HomeContent(
            shortcuts = defaultShortcuts(includeLocalShortcut = false) + playlists.map { playlist ->
                HomeShortcutUi(
                    id = "playlist:${playlist.id}",
                    title = playlist.name,
                    iconRes = R.drawable.ic_playlist_small,
                    style = ShortcutStyle.CUSTOM_PLAYLIST,
                    playlistId = playlist.id,
                    artworkUrl = playlist.artworkUrl,
                    artworkThumbnailUrls = playlist.artworkThumbnailUrls,
                )
            },
            localMusic = localMusic,
            // A 面保持纯本地体验，避免历史 B 面歌曲重新出现在首页。
            recentlyPlayed = recentlyPlayed
                .filter { LocalMusicIdentity.matches(it.id) }
                .map(::libraryTrackToHomeUi),
        )
    }

    override fun setLocalMediaPermission(granted: Boolean) {
        permissionGranted.value = granted
    }

    override suspend fun refresh() {
        if (permissionGranted.value) refreshGeneration.update { it + 1L }
    }
}

/** 使用聚合 SDK 拉取真实首页数据，平台容灾、Key 轮换由 SDK 内部负责。 */
class MusicSdkHomeRepository(
    private val musicSdk: MusicSdk,
    libraryRepository: MusicLibraryRepository,
) : HomeRepository {
    private val mutableContent = MutableStateFlow(HomeContent())
    override val content: Flow<HomeContent> = combine(
        mutableContent,
        libraryRepository.observePlaylists(),
        libraryRepository.observeRecentlyPlayed(),
    ) { remoteContent, playlists, recentlyPlayed ->
        remoteContent.copy(
            shortcuts = defaultShortcuts() + playlists.map { playlist ->
                HomeShortcutUi(
                    id = "playlist:${playlist.id}",
                    title = playlist.name,
                    iconRes = R.drawable.ic_playlist_small,
                    style = ShortcutStyle.CUSTOM_PLAYLIST,
                    playlistId = playlist.id,
                    artworkUrl = playlist.artworkUrl,
                    artworkThumbnailUrls = playlist.artworkThumbnailUrls,
                )
            },
            recentlyPlayed = recentlyPlayed.map(::libraryTrackToHomeUi),
        )
    }

    override suspend fun refresh() = coroutineScope {
        // 两块内容互不依赖，并发请求可显著降低首屏等待时间。
        val recommended = async {
            musicSdk.tracks(
                // 推荐使用各平台周热门，避免 latest 被单个创作者的批量上传占满。
                query = TrackQuery(sort = MusicSort.POPULAR),
                page = PageRequest(limit = RECOMMENDED_LIMIT),
            ).items.map(::musicTrackToHomeUi)
        }
        val mostPlayed = async {
            musicSdk.trendingTracks(limit = MOST_PLAYED_LIMIT).items.map(::musicTrackToHomeUi)
        }
        mutableContent.value = mutableContent.value.copy(
            recommended = recommended.await(),
            mostPlayed = mostPlayed.await(),
        )
    }

    private companion object {
        const val RECOMMENDED_LIMIT = 8
        const val MOST_PLAYED_LIMIT = 10
    }
}

private fun defaultShortcuts(includeLocalShortcut: Boolean = true) = buildList {
    add(
        HomeShortcutUi(
            id = "add",
            iconRes = R.drawable.ic_home_add_playlist,
            style = ShortcutStyle.NEUTRAL,
            titleRes = R.string.home_add_new_playlist,
        ),
    )
    add(
        HomeShortcutUi(
            id = "favorite",
            iconRes = R.drawable.ic_home_favorite,
            style = ShortcutStyle.FAVORITE,
            titleRes = R.string.home_favorite_songs,
        ),
    )
    if (includeLocalShortcut) {
        add(
            HomeShortcutUi(
                id = "local",
                iconRes = R.drawable.ic_home_local_playlist,
                style = ShortcutStyle.LOCAL,
                titleRes = R.string.home_local_playlists,
            ),
        )
    }
}

private fun LocalMusicTrack.toHomeTrackUi() = HomeTrackUi(
    id = id,
    title = title,
    artist = artist,
    artworkRes = R.drawable.placeholder_local_music_track,
    artworkUrl = artworkUrl,
    artworkThumbnailUrls = listOfNotNull(artworkUrl),
    streamUrl = contentUri,
    durationMs = durationMs,
)

private fun musicTrackToHomeUi(track: MusicTrack) = HomeTrackUi(
    id = "${track.platform}:${track.id}",
    title = track.title,
    artist = track.artistName,
    artworkRes = R.drawable.home_cover_recommended_3,
    artworkUrl = track.artwork?.preferredUrl ?: track.artworkUrl,
    artworkThumbnailUrls = track.artwork?.thumbnailCandidates().orEmpty()
        .ifEmpty { listOfNotNull(track.artworkUrl) },
    streamUrl = track.streamUrl,
    durationMs = track.durationMs,
    lyrics = track.lyrics,
    description = track.description,
    artistRef = track.artist,
)

private fun libraryTrackToHomeUi(track: LibraryTrack) = HomeTrackUi(
    id = track.id,
    title = track.title,
    artist = track.artist,
    artworkRes = track.artworkFallbackRes,
    artworkUrl = track.artworkUrl,
    artworkThumbnailUrls = track.artworkThumbnailUrls,
    streamUrl = track.streamUrl,
    durationMs = track.durationMs,
    lyrics = track.lyrics,
    description = track.description,
    artistRef = track.artistRef,
)
