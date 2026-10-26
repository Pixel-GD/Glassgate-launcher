package com.pixelgd.glassgate.widgets

import android.Manifest
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.pixelgd.glassgate.util.withAlpha
import kotlin.math.abs

/**
 * The Weather widget, laid out like iOS. Small: place, big temperature, symbol, condition,
 * high and low. Medium: the same header over the next six hours. Large: header, hours and a
 * five-day list with temperature range bars. Asks for coarse location on tap if it needs it.
 */
class WeatherWidgetView(context: Context, size: WidgetSize) : GlassWidgetView(context, size),
    WeatherRepository.Listener {

    private val repo = WeatherRepository.of(context)
    private var weather: Weather? = repo.weather
    private var state = repo.state

    private val placePaint = textPaint(600)
    private val tempPaint = textPaint(300).apply { letterSpacing = -0.03f }
    private val condPaint = textPaint(600)
    private val hiLoPaint = textPaint(600, 0.85f)
    private val smallPaint = textPaint(600, 0.85f)
    private val hourTemp = textPaint(600)
    private val msgPaint = textPaint(500, 0.8f)
    private val divider = Paint().apply { color = Color.WHITE.withAlpha(0.18f) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()
    private val path = Path()
    private var downX = 0f
    private var downY = 0f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        repo.addListener(this)
        repo.refreshIfStale()
    }

    override fun onDetachedFromWindow() {
        repo.removeListener(this)
        super.onDetachedFromWindow()
    }

    /** Coming back from the permission dialog (or the screen turning on) gets fresh weather. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) repo.refreshIfStale()
    }

    override fun onWeather(weather: Weather?, state: WeatherRepository.State) {
        this.weather = weather
        this.state = state
        contentDescription = weather?.let { "${it.place}, ${it.temp} degrees, ${it.condition.label}" } ?: "Weather"
        invalidate()
    }

    /** A tap asks for location when it's missing; long-press still reaches the widget frame. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (state != WeatherRepository.State.NEEDS_PERMISSION) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; return true }
            MotionEvent.ACTION_UP -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (abs(event.x - downX) < slop && abs(event.y - downY) < slop &&
                    event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()
                ) {
                    (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = weather
        if (w == null) return message(canvas)
        val headerBottom = header(canvas, w)
        when (size) {
            WidgetSize.SMALL -> Unit
            WidgetSize.MEDIUM -> hours(canvas, w, headerBottom + 8f * u, height - pad)
            WidgetSize.LARGE -> {
                val hoursBottom = headerBottom + 8f * u + 64f * u
                hours(canvas, w, headerBottom + 8f * u, hoursBottom)
                canvas.drawRect(pad, hoursBottom + 8f * u, width - pad, hoursBottom + 8f * u + maxOf(1f, 0.5f * u), divider)
                days(canvas, w, hoursBottom + 14f * u, height - pad * 0.6f)
            }
        }
    }

    private fun message(c: Canvas) {
        placePaint.textSize = 15f * u
        msgPaint.textSize = 13f * u
        c.text("Weather", pad, pad, placePaint)
        val msg = when (state) {
            WeatherRepository.State.NEEDS_PERMISSION -> "Tap to allow location for local weather."
            WeatherRepository.State.NO_LOCATION -> "Location is unavailable. Turn on Location."
            WeatherRepository.State.ERROR -> "Weather is unavailable right now."
            else -> "Loading…"
        }
        // Simple word wrap: the messages are short.
        val maxW = width - pad * 2
        var line = ""
        var y = pad + placePaint.lineHeight() + 8f * u
        for (word in msg.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (msgPaint.measureText(next) > maxW && line.isNotEmpty()) {
                c.text(line, pad, y, msgPaint)
                y += msgPaint.lineHeight() + 3f * u
                line = word
            } else line = next
        }
        if (line.isNotEmpty()) c.text(line, pad, y, msgPaint)
    }

    /** Place, temperature, symbol, condition, H/L. Returns its bottom edge. */
    private fun header(c: Canvas, w: Weather): Float {
        placePaint.textSize = 15f * u
        tempPaint.textSize = 42f * u
        condPaint.textSize = 13f * u
        hiLoPaint.textSize = 13f * u
        val maxW = width - pad * 2
        var y = pad
        val placeW = placePaint.measureText(w.place).coerceAtMost(maxW - 14f * u)
        c.text(w.place, pad, y, placePaint, maxW - 14f * u)
        locationArrow(c, pad + placeW + 4f * u, y + 1f * u, 9f * u)
        y += placePaint.lineHeight() + 1f * u
        c.text("${w.temp}°", pad - 2f * u, y, tempPaint)
        y += tempPaint.lineHeight()
        if (size == WidgetSize.SMALL) {
            // iOS small: symbol, condition and H/L stacked at the bottom.
            val glyphS = 20f * u
            val bottom = height - pad
            val hlTop = bottom - hiLoPaint.lineHeight()
            val condTop = hlTop - 2f * u - condPaint.lineHeight()
            WeatherGlyph.draw(c, w.condition, w.isDay, pad + glyphS / 2f, condTop - 4f * u - glyphS / 2f, glyphS)
            c.text(w.condition.label, pad, condTop, condPaint, maxW)
            c.text("H:${w.high}°  L:${w.low}°", pad, hlTop, hiLoPaint, maxW)
            return bottom
        }
        // Medium and large: symbol, condition and H/L in the top right.
        val right = width - pad
        val glyphS = 20f * u
        WeatherGlyph.draw(c, w.condition, w.isDay, right - glyphS / 2f, pad + glyphS / 2f, glyphS)
        condPaint.textAlign = Paint.Align.RIGHT
        hiLoPaint.textAlign = Paint.Align.RIGHT
        val condTop = pad + glyphS + 6f * u
        c.text(w.condition.label, right, condTop, condPaint)
        c.text("H:${w.high}°  L:${w.low}°", right, condTop + condPaint.lineHeight() + 2f * u, hiLoPaint)
        condPaint.textAlign = Paint.Align.LEFT
        hiLoPaint.textAlign = Paint.Align.LEFT
        return y
    }

    /** Six columns: hour, symbol, temperature. */
    private fun hours(c: Canvas, w: Weather, top: Float, bottom: Float) {
        if (w.hours.isEmpty()) return
        smallPaint.textSize = 12f * u
        hourTemp.textSize = 15f * u
        smallPaint.textAlign = Paint.Align.CENTER
        hourTemp.textAlign = Paint.Align.CENTER
        val colW = (width - pad * 2) / 6f
        val glyphS = 20f * u
        val total = smallPaint.lineHeight() + glyphS + hourTemp.lineHeight() + 12f * u
        val y0 = top + ((bottom - top) - total).coerceAtLeast(0f) / 2f
        for ((i, h) in w.hours.withIndex()) {
            val cx = pad + colW * (i + 0.5f)
            c.text(h.label, cx, y0, smallPaint, colW)
            val gy = y0 + smallPaint.lineHeight() + 6f * u
            WeatherGlyph.draw(c, h.condition, h.isDay, cx, gy + glyphS / 2f, glyphS)
            c.text("${h.temp}°", cx, gy + glyphS + 6f * u, hourTemp)
        }
        smallPaint.textAlign = Paint.Align.LEFT
    }

    /** Five days: name, symbol, low, range bar, high. */
    private fun days(c: Canvas, w: Weather, top: Float, bottom: Float) {
        val days = w.days.take(5)
        if (days.isEmpty()) return
        smallPaint.textSize = 15f * u
        hourTemp.textSize = 15f * u
        hourTemp.textAlign = Paint.Align.RIGHT
        val minAll = days.minOf { it.low }
        val maxAll = days.maxOf { it.high }.coerceAtLeast(minAll + 1)
        val rowH = (bottom - top) / days.size
        val nameW = 52f * u
        val glyphS = 20f * u
        val tempW = 34f * u
        val barLeft = pad + nameW + glyphS + 12f * u + tempW
        val barRight = width - pad - tempW - 4f * u
        val barH = 4f * u
        for ((i, d) in days.withIndex()) {
            val ty = top + rowH * i + (rowH - smallPaint.lineHeight()) / 2f
            val cy = top + rowH * i + rowH / 2f
            c.text(d.label, pad, ty, smallPaint, nameW)
            WeatherGlyph.draw(c, d.condition, true, pad + nameW + glyphS / 2f, cy, glyphS)
            hourTemp.color = Color.WHITE.withAlpha(0.6f)
            c.text("${d.low}°", barLeft - 6f * u, ty, hourTemp)
            hourTemp.color = Color.WHITE
            c.text("${d.high}°", width - pad, ty, hourTemp)
            // Track, then this day's range over the week's range, cool to warm like iOS.
            rect.set(barLeft, cy - barH / 2, barRight, cy + barH / 2)
            bar.shader = null
            bar.color = Color.argb(60, 0, 0, 0)
            c.drawRoundRect(rect, barH, barH, bar)
            val span = (maxAll - minAll).toFloat()
            val l = barLeft + (barRight - barLeft) * (d.low - minAll) / span
            val r = barLeft + (barRight - barLeft) * (d.high - minAll) / span
            rect.set(l, cy - barH / 2, maxOf(r, l + barH), cy + barH / 2)
            bar.shader = LinearGradient(barLeft, 0f, barRight, 0f,
                intArrayOf(Color.rgb(90, 200, 250), Color.rgb(160, 220, 120), Color.rgb(255, 204, 0), Color.rgb(255, 149, 0)),
                null, Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, barH, barH, bar)
        }
        hourTemp.textAlign = Paint.Align.CENTER
    }

    /** The filled location arrow iOS shows after "My Location". */
    private fun locationArrow(c: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x + s, y)
        path.lineTo(x, y + s * 0.45f)
        path.lineTo(x + s * 0.5f, y + s * 0.5f)
        path.lineTo(x + s * 0.55f, y + s)
        path.close()
        c.drawPath(path, arrow)
    }

    companion object {
        const val REQUEST_LOCATION = 0x7765
    }
}
