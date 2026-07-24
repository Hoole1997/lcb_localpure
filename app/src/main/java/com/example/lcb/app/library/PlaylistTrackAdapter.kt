package com.example.lcb.app.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.lcb.app.R
import com.example.lcb.app.databinding.ItemLibraryTrackBinding
import com.example.lcb.app.ui.TrackArtworkLoader

internal class PlaylistTrackAdapter(
    private val onTrackClick: (LibraryTrack) -> Unit,
    private val onTrackLongClick: (LibraryTrack) -> Unit,
    private val onTrackMore: (LibraryTrack) -> Unit,
    private val onSelectionChanged: (LibraryTrack) -> Unit,
) : ListAdapter<PlaylistTrackUi, PlaylistTrackAdapter.Holder>(Diff) {
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemLibraryTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        val payload = payloads.filterIsInstance<StatePayload>().lastOrNull()
        if (payload != null) holder.bindState(payload) else onBindViewHolder(holder, position)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    inner class Holder(
        private val binding: ItemLibraryTrackBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: PlaylistTrackUi? = null

        fun bind(value: PlaylistTrackUi) {
            item = value
            binding.title.text = value.track.title
            binding.artist.text = value.track.artist
            val urls = value.track.artworkThumbnailUrls.ifEmpty { listOfNotNull(value.track.artworkUrl) }
            TrackArtworkLoader.load(binding.cover.image, urls, value.track.artworkFallbackRes)
            bindState(StatePayload(value.isPlaying, value.isSelected, value.isSelectionMode))
            binding.root.setOnClickListener {
                item?.let { current ->
                    if (current.isSelectionMode) onSelectionChanged(current.track) else onTrackClick(current.track)
                }
            }
            binding.root.setOnLongClickListener {
                item?.track?.let(onTrackLongClick)
                true
            }
            binding.more.setOnClickListener { item?.track?.let(onTrackMore) }
            binding.selected.setOnClickListener { item?.track?.let(onSelectionChanged) }
        }

        fun bindState(payload: StatePayload) {
            item = item?.copy(
                isPlaying = payload.isPlaying,
                isSelected = payload.isSelected,
                isSelectionMode = payload.isSelectionMode,
            )
            binding.cover.setPlaying(payload.isPlaying)
            binding.more.isVisible = !payload.isSelectionMode
            binding.selected.isVisible = payload.isSelectionMode
            binding.selected.isChecked = payload.isSelected
            binding.root.setBackgroundResource(
                if (payload.isSelected) R.drawable.bg_recommended_selected else R.drawable.bg_search_result_ripple,
            )
        }

        fun recycle() {
            item = null
            binding.root.setOnClickListener(null)
            binding.root.setOnLongClickListener(null)
            binding.more.setOnClickListener(null)
            binding.selected.setOnClickListener(null)
            binding.cover.setPlaying(false)
            TrackArtworkLoader.clear(binding.cover.image)
        }
    }

    data class StatePayload(
        val isPlaying: Boolean,
        val isSelected: Boolean,
        val isSelectionMode: Boolean,
    )

    private object Diff : DiffUtil.ItemCallback<PlaylistTrackUi>() {
        override fun areItemsTheSame(oldItem: PlaylistTrackUi, newItem: PlaylistTrackUi) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: PlaylistTrackUi, newItem: PlaylistTrackUi) = oldItem == newItem

        override fun getChangePayload(oldItem: PlaylistTrackUi, newItem: PlaylistTrackUi): Any? {
            return if (oldItem.track == newItem.track) {
                StatePayload(
                    isPlaying = newItem.isPlaying,
                    isSelected = newItem.isSelected,
                    isSelectionMode = newItem.isSelectionMode,
                )
            } else {
                null
            }
        }
    }
}
