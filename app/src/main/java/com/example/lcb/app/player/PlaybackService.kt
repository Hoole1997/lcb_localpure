package com.example.lcb.app.player

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.lcb.app.MusicLibraryDependencies
import com.example.lcb.app.R
import com.example.lcb.app.analytics.MusicAnalytics
import com.example.lcb.app.library.toLibraryTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 播放器由前台媒体服务独占，避免 Activity 重建时重复创建解码器或中断播放。
 * Media3 自动根据 MediaSession 元数据维护系统媒体通知与锁屏控制。
 */
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val failedMediaIds = linkedSetOf<String>()
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastRecordedMediaId: String? = null
    private val libraryRepository by lazy(LazyThreadSafetyMode.NONE) {
        MusicLibraryDependencies.repository(applicationContext)
    }
    private val recoveryListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            mediaSession?.player?.let { player ->
                // Service 是失败恢复的唯一入口：必须先记录当前坏源，再切歌，避免页面监听被恢复流程吞掉。
                reportPlaybackFailure(player, error)
                skipFailedItem(player)
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) failedMediaIds.clear()
        }
    }
    private val historyListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) mediaSession?.player?.let(::recordCurrentTrack)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaSession?.player?.takeIf(Player::isPlaying)?.let(::recordCurrentTrack)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build().apply {
            setHandleAudioBecomingNoisy(true)
            addListener(recoveryListener)
            addListener(historyListener)
        }
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.let { session ->
            session.player.removeListener(recoveryListener)
            session.player.removeListener(historyListener)
            session.player.release()
            session.release()
        }
        mediaSession = null
        historyScope.cancel()
        super.onDestroy()
    }

    /** 只在歌曲真正开始输出音频时记录；暂停/恢复同一首不会制造重复数据库写入。 */
    private fun recordCurrentTrack(player: Player) {
        val mediaItem = player.currentMediaItem ?: return
        val mediaId = mediaItem.mediaId.takeIf(String::isNotBlank) ?: return
        if (lastRecordedMediaId == mediaId) return
        lastRecordedMediaId = mediaId
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        val track = mediaItem.toPlayerTrack(duration).toLibraryTrack(
            artworkFallbackRes = R.drawable.home_cover_recommended_3,
        )
        historyScope.launch {
            try {
                libraryRepository.recordRecentlyPlayed(track)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // 播放历史是附属能力，数据库故障不能打断前台音频播放。
                Log.w(TAG, "Unable to record recent playback for $mediaId", error)
            }
        }
    }

    /**
     * 播放可能发生在页面之外，因此错误事件不能依赖 PlayerActivity 的 MediaController 回调。
     * 元数据解析失败时仍使用 unknown 平台和原始错误码完成兜底上报，不影响后续自动切歌。
     */
    private fun reportPlaybackFailure(player: Player, error: PlaybackException) {
        val platform = runCatching {
            player.currentMediaItem
                ?.toPlayerTrack(durationMs = 0L)
                ?.artistRef
                ?.platform
        }.getOrNull()
        MusicAnalytics.playbackError(platform, error.errorCode)
    }

    /**
     * 上游声明可播放但 CDN 失效时跳到下一个尚未失败的条目。失败集合持续到队列变化，
     * 防止多个坏源在循环模式下互相反复重试。
     */
    private fun skipFailedItem(player: Player) {
        player.currentMediaItem?.mediaId?.takeIf(String::isNotBlank)?.let(failedMediaIds::add)
        val nextIndex = (1..player.mediaItemCount)
            .map { step -> (player.currentMediaItemIndex + step) % player.mediaItemCount }
            .firstOrNull { index -> player.getMediaItemAt(index).mediaId !in failedMediaIds }
        if (nextIndex == null) {
            player.pause()
            return
        }
        player.seekToDefaultPosition(nextIndex)
        player.prepare()
        player.play()
    }

    private companion object {
        const val TAG = "PlaybackService"
    }
}
