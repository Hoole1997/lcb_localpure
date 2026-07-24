package com.example.lcb.app.utils

import android.os.SystemClock

/** 可替换时钟让频控逻辑无需 Android 生命周期即可单元测试。 */
internal fun interface AdMonotonicClock {
    fun nowMillis(): Long
}

/**
 * 全局插屏频控。请求失败也计入冷却，防止弱网或广告源异常时连续触发请求，
 * 同时保证不同业务页共享同一展示节奏。
 */
internal class InterstitialFrequencyGate(
    private val minimumIntervalMs: Long,
    private val clock: AdMonotonicClock,
    private val initialDelayMs: Long = 0L,
) {
    private val sessionStartedAt = clock.nowMillis()
    private var lastRequestAt: Long? = null

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock.nowMillis()
        val previous = lastRequestAt
        if (previous == null && now >= sessionStartedAt && now - sessionStartedAt < initialDelayMs) return false
        if (previous != null && now >= previous && now - previous < minimumIntervalMs) return false
        lastRequestAt = now
        return true
    }
}

internal object BusinessAdPolicy {
    private const val INTERSTITIAL_INITIAL_DELAY_MS = 30_000L
    private const val INTERSTITIAL_MIN_INTERVAL_MS = 90_000L
    private val interstitialGate = InterstitialFrequencyGate(
        minimumIntervalMs = INTERSTITIAL_MIN_INTERVAL_MS,
        clock = AdMonotonicClock(SystemClock::elapsedRealtime),
        initialDelayMs = INTERSTITIAL_INITIAL_DELAY_MS,
    )

    /** Application 启动时初始化频控计时，避免开屏广告后立刻衔接插屏。 */
    fun initializeSession() = Unit

    fun tryAcquireInterstitial(placement: InterstitialAdPlacement): Boolean {
        // placement 保留为明确的业务输入，后续可按播放/内容页分别配置远端策略。
        return when (placement) {
            InterstitialAdPlacement.CONTENT_PAGE,
            InterstitialAdPlacement.PLAYBACK_START,
            -> interstitialGate.tryAcquire()
        }
    }
}
