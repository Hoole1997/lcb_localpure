package com.example.lcb.app.artist

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.icu.text.CompactDecimalFormat
import android.os.Bundle
import android.widget.Toast
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
import com.example.lcb.app.databinding.ActivityArtistBinding
import com.example.lcb.app.player.MiniPlayerViewBinder
import com.example.lcb.app.player.PlaybackQueueBottomSheet
import com.example.lcb.app.player.PlaybackService
import com.example.lcb.app.player.PlayerActivity
import com.example.lcb.app.player.PlayerTrack
import com.example.lcb.app.player.toPlayerTrack
import com.example.lcb.app.player.toPlayerTrackQueue
import com.example.lcb.app.trackactions.TrackActionUiModel
import com.example.lcb.app.trackactions.TrackActionsController
import com.example.lcb.app.ui.TrackArtworkLoader
import com.example.lcb.app.utils.BottomNativeAdController
import com.example.lcb.app.utils.InterstitialAdPlacement
import com.example.lcb.app.utils.loadRequestedPostNavigationInterstitial
import com.example.lcb.app.utils.requestPostNavigationInterstitial
import com.example.lcb.music.model.MusicArtistDetails
import com.example.lcb.music.model.MusicArtistRef
import com.example.lcb.music.model.MusicImage
import com.example.lcb.music.model.MusicPlatform
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.abs

class ArtistActivity : AppCompatActivity() {
    private lateinit var binding: ActivityArtistBinding
    private lateinit var artistRequest: ArtistRequest

    private val repository by lazy(LazyThreadSafetyMode.NONE) {
        MusicSdkArtistRepository(MusicDependencies.sdk)
    }
    private val viewModel: ArtistViewModel by viewModels {
        ArtistViewModel.Factory(artistRequest, repository)
    }
    private val nestedViewPool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(COLLECTION_VIEW_TYPE, COLLECTION_POOL_SIZE)
    }
    private val pageAdapter by lazy(LazyThreadSafetyMode.NONE) {
        ArtistPageAdapter(
            recycledViewPool = nestedViewPool,
            onPlayAll = ::playAll,
            onShare = ::shareArtist,
            onBioToggle = viewModel::toggleBio,
            onTrackClick = ::playTrack,
            onTrackMore = ::showTrackActions,
            onCollectionClick = viewModel::playCollection,
            onRetry = viewModel::retry,
        )
    }
    private val trackActions by lazy(LazyThreadSafetyMode.NONE) { TrackActionsController(this) }
    private val miniPlayerBinder by lazy(LazyThreadSafetyMode.NONE) { MiniPlayerViewBinder(binding.miniPlayer) }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var queueSheet: PlaybackQueueBottomSheet? = null
    private var renderedHeaderKey: String? = null
    private var isMiniPlayerVisible = false
    private lateinit var bottomAdController: BottomNativeAdController
    private val appBarOffsetListener = com.google.android.material.appbar.AppBarLayout.OnOffsetChangedListener {
            appBar,
            verticalOffset,
        ->
        val range = appBar.totalScrollRange.takeIf { it > 0 } ?: return@OnOffsetChangedListener
        val collapsedFraction = (abs(verticalOffset).toFloat() / range).coerceIn(0f, 1f)
        // 仅使用透明度和 transform，滚动时不会触发布局或 Bitmap 重解码。
        val detailAlpha = (1f - ((collapsedFraction - 0.12f) / 0.56f)).coerceIn(0f, 1f)
        val detailOffset = dp(12) * collapsedFraction
        listOf(binding.avatar, binding.artistName, binding.artistMeta, binding.stats).forEach { view ->
            view.alpha = detailAlpha
            view.translationY = detailOffset
        }
        val imageScale = 1.045f - 0.045f * collapsedFraction
        binding.heroCover.scaleX = imageScale
        binding.heroCover.scaleY = imageScale
        binding.heroScrim.alpha = 0.88f + 0.12f * collapsedFraction
    }
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncPlayback(player)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        artistRequest = intent.toArtistRequest(getString(R.string.artist_title)) ?: run {
            Toast.makeText(this, R.string.artist_load_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        enableEdgeToEdge()
        binding = ActivityArtistBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        configureToolbar()
        configureInsets()
        configureImmersiveHeader()
        configureList()
        configureMiniPlayer()
        bottomAdController = BottomNativeAdController(
            activity = this,
            adContainer = binding.adContainer,
            scrollingContent = binding.contentList,
            baseContentBottomPaddingDp = LIST_BOTTOM_DP,
            miniPlayerHost = binding.miniPlayerHost,
        )
        observeState()
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

    override fun onDestroy() {
        if (::binding.isInitialized) {
            binding.appBar.removeOnOffsetChangedListener(appBarOffsetListener)
        }
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
            setHomeActionContentDescription(R.string.action_back)
            title = ""
        }
        binding.toolbar.navigationIcon?.setTint(Color.WHITE)
        binding.collapsingToolbar.setExpandedTitleColor(Color.TRANSPARENT)
        binding.collapsingToolbar.setCollapsedTitleTextColor(Color.WHITE)
    }

    private fun configureInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.artistRoot) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            root.setPadding(bars.left, 0, bars.right, bars.bottom)

            // AppBar 本身不消费顶部 inset，让封面绘制到状态栏后方；仅把 Toolbar 内容下移。
            binding.toolbar.updatePadding(top = statusBar.top)
            binding.toolbar.layoutParams = binding.toolbar.layoutParams.apply {
                height = resources.getDimensionPixelSize(R.dimen.artist_toolbar_height) + statusBar.top
            }
            binding.collapsingToolbar.layoutParams = binding.collapsingToolbar.layoutParams.apply {
                height = resources.getDimensionPixelSize(R.dimen.artist_hero_height) + statusBar.top
            }
            binding.collapsingToolbar.minimumHeight =
                resources.getDimensionPixelSize(R.dimen.artist_toolbar_height) + statusBar.top
            insets
        }
    }

    private fun configureImmersiveHeader() {
        binding.heroCover.scaleX = 1.045f
        binding.heroCover.scaleY = 1.045f
        binding.appBar.addOnOffsetChangedListener(appBarOffsetListener)
    }

    private fun configureList() = with(binding.contentList) {
        val linearLayoutManager = LinearLayoutManager(this@ArtistActivity).apply {
            initialPrefetchItemCount = LIST_PREFETCH_COUNT
        }
        layoutManager = linearLayoutManager
        adapter = pageAdapter.apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
        setHasFixedSize(false)
        itemAnimator = DefaultItemAnimator().apply {
            addDuration = 170L
            removeDuration = 130L
            moveDuration = 190L
            supportsChangeAnimations = false
        }
        addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (linearLayoutManager.findLastVisibleItemPosition() >= pageAdapter.itemCount - LOAD_MORE_DISTANCE) {
                        viewModel.loadNextPage()
                    }
                }
            },
        )
    }

    private fun configureMiniPlayer() {
        miniPlayerBinder.setCallbacks(
            onOpenPlayer = { PlayerActivity.openExisting(this) },
            onPlayPause = {
                controller?.let { player -> if (player.playWhenReady) player.pause() else player.play() }
            },
            onQueue = ::showPlaybackQueue,
        )
        binding.retry.setOnClickListener { viewModel.refresh() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::renderState) }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is ArtistEvent.OpenQueue -> PlayerActivity.openQueue(
                                this@ArtistActivity,
                                event.queue,
                                event.currentTrackId,
                            )
                            is ArtistEvent.Message -> Toast.makeText(
                                this@ArtistActivity,
                                event.messageRes,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
        }
    }

    private fun renderState(state: ArtistUiState) {
        pageAdapter.submitState(state)
        renderHeader(state.details)

        val fatalError = !state.isInitialLoading && state.details == null && state.tracks.isEmpty() &&
            state.detailsError != null && state.tracksError != null
        binding.stateContainer.isVisible = fatalError
        binding.stateMessage.text = if (fatalError) getString(R.string.artist_load_failed) else ""
        binding.contentList.isVisible = !fatalError

        val mini = state.miniPlayer?.let {
            MiniPlayerViewBinder.Model(it.track, it.artworkUrls, it.artworkFallbackRes, it.isPlaying)
        }
        isMiniPlayerVisible = mini != null
        bottomAdController.setMiniPlayerVisible(isMiniPlayerVisible)
        binding.miniPlayerHost.isVisible = isMiniPlayerVisible
        if (isMiniPlayerVisible) {
            binding.miniPlayerHost.animate().cancel()
            binding.miniPlayerHost.translationY = 0f
        }
        miniPlayerBinder.render(
            model = mini,
            controllerReady = controller != null,
            hasQueue = controller?.mediaItemCount?.let { it > 0 } == true,
        )
        if (!state.isInitialLoading && (state.details != null || state.tracks.isNotEmpty())) {
            bottomAdController.loadOnce()
        }
    }

    private fun renderHeader(details: MusicArtistDetails?) {
        val artist = details?.artist
        val name = artist?.name?.takeIf(String::isNotBlank) ?: artistRequest.fallbackName
        binding.artistName.text = name
        binding.collapsingToolbar.title = name

        val handle = artist?.handle?.takeIf(String::isNotBlank)?.let { if (it.startsWith('@')) it else "@$it" }
        val location = details?.location?.displayName?.takeIf(String::isNotBlank)
        binding.handle.isVisible = handle != null
        binding.handle.text = handle
        binding.location.isVisible = location != null
        binding.location.text = location
        binding.metaSeparator.isVisible = handle != null && location != null
        binding.verified.isVisible = artist?.isVerified == true
        binding.followersValue.text = formatCount(artist?.followerCount)
        binding.tracksValue.text = formatCount(artist?.trackCount ?: viewModel.state.value.tracks.size.takeIf { it > 0 })
        binding.albumsValue.text = formatCount(details?.albumCount ?: viewModel.state.value.albums.size.takeIf { it > 0 })

        val heroCandidates = details?.coverImage.fullSizeCandidates()
            .orEmpty()
            .ifEmpty { artist?.image.fullSizeCandidates().orEmpty() }
        val avatarCandidates = artist?.image.fullSizeCandidates().orEmpty()
        val headerKey = "$name|${heroCandidates.joinToString()}|${avatarCandidates.joinToString()}"
        if (renderedHeaderKey == headerKey) return
        renderedHeaderKey = headerKey
        TrackArtworkLoader.load(binding.heroCover, heroCandidates, R.drawable.bg_artist_hero)
        TrackArtworkLoader.load(binding.avatar, avatarCandidates, R.drawable.ic_artist_placeholder)
    }

    private fun playTrack(item: ArtistTrackUi) {
        val queue = viewModel.queue()
        if (queue.isEmpty()) return
        viewModel.updatePlayback(item.track, playWhenReady = true, isActivelyPlaying = true)
        PlayerActivity.openQueue(this, queue, item.id)
    }

    private fun playAll() {
        val queue = viewModel.queue()
        val first = queue.firstOrNull() ?: return
        viewModel.updatePlayback(first, playWhenReady = true, isActivelyPlaying = true)
        PlayerActivity.openQueue(this, queue, first.id)
    }

    private fun showTrackActions(item: ArtistTrackUi) {
        trackActions.show(
            TrackActionUiModel(
                id = item.id,
                title = item.track.title,
                artist = item.track.artist,
                artworkUrls = item.artworkUrls,
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

    private fun shareArtist() {
        val details = viewModel.state.value.details
        val name = details?.artist?.name ?: artistRequest.fallbackName
        val target = details?.artist?.permalink
            ?: details?.artist?.website
            ?: details?.socialLinks?.firstOrNull()?.url
            ?: "https://play.google.com/store/apps/details?id=$packageName"
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, getString(R.string.artist_share_text, name, target))
                },
                getString(R.string.artist_share),
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
        val current = player.currentMediaItem?.toPlayerTrack(duration)
        viewModel.updatePlayback(
            track = current,
            playWhenReady = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
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

    private fun formatCount(value: Int?): String {
        value ?: return "—"
        val locale = resources.configuration.locales[0]
        return if (value >= 1_000) {
            CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT).apply {
                maximumFractionDigits = 1
            }.format(value)
        } else {
            NumberFormat.getIntegerInstance(locale).format(value)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_PLATFORM = "artist.platform"
        private const val EXTRA_ARTIST_ID = "artist.id"
        private const val EXTRA_ARTIST_NAME = "artist.name"
        private const val LIST_PREFETCH_COUNT = 8
        private const val LOAD_MORE_DISTANCE = 5
        private const val LIST_BOTTOM_DP = 18
        private const val COLLECTION_VIEW_TYPE = 0
        private const val COLLECTION_POOL_SIZE = 12

        fun open(context: Context, artist: MusicArtistRef) {
            open(context, artist.platform, artist.id, artist.name)
        }

        fun open(context: Context, platform: MusicPlatform, artistId: String, artistName: String) {
            if (artistId.isBlank()) return
            context.startActivity(
                Intent(context, ArtistActivity::class.java).apply {
                    putExtra(EXTRA_PLATFORM, platform.name)
                    putExtra(EXTRA_ARTIST_ID, artistId)
                    putExtra(EXTRA_ARTIST_NAME, artistName)
                }.requestPostNavigationInterstitial(InterstitialAdPlacement.CONTENT_PAGE),
            )
        }

        private fun Intent.toArtistRequest(defaultName: String): ArtistRequest? {
            val platform = getStringExtra(EXTRA_PLATFORM)
                ?.let { runCatching { MusicPlatform.valueOf(it) }.getOrNull() }
                ?: return null
            val artistId = getStringExtra(EXTRA_ARTIST_ID)?.takeIf(String::isNotBlank) ?: return null
            val name = getStringExtra(EXTRA_ARTIST_NAME)?.takeIf(String::isNotBlank) ?: defaultName
            return ArtistRequest(platform, artistId, name)
        }
    }
}

private fun MusicImage?.fullSizeCandidates(): List<String> {
    val image = this ?: return emptyList()
    return (listOfNotNull(image.largeUrl, image.mediumUrl, image.smallUrl) + image.thumbnailCandidates()).distinct()
}
