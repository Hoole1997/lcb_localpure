package com.example.lcb.app.library

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.Gravity
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lcb.app.MusicLibraryDependencies
import com.example.lcb.app.R
import com.example.lcb.app.databinding.ActivityPlaylistBinding
import com.example.lcb.app.player.PlaybackService
import com.example.lcb.app.player.PlayerActivity
import com.example.lcb.app.trackactions.TrackActionUiModel
import com.example.lcb.app.trackactions.TrackActionsController
import com.example.lcb.app.utils.BottomNativeAdController
import com.example.lcb.app.utils.InterstitialAdPlacement
import com.example.lcb.app.utils.loadRequestedPostNavigationInterstitial
import com.example.lcb.app.utils.requestPostNavigationInterstitial
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch

class PlaylistActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlaylistBinding
    private val repository by lazy(LazyThreadSafetyMode.NONE) {
        MusicLibraryDependencies.repository(this)
    }
    private val collection: LibraryCollection by lazy(LazyThreadSafetyMode.NONE) {
        if (intent.getBooleanExtra(EXTRA_FAVORITES, false)) {
            LibraryCollection.Favorites
        } else {
            LibraryCollection.Playlist(intent.getLongExtra(EXTRA_PLAYLIST_ID, INVALID_PLAYLIST_ID))
        }
    }
    private val viewModel: PlaylistDetailViewModel by viewModels {
        PlaylistDetailViewModel.Factory(
            repository = repository,
            collection = collection,
            favoritesTitle = getString(R.string.playlist_favorites_title),
        )
    }
    private val adapter by lazy(LazyThreadSafetyMode.NONE) {
        PlaylistTrackAdapter(
            onTrackClick = ::playTrack,
            onTrackLongClick = { track -> viewModel.enterSelection(track.id) },
            onTrackMore = ::showTrackActions,
            onSelectionChanged = { track -> viewModel.toggleSelection(track.id) },
        )
    }
    private val trackActions by lazy(LazyThreadSafetyMode.NONE) {
        TrackActionsController(
            activity = this,
            repository = repository,
        )
    }
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private lateinit var bottomAdController: BottomNativeAdController
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            viewModel.updatePlayback(player.currentMediaItem?.mediaId, player.isPlaying)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if ((collection as? LibraryCollection.Playlist)?.id == INVALID_PLAYLIST_ID) {
            finish()
            return
        }
        enableEdgeToEdge()
        binding = ActivityPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        configureToolbar()
        configureInsets()
        configureList()
        bottomAdController = BottomNativeAdController(
            activity = this,
            adContainer = binding.adContainer,
            scrollingContent = binding.trackList,
            baseContentBottomPaddingDp = TRACK_LIST_BOTTOM_PADDING_DP,
        )
        configureBackNavigation()
        observeState()
        loadRequestedPostNavigationInterstitial(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        connectPlaybackController()
    }

    override fun onStop() {
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onStop()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_playlist, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val state = viewModel.state.value
        menu.findItem(R.id.action_select)?.isVisible = !state.isSelectionMode && state.tracks.isNotEmpty()
        menu.findItem(R.id.action_remove)?.apply {
            isVisible = state.isSelectionMode
            isEnabled = state.selectedCount > 0
            icon?.alpha = if (isEnabled) 255 else 90
        }
        menu.findItem(R.id.action_more)?.isVisible = !state.isFavorites && !state.isSelectionMode
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_select -> {
            viewModel.enterSelection()
            true
        }
        R.id.action_remove -> {
            viewModel.removeSelected()
            true
        }
        R.id.action_more -> {
            showPlaylistOverflow()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        handleBack()
        return true
    }

    private fun configureToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.navigationIcon?.setTint(Color.WHITE)
    }

    private fun configureInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.playlistRoot) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(bars.left, 0, bars.right, bars.bottom)
            binding.appBar.updatePadding(top = bars.top)
            insets
        }
    }

    private fun configureList() = with(binding.trackList) {
        layoutManager = LinearLayoutManager(this@PlaylistActivity).apply { initialPrefetchItemCount = 8 }
        adapter = this@PlaylistActivity.adapter.apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
        setHasFixedSize(true)
        itemAnimator = DefaultItemAnimator().apply {
            addDuration = 160L
            removeDuration = 140L
            moveDuration = 180L
            supportsChangeAnimations = false
        }
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
    }

    private fun handleBack() {
        if (viewModel.state.value.isSelectionMode) viewModel.exitSelection() else finish()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::renderState) }
                launch { viewModel.events.collect(::handleEvent) }
            }
        }
    }

    private fun renderState(state: PlaylistDetailUiState) {
        if (state.isMissing) {
            finish()
            return
        }
        supportActionBar?.title = if (state.isSelectionMode) {
            getString(R.string.playlist_selected_count, state.selectedCount)
        } else {
            state.title
        }
        adapter.submitList(state.tracks)
        binding.emptyState.isVisible = state.tracks.isEmpty()
        binding.trackList.isVisible = state.tracks.isNotEmpty()
        binding.emptyTitle.setText(
            if (state.isFavorites) R.string.playlist_favorites_empty else R.string.playlist_tracks_empty,
        )
        bottomAdController.setSuppressed(state.isSelectionMode)
        if (state.tracks.isNotEmpty()) bottomAdController.loadOnce()
        invalidateOptionsMenu()
    }

    private fun handleEvent(event: PlaylistDetailEvent) {
        when (event) {
            PlaylistDetailEvent.PlaylistDeleted -> finish()
            is PlaylistDetailEvent.TracksRemoved -> Toast.makeText(
                this,
                resources.getQuantityString(
                    R.plurals.playlist_removed_count,
                    event.count,
                    event.count,
                ),
                Toast.LENGTH_SHORT,
            ).show()
            is PlaylistDetailEvent.Error -> Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun playTrack(track: LibraryTrack) {
        val queue = viewModel.playerQueue()
        if (queue.isEmpty()) return
        PlayerActivity.openQueue(this, queue, track.id)
    }

    private fun showTrackActions(track: LibraryTrack) {
        trackActions.show(
            TrackActionUiModel(
                id = track.id,
                title = track.title,
                artist = track.artist,
                artworkUrls = track.artworkThumbnailUrls,
                artworkFallbackRes = track.artworkFallbackRes,
                artworkUrl = track.artworkUrl,
                streamUrl = track.streamUrl,
                durationMs = track.durationMs,
                lyrics = track.lyrics,
                description = track.description,
                artistRef = track.artistRef,
            ),
        )
    }

    private fun showPlaylistOverflow() {
        PopupMenu(this, binding.toolbar, Gravity.END).apply {
            menuInflater.inflate(R.menu.menu_playlist_overflow, menu)
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_delete_playlist) {
                    confirmDeletePlaylist()
                    true
                } else {
                    false
                }
            }
            show()
        }
    }

    private fun confirmDeletePlaylist() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.playlist_delete_title)
            .setMessage(getString(R.string.playlist_delete_message, viewModel.state.value.title))
            .setNegativeButton(R.string.playlist_cancel, null)
            .setPositiveButton(R.string.playlist_delete) { _, _ -> viewModel.deletePlaylist() }
            .show()
    }

    private fun connectPlaybackController() {
        val future = MediaController.Builder(
            this,
            SessionToken(this, ComponentName(this, PlaybackService::class.java)),
        ).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (isFinishing || controllerFuture !== future) return@addListener
                runCatching { future.get() }.onSuccess { mediaController ->
                    controller = mediaController
                    mediaController.addListener(playerListener)
                    viewModel.updatePlayback(mediaController.currentMediaItem?.mediaId, mediaController.isPlaying)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    companion object {
        private const val EXTRA_FAVORITES = "playlist_favorites"
        private const val EXTRA_PLAYLIST_ID = "playlist_id"
        private const val INVALID_PLAYLIST_ID = -1L
        private const val TRACK_LIST_BOTTOM_PADDING_DP = 20

        fun openFavorites(context: Context) {
            context.startActivity(
                Intent(context, PlaylistActivity::class.java)
                    .putExtra(EXTRA_FAVORITES, true)
                    .requestPostNavigationInterstitial(InterstitialAdPlacement.CONTENT_PAGE),
            )
        }

        fun openPlaylist(context: Context, playlistId: Long) {
            context.startActivity(
                Intent(context, PlaylistActivity::class.java)
                    .putExtra(EXTRA_PLAYLIST_ID, playlistId)
                    .requestPostNavigationInterstitial(InterstitialAdPlacement.CONTENT_PAGE),
            )
        }
    }
}
