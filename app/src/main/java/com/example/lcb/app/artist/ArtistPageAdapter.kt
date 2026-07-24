package com.example.lcb.app.artist

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.lcb.app.R
import com.example.lcb.app.databinding.ItemArtistAboutBinding
import com.example.lcb.app.databinding.ItemArtistActionsBinding
import com.example.lcb.app.databinding.ItemArtistCollectionBinding
import com.example.lcb.app.databinding.ItemArtistCollectionCarouselBinding
import com.example.lcb.app.databinding.ItemArtistMessageBinding
import com.example.lcb.app.databinding.ItemArtistSectionHeaderBinding
import com.example.lcb.app.databinding.ItemRecommendedMusicBinding
import com.example.lcb.app.databinding.ItemSearchLoadingBinding
import com.example.lcb.app.databinding.ItemSearchLoadMoreErrorBinding
import com.example.lcb.app.ui.TrackArtworkLoader
import com.example.lcb.music.model.MusicCollectionType
import com.example.lcb.music.model.MusicPlatform
import java.text.NumberFormat

sealed interface ArtistListItem {
    val stableId: Long

    data class Actions(val canPlay: Boolean) : ArtistListItem { override val stableId = 1L }
    data class About(val bio: String?, val tags: List<String>, val expanded: Boolean) : ArtistListItem {
        override val stableId = 2L
    }
    data class SectionHeader(val id: Long, @param:StringRes val titleRes: Int, val count: Int?) : ArtistListItem {
        override val stableId = id
    }
    data class Collections(
        val id: Long,
        val values: List<ArtistCollectionUi>,
        val loadingId: String?,
    ) : ArtistListItem { override val stableId = id }
    data class Track(val value: ArtistTrackUi) : ArtistListItem {
        override val stableId = 10_000L + value.id.hashCode().toLong()
    }
    data class Message(val id: Long, @param:StringRes val textRes: Int, val retryable: Boolean) : ArtistListItem {
        override val stableId = id
    }
    data class Skeleton(val index: Int) : ArtistListItem {
        override val stableId = Long.MIN_VALUE + index
    }
    data object LoadingMore : ArtistListItem { override val stableId = Long.MAX_VALUE - 1 }
    data class LoadMoreError(val error: ArtistLoadError) : ArtistListItem { override val stableId = Long.MAX_VALUE }
}

/** 歌手页主列表：所有纵向内容统一由 ListAdapter + DiffUtil 更新。 */
class ArtistPageAdapter(
    private val recycledViewPool: RecyclerView.RecycledViewPool,
    private val onPlayAll: () -> Unit,
    private val onShare: () -> Unit,
    private val onBioToggle: () -> Unit,
    private val onTrackClick: (ArtistTrackUi) -> Unit,
    private val onTrackMore: (ArtistTrackUi) -> Unit,
    private val onCollectionClick: (ArtistCollectionUi) -> Unit,
    private val onRetry: () -> Unit,
) : ListAdapter<ArtistListItem, RecyclerView.ViewHolder>(Diff) {
    private val animatedIds = hashSetOf<Long>()

    init {
        setHasStableIds(true)
    }

    fun submitState(state: ArtistUiState) {
        submitList(
            buildList {
                if (state.isInitialLoading) {
                    repeat(SKELETON_COUNT) { add(ArtistListItem.Skeleton(it)) }
                    return@buildList
                }

                add(ArtistListItem.Actions(canPlay = state.tracks.isNotEmpty()))
                state.details?.let { details ->
                    val bio = details.artist.bio?.takeIf(String::isNotBlank)
                    if (bio != null || details.tags.isNotEmpty()) {
                        add(ArtistListItem.About(bio, details.tags, state.isBioExpanded))
                    }
                }
                state.detailsError?.let {
                    add(ArtistListItem.Message(DETAILS_ERROR_ID, it.messageRes, retryable = true))
                }

                if (state.albums.isNotEmpty() || state.albumsError != null) {
                    add(ArtistListItem.SectionHeader(ALBUM_HEADER_ID, R.string.artist_releases, state.details?.albumCount))
                    if (state.albums.isNotEmpty()) {
                        add(ArtistListItem.Collections(ALBUMS_ID, state.albums, state.loadingCollectionId))
                    } else {
                        add(
                            ArtistListItem.Message(
                                ALBUM_ERROR_ID,
                                requireNotNull(state.albumsError).messageRes,
                                retryable = true,
                            ),
                        )
                    }
                }

                if (state.playlists.isNotEmpty() || state.playlistsError != null) {
                    add(
                        ArtistListItem.SectionHeader(
                            PLAYLIST_HEADER_ID,
                            R.string.artist_playlists,
                            state.details?.playlistCount,
                        ),
                    )
                    if (state.playlists.isNotEmpty()) {
                        add(ArtistListItem.Collections(PLAYLISTS_ID, state.playlists, state.loadingCollectionId))
                    } else if (state.request.platform == MusicPlatform.AUDIUS) {
                        add(
                            ArtistListItem.Message(
                                PLAYLIST_ERROR_ID,
                                requireNotNull(state.playlistsError).messageRes,
                                retryable = true,
                            ),
                        )
                    }
                }

                add(
                    ArtistListItem.SectionHeader(
                        TRACK_HEADER_ID,
                        R.string.artist_popular_songs,
                        state.details?.artist?.trackCount,
                    ),
                )
                when {
                    state.tracks.isNotEmpty() -> addAll(state.tracks.map(ArtistListItem::Track))
                    state.tracksError != null -> add(
                        ArtistListItem.Message(
                            TRACK_ERROR_ID,
                            state.tracksError.messageRes,
                            retryable = true,
                        ),
                    )
                    else -> add(ArtistListItem.Message(TRACK_EMPTY_ID, R.string.artist_no_songs, false))
                }
                when {
                    state.isLoadingMore -> add(ArtistListItem.LoadingMore)
                    state.loadMoreError != null -> add(ArtistListItem.LoadMoreError(state.loadMoreError))
                }
            },
        )
    }

    override fun getItemId(position: Int): Long = getItem(position).stableId

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ArtistListItem.Actions -> TYPE_ACTIONS
        is ArtistListItem.About -> TYPE_ABOUT
        is ArtistListItem.SectionHeader -> TYPE_HEADER
        is ArtistListItem.Collections -> TYPE_COLLECTIONS
        is ArtistListItem.Track -> TYPE_TRACK
        is ArtistListItem.Message -> TYPE_MESSAGE
        is ArtistListItem.Skeleton -> TYPE_SKELETON
        ArtistListItem.LoadingMore -> TYPE_LOADING
        is ArtistListItem.LoadMoreError -> TYPE_LOAD_ERROR
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ACTIONS -> ActionsHolder(ItemArtistActionsBinding.inflate(inflater, parent, false))
            TYPE_ABOUT -> AboutHolder(ItemArtistAboutBinding.inflate(inflater, parent, false))
            TYPE_HEADER -> HeaderHolder(ItemArtistSectionHeaderBinding.inflate(inflater, parent, false))
            TYPE_COLLECTIONS -> CollectionsHolder(
                ItemArtistCollectionCarouselBinding.inflate(inflater, parent, false),
            )
            TYPE_TRACK -> TrackHolder(ItemRecommendedMusicBinding.inflate(inflater, parent, false))
            TYPE_MESSAGE -> MessageHolder(ItemArtistMessageBinding.inflate(inflater, parent, false))
            TYPE_SKELETON -> SkeletonHolder(inflater.inflate(R.layout.item_search_skeleton, parent, false))
            TYPE_LOADING -> LoadingHolder(ItemSearchLoadingBinding.inflate(inflater, parent, false))
            else -> LoadErrorHolder(ItemSearchLoadMoreErrorBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ArtistListItem.Actions -> (holder as ActionsHolder).bind(item)
            is ArtistListItem.About -> (holder as AboutHolder).bind(item)
            is ArtistListItem.SectionHeader -> (holder as HeaderHolder).bind(item)
            is ArtistListItem.Collections -> (holder as CollectionsHolder).bind(item)
            is ArtistListItem.Track -> (holder as TrackHolder).bind(item.value)
            is ArtistListItem.Message -> (holder as MessageHolder).bind(item)
            is ArtistListItem.Skeleton -> Unit
            ArtistListItem.LoadingMore -> Unit
            is ArtistListItem.LoadMoreError -> (holder as LoadErrorHolder).bind(item.error)
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
        when (holder) {
            is SkeletonHolder -> holder.start()
            is TrackHolder -> animateIn(holder)
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

    private fun animateIn(holder: RecyclerView.ViewHolder) {
        if (!animationsEnabled() || !animatedIds.add(holder.itemId)) return
        val distance = holder.itemView.resources.displayMetrics.density * 8f
        holder.itemView.apply {
            alpha = 0f
            translationY = distance
            animate().alpha(1f).translationY(0f).setDuration(200L).start()
        }
    }

    private inner class ActionsHolder(
        private val binding: ItemArtistActionsBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ArtistListItem.Actions) {
            binding.play.isEnabled = item.canPlay
            binding.play.alpha = if (item.canPlay) 1f else 0.42f
            binding.play.setOnClickListener { onPlayAll() }
            binding.share.setOnClickListener { onShare() }
        }
    }

    private inner class AboutHolder(
        private val binding: ItemArtistAboutBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ArtistListItem.About) {
            binding.bio.isVisible = item.bio != null
            binding.bio.maxLines = if (item.expanded) Int.MAX_VALUE else BIO_COLLAPSED_LINES
            binding.bio.ellipsize = if (item.expanded) null else android.text.TextUtils.TruncateAt.END
            binding.bio.text = item.bio?.let {
                HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
            }
            binding.tags.isVisible = item.tags.isNotEmpty()
            binding.tags.text = item.tags.take(MAX_TAGS).joinToString("   ") { "#$it" }
            val canExpand = (binding.bio.text?.length ?: 0) > BIO_EXPAND_THRESHOLD
            binding.toggle.isVisible = canExpand
            binding.toggle.text = binding.root.context.getString(
                if (item.expanded) R.string.artist_less else R.string.artist_more,
            )
            binding.toggle.setOnClickListener { onBioToggle() }
        }
    }

    private class HeaderHolder(
        private val binding: ItemArtistSectionHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ArtistListItem.SectionHeader) {
            binding.title.setText(item.titleRes)
            binding.count.isVisible = item.count != null
            binding.count.text = item.count?.let(NumberFormat.getIntegerInstance()::format).orEmpty()
        }
    }

    private inner class CollectionsHolder(
        private val binding: ItemArtistCollectionCarouselBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private val collectionAdapter = ArtistCollectionAdapter(onCollectionClick)

        init {
            binding.collections.apply {
                layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false).apply {
                    initialPrefetchItemCount = COLLECTION_PREFETCH_COUNT
                }
                adapter = collectionAdapter
                setHasFixedSize(true)
                setRecycledViewPool(recycledViewPool)
                itemAnimator = null
            }
        }

        fun bind(item: ArtistListItem.Collections) {
            collectionAdapter.submitList(
                item.values.map { value -> CollectionCard(value, value.id == item.loadingId) },
            )
        }
    }

    private inner class TrackHolder(
        private val binding: ItemRecommendedMusicBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var bound: ArtistTrackUi? = null

        fun bind(item: ArtistTrackUi) {
            bound = item
            binding.title.text = item.track.title
            binding.artist.text = item.track.artist
            binding.selected.isVisible = false
            binding.more.isVisible = true
            binding.cover.setPlaying(item.isPlaying)
            TrackArtworkLoader.load(binding.cover.image, item.artworkUrls, item.artworkFallbackRes)
            binding.root.setOnClickListener { bound?.let(onTrackClick) }
            binding.more.setOnClickListener { bound?.let(onTrackMore) }
        }

        fun bindPlayback(isPlaying: Boolean) {
            bound = bound?.copy(isPlaying = isPlaying)
            binding.cover.setPlaying(isPlaying)
        }

        fun recycle() {
            bound = null
            binding.root.animate().cancel()
            binding.root.alpha = 1f
            binding.root.translationY = 0f
            binding.root.setOnClickListener(null)
            binding.more.setOnClickListener(null)
            binding.cover.setPlaying(false)
            TrackArtworkLoader.clear(binding.cover.image)
        }
    }

    private inner class MessageHolder(
        private val binding: ItemArtistMessageBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ArtistListItem.Message) {
            val context = binding.root.context
            val message = context.getString(item.textRes)
            binding.message.text = if (item.retryable) {
                context.getString(R.string.artist_tap_to_retry, message)
            } else {
                message
            }
            binding.root.isClickable = item.retryable
            binding.root.isFocusable = item.retryable
            binding.root.setOnClickListener(if (item.retryable) View.OnClickListener { onRetry() } else null)
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

    private inner class LoadErrorHolder(
        private val binding: ItemSearchLoadMoreErrorBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(error: ArtistLoadError) {
            val context = binding.root.context
            binding.message.text = context.getString(
                R.string.artist_tap_to_retry,
                context.getString(error.messageRes),
            )
            binding.root.setOnClickListener { onRetry() }
        }
    }

    private data class PlaybackPayload(val isPlaying: Boolean)

    private object Diff : DiffUtil.ItemCallback<ArtistListItem>() {
        override fun areItemsTheSame(oldItem: ArtistListItem, newItem: ArtistListItem): Boolean =
            oldItem::class == newItem::class && oldItem.stableId == newItem.stableId

        override fun areContentsTheSame(oldItem: ArtistListItem, newItem: ArtistListItem): Boolean = oldItem == newItem

        override fun getChangePayload(oldItem: ArtistListItem, newItem: ArtistListItem): Any? =
            if (
                oldItem is ArtistListItem.Track && newItem is ArtistListItem.Track &&
                oldItem.value.copy(isPlaying = false) == newItem.value.copy(isPlaying = false) &&
                oldItem.value.isPlaying != newItem.value.isPlaying
            ) {
                PlaybackPayload(newItem.value.isPlaying)
            } else {
                null
            }
    }

    private companion object {
        const val TYPE_ACTIONS = 1
        const val TYPE_ABOUT = 2
        const val TYPE_HEADER = 3
        const val TYPE_COLLECTIONS = 4
        const val TYPE_TRACK = 5
        const val TYPE_MESSAGE = 6
        const val TYPE_SKELETON = 7
        const val TYPE_LOADING = 8
        const val TYPE_LOAD_ERROR = 9
        const val SKELETON_COUNT = 7
        const val BIO_COLLAPSED_LINES = 4
        const val BIO_EXPAND_THRESHOLD = 180
        const val MAX_TAGS = 8
        const val COLLECTION_PREFETCH_COUNT = 4
        const val DETAILS_ERROR_ID = 101L
        const val ALBUM_HEADER_ID = 102L
        const val ALBUMS_ID = 103L
        const val ALBUM_ERROR_ID = 104L
        const val PLAYLIST_HEADER_ID = 105L
        const val PLAYLISTS_ID = 106L
        const val PLAYLIST_ERROR_ID = 107L
        const val TRACK_HEADER_ID = 108L
        const val TRACK_ERROR_ID = 109L
        const val TRACK_EMPTY_ID = 110L
    }
}

private data class CollectionCard(
    val value: ArtistCollectionUi,
    val isLoading: Boolean,
)

private class ArtistCollectionAdapter(
    private val onClick: (ArtistCollectionUi) -> Unit,
) : ListAdapter<CollectionCard, ArtistCollectionAdapter.Holder>(Diff) {
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).value.id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemArtistCollectionBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: Holder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    inner class Holder(
        private val binding: ItemArtistCollectionBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var bound: ArtistCollectionUi? = null

        fun bind(item: CollectionCard) {
            bound = item.value
            binding.title.text = item.value.title
            binding.subtitle.text = collectionSubtitle(binding.root, item.value)
            binding.progress.isVisible = item.isLoading
            binding.play.isVisible = !item.isLoading
            binding.root.isEnabled = !item.isLoading
            binding.root.alpha = if (item.isLoading) 0.72f else 1f
            TrackArtworkLoader.load(binding.artwork, item.value.artworkUrls, item.value.artworkFallbackRes)
            binding.root.setOnClickListener { bound?.let(onClick) }
        }

        fun recycle() {
            bound = null
            binding.root.setOnClickListener(null)
            TrackArtworkLoader.clear(binding.artwork)
        }
    }

    /** Provider 元数据原样展示；缺省的产品文案与数量由 Android 资源系统本地化。 */
    private fun collectionSubtitle(view: View, collection: ArtistCollectionUi): String {
        collection.subtitle.takeIf(String::isNotBlank)?.let { return it }
        collection.trackCount?.let { count ->
            return view.resources.getQuantityString(R.plurals.artist_track_count, count, count)
        }
        return view.context.getString(
            if (collection.type == MusicCollectionType.ALBUM) {
                R.string.artist_collection_release
            } else {
                R.string.artist_collection_playlist
            },
        )
    }

    private object Diff : DiffUtil.ItemCallback<CollectionCard>() {
        override fun areItemsTheSame(oldItem: CollectionCard, newItem: CollectionCard): Boolean =
            oldItem.value.id == newItem.value.id && oldItem.value.platform == newItem.value.platform

        override fun areContentsTheSame(oldItem: CollectionCard, newItem: CollectionCard): Boolean = oldItem == newItem
    }
}

private fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()
