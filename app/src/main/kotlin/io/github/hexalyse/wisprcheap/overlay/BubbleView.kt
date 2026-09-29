package io.github.hexalyse.wisprcheap.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Phase 0 bubble: a round button with a microphone glyph and a "hold to talk" ring. */
class BubbleView(context: Context) : View(context) {
    enum class Mode { IDLE, RECORDING, BUSY }

    var mode = Mode.IDLE
        set(value) {
            field = value
            invalidate()
        }

    private var ring = 0f
    private var ringAnimator: ValueAnimator? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    init {
        contentDescription = "Dictate"
    }

    /** Fills a ring around the bubble over [durationMs]: the time to hold before recording starts. */
    fun startHoldRing(durationMs: Long) {
        ringAnimator?.cancel()
        ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener {
                ring = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun cancelHoldRing() {
        ringAnimator?.cancel()
        ringAnimator = null
        ring = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.82f
        fill.color = when (mode) {
            Mode.IDLE -> 0xE6344054.toInt()
            Mode.RECORDING -> 0xFFE5484D.toInt()
            Mode.BUSY -> 0xFFF59E0B.toInt()
        }
        canvas.drawCircle(cx, cy, r, fill)

        // Microphone: capsule, cradle arc, stem and base.
        val u = r / 10f
        rect.set(cx - 2.2f * u, cy - 5f * u, cx + 2.2f * u, cy + 1.2f * u)
        canvas.drawRoundRect(rect, 2.2f * u, 2.2f * u, glyph)
        stroke.strokeWidth = 1.1f * u
        rect.set(cx - 3.8f * u, cy - 2.6f * u, cx + 3.8f * u, cy + 3.4f * u)
        canvas.drawArc(rect, 0f, 180f, false, stroke)
        canvas.drawLine(cx, cy + 3.4f * u, cx, cy + 5.4f * u, stroke)
        canvas.drawLine(cx - 2f * u, cy + 5.4f * u, cx + 2f * u, cy + 5.4f * u, stroke)

        if (ring > 0f) {
            ringPaint.strokeWidth = r * 0.09f
            val rr = r * 1.1f
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            canvas.drawArc(rect, -90f, 360f * ring, false, ringPaint)
        }
    }
}
