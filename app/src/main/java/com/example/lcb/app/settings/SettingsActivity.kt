package com.example.lcb.app.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.lcb.app.R
import com.example.lcb.app.analytics.MusicAnalytics
import com.example.lcb.app.databinding.ActivitySettingsBinding
import com.example.lcb.app.utils.BusinessAdSwitchKey
import com.example.lcb.app.utils.loadNative
import kotlinx.coroutines.launch
import net.corekit.core.controller.AdSlotSwitchController

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val repository by lazy(LazyThreadSafetyMode.NONE) { DefaultAppSettingsRepository() }
    private val viewModel: SettingsViewModel by viewModels { SettingsViewModel.Factory(repository) }
    private val settingsAdapter by lazy(LazyThreadSafetyMode.NONE) { SettingsAdapter(::onSettingClick) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureSystemBars()
        configureToolbar()
        configureInsets()
        configureSettingsList()
        configureLanguageResult()
        observeState()
        if (savedInstanceState == null) MusicAnalytics.screenView(MusicAnalytics.Screen.SETTINGS)
        // 设置操作不使用插屏；底部原生广告失败时容器自动隐藏。
        if (AdSlotSwitchController.isEnabled(BusinessAdSwitchKey.SETTINGS_BOTTOM_NATIVE)) {
            loadNative(binding.adContainer, position = TAG)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统“应用语言”页面返回时同步 Android 13+ 可能发生的外部修改。
        viewModel.refreshLocale()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun configureSystemBars() {
        androidx.core.view.WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun configureToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeActionContentDescription(R.string.action_back)
            title = getString(R.string.settings_title)
        }
        binding.toolbar.navigationIcon?.setTint(Color.WHITE)
    }

    private fun configureInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            binding.toolbar.updatePadding(top = bars.top)
            insets
        }
    }

    private fun configureSettingsList() = with(binding.settingsList) {
        layoutManager = LinearLayoutManager(this@SettingsActivity)
        adapter = settingsAdapter
        setHasFixedSize(true)
        itemAnimator = null
    }

    private fun configureLanguageResult() {
        supportFragmentManager.setFragmentResultListener(
            LanguagePickerBottomSheet.REQUEST_KEY,
            this,
        ) { _, result ->
            val tag = result.getString(LanguagePickerBottomSheet.RESULT_LANGUAGE_TAG)
                ?: return@setFragmentResultListener
            val applyResult = viewModel.applyLanguage(tag)
            MusicAnalytics.settings(
                MusicAnalytics.SettingsAction.APPLY_LANGUAGE,
                if (applyResult == LanguageApplyResult.FAILED) {
                    MusicAnalytics.Outcome.FAILURE
                } else {
                    MusicAnalytics.Outcome.SUCCESS
                },
                value = tag,
            )
            if (applyResult == LanguageApplyResult.FAILED) {
                Toast.makeText(this, R.string.settings_language_change_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: SettingsUiState) {
        settingsAdapter.submitList(
            listOf(
                SettingsRowUi(
                    SettingsAction.LANGUAGE,
                    R.string.settings_language,
                    R.drawable.ic_settings_language,
                    getString(state.currentLanguage.displayNameRes),
                ),
                SettingsRowUi(
                    SettingsAction.PRIVACY_POLICY,
                    R.string.settings_privacy_policy,
                    R.drawable.ic_settings_privacy,
                ),
                SettingsRowUi(
                    SettingsAction.TERMS_OF_SERVICE,
                    R.string.settings_terms_of_service,
                    R.drawable.ic_settings_terms,
                ),
            ),
        )
    }

    private fun onSettingClick(action: SettingsAction) {
        when (action) {
            SettingsAction.LANGUAGE -> {
                MusicAnalytics.settings(
                    action = MusicAnalytics.SettingsAction.OPEN_LANGUAGE,
                    value = viewModel.state.value.currentLanguage.analyticsValue,
                )
                showLanguagePicker()
            }
            SettingsAction.PRIVACY_POLICY -> openSecurePage(
                viewModel.state.value.privacyPolicyUrl,
                MusicAnalytics.SettingsAction.OPEN_PRIVACY_POLICY,
            )
            SettingsAction.TERMS_OF_SERVICE -> openSecurePage(
                viewModel.state.value.termsOfServiceUrl,
                MusicAnalytics.SettingsAction.OPEN_TERMS_OF_SERVICE,
            )
        }
    }

    private fun showLanguagePicker() {
        if (supportFragmentManager.findFragmentByTag(LanguagePickerBottomSheet.TAG) != null) return
        LanguagePickerBottomSheet
            .newInstance(viewModel.state.value.currentLanguage.languageTag)
            .show(supportFragmentManager, LanguagePickerBottomSheet.TAG)
    }

    private fun openSecurePage(url: String?, analyticsAction: MusicAnalytics.SettingsAction) {
        if (url == null) {
            MusicAnalytics.settings(analyticsAction, MusicAnalytics.Outcome.FAILURE)
            Toast.makeText(this, R.string.settings_link_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            startActivity(intent)
            MusicAnalytics.settings(analyticsAction, MusicAnalytics.Outcome.SUCCESS)
        } catch (_: ActivityNotFoundException) {
            MusicAnalytics.settings(analyticsAction, MusicAnalytics.Outcome.FAILURE)
            Toast.makeText(this, R.string.settings_link_open_failed, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            MusicAnalytics.settings(analyticsAction, MusicAnalytics.Outcome.FAILURE)
            Toast.makeText(this, R.string.settings_link_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "SettingsActivity"
        fun open(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}
