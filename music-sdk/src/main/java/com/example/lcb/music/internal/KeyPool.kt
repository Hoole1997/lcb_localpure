package com.example.lcb.music.internal

import java.util.concurrent.atomic.AtomicInteger

/**
 * 线程安全的通用凭据池。无效凭据永久摘除；单 Key 限流进入冷却，后续请求自动恢复。
 * 网络与平台服务故障不改变 Key 状态，确保连接恢复后可以立即重试。
 *
 * [replace] 使 SDK 能在运行时接收 Remote Config 下发的新凭据。未变化凭据保留当前
 * 禁用/冷却状态，避免配置监听器重复回调时将已失效凭据误复活。
 */
internal class KeyPool<T>(
    credentials: List<T>,
    private val defaultCooldownMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val normalize: (T) -> T? = { it },
    private val identity: (T) -> Any? = { it },
) {
    private data class State<T>(
        var credential: T,
        var cooldownUntil: Long = 0,
        var disabled: Boolean = false,
    )

    private var states: List<State<T>> = emptyList()
    private val cursor = AtomicInteger(0)

    init {
        replace(credentials)
    }

    @Synchronized
    fun candidates(): List<T> {
        val time = now()
        val available = states.filter { !it.disabled && it.cooldownUntil <= time }
        if (available.isEmpty()) return emptyList()
        val start = Math.floorMod(cursor.getAndIncrement(), available.size)
        return List(available.size) { available[(start + it) % available.size].credential }
    }

    @Synchronized
    fun markFailure(credential: T, failure: ProviderRequestException) {
        val credentialIdentity = identity(credential)
        val state = states.firstOrNull { identity(it.credential) == credentialIdentity } ?: return
        when {
            failure.invalidCredential -> state.disabled = true
            failure.rateLimited -> state.cooldownUntil = now() + (failure.retryAfterMs ?: defaultCooldownMs)
        }
    }

    /**
     * 原子替换凭据集合。移除的凭据立即不再分配给新请求；已在执行的请求可正常完成。
     */
    @Synchronized
    fun replace(credentials: List<T>) {
        val normalized = credentials.mapNotNull(normalize).distinctBy(identity)
        val previous = states.associateBy { identity(it.credential) }
        states = normalized.map { credential ->
            previous[identity(credential)]?.also { it.credential = credential } ?: State(credential)
        }
        cursor.set(0)
    }

    @Synchronized
    fun snapshot(): Triple<Int, Int, Int> {
        val time = now()
        val disabled = states.count { it.disabled }
        val cooling = states.count { !it.disabled && it.cooldownUntil > time }
        return Triple(states.size - disabled - cooling, cooling, disabled)
    }

    companion object {
        fun clean(keys: List<String>): List<String> = keys.map(String::trim).filter(String::isNotEmpty).distinct()
    }
}
