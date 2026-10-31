package com.sphy.airconcontroller.fridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.sphy.airconcontroller.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Line graph of both compartments (solid) and their targets (dashed) over a time range. */
class FridgeGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val leftColor = ContextCompat.getColor(context, R.color.classic_accent)
    private val rightColor = ContextCompat.getColor(context, R.color.gauge_charge)
    private val textColor = ContextCompat.getColor(context, R.color.text_muted)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dashed = Paint(line).apply {
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 5 * density), 0f)
    }
    private val grid = Paint().apply {
        color = textColor
        alpha = 50
        strokeWidth = 1f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 12 * density
    }
    private val path = Path()

    private var samples: List<FridgeHistory.Sample> = emptyList()
    private var rangeMs = FridgeHistory.HOUR_MS
    private var end = System.currentTimeMillis()
    private var celsius = true
    var emptyText: String = ""

    fun setData(samples: List<FridgeHistory.Sample>, rangeMs: Long, now: Long, celsius: Boolean) {
        this.samples = samples
        this.rangeMs = rangeMs
        this.end = now
        this.celsius = celsius
        invalidate()
    }

    private fun value(raw: Int, sampleCelsius: Boolean): Float =
        if (celsius) FridgeHistory.toCelsius(raw, sampleCelsius) else FridgeHistory.toFahrenheit(raw, sampleCelsius)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padL = 40 * density
        val padR = 10 * density
        val padT = 24 * density
        val padB = 22 * density
        val w = width - padL - padR
        val h = height - padT - padB
        if (w <= 0 || h <= 0) return
        val start = end - rangeMs

        if (samples.isEmpty()) {
            label.textAlign = Paint.Align.CENTER
            canvas.drawText(emptyText, width / 2f, height / 2f, label)
            label.textAlign = Paint.Align.LEFT
            return
        }

        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (s in samples) {
            for (v in listOf(s.left, s.right, s.leftTarget, s.rightTarget)) {
                val c = value(v, s.celsius)
                lo = min(lo, c)
                hi = max(hi, c)
            }
        }
        val minSpan = if (celsius) 6f else 10f
        if (hi - lo < minSpan) {
            val mid = (hi + lo) / 2
            lo = mid - minSpan / 2
            hi = mid + minSpan / 2
        }
        val step = niceStep((hi - lo) / 5)
        lo = floor(lo / step) * step
        hi = ceil(hi / step) * step
        fun y(v: Float) = padT + h - (v - lo) / (hi - lo) * h
        fun x(t: Long) = padL + (t - start).toFloat() / rangeMs * w

        label.textAlign = Paint.Align.RIGHT
        var g = lo
        while (g <= hi + step / 2) {
            val yy = y(g)
            canvas.drawLine(padL, yy, padL + w, yy, grid)
            canvas.drawText(formatTemp(g), padL - 6 * density, yy + 4 * density, label)
            g += step
        }
        label.textAlign = Paint.Align.CENTER
        timeTicks(start).forEach { (t, text) ->
            val xx = x(t)
            canvas.drawLine(xx, padT, xx, padT + h, grid)
            canvas.drawText(text, xx, height - 6 * density, label)
        }

        drawSeries(canvas, dashed, rightColor, { it.rightTarget }, ::x, ::y)
        drawSeries(canvas, dashed, leftColor, { it.leftTarget }, ::x, ::y)
        drawSeries(canvas, line, rightColor, { it.right }, ::x, ::y)
        drawSeries(canvas, line, leftColor, { it.left }, ::x, ::y)

        label.textAlign = Paint.Align.LEFT
        var lx = padL
        val ly = 15 * density
        for ((name, color) in listOf(
            context.getString(R.string.fridge_left) to leftColor,
            context.getString(R.string.fridge_right) to rightColor,
        )) {
            line.color = color
            canvas.drawLine(lx, ly - 4 * density, lx + 16 * density, ly - 4 * density, line)
            lx += 20 * density
            canvas.drawText(name, lx, ly, label)
            lx += label.measureText(name) + 16 * density
        }
        dashed.color = textColor
        canvas.drawLine(lx, ly - 4 * density, lx + 16 * density, ly - 4 * density, dashed)
        canvas.drawText(context.getString(R.string.fridge_graph_target), lx + 20 * density, ly, label)
    }

    private inline fun drawSeries(
        canvas: Canvas,
        paint: Paint,
        color: Int,
        pick: (FridgeHistory.Sample) -> Int,
        x: (Long) -> Float,
        y: (Float) -> Float,
    ) {
        path.reset()
        var prev: Long? = null
        var single = true
        for (s in samples) {
            val px = x(s.time)
            val py = y(value(pick(s), s.celsius))
            if (prev == null || s.time - prev > FridgeHistory.GAP_MS) {
                path.moveTo(px, py)
                single = true
            } else {
                path.lineTo(px, py)
                single = false
            }
            // Keep isolated samples visible as a short dash.
            if (single) path.lineTo(px + 0.5f, py)
            prev = s.time
        }
        paint.color = color
        canvas.drawPath(path, paint)
    }

    private fun timeTicks(start: Long): List<Pair<Long, String>> {
        val (stepMs, pattern) = when {
            rangeMs <= FridgeHistory.HOUR_MS -> 15 * 60_000L to "HH:mm"
            rangeMs <= FridgeHistory.DAY_MS -> 4 * FridgeHistory.HOUR_MS to "HH:mm"
            else -> FridgeHistory.DAY_MS to "EEE"
        }
        val fmt = SimpleDateFormat(pattern, Locale.getDefault())
        val cal = Calendar.getInstance().apply {
            timeInMillis = start
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (stepMs >= FridgeHistory.DAY_MS) {
                set(Calendar.MINUTE, 0)
                set(Calendar.HOUR_OF_DAY, 0)
            } else if (stepMs >= FridgeHistory.HOUR_MS) {
                set(Calendar.MINUTE, 0)
                set(Calendar.HOUR_OF_DAY, get(Calendar.HOUR_OF_DAY) / 4 * 4)
            } else {
                set(Calendar.MINUTE, get(Calendar.MINUTE) / 15 * 15)
            }
        }
        val ticks = mutableListOf<Pair<Long, String>>()
        while (cal.timeInMillis <= end) {
            if (cal.timeInMillis >= start) ticks.add(cal.timeInMillis to fmt.format(Date(cal.timeInMillis)))
            if (stepMs >= FridgeHistory.DAY_MS) cal.add(Calendar.DAY_OF_MONTH, 1)
            else cal.add(Calendar.MILLISECOND, stepMs.toInt())
        }
        return ticks
    }

    private fun formatTemp(v: Float): String =
        if (abs(v - Math.round(v)) < 0.05f) "${Math.round(v)}°" else String.format(Locale.US, "%.1f°", v)

    private fun niceStep(raw: Float): Float {
        val options = floatArrayOf(1f, 2f, 5f, 10f, 20f, 50f)
        return options.firstOrNull { it >= raw } ?: 100f
    }
}
