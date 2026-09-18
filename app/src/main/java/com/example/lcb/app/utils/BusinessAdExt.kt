package com.example.lcb.app.utils

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity

/**
 * 广告 SDK 已移除。保留业务层入口作为稳定边界，统一返回“未展示”，
 * 这样 Activity 不需要分散删除广告回调和布局恢复逻辑。
 */
@Suppress("UNUSED_PARAMETER")
fun FragmentActivity.loadNative(
    container: ViewGroup,
    condition: () -> Boolean = { true },
    call: (Boolean) -> Unit = {},
    position: String? = null,
) {
    container.visibility = View.GONE
    call(false)
}

@Suppress("UNUSED_PARAMETER")
fun FragmentActivity.loadInterstitial(
    condition: () -> Boolean = { true },
    call: (Boolean) -> Unit,
    position: String? = null,
) {
    call(false)
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

/** 广告关闭期间不再向 Intent 写入任何广告元数据。 */
@Suppress("UNUSED_PARAMETER")
fun Intent.requestPostNavigationInterstitial(placement: InterstitialAdPlacement): Intent = this

/** 消费历史广告标记并立即返回未展示，不触发延迟、开关或频控。 */
@Suppress("UNUSED_PARAMETER")
fun FragmentActivity.loadRequestedPostNavigationInterstitial(
    savedInstanceState: Bundle?,
    condition: () -> Boolean = { true },
    call: (Boolean) -> Unit = {},
    position: String? = null,
) {
    call(false)
}
