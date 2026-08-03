package com.example.lcb.app.settings

import androidx.annotation.StringRes
import com.example.lcb.app.R
import java.util.Locale

/**
 * 应用 UI 支持的语言集合。
 *
 * 语言 tag 与 TranslateGo 的 localeConfig 保持一致；名称使用各语言的原生写法，
 * 即使用户误切到不熟悉的语言，也能在选择器中识别并切回。
 */
enum class AppLanguage(
    val languageTag: String,
    @param:StringRes val displayNameRes: Int,
) {
    /** 空语言列表是 AppCompat 官方定义的“跟随系统”模式，并作为首次安装默认项。 */
    SYSTEM_DEFAULT("", R.string.settings_language_system_default),
    ENGLISH("en", R.string.settings_language_english),
    CHINESE_SIMPLIFIED("zh-CN", R.string.settings_language_chinese_simplified),
    JAPANESE("ja", R.string.settings_language_japanese),
    KOREAN("ko", R.string.settings_language_korean),
    INDONESIAN("id", R.string.settings_language_indonesian),
    MALAY("ms", R.string.settings_language_malay),
    GERMAN("de", R.string.settings_language_german),
    FRENCH("fr", R.string.settings_language_french),
    SPANISH("es", R.string.settings_language_spanish),
    VIETNAMESE("vi", R.string.settings_language_vietnamese),
    ;

    /** 埋点使用稳定、无本地化的语言标识；空 tag 明确表示跟随系统。 */
    val analyticsValue: String
        get() = languageTag.ifBlank { SYSTEM_ANALYTICS_VALUE }.lowercase(Locale.ROOT)

    companion object {
        private const val SYSTEM_ANALYTICS_VALUE = "system"

        fun fromTag(tag: String?): AppLanguage? {
            if (tag.isNullOrBlank()) return null
            entries.firstOrNull { it.languageTag.equals(tag, ignoreCase = true) }?.let { return it }
            val locale = Locale.forLanguageTag(tag.replace('_', '-'))
            if (locale.language.equals("zh", ignoreCase = true) &&
                (locale.script.equals("Hans", ignoreCase = true) || ('-' !in tag))
            ) {
                return CHINESE_SIMPLIFIED
            }
            // 仅无地区限定的语言（如 en）可匹配 en-US；不能把 zh-TW 错当成 zh-CN。
            entries.firstOrNull { language ->
                '-' !in language.languageTag &&
                    language.languageTag.equals(tag.substringBefore('-'), ignoreCase = true)
            }?.let { return it }
            return null
        }
    }
}
