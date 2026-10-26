package com.pixelgd.glassgate.library

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pixelgd.glassgate.DismissibleOverlay
import com.pixelgd.glassgate.glass.GlassDrawable
import com.pixelgd.glassgate.glass.GlassStyle
import com.pixelgd.glassgate.home.AppIconView
import com.pixelgd.glassgate.home.HomeActions
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.dpi
import com.pixelgd.glassgate.util.lerp

/**
 * An App Library category opened from its tile's small-icon cluster: the category name in big
 * bold type over a large glass card with every app in it, zooming out of the tile. Tap outside
 * or press Back to close. Lives in LauncherActivity.overlay.
 */
@SuppressLint("ViewConstructor")
class CategoryFolderView(
    context: Context,
    group: LibraryGroup,
    private val actions: HomeActions,
    private val source: View,
) : FrameLayout(context), DismissibleOverlay {

    private val sheet = GlassDrawable(this, GlassStyle.SHEET).apply { progress = 0f }
    private val card = FrameLayout(context)
    private val title = TextView(context)
    private val cardPad = context.dpi(18f)
    private var closing = false

    // Where the card starts, relative to its resting place: the source tile's centre and scale.
    private var fromDx = 0f
    private var fromDy = 0f
    private var fromScale = 0.4f

    private val spring = Spring(Spring.STIFF_UI, Spring.DAMP_UI) { v -> apply(v) }.also { it.snapTo(0f) }

    init {
        background = sheet
        isClickable = true
        setOnClickListener { close() }

        title.apply {
            text = context.getString(group.category.title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = -0.02f
            gravity = Gravity.CENTER
            setShadowLayer(context.dp(6f), 0f, context.dp(1f), Color.argb(70, 0, 0, 0))
        }

        val iconPx = context.dpi(60f)
        val grid = IconGrid(context, COLS)
        for (app in group.apps) {
            grid.addView(AppIconView(context, iconPx, showLabel = true).apply {
                this.app = app
                setOnClickListener {
                    actions.launch(app, it)
                    (this@CategoryFolderView.parent as? ViewGroup)?.removeView(this@CategoryFolderView)
                }
                setOnLongClickListener { actions.showContextMenu(null, app, it); true }
            })
        }
        val scroll = MaxHeightScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            setPadding(cardPad, cardPad, cardPad, cardPad)
            clipToPadding = false
            addView(grid)
            setOnScrollChangeListener { _, _, _, _, _ -> card.invalidate() }
        }
        card.background = GlassDrawable(card, GlassStyle.FOLDER_OPEN)
        card.isClickable = true // taps between icons don't close
        card.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = context.dpi(26f)
        (card.layoutParams as LayoutParams).setMargins(side, 0, side, 0)
        (card.getChildAt(0) as MaxHeightScrollView).maxHeight = (h * 0.6f).toInt()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Sit a little above centre, title just above the card.
        card.offsetTopAndBottom(-context.dpi(24f))
        val tTop = card.top - title.measuredHeight - context.dpi(18f)
        title.layout(0, tTop, width, tTop + title.measuredHeight)
        if (changed) {
            val me = IntArray(2).also { getLocationInWindow(it) }
            val src = IntArray(2).also { source.getLocationInWindow(it) }
            val srcSize = minOf(source.width, source.height).toFloat()
            fromDx = src[0] + srcSize / 2f - (me[0] + card.left + card.width / 2f)
            fromDy = src[1] + srcSize / 2f - (me[1] + card.top + card.height / 2f)
            fromScale = (srcSize / card.width.coerceAtLeast(1)).coerceIn(0.1f, 1f)
            apply(spring.value)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spring.animateTo(1f)
    }

    override fun onDetachedFromWindow() {
        spring.cancel()
        super.onDetachedFromWindow()
    }

    private fun apply(v: Float) {
        val p = v.coerceIn(0f, 1.2f)
        val s = lerp(fromScale, 1f, p)
        card.scaleX = s
        card.scaleY = s
        card.translationX = lerp(fromDx, 0f, p)
        card.translationY = lerp(fromDy, 0f, p)
        card.alpha = v.coerceIn(0f, 1f)
        title.alpha = v.coerceIn(0f, 1f)
        sheet.progress = v.coerceIn(0f, 1f)
        card.invalidate()
    }

    /** Home and Back (via LauncherActivity) animate this out too. */
    override fun dismiss() = close()

    fun close() {
        if (closing) return
        closing = true
        spring.animateTo(0f) { (parent as? ViewGroup)?.removeView(this) }
    }

    /** A ScrollView that wraps its content up to [maxHeight]. */
    private class MaxHeightScrollView(context: Context) : ScrollView(context) {
        var maxHeight = Int.MAX_VALUE
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }

    /** Icons in [cols] equal columns, rows as tall as the tallest icon plus a gap. */
    private class IconGrid(context: Context, private val cols: Int) : ViewGroup(context) {
        private val rowGap = context.dpi(18f)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val cellW = w / cols
            var cellH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                c.measure(MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                cellH = maxOf(cellH, c.measuredHeight)
            }
            val rows = (childCount + cols - 1) / cols
            setMeasuredDimension(w, if (rows == 0) 0 else rows * cellH + (rows - 1) * rowGap)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val cellW = (r - l) / cols
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val x = (i % cols) * cellW
                val y = (i / cols) * (c.measuredHeight + rowGap)
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            }
        }
    }

    companion object {
        private const val COLS = 4
    }
}
