package com.example.lcb.app.search

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lcb.app.MusicDependencies
import com.example.lcb.app.R
import com.example.lcb.app.analytics.MusicAnalytics
import com.example.lcb.app.artist.ArtistActivity
import com.example.lcb.app.databinding.ActivitySearchBinding
import com.example.lcb.app.player.PlaybackService
import com.example.lcb.app.player.PlayerActivity
import com.example.lcb.app.trackactions.TrackActionUiModel
import com.example.lcb.app.trackactions.TrackActionsController
import com.example.lcb.app.utils.BusinessAdSwitchKey
import com.example.lcb.app.utils.InterstitialAdPlacement
import com.example.lcb.app.utils.loadNative
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch
import net.corekit.core.controller.AdSlotSwitchController

class SearchActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySearchBinding
    private val repository by lazy(LazyThreadSafetyMode.NONE) {
        MusicSdkSearchRepository(MusicDependencies.sdk)
    }
    private val viewModel: SearchViewModel by viewModels { SearchViewModel.Factory(repository) }
    private val resultAdapter by lazy(LazyThreadSafetyMode.NONE) {
        SearchResultAdapter(
            onTrackClick = ::openPlayer,
            onArtistClick = ::openArtist,
            onTrackMore = ::showTrackActions,
            onRetryLoadMore = viewModel::retry,
        )
    }
    private val trackActions by lazy(LazyThreadSafetyMode.NONE) {
        TrackActionsController(this, MusicAnalytics.Surface.SEARCH)
    }
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var nativeAdRequested = false
    private var lastReportedSearchQuery: String? = null
    private var lastReportedSearchOutcome: Pair<String, MusicAnalytics.SearchAction>? = null
    private var skipRestoredAnalyticsSnapshot = false
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncPlayback(player)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        skipRestoredAnalyticsSnapshot = savedInstanceState != null
        enableEdgeToEdge()
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        configureInsets()
        configureSearchInput(savedInstanceState)
        configureResults()
        observeState()
        if (savedInstanceState == null) MusicAnalytics.screenView(MusicAnalytics.Screen.SEARCH)
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

    private fun configureInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.searchRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun configureSearchInput(savedInstanceState: Bundle?) {
        binding.searchInput.filters = arrayOf(InputFilter.LengthFilter(MAX_QUERY_LENGTH))
        val restoredQuery = viewModel.state.value.query
        if (binding.searchInput.text?.toString() != restoredQuery) {
            binding.searchInput.setText(restoredQuery)
            binding.searchInput.setSelection(restoredQuery.length)
        }
        binding.searchInput.doAfterTextChanged { editable ->
            viewModel.onQueryChanged(editable?.toString().orEmpty())
        }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
        binding.clear.setOnClickListener {
            binding.searchInput.text?.clear()
            binding.searchInput.requestFocus()
        }
        binding.cancel.setOnClickListener { finish() }
        binding.retry.setOnClickListener {
            MusicAnalytics.search(
                MusicAnalytics.SearchAction.RETRY,
                viewModel.state.value.query.trim().length,
            )
            viewModel.retry()
        }

        if (savedInstanceState == null) {
            binding.searchInput.requestFocus()
            binding.searchInput.doOnLayout {
                getSystemService(InputMethodManager::class.java)?.showSoftInput(binding.searchInput, 0)
            }
        }
    }

    private fun configureResults() = with(binding.results) {
        val linearLayoutManager = LinearLayoutManager(this@SearchActivity).apply {
            initialPrefetchItemCount = RESULT_PREFETCH_COUNT
        }
        layoutManager = linearLayoutManager
        adapter = resultAdapter.apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
        setHasFixedSize(true)
        itemAnimator?.changeDuration = 0L
        addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val lastVisible = linearLayoutManager.findLastVisibleItemPosition()
                    if (lastVisible >= resultAdapter.itemCount - LOAD_MORE_DISTANCE) {
                        viewModel.loadNextPage()
                    }
                }
            },
        )
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::renderState)
            }
        }
    }

    private fun renderState(state: SearchUiState) {
        val currentInput = binding.searchInput.text?.toString().orEmpty()
        if (currentInput != state.query) {
            binding.searchInput.setText(state.query)
            binding.searchInput.setSelection(state.query.length)
        }
        binding.clear.isVisible = state.query.isNotEmpty()
        resultAdapter.submitState(state)

        val showInitialError = state.initialLoadError != null && state.tracks.isEmpty() && !state.isInitialLoading
        val showEmpty = state.hasSearched && state.query.isNotBlank() && state.tracks.isEmpty() &&
            !state.isInitialLoading && state.initialLoadError == null
        binding.stateContainer.isVisible = showInitialError || showEmpty
        when {
            showInitialError -> binding.stateMessage.setText(requireNotNull(state.initialLoadError).messageRes)
            showEmpty -> binding.stateMessage.setText(R.string.search_empty)
            else -> binding.stateMessage.text = ""
        }
        binding.retry.isVisible = showInitialError
        if (state.tracks.isNotEmpty() && !nativeAdRequested) {
            nativeAdRequested = true
            // 用户获得搜索结果后再加载，避免刚进入输入页就用广告挤压可用空间。
            if (AdSlotSwitchController.isEnabled(BusinessAdSwitchKey.SEARCH_RESULT_BOTTOM_NATIVE)) {
                loadNative(binding.adContainer, position = TAG)
            }
        }
        reportSearchState(state)
    }

    /** 搜索词只在内存中用于防重，上报时仅发送长度、结果数量和状态。 */
    private fun reportSearchState(state: SearchUiState) {
        val normalizedQuery = state.query.trim()
        if (!state.hasSearched || normalizedQuery.isEmpty()) {
            skipRestoredAnalyticsSnapshot = false
            return
        }
        val outcome = when {
            state.isInitialLoading -> null
            state.initialLoadError != null -> MusicAnalytics.SearchAction.FAILURE
            state.tracks.isEmpty() -> MusicAnalytics.SearchAction.EMPTY
            else -> MusicAnalytics.SearchAction.SUCCESS
        }
        if (skipRestoredAnalyticsSnapshot) {
            lastReportedSearchQuery = normalizedQuery
            outcome?.let { lastReportedSearchOutcome = normalizedQuery to it }
            skipRestoredAnalyticsSnapshot = false
            return
        }
        if (lastReportedSearchQuery != normalizedQuery) {
            lastReportedSearchQuery = normalizedQuery
            lastReportedSearchOutcome = null
            MusicAnalytics.search(MusicAnalytics.SearchAction.STARTED, normalizedQuery.length)
        }
        val outcomeKey = outcome?.let { normalizedQuery to it } ?: return
        if (lastReportedSearchOutcome == outcomeKey) return
        lastReportedSearchOutcome = outcomeKey
        MusicAnalytics.search(outcome, normalizedQuery.length, state.tracks.size)
    }

    private fun openPlayer(track: SearchTrackUi) {
        val queue = viewModel.playerQueue()
        if (queue.isEmpty()) return
        MusicAnalytics.trackSelected(
            MusicAnalytics.Surface.SEARCH,
            track.artistRef?.platform,
            queue.size,
        )
        viewModel.updatePlayback(track.id, true)
        PlayerActivity.openQueue(this, queue, track.id)
    }

    private fun openArtist(track: SearchTrackUi) {
        track.artistRef?.let { artist ->
            MusicAnalytics.trackAction(
                MusicAnalytics.TrackAction.OPEN_ARTIST,
                MusicAnalytics.Surface.SEARCH,
                artist.platform,
            )
            ArtistActivity.open(
                this,
                artist,
                InterstitialAdPlacement.ARTIST_LIST_NAME_ENTRY,
            )
        }
    }

    private fun showTrackActions(track: SearchTrackUi) {
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

    private fun connectPlaybackController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (isFinishing || controllerFuture !== future) return@addListener
                runCatching { future.get() }.onSuccess { mediaController ->
                    controller = mediaController
                    mediaController.addListener(playerListener)
                    syncPlayback(mediaController)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun syncPlayback(player: Player) {
        viewModel.updatePlayback(
            trackId = player.currentMediaItem?.mediaId,
            isPlaying = player.isPlaying,
        )
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(
            binding.searchInput.windowToken,
            0,
        )
        binding.searchInput.clearFocus()
    }

    companion object {
        private const val MAX_QUERY_LENGTH = 100
        private const val RESULT_PREFETCH_COUNT = 8
        private const val LOAD_MORE_DISTANCE = 5
        private const val TAG = "SearchActivity"
        fun open(context: Context) {
            context.startActivity(Intent(context, SearchActivity::class.java))
        }
    }
}
