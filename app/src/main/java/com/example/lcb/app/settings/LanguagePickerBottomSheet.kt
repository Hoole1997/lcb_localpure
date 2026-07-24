package com.example.lcb.app.settings

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.lcb.app.R
import com.example.lcb.app.databinding.DialogLanguagePickerBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * 独立语言 BottomSheet。结果通过 Fragment Result API 返回，避免持有 Activity/Fragment 回调。
 */
class LanguagePickerBottomSheet : BottomSheetDialogFragment() {
    private var _binding: DialogLanguagePickerBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val languageAdapter = LanguageAdapter(::selectLanguage)

    override fun getTheme(): Int = R.style.ThemeOverlay_LCB_LanguageBottomSheet

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        BottomSheetDialog(requireContext(), theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = DialogLanguagePickerBinding.inflate(inflater, container, false)
        .also { _binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentTag = arguments?.getString(ARG_CURRENT_TAG)
        binding.languageList.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                initialPrefetchItemCount = AppLanguage.entries.size
            }
            adapter = languageAdapter
            setHasFixedSize(true)
            itemAnimator = null
        }
        languageAdapter.submitList(
            AppLanguage.entries.map { language ->
                LanguageOptionUi(language, language.languageTag.equals(currentTag, ignoreCase = true))
            },
        )
        limitListHeight()
        configureInsets()
    }

    override fun onStart() {
        super.onStart()
        val sheet = (dialog as? BottomSheetDialog)
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return
        sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onDestroyView() {
        binding.languageList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun configureInsets() {
        val originalBottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.updatePadding(bottom = originalBottom + navigation.bottom)
            insets
        }
    }

    private fun selectLanguage(language: AppLanguage) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply { putString(RESULT_LANGUAGE_TAG, language.languageTag) },
        )
        dismiss()
    }

    private fun limitListHeight() {
        val density = resources.displayMetrics.density
        val rowHeight = (LANGUAGE_ROW_HEIGHT_DP * density).toInt()
        val contentHeight = rowHeight * AppLanguage.entries.size
        val maximumHeight = (resources.displayMetrics.heightPixels * MAX_LIST_HEIGHT_RATIO).toInt()
        binding.languageList.layoutParams = binding.languageList.layoutParams.apply {
            height = contentHeight.coerceAtMost(maximumHeight.coerceAtLeast(rowHeight * 3))
        }
    }

    companion object {
        const val REQUEST_KEY = "settings.language.request"
        const val RESULT_LANGUAGE_TAG = "settings.language.tag"
        const val TAG = "LanguagePickerBottomSheet"
        private const val ARG_CURRENT_TAG = "settings.language.current"
        private const val LANGUAGE_ROW_HEIGHT_DP = 56
        private const val MAX_LIST_HEIGHT_RATIO = 0.62f

        fun newInstance(currentLanguageTag: String) = LanguagePickerBottomSheet().apply {
            arguments = Bundle().apply { putString(ARG_CURRENT_TAG, currentLanguageTag) }
        }
    }
}
