package com.example.lcb.app.utils

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.android.common.bill.ads.AdResult
import com.android.common.bill.ads.ext.AdShowExt
import com.android.common.bill.ui.NativeAdStyleType
import com.example.lcb.app.LauncherSdkGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun FragmentActivity.loadNative(
    container: ViewGroup,
    styleType: NativeAdStyleType = NativeAdStyleType.STANDARD,
    condition: () -> Boolean = { true },
    call: (Boolean) -> Unit = {}
) {
    lifecycleScope.launch {
        try {
            if (!condition.invoke()) {
                container.visibility = View.GONE
                call.invoke(false)
                return@launch
            }

            val success = AdShowExt.showNativeAdInContainer(
                context = container.context,
                container = container,
                styleType = styleType
            )

            // 广告 SDK 返回时 Activity 可能已经退出；此时不再操作旧页面 View。
            if (lifecycle.currentState == Lifecycle.State.DESTROYED) return@launch

            if (success) {
                container.visibility = View.VISIBLE
                call.invoke(true)
            } else {
                container.visibility = View.GONE
                call.invoke(false)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            if (lifecycle.currentState == Lifecycle.State.DESTROYED) return@launch
            container.visibility = View.GONE
            call.invoke(false)
        }
    }
}

fun FragmentActivity.loadInterstitial(
    condition: () -> Boolean = { true },
    call: (Boolean) -> Unit
) {
    lifecycleScope.launch {
        try {
            if (!condition.invoke()) {
                call.invoke(false)
                return@launch
            }
            LauncherSdkGateway.beforeShowAd(this@loadInterstitial)
            when (AdShowExt.showInterstitialAd(this@loadInterstitial)) {
                is AdResult.Success -> call.invoke(true)
                is AdResult.Failure -> call.invoke(false)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            if (lifecycle.currentState == Lifecycle.State.DESTROYED) return@launch
            call.invoke(false)
        }
    }
}

/** 插屏展示场景用于后续单独配置频控，不让业务页面感知广告 SDK。 */
enum class InterstitialAdPlacement {
    CONTENT_PAGE,
    PLAYBACK_START,
}

/** 在创建目标页 Intent 时标记，目标页会在完成跳转和首帧展示后再请求插屏。 */
fun Intent.requestPostNavigationInterstitial(placement: InterstitialAdPlacement): Intent = apply {
    putExtra(EXTRA_INTERSTITIAL_PLACEMENT, placement.name)
}

/**
 * 只消费一次 Intent 中的广告请求。旋转、语言切换和进程恢复不会重复弹出插屏，
 * 同时要求 Activity 仍处于 RESUMED，避免从已被覆盖的页面展示广告。
 */
fun FragmentActivity.loadRequestedPostNavigationInterstitial(
    savedInstanceState: Bundle?,
    condition: () -> Boolean = { true },
    call: (Boolean) -> Unit = {},
) {
    if (savedInstanceState != null) return
    val placement = intent.getStringExtra(EXTRA_INTERSTITIAL_PLACEMENT)
        ?.let { runCatching { InterstitialAdPlacement.valueOf(it) }.getOrNull() }
        ?: return
    intent.removeExtra(EXTRA_INTERSTITIAL_PLACEMENT)

    lifecycleScope.launch {
        // 让目标页先完成进入动画与首帧绘制，广告关闭后用户能直接继续当前任务。
        delay(POST_NAVIGATION_AD_DELAY_MS)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
        loadInterstitial(
            condition = { condition() && BusinessAdPolicy.tryAcquireInterstitial(placement) },
            call = call,
        )
    }
}

private const val EXTRA_INTERSTITIAL_PLACEMENT = "business_ad.interstitial_placement"
private const val POST_NAVIGATION_AD_DELAY_MS = 350L
