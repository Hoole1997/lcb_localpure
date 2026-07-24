package com.example.lcb.app.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.lcb.app.R
import com.example.lcb.app.MusicLibraryDependencies
import com.example.lcb.app.artist.ArtistActivity
import com.example.lcb.app.databinding.ActivityPlayerBinding
import com.example.lcb.app.home.HomeTrackUi
import com.example.lcb.app.library.dialog.PlaylistDialogsController
import com.example.lcb.app.library.toLibraryTrack
import com.example.lcb.app.utils.InterstitialAdPlacement
import com.example.lcb.app.utils.loadRequestedPostNavigationInterstitial
import com.example.lcb.app.utils.requestPostNavigationInterstitial
import com.google.common.util.concurrent.ListenableFuture
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var imageLoader: RequestManager
    private val viewModel: PlayerViewModel by viewModels()
    private val libraryRepository by lazy(LazyThreadSafetyMode.NONE) {
        MusicLibraryDependencies.repository(this)
    }
    private val playlistDialogs by lazy(LazyThreadSafetyMode.NONE) {
        PlaylistDialogsController(this, libraryRepository)
    }
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private var favoriteTrackJob: Job? = null
    private var queueSheet: PlaybackQueueBottomSheet? = null
    private var activeQueue: List<PlayerTrack> = emptyList()
    private var userSeeking = false

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = renderPlayback(player)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaId?.let { id ->
                activeQueue.firstOrNull { it.id == id }?.let(::renderTrack)
                queueSheet?.updatePlayback(id, controller?.isPlaying == true)
            }
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Toast.makeText(this@PlayerActivity, error.localizedMessage, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 在 Activity 有效时只创建一次，销毁阶段不再通过 View 重新获取 RequestManager。
        imageLoader = Glide.with(this)
        renderTrack(viewModel.state.value.track)
        applyInsets()
        configureActions()
        observeUiState()
        loadRequestedPostNavigationInterstitial(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        connectController()
    }

    override fun onStop() {
        queueSheet?.dismiss()
        queueSheet = null
        progressJob?.cancel()
        progressJob = null
        binding.artwork.stopProgressAnimation()
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onStop()
    }

    override fun onDestroy() {
        binding.artwork.onTextVisibilityChanged = null
        // 主动清理 View target，避免解码完成回调继续持有已销毁的 Activity View。
        imageLoader.clear(binding.artwork.artwork)
        super.onDestroy()
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (isFinishing || controllerFuture !== future) return@addListener
                runCatching { future.get() }
                    .onSuccess(::onControllerConnected)
                    .onFailure { Toast.makeText(this, R.string.player_invalid_track, Toast.LENGTH_SHORT).show() }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun onControllerConnected(mediaController: MediaController) {
        controller = mediaController
        mediaController.addListener(playerListener)
        val requestedQueue = viewModel.queue.filter { it.id.isNotBlank() && it.streamUrl.isNotBlank() }
        val currentTrack = viewModel.state.value.track
        if (requestedQueue.isEmpty() && mediaController.mediaItemCount == 0) {
            Toast.makeText(this, R.string.player_invalid_track, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (requestedQueue.isNotEmpty()) {
            activeQueue = requestedQueue
            val queueIds = requestedQueue.map(PlayerTrack::id)
            val currentQueueIds = List(mediaController.mediaItemCount) { mediaController.getMediaItemAt(it).mediaId }
            val targetIndex = requestedQueue.indexOfFirst { it.id == currentTrack.id }.coerceAtLeast(0)
            when {
                currentQueueIds != queueIds -> {
                    mediaController.setMediaItems(requestedQueue.map(::toMediaItem), targetIndex, C.TIME_UNSET)
                    mediaController.prepare()
                    mediaController.play()
                }
                mediaController.currentMediaItemIndex != targetIndex -> {
                    mediaController.seekToDefaultPosition(targetIndex)
                    mediaController.play()
                }
            }
            // 从业务列表主动点歌表示播放意图；仅 openExisting 会保留暂停状态。
            if (!mediaController.playWhenReady) mediaController.play()
        } else {
            // 从 Mini Player 返回时沿用 MediaSession 已有队列，绝不重建成单曲队列。
            activeQueue = List(mediaController.mediaItemCount) { index ->
                mediaController.getMediaItemAt(index).toPlayerTrack(
                    if (index == mediaController.currentMediaItemIndex) mediaController.duration.coerceAtLeast(0L) else 0L,
                )
            }
        }
        val mode = when {
            mediaController.repeatMode == Player.REPEAT_MODE_ONE -> PlaybackMode.REPEAT_ONE
            mediaController.shuffleModeEnabled -> PlaybackMode.SHUFFLE
            else -> PlaybackMode.SEQUENTIAL
        }
        viewModel.setPlaybackMode(mode)
        activeQueue.firstOrNull { it.id == mediaController.currentMediaItem?.mediaId }
            ?.let(::renderTrack)
            ?: currentTrack.takeIf { it.id.isNotBlank() }?.let(::renderTrack)
        if (mediaController.playbackState == Player.STATE_IDLE) {
            mediaController.prepare()
        }
        renderPlayback(mediaController)
        startProgressUpdates()
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.favorite.alpha = if (state.isFavorite) 1f else 0.78f
                    binding.favorite.setImageResource(
                        if (state.isFavorite) {
                            R.drawable.ic_track_action_favorite_filled
                        } else {
                            R.drawable.ic_player_v_favorite
                        },
                    )
                    renderPlaybackMode(state.playbackMode)
                    controller?.let { applyPlaybackMode(it, state.playbackMode) }
                }
            }
        }
    }

    private fun configureActions() {
        binding.artwork.onTextVisibilityChanged = viewModel::setTrackTextVisible
        binding.back.setOnClickListener { finish() }
        binding.play.setOnClickListener { controller?.let { if (it.playWhenReady) it.pause() else it.play() } }
        binding.previous.setOnClickListener { skipAndPlay { seekToPrevious() } }
        binding.next.setOnClickListener { skipAndPlay { seekToNext() } }
        binding.shuffle.setOnClickListener {
            viewModel.nextPlaybackMode()
        }
        binding.favorite.setOnClickListener {
            val track = viewModel.state.value.track
            if (track.id.isBlank()) return@setOnClickListener
            lifecycleScope.launch {
                libraryRepository.setFavorite(
                    track.toLibraryTrack(artworkFallbackRes = R.drawable.home_cover_recommended_3),
                    favorite = !viewModel.state.value.isFavorite,
                )
            }
        }
        binding.addToPlaylist.setOnClickListener {
            val track = viewModel.state.value.track
            if (track.id.isNotBlank()) {
                playlistDialogs.showPlaylistPicker(
                    track.toLibraryTrack(artworkFallbackRes = R.drawable.home_cover_recommended_3),
                )
            }
        }
        binding.queue.setOnClickListener { showPlaybackQueue() }
        binding.share.setOnClickListener { shareTrack() }
        binding.seekBar.onSeekStarted = { userSeeking = true }
        binding.seekBar.onSeekChanged = { fraction ->
            val duration = controller?.duration?.takeIf { it > 0 }
                ?: viewModel.state.value.track.durationMs
            binding.currentTime.text = formatTime((duration * fraction).toLong())
        }
        binding.seekBar.onSeekFinished = { fraction ->
            controller?.takeIf { it.duration > 0 }?.let { player ->
                player.seekTo((player.duration * fraction).toLong())
            }
            userSeeking = false
        }
        binding.seekBar.onSeekCancelled = {
            userSeeking = false
            controller?.let(::renderProgress)
        }
    }

    /**
     * 上一首/下一首是用户明确发起的新播放请求，因此即使当前处于暂停状态也应立即播放。
     * 自动跳过不可播放歌曲的逻辑不复用这里，避免后台错误恢复意外改变用户的暂停意图。
     */
    private fun skipAndPlay(skipAction: Player.() -> Unit) {
        controller?.run {
            skipAction()
            play()
        }
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    controller?.let(::renderProgress)
                    delay(PROGRESS_INTERVAL_MS)
                }
            }
        }
    }

    private fun renderPlayback(player: Player) {
        // 缓冲期间 playWhenReady 仍为 true，按钮不会闪回播放态。
        binding.play.setImageResource(if (player.playWhenReady) R.drawable.ic_player_v_pause else R.drawable.ic_player_v_play)
        binding.play.contentDescription = getString(if (player.playWhenReady) R.string.home_pause else R.string.home_play)
        binding.previous.isEnabled = player.mediaItemCount > 1
        binding.next.isEnabled = player.mediaItemCount > 1
        binding.previous.alpha = if (binding.previous.isEnabled) 1f else 0.38f
        binding.next.alpha = if (binding.next.isEnabled) 1f else 0.38f
        player.currentMediaItem?.mediaId?.let { queueSheet?.updatePlayback(it, player.isPlaying) }
        renderProgress(player)
    }

    private fun renderProgress(player: Player) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: viewModel.state.value.track.durationMs.coerceAtLeast(0L)
        val position = player.currentPosition.coerceAtLeast(0L).coerceAtMost(duration.coerceAtLeast(0L))
        binding.currentTime.text = formatTime(position)
        binding.duration.text = formatTime(duration)
        val ratio = if (duration > 0) position.toFloat() / duration else 0f
        if (!userSeeking) binding.seekBar.setProgress(ratio)
        binding.artwork.updatePlayback(position, duration, player.isPlaying)
    }

    private fun renderTrack(track: PlayerTrack) {
        viewModel.setCurrentTrack(track)
        observeFavorite(track.id)
        binding.title.text = track.title
        binding.artist.text = track.artist
        binding.artist.apply {
            isClickable = track.artistRef != null
            isFocusable = track.artistRef != null
            alpha = if (track.artistRef != null) 1f else 0.82f
            setOnClickListener {
                track.artistRef?.let { artist -> ArtistActivity.open(this@PlayerActivity, artist) }
            }
        }
        renderTrackText(track)
        // 每次 Activity 创建和媒体切换都重新绑定 target；磁盘/内存缓存避免重复下载。
        imageLoader.clear(binding.artwork.artwork)
        imageLoader
            .load(track.artworkUrl?.takeIf(String::isNotBlank) ?: R.drawable.home_cover_recommended_3)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .placeholder(R.drawable.bg_player_artwork)
            .error(R.drawable.home_cover_recommended_3)
            .override(MAX_ARTWORK_PIXELS, MAX_ARTWORK_PIXELS)
            .centerCrop()
            .dontAnimate()
            .into(binding.artwork.artwork)
    }

    private fun observeFavorite(trackId: String) {
        favoriteTrackJob?.cancel()
        viewModel.setFavorite(false)
        if (trackId.isBlank()) {
            return
        }
        favoriteTrackJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                libraryRepository.observeFavorite(trackId).collect(viewModel::setFavorite)
            }
        }
    }

    /**
     * 歌词和平台介绍共用一个可滚动区域，但通过标题明确区分语义。
     * 有歌词时优先展示歌词；Audius 常见的 HTML 介绍先转成纯文本，避免标签直接出现在 UI。
     */
    private fun renderTrackText(track: PlayerTrack) {
        val trackText = track.resolveDisplayText()
        val content = when (trackText?.type) {
            TrackTextType.LYRICS -> trackText.rawText
            TrackTextType.DESCRIPTION -> HtmlCompat.fromHtml(
                trackText.rawText,
                HtmlCompat.FROM_HTML_MODE_COMPACT,
            ).toString().trim().takeIf(String::isNotEmpty)
            null -> null
        }

        val labelRes = when (trackText?.type) {
            TrackTextType.LYRICS -> R.string.player_lyrics
            TrackTextType.DESCRIPTION -> R.string.player_about_track
            null -> null
        }
        binding.artwork.setTrackText(
            labelRes = labelRes,
            content = content,
            showText = viewModel.state.value.isTrackTextVisible,
        )
    }

    private fun toMediaItem(track: PlayerTrack): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setArtworkUri(track.artworkUrl?.takeIf(String::isNotBlank)?.let(Uri::parse))
            .setExtras(
                Bundle().apply {
                    track.lyrics?.takeIf(String::isNotBlank)?.let { putString(MEDIA_METADATA_LYRICS_KEY, it) }
                    track.description?.takeIf(String::isNotBlank)?.let {
                        putString(MEDIA_METADATA_DESCRIPTION_KEY, it)
                    }
                    track.artistRef?.let { artist ->
                        putString(MEDIA_METADATA_ARTIST_ID_KEY, artist.id)
                        putString(MEDIA_METADATA_ARTIST_PLATFORM_KEY, artist.platform.name)
                    }
                },
            )
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(track.streamUrl)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun applyPlaybackMode(player: Player, mode: PlaybackMode) {
        when (mode) {
            PlaybackMode.SEQUENTIAL -> {
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_ALL
            }
            PlaybackMode.REPEAT_ONE -> {
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_ONE
            }
            PlaybackMode.SHUFFLE -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = true
            }
        }
    }

    private fun renderPlaybackMode(mode: PlaybackMode) {
        binding.shuffle.setImageResource(
            when (mode) {
                PlaybackMode.SEQUENTIAL -> R.drawable.ic_player_v_sequential
                PlaybackMode.REPEAT_ONE -> R.drawable.ic_player_v_repeat_one
                PlaybackMode.SHUFFLE -> R.drawable.ic_player_v_shuffle
            },
        )
        binding.shuffle.alpha = if (mode == PlaybackMode.SEQUENTIAL) 0.72f else 1f
        binding.shuffle.contentDescription = playbackModeLabel(mode)
    }

    /** 队列弹层只负责展示；实际切歌仍交给 MediaController，保持播放状态单一来源。 */
    private fun showPlaybackQueue() {
        val player = controller ?: return
        val tracks = activeQueue
        if (tracks.isEmpty()) return
        queueSheet?.dismiss()
        queueSheet = PlaybackQueueBottomSheet(
            context = this,
            tracks = tracks,
            currentTrackId = player.currentMediaItem?.mediaId,
            isPlaying = player.isPlaying,
            onTrackSelected = { track ->
                val index = tracks.indexOfFirst { it.id == track.id }
                if (index >= 0) {
                    player.seekToDefaultPosition(index)
                    player.play()
                }
            },
            onDismiss = { queueSheet = null },
        ).also { it.show() }
    }

    private fun playbackModeLabel(mode: PlaybackMode) = getString(
        when (mode) {
            PlaybackMode.SEQUENTIAL -> R.string.player_sequential
            PlaybackMode.REPEAT_ONE -> R.string.player_repeat_one
            PlaybackMode.SHUFFLE -> R.string.player_shuffle
        },
    )

    private fun shareTrack() {
        val storeUrl = "https://play.google.com/store/apps/details?id=$packageName"
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, getString(R.string.player_share_app, getString(R.string.app_name), storeUrl))
                },
                getString(R.string.player_share),
            ),
        )
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.playerRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds.coerceAtLeast(0L))
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    companion object {
        const val EXTRA_QUEUE_JSON = "player.queue.json"
        const val EXTRA_CURRENT_ID = "player.current.id"
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val MAX_ARTWORK_PIXELS = 720
        private const val MAX_QUEUE_SIZE = 100

        /** 对外调用时传入完整列表和当前歌曲 id，队列会限制大小避免 Binder 事务过大。 */
        fun open(context: Context, queue: List<HomeTrackUi>, currentTrackId: String) {
            val tracks = queue.asSequence()
                .distinctBy(HomeTrackUi::id)
                .take(MAX_QUEUE_SIZE)
                .map { track ->
                    PlayerTrack(
                        id = track.id,
                        title = track.title,
                        artist = track.artist,
                        artworkUrl = track.artworkUrl,
                        streamUrl = track.streamUrl,
                        durationMs = track.durationMs,
                        lyrics = track.lyrics,
                        description = track.description,
                        artistRef = track.artistRef,
                    )
                }
                .toCollection(ArrayList())
            openQueue(context, tracks, currentTrackId)
        }

        /** SDK 或其他业务模块可直接使用播放器模型，不需要依赖首页 UI 模型。 */
        fun openQueue(context: Context, queue: List<PlayerTrack>, currentTrackId: String) {
            val tracks = queue.asSequence()
                .filter { it.id.isNotBlank() && it.streamUrl.isNotBlank() }
                .distinctBy(PlayerTrack::id)
                .take(MAX_QUEUE_SIZE)
                .toCollection(ArrayList())
            if (tracks.isEmpty()) return
            context.startActivity(
                Intent(context, PlayerActivity::class.java).apply {
                    putExtra(EXTRA_QUEUE_JSON, Gson().toJson(tracks))
                    putExtra(EXTRA_CURRENT_ID, currentTrackId)
                }.requestPostNavigationInterstitial(InterstitialAdPlacement.PLAYBACK_START),
            )
        }

        /** 从 Mini Player 返回完整播放器时，继续使用 MediaSession 中的现有队列和位置。 */
        fun openExisting(context: Context) {
            context.startActivity(Intent(context, PlayerActivity::class.java))
        }
    }
}
