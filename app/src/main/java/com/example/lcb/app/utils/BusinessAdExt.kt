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
import net.corekit.core.controller.AdSlotSwitchController

fun FragmentActivity.loadNative(
    container: ViewGroup,
    styleType: NativeAdStyleType = NativeAdStyleType.STANDARD,
    condition: () -> Boolean = { true },
    call: (Boolean) -> Unit = {},
    position: String? = null,
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
                styleType = styleType,
                position = position,
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
    call: (Boolean) -> Unit,
    position: String? = null,
) {
    lifecycleScope.launch {
        try {
            if (!condition.invoke()) {
                call.invoke(false)
                return@launch
            }
            LauncherSdkGateway.beforeShowAd(this@loadInterstitial)
            when (AdShowExt.showInterstitialAd(this@loadInterstitial, position = position)) {
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

/**
 * 插屏来源只携带对应的业务开关 key；广告 SDK 的 position 仍由目标 Activity 单独传入。
 */
enum class InterstitialAdPlacement(internal val switchKey: String) {
    HOME_RECOMMENDED_MORE_ENTRY(BusinessAdSwitchKey.HOME_RECOMMENDED_MORE_ENTRY_INTERSTITIAL),
    HOME_LOCAL_PLAYLISTS_ENTRY(BusinessAdSwitchKey.HOME_LOCAL_PLAYLISTS_ENTRY_INTERSTITIAL),
    HOME_FAVORITES_ENTRY(BusinessAdSwitchKey.HOME_FAVORITES_ENTRY_INTERSTITIAL),
    HOME_PLAYLIST_ENTRY(BusinessAdSwitchKey.HOME_PLAYLIST_ENTRY_INTERSTITIAL),
    ARTIST_LIST_NAME_ENTRY(BusinessAdSwitchKey.ARTIST_LIST_NAME_ENTRY_INTERSTITIAL),
    PLAYER_ARTIST_NAME_ENTRY(BusinessAdSwitchKey.PLAYER_ARTIST_NAME_ENTRY_INTERSTITIAL),
    SONG_INFO_ARTIST_ENTRY(BusinessAdSwitchKey.SONG_INFO_ARTIST_ENTRY_INTERSTITIAL),
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
    position: String? = null,
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
        // 业务开关和广告 position 相互独立；关闭时不请求广告，也不消耗全局频控。
        if (!AdSlotSwitchController.isEnabled(placement.switchKey)) {
            call(false)
            return@launch
        }
        loadInterstitial(
            condition = { condition() && BusinessAdPolicy.tryAcquireInterstitial(placement) },
            call = call,
            position = position,
        )
    }
}

private const val EXTRA_INTERSTITIAL_PLACEMENT = "business_ad.interstitial_placement"
private const val POST_NAVIGATION_AD_DELAY_MS = 350L
