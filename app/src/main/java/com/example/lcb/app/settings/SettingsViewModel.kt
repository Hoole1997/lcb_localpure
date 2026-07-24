package com.example.lcb.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsUiState(
    val supportedLanguages: List<AppLanguage>,
    val currentLanguage: AppLanguage,
    val privacyPolicyUrl: String?,
    val termsOfServiceUrl: String?,
)

/** 设置页保持单向数据流；Activity 只发送事件并渲染不可变状态。 */
class SettingsViewModel(
    private val repository: AppSettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(repository.toUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun refreshLocale() {
        _state.update { it.copy(currentLanguage = repository.currentLanguage()) }
    }

    fun applyLanguage(languageTag: String): LanguageApplyResult {
        val language = repository.supportedLanguages.firstOrNull {
            it.languageTag.equals(languageTag, ignoreCase = true)
        } ?: return LanguageApplyResult.FAILED
        val result = repository.applyLanguage(language)
        if (result != LanguageApplyResult.FAILED) {
            _state.update { it.copy(currentLanguage = language) }
        }
        return result
    }

    private fun AppSettingsRepository.toUiState() = SettingsUiState(
        supportedLanguages = supportedLanguages,
        currentLanguage = currentLanguage(),
        privacyPolicyUrl = privacyPolicyUrl,
        termsOfServiceUrl = termsOfServiceUrl,
    )

    class Factory(private val repository: AppSettingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(repository) as T
    }
}
