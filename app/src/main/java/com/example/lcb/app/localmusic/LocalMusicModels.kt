package com.example.lcb.app.localmusic

import com.example.lcb.app.player.PlayerTrack

/** MediaStore 元数据模型；音频内容始终通过 content URI 流式读取，不进入应用内存。 */
data class LocalMusicTrack(
    val mediaStoreId: Long,
    val title: String,
    val artist: String,
    val album: String?,
    val folderName: String?,
    val artworkUrl: String?,
    val contentUri: String,
    val durationMs: Long,
) {
    val id: String = "LOCAL:$mediaStoreId"

    fun toPlayerTrack() = PlayerTrack(
        id = id,
        title = title,
        artist = artist,
        artworkUrl = artworkUrl,
        streamUrl = contentUri,
        durationMs = durationMs,
    )
}

data class LocalMusicTrackUi(
    val track: LocalMusicTrack,
    val isPlaying: Boolean = false,
) {
    val id: String get() = track.id
}

/** 文件夹筛选项只保存展示所需的轻量信息，避免 UI 层接触 MediaStore 路径细节。 */
data class LocalMusicFolderUi(
    val name: String?,
    val trackCount: Int,
    val isSelected: Boolean,
) {
    val id: String = name ?: ALL_TRACKS_ID

    private companion object {
        const val ALL_TRACKS_ID = "__all_local_tracks__"
    }
}

data class LocalMusicUiState(
    val hasPermission: Boolean = false,
    val isLoading: Boolean = false,
    val tracks: List<LocalMusicTrackUi> = emptyList(),
    val folders: List<LocalMusicFolderUi> = emptyList(),
    val totalTrackCount: Int = 0,
    val folderCount: Int = 0,
    val errorMessage: String? = null,
    val miniPlayer: LocalMusicMiniPlayerUi? = null,
)

data class LocalMusicMiniPlayerUi(
    val track: PlayerTrack,
    val isPlaying: Boolean,
)
