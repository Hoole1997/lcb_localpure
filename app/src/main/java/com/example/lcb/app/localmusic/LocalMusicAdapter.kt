package com.example.lcb.app.localmusic

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.lcb.app.R
import com.example.lcb.app.databinding.ItemLocalMusicTrackBinding
import com.example.lcb.app.ui.TrackArtworkLoader
import java.util.Locale

/** 本地歌曲列表不持有 Cursor；提交给 DiffUtil 的都是扫描完成后的不可变轻量元数据。 */
internal class LocalMusicAdapter(
    private val onTrackClick: (LocalMusicTrack) -> Unit,
    private val onTrackMore: (LocalMusicTrack) -> Unit,
) : ListAdapter<LocalMusicTrackUi, LocalMusicAdapter.Holder>(Diff) {
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemLocalMusicTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        val playing = payloads.filterIsInstance<Boolean>().lastOrNull()
        if (playing != null) holder.bindPlayback(playing) else onBindViewHolder(holder, position)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    inner class Holder(private val binding: ItemLocalMusicTrackBinding) : RecyclerView.ViewHolder(binding.root) {
        private var item: LocalMusicTrackUi? = null

        fun bind(value: LocalMusicTrackUi) {
            item = value
            binding.title.text = value.track.title
            binding.metadata.text = metadata(value.track)
            binding.duration.text = duration(value.track.durationMs)
            TrackArtworkLoader.load(
                binding.cover.image,
                listOfNotNull(value.track.artworkUrl),
                R.drawable.placeholder_local_music_track,
            )
            bindPlayback(value.isPlaying)
            binding.root.setOnClickListener { item?.track?.let(onTrackClick) }
            binding.more.setOnClickListener { item?.track?.let(onTrackMore) }
        }

        fun bindPlayback(isPlaying: Boolean) {
            item = item?.copy(isPlaying = isPlaying)
            binding.cover.setPlaying(isPlaying)
        }

        fun recycle() {
            item = null
            binding.root.setOnClickListener(null)
            binding.more.setOnClickListener(null)
            binding.cover.setPlaying(false)
            TrackArtworkLoader.clear(binding.cover.image)
        }

        private fun metadata(track: LocalMusicTrack): String = buildList {
            add(track.artist)
            track.album
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != "<unknown>" && !it.equals(track.artist, ignoreCase = true) }
                ?.let(::add)
        }.joinToString(" · ")

        private fun duration(durationMs: Long): String {
            val totalSeconds = (durationMs.coerceAtLeast(0L) / 1_000L)
            val hours = totalSeconds / 3_600L
            val minutes = (totalSeconds % 3_600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%d:%02d", minutes, seconds)
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<LocalMusicTrackUi>() {
        override fun areItemsTheSame(oldItem: LocalMusicTrackUi, newItem: LocalMusicTrackUi) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: LocalMusicTrackUi, newItem: LocalMusicTrackUi) = oldItem == newItem
        override fun getChangePayload(oldItem: LocalMusicTrackUi, newItem: LocalMusicTrackUi): Any? =
            newItem.isPlaying.takeIf { oldItem.track == newItem.track && oldItem.isPlaying != newItem.isPlaying }
    }
}
