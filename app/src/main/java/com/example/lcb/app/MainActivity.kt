package com.example.lcb.app

import android.content.ComponentName
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.lcb.app.databinding.ActivityMainHomeBinding
import com.example.lcb.app.artist.ArtistActivity
import com.example.lcb.app.home.*
import com.example.lcb.app.library.PlaylistActivity
import com.example.lcb.app.localmusic.LocalMusicActivity
import com.example.lcb.app.library.dialog.PlaylistDialogsController
import com.example.lcb.app.player.PlayerActivity
import com.example.lcb.app.player.PlaybackQueueBottomSheet
import com.example.lcb.app.player.PlaybackService
import com.example.lcb.app.player.PlayerTrack
import com.example.lcb.app.player.toPlayerTrack
import com.example.lcb.app.player.toPlayerTrackQueue
import com.example.lcb.app.trackactions.TrackActionUiModel
import com.example.lcb.app.trackactions.TrackActionsController
import com.example.lcb.app.settings.SettingsActivity
import com.example.lcb.app.utils.BottomNativeAdController
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), HomeCallbacks {
    private lateinit var binding: ActivityMainHomeBinding
    private var displayedError: String? = null
    private val libraryRepository by lazy(LazyThreadSafetyMode.NONE) {
        MusicLibraryDependencies.repository(this)
    }
    private val homeRepository by lazy(LazyThreadSafetyMode.NONE) {
        MusicSdkHomeRepository(MusicDependencies.sdk, libraryRepository)
    }
    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(homeRepository)
    }
    private val homeAdapter by lazy(LazyThreadSafetyMode.NONE) { HomeAdapter(this) }
    private val playlistDialogs by lazy(LazyThreadSafetyMode.NONE) {
        PlaylistDialogsController(this, libraryRepository)
    }
    private val trackActions by lazy(LazyThreadSafetyMode.NONE) {
        TrackActionsController(this, repository = libraryRepository)
    }
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var queueSheet: PlaybackQueueBottomSheet? = null
    private lateinit var bottomAdController: BottomNativeAdController
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncPlaybackState(player)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureInsets()
        configureList()
        configureMiniPlayer()
        configureAds()
        observeState()
        configureBackNavigation()
    }

    override fun onStart() {
        super.onStart()
        connectPlaybackController()
    }

    override fun onStop() {
        queueSheet?.dismiss()
        queueSheet = null
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onStop()
    }

    private fun configureList() = with(binding.homeList) {
        layoutManager = LinearLayoutManager(this@MainActivity).apply {
            // 首页首屏包含多个类型，额外预取可减少快速滚动时的创建抖动。
            initialPrefetchItemCount = 8
        }
        adapter = homeAdapter.apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
        setHasFixedSize(false)
        setItemViewCacheSize(8)
    }

    private fun configureMiniPlayer() {
        binding.miniPlayer.cover.clipToOutline = true
        binding.miniPlayer.root.isVisible = false
        binding.miniPlayer.playPause.isEnabled = false
        binding.miniPlayer.queue.isEnabled = false
        binding.miniPlayer.playPause.setOnClickListener {
            controller?.let { player -> if (player.playWhenReady) player.pause() else player.play() }
        }
        binding.miniPlayer.queue.setOnClickListener { showPlaybackQueue() }
    }

    private fun configureAds() {
        bottomAdController = BottomNativeAdController(
            activity = this,
            adContainer = binding.adContainer,
            scrollingContent = binding.homeList,
            baseContentBottomPaddingDp = HOME_LIST_BOTTOM_PADDING_DP,
            miniPlayerHost = binding.miniPlayer.root,
        )
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // submitList 使用 AsyncListDiffer，差异计算不会阻塞主线程。
                    homeAdapter.submitList(state.items)
                    renderMiniPlayer(state.miniPlayer)
                    renderLoadError(state.errorMessage)
                    if (!state.isLoading) bottomAdController.loadOnce()
                }
            }
        }
    }

    private fun renderLoadError(message: String?) {
        if (message == null) {
            displayedError = null
            return
        }
        if (displayedError != message) {
            displayedError = message
            toast(message)
        }
    }

    private fun renderMiniPlayer(player: MiniPlayerUi?) {
        bottomAdController.setMiniPlayerVisible(player != null)
        with(binding.miniPlayer) {
            root.isVisible = player != null
            if (player == null) return
            root.setOnClickListener { PlayerActivity.openExisting(this@MainActivity) }
            title.text = getString(R.string.home_player_title, player.track.title, player.track.artist)
            playPause.setImageResource(if (player.isPlaying) R.drawable.ic_home_pause else R.drawable.ic_home_play)
            playPause.contentDescription = getString(if (player.isPlaying) R.string.home_pause else R.string.home_play)
            playPause.isEnabled = controller != null
            playPause.alpha = if (playPause.isEnabled) 1f else 0.45f
            queue.isEnabled = controller != null
            queue.alpha = if (queue.isEnabled) 1f else 0.45f
            Glide.with(cover).load(player.track.artworkUrl ?: player.track.artworkRes).centerCrop().into(cover)
        }
    }

    private fun configureInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = LauncherSdkGateway.returnToLauncher()
        })
    }

    override fun onSearch() = com.example.lcb.app.search.SearchActivity.open(this)
    override fun onSettings() = SettingsActivity.open(this)

    override fun onSectionAction(sectionId: Long) {
        when (sectionId) {
            2L -> com.example.lcb.app.recommended.RecommendedMusicActivity.open(this)
            5L -> viewModel.recentQueue().takeIf(List<HomeTrackUi>::isNotEmpty)?.let { queue ->
                onTrackClick(queue.first(), queue)
            }
            else -> toast("Open section")
        }
    }

    override fun onTrackClick(track: HomeTrackUi, queue: List<HomeTrackUi>) {
        // 页面跳转前做乐观展示；返回首页时会立即由 MediaSession 真实状态校准。
        viewModel.updatePlayback(track, true)
        PlayerActivity.open(this, queue, track.id)
    }
    override fun onArtistClick(track: HomeTrackUi) {
        track.artistRef?.let { ArtistActivity.open(this, it) }
    }
    override fun onTrackMore(track: HomeTrackUi) {
        trackActions.show(
            TrackActionUiModel(
                id = track.id,
                title = track.title,
                artist = track.artist,
                artworkUrls = track.artworkThumbnailUrls,
                artworkFallbackRes = track.artworkRes,
                artworkUrl = track.artworkUrl,
                streamUrl = track.streamUrl,
                durationMs = track.durationMs,
                lyrics = track.lyrics,
                description = track.description,
                artistRef = track.artistRef,
            ),
        )
    }
    override fun onShortcutClick(shortcut: HomeShortcutUi) {
        when (shortcut.style) {
            ShortcutStyle.NEUTRAL -> playlistDialogs.showCreatePlaylist()
            ShortcutStyle.FAVORITE -> PlaylistActivity.openFavorites(this)
            ShortcutStyle.LOCAL -> LocalMusicActivity.open(this)
            ShortcutStyle.CUSTOM_PLAYLIST -> shortcut.playlistId?.let { playlistId ->
                PlaylistActivity.openPlaylist(this, playlistId)
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun connectPlaybackController() {
        binding.miniPlayer.playPause.isEnabled = false
        binding.miniPlayer.queue.isEnabled = false
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (isFinishing || controllerFuture !== future) return@addListener
                runCatching { future.get() }.onSuccess { mediaController ->
                    controller = mediaController
                    mediaController.addListener(playerListener)
                    syncPlaybackState(mediaController)
                    binding.miniPlayer.playPause.isEnabled = mediaController.currentMediaItem != null
                    binding.miniPlayer.queue.isEnabled = mediaController.mediaItemCount > 0
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    /** MediaSession 是播放状态唯一来源，暂停、切歌、耳机控制和播放结束都会走这里。 */
    private fun syncPlaybackState(player: Player) {
        val track = player.currentMediaItem?.toHomeTrack(player) ?: run {
            viewModel.clearPlayback()
            return
        }
        val isPlaying = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        viewModel.updatePlayback(track, isPlaying, player.isPlaying)
        queueSheet?.updatePlayback(track.id, player.isPlaying)
    }

    private fun MediaItem.toHomeTrack(player: Player) = mediaId.takeIf(String::isNotBlank)?.let { id ->
        val playerTrack = toPlayerTrack(
            player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L,
        )
        HomeTrackUi(
            id = id,
            title = playerTrack.title,
            artist = playerTrack.artist,
            artworkRes = R.drawable.home_cover_recommended_3,
            artworkUrl = playerTrack.artworkUrl,
            streamUrl = playerTrack.streamUrl,
            durationMs = playerTrack.durationMs,
            lyrics = playerTrack.lyrics,
            description = playerTrack.description,
            artistRef = playerTrack.artistRef,
        )
    }

    private fun showPlaybackQueue() {
        val player = controller ?: return
        val tracks = player.toPlayerTrackQueue()
        if (tracks.isEmpty()) return
        queueSheet?.dismiss()
        queueSheet = PlaybackQueueBottomSheet(
            context = this,
            tracks = tracks,
            currentTrackId = player.currentMediaItem?.mediaId,
            isPlaying = player.isPlaying,
            onTrackSelected = { selected ->
                tracks.indexOfFirst { it.id == selected.id }.takeIf { it >= 0 }?.let { index ->
                    player.seekToDefaultPosition(index)
                    player.play()
                }
            },
            onDismiss = { queueSheet = null },
        ).also { it.show() }
    }

    private companion object {
        const val HOME_LIST_BOTTOM_PADDING_DP = 18
    }
}
