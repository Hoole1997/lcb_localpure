package com.example.lcb.app.home

import android.content.Context
import android.util.AttributeSet
import com.example.lcb.app.ui.TrackArtworkView

/** 保留首页 XML 的稳定类名，实际能力由跨页面的 [TrackArtworkView] 提供。 */
class HomeTrackArtworkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TrackArtworkView(context, attrs)
