package com.example.lcb.app.recommended

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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
import com.example.lcb.app.MusicDependencies
import com.example.lcb.app.R
import com.example.lcb.app.artist.ArtistActivity
import com.example.lcb.app.databinding.ActivityRecommendedMusicBinding
import com.example.lcb.app.player.MiniPlayerViewBinder
import com.example.lcb.app.player.PlaybackQueueBottomSheet
import com.example.lcb.app.player.PlaybackService
import com.example.lcb.app.player.PlayerActivity
import com.example.lcb.app.player.PlayerTrack
import com.example.lcb.app.player.toPlayerTrack
import com.example.lcb.app.player.toPlayerTrackQueue
import com.example.lcb.app.trackactions.TrackActionUiModel
import com.example.lcb.app.trackactions.TrackActionsController
import com.example.lcb.app.utils.BottomNativeAdController
import com.example.lcb.app.utils.InterstitialAdPlacement
import com.example.lcb.app.utils.loadRequestedPostNavigationInterstitial
import com.example.lcb.app.utils.requestPostNavigationInterstitial
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch

class RecommendedMusicActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRecommendedMusicBinding
    private val repository by lazy(LazyThreadSafetyMode.NONE) {
        MusicSdkRecommendedMusicRepository(MusicDependencies.sdk)
    }
    private val viewModel: RecommendedMusicViewModel by viewModels {
        RecommendedMusicViewModel.Factory(repository)
    }
    private val musicAdapter by lazy(LazyThreadSafetyMode.NONE) {
        RecommendedMusicAdapter(
            onTrackClick = ::playTrack,
            onArtistClick = { item -> item.track.artistRef?.let { ArtistActivity.open(this, it) } },
            onTrackMore = ::showTrackActions,
            onSelectionChanged = { viewModel.toggleSelection(it.id) },
            onRetryLoadMore = viewModel::retry,
        )
    }
    private val trackActions by lazy(LazyThreadSafetyMode.NONE) { TrackActionsController(this) }
    private val miniPlayerBinder by lazy(LazyThreadSafetyMode.NONE) { MiniPlayerViewBinder(binding.miniPlayer) }
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var queueSheet: PlaybackQueueBottomSheet? = null
    private var isMiniPlayerVisible = false
    private lateinit var bottomAdController: BottomNativeAdController
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncPlaybackState(player)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityRecommendedMusicBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        configureToolbar()
        configureInsets()
        configureList()
        configureActions()
        configureMiniPlayer()
        bottomAdController = BottomNativeAdController(
            activity = this,
            adContainer = binding.adContainer,
            scrollingContent = binding.musicList,
            baseContentBottomPaddingDp = LIST_BOTTOM_PADDING_DP,
            miniPlayerHost = binding.miniPlayerHost,
        )
        observeState()
        // Figma 的 60dp 广告位使用真实广告；加载失败时 GONE，不留下空白区域。
        bottomAdController.loadOnce()
        loadRequestedPostNavigationInterstitial(savedInstanceState)
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

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun configureToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeActionContentDescription(R.string.player_close)
            title = getString(R.string.recommended_title)
        }
        // 返回图标由 Toolbar/ActionBar API 创建，只调整设计要求的前景色。
        binding.toolbar.navigationIcon?.setTint(Color.WHITE)
    }

    private fun configureInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.recommendedRoot) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(bars.left, 0, bars.right, bars.bottom)
            binding.appBar.updatePadding(top = bars.top)
            insets
        }
    }

    private fun configureList() = with(binding.musicList) {
        val linearLayoutManager = LinearLayoutManager(this@RecommendedMusicActivity).apply {
            initialPrefetchItemCount = RESULT_PREFETCH_COUNT
        }
        layoutManager = linearLayoutManager
        adapter = musicAdapter.apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
        setHasFixedSize(true)
        itemAnimator = DefaultItemAnimator().apply {
            addDuration = 180L
            moveDuration = 200L
            removeDuration = 140L
            supportsChangeAnimations = false
        }
        addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (linearLayoutManager.findLastVisibleItemPosition() >= musicAdapter.itemCount - LOAD_MORE_DISTANCE) {
                        viewModel.loadNextPage()
                    }
                }
            },
        )
    }

    private fun configureActions() {
        binding.playAll.setOnClickListener { playAll() }
        binding.multipleChoice.setOnClickListener { viewModel.toggleSelectionMode() }
        binding.retry.setOnClickListener { viewModel.retry() }
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

    private fun renderState(state: RecommendedMusicUiState) {
        musicAdapter.submitState(state)
        val showError = state.errorMessage != null && state.tracks.isEmpty() && !state.isInitialLoading
        val showEmpty = !state.isInitialLoading && state.errorMessage == null && state.tracks.isEmpty()
        binding.stateContainer.isVisible = showError || showEmpty
        binding.stateMessage.text = when {
            showError -> state.errorMessage
            showEmpty -> getString(R.string.recommended_empty)
            else -> ""
        }
        binding.retry.isVisible = showError
        binding.playAll.isEnabled = state.tracks.isNotEmpty()
        binding.playAll.alpha = if (binding.playAll.isEnabled) 1f else 0.45f
        binding.multipleChoice.isEnabled = state.tracks.isNotEmpty()
        binding.multipleChoice.alpha = if (state.isSelectionMode) 1f else 0.86f
        binding.multipleChoice.contentDescription = getString(
            if (state.isSelectionMode) R.string.recommended_finish_selection
            else R.string.recommended_multiple_choice,
        )
        binding.playAllLabel.text = if (state.isSelectionMode && state.selectedCount > 0) {
            getString(R.string.recommended_play_selected, state.selectedCount)
        } else {
            getString(R.string.recommended_play_all)
        }
        renderMiniPlayer(state.miniPlayer)
    }

    private fun renderMiniPlayer(model: RecommendedMiniPlayerUi?) {
        isMiniPlayerVisible = model != null
        bottomAdController.setMiniPlayerVisible(isMiniPlayerVisible)
        binding.miniPlayerHost.isVisible = model != null
        if (model == null) {
            miniPlayerBinder.render(model = null, controllerReady = false, hasQueue = false)
            return
        }
        binding.miniPlayerHost.animate().cancel()
        binding.miniPlayerHost.translationY = 0f
        miniPlayerBinder.render(
            model = MiniPlayerViewBinder.Model(
                track = model.track,
                artworkUrls = model.artworkThumbnailUrls,
                artworkFallbackRes = model.artworkFallbackRes,
                isPlaying = model.isPlaying,
            ),
            controllerReady = controller != null,
            hasQueue = controller?.mediaItemCount?.let { it > 0 } == true,
        )
    }

    private fun playTrack(item: RecommendedTrackUi) {
        val queue = viewModel.queueForTrack()
        if (queue.isEmpty()) return
        viewModel.updatePlayback(item.track, isPlaying = true, isActivelyPlaying = true)
        PlayerActivity.openQueue(this, queue, item.id)
    }

    private fun playAll() {
        val queue = viewModel.playAllQueue()
        val first = queue.firstOrNull() ?: return
        viewModel.updatePlayback(first, isPlaying = true, isActivelyPlaying = true)
        PlayerActivity.openQueue(this, queue, first.id)
    }

    private fun showTrackActions(item: RecommendedTrackUi) {
        trackActions.show(
            TrackActionUiModel(
                id = item.id,
                title = item.track.title,
                artist = item.track.artist,
                artworkUrls = item.artworkThumbnailUrls,
                artworkFallbackRes = item.artworkFallbackRes,
                artworkUrl = item.track.artworkUrl,
                streamUrl = item.track.streamUrl,
                durationMs = item.track.durationMs,
                lyrics = item.track.lyrics,
                description = item.track.description,
                artistRef = item.track.artistRef,
            ),
        )
    }

    private fun connectPlaybackController() {
        miniPlayerBinder.updateControllerState(controllerReady = false, hasQueue = false)
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
                    miniPlayerBinder.updateControllerState(
                        controllerReady = true,
                        hasQueue = mediaController.mediaItemCount > 0,
                    )
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun syncPlaybackState(player: Player) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        val current = player.currentMediaItem?.toPlayerTrack(duration)
        viewModel.updatePlayback(
            track = current,
            isPlaying = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
            isActivelyPlaying = player.isPlaying,
        )
        miniPlayerBinder.updateControllerState(
            controllerReady = controller != null,
            hasQueue = player.mediaItemCount > 0,
        )
        current?.id?.let { queueSheet?.updatePlayback(it, player.isPlaying) }
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

    companion object {
        private const val RESULT_PREFETCH_COUNT = 8
        private const val LOAD_MORE_DISTANCE = 5
        private const val LIST_BOTTOM_PADDING_DP = 16

        fun open(context: Context) {
            context.startActivity(
                Intent(context, RecommendedMusicActivity::class.java)
                    .requestPostNavigationInterstitial(InterstitialAdPlacement.CONTENT_PAGE),
            )
        }
    }
}
