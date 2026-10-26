package com.pixelgd.glassgate.menu

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Small line glyphs in the spirit of SF Symbols, drawn with the menu's text paint so they
 * follow its colour. [draw] fits the glyph in a [size] square centred on (cx, cy).
 */
enum class Glyph {
    /** "apps.iphone": a phone outline with a 2x2 grid of apps. */
    EDIT_HOME,
    /** "minus.circle". */
    REMOVE,
    /** "info.circle". */
    INFO,
    /** "pencil". */
    RENAME,
    /** "plus.square": add a widget. */
    ADD_WIDGET,
    /** "paintbrush"-ish: a half-filled circle, the iOS Customize icon. */
    CUSTOMIZE,
    /** Two stacked page cards. */
    PAGES,
    /** "trash". */
    DELETE,
    /** "plus.circle": add to Home Screen. */
    ADD;

    fun draw(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val p = PAINT
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeWidth = size * 0.085f
        val r = size / 2f
        val rect = RectF()
        when (this) {
            EDIT_HOME -> {
                rect.set(cx - r * 0.62f, cy - r * 0.95f, cx + r * 0.62f, cy + r * 0.95f)
                canvas.drawRoundRect(rect, r * 0.28f, r * 0.28f, p)
                p.style = Paint.Style.FILL
                val d = r * 0.2f
                for (i in 0..1) for (j in 0..1) {
                    val x = cx - r * 0.25f + i * r * 0.5f
                    val y = cy - r * 0.42f + j * r * 0.5f
                    rect.set(x - d, y - d, x + d, y + d)
                    canvas.drawRoundRect(rect, d * 0.5f, d * 0.5f, p)
                }
            }
            REMOVE, ADD -> {
                canvas.drawCircle(cx, cy, r * 0.88f, p)
                canvas.drawLine(cx - r * 0.42f, cy, cx + r * 0.42f, cy, p)
                if (this == ADD) canvas.drawLine(cx, cy - r * 0.42f, cx, cy + r * 0.42f, p)
            }
            INFO -> {
                canvas.drawCircle(cx, cy, r * 0.88f, p)
                canvas.drawLine(cx, cy - r * 0.08f, cx, cy + r * 0.45f, p)
                p.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy - r * 0.38f, size * 0.06f, p)
            }
            RENAME -> {
                canvas.save()
                canvas.rotate(45f, cx, cy)
                rect.set(cx - r * 0.2f, cy - r * 0.9f, cx + r * 0.2f, cy + r * 0.55f)
                canvas.drawRect(rect, p)
                canvas.drawLine(cx - r * 0.2f, cy + r * 0.55f, cx, cy + r * 0.92f, p)
                canvas.drawLine(cx + r * 0.2f, cy + r * 0.55f, cx, cy + r * 0.92f, p)
                canvas.restore()
            }
            ADD_WIDGET -> {
                rect.set(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f)
                canvas.drawRoundRect(rect, r * 0.3f, r * 0.3f, p)
                canvas.drawLine(cx - r * 0.4f, cy, cx + r * 0.4f, cy, p)
                canvas.drawLine(cx, cy - r * 0.4f, cx, cy + r * 0.4f, p)
            }
            CUSTOMIZE -> {
                canvas.drawCircle(cx, cy, r * 0.85f, p)
                p.style = Paint.Style.FILL
                rect.set(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f)
                canvas.drawArc(rect, 90f, 180f, true, p)
            }
            PAGES -> {
                rect.set(cx - r * 0.5f, cy - r * 0.9f, cx + r * 0.75f, cy + r * 0.55f)
                canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, p)
                rect.set(cx - r * 0.75f, cy - r * 0.55f, cx + r * 0.5f, cy + r * 0.9f)
                p.style = Paint.Style.FILL
                p.alpha = 255
                canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, p)
            }
            DELETE -> {
                canvas.drawLine(cx - r * 0.8f, cy - r * 0.55f, cx + r * 0.8f, cy - r * 0.55f, p)
                canvas.drawLine(cx - r * 0.25f, cy - r * 0.85f, cx + r * 0.25f, cy - r * 0.85f, p)
                rect.set(cx - r * 0.6f, cy - r * 0.55f, cx + r * 0.6f, cy + r * 0.9f)
                canvas.drawRoundRect(rect, r * 0.15f, r * 0.15f, p)
                canvas.drawLine(cx - r * 0.2f, cy - r * 0.25f, cx - r * 0.2f, cy + r * 0.6f, p)
                canvas.drawLine(cx + r * 0.2f, cy - r * 0.25f, cx + r * 0.2f, cy + r * 0.6f, p)
            }
        }
    }

    private companion object {
        val PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    }
}
