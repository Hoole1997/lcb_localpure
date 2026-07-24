package com.example.lcb.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsViewModelTest {
    @Test
    fun `successful language selection updates immutable state`() {
        val repository = FakeRepository()
        val viewModel = SettingsViewModel(repository)

        val result = viewModel.applyLanguage(AppLanguage.CHINESE_SIMPLIFIED.languageTag)

        assertEquals(LanguageApplyResult.APPLIED, result)
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, viewModel.state.value.currentLanguage)
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, repository.current)
    }

    @Test
    fun `failed language selection preserves current language`() {
        val repository = FakeRepository(result = LanguageApplyResult.FAILED)
        val viewModel = SettingsViewModel(repository)

        val result = viewModel.applyLanguage(AppLanguage.JAPANESE.languageTag)

        assertEquals(LanguageApplyResult.FAILED, result)
        assertEquals(AppLanguage.ENGLISH, viewModel.state.value.currentLanguage)
    }

    @Test
    fun `refresh reflects a locale changed outside the activity`() {
        val repository = FakeRepository()
        val viewModel = SettingsViewModel(repository)
        repository.current = AppLanguage.GERMAN

        viewModel.refreshLocale()

        assertEquals(AppLanguage.GERMAN, viewModel.state.value.currentLanguage)
    }

    @Test
    fun `regional tags resolve to their supported app language`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-US"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromTag("zh"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromTag("zh-CN"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromTag("zh-Hans-US"))
        assertNull(AppLanguage.fromTag("zh-TW"))
        assertNull(AppLanguage.fromTag("zh-Hant-HK"))
    }

    private class FakeRepository(
        private val result: LanguageApplyResult = LanguageApplyResult.APPLIED,
    ) : AppSettingsRepository {
        override val supportedLanguages = AppLanguage.entries
        override val privacyPolicyUrl: String? = "https://example.com/privacy"
        override val termsOfServiceUrl: String? = "https://example.com/terms"
        var current: AppLanguage = AppLanguage.ENGLISH

        override fun currentLanguage() = current

        override fun applyLanguage(language: AppLanguage): LanguageApplyResult {
            if (result != LanguageApplyResult.FAILED) current = language
            return result
        }
    }
}
