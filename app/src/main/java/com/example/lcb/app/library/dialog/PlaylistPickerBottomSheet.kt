package com.example.lcb.app.library.dialog

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.lcb.app.databinding.DialogPlaylistPickerBinding
import com.example.lcb.app.library.PlaylistSummary
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/** 只负责歌单选择 UI，Room 订阅和写入由 [PlaylistDialogsController] 管理。 */
internal class PlaylistPickerBottomSheet(
    private val context: Context,
    onPlaylistSelected: (PlaylistSummary) -> Unit,
    onCreateRequested: () -> Unit,
    onDismiss: () -> Unit,
) {
    private val binding = DialogPlaylistPickerBinding.inflate(LayoutInflater.from(context))
    private val adapter = PlaylistPickerAdapter(onPlaylistSelected)
    private val dialog = BottomSheetDialog(context).apply {
        setContentView(binding.root)
        setOnDismissListener { onDismiss() }
    }
    private var maximumListHeight = 0

    init {
        binding.playlistList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@PlaylistPickerBottomSheet.adapter
            itemAnimator?.changeDuration = 0L
            setHasFixedSize(true)
        }
        binding.createPlaylist.setOnClickListener { onCreateRequested() }
        configureInsets()
        configureSheet(context)
    }

    fun show() = dialog.show()

    fun dismiss() = dialog.dismiss()

    fun submitPlaylists(playlists: List<PlaylistSummary>) {
        adapter.submitList(playlists)
        binding.emptyState.isVisible = playlists.isEmpty()
        binding.playlistList.isVisible = playlists.isNotEmpty()
        if (playlists.isNotEmpty() && maximumListHeight > 0) updateListHeight(playlists.size)
    }

    fun setBusy(busy: Boolean) {
        binding.createPlaylist.isEnabled = !busy
        binding.playlistList.isEnabled = !busy
        binding.progress.isVisible = busy
    }

    private fun configureInsets() {
        val originalBottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = originalBottom + navigation.bottom)
            insets
        }
    }

    private fun configureSheet(context: Context) {
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<ViewGroup>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener
            sheet.setBackgroundColor(Color.TRANSPARENT)
            maximumListHeight = (context.resources.displayMetrics.heightPixels * MAX_HEIGHT_RATIO).toInt()
            updateListHeight(adapter.itemCount)
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
            ViewCompat.requestApplyInsets(binding.root)
        }
    }

    private fun updateListHeight(itemCount: Int) {
        if (itemCount <= 0 || maximumListHeight <= 0) return
        val rowHeight = (ROW_HEIGHT_DP * context.resources.displayMetrics.density).toInt()
        binding.playlistList.layoutParams = binding.playlistList.layoutParams.apply {
            height = (itemCount * rowHeight).coerceAtMost(maximumListHeight)
        }
    }

    private companion object {
        const val MAX_HEIGHT_RATIO = 0.48f
        const val ROW_HEIGHT_DP = 68
    }
}
