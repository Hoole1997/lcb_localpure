package com.example.lcb.app.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.example.lcb.music.model.MusicArtistRef
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PlayerTrack(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val streamUrl: String,
    val durationMs: Long,
    val lyrics: String? = null,
    val description: String? = null,
    /** 稳定歌手引用用于从任意播放入口打开歌手页，旧的本地数据允许为空。 */
    val artistRef: MusicArtistRef? = null,
)

internal const val MEDIA_METADATA_LYRICS_KEY = "com.example.lcb.metadata.LYRICS"
internal const val MEDIA_METADATA_DESCRIPTION_KEY = "com.example.lcb.metadata.DESCRIPTION"
internal const val MEDIA_METADATA_ARTIST_ID_KEY = "com.example.lcb.metadata.ARTIST_ID"
internal const val MEDIA_METADATA_ARTIST_PLATFORM_KEY = "com.example.lcb.metadata.ARTIST_PLATFORM"

enum class PlaybackMode { SEQUENTIAL, REPEAT_ONE, SHUFFLE }

data class PlayerUiState(
    val track: PlayerTrack,
    val isFavorite: Boolean = false,
    val playbackMode: PlaybackMode = PlaybackMode.SEQUENTIAL,
    val isTrackTextVisible: Boolean = false,
)

/** 页面状态使用 SavedStateHandle 恢复；真实播放状态由 MediaSession 负责。 */
class PlayerViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {
    private val initialQueue = savedStateHandle.get<String>(PlayerActivity.EXTRA_QUEUE_JSON)
        ?.takeIf { it.length <= MAX_QUEUE_JSON_CHARS }
        ?.let { json ->
            runCatching { Gson().fromJson(json, Array<PlayerTrack>::class.java).toList() }
                .getOrDefault(emptyList())
        }
        .orEmpty()
    private val initialTrackId = savedStateHandle.get<String>(PlayerActivity.EXTRA_CURRENT_ID).orEmpty()
    private val initialTrack = initialQueue.firstOrNull { it.id == initialTrackId }
        ?: initialQueue.firstOrNull()
        ?: PlayerTrack("", "", "", null, "", 0L)
    private val mutableState = MutableStateFlow(
        PlayerUiState(
            track = initialTrack,
        ),
    )
    val state = mutableState.asStateFlow()

    fun setFavorite(favorite: Boolean) = mutableState.update { it.copy(isFavorite = favorite) }
    val queue: List<PlayerTrack> = initialQueue.ifEmpty { listOf(initialTrack) }

    fun setCurrentTrack(track: PlayerTrack) = mutableState.update { state ->
        state.copy(
            track = track,
            // 上一首的反面状态不能泄漏到新曲目；同曲目配置变更则保留。
            isTrackTextVisible = state.isTrackTextVisible && state.track.id == track.id,
        )
    }

    fun setTrackTextVisible(visible: Boolean) = mutableState.update {
        it.copy(isTrackTextVisible = visible)
    }

    fun setPlaybackMode(mode: PlaybackMode) = mutableState.update { it.copy(playbackMode = mode) }

    fun nextPlaybackMode() = mutableState.update { state ->
        state.copy(
            playbackMode = when (state.playbackMode) {
                PlaybackMode.SEQUENTIAL -> PlaybackMode.REPEAT_ONE
                PlaybackMode.REPEAT_ONE -> PlaybackMode.SHUFFLE
                PlaybackMode.SHUFFLE -> PlaybackMode.SEQUENTIAL
            },
        )
    }

    private companion object {
        const val MAX_QUEUE_JSON_CHARS = 200_000
    }
}
