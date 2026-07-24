package com.example.lcb.app.home

import com.example.lcb.app.R
import com.example.lcb.app.library.MusicLibraryRepository
import com.example.lcb.app.library.LibraryTrack
import com.example.lcb.music.MusicSdk
import com.example.lcb.music.model.MusicSort
import com.example.lcb.music.model.MusicTrack
import com.example.lcb.music.model.PageRequest
import com.example.lcb.music.model.TrackQuery
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

interface HomeRepository {
    val content: Flow<HomeContent>
    suspend fun refresh()
}

data class HomeContent(
    val recommended: List<HomeTrackUi> = emptyList(),
    val mostPlayed: List<HomeTrackUi> = emptyList(),
    val shortcuts: List<HomeShortcutUi> = defaultShortcuts(),
    /** 最近播放由 Room 实时提供，最多保存 50 首且按最后播放时间倒序。 */
    val recentlyPlayed: List<HomeTrackUi> = emptyList(),
)

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
            recentlyPlayed = recentlyPlayed.map(::toUi),
        )
    }

    override suspend fun refresh() = coroutineScope {
        // 两块内容互不依赖，并发请求可显著降低首屏等待时间。
        val recommended = async {
            musicSdk.tracks(
                // 推荐使用各平台周热门，避免 latest 被单个创作者的批量上传占满。
                query = TrackQuery(sort = MusicSort.POPULAR),
                page = PageRequest(limit = RECOMMENDED_LIMIT),
            ).items.map(::toUi)
        }
        val mostPlayed = async {
            musicSdk.trendingTracks(limit = MOST_PLAYED_LIMIT).items.map(::toUi)
        }
        mutableContent.value = mutableContent.value.copy(
            recommended = recommended.await(),
            mostPlayed = mostPlayed.await(),
        )
    }

    private fun toUi(track: MusicTrack) = HomeTrackUi(
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

    private fun toUi(track: LibraryTrack) = HomeTrackUi(
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

    private companion object {
        const val RECOMMENDED_LIMIT = 8
        const val MOST_PLAYED_LIMIT = 10
    }
}

private fun defaultShortcuts() = listOf(
    HomeShortcutUi(
        id = "add",
        iconRes = R.drawable.ic_home_add_playlist,
        style = ShortcutStyle.NEUTRAL,
        titleRes = R.string.home_add_new_playlist,
    ),
    HomeShortcutUi(
        id = "favorite",
        iconRes = R.drawable.ic_home_favorite,
        style = ShortcutStyle.FAVORITE,
        titleRes = R.string.home_favorite_songs,
    ),
    HomeShortcutUi(
        id = "local",
        iconRes = R.drawable.ic_home_local_playlist,
        style = ShortcutStyle.LOCAL,
        titleRes = R.string.home_local_playlists,
    ),
)
