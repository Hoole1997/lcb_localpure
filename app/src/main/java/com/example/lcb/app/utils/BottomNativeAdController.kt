package com.example.lcb.app.utils

import android.view.View
import android.view.ViewGroup
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentActivity

/**
 * 统一协调底部原生广告、滚动内容与 Mini Player，避免每个页面重复计算遮挡距离。
 * 广告失败时所有间距立即恢复；页面进入多选等专注状态时也可以临时隐藏广告。
 */
class BottomNativeAdController(
    private val activity: FragmentActivity,
    private val adContainer: ViewGroup,
    private val scrollingContent: View,
    private val baseContentBottomPaddingDp: Int,
    private val miniPlayerHost: View? = null,
    private val miniPlayerHeightDp: Int = DEFAULT_MINI_PLAYER_HEIGHT_DP,
    private val miniPlayerBottomMarginDp: Int = DEFAULT_MINI_PLAYER_MARGIN_DP,
    private val adHeightDp: Int = DEFAULT_AD_HEIGHT_DP,
) {
    private var requested = false
    private var loaded = false
    private var suppressed = false
    private var miniPlayerVisible = false

    /** 同一个 Activity 实例最多请求一次，避免 StateFlow 重复渲染触发多次广告请求。 */
    fun loadOnce(condition: () -> Boolean = { true }, position: String? = null) {
        if (requested) return
        requested = true
        activity.loadNative(adContainer, condition = condition, position = position, call = { success ->
            loaded = success
            renderInsets()
        })
    }

    fun setMiniPlayerVisible(visible: Boolean) {
        if (miniPlayerVisible == visible) return
        miniPlayerVisible = visible
        renderInsets()
    }

    fun setSuppressed(value: Boolean) {
        if (suppressed == value) return
        suppressed = value
        renderInsets()
    }

    private fun renderInsets() {
        val showAd = loaded && !suppressed
        adContainer.visibility = if (showAd) View.VISIBLE else View.GONE
        val adHeight = if (showAd) adHeightDp else 0

        miniPlayerHost?.let { miniPlayer ->
            (miniPlayer.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.bottomMargin = activity.dp(miniPlayerBottomMarginDp + adHeight)
                miniPlayer.layoutParams = params
            }
        }

        val miniHeight = if (miniPlayerHost != null && miniPlayerVisible) {
            miniPlayerHeightDp + miniPlayerBottomMarginDp
        } else {
            0
        }
        scrollingContent.updatePadding(
            bottom = activity.dp(baseContentBottomPaddingDp + adHeight + miniHeight),
        )
    }

    private fun FragmentActivity.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val DEFAULT_AD_HEIGHT_DP = 60
        const val DEFAULT_MINI_PLAYER_HEIGHT_DP = 60
        const val DEFAULT_MINI_PLAYER_MARGIN_DP = 14
    }
}
