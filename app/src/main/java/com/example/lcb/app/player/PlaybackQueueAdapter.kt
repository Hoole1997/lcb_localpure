package com.example.lcb.app.player

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.lcb.app.R
import com.example.lcb.app.databinding.ItemPlaybackQueueBinding

/** 使用 ListAdapter/DiffUtil，只刷新切歌前后的状态项，避免整个队列闪动。 */
class PlaybackQueueAdapter(
    private val onTrackClick: (PlayerTrack) -> Unit,
) : ListAdapter<PlaybackQueueAdapter.QueueItem, PlaybackQueueAdapter.QueueViewHolder>(DiffCallback) {

    data class QueueItem(val track: PlayerTrack, val isCurrent: Boolean, val isPlaying: Boolean)

    fun submitTracks(tracks: List<PlayerTrack>, currentTrackId: String?, isPlaying: Boolean) {
        submitList(tracks.map { QueueItem(it, it.id == currentTrackId, it.id == currentTrackId && isPlaying) })
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val binding = ItemPlaybackQueueBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return QueueViewHolder(binding, onTrackClick)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: QueueViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    class QueueViewHolder(
        private val binding: ItemPlaybackQueueBinding,
        private val onTrackClick: (PlayerTrack) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: QueueItem) {
            with(binding) {
                title.text = item.track.title
                artist.text = item.track.artist
                playing.visibility = if (item.isCurrent) View.VISIBLE else View.INVISIBLE
                playing.setPlaying(item.isPlaying)
                playing.contentDescription = root.context.getString(
                    if (item.isPlaying) R.string.player_currently_playing else R.string.player_current_track,
                )
                title.setTextColor(if (item.isCurrent) CURRENT_TITLE_COLOR else DEFAULT_TITLE_COLOR)
                title.setTypeface(null, if (item.isCurrent) Typeface.BOLD else Typeface.NORMAL)
                artist.setTextColor(if (item.isCurrent) CURRENT_ARTIST_COLOR else DEFAULT_ARTIST_COLOR)
                root.setOnClickListener { onTrackClick(item.track) }
                Glide.with(cover)
                    .load(item.track.artworkUrl?.takeIf(String::isNotBlank) ?: R.drawable.home_cover_recommended_3)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .placeholder(R.drawable.bg_player_artwork)
                    .error(R.drawable.home_cover_recommended_3)
                    .centerCrop()
                    .into(cover)
            }
        }

        fun clear() = Glide.with(binding.cover).clear(binding.cover)
    }

    private object DiffCallback : DiffUtil.ItemCallback<QueueItem>() {
        override fun areItemsTheSame(oldItem: QueueItem, newItem: QueueItem) = oldItem.track.id == newItem.track.id
        override fun areContentsTheSame(oldItem: QueueItem, newItem: QueueItem) = oldItem == newItem
    }

    private companion object {
        val CURRENT_TITLE_COLOR: Int = Color.parseColor("#FFFF78C8")
        val CURRENT_ARTIST_COLOR: Int = Color.parseColor("#BFFFFFFF")
        val DEFAULT_TITLE_COLOR: Int = Color.parseColor("#E8FFFFFF")
        val DEFAULT_ARTIST_COLOR: Int = Color.parseColor("#82FFFFFF")
    }
}
