package com.example.lcb.app.settings

/** 设置页只依赖这个稳定接口，系统 Locale API 与 BuildConfig 不会渗透到 UI 层。 */
interface AppSettingsRepository {
    val supportedLanguages: List<AppLanguage>
    val privacyPolicyUrl: String?
    val termsOfServiceUrl: String?

    fun currentLanguage(): AppLanguage

    /** AppCompat 可能立即重建 Activity，因此调用方必须在主线程执行。 */
    fun applyLanguage(language: AppLanguage): LanguageApplyResult
}

enum class LanguageApplyResult {
    UNCHANGED,
    APPLIED,
    FAILED,
}
