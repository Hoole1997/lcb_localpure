package com.example.lcb.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lcb.app.analytics.MusicAnalytics
import com.example.lcb.app.databinding.ActivityMainHomeBinding
import com.example.lcb.app.artist.ArtistActivity
import com.example.lcb.app.home.*
import com.example.lcb.app.library.PlaylistActivity
import com.example.lcb.app.localmusic.LocalMediaPermission
import com.example.lcb.app.localmusic.LocalMusicIdentity
import com.example.lcb.app.localmusic.MediaStoreLocalMusicRepository
import com.example.lcb.app.localmusic.LocalMusicActivity
import com.example.lcb.app.library.dialog.PlaylistDialogsController
import com.example.lcb.app.player.PlayerActivity
import com.example.lcb.app.player.PlaybackQueueBottomSheet
import com.example.lcb.app.player.PlaybackService
import com.example.lcb.app.player.MiniPlayerViewBinder
import com.example.lcb.app.player.PlayerTrack
import com.example.lcb.app.player.artworkCandidates
import com.example.lcb.app.player.toPlayerTrack
import com.example.lcb.app.player.toPlayerTrackQueue
import com.example.lcb.app.trackactions.TrackActionUiModel
import com.example.lcb.app.trackactions.TrackActionsController
import com.example.lcb.app.settings.SettingsActivity
import com.example.lcb.app.utils.BottomNativeAdController
import com.example.lcb.app.utils.BusinessAdSwitchKey
import com.example.lcb.app.utils.InterstitialAdPlacement
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.corekit.core.controller.AdSlotSwitchController

class MainActivity : AppCompatActivity(), HomeCallbacks {
    private lateinit var binding: ActivityMainHomeBinding
    private var homeInitialized = false
    private var initialVisibilityHandled = false
    private var homeModeRefreshJob: Job? = null
    private var modeRestartInProgress = false
    private val libraryRepository by lazy(LazyThreadSafetyMode.NONE) {
        MusicLibraryDependencies.repository(this)
    }
    private val homeMode: HomeExperienceMode
        get() = HomeExperienceModeStore.current
    private val homeRepository by lazy(LazyThreadSafetyMode.NONE) {
        when (homeMode) {
            HomeExperienceMode.LOCAL -> LocalHomeRepository(
                localMusicRepository = MediaStoreLocalMusicRepository(this),
                libraryRepository = libraryRepository,
            )
            HomeExperienceMode.ONLINE -> MusicSdkHomeRepository(MusicDependencies.sdk, libraryRepository)
        }
    }
    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(homeRepository, homeMode)
    }
    private val homeAdapter by lazy(LazyThreadSafetyMode.NONE) { HomeAdapter(this) }
    private val miniPlayerBinder by lazy(LazyThreadSafetyMode.NONE) {
        MiniPlayerViewBinder(binding.miniPlayer)
    }
    private val playlistDialogs by lazy(LazyThreadSafetyMode.NONE) {
        PlaylistDialogsController(this, libraryRepository)
    }
    private val trackActions by lazy(LazyThreadSafetyMode.NONE) {
        TrackActionsController(
            activity = this,
            surface = MusicAnalytics.Surface.HOME,
            repository = libraryRepository,
        )
    }
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var queueSheet: PlaybackQueueBottomSheet? = null
    private var localPermissionRequestAttempted = false
    private lateinit var bottomAdController: BottomNativeAdController
    private val localPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        localPermissionRequestAttempted = true
        MusicAnalytics.localMediaPermission(
            if (granted) MusicAnalytics.Outcome.GRANTED else MusicAnalytics.Outcome.DENIED,
        )
        if (homeInitialized) viewModel.setLocalMediaPermission(granted)
    }
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncPlaybackState(player)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        localPermissionRequestAttempted = savedInstanceState
            ?.getBoolean(STATE_LOCAL_PERMISSION_ATTEMPTED) == true
        enableEdgeToEdge()
        binding = ActivityMainHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureInsets()
        configureBackNavigation()
        // 模式未解析前不提前构造本地/在线 Repository，避免错误页面闪现和无意义的权限弹框。
        binding.miniPlayer.root.visibility = android.view.View.GONE
        lifecycleScope.launch {
            MusicRemoteConfigSync.awaitHomeBootstrap()
            initializeHome(savedInstanceState)
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能从系统设置返回，恢复时重新校准权限而不是沿用旧状态。
        if (homeInitialized && homeMode == HomeExperienceMode.LOCAL) {
            viewModel.setLocalMediaPermission(LocalMediaPermission.isGranted(this))
        }
        if (homeInitialized) {
            // 首次可见前已经完成 bootstrap 强制刷新，后续每次 onResume 再执行五秒检测。
            if (initialVisibilityHandled) refreshHomeMode() else initialVisibilityHandled = true
        }
    }

    override fun onPause() {
        homeModeRefreshJob?.cancel()
        homeModeRefreshJob = null
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        if (homeInitialized) connectPlaybackController()
    }

    override fun onStop() {
        if (homeInitialized) {
            queueSheet?.dismiss()
            queueSheet = null
            controller?.removeListener(playerListener)
            controller = null
            controllerFuture?.let(MediaController::releaseFuture)
            controllerFuture = null
            miniPlayerBinder.updateControllerState(controllerReady = false, hasQueue = false)
        }
        super.onStop()
    }

    /** 只有远端模式确定后才一次性装配首页依赖，确保一个 Activity 实例只对应一种产品形态。 */
    private fun initializeHome(savedInstanceState: Bundle?) {
        if (homeInitialized || isFinishing || isDestroyed) return
        homeInitialized = true
        configureList()
        configureMiniPlayer()
        configureAds()
        observeState()
        if (savedInstanceState == null) MusicAnalytics.screenView(MusicAnalytics.Screen.HOME)
        HomeExperienceModeDiagnostics.logActivitySelection()
        initializeLocalHomeIfNeeded()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) connectPlaybackController()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) initialVisibilityHandled = true
    }

    /** 每次首页可见都异步检查最新 A/B 值；模式变化时用新 Activity 释放旧模式整套依赖。 */
    private fun refreshHomeMode() {
        if (modeRestartInProgress) return
        homeModeRefreshJob?.cancel()
        homeModeRefreshJob = lifecycleScope.launch {
            val changed = MusicRemoteConfigSync.refreshHomeModeOnResume()
            if (!changed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch

            modeRestartInProgress = true
            val replacement = Intent(this@MainActivity, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            startActivity(replacement)
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_LOCAL_PERMISSION_ATTEMPTED, localPermissionRequestAttempted)
        super.onSaveInstanceState(outState)
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
        miniPlayerBinder.setCallbacks(
            onOpenPlayer = {
                val track = viewModel.uiState.value.miniPlayer?.track ?: return@setCallbacks
                MusicAnalytics.playback(
                    MusicAnalytics.PlaybackAction.OPEN_PLAYER,
                    MusicAnalytics.Surface.HOME_MINI_PLAYER,
                    track.artistRef?.platform,
                )
                PlayerActivity.openExisting(this)
            },
            onPlayPause = {
                controller?.let { player ->
                    MusicAnalytics.playback(
                        action = if (player.playWhenReady) {
                            MusicAnalytics.PlaybackAction.PAUSE
                        } else {
                            MusicAnalytics.PlaybackAction.PLAY
                        },
                        surface = MusicAnalytics.Surface.HOME_MINI_PLAYER,
                        platform = viewModel.uiState.value.miniPlayer?.track?.artistRef?.platform,
                        queueSize = player.mediaItemCount,
                    )
                    if (player.playWhenReady) player.pause() else player.play()
                }
            },
            onQueue = ::showPlaybackQueue,
        )
        miniPlayerBinder.render(model = null, controllerReady = false, hasQueue = false)
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
                    if (
                        state.canRequestBottomAd &&
                        AdSlotSwitchController.isEnabled(BusinessAdSwitchKey.HOME_BOTTOM_NATIVE)
                    ) {
                        bottomAdController.loadOnce(position = TAG)
                    }
                }
            }
        }
    }

    private fun renderMiniPlayer(player: MiniPlayerUi?) {
        bottomAdController.setMiniPlayerVisible(player != null)
        miniPlayerBinder.render(
            model = player?.let {
                val track = it.track.toPlayerTrack()
                MiniPlayerViewBinder.Model(
                    track = track,
                    artworkUrls = track.artworkCandidates(),
                    artworkFallbackRes = it.track.artworkRes,
                    isPlaying = it.isPlaying,
                )
            },
            controllerReady = controller != null,
            hasQueue = controller?.mediaItemCount?.let { it > 0 } == true,
        )
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

    override fun onSearch() {
        if (homeMode == HomeExperienceMode.ONLINE) {
            com.example.lcb.app.search.SearchActivity.open(this)
        }
    }

    override fun onSettings() {
        SettingsActivity.open(this)
    }

    override fun onSectionAction(sectionId: Long) {
        when (sectionId) {
            HomeSectionId.RECOMMENDED -> com.example.lcb.app.recommended.RecommendedMusicActivity.open(this)
            HomeSectionId.RECENTLY_PLAYED -> viewModel.recentQueue()
                .takeIf(List<HomeTrackUi>::isNotEmpty)?.let { queue ->
                    onTrackClick(queue.first(), queue)
                }
            HomeSectionId.LOCAL_MUSIC -> viewModel.localQueue()
                .takeIf(List<HomeTrackUi>::isNotEmpty)?.let { queue ->
                    onTrackClick(queue.first(), queue)
                }
            else -> toast("Open section")
        }
    }

    override fun onTrackClick(track: HomeTrackUi, queue: List<HomeTrackUi>) {
        MusicAnalytics.trackSelected(
            source = MusicAnalytics.Surface.HOME,
            platform = track.artistRef?.platform,
            queueSize = queue.size,
        )
        // 页面跳转前做乐观展示；返回首页时会立即由 MediaSession 真实状态校准。
        viewModel.updatePlayback(track, true)
        PlayerActivity.open(this, queue, track.id)
    }
    override fun onArtistClick(track: HomeTrackUi) {
        track.artistRef?.let { artist ->
            MusicAnalytics.trackAction(
                MusicAnalytics.TrackAction.OPEN_ARTIST,
                MusicAnalytics.Surface.HOME,
                artist.platform,
            )
            ArtistActivity.open(
                this,
                artist,
                InterstitialAdPlacement.ARTIST_LIST_NAME_ENTRY,
            )
        }
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
                showSongInfo = track.artistRef != null,
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

    /** A 面首次进入直接申请音频权限；拒绝后由列表内按钮引导重试或进入系统设置。 */
    private fun initializeLocalHomeIfNeeded() {
        if (homeMode != HomeExperienceMode.LOCAL) return
        val granted = LocalMediaPermission.isGranted(this)
        viewModel.setLocalMediaPermission(granted)
        if (!granted && !localPermissionRequestAttempted) requestLocalMediaPermission()
    }

    override fun onLocalStateAction(action: HomeLocalStateAction) {
        when (action) {
            HomeLocalStateAction.REQUEST_PERMISSION -> {
                if (LocalMediaPermission.shouldOpenSettings(this, localPermissionRequestAttempted)) {
                    MusicAnalytics.localMediaPermission(MusicAnalytics.Outcome.OPEN_SETTINGS)
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                            Uri.fromParts("package", packageName, null),
                        ),
                    )
                } else {
                    requestLocalMediaPermission()
                }
            }
            HomeLocalStateAction.RETRY -> viewModel.refresh()
        }
    }

    override fun onHomeRetry() {
        viewModel.refresh()
    }

    private fun requestLocalMediaPermission() {
        localPermissionRequestAttempted = true
        localPermissionLauncher.launch(LocalMediaPermission.requiredPermission())
    }

    private fun connectPlaybackController() {
        miniPlayerBinder.updateControllerState(controllerReady = false, hasQueue = false)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (isFinishing || isDestroyed || controllerFuture !== future) return@addListener
                runCatching { future.get() }.onSuccess { mediaController ->
                    controller = mediaController
                    mediaController.addListener(playerListener)
                    syncPlaybackState(mediaController)
                    miniPlayerBinder.updateControllerState(
                        controllerReady = mediaController.currentMediaItem != null,
                        hasQueue = mediaController.mediaItemCount > 0,
                    )
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
            // MediaSession 元数据不携带 drawable，按歌曲来源恢复正确占位图，避免本地 Mini Player 回退成在线封面。
            artworkRes = if (LocalMusicIdentity.matches(id)) {
                R.drawable.placeholder_local_music_track
            } else {
                R.drawable.home_cover_recommended_3
            },
            artworkUrl = playerTrack.artworkUrl,
            artworkThumbnailUrls = playerTrack.artworkCandidates(),
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
        MusicAnalytics.playback(
            MusicAnalytics.PlaybackAction.QUEUE_OPEN,
            MusicAnalytics.Surface.HOME_MINI_PLAYER,
            queueSize = tracks.size,
        )
        queueSheet?.dismiss()
        queueSheet = PlaybackQueueBottomSheet(
            context = this,
            tracks = tracks,
            currentTrackId = player.currentMediaItem?.mediaId,
            isPlaying = player.isPlaying,
            onTrackSelected = { selected ->
                tracks.indexOfFirst { it.id == selected.id }.takeIf { it >= 0 }?.let { index ->
                    MusicAnalytics.playback(
                        MusicAnalytics.PlaybackAction.QUEUE_SELECT,
                        MusicAnalytics.Surface.HOME_MINI_PLAYER,
                        selected.artistRef?.platform,
                        tracks.size,
                    )
                    player.seekToDefaultPosition(index)
                    player.play()
                }
            },
            onDismiss = { queueSheet = null },
        ).also { it.show() }
    }

    private companion object {
        const val HOME_LIST_BOTTOM_PADDING_DP = 18
        const val STATE_LOCAL_PERMISSION_ATTEMPTED = "home.local_permission_attempted"
        private const val TAG = "MainActivity"
    }
}
