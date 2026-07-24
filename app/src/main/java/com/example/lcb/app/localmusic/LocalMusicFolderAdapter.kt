package com.example.lcb.app.localmusic

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.lcb.app.R
import com.example.lcb.app.databinding.ItemLocalMusicFolderBinding

/** 文件夹横向筛选器独立于歌曲列表，便于后续复用或替换为真正的目录页。 */
internal class LocalMusicFolderAdapter(
    private val onFolderClick: (String?) -> Unit,
) : ListAdapter<LocalMusicFolderUi, LocalMusicFolderAdapter.Holder>(Diff) {
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemLocalMusicFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemLocalMusicFolderBinding) : RecyclerView.ViewHolder(binding.root) {
        private var item: LocalMusicFolderUi? = null

        init {
            binding.root.setOnClickListener { item?.let { onFolderClick(it.name) } }
        }

        fun bind(value: LocalMusicFolderUi) {
            item = value
            val label = value.name ?: binding.root.context.getString(R.string.local_music_all_tracks)
            binding.root.text = binding.root.context.getString(
                R.string.local_music_folder_chip,
                label,
                value.trackCount,
            )
            binding.root.isSelected = value.isSelected
            binding.root.isActivated = value.isSelected
        }
    }

    private object Diff : DiffUtil.ItemCallback<LocalMusicFolderUi>() {
        override fun areItemsTheSame(oldItem: LocalMusicFolderUi, newItem: LocalMusicFolderUi) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: LocalMusicFolderUi, newItem: LocalMusicFolderUi) = oldItem == newItem
    }
}
