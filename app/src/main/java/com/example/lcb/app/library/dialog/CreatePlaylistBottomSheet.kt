package com.example.lcb.app.library.dialog

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import com.example.lcb.app.databinding.DialogCreatePlaylistBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/** 新建歌单输入弹框，校验错误留在输入框内，不通过 Toast 打断输入。 */
internal class CreatePlaylistBottomSheet(
    private val context: Context,
    private val onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    private val binding = DialogCreatePlaylistBinding.inflate(LayoutInflater.from(context))
    private val dialog = BottomSheetDialog(context).apply {
        setContentView(binding.root)
        setOnDismissListener { onDismiss() }
    }

    init {
        binding.cancel.setOnClickListener { dismiss() }
        binding.create.setOnClickListener { submit() }
        binding.nameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        configureInsets()
        configureSheet()
    }

    fun show() = dialog.show()

    fun dismiss() = dialog.dismiss()

    fun setBusy(busy: Boolean) {
        binding.nameInput.isEnabled = !busy
        binding.cancel.isEnabled = !busy
        binding.create.isEnabled = !busy
    }

    fun showError(message: String) {
        setBusy(false)
        binding.nameLayout.error = message
    }

    private fun submit() {
        val name = binding.nameInput.text?.toString().orEmpty().trim()
        if (name.isEmpty()) {
            binding.nameLayout.error = context.getString(com.example.lcb.app.R.string.playlist_name_required)
            return
        }
        binding.nameLayout.error = null
        onCreate(name)
    }

    private fun configureInsets() {
        val originalBottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = originalBottom + navigation.bottom)
            insets
        }
    }

    private fun configureSheet() {
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<ViewGroup>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener
            sheet.setBackgroundColor(Color.TRANSPARENT)
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
            binding.nameInput.requestFocus()
            binding.nameInput.doOnLayout {
                context.getSystemService(InputMethodManager::class.java)?.showSoftInput(binding.nameInput, 0)
            }
            ViewCompat.requestApplyInsets(binding.root)
        }
    }
}
