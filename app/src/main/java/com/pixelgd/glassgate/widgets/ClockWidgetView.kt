package com.pixelgd.glassgate.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.format.DateFormat
import com.pixelgd.glassgate.util.withAlpha
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The Clock widget. Small: an analog face with the city name, like iOS. Medium: the face on
 * the left, day, time and date on the right. Large: a big face over the date.
 * Ticks once a second while it's on screen.
 */
class ClockWidgetView(context: Context, size: WidgetSize) : GlassWidgetView(context, size) {

    private val cal = Calendar.getInstance()
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            removeCallbacks(this)
            postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    private val numeral = textPaint(600).apply { textAlign = Paint.Align.CENTER }
    private val cityPaint = textPaint(600, 0.75f).apply { textAlign = Paint.Align.CENTER }
    private val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(4f, 0f, 1f, Color.argb(60, 0, 0, 0))
    }
    private val second = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 159, 10) // iOS system orange
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val dayPaint = textPaint(600, 0.75f)
    private val timePaint = textPaint(700)
    private val datePaint = textPaint(500, 0.8f)

    private val city: String
        get() = TimeZone.getDefault().id.substringAfterLast('/').replace('_', ' ')

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(tick)
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        removeCallbacks(tick)
        if (isVisible && isAttachedToWindow) post(tick)
    }

    override fun onDraw(canvas: Canvas) {
        cal.timeInMillis = System.currentTimeMillis()
        cal.timeZone = TimeZone.getDefault()
        val w = width.toFloat()
        val h = height.toFloat()
        contentDescription = DateFormat.getTimeFormat(context).format(cal.time)
        when (size) {
            WidgetSize.SMALL -> face(canvas, w / 2f, h / 2f, min(w, h) / 2f - 10f * u, showCity = true)
            WidgetSize.MEDIUM -> {
                val r = h / 2f - pad
                face(canvas, pad + r, h / 2f, r, showCity = false)
                digital(canvas, pad * 2 + r * 2 + 6f * u, w - pad)
            }
            WidgetSize.LARGE -> {
                val bottom = header(canvas)
                val r = min(w / 2f - pad, (h - bottom - pad) / 2f)
                face(canvas, w / 2f, bottom + (h - bottom) / 2f, r, showCity = true)
            }
        }
    }

    /** Analog face: numerals, minute ticks, hands. */
    private fun face(c: Canvas, cx: Float, cy: Float, r: Float, showCity: Boolean) {
        val s = r / 75f // geometry tuned for a 75pt-radius face
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0)
            val major = i % 5 == 0
            val outer = r - 2f * s
            val inner = outer - (if (major) 6f else 3.5f) * s
            tickPaint.color = Color.WHITE.withAlpha(if (major) 0.9f else 0.45f)
            tickPaint.strokeWidth = (if (major) 1.8f else 1f) * s
            c.drawLine(cx + sin(a).toFloat() * inner, cy - cos(a).toFloat() * inner,
                cx + sin(a).toFloat() * outer, cy - cos(a).toFloat() * outer, tickPaint)
        }
        numeral.textSize = 15f * s
        val nr = r - 20f * s
        for (n in 1..12) {
            val a = Math.toRadians(n * 30.0)
            val x = cx + sin(a).toFloat() * nr
            val y = cy - cos(a).toFloat() * nr - (numeral.ascent() + numeral.descent()) / 2f
            c.drawText(n.toString(), x, y, numeral)
        }
        if (showCity) {
            cityPaint.textSize = 10f * s
            c.drawText(cityAbbrev(), cx, cy - 26f * s, cityPaint)
        }

        val sec = cal.get(Calendar.SECOND)
        val min = cal.get(Calendar.MINUTE) + sec / 60f
        val hr = cal.get(Calendar.HOUR) + min / 60f
        handAt(c, cx, cy, hr * 30f, r * 0.5f, 4.5f * s, hand)
        handAt(c, cx, cy, min * 6f, r * 0.78f, 3.2f * s, hand)
        handAt(c, cx, cy, sec * 6f, r * 0.86f, 1.2f * s, second, tail = r * 0.15f)
        c.drawCircle(cx, cy, 3.4f * s, hand)
        c.drawCircle(cx, cy, 1.8f * s, second)
    }

    private fun handAt(c: Canvas, cx: Float, cy: Float, deg: Float, len: Float, width: Float, p: Paint, tail: Float = 0f) {
        val a = Math.toRadians(deg.toDouble())
        val dx = sin(a).toFloat()
        val dy = -cos(a).toFloat()
        p.strokeWidth = width
        c.drawLine(cx - dx * tail, cy - dy * tail, cx + dx * len, cy + dy * len, p)
    }

    /** "SUNDAY", big time, date: the right half of the medium widget. */
    private fun digital(c: Canvas, left: Float, right: Float) {
        val maxW = right - left
        dayPaint.textSize = 13f * u
        timePaint.textSize = 44f * u
        timePaint.letterSpacing = -0.02f
        datePaint.textSize = 15f * u
        val day = DateFormat.format("EEEE", cal).toString().uppercase(Locale.getDefault())
        val time = DateFormat.getTimeFormat(context).format(cal.time)
            .replace(" AM", "").replace(" PM", "").replace(" AM", "").replace(" PM", "")
        val date = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMMd"), cal).toString()
        val total = dayPaint.lineHeight() + timePaint.lineHeight() + datePaint.lineHeight() + 8f * u
        var y = (height - total) / 2f
        c.text(day, left, y, dayPaint, maxW)
        y += dayPaint.lineHeight() + 2f * u
        c.text(time, left, y, timePaint, maxW)
        y += timePaint.lineHeight() + 6f * u
        c.text(date, left, y, datePaint, maxW)
    }

    /** Large widget header: day and date. Returns its bottom edge. */
    private fun header(c: Canvas): Float {
        dayPaint.textSize = 13f * u
        timePaint.textSize = 26f * u
        val day = DateFormat.format("EEEE", cal).toString().uppercase(Locale.getDefault())
        val date = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMMd"), cal).toString()
        var y = pad
        c.text(day, pad, y, dayPaint, width - pad * 2)
        y += dayPaint.lineHeight() + 2f * u
        c.text(date, pad, y, timePaint, width - pad * 2)
        return y + timePaint.lineHeight()
    }

    private fun cityAbbrev(): String {
        val name = city
        return if (name.length <= 3) name.uppercase() else name.take(3).uppercase(Locale.getDefault())
    }
}
