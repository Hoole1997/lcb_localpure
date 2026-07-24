package com.example.lcb.app

import com.example.lcb.music.AudiusCredential
import com.google.gson.JsonParser

/** Remote Config 的局部凭据更新；null 表示不覆盖当前平台配置。 */
internal data class MusicCredentialPatch(
    val jamendoClientIds: List<String>? = null,
    val audiusCredentials: List<AudiusCredential>? = null,
) {
    val isEmpty: Boolean get() = jamendoClientIds == null && audiusCredentials == null
}

/**
 * 将 Firebase Remote Config 的字符串解析为强类型凭据，与 Firebase 监听生命周期解耦，便于单元测试。
 */
internal object MusicRemoteCredentialsParser {
    fun parse(
        jamendoRaw: String?,
        audiusCredentialsRaw: String?,
        legacyAudiusApiKeysRaw: String?,
        legacyAudiusBearerTokensRaw: String?,
    ): MusicCredentialPatch {
        val jamendo = parseStringList(jamendoRaw)
        val audius = parseAudiusJson(audiusCredentialsRaw)
            ?: parseLegacyAudius(legacyAudiusApiKeysRaw, legacyAudiusBearerTokensRaw)
        return MusicCredentialPatch(jamendoClientIds = jamendo, audiusCredentials = audius)
    }

    /** 推荐格式：[{"apiKey":"...","bearerToken":"..."}]。 */
    private fun parseAudiusJson(raw: String?): List<AudiusCredential>? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val array = runCatching { JsonParser.parseString(value) }.getOrNull()
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: return null
        if (array.isEmpty) return emptyList()

        val credentials = array.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val apiKey = item.get("apiKey")?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
            if (apiKey.isEmpty()) return@mapNotNull null
            val bearer = item.get("bearerToken")
                ?.takeUnless { it.isJsonNull }
                ?.asString
                ?.trim()
                ?.removeBearerPrefix()
                ?.takeIf(String::isNotEmpty)
            AudiusCredential(apiKey = apiKey, bearerToken = bearer)
        }.distinctBy { it.apiKey to it.bearerToken }

        // 非空 JSON 全部非法时不覆盖现有配置，避免误下发导致平台整体不可用。
        return credentials.takeIf(List<AudiusCredential>::isNotEmpty)
    }

    private fun parseLegacyAudius(apiKeysRaw: String?, bearerTokensRaw: String?): List<AudiusCredential>? {
        val apiKeys = parseStringList(apiKeysRaw) ?: return null
        if (apiKeys.isEmpty()) return emptyList()
        val bearerTokens = parseStringList(bearerTokensRaw).orEmpty()
        return apiKeys.mapIndexed { index, apiKey ->
            AudiusCredential(apiKey = apiKey, bearerToken = bearerTokens.getOrNull(index)?.removeBearerPrefix())
        }
    }

    /** Jamendo 及兼容字段同时支持 JSON 数组和英文逗号分隔格式。 */
    private fun parseStringList(raw: String?): List<String>? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (!value.startsWith('[')) {
            return value.split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .takeIf(List<String>::isNotEmpty)
        }
        val array = runCatching { JsonParser.parseString(value) }.getOrNull()
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: return null
        if (array.isEmpty) return emptyList()
        val values = array.mapNotNull { element ->
            element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }.distinct()
        return values.takeIf(List<String>::isNotEmpty)
    }

    private fun String.removeBearerPrefix(): String =
        if (startsWith("Bearer ", ignoreCase = true)) substringAfter(' ').trim() else this
}
