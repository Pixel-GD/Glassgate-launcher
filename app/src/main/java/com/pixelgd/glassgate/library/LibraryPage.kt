package com.pixelgd.glassgate.library

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ScrollView
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.R
import com.pixelgd.glassgate.glass.GlassDrawable
import com.pixelgd.glassgate.glass.GlassStyle
import com.pixelgd.glassgate.home.HomeActions
import com.pixelgd.glassgate.home.HomeView
import com.pixelgd.glassgate.home.PagedView
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.dpi
import com.pixelgd.glassgate.util.sp
import com.pixelgd.glassgate.util.withAlpha
import kotlin.math.abs

/**
 * The App Library: the page after the last home page. A frosted backdrop, a glass search
 * field at the top (tap, or pull down, for the A to Z list) and a two-column grid of
 * auto-sorted category tiles. As it slides in, the dock and Search pill slide away, like iOS.
 *
 * LauncherActivity keeps one instance and hands it back from createTrailingPage().
 */
@SuppressLint("ViewConstructor")
class LibraryPage(
    context: Context,
    private val actions: HomeActions,
    private val overlay: FrameLayout,
    private val home: HomeView,
) : FrameLayout(context), PagedView.ScrollListener {

    private val launcher = Launcher.of(context)
    private val usage = AppUsage.of(context)
    private val sheet = GlassDrawable(this, GlassStyle.SHEET).apply { progress = 0f }
    private val searchBar = LibrarySearchBar(context)
    private val grid = TileGrid(context)
    private val scroll = ScrollView(context)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var signature = ""
    private var shown = 0f
    private var insetTop = 0
    private var insetBottom = 0

    init {
        background = sheet
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.clipToPadding = false
        scroll.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> grid.onMoved() }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(searchBar, LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(42f), Gravity.TOP))
        searchBar.setOnClickListener { openSearch() }
    }

    /** Re-sorts the tiles if the apps or their usage changed. Cheap when nothing did. */
    fun refresh() {
        val groups = LibraryCategories.build(launcher.apps.all, usage)
        val tilePx = tileSize()
        val sig = buildString {
            append(tilePx).append(launcher.icons.style).append(launcher.icons.tint)
            for (g in groups) {
                append('|').append(g.category)
                // Only what is visible on the tile changes its look.
                for (a in g.apps.take(7)) append(',').append(a.key)
            }
        }
        if (sig == signature) return
        signature = sig
        grid.removeAllViews()
        for (g in groups) {
            grid.addView(CategoryTileView(context, g, tilePx, actions) { openCategory(it) })
        }
    }

    private fun tileSize(): Int {
        val w = resources.displayMetrics.widthPixels
        return ((w - SIDE_DP.px() * 2 - COL_GAP_DP.px()) / 2f).toInt()
    }

    private fun Float.px() = context.dp(this)

    // ---- insets ----

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rootWindowInsets?.let { applyInsets(it) }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        applyInsets(insets)
        return super.onApplyWindowInsets(insets)
    }

    private fun applyInsets(insets: WindowInsets) {
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        if (bars.top == insetTop && bars.bottom == insetBottom && scroll.paddingTop > 0) return
        insetTop = bars.top
        insetBottom = bars.bottom
        val side = SIDE_DP.px().toInt()
        val barLp = searchBar.layoutParams as LayoutParams
        barLp.setMargins(side, insetTop + context.dpi(10f), side, 0)
        searchBar.layoutParams = barLp
        scroll.setPadding(side, insetTop + context.dpi(10f + 42f + 22f), side, insetBottom + context.dpi(28f))
    }

    // ---- paging: fade the frosted backdrop in, slide the dock and pill out ----

    override fun onPageScroll(position: Float, pageCount: Int, isDragging: Boolean) {
        val pages = parent as? ViewGroup ?: return
        val index = pages.indexOfChild(this)
        if (index < 0) return
        val t = if (position >= index) 1f else (1f - abs(position - index)).coerceIn(0f, 1f)
        if (t > 0f && shown == 0f) refresh()
        if (t == shown && t == 0f) return
        shown = t
        sheet.progress = t
        for (v in arrayOf<View>(home.dock, home.searchPill)) {
            v.alpha = 1f - t
            v.translationY = t * (home.height - v.top).coerceAtLeast(0)
            v.invalidate()
        }
        searchBar.invalidate()
        grid.onMoved()
    }

    // ---- gestures ----

    private var downX = 0f
    private var downY = 0f
    private var vertical = false
    private var pullFromTop = false

    /**
     * Claims vertical drags from the pager early, so scrolling the tiles back up isn't taken as
     * the home screen's swipe-down for Spotlight. A pull down from the top opens the A to Z list.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                vertical = false
                pullFromTop = scroll.scrollY == 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!vertical && abs(dy) > slop / 2f && abs(dy) > abs(dx) * 1.5f) {
                    vertical = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (vertical && pullFromTop && scroll.scrollY == 0 && ev.y - downY > slop * 5) openSearch()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---- overlays ----

    fun openSearch() {
        if (overlay.childCount > 0) return
        overlay.addView(LibrarySearchView(context, actions),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun openCategory(tile: CategoryTileView) {
        if (overlay.childCount > 0) return
        overlay.addView(CategoryFolderView(context, tile.group, actions, tile),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Two columns of tiles. */
    private inner class TileGrid(context: Context) : ViewGroup(context) {
        private val colGap = COL_GAP_DP.px().toInt()
        private val rowGap = context.dpi(16f)

        init { clipChildren = false }

        fun onMoved() {
            for (i in 0 until childCount) (getChildAt(i) as? CategoryTileView)?.onMoved()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                rowH = maxOf(rowH, c.measuredHeight)
            }
            val rows = (childCount + 1) / 2
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
                if (rows == 0) 0 else rows * rowH + (rows - 1) * rowGap)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val colW = (w - colGap) / 2
                val x = (i % 2) * (colW + colGap) + (colW - c.measuredWidth) / 2
                val y = (i / 2) * (c.measuredHeight + rowGap)
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            }
        }
    }

    companion object {
        private const val SIDE_DP = 22f
        private const val COL_GAP_DP = 20f
    }
}

/** The glass "App Library" search field at the top of the page; tapping it opens the list. */
class LibrarySearchBar(context: Context) : View(context) {
    private val label = context.getString(R.string.library_title)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f)
        strokeCap = Paint.Cap.ROUND
    }

    init {
        background = GlassDrawable(this, GlassStyle.PILL)
        isClickable = true
        contentDescription = label
    }

    override fun onDraw(canvas: Canvas) {
        val color = Color.WHITE.withAlpha(0.85f)
        textPaint.color = color
        iconPaint.color = color
        val r = context.dp(6.5f)
        val gap = context.dp(8f)
        val total = r * 2 + gap + textPaint.measureText(label)
        val left = (width - total) / 2f
        val cy = height / 2f
        drawMagnifier(canvas, left + r, cy - context.dp(1f), r, iconPaint)
        canvas.drawText(label, left + r * 2 + gap, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }
}

/** A search glyph: a ring with a short handle to the lower right. */
internal fun drawMagnifier(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
    canvas.drawCircle(cx, cy, r, paint)
    canvas.drawLine(cx + r * 0.72f, cy + r * 0.72f, cx + r * 1.35f, cy + r * 1.35f, paint)
}
