package com.pixelgd.glassgate.widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.text.format.DateUtils
import com.pixelgd.glassgate.util.withAlpha
import kotlin.math.min

/**
 * The Batteries widget: the phone's charge as an iOS ring (green, yellow in Battery Saver,
 * red at 20% and under) with a bolt while charging. Medium adds the percentage and status
 * beside the ring; large adds detail rows.
 */
class BatteryWidgetView(context: Context, size: WidgetSize) : GlassWidgetView(context, size) {

    private var level = -1
    private var charging = false
    private var plugged = 0
    private var tempTenths = 0
    private var health = BatteryManager.BATTERY_HEALTH_UNKNOWN
    private var saver = false
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_BATTERY_CHANGED) read(i)
            saver = power?.isPowerSaveMode == true
            invalidate()
        }
    }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bigPct = textPaint(700)
    private val pctPaint = textPaint(600).apply { textAlign = Paint.Align.CENTER }
    private val titlePaint = textPaint(600)
    private val subPaint = textPaint(500, 0.7f)
    private val rowPaint = textPaint(500, 0.75f)
    private val rowValue = textPaint(600).apply { textAlign = Paint.Align.RIGHT }
    private val divider = Paint().apply { color = Color.WHITE.withAlpha(0.18f) }
    private val rect = RectF()
    private val path = Path()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        val sticky = if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        sticky?.let { read(it) }
        saver = power?.isPowerSaveMode == true
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(receiver) }
        super.onDetachedFromWindow()
    }

    private fun read(i: Intent) {
        val raw = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        level = if (raw < 0 || scale <= 0) -1 else raw * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        tempTenths = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        contentDescription = "Battery $level percent" + if (charging) ", charging" else ""
    }

    private val ringColor: Int
        get() = when {
            saver -> Color.rgb(255, 214, 10)
            level in 0..20 && !charging -> Color.rgb(255, 69, 58)
            else -> Color.rgb(48, 209, 88)
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (size) {
            WidgetSize.SMALL -> {
                pctPaint.textSize = 22f * u
                val ringR = min(w, h) / 2f - pad - 14f * u
                val cy = pad + ringR + 6f * u
                ring(canvas, w / 2f, cy, ringR, 9f * u)
                canvas.text(pctText(), w / 2f, h - pad - pctPaint.lineHeight(), pctPaint)
            }
            WidgetSize.MEDIUM -> {
                val ringR = h / 2f - pad - 4f * u
                ring(canvas, pad + ringR + 4f * u, h / 2f, ringR, 10f * u)
                summary(canvas, pad * 2 + ringR * 2 + 12f * u, h / 2f)
            }
            WidgetSize.LARGE -> {
                val ringR = 52f * u
                ring(canvas, pad + ringR + 4f * u, pad + ringR + 4f * u, ringR, 10f * u)
                summary(canvas, pad * 2 + ringR * 2 + 12f * u, pad + ringR + 4f * u)
                details(canvas, pad + ringR * 2 + 30f * u)
            }
        }
    }

    private fun pctText() = if (level < 0) "—" else "$level%"

    private fun ring(c: Canvas, cx: Float, cy: Float, r: Float, stroke: Float) {
        track.strokeWidth = stroke
        track.color = ringColor.withAlpha(0.22f)
        arc.strokeWidth = stroke
        arc.color = ringColor
        rect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawOval(rect, track)
        if (level > 0) c.drawArc(rect, -90f, 360f * level / 100f, false, arc)
        phone(c, cx, cy, r * 0.62f)
        if (charging) bolt(c, cx + r * 0.72f, cy - r * 0.72f, r * 0.36f)
    }

    /** A phone outline, the device glyph inside the ring. */
    private fun phone(c: Canvas, cx: Float, cy: Float, hgt: Float) {
        val wd = hgt * 0.52f
        glyph.style = Paint.Style.STROKE
        glyph.strokeWidth = hgt * 0.09f
        rect.set(cx - wd / 2, cy - hgt / 2, cx + wd / 2, cy + hgt / 2)
        c.drawRoundRect(rect, wd * 0.28f, wd * 0.28f, glyph)
        glyph.style = Paint.Style.FILL
        rect.set(cx - wd * 0.16f, cy - hgt / 2 + hgt * 0.1f, cx + wd * 0.16f, cy - hgt / 2 + hgt * 0.16f)
        c.drawRoundRect(rect, hgt, hgt, glyph)
    }

    /** The charging bolt, on a dark badge so it reads over the ring. */
    private fun bolt(c: Canvas, cx: Float, cy: Float, r: Float) {
        glyph.style = Paint.Style.FILL
        glyph.color = Color.argb(150, 0, 0, 0)
        c.drawCircle(cx, cy, r, glyph)
        glyph.color = Color.WHITE
        val s = r * 0.62f
        path.reset()
        path.moveTo(cx + s * 0.15f, cy - s)
        path.lineTo(cx - s * 0.55f, cy + s * 0.12f)
        path.lineTo(cx - s * 0.02f, cy + s * 0.12f)
        path.lineTo(cx - s * 0.15f, cy + s)
        path.lineTo(cx + s * 0.55f, cy - s * 0.12f)
        path.lineTo(cx + s * 0.02f, cy - s * 0.12f)
        path.close()
        c.drawPath(path, glyph)
    }

    /** Big percentage with a status line, vertically centred on [cy]. */
    private fun summary(c: Canvas, left: Float, cy: Float) {
        val maxW = width - left - pad
        titlePaint.textSize = 13f * u
        bigPct.textSize = 40f * u
        subPaint.textSize = 13f * u
        val total = titlePaint.lineHeight() + bigPct.lineHeight() + subPaint.lineHeight() + 8f * u
        var y = cy - total / 2f
        c.text(Build.MODEL?.takeIf { it.isNotBlank() } ?: "This phone", left, y, titlePaint, maxW)
        y += titlePaint.lineHeight() + 2f * u
        c.text(pctText(), left, y, bigPct, maxW)
        y += bigPct.lineHeight() + 6f * u
        c.text(statusText(), left, y, subPaint, maxW)
    }

    private fun statusText(): String = when {
        level >= 100 && charging -> "Charged"
        charging -> chargeEta()?.let { "Full in $it" } ?: "Charging"
        saver -> "Battery Saver on"
        level in 0..20 -> "Low battery"
        else -> "On battery"
    }

    private fun chargeEta(): String? {
        val ms = battery?.computeChargeTimeRemaining() ?: -1L
        if (ms <= 0) return null
        return DateUtils.formatElapsedTime(ms / 1000).let { t ->
            val parts = t.split(':')
            if (parts.size == 3) "${parts[0].toInt()}h ${parts[1].toInt()}m" else "${parts[0].toInt()}m"
        }
    }

    /** Large widget: detail rows under the ring. */
    private fun details(c: Canvas, top: Float) {
        rowPaint.textSize = 15f * u
        rowValue.textSize = 15f * u
        val rows = listOf(
            "Status" to statusText(),
            "Power source" to when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "Charger"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                BatteryManager.BATTERY_PLUGGED_DOCK -> "Dock"
                else -> "Battery"
            },
            "Temperature" to if (tempTenths == 0) "—" else "%.1f°C".format(tempTenths / 10f),
            "Health" to when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Service"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                else -> "Unknown"
            },
        )
        val rowH = ((height - pad - top) / rows.size).coerceAtMost(44f * u)
        var y = top
        for ((i, row) in rows.withIndex()) {
            if (i > 0) c.drawRect(pad, y, width - pad, y + maxOf(1f, 0.5f * u), divider)
            val ty = y + (rowH - rowPaint.lineHeight()) / 2f
            c.text(row.first, pad, ty, rowPaint)
            c.text(row.second, width - pad, ty, rowValue)
            y += rowH
        }
    }
}
