package com.example.lcb.app.settings

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.example.lcb.app.BuildConfig

/** 通过 AndroidX 官方 per-app locale API 统一兼容 Android 8 至 Android 15+。 */
class DefaultAppSettingsRepository(context: Context) : AppSettingsRepository {
    private val applicationContext = context.applicationContext

    override val supportedLanguages: List<AppLanguage> = AppLanguage.entries
    override val privacyPolicyUrl: String? = BuildConfig.PRIVACY_POLICY_URL.validHttpsUrlOrNull()
    override val termsOfServiceUrl: String? = BuildConfig.TERMS_OF_SERVICE_URL.validHttpsUrlOrNull()

    override fun currentLanguage(): AppLanguage {
        val explicitTag = runCatching {
            AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
        }.onFailure { error ->
            // 个别厂商系统的 LocaleManager 可能异常，设置页不能因此崩溃。
            Log.w(TAG, "Unable to read application locale", error)
        }.getOrNull()
        val effectiveTag = explicitTag ?: applicationContext.resources.configuration.locales
            .takeIf { !it.isEmpty }
            ?.get(0)
            ?.toLanguageTag()
        return AppLanguage.fromTag(effectiveTag) ?: AppLanguage.ENGLISH
    }

    override fun applyLanguage(language: AppLanguage): LanguageApplyResult {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Application locale must be changed on the main thread"
        }
        val hasExplicitLocale = runCatching {
            AppCompatDelegate.getApplicationLocales().get(0) != null
        }.getOrDefault(false)
        if (language == currentLanguage() && hasExplicitLocale) {
            return LanguageApplyResult.UNCHANGED
        }
        return runCatching {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(language.languageTag),
            )
        }.fold(
            onSuccess = { LanguageApplyResult.APPLIED },
            onFailure = { error ->
                Log.e(TAG, "Unable to apply application locale", error)
                LanguageApplyResult.FAILED
            },
        )
    }

    private fun String.validHttpsUrlOrNull(): String? = trim().takeIf { value ->
        value.startsWith("https://", ignoreCase = true) && value.length > "https://".length
    }

    private companion object {
        const val TAG = "AppSettingsRepository"
    }
}
