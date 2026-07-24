package com.example.lcb.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import com.example.lcb.app.R
import kotlin.math.min

/** 各歌曲列表共用的轻量封面控件，集中管理圆角裁剪和播放中动画。 */
open class TrackArtworkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    val image = AppCompatImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = null
    }
    private val playingShade = View(context).apply {
        background = ColorDrawable(Color.argb(92, 10, 8, 14))
        isVisible = false
    }
    private val playingIndicator = PlayingEqualizerView(context).apply {
        contentDescription = context.getString(R.string.home_playing)
        isVisible = false
        setPlaying(false)
    }

    init {
        clipToOutline = true
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(playingShade, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(playingIndicator, LayoutParams(0, 0, Gravity.CENTER))
    }

    fun setPlaying(isPlaying: Boolean) {
        playingShade.isVisible = isPlaying
        playingIndicator.isVisible = isPlaying
        playingIndicator.setPlaying(isPlaying)
        contentDescription = if (isPlaying) context.getString(R.string.home_playing) else null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        val size = (min(w, h) * INDICATOR_SIZE_RATIO).toInt()
            .coerceIn((MIN_INDICATOR_DP * density).toInt(), (MAX_INDICATOR_DP * density).toInt())
        playingIndicator.layoutParams = (playingIndicator.layoutParams as LayoutParams).apply {
            width = size
            height = size
            gravity = Gravity.CENTER
        }
    }

    private companion object {
        const val INDICATOR_SIZE_RATIO = 0.26f
        const val MIN_INDICATOR_DP = 18
        const val MAX_INDICATOR_DP = 28
    }
}
