package com.example.lcb.app

import com.example.lcb.music.AudiusCredential
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Remote Config 的局部凭据更新；null 表示不覆盖当前平台配置。 */
internal data class MusicCredentialPatch(
    val jamendoClientIds: List<String>? = null,
    val audiusCredentials: List<AudiusCredential>? = null,
) {
    val isEmpty: Boolean get() = jamendoClientIds == null && audiusCredentials == null
}

/**
 * 解析单一 `music_sdk_config` JSON，与远端配置 SDK 和 Activity 生命周期完全解耦。
 *
 * 推荐格式：
 * {
 *   "jamendoClientIds": ["id1", "id2"],
 *   "audiusCredentials": [
 *     {"apiKey": "key1", "bearerToken": "token1"}
 *   ]
 * }
 */
internal object MusicRemoteCredentialsParser {
    private const val JAMENDO_CLIENT_IDS = "jamendoClientIds"
    private const val AUDIUS_CREDENTIALS = "audiusCredentials"

    fun parse(raw: String?): MusicCredentialPatch {
        val root = raw?.trim()?.takeIf(String::isNotEmpty)
            ?.let { value -> runCatching { JsonParser.parseString(value) }.getOrNull() }
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return MusicCredentialPatch()

        return MusicCredentialPatch(
            jamendoClientIds = root.parseStringArray(JAMENDO_CLIENT_IDS),
            audiusCredentials = root.parseAudiusCredentials(),
        )
    }

    /** 字段缺失或类型错误时不覆盖；显式空数组用于停用对应平台。 */
    private fun JsonObject.parseStringArray(field: String): List<String>? {
        if (!has(field)) return null
        val array = get(field).takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (array.isEmpty) return emptyList()
        return array.mapNotNull { element ->
            element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }.distinct().takeIf(List<String>::isNotEmpty)
    }

    private fun JsonObject.parseAudiusCredentials(): List<AudiusCredential>? {
        if (!has(AUDIUS_CREDENTIALS)) return null
        val array = get(AUDIUS_CREDENTIALS).takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (array.isEmpty) return emptyList()

        return array.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val apiKey = item.stringOrNull("apiKey") ?: return@mapNotNull null
            val bearerToken = item.stringOrNull("bearerToken")
                ?.removeBearerPrefix()
                ?.takeIf(String::isNotEmpty)
            AudiusCredential(apiKey = apiKey, bearerToken = bearerToken)
        }.distinctBy { it.apiKey to it.bearerToken }
            // 非空数组全部非法时保留当前凭据，避免错误配置导致整个平台不可用。
            .takeIf(List<AudiusCredential>::isNotEmpty)
    }

    private fun JsonObject.stringOrNull(field: String): String? = get(field)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private fun String.removeBearerPrefix(): String =
        if (startsWith("Bearer ", ignoreCase = true)) substringAfter(' ').trim() else this
}
