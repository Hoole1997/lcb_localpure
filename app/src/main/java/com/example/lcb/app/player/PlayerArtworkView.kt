package com.example.lcb.app.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.animation.ValueAnimator
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/** 唱片外圈使用矢量绘制，封面仍由 Glide 解码，不创建额外大尺寸 Bitmap。 */
class PlayerArtworkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val ringView = RecordRingView(context)
    val artwork = AppCompatImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        background = context.getDrawable(com.example.lcb.app.R.drawable.bg_player_artwork)
    }

    init {
        addView(ringView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(artwork, LayoutParams(0, 0, Gravity.CENTER))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val artworkSize = (min(w, h) * 0.724f).toInt()
        artwork.layoutParams = (artwork.layoutParams as LayoutParams).apply {
            width = artworkSize
            height = artworkSize
            gravity = Gravity.CENTER
        }
    }

    fun updatePlayback(positionMs: Long, durationMs: Long, isPlaying: Boolean) =
        ringView.updatePlayback(positionMs, durationMs, isPlaying)

    fun stopProgressAnimation() = ringView.stopAnimation()

    private class RecordRingView(context: Context) : android.view.View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arcBounds = RectF()
        private val ticks = Path()
        private val motionEnabled =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
        private var basePositionMs = 0L
        private var durationMs = 0L
        private var baseRealtimeMs = 0L
        private var playing = false

        fun updatePlayback(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
            basePositionMs = positionMs.coerceAtLeast(0L)
            this.durationMs = durationMs.coerceAtLeast(0L)
            baseRealtimeMs = SystemClock.elapsedRealtime()
            playing = isPlaying
            postInvalidateOnAnimation()
        }

        fun stopAnimation() {
            playing = false
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val size = min(w, h).toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val outerRadius = size / 2f - dp(6f)
            val innerRadius = outerRadius - dp(14f)
            ticks.reset()
            repeat(100) { index ->
                val angle = Math.toRadians((index * 3.6 - 90.0))
                val cos = kotlin.math.cos(angle).toFloat()
                val sin = kotlin.math.sin(angle).toFloat()
                ticks.moveTo(cx + cos * outerRadius, cy + sin * outerRadius)
                ticks.lineTo(cx + cos * innerRadius, cy + sin * innerRadius)
            }
        }

        override fun onDraw(canvas: Canvas) {
            val size = min(width, height).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            paint.color = Color.argb(95, 255, 255, 255)
            paint.strokeWidth = dp(6f)
            paint.strokeCap = Paint.Cap.BUTT
            paint.style = Paint.Style.STROKE
            canvas.drawPath(ticks, paint)

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(48, 48, 48)
            canvas.drawCircle(cx, cy, size * 0.425f, paint)

            val arcRadius = size * 0.407f
            arcBounds.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(4f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.argb(75, 255, 255, 255)
            canvas.drawArc(arcBounds, -90f, 360f, false, paint)
            paint.color = Color.WHITE
            val progress = currentProgress()
            canvas.drawArc(arcBounds, -90f, progress * 360f, false, paint)

            val angle = Math.toRadians((-90f + progress * 360f).toDouble())
            paint.style = Paint.Style.FILL
            canvas.drawCircle(
                cx + kotlin.math.cos(angle).toFloat() * arcRadius,
                cy + kotlin.math.sin(angle).toFloat() * arcRadius,
                dp(5f),
                paint,
            )
            if (playing && motionEnabled && isAttachedToWindow) postInvalidateOnAnimation()
        }

        private fun currentProgress(): Float {
            if (durationMs <= 0L) return 0f
            val elapsed = if (playing) SystemClock.elapsedRealtime() - baseRealtimeMs else 0L
            return ((basePositionMs + elapsed).toFloat() / durationMs).coerceIn(0f, 1f)
        }

        private fun dp(value: Float) = value * resources.displayMetrics.density
    }
}
