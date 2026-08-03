package com.example.lcb.app.search

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Build
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.lcb.app.databinding.ItemSearchLoadingBinding
import com.example.lcb.app.databinding.ItemSearchLoadMoreErrorBinding
import com.example.lcb.app.databinding.ItemSearchResultBinding
import com.example.lcb.app.ui.AppLoadError
import com.example.lcb.app.ui.TrackArtworkLoader

sealed interface SearchListItem {
    val stableId: Long

    data class Track(val value: SearchTrackUi) : SearchListItem {
        override val stableId = value.id.hashCode().toLong()
    }

    data class Skeleton(val index: Int) : SearchListItem {
        override val stableId = Long.MIN_VALUE + index
    }

    data object LoadingMore : SearchListItem { override val stableId = Long.MAX_VALUE - 1 }
    data class LoadMoreError(val error: AppLoadError) : SearchListItem { override val stableId = Long.MAX_VALUE }
}

class SearchResultAdapter(
    private val onTrackClick: (SearchTrackUi) -> Unit,
    private val onArtistClick: (SearchTrackUi) -> Unit,
    private val onTrackMore: (SearchTrackUi) -> Unit,
    private val onRetryLoadMore: () -> Unit,
) : ListAdapter<SearchListItem, RecyclerView.ViewHolder>(Diff) {
    var query: String = ""
        private set

    init {
        setHasStableIds(true)
    }

    fun submitState(state: SearchUiState) {
        query = state.query.trim()
        submitList(
            buildList {
                if (state.isInitialLoading) {
                    repeat(SKELETON_COUNT) { add(SearchListItem.Skeleton(it)) }
                } else {
                    addAll(state.tracks.map(SearchListItem::Track))
                    when {
                        state.isLoadingMore -> add(SearchListItem.LoadingMore)
                        state.loadMoreError != null -> add(
                            SearchListItem.LoadMoreError(state.loadMoreError),
                        )
                    }
                }
            },
        )
    }

    override fun getItemId(position: Int): Long = getItem(position).stableId

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is SearchListItem.Track -> TYPE_TRACK
        is SearchListItem.Skeleton -> TYPE_SKELETON
        SearchListItem.LoadingMore -> TYPE_LOADING
        is SearchListItem.LoadMoreError -> TYPE_ERROR
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_TRACK -> TrackHolder(ItemSearchResultBinding.inflate(inflater, parent, false))
            TYPE_SKELETON -> SkeletonHolder(
                inflater.inflate(com.example.lcb.app.R.layout.item_search_skeleton, parent, false),
            )
            TYPE_LOADING -> LoadingHolder(ItemSearchLoadingBinding.inflate(inflater, parent, false))
            else -> ErrorHolder(ItemSearchLoadMoreErrorBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SearchListItem.Track -> (holder as TrackHolder).bind(item.value)
            is SearchListItem.Skeleton -> Unit
            SearchListItem.LoadingMore -> Unit
            is SearchListItem.LoadMoreError -> (holder as ErrorHolder).bind(item.error)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val playback = payloads.filterIsInstance<PlaybackPayload>().lastOrNull()
        if (holder is TrackHolder && playback != null) {
            holder.bindPlayback(playback.isPlaying)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is SkeletonHolder) holder.start()
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        if (holder is SkeletonHolder) holder.stop()
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is SkeletonHolder -> holder.stop()
            is TrackHolder -> holder.recycle()
        }
        super.onViewRecycled(holder)
    }

    private inner class TrackHolder(
        private val binding: ItemSearchResultBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var boundTrack: SearchTrackUi? = null

        fun bind(track: SearchTrackUi) {
            boundTrack = track
            binding.title.text = highlightSearchMatches(track.title, query, SEARCH_HIGHLIGHT_COLOR)
            binding.artist.text = track.artist
            binding.artist.isClickable = track.artistRef != null
            binding.artist.isFocusable = track.artistRef != null
            binding.artist.setOnClickListener(
                if (track.artistRef != null) View.OnClickListener { boundTrack?.let(onArtistClick) } else null,
            )
            binding.cover.setPlaying(track.isPlaying)
            TrackArtworkLoader.load(
                binding.cover.image,
                track.artworkThumbnailUrls,
                track.artworkFallbackRes,
            )
            binding.root.setOnClickListener { boundTrack?.let(onTrackClick) }
            binding.more.setOnClickListener { boundTrack?.let(onTrackMore) }
        }

        fun bindPlayback(isPlaying: Boolean) {
            boundTrack = boundTrack?.copy(isPlaying = isPlaying)
            binding.cover.setPlaying(isPlaying)
        }

        fun recycle() {
            boundTrack = null
            binding.cover.setPlaying(false)
            TrackArtworkLoader.clear(binding.cover.image)
            binding.root.setOnClickListener(null)
            binding.artist.setOnClickListener(null)
            binding.more.setOnClickListener(null)
        }
    }

    private class SkeletonHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private var animator: ObjectAnimator? = null

        fun start() {
            if (animator != null || !animationsEnabled()) return
            animator = ObjectAnimator.ofFloat(itemView, View.ALPHA, 0.42f, 0.86f).apply {
                duration = 720L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }

        fun stop() {
            animator?.cancel()
            animator = null
            itemView.alpha = 1f
        }

        private fun animationsEnabled(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
    }

    private class LoadingHolder(binding: ItemSearchLoadingBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class ErrorHolder(
        private val binding: ItemSearchLoadMoreErrorBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(error: AppLoadError) {
            binding.message.setText(error.messageRes)
            binding.root.setOnClickListener { onRetryLoadMore() }
        }
    }

    private data class PlaybackPayload(val isPlaying: Boolean)

    private object Diff : DiffUtil.ItemCallback<SearchListItem>() {
        override fun areItemsTheSame(oldItem: SearchListItem, newItem: SearchListItem): Boolean =
            oldItem::class == newItem::class && oldItem.stableId == newItem.stableId

        override fun areContentsTheSame(oldItem: SearchListItem, newItem: SearchListItem): Boolean =
            oldItem == newItem

        override fun getChangePayload(oldItem: SearchListItem, newItem: SearchListItem): Any? {
            return if (
                oldItem is SearchListItem.Track && newItem is SearchListItem.Track &&
                oldItem.value.copy(isPlaying = false) == newItem.value.copy(isPlaying = false) &&
                oldItem.value.isPlaying != newItem.value.isPlaying
            ) {
                PlaybackPayload(newItem.value.isPlaying)
            } else {
                null
            }
        }
    }

    private companion object {
        const val TYPE_TRACK = 1
        const val TYPE_SKELETON = 2
        const val TYPE_LOADING = 3
        const val TYPE_ERROR = 4
        const val SKELETON_COUNT = 7
        val SEARCH_HIGHLIGHT_COLOR: Int = Color.rgb(198, 45, 239)
    }
}

/** 不使用正则，避免特殊字符查询触发转义问题或灾难性回溯。 */
internal fun highlightSearchMatches(title: String, query: String, color: Int): CharSequence {
    val ranges = searchMatchRanges(title, query)
    if (ranges.isEmpty()) return title
    val result = SpannableString(title)
    ranges.forEach { range ->
        result.setSpan(
            ForegroundColorSpan(color),
            range.first,
            range.last + 1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    return result
}

internal fun searchMatchRanges(title: String, query: String): List<IntRange> {
    val keyword = query.trim()
    if (keyword.isEmpty() || title.isEmpty()) return emptyList()
    return buildList {
        var start = title.indexOf(keyword, ignoreCase = true)
        while (start >= 0) {
            add(start until start + keyword.length)
            start = title.indexOf(keyword, startIndex = start + keyword.length, ignoreCase = true)
        }
    }
}
