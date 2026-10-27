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
    ADD,
    /** "arrow.uturn.backward". */
    UNDO,
    /** "arrow.uturn.forward". */
    REDO,
    /** "photo": a frame with a mountain, for Edit Wallpaper. */
    WALLPAPER,
    /** "gearshape". */
    SETTINGS,
    /** "faceid"-ish: a padlock, for Require Face ID. */
    LOCK,
    /** "eye.slash": hide the app. */
    HIDE,
    /** "square.and.arrow.up". */
    SHARE,
    /** "bell". */
    BELL,
    /** "hand.draw": a finger swiping down. */
    SWIPE,
    /** "accessibility": a figure in a circle. */
    PERSON,
    /** "waveform": haptics. */
    WAVE,
    /** "drop": Liquid Glass. */
    DROP,
    /** "magnifyingglass". */
    SEARCH,
    /** "square.grid.2x2": apps. */
    GRID,
    /** "arrow.clockwise.icloud"-ish: backup, a tray with an arrow. */
    BACKUP;

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
            UNDO, REDO -> {
                canvas.save()
                if (this == REDO) canvas.scale(-1f, 1f, cx, cy)
                rect.set(cx - r * 0.55f, cy - r * 0.55f, cx + r * 0.75f, cy + r * 0.65f)
                val path = android.graphics.Path()
                path.moveTo(cx - r * 0.1f, cy + r * 0.65f)
                path.lineTo(cx + r * 0.1f, cy + r * 0.65f)
                path.arcTo(rect, 90f, -180f)
                path.lineTo(cx - r * 0.75f, cy - r * 0.55f)
                canvas.drawPath(path, p)
                canvas.drawLine(cx - r * 0.75f, cy - r * 0.55f, cx - r * 0.4f, cy - r * 0.9f, p)
                canvas.drawLine(cx - r * 0.75f, cy - r * 0.55f, cx - r * 0.4f, cy - r * 0.2f, p)
                canvas.restore()
            }
            WALLPAPER -> {
                rect.set(cx - r * 0.9f, cy - r * 0.7f, cx + r * 0.9f, cy + r * 0.7f)
                canvas.drawRoundRect(rect, r * 0.2f, r * 0.2f, p)
                val path = android.graphics.Path()
                path.moveTo(cx - r * 0.7f, cy + r * 0.5f)
                path.lineTo(cx - r * 0.2f, cy - r * 0.1f)
                path.lineTo(cx + r * 0.15f, cy + r * 0.25f)
                path.lineTo(cx + r * 0.35f, cy + r * 0.05f)
                path.lineTo(cx + r * 0.7f, cy + r * 0.5f)
                canvas.drawPath(path, p)
                p.style = Paint.Style.FILL
                canvas.drawCircle(cx + r * 0.4f, cy - r * 0.35f, size * 0.07f, p)
            }
            SETTINGS -> {
                canvas.drawCircle(cx, cy, r * 0.3f, p)
                canvas.drawCircle(cx, cy, r * 0.62f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = Math.cos(a).toFloat()
                    val sn = Math.sin(a).toFloat()
                    canvas.drawLine(cx + c * r * 0.62f, cy + sn * r * 0.62f, cx + c * r * 0.9f, cy + sn * r * 0.9f, p)
                }
            }
            LOCK -> {
                rect.set(cx - r * 0.65f, cy - r * 0.1f, cx + r * 0.65f, cy + r * 0.9f)
                canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, p)
                rect.set(cx - r * 0.4f, cy - r * 0.85f, cx + r * 0.4f, cy + r * 0.1f)
                canvas.drawArc(rect, 180f, 180f, false, p)
                canvas.drawLine(cx - r * 0.4f, cy - r * 0.38f, cx - r * 0.4f, cy - r * 0.1f, p)
                canvas.drawLine(cx + r * 0.4f, cy - r * 0.38f, cx + r * 0.4f, cy - r * 0.1f, p)
            }
            HIDE -> {
                rect.set(cx - r * 0.9f, cy - r * 0.5f, cx + r * 0.9f, cy + r * 0.5f)
                canvas.drawOval(rect, p)
                canvas.drawCircle(cx, cy, r * 0.22f, p)
                canvas.drawLine(cx - r * 0.75f, cy - r * 0.75f, cx + r * 0.75f, cy + r * 0.75f, p)
            }
            SHARE -> {
                val path = android.graphics.Path()
                path.moveTo(cx - r * 0.3f, cy - r * 0.15f)
                path.lineTo(cx - r * 0.65f, cy - r * 0.15f)
                path.lineTo(cx - r * 0.65f, cy + r * 0.9f)
                path.lineTo(cx + r * 0.65f, cy + r * 0.9f)
                path.lineTo(cx + r * 0.65f, cy - r * 0.15f)
                path.lineTo(cx + r * 0.3f, cy - r * 0.15f)
                canvas.drawPath(path, p)
                canvas.drawLine(cx, cy - r * 0.9f, cx, cy + r * 0.35f, p)
                canvas.drawLine(cx, cy - r * 0.9f, cx - r * 0.3f, cy - r * 0.6f, p)
                canvas.drawLine(cx, cy - r * 0.9f, cx + r * 0.3f, cy - r * 0.6f, p)
            }
            BELL -> {
                val path = android.graphics.Path()
                path.moveTo(cx - r * 0.7f, cy + r * 0.45f)
                path.quadTo(cx - r * 0.5f, cy + r * 0.2f, cx - r * 0.5f, cy - r * 0.1f)
                path.cubicTo(cx - r * 0.5f, cy - r * 0.95f, cx + r * 0.5f, cy - r * 0.95f, cx + r * 0.5f, cy - r * 0.1f)
                path.quadTo(cx + r * 0.5f, cy + r * 0.2f, cx + r * 0.7f, cy + r * 0.45f)
                path.close()
                canvas.drawPath(path, p)
                canvas.drawLine(cx - r * 0.18f, cy + r * 0.75f, cx + r * 0.18f, cy + r * 0.75f, p)
            }
            SWIPE -> {
                canvas.drawLine(cx, cy - r * 0.85f, cx, cy + r * 0.85f, p)
                canvas.drawLine(cx, cy + r * 0.85f, cx - r * 0.4f, cy + r * 0.45f, p)
                canvas.drawLine(cx, cy + r * 0.85f, cx + r * 0.4f, cy + r * 0.45f, p)
                canvas.drawLine(cx - r * 0.75f, cy - r * 0.85f, cx + r * 0.75f, cy - r * 0.85f, p)
            }
            PERSON -> {
                canvas.drawCircle(cx, cy, r * 0.9f, p)
                p.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy - r * 0.45f, size * 0.07f, p)
                p.style = Paint.Style.STROKE
                canvas.drawLine(cx - r * 0.45f, cy - r * 0.15f, cx + r * 0.45f, cy - r * 0.15f, p)
                canvas.drawLine(cx, cy - r * 0.15f, cx, cy + r * 0.2f, p)
                canvas.drawLine(cx, cy + r * 0.2f, cx - r * 0.25f, cy + r * 0.6f, p)
                canvas.drawLine(cx, cy + r * 0.2f, cx + r * 0.25f, cy + r * 0.6f, p)
            }
            WAVE -> {
                val hs = floatArrayOf(0.3f, 0.65f, 0.9f, 0.55f, 0.3f)
                hs.forEachIndexed { i, h ->
                    val x = cx + (i - 2) * r * 0.36f
                    canvas.drawLine(x, cy - r * h, x, cy + r * h, p)
                }
            }
            DROP -> {
                val path = android.graphics.Path()
                path.moveTo(cx, cy - r * 0.9f)
                path.cubicTo(cx + r * 0.2f, cy - r * 0.5f, cx + r * 0.7f, cy - r * 0.05f, cx + r * 0.7f, cy + r * 0.3f)
                path.cubicTo(cx + r * 0.7f, cy + r * 0.75f, cx + r * 0.35f, cy + r * 0.95f, cx, cy + r * 0.95f)
                path.cubicTo(cx - r * 0.35f, cy + r * 0.95f, cx - r * 0.7f, cy + r * 0.75f, cx - r * 0.7f, cy + r * 0.3f)
                path.cubicTo(cx - r * 0.7f, cy - r * 0.05f, cx - r * 0.2f, cy - r * 0.5f, cx, cy - r * 0.9f)
                canvas.drawPath(path, p)
            }
            SEARCH -> {
                canvas.drawCircle(cx - r * 0.15f, cy - r * 0.15f, r * 0.55f, p)
                canvas.drawLine(cx + r * 0.25f, cy + r * 0.25f, cx + r * 0.8f, cy + r * 0.8f, p)
            }
            GRID -> {
                val d = r * 0.36f
                for (i in 0..1) for (j in 0..1) {
                    val x = cx + (i - 0.5f) * r * 0.95f
                    val y = cy + (j - 0.5f) * r * 0.95f
                    rect.set(x - d, y - d, x + d, y + d)
                    canvas.drawRoundRect(rect, d * 0.5f, d * 0.5f, p)
                }
            }
            BACKUP -> {
                val path = android.graphics.Path()
                path.moveTo(cx - r * 0.85f, cy + r * 0.1f)
                path.lineTo(cx - r * 0.85f, cy + r * 0.8f)
                path.lineTo(cx + r * 0.85f, cy + r * 0.8f)
                path.lineTo(cx + r * 0.85f, cy + r * 0.1f)
                canvas.drawPath(path, p)
                canvas.drawLine(cx, cy - r * 0.9f, cx, cy + r * 0.35f, p)
                canvas.drawLine(cx, cy + r * 0.35f, cx - r * 0.35f, cy, p)
                canvas.drawLine(cx, cy + r * 0.35f, cx + r * 0.35f, cy, p)
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
