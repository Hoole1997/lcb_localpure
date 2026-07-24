package com.example.lcb.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 通用的轻量播放波形。View 离屏、隐藏或系统关闭动画时都不会持续刷新。
 */
class PlayingEqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF78C8")
        strokeCap = Paint.Cap.ROUND
    }
    private var phase = 0f
    private var playing = true
    private val animator = ValueAnimator.ofFloat(0f, (PI * 2).toFloat()).apply {
        duration = ANIMATION_DURATION_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val barWidth = width / 9f
        val gap = barWidth
        val contentWidth = BAR_COUNT * barWidth + (BAR_COUNT - 1) * gap
        val startX = (width - contentWidth) / 2f
        paint.strokeWidth = barWidth
        repeat(BAR_COUNT) { index ->
            val wave = abs(sin(phase + index * PHASE_OFFSET)).toFloat()
            val barHeight = height * (MIN_HEIGHT_RATIO + wave * HEIGHT_RANGE_RATIO)
            val x = startX + barWidth / 2f + index * (barWidth + gap)
            canvas.drawLine(x, (height - barHeight) / 2f, x, (height + barHeight) / 2f, paint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimationState()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimationState()
    }

    fun setPlaying(isPlaying: Boolean) {
        if (playing == isPlaying) return
        playing = isPlaying
        if (!playing) phase = PAUSED_PHASE
        updateAnimationState()
        invalidate()
    }

    private fun updateAnimationState() {
        val shouldAnimate = playing && isAttachedToWindow && visibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()
        when {
            shouldAnimate && !animator.isStarted -> animator.start()
            !shouldAnimate && animator.isStarted -> animator.cancel()
        }
    }

    private companion object {
        const val BAR_COUNT = 4
        const val ANIMATION_DURATION_MS = 820L
        const val PHASE_OFFSET = 1.35f
        const val MIN_HEIGHT_RATIO = 0.24f
        const val HEIGHT_RANGE_RATIO = 0.68f
        const val PAUSED_PHASE = 0.7f
    }
}
