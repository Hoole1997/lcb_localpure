package com.example.lcb.app

import com.example.lcb.music.AudiusCredential
import com.example.lcb.music.MusicSdk
import com.example.lcb.music.MusicSdkConfig
import com.example.lcb.music.MusicSdkCredentials
import com.example.lcb.music.MusicSdkFactory

/**
 * 应用级音乐依赖入口。
 *
 * Application 会主动调用 [initialize]，使 OkHttp 连接池、Provider 和凭据健康状态在所有
 * Activity 之间复用。[sdk] 保留了延迟初始化兜底，避免预览/测试进程绕过 Application 时崩溃。
 */
internal object MusicDependencies {
    private val lock = Any()

    @Volatile
    private var sdkInstance: MusicSdk? = null

    @Volatile
    private var activeCredentials: MusicSdkCredentials = MusicSdkCredentials()

    val sdk: MusicSdk
        get() = sdkInstance ?: run {
            initialize()
            checkNotNull(sdkInstance) { "MusicSdk initialization failed" }
        }

    /** 使用 Gradle 写入 BuildConfig 的内置兜底凭据初始化，Remote Config 可在运行时热更新。 */
    fun initialize() {
        synchronized(lock) {
            if (sdkInstance != null) return
            val initialCredentials = localCredentials()
            sdkInstance = MusicSdkFactory.create(
                MusicSdkConfig(
                    jamendoClientIds = initialCredentials.jamendoClientIds,
                    audiusCredentials = initialCredentials.audiusCredentials,
                ),
            )
            activeCredentials = initialCredentials
        }
    }

    /**
     * 合并 Remote Config 的局部更新。null 表示该平台未下发，保留当前值；空列表表示
     * 明确停用该平台。
     */
    fun updateCredentials(
        jamendoClientIds: List<String>? = null,
        audiusCredentials: List<AudiusCredential>? = null,
    ) {
        synchronized(lock) {
            if (sdkInstance == null) initialize()
            val merged = MusicSdkCredentials(
                jamendoClientIds = jamendoClientIds ?: activeCredentials.jamendoClientIds,
                audiusCredentials = audiusCredentials ?: activeCredentials.audiusCredentials,
            )
            checkNotNull(sdkInstance).updateCredentials(merged)
            activeCredentials = merged
        }
    }

    fun credentialsSnapshot(): MusicSdkCredentials = activeCredentials

    private fun localCredentials(): MusicSdkCredentials {
        val apiKeys = BuildConfig.MUSIC_AUDIUS_API_KEYS.toCredentialList()
        val bearerTokens = BuildConfig.MUSIC_AUDIUS_BEARER_TOKENS.toCredentialList()
        return MusicSdkCredentials(
            jamendoClientIds = BuildConfig.MUSIC_JAMENDO_CLIENT_IDS.toCredentialList(),
            audiusCredentials = apiKeys.mapIndexed { index, apiKey ->
                AudiusCredential(apiKey = apiKey, bearerToken = bearerTokens.getOrNull(index))
            },
        )
    }
}

/** Gradle 内置值和可选 CI 覆盖值均使用英文逗号配置多个凭据。 */
private fun String.toCredentialList(): List<String> =
    split(',').map(String::trim).filter(String::isNotEmpty).distinct()
