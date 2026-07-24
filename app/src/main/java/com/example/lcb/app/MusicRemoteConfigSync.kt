package com.example.lcb.app

import com.blankj.utilcode.util.LogUtils
import com.google.firebase.remoteconfig.ConfigUpdate
import com.google.firebase.remoteconfig.ConfigUpdateListener
import com.google.firebase.remoteconfig.ConfigUpdateListenerRegistration
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException
import net.corekit.core.utils.ConfigRemoteManager

/**
 * Application 级 Remote Config 同步器。首次启动会 fetch + activate，后续使用 Firebase 实时更新
 * 监听器热替换 SDK 凭据，整个过程不重建 SDK 或 OkHttpClient。
 */
internal object MusicRemoteConfigSync {
    private const val JAMENDO_CLIENT_IDS = "music_jamendo_client_ids"
    private const val AUDIUS_CREDENTIALS = "music_audius_credentials"
    private const val AUDIUS_API_KEYS = "music_audius_api_keys"
    private const val AUDIUS_BEARER_TOKENS = "music_audius_bearer_tokens"

    private val supportedKeys = setOf(
        JAMENDO_CLIENT_IDS,
        AUDIUS_CREDENTIALS,
        AUDIUS_API_KEYS,
        AUDIUS_BEARER_TOKENS,
    )

    @Volatile
    private var started = false
    private var registration: ConfigUpdateListenerRegistration? = null

    @Synchronized
    fun start() {
        if (started) return
        val remoteConfig = runCatching {
            ConfigRemoteManager.getFirebaseRemoteConfig() ?: FirebaseRemoteConfig.getInstance()
        }.getOrElse { error ->
            LogUtils.w("Music Remote Config unavailable: ${error.javaClass.simpleName}")
            return
        }
        started = true

        // 先同步已激活/最新配置，不让实时监听是唯一的更新入口。
        remoteConfig.fetchAndActivate().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                applyActivatedConfig(remoteConfig, "startup")
            } else {
                // 离线或被限流时仍尝试使用上次已激活的本地缓存。
                applyActivatedConfig(remoteConfig, "cache")
                LogUtils.w("Music Remote Config fetch failed: ${task.exception?.javaClass?.simpleName.orEmpty()}")
            }
        }

        registration = remoteConfig.addOnConfigUpdateListener(
            object : ConfigUpdateListener {
                override fun onUpdate(configUpdate: ConfigUpdate) {
                    if (configUpdate.updatedKeys.none(supportedKeys::contains)) return
                    remoteConfig.activate().addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            applyActivatedConfig(remoteConfig, "realtime")
                        } else {
                            LogUtils.w("Music Remote Config activation failed")
                        }
                    }
                }

                override fun onError(error: FirebaseRemoteConfigException) {
                    // 只记录错误类型，不打印可能携带远程内容的完整异常。
                    LogUtils.w("Music Remote Config listener error: ${error.code}")
                }
            },
        )
    }

    private fun applyActivatedConfig(remoteConfig: FirebaseRemoteConfig, source: String) {
        val activeKeys = remoteConfig.all.keys
        val patch = MusicRemoteCredentialsParser.parse(
            jamendoRaw = remoteConfig.valueIfPresent(activeKeys, JAMENDO_CLIENT_IDS),
            audiusCredentialsRaw = remoteConfig.valueIfPresent(activeKeys, AUDIUS_CREDENTIALS),
            legacyAudiusApiKeysRaw = remoteConfig.valueIfPresent(activeKeys, AUDIUS_API_KEYS),
            legacyAudiusBearerTokensRaw = remoteConfig.valueIfPresent(activeKeys, AUDIUS_BEARER_TOKENS),
        )
        if (patch.isEmpty) return

        MusicDependencies.updateCredentials(
            jamendoClientIds = patch.jamendoClientIds,
            audiusCredentials = patch.audiusCredentials,
        )
        val snapshot = MusicDependencies.credentialsSnapshot()
        LogUtils.i(
            "Music credentials updated from $source: " +
                "jamendo=${snapshot.jamendoClientIds.size}, audius=${snapshot.audiusCredentials.size}",
        )
    }

    private fun FirebaseRemoteConfig.valueIfPresent(activeKeys: Set<String>, key: String): String? =
        key.takeIf(activeKeys::contains)?.let(::getString)
}
