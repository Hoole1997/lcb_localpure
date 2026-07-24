package com.example.lcb.music

/**
 * Audius 请求凭据。API Key 与 Bearer Token 必须成对轮换，避免切换 Key 后继续携带
 * 上一组 Token。Bearer 可为空，以兼容只需要公开 API Key 的读接口。
 */
data class AudiusCredential(
    val apiKey: String,
    val bearerToken: String? = null,
) {
    // 凭据对象被误打印时不暴露原始 Key/Token。
    override fun toString(): String = "AudiusCredential(apiKey=***, bearerToken=${if (bearerToken.isNullOrBlank()) "null" else "***"})"
}

/** SDK 运行时可热替换的全量平台凭据。 */
data class MusicSdkCredentials(
    val jamendoClientIds: List<String> = emptyList(),
    val audiusCredentials: List<AudiusCredential> = emptyList(),
)

internal fun AudiusCredential.normalizedOrNull(): AudiusCredential? {
    val normalizedApiKey = apiKey.trim().takeIf(String::isNotEmpty) ?: return null
    val normalizedBearer = bearerToken
        ?.trim()
        ?.removeBearerPrefix()
        ?.takeIf(String::isNotEmpty)
    return AudiusCredential(apiKey = normalizedApiKey, bearerToken = normalizedBearer)
}

internal fun AudiusCredential.identity(): String = "$apiKey\u0000${bearerToken.orEmpty()}"

private fun String.removeBearerPrefix(): String =
    if (startsWith("Bearer ", ignoreCase = true)) substringAfter(' ').trim() else this
