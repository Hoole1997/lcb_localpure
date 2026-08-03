package com.example.lcb.app.search

import androidx.annotation.DrawableRes
import com.example.lcb.app.player.PlayerTrack
import com.example.lcb.app.ui.AppLoadError
import com.example.lcb.music.model.MusicArtistRef

/** 搜索展示模型不暴露平台 DTO，UI 与聚合 SDK 保持单向依赖。 */
data class SearchTrackUi(
    val id: String,
    val title: String,
    val artist: String,
    @param:DrawableRes val artworkFallbackRes: Int,
    val artworkUrl: String?,
    val artworkThumbnailUrls: List<String>,
    val streamUrl: String,
    val durationMs: Long,
    val lyrics: String?,
    val description: String?,
    val artistRef: MusicArtistRef? = null,
    val isPlaying: Boolean = false,
) {
    fun toPlayerTrack() = PlayerTrack(
        id = id,
        title = title,
        artist = artist,
        artworkUrl = artworkUrl,
        streamUrl = streamUrl,
        durationMs = durationMs,
        lyrics = lyrics,
        description = description,
        artistRef = artistRef,
        artworkThumbnailUrls = artworkThumbnailUrls,
    )
}

data class SearchUiState(
    val query: String = "",
    val tracks: List<SearchTrackUi> = emptyList(),
    val isInitialLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val hasSearched: Boolean = false,
    val initialLoadError: AppLoadError? = null,
    val loadMoreError: AppLoadError? = null,
)
