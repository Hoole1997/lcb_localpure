package com.example.lcb.app.ui

import androidx.annotation.StringRes
import com.example.lcb.app.R

/**
 * ViewModel 只发布稳定的加载失败语义，展示层再按当前 Locale 解析文案。
 * SDK 或系统异常的英文 message 不应直接暴露给用户，也不会在切换语言后残留。
 */
enum class AppLoadError(@param:StringRes val messageRes: Int) {
    HOME(R.string.load_error_home),
    RECOMMENDED(R.string.load_error_recommended),
    RECOMMENDED_MORE(R.string.load_error_recommended_more),
    SEARCH(R.string.load_error_search),
    SEARCH_MORE(R.string.load_error_search_more),
    LOCAL_MUSIC(R.string.load_error_local_music),
}
