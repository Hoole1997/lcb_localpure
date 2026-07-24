package com.example.lcb.app.recommended

import androidx.annotation.DrawableRes
import com.example.lcb.app.player.PlayerTrack

/** 推荐列表在播放器模型之外只保存列表展示所需的小图和局部状态。 */
data class RecommendedTrackUi(
    val track: PlayerTrack,
    val artworkThumbnailUrls: List<String>,
    @param:DrawableRes val artworkFallbackRes: Int,
    val isPlaying: Boolean = false,
    val isSelected: Boolean = false,
) {
    val id: String get() = track.id
}

data class RecommendedMiniPlayerUi(
    val track: PlayerTrack,
    val artworkThumbnailUrls: List<String>,
    @param:DrawableRes val artworkFallbackRes: Int,
    val isPlaying: Boolean,
)

data class RecommendedMusicUiState(
    val tracks: List<RecommendedTrackUi> = emptyList(),
    val miniPlayer: RecommendedMiniPlayerUi? = null,
    val isInitialLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val errorMessage: String? = null,
    val loadMoreErrorMessage: String? = null,
    val isSelectionMode: Boolean = false,
) {
    val selectedCount: Int get() = tracks.count(RecommendedTrackUi::isSelected)
}
