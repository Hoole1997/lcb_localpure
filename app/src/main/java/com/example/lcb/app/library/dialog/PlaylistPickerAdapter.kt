package com.example.lcb.app.library.dialog

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.lcb.app.R
import com.example.lcb.app.databinding.ItemPlaylistPickerBinding
import com.example.lcb.app.library.PlaylistSummary
import com.example.lcb.app.ui.TrackArtworkLoader

internal class PlaylistPickerAdapter(
    private val onPlaylistClick: (PlaylistSummary) -> Unit,
) : ListAdapter<PlaylistSummary, PlaylistPickerAdapter.Holder>(Diff) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemPlaylistPickerBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: Holder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    inner class Holder(
        private val binding: ItemPlaylistPickerBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: PlaylistSummary? = null

        fun bind(value: PlaylistSummary) {
            item = value
            binding.title.text = value.name
            binding.count.text = binding.root.resources.getQuantityString(
                R.plurals.playlist_song_count,
                value.trackCount,
                value.trackCount,
            )
            val artworkUrls = value.artworkThumbnailUrls.ifEmpty { listOfNotNull(value.artworkUrl) }
            binding.cover.isVisible = artworkUrls.isNotEmpty()
            binding.fallback.isVisible = artworkUrls.isEmpty()
            if (artworkUrls.isNotEmpty()) {
                TrackArtworkLoader.load(binding.cover.image, artworkUrls, R.drawable.home_cover_recommended_3)
            } else {
                TrackArtworkLoader.clear(binding.cover.image)
            }
            binding.root.setOnClickListener { item?.let(onPlaylistClick) }
        }

        fun recycle() {
            item = null
            binding.root.setOnClickListener(null)
            TrackArtworkLoader.clear(binding.cover.image)
        }
    }

    private object Diff : DiffUtil.ItemCallback<PlaylistSummary>() {
        override fun areItemsTheSame(oldItem: PlaylistSummary, newItem: PlaylistSummary) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: PlaylistSummary, newItem: PlaylistSummary) = oldItem == newItem
    }
}
