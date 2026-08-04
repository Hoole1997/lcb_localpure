package com.example.lcb.app.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.lcb.app.ui.AppLoadError
import com.example.lcb.music.model.MusicArtistRef

/** 首页展示模型与网络 DTO 解耦，避免 Adapter 感知具体媒体平台。 */
data class HomeTrackUi(
    val id: String,
    val title: String,
    val artist: String,
    @param:DrawableRes val artworkRes: Int,
    val artworkUrl: String? = null,
    /** 首页小图候选链，从小尺寸主节点到镜像节点按顺序回退。 */
    val artworkThumbnailUrls: List<String> = artworkUrl?.let { listOf(it) }.orEmpty(),
    val streamUrl: String = "",
    val durationMs: Long = 0L,
    val lyrics: String? = null,
    val description: String? = null,
    val artistRef: MusicArtistRef? = null,
    val isPlaying: Boolean = false,
    val isDownloaded: Boolean = false,
)

data class HomeShortcutUi(
    val id: String,
    val title: String? = null,
    @param:DrawableRes val iconRes: Int,
    val style: ShortcutStyle,
    val playlistId: Long? = null,
    val artworkUrl: String? = null,
    val artworkThumbnailUrls: List<String> = emptyList(),
    @param:StringRes val titleRes: Int? = null,
)

enum class ShortcutStyle { NEUTRAL, FAVORITE, LOCAL, CUSTOM_PLAYLIST }

/** A 面的 MediaStore 状态由 Repository 提供，UI 不接触异常或平台查询细节。 */
sealed interface LocalHomeMusicState {
    data object Hidden : LocalHomeMusicState
    data object PermissionRequired : LocalHomeMusicState
    data object Loading : LocalHomeMusicState
    data object Empty : LocalHomeMusicState
    data object Error : LocalHomeMusicState
    data class Loaded(val tracks: List<HomeTrackUi>) : LocalHomeMusicState
}

enum class HomeLocalStateAction { REQUEST_PERMISSION, RETRY }

internal object HomeSectionId {
    const val RECOMMENDED = 2L
    const val MOST_PLAYED = 3L
    const val MY_PLAYLIST = 4L
    const val RECENTLY_PLAYED = 5L
    const val LOCAL_MUSIC = 6L
}

/** 单 RecyclerView 的多类型条目；每个条目拥有跨刷新稳定的 id。 */
sealed interface HomeListItem {
    val stableId: Long

    data class Header(val showSearch: Boolean) : HomeListItem { override val stableId = 1L }
    data object RecommendedSkeleton : HomeListItem { override val stableId = 11L }
    data object MostPlayedSkeleton : HomeListItem { override val stableId = 21L }
    data class SectionTitle(
        val id: Long,
        @param:StringRes val titleRes: Int,
        @param:StringRes val actionRes: Int? = null,
    ) : HomeListItem {
        override val stableId = id
    }
    data class Recommended(val groups: List<List<HomeTrackUi>>) : HomeListItem { override val stableId = 10L }
    data class MostPlayed(val tracks: List<HomeTrackUi>) : HomeListItem { override val stableId = 20L }
    /** 在线首页首屏加载失败状态；作为列表 item 参与 Diff 更新，不使用覆盖层。 */
    data class LoadError(val error: AppLoadError) : HomeListItem { override val stableId = 12L }
    data class Shortcuts(val items: List<HomeShortcutUi>) : HomeListItem { override val stableId = 30L }
    data class LocalState(
        @param:StringRes val titleRes: Int? = null,
        @param:StringRes val messageRes: Int? = null,
        @param:StringRes val actionRes: Int? = null,
        val action: HomeLocalStateAction? = null,
        val showProgress: Boolean = false,
    ) : HomeListItem {
        override val stableId = 40L
    }
    data class LocalTrack(val track: HomeTrackUi) : HomeListItem {
        override val stableId = 20_000L + track.id.hashCode().toLong()
    }
    data class RecentTrack(val track: HomeTrackUi) : HomeListItem {
        override val stableId = 10_000L + track.id.hashCode().toLong()
    }
}

data class MiniPlayerUi(val track: HomeTrackUi, val isPlaying: Boolean)

data class HomeUiState(
    val mode: HomeExperienceMode = HomeExperienceMode.LOCAL,
    val items: List<HomeListItem> = emptyList(),
    val miniPlayer: MiniPlayerUi? = null,
    val isLoading: Boolean = true,
    val loadError: AppLoadError? = null,
    val canRequestBottomAd: Boolean = false,
)
