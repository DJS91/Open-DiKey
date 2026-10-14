package com.sphy.airconcontroller.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.sphy.airconcontroller.R
import kotlin.math.max
import kotlin.math.min

/** Rounded track with an animated solid-color fill for 0–100% values. */
class PercentBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_stroke)
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_orange)
        style = Paint.Style.FILL
    }
    private val rect = RectF()
    private var displayPct = 0f
    private var targetPct = 0f

    fun setFillColor(@ColorInt color: Int) {
        fillPaint.color = color
        invalidate()
    }

    fun setPercent(value: Double?, animate: Boolean = true) {
        val next = value?.toFloat()?.coerceIn(0f, 100f) ?: 0f
        targetPct = next
        if (!animate) {
            displayPct = next
            invalidate()
            return
        }
        postOnAnimation(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val delta = targetPct - displayPct
            if (kotlin.math.abs(delta) < 0.3f) {
                displayPct = targetPct
                invalidate()
                return
            }
            displayPct += delta * 0.25f
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val radius = h / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        val fillW = max(0f, min(w, w * displayPct / 100f))
        if (fillW <= 0f) return
        rect.set(0f, 0f, fillW, h)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }
}
