package com.example.lcb.app.player

import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import com.example.lcb.app.R
import com.example.lcb.app.databinding.ViewHomeMiniPlayerBinding
import com.example.lcb.app.ui.TrackArtworkLoader

/** Mini Player 的纯 View 绑定器；播放生命周期仍由宿主 Activity 的 MediaController 管理。 */
class MiniPlayerViewBinder(
    private val binding: ViewHomeMiniPlayerBinding,
) {
    fun setCallbacks(
        onOpenPlayer: () -> Unit,
        onPlayPause: () -> Unit,
        onQueue: () -> Unit,
    ) {
        binding.root.setOnClickListener { onOpenPlayer() }
        binding.playPause.setOnClickListener { onPlayPause() }
        binding.queue.setOnClickListener { onQueue() }
        binding.cover.clipToOutline = true
    }

    fun render(model: Model?, controllerReady: Boolean, hasQueue: Boolean) {
        binding.root.isVisible = model != null
        if (model == null) {
            updateControllerState(controllerReady = false, hasQueue = false)
            TrackArtworkLoader.clear(binding.cover)
            return
        }
        binding.title.text = binding.root.context.getString(
            R.string.home_player_title,
            model.track.title,
            model.track.artist,
        )
        binding.playPause.setImageResource(if (model.isPlaying) R.drawable.ic_home_pause else R.drawable.ic_home_play)
        binding.playPause.contentDescription = binding.root.context.getString(
            if (model.isPlaying) R.string.home_pause else R.string.home_play,
        )
        updateControllerState(controllerReady, hasQueue)
        TrackArtworkLoader.load(binding.cover, model.artworkUrls, model.artworkFallbackRes)
    }

    /**
     * MediaController 的连接生命周期与歌曲模型是两条独立状态链。
     * 页面从播放器返回时歌曲可能完全没变，因此必须允许宿主单独刷新控制按钮，不能依赖 StateFlow 重发歌曲。
     */
    fun updateControllerState(controllerReady: Boolean, hasQueue: Boolean) {
        binding.playPause.isEnabled = controllerReady
        binding.playPause.alpha = if (controllerReady) 1f else DISABLED_ALPHA
        binding.queue.isEnabled = controllerReady && hasQueue
        binding.queue.alpha = if (binding.queue.isEnabled) 1f else DISABLED_ALPHA
    }

    data class Model(
        val track: PlayerTrack,
        val artworkUrls: List<String>,
        @param:DrawableRes val artworkFallbackRes: Int,
        val isPlaying: Boolean,
    )

    private companion object {
        const val DISABLED_ALPHA = 0.45f
    }
}
