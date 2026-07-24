package com.example.lcb.app.localmusic

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lcb.app.R
import com.example.lcb.app.databinding.ActivityLocalMusicBinding
import com.example.lcb.app.player.MiniPlayerViewBinder
import com.example.lcb.app.player.PlaybackQueueBottomSheet
import com.example.lcb.app.player.PlaybackService
import com.example.lcb.app.player.PlayerActivity
import com.example.lcb.app.player.toPlayerTrack
import com.example.lcb.app.player.toPlayerTrackQueue
import com.example.lcb.app.trackactions.TrackActionUiModel
import com.example.lcb.app.trackactions.TrackActionsController
import com.example.lcb.app.utils.BottomNativeAdController
import com.example.lcb.app.utils.InterstitialAdPlacement
import com.example.lcb.app.utils.loadRequestedPostNavigationInterstitial
import com.example.lcb.app.utils.requestPostNavigationInterstitial
import com.google.common.util.concurrent.ListenableFuture
import com.google.android.material.appbar.AppBarLayout
import kotlinx.coroutines.launch
import kotlin.math.abs

class LocalMusicActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLocalMusicBinding
    private val repository by lazy(LazyThreadSafetyMode.NONE) { MediaStoreLocalMusicRepository(this) }
    private val viewModel: LocalMusicViewModel by viewModels { LocalMusicViewModel.Factory(repository) }
    private val musicAdapter by lazy(LazyThreadSafetyMode.NONE) {
        LocalMusicAdapter(onTrackClick = ::playTrack, onTrackMore = ::showTrackActions)
    }
    private val folderAdapter by lazy(LazyThreadSafetyMode.NONE) {
        LocalMusicFolderAdapter { folderName ->
            viewModel.selectFolder(folderName)
            binding.trackList.scrollToPosition(0)
        }
    }
    private val trackActions by lazy(LazyThreadSafetyMode.NONE) { TrackActionsController(this) }
    private val miniPlayerBinder by lazy(LazyThreadSafetyMode.NONE) { MiniPlayerViewBinder(binding.miniPlayer) }
    private var permissionRequestAttempted = false
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var queueSheet: PlaybackQueueBottomSheet? = null
    private var isMiniPlayerVisible = false
    private lateinit var bottomAdController: BottomNativeAdController
    private var hasPlayableTracks = false
    private var isHeaderInteractive = true
    private var appBarOffsetListener: AppBarLayout.OnOffsetChangedListener? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionRequestAttempted = true
        viewModel.setPermissionGranted(granted)
    }
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncPlayback(player)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionRequestAttempted = savedInstanceState?.getBoolean(STATE_PERMISSION_ATTEMPTED) == true
        enableEdgeToEdge()
        binding = ActivityLocalMusicBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        configureToolbar()
        configureInsets()
        configureCollapsingHeader()
        configureList()
        configureActions()
        configureMiniPlayer()
        bottomAdController = BottomNativeAdController(
            activity = this,
            adContainer = binding.adContainer,
            scrollingContent = binding.trackList,
            baseContentBottomPaddingDp = TRACK_LIST_BOTTOM_PADDING_DP,
            miniPlayerHost = binding.miniPlayerHost,
        )
        observeState()
        val granted = hasMediaPermission()
        viewModel.setPermissionGranted(granted)
        if (!granted && !permissionRequestAttempted) requestMediaPermission()
        loadRequestedPostNavigationInterstitial(
            savedInstanceState,
            condition = { hasMediaPermission() },
        )
    }

    override fun onResume() {
        super.onResume()
        viewModel.setPermissionGranted(hasMediaPermission())
    }

    override fun onStart() {
        super.onStart()
        connectPlaybackController()
    }

    override fun onStop() {
        queueSheet?.dismiss()
        queueSheet = null
        controller?.removeListener(playerListener)
        miniPlayerBinder.updateControllerState(controllerReady = false, hasQueue = false)
        controller = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PERMISSION_ATTEMPTED, permissionRequestAttempted)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        appBarOffsetListener?.let(binding.appBar::removeOnOffsetChangedListener)
        appBarOffsetListener = null
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun configureToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeActionContentDescription(R.string.player_close)
            title = getString(R.string.local_music_title)
        }
        binding.toolbar.navigationIcon?.setTint(Color.WHITE)
    }

    private fun configureInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.localMusicRoot) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(bars.left, 0, bars.right, bars.bottom)
            binding.appBar.updatePadding(top = bars.top)
            insets
        }
    }

    private fun configureCollapsingHeader() {
        appBarOffsetListener = AppBarLayout.OnOffsetChangedListener { appBar, verticalOffset ->
            val scrollRange = appBar.totalScrollRange.takeIf { it > 0 } ?: return@OnOffsetChangedListener
            val collapseFraction = abs(verticalOffset).toFloat() / scrollRange.toFloat()
            val fadeProgress = ((collapseFraction - HEADER_FADE_START) / HEADER_FADE_RANGE).coerceIn(0f, 1f)
            binding.libraryHeader.alpha = 1f - fadeProgress

            // 透明 Toolbar 下不能残留可点击的不可见控件；折叠后同时移出无障碍焦点。
            val interactive = fadeProgress < HEADER_INTERACTION_CUTOFF
            if (interactive != isHeaderInteractive) {
                isHeaderInteractive = interactive
                binding.folderList.isEnabled = interactive
                binding.playAll.isEnabled = interactive && hasPlayableTracks
                binding.libraryHeader.importantForAccessibility = if (interactive) {
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                } else {
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
            }
        }.also(binding.appBar::addOnOffsetChangedListener)
    }

    private fun configureList() = with(binding.trackList) {
        layoutManager = LinearLayoutManager(this@LocalMusicActivity).apply { initialPrefetchItemCount = 8 }
        adapter = musicAdapter.apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
        setHasFixedSize(true)
        itemAnimator = DefaultItemAnimator().apply {
            addDuration = 160L
            removeDuration = 130L
            moveDuration = 180L
            supportsChangeAnimations = false
        }
        binding.folderList.apply {
            layoutManager = LinearLayoutManager(this@LocalMusicActivity, RecyclerView.HORIZONTAL, false).apply {
                initialPrefetchItemCount = 6
            }
            adapter = folderAdapter
            setHasFixedSize(true)
            itemAnimator = null
        }
    }

    private fun configureActions() {
        binding.playAll.setOnClickListener {
            viewModel.state.value.tracks.firstOrNull()?.track?.let(::playTrack)
        }
        binding.stateAction.setOnClickListener {
            val state = viewModel.state.value
            when {
                !state.hasPermission -> handlePermissionAction()
                state.errorMessage != null -> viewModel.retry()
                else -> viewModel.retry()
            }
        }
    }

    private fun configureMiniPlayer() {
        miniPlayerBinder.setCallbacks(
            onOpenPlayer = { PlayerActivity.openExisting(this) },
            onPlayPause = {
                controller?.let { player -> if (player.playWhenReady) player.pause() else player.play() }
            },
            onQueue = ::showPlaybackQueue,
        )
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::renderState)
            }
        }
    }

    private fun renderState(state: LocalMusicUiState) {
        musicAdapter.submitList(state.tracks)
        folderAdapter.submitList(state.folders)
        binding.loading.isVisible = state.isLoading
        binding.trackList.isVisible = state.tracks.isNotEmpty()
        binding.libraryHeader.isVisible = state.totalTrackCount > 0
        hasPlayableTracks = state.tracks.isNotEmpty()
        binding.playAll.isEnabled = hasPlayableTracks && isHeaderInteractive
        binding.librarySummary.text = getString(
            R.string.local_music_library_summary,
            resources.getQuantityString(R.plurals.local_music_song_count, state.totalTrackCount, state.totalTrackCount),
            resources.getQuantityString(R.plurals.local_music_folder_count, state.folderCount, state.folderCount),
        )
        val showPermission = !state.hasPermission
        val showError = state.hasPermission && state.errorMessage != null && !state.isLoading
        val showEmpty = state.hasPermission && !state.isLoading && state.errorMessage == null && state.tracks.isEmpty()
        binding.stateContainer.isVisible = showPermission || showError || showEmpty
        when {
            showPermission -> {
                binding.stateTitle.setText(R.string.local_music_permission_title)
                binding.stateMessage.setText(R.string.local_music_permission_message)
                binding.stateAction.setText(
                    if (mustOpenSettings()) R.string.local_music_open_settings else R.string.local_music_allow_access,
                )
                binding.stateAction.isVisible = true
            }
            showError -> {
                binding.stateTitle.setText(R.string.local_music_error_title)
                binding.stateMessage.text = state.errorMessage
                binding.stateAction.setText(R.string.search_retry)
                binding.stateAction.isVisible = true
            }
            showEmpty -> {
                binding.stateTitle.setText(R.string.local_music_empty_title)
                binding.stateMessage.setText(R.string.local_music_empty_message)
                binding.stateAction.setText(R.string.local_music_scan_again)
                binding.stateAction.isVisible = true
            }
        }
        renderMiniPlayer(state.miniPlayer)
        if (state.hasPermission && state.tracks.isNotEmpty()) bottomAdController.loadOnce()
    }

    private fun renderMiniPlayer(model: LocalMusicMiniPlayerUi?) {
        isMiniPlayerVisible = model != null
        bottomAdController.setMiniPlayerVisible(isMiniPlayerVisible)
        binding.miniPlayerHost.isVisible = isMiniPlayerVisible
        if (isMiniPlayerVisible) {
            binding.miniPlayerHost.animate().cancel()
            binding.miniPlayerHost.translationY = 0f
        }
        miniPlayerBinder.render(
            model = model?.let {
                MiniPlayerViewBinder.Model(
                    track = it.track,
                    artworkUrls = listOfNotNull(it.track.artworkUrl),
                    artworkFallbackRes = R.drawable.placeholder_local_music_track,
                    isPlaying = it.isPlaying,
                )
            },
            controllerReady = controller != null,
            hasQueue = controller?.mediaItemCount?.let { it > 0 } == true,
        )
    }

    private fun playTrack(track: LocalMusicTrack) {
        val queue = viewModel.queueForTrack(track.id)
        if (queue.isNotEmpty()) PlayerActivity.openQueue(this, queue, track.id)
    }

    private fun showTrackActions(track: LocalMusicTrack) {
        trackActions.show(
            TrackActionUiModel(
                id = track.id,
                title = track.title,
                artist = track.artist,
                artworkUrls = listOfNotNull(track.artworkUrl),
                artworkFallbackRes = R.drawable.placeholder_local_music_track,
                artworkUrl = track.artworkUrl,
                streamUrl = track.contentUri,
                durationMs = track.durationMs,
                showSongInfo = false,
            ),
        )
    }

    private fun connectPlaybackController() {
        // 返回本页后先禁用旧 Controller 的按钮，连接完成时再显式恢复。
        miniPlayerBinder.updateControllerState(controllerReady = false, hasQueue = false)
        val future = MediaController.Builder(
            this,
            SessionToken(this, ComponentName(this, PlaybackService::class.java)),
        ).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (isFinishing || isDestroyed || controllerFuture !== future) return@addListener
                runCatching { future.get() }.onSuccess { mediaController ->
                    controller = mediaController
                    mediaController.addListener(playerListener)
                    syncPlayback(mediaController)
                    miniPlayerBinder.updateControllerState(
                        controllerReady = true,
                        hasQueue = mediaController.mediaItemCount > 0,
                    )
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun syncPlayback(player: Player) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        viewModel.updatePlayback(player.currentMediaItem?.toPlayerTrack(duration), player.isPlaying)
        miniPlayerBinder.updateControllerState(
            controllerReady = controller != null,
            hasQueue = player.mediaItemCount > 0,
        )
        player.currentMediaItem?.mediaId?.let { queueSheet?.updatePlayback(it, player.isPlaying) }
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

    private fun handlePermissionAction() {
        if (mustOpenSettings()) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                    Uri.fromParts("package", packageName, null),
                ),
            )
        } else {
            requestMediaPermission()
        }
    }

    private fun requestMediaPermission() {
        permissionRequestAttempted = true
        permissionLauncher.launch(requiredMediaPermission())
    }

    private fun mustOpenSettings(): Boolean = permissionRequestAttempted &&
        !ActivityCompat.shouldShowRequestPermissionRationale(this, requiredMediaPermission())

    private fun hasMediaPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        requiredMediaPermission(),
    ) == PackageManager.PERMISSION_GRANTED

    private fun requiredMediaPermission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_PERMISSION_ATTEMPTED = "local_music.permission_attempted"
        private const val HEADER_FADE_START = 0.12f
        private const val HEADER_FADE_RANGE = 0.5f
        private const val HEADER_INTERACTION_CUTOFF = 0.82f
        private const val TRACK_LIST_BOTTOM_PADDING_DP = 20

        fun open(context: Context) {
            context.startActivity(
                Intent(context, LocalMusicActivity::class.java)
                    .requestPostNavigationInterstitial(InterstitialAdPlacement.CONTENT_PAGE),
            )
        }
    }
}
