package com.pixelgd.glassgate.menu

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import com.pixelgd.glassgate.glass.GlassDrawable
import com.pixelgd.glassgate.glass.GlassStyle
import com.pixelgd.glassgate.glass.Squircle
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.isDarkMode
import com.pixelgd.glassgate.util.sp
import com.pixelgd.glassgate.util.tick
import com.pixelgd.glassgate.util.withAlpha

/** One row of a [GlassMenuView]. Give a [glyph] or an [icon] (shortcut icons), or neither. */
class MenuItem(
    val title: String,
    val glyph: Glyph? = null,
    val icon: Drawable? = null,
    val destructive: Boolean = false,
    /** Run once the menu has finished closing, e.g. when the action opens its own blur. */
    val afterClose: Boolean = false,
    val action: () -> Unit,
)

/**
 * The iOS 26 glass menu: rows of title + trailing glyph in sections, with a wider gap between
 * sections. Like UIKit, a finger can slide across rows and the row under it on lift fires.
 * [onPick] gets the chosen row; the host closes itself and runs the item's action.
 */
@SuppressLint("ViewConstructor")
class GlassMenuView(
    context: Context,
    private val sections: List<List<MenuItem>>,
    private val onPick: (MenuItem) -> Unit,
) : View(context) {

    private val dark = context.isDarkMode
    private val textColor = if (dark) Color.WHITE else Color.rgb(20, 20, 24)
    private val red = Color.rgb(255, 59, 48)
    private val rowH = context.dp(46f)
    private val gapH = context.dp(8f)
    private val padX = context.dp(18f)
    private val glyphSize = context.dp(19f)
    private val menuWidth = context.dp(254f)
    private val radius = GlassStyle.MENU.cornerRadius * context.dp(1f)

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(16f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val rect = RectF()

    /** Row rects in drawing order, paired with their item. */
    private val rows = ArrayList<Pair<RectF, MenuItem>>()
    private var pressed: MenuItem? = null

    init {
        background = GlassDrawable(this, GlassStyle.MENU)
        isClickable = true
        var y = 0f
        sections.filter { it.isNotEmpty() }.forEachIndexed { si, items ->
            if (si > 0) y += gapH
            for (item in items) {
                rows += RectF(0f, y, menuWidth, y + rowH) to item
                y += rowH
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(menuWidth.toInt(), (rows.lastOrNull()?.first?.bottom ?: 0f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        Squircle.roundRect(rect, radius, clip)
        canvas.save()
        canvas.clipPath(clip)
        // Section gaps are a slightly darker band, like UIMenu's separators.
        fillPaint.color = Color.BLACK.withAlpha(if (dark) 0.25f else 0.07f)
        for (i in 1 until rows.size) {
            val prev = rows[i - 1].first
            val cur = rows[i].first
            if (cur.top > prev.bottom + 0.5f) canvas.drawRect(0f, prev.bottom, width.toFloat(), cur.top, fillPaint)
            else {
                fillPaint.color = textColor.withAlpha(0.08f)
                canvas.drawRect(padX, cur.top - context.dp(0.33f), width.toFloat(), cur.top + context.dp(0.33f), fillPaint)
                fillPaint.color = Color.BLACK.withAlpha(if (dark) 0.25f else 0.07f)
            }
        }
        for ((r, item) in rows) {
            if (item === pressed) {
                fillPaint.color = textColor.withAlpha(if (dark) 0.16f else 0.10f)
                canvas.drawRect(r, fillPaint)
            }
            val color = if (item.destructive) red else textColor
            textPaint.color = color
            val maxText = r.width() - padX * 2 - glyphSize - context.dp(12f)
            val title = TextUtils.ellipsize(item.title, textPaint, maxText, TextUtils.TruncateAt.END)
            val baseline = r.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(title, 0, title.length, r.left + padX, baseline, textPaint)
            val gx = r.right - padX - glyphSize / 2f
            item.glyph?.draw(canvas, gx, r.centerY(), glyphSize, color)
            item.icon?.let { drawIcon(canvas, it, gx, r.centerY(), color) }
        }
        canvas.restore()
    }

    /** Shortcut icons: the monochrome layer in the text colour when there is one, else as-is. */
    private fun drawIcon(canvas: Canvas, d: Drawable, cx: Float, cy: Float, color: Int) {
        val s = context.dp(24f)
        val mono = (d as? AdaptiveIconDrawable)?.monochrome
        val draw = mono?.mutate()?.apply { setTint(color) } ?: d
        if (mono != null) {
            // Adaptive layers carry a 25% safe-zone margin on each side.
            val big = s * 1.5f
            draw.setBounds((cx - big / 2).toInt(), (cy - big / 2).toInt(), (cx + big / 2).toInt(), (cy + big / 2).toInt())
        } else {
            draw.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
        }
        draw.draw(canvas)
    }

    private fun rowAt(x: Float, y: Float) =
        if (x < 0 || x > width) null else rows.firstOrNull { it.first.contains(x, y) }?.second

    /** Lets the host feed a gesture that started elsewhere (the long-press that opened the menu). */
    fun trackFinger(x: Float, y: Float, up: Boolean) {
        val hit = rowAt(x, y)
        if (hit !== pressed) {
            pressed = hit
            if (hit != null) tick()
            invalidate()
        }
        if (up) {
            val picked = pressed
            pressed = null
            invalidate()
            if (picked != null) onPick(picked)
        }
    }


    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> trackFinger(event.x, event.y, false)
            MotionEvent.ACTION_UP -> trackFinger(event.x, event.y, true)
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true
    }
}
