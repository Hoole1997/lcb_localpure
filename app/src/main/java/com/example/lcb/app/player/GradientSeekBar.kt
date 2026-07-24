package com.example.lcb.app.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max

/**
 * Figma 样式进度条。轨道在 View 内部为 thumb 预留半径，0% 和 100% 时均不会裁剪圆点。
 */
class GradientSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackBounds = RectF()
    private var gradient: LinearGradient? = null
    private var fraction = 0f
    private var tracking = false
    private var downX = 0f
    private var startFraction = 0f
    private var moved = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    var onSeekStarted: (() -> Unit)? = null
    var onSeekChanged: ((Float) -> Unit)? = null
    var onSeekFinished: ((Float) -> Unit)? = null
    var onSeekCancelled: (() -> Unit)? = null

    init {
        isFocusable = true
        isClickable = true
    }

    fun setProgress(value: Float) {
        if (tracking) return
        fraction = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val radius = dp(4f)
        val centerY = h / 2f
        trackBounds.set(radius, centerY - dp(2f), max(radius, w - radius), centerY + dp(2f))
        gradient = LinearGradient(
            trackBounds.left,
            0f,
            trackBounds.right,
            0f,
            intArrayOf(Color.rgb(198, 45, 239), Color.rgb(119, 49, 244)),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val radius = dp(4f)
        val thumbX = trackBounds.left + trackBounds.width() * fraction
        paint.shader = null
        paint.color = Color.argb(102, 255, 255, 255)
        canvas.drawRoundRect(trackBounds, dp(2f), dp(2f), paint)

        if (fraction > 0f) {
            paint.shader = gradient
            canvas.drawRoundRect(
                trackBounds.left,
                trackBounds.top,
                thumbX,
                trackBounds.bottom,
                dp(2f),
                dp(2f),
                paint,
            )
        }
        paint.shader = null
        paint.color = Color.WHITE
        canvas.drawCircle(thumbX, height / 2f, radius, paint)
        paint.color = Color.rgb(198, 45, 239)
        canvas.drawCircle(thumbX, height / 2f, dp(3f), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                tracking = true
                downX = event.x
                startFraction = fraction
                moved = false
                onSeekStarted?.invoke()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && abs(event.x - downX) > touchSlop) moved = true
                if (moved) updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                    val tappedFraction = fractionForX(event.x)
                    // 长按 thumb 但未移动时不发起 seek，避免无意义的网络重新缓冲。
                    if (moved || abs(tappedFraction - startFraction) > MIN_TAP_DELTA) {
                        if (!moved) {
                            fraction = tappedFraction
                            onSeekChanged?.invoke(fraction)
                            invalidate()
                        }
                        onSeekFinished?.invoke(fraction)
                    } else {
                        fraction = startFraction
                        onSeekCancelled?.invoke()
                        invalidate()
                    }
                } else {
                    fraction = startFraction
                    onSeekCancelled?.invoke()
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromTouch(x: Float) {
        fraction = fractionForX(x)
        onSeekChanged?.invoke(fraction)
        invalidate()
    }

    private fun fractionForX(x: Float) =
        ((x - trackBounds.left) / trackBounds.width()).coerceIn(0f, 1f)

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private companion object {
        const val MIN_TAP_DELTA = 0.005f
    }
}
