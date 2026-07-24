package com.example.lcb.app.recommended

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.lcb.app.R
import com.example.lcb.app.databinding.ItemRecommendedMusicBinding
import com.example.lcb.app.databinding.ItemSearchLoadingBinding
import com.example.lcb.app.databinding.ItemSearchLoadMoreErrorBinding
import com.example.lcb.app.ui.TrackArtworkLoader

sealed interface RecommendedListItem {
    val stableId: Long

    data class Track(
        val value: RecommendedTrackUi,
        val isSelectionMode: Boolean,
    ) : RecommendedListItem {
        override val stableId = value.id.hashCode().toLong()
    }

    data class Skeleton(val index: Int) : RecommendedListItem {
        override val stableId = Long.MIN_VALUE + index
    }

    data object LoadingMore : RecommendedListItem { override val stableId = Long.MAX_VALUE - 1 }
    data class LoadMoreError(val message: String) : RecommendedListItem { override val stableId = Long.MAX_VALUE }
}

class RecommendedMusicAdapter(
    private val onTrackClick: (RecommendedTrackUi) -> Unit,
    private val onArtistClick: (RecommendedTrackUi) -> Unit,
    private val onTrackMore: (RecommendedTrackUi) -> Unit,
    private val onSelectionChanged: (RecommendedTrackUi) -> Unit,
    private val onRetryLoadMore: () -> Unit,
) : ListAdapter<RecommendedListItem, RecyclerView.ViewHolder>(Diff) {
    private val animatedTrackIds = hashSetOf<Long>()

    init {
        setHasStableIds(true)
    }

    fun submitState(state: RecommendedMusicUiState) {
        submitList(
            buildList {
                if (state.isInitialLoading) {
                    repeat(SKELETON_COUNT) { add(RecommendedListItem.Skeleton(it)) }
                } else {
                    addAll(state.tracks.map { RecommendedListItem.Track(it, state.isSelectionMode) })
                    when {
                        state.isLoadingMore -> add(RecommendedListItem.LoadingMore)
                        state.loadMoreErrorMessage != null -> add(
                            RecommendedListItem.LoadMoreError(state.loadMoreErrorMessage),
                        )
                    }
                }
            },
        )
    }

    override fun getItemId(position: Int): Long = getItem(position).stableId

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is RecommendedListItem.Track -> TYPE_TRACK
        is RecommendedListItem.Skeleton -> TYPE_SKELETON
        RecommendedListItem.LoadingMore -> TYPE_LOADING
        is RecommendedListItem.LoadMoreError -> TYPE_ERROR
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_TRACK -> TrackHolder(ItemRecommendedMusicBinding.inflate(inflater, parent, false))
            TYPE_SKELETON -> SkeletonHolder(inflater.inflate(R.layout.item_search_skeleton, parent, false))
            TYPE_LOADING -> LoadingHolder(ItemSearchLoadingBinding.inflate(inflater, parent, false))
            else -> ErrorHolder(ItemSearchLoadMoreErrorBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is RecommendedListItem.Track -> (holder as TrackHolder).bind(item)
            is RecommendedListItem.Skeleton -> Unit
            RecommendedListItem.LoadingMore -> Unit
            is RecommendedListItem.LoadMoreError -> (holder as ErrorHolder).bind(item.message)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val payload = payloads.filterIsInstance<TrackPayload>().lastOrNull()
        if (holder is TrackHolder && payload != null) {
            holder.bindPartial(payload)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        when (holder) {
            is SkeletonHolder -> holder.start()
            is TrackHolder -> animateAttachedTrack(holder)
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is SkeletonHolder -> holder.stop()
            is TrackHolder -> holder.itemView.animate().cancel()
        }
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is SkeletonHolder -> holder.stop()
            is TrackHolder -> holder.recycle()
        }
        super.onViewRecycled(holder)
    }

    private fun animateAttachedTrack(holder: TrackHolder) {
        if (!animationsEnabled() || !animatedTrackIds.add(holder.itemId)) return
        val distance = 10f * holder.itemView.resources.displayMetrics.density
        holder.itemView.apply {
            animate().cancel()
            alpha = 0f
            translationY = distance
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .setStartDelay((holder.bindingAdapterPosition.coerceAtLeast(0) % 6) * 22L)
                .start()
        }
    }

    private inner class TrackHolder(
        private val binding: ItemRecommendedMusicBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var boundItem: RecommendedListItem.Track? = null

        fun bind(item: RecommendedListItem.Track) {
            boundItem = item
            val track = item.value
            binding.title.text = track.track.title
            binding.artist.text = track.track.artist
            binding.artist.isClickable = track.track.artistRef != null
            binding.artist.isFocusable = track.track.artistRef != null
            binding.artist.setOnClickListener(
                if (track.track.artistRef != null) View.OnClickListener {
                    boundItem?.value?.let(onArtistClick)
                } else null,
            )
            TrackArtworkLoader.load(
                binding.cover.image,
                track.artworkThumbnailUrls,
                track.artworkFallbackRes,
            )
            bindPartial(
                TrackPayload(
                    isPlaying = track.isPlaying,
                    isSelected = track.isSelected,
                    isSelectionMode = item.isSelectionMode,
                ),
            )
            binding.root.setOnClickListener {
                boundItem?.let { current ->
                    if (current.isSelectionMode) onSelectionChanged(current.value) else onTrackClick(current.value)
                }
            }
            binding.more.setOnClickListener { boundItem?.value?.let(onTrackMore) }
            binding.selected.setOnCheckedChangeListener { _, _ ->
                boundItem?.value?.let(onSelectionChanged)
            }
        }

        fun bindPartial(payload: TrackPayload) {
            boundItem = boundItem?.let { current ->
                current.copy(
                    value = current.value.copy(
                        isPlaying = payload.isPlaying,
                        isSelected = payload.isSelected,
                    ),
                    isSelectionMode = payload.isSelectionMode,
                )
            }
            binding.cover.setPlaying(payload.isPlaying)
            binding.more.isVisible = !payload.isSelectionMode
            binding.selected.isVisible = payload.isSelectionMode
            binding.selected.setOnCheckedChangeListener(null)
            binding.selected.isChecked = payload.isSelected
            binding.selected.setOnCheckedChangeListener { _, _ ->
                boundItem?.value?.let(onSelectionChanged)
            }
            binding.root.setBackgroundResource(
                if (payload.isSelected) R.drawable.bg_recommended_selected else R.drawable.bg_search_result_ripple,
            )
        }

        fun recycle() {
            boundItem = null
            binding.root.animate().cancel()
            binding.root.alpha = 1f
            binding.root.translationY = 0f
            binding.root.setOnClickListener(null)
            binding.artist.setOnClickListener(null)
            binding.more.setOnClickListener(null)
            binding.selected.setOnCheckedChangeListener(null)
            binding.cover.setPlaying(false)
            TrackArtworkLoader.clear(binding.cover.image)
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
    }

    private class LoadingHolder(binding: ItemSearchLoadingBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class ErrorHolder(
        private val binding: ItemSearchLoadMoreErrorBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: String) {
            binding.message.text = message
            binding.root.setOnClickListener { onRetryLoadMore() }
        }
    }

    private data class TrackPayload(
        val isPlaying: Boolean,
        val isSelected: Boolean,
        val isSelectionMode: Boolean,
    )

    private object Diff : DiffUtil.ItemCallback<RecommendedListItem>() {
        override fun areItemsTheSame(oldItem: RecommendedListItem, newItem: RecommendedListItem): Boolean =
            oldItem::class == newItem::class && oldItem.stableId == newItem.stableId

        override fun areContentsTheSame(oldItem: RecommendedListItem, newItem: RecommendedListItem): Boolean =
            oldItem == newItem

        override fun getChangePayload(oldItem: RecommendedListItem, newItem: RecommendedListItem): Any? {
            return if (
                oldItem is RecommendedListItem.Track && newItem is RecommendedListItem.Track &&
                oldItem.value.copy(isPlaying = false, isSelected = false) ==
                newItem.value.copy(isPlaying = false, isSelected = false)
            ) {
                TrackPayload(
                    isPlaying = newItem.value.isPlaying,
                    isSelected = newItem.value.isSelected,
                    isSelectionMode = newItem.isSelectionMode,
                )
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
        const val SKELETON_COUNT = 8

        fun animationsEnabled(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
    }
}
