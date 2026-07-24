package com.example.lcb.app.artist

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.lcb.app.R
import com.example.lcb.app.player.PlayerTrack
import com.example.lcb.music.model.MusicArtistDetails
import com.example.lcb.music.model.MusicCollectionType
import com.example.lcb.music.model.MusicPlatform

/** 页面只接受稳定的平台与歌手 id，避免用同名歌手反查造成串页。 */
data class ArtistRequest(
    val platform: MusicPlatform,
    val artistId: String,
    val fallbackName: String,
)

data class ArtistTrackUi(
    val track: PlayerTrack,
    val artworkUrls: List<String>,
    @param:DrawableRes val artworkFallbackRes: Int,
    val isPlaying: Boolean = false,
) {
    val id: String get() = track.id
}

data class ArtistCollectionUi(
    val id: String,
    val platform: MusicPlatform,
    val type: MusicCollectionType,
    val title: String,
    /** 平台返回的日期或创建者名称；为空时由 UI 按当前语言生成合集类型/歌曲数量。 */
    val subtitle: String,
    val artworkUrls: List<String>,
    @param:DrawableRes val artworkFallbackRes: Int,
    val trackCount: Int?,
)

/**
 * ViewModel 只传递稳定的错误语义，展示层再按当前 Locale 解析资源。
 * 这样切换语言重建页面后不会继续展示旧语言或 SDK 的英文异常信息。
 */
enum class ArtistLoadError(@param:StringRes val messageRes: Int) {
    DETAILS(R.string.artist_details_load_failed),
    TRACKS(R.string.artist_songs_load_failed),
    RELEASES(R.string.artist_releases_load_failed),
    PLAYLISTS(R.string.artist_playlists_load_failed),
    MORE_TRACKS(R.string.artist_more_songs_load_failed),
}

data class ArtistMiniPlayerUi(
    val track: PlayerTrack,
    val artworkUrls: List<String>,
    @param:DrawableRes val artworkFallbackRes: Int,
    val isPlaying: Boolean,
)

data class ArtistUiState(
    val request: ArtistRequest,
    val details: MusicArtistDetails? = null,
    val tracks: List<ArtistTrackUi> = emptyList(),
    val albums: List<ArtistCollectionUi> = emptyList(),
    val playlists: List<ArtistCollectionUi> = emptyList(),
    val miniPlayer: ArtistMiniPlayerUi? = null,
    val isInitialLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMoreTracks: Boolean = false,
    val detailsError: ArtistLoadError? = null,
    val tracksError: ArtistLoadError? = null,
    val albumsError: ArtistLoadError? = null,
    val playlistsError: ArtistLoadError? = null,
    val loadMoreError: ArtistLoadError? = null,
    val isBioExpanded: Boolean = false,
    val loadingCollectionId: String? = null,
)

sealed interface ArtistEvent {
    data class OpenQueue(val queue: List<PlayerTrack>, val currentTrackId: String) : ArtistEvent
    data class Message(@param:StringRes val messageRes: Int) : ArtistEvent
}
