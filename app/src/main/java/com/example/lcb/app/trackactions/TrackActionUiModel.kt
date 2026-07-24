package com.example.lcb.app.trackactions

import androidx.annotation.DrawableRes
import com.example.lcb.music.model.MusicArtistRef

/**
 * 歌曲操作弹框只依赖展示所需字段，不感知首页、搜索或推荐页各自的业务模型。
 */
data class TrackActionUiModel(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrls: List<String>,
    @param:DrawableRes val artworkFallbackRes: Int,
    val artworkUrl: String? = artworkUrls.firstOrNull(),
    val streamUrl: String = "",
    val durationMs: Long = 0L,
    val lyrics: String? = null,
    val description: String? = null,
    /** Song Info 使用稳定引用进入歌手页；旧的本地歌曲允许为空并由 Resolver 补查。 */
    val artistRef: MusicArtistRef? = null,
    val isFavorite: Boolean = false,
    /** 本地 MediaStore 歌曲没有在线歌手引用。 */
    val showSongInfo: Boolean = true,
) {
    /** 删除设备文件是严格的本地能力，不能由在线页面误配置后显示。 */
    val isLocalDeviceTrack: Boolean
        get() = id.startsWith(LOCAL_TRACK_ID_PREFIX) && streamUrl.startsWith(MEDIA_CONTENT_URI_PREFIX)

    private companion object {
        const val LOCAL_TRACK_ID_PREFIX = "LOCAL:"
        const val MEDIA_CONTENT_URI_PREFIX = "content://media/"
    }
}

enum class TrackActionType {
    SONG_INFO,
    ADD_TO_PLAYLIST,
    DOWNLOAD,
    FAVORITE_CHANGED,
    DELETE_FROM_DEVICE,
}

/** UI 先通过统一事件出口解耦；后续数据闭环可以直接在 Controller 外接入。 */
data class TrackActionEvent(
    val type: TrackActionType,
    val track: TrackActionUiModel,
    val isFavorite: Boolean = track.isFavorite,
)
