package com.example.lcb.app.home

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.lcb.app.ui.TrackArtworkLoader
import com.example.lcb.app.R
import com.example.lcb.app.databinding.*

interface HomeCallbacks {
    fun onSearch()
    fun onSettings()
    fun onSectionAction(sectionId: Long)
    fun onTrackClick(track: HomeTrackUi, queue: List<HomeTrackUi>)
    fun onArtistClick(track: HomeTrackUi)
    fun onTrackMore(track: HomeTrackUi)
    fun onShortcutClick(shortcut: HomeShortcutUi)
    fun onLocalStateAction(action: HomeLocalStateAction)
    fun onHomeRetry()
}

/**
 * 首页唯一的 RecyclerView Adapter。ListAdapter 在后台计算差异，稳定 id 保证动画及状态复用正确。
 */
class HomeAdapter(private val callbacks: HomeCallbacks) : ListAdapter<HomeListItem, RecyclerView.ViewHolder>(Diff) {
    init { setHasStableIds(true) }

    override fun getItemId(position: Int) = getItem(position).stableId

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is HomeListItem.Header -> TYPE_HEADER
        HomeListItem.RecommendedSkeleton -> TYPE_RECOMMENDED_SKELETON
        HomeListItem.MostPlayedSkeleton -> TYPE_MOST_PLAYED_SKELETON
        is HomeListItem.SectionTitle -> TYPE_SECTION
        is HomeListItem.Recommended -> TYPE_RECOMMENDED
        is HomeListItem.MostPlayed -> TYPE_MOST_PLAYED
        is HomeListItem.LoadError -> TYPE_LOAD_ERROR
        is HomeListItem.Shortcuts -> TYPE_SHORTCUTS
        is HomeListItem.LocalState -> TYPE_LOCAL_STATE
        is HomeListItem.LocalTrack -> TYPE_TRACK
        is HomeListItem.RecentTrack -> TYPE_TRACK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(ItemHomeHeaderBinding.inflate(inflater, parent, false))
            TYPE_SECTION -> SectionHolder(ItemHomeSectionTitleBinding.inflate(inflater, parent, false))
            TYPE_RECOMMENDED -> RecommendedHolder(ItemHomeRecommendedBinding.inflate(inflater, parent, false))
            TYPE_MOST_PLAYED -> MostPlayedHolder(ItemHomeHorizontalSectionBinding.inflate(inflater, parent, false))
            TYPE_RECOMMENDED_SKELETON -> SkeletonHolder(
                inflater.inflate(R.layout.item_home_recommended_skeleton, parent, false),
            )
            TYPE_MOST_PLAYED_SKELETON -> SkeletonHolder(
                inflater.inflate(R.layout.item_home_most_played_skeleton, parent, false),
            )
            TYPE_LOAD_ERROR -> LoadErrorHolder(ItemHomeLoadErrorBinding.inflate(inflater, parent, false))
            TYPE_SHORTCUTS -> ShortcutsHolder(ItemHomeShortcutsBinding.inflate(inflater, parent, false))
            TYPE_LOCAL_STATE -> LocalStateHolder(ItemHomeLocalStateBinding.inflate(inflater, parent, false))
            else -> TrackHolder(ItemHomeTrackBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = bind(holder, getItem(position))

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val payload = payloads.filterIsInstance<PlaybackPayload>().lastOrNull()
        when {
            holder is RecommendedHolder && payload != null -> holder.bindPlayback(payload.states)
            holder is MostPlayedHolder && payload != null -> holder.bindPlayback(payload.states)
            holder is TrackHolder && payload != null -> holder.bindPlayback(payload.states)
            else -> bind(holder, getItem(position))
        }
    }

    private fun bind(holder: RecyclerView.ViewHolder, item: HomeListItem) {
        when {
            holder is HeaderHolder && item is HomeListItem.Header -> holder.bind(item)
            holder is SkeletonHolder -> holder.start()
            holder is SectionHolder && item is HomeListItem.SectionTitle -> holder.bind(item)
            holder is RecommendedHolder && item is HomeListItem.Recommended -> holder.bind(item)
            holder is MostPlayedHolder && item is HomeListItem.MostPlayed -> holder.bind(item)
            holder is LoadErrorHolder && item is HomeListItem.LoadError -> holder.bind(item)
            holder is ShortcutsHolder && item is HomeListItem.Shortcuts -> holder.bind(item)
            holder is LocalStateHolder && item is HomeListItem.LocalState -> holder.bind(item)
            holder is TrackHolder && item is HomeListItem.LocalTrack -> holder.bind(item.track, TrackSource.LOCAL)
            holder is TrackHolder && item is HomeListItem.RecentTrack -> holder.bind(item.track, TrackSource.RECENT)
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
        if (holder is SkeletonHolder) holder.stop()
        if (holder is ShortcutsHolder) holder.recycle()
        super.onViewRecycled(holder)
    }

    /** 只改变 alpha，不触发布局与重绘区域变化；离屏后立即停止，避免无效耗电。 */
    private class SkeletonHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private var animator: ObjectAnimator? = null

        fun start() {
            if (animator != null || !animationsEnabled()) return
            animator = ObjectAnimator.ofFloat(itemView, View.ALPHA, 0.48f, 0.9f).apply {
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

    private inner class HeaderHolder(private val binding: ItemHomeHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HomeListItem.Header) {
            binding.search.isVisible = item.showSearch
            binding.search.setOnClickListener(
                if (item.showSearch) View.OnClickListener { callbacks.onSearch() } else null,
            )
            binding.settings.setOnClickListener { callbacks.onSettings() }
        }
    }

    private inner class LocalStateHolder(
        private val binding: ItemHomeLocalStateBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HomeListItem.LocalState) {
            binding.loading.isVisible = item.showProgress
            binding.title.isVisible = item.titleRes != null
            item.titleRes?.let(binding.title::setText)
            binding.message.isVisible = item.messageRes != null
            item.messageRes?.let(binding.message::setText)
            binding.action.isVisible = item.actionRes != null && item.action != null
            item.actionRes?.let(binding.action::setText)
            binding.action.setOnClickListener(
                item.action?.let { action ->
                    View.OnClickListener { callbacks.onLocalStateAction(action) }
                },
            )
        }
    }

    private inner class LoadErrorHolder(
        private val binding: ItemHomeLoadErrorBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HomeListItem.LoadError) {
            binding.message.setText(item.error.messageRes)
            binding.retry.setOnClickListener { callbacks.onHomeRetry() }
        }
    }

    private inner class SectionHolder(private val binding: ItemHomeSectionTitleBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HomeListItem.SectionTitle) {
            binding.title.setText(item.titleRes)
            binding.action.isVisible = item.actionRes != null
            item.actionRes?.let(binding.action::setText)
            binding.action.setOnClickListener { callbacks.onSectionAction(item.id) }
        }
    }

    private inner class RecommendedHolder(private val binding: ItemHomeRecommendedBinding) : RecyclerView.ViewHolder(binding.root) {
        private val boundCovers = mutableMapOf<String, HomeTrackArtworkView>()

        fun bind(item: HomeListItem.Recommended) {
            val inflater = LayoutInflater.from(binding.root.context)
            binding.container.removeAllViews()
            boundCovers.clear()
            val queue = item.groups.flatten()
            item.groups.forEach { group ->
                val card = ItemHomeRecommendedCardBinding.inflate(inflater, binding.container, false)
                group.forEach { track ->
                    val row = ItemHomeCompactTrackBinding.inflate(inflater, card.root, false)
                    bindCompactTrack(row, track, queue)
                    boundCovers[track.id] = row.cover
                    card.root.addView(row.root)
                }
                card.root.layoutParams = horizontalParams(306, 12)
                binding.container.addView(card.root)
            }
        }

        fun bindPlayback(states: Map<String, Boolean>) {
            boundCovers.forEach { (id, cover) -> cover.setPlaying(states[id] == true) }
        }
    }

    private inner class MostPlayedHolder(private val binding: ItemHomeHorizontalSectionBinding) : RecyclerView.ViewHolder(binding.root) {
        private val boundCovers = mutableMapOf<String, Pair<HomeTrackArtworkView, View>>()

        fun bind(item: HomeListItem.MostPlayed) {
            val inflater = LayoutInflater.from(binding.root.context)
            binding.container.removeAllViews()
            boundCovers.clear()
            item.tracks.forEach { track ->
                val card = ItemHomeCoverTrackBinding.inflate(inflater, binding.container, false)
                bindArtwork(card.cover, track)
                card.play.isVisible = !track.isPlaying
                boundCovers[track.id] = card.cover to card.play
                card.title.text = track.title
                card.artist.text = track.artist
                bindArtistClick(card.artist, track)
                card.root.setOnClickListener { callbacks.onTrackClick(track, item.tracks) }
                // 歌曲标题与歌手名由字体尺度决定高度，不能跟随父容器的固定高度。
                card.root.layoutParams = horizontalParams(135, 12, ViewGroup.LayoutParams.WRAP_CONTENT)
                binding.container.addView(card.root)
            }
        }

        fun bindPlayback(states: Map<String, Boolean>) {
            boundCovers.forEach { (id, views) ->
                val isPlaying = states[id] == true
                views.first.setPlaying(isPlaying)
                views.second.isVisible = !isPlaying
            }
        }
    }

    private inner class ShortcutsHolder(private val binding: ItemHomeShortcutsBinding) : RecyclerView.ViewHolder(binding.root) {
        private val artworkViews = mutableListOf<android.widget.ImageView>()

        fun bind(item: HomeListItem.Shortcuts) {
            val inflater = LayoutInflater.from(binding.root.context)
            artworkViews.forEach(TrackArtworkLoader::clear)
            artworkViews.clear()
            binding.container.removeAllViews()
            item.items.forEach { shortcut ->
                val card = ItemHomeShortcutBinding.inflate(inflater, binding.container, false)
                card.title.text = shortcut.title
                    ?: shortcut.titleRes?.let(card.root.context::getString)
                    .orEmpty()
                card.icon.setImageResource(shortcut.iconRes)
                card.card.clipToOutline = true
                val artworkUrls = shortcut.artworkThumbnailUrls.ifEmpty { listOfNotNull(shortcut.artworkUrl) }
                card.artwork.isVisible = artworkUrls.isNotEmpty()
                card.icon.isVisible = artworkUrls.isEmpty()
                if (artworkUrls.isNotEmpty()) {
                    TrackArtworkLoader.load(card.artwork, artworkUrls, R.drawable.home_cover_recommended_3)
                    artworkViews += card.artwork
                }
                card.card.setBackgroundResource(
                    when (shortcut.style) {
                        ShortcutStyle.NEUTRAL -> R.drawable.bg_home_shortcut_neutral
                        ShortcutStyle.FAVORITE -> R.drawable.bg_home_shortcut_favorite
                        ShortcutStyle.LOCAL -> R.drawable.bg_home_shortcut_local
                        ShortcutStyle.CUSTOM_PLAYLIST -> R.drawable.bg_home_shortcut_custom_playlist
                    },
                )
                card.root.setOnClickListener { callbacks.onShortcutClick(shortcut) }
                card.root.layoutParams = horizontalParams(118, 11)
                binding.container.addView(card.root)
            }
        }

        fun recycle() {
            artworkViews.forEach(TrackArtworkLoader::clear)
            artworkViews.clear()
            binding.container.removeAllViews()
        }
    }

    private inner class TrackHolder(private val binding: ItemHomeTrackBinding) : RecyclerView.ViewHolder(binding.root) {
        private var current: HomeTrackUi? = null

        fun bind(track: HomeTrackUi, source: TrackSource) {
            current = track
            binding.title.text = track.title
            binding.artist.text = track.artist
            bindArtistClick(binding.artist, track)
            bindArtwork(binding.cover, track)
            bindPlayback(mapOf(track.id to track.isPlaying))
            binding.root.setOnClickListener {
                val queue = when (source) {
                    TrackSource.LOCAL -> currentList.filterIsInstance<HomeListItem.LocalTrack>().map { it.track }
                    TrackSource.RECENT -> currentList.filterIsInstance<HomeListItem.RecentTrack>().map { it.track }
                }.queueStartingAt(track.id)
                callbacks.onTrackClick(track, queue.ifEmpty { listOf(track) })
            }
            binding.more.setOnClickListener { callbacks.onTrackMore(track) }
        }

        fun bindPlayback(states: Map<String, Boolean>) {
            val track = current ?: return
            val isPlaying = states[track.id] == true
            current = track.copy(isPlaying = isPlaying)
            val activeColor = Color.rgb(217, 2, 244)
            binding.title.setTextColor(if (isPlaying) activeColor else Color.rgb(255, 254, 254))
            binding.artist.setTextColor(if (isPlaying) activeColor else Color.rgb(153, 153, 153))
            binding.cover.setPlaying(isPlaying)
        }
    }

    private fun bindCompactTrack(
        binding: ItemHomeCompactTrackBinding,
        track: HomeTrackUi,
        queue: List<HomeTrackUi>,
    ) {
        binding.title.text = track.title
        binding.artist.text = track.artist
        bindArtistClick(binding.artist, track)
        bindArtwork(binding.cover, track)
        binding.root.setOnClickListener { callbacks.onTrackClick(track, queue) }
        binding.more.setOnClickListener { callbacks.onTrackMore(track) }
    }

    private fun bindArtwork(view: HomeTrackArtworkView, track: HomeTrackUi) {
        view.setPlaying(track.isPlaying)
        TrackArtworkLoader.load(view.image, track.artworkThumbnailUrls, track.artworkRes)
    }

    private fun bindArtistClick(view: android.widget.TextView, track: HomeTrackUi) {
        val enabled = track.artistRef != null
        view.isClickable = enabled
        view.isFocusable = enabled
        view.setOnClickListener(if (enabled) View.OnClickListener { callbacks.onArtistClick(track) } else null)
    }

    private fun horizontalParams(
        widthDp: Int,
        marginEndDp: Int,
        height: Int = ViewGroup.LayoutParams.MATCH_PARENT,
    ): LinearLayout.LayoutParams {
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        return LinearLayout.LayoutParams((widthDp * density).toInt(), height).apply {
            marginEnd = (marginEndDp * density).toInt()
        }
    }

    private data class PlaybackPayload(val states: Map<String, Boolean>)

    private object Diff : DiffUtil.ItemCallback<HomeListItem>() {
        override fun areItemsTheSame(oldItem: HomeListItem, newItem: HomeListItem) =
            oldItem::class == newItem::class && oldItem.stableId == newItem.stableId

        override fun areContentsTheSame(oldItem: HomeListItem, newItem: HomeListItem) = oldItem == newItem

        override fun getChangePayload(oldItem: HomeListItem, newItem: HomeListItem): Any? {
            val newTracks = when {
                oldItem is HomeListItem.Recommended && newItem is HomeListItem.Recommended &&
                    sameExceptPlayback(oldItem.groups.flatten(), newItem.groups.flatten()) -> newItem.groups.flatten()
                oldItem is HomeListItem.MostPlayed && newItem is HomeListItem.MostPlayed &&
                    sameExceptPlayback(oldItem.tracks, newItem.tracks) -> newItem.tracks
                oldItem is HomeListItem.RecentTrack && newItem is HomeListItem.RecentTrack &&
                    sameExceptPlayback(listOf(oldItem.track), listOf(newItem.track)) -> listOf(newItem.track)
                oldItem is HomeListItem.LocalTrack && newItem is HomeListItem.LocalTrack &&
                    sameExceptPlayback(listOf(oldItem.track), listOf(newItem.track)) -> listOf(newItem.track)
                else -> return null
            }
            return PlaybackPayload(newTracks.associate { it.id to it.isPlaying })
        }

        private fun sameExceptPlayback(old: List<HomeTrackUi>, new: List<HomeTrackUi>): Boolean =
            old.size == new.size && old.zip(new).all { (oldTrack, newTrack) ->
                oldTrack.copy(isPlaying = newTrack.isPlaying) == newTrack
            }
    }

    private companion object {
        const val MAX_PLAYER_QUEUE_SIZE = 100
        const val TYPE_HEADER = 1
        const val TYPE_SECTION = 2
        const val TYPE_RECOMMENDED = 3
        const val TYPE_MOST_PLAYED = 4
        const val TYPE_SHORTCUTS = 5
        const val TYPE_TRACK = 6
        const val TYPE_RECOMMENDED_SKELETON = 7
        const val TYPE_MOST_PLAYED_SKELETON = 8
        const val TYPE_LOCAL_STATE = 9
        const val TYPE_LOAD_ERROR = 10
    }

    private enum class TrackSource { LOCAL, RECENT }

    private fun List<HomeTrackUi>.queueStartingAt(trackId: String): List<HomeTrackUi> {
        val selectedIndex = indexOfFirst { it.id == trackId }
        if (selectedIndex < 0) return emptyList()
        return (drop(selectedIndex) + take(selectedIndex)).take(MAX_PLAYER_QUEUE_SIZE)
    }
}
