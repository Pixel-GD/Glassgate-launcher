package com.pixelgd.glassgate.edit

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import com.pixelgd.glassgate.DismissibleOverlay
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.LauncherActivity
import com.pixelgd.glassgate.glass.Squircle
import com.pixelgd.glassgate.home.PageGridView
import com.pixelgd.glassgate.layout.HomeItem
import com.pixelgd.glassgate.layout.HomePage
import com.pixelgd.glassgate.menu.GlassAlert
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.dpi
import com.pixelgd.glassgate.util.lerp
import com.pixelgd.glassgate.util.thud
import com.pixelgd.glassgate.util.tick
import com.pixelgd.glassgate.util.withAlpha
import kotlin.math.hypot

/**
 * iOS "Edit Pages", opened from the page dots in jiggle mode: every home page as a thumbnail with
 * a check circle. Untick to hide a page, tap a hidden page's "−" to delete it (its apps stay in
 * the App Library), and long-press and drag to reorder. Changes are saved on Done.
 */
@SuppressLint("ViewConstructor")
class PageOverview private constructor(private val activity: LauncherActivity) : ViewGroup(activity), DismissibleOverlay {

    private val launcher = Launcher.of(activity)
    private val done = GlassButton(activity, "Done")
    private val grid = Grid()
    private var shown = 0f
    private val spring = Spring(Spring.STIFF_UI, Spring.DAMP_UI) { apply(it) }
    private var leaving = false

    private class Entry(val page: HomePage, var hidden: Boolean) {
        var thumb: Bitmap? = null
        val sx = Spring(Spring.STIFF_UI, Spring.DAMP_UI) { x = it }
        val sy = Spring(Spring.STIFF_UI, Spring.DAMP_UI) { y = it }
        var x = 0f
        var y = 0f
        var placed = false
    }

    private val entries = launcher.layout.layout.pages.mapTo(ArrayList()) { Entry(it, it.hidden) }

    init {
        isClickable = true
        addView(grid)
        addView(done)
        done.setOnClickListener { dismiss() }
        apply(0f)
    }

    private fun apply(t: Float) {
        shown = t
        val a = t.coerceIn(0f, 1f)
        setBackgroundColor(Color.BLACK.withAlpha(0.25f * a))
        val blur = context.dp(30f) * a
        activity.home.setRenderEffect(if (blur > 0.5f) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) else null)
        grid.alpha = a
        val s = lerp(1.08f, 1f, t)
        grid.scaleX = s
        grid.scaleY = s
        done.alpha = a
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        done.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        grid.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val insets = rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val top = (insets?.top ?: 0) + context.dpi(6f)
        val side = context.dpi(16f)
        done.layout(r - l - side - done.measuredWidth, top, r - l - side, top + done.measuredHeight)
        grid.layout(0, 0, r - l, b - t)
        grid.pivotX = (r - l) / 2f
        grid.pivotY = (b - t) / 2f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spring.animateTo(1f, 0f)
        snapshotAll()
        // Icons on pages that weren't on screen render in the background; pick them up.
        postDelayed({ snapshotAll() }, 350)
        postDelayed({ snapshotAll() }, 1000)
    }

    override fun onDetachedFromWindow() {
        spring.cancel()
        activity.home.setRenderEffect(null)
        super.onDetachedFromWindow()
    }

    override fun dismiss() {
        if (leaving) { removeNow(); return }
        leaving = true
        commit()
        spring.animateTo(0f) { removeNow() }
    }

    private fun removeNow() {
        (parent as? ViewGroup)?.removeView(this)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    /** Writes the new order, hidden flags and deletions back to the layout. */
    private fun commit() {
        val kept = entries.map { it.page }
        val changed = kept != launcher.layout.layout.pages || entries.any { it.hidden != it.page.hidden }
        if (!changed) return
        launcher.layout.edit { l ->
            for (p in l.pages) if (kept.none { it === p }) {
                for (item in p.items) when (item) {
                    is HomeItem.App -> l.libraryOnly += item.key
                    is HomeItem.Folder -> l.libraryOnly += item.keys
                    is HomeItem.Widget -> Unit
                }
            }
            l.pages.clear()
            for (e in entries) { e.page.hidden = e.hidden; l.pages += e.page }
            if (l.pages.isNotEmpty() && l.pages.all { it.hidden }) l.pages[0].hidden = false
        }
    }

    // ---- thumbnails ----

    private fun snapshotAll() {
        val pages = activity.home.pages
        val w = pages.width
        val h = pages.height
        if (w == 0 || h == 0) return
        val layout = launcher.layout.layout
        val iconPx = context.dpi(launcher.prefs.iconSizeDp.toFloat())
        val ref = (0 until pages.childCount).firstNotNullOfOrNull { pages.getChildAt(it) as? PageGridView }
        for (e in entries) {
            val v = PageGridView(activity, activity, layout.cols, layout.rows)
            if (ref != null) v.setPadding(ref.paddingLeft, ref.paddingTop, ref.paddingRight, ref.paddingBottom)
            v.bind(e.page, iconPx, launcher.prefs.showLabels)
            v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            val scale = 0.5f
            val bmp = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(scale, scale)
            v.draw(c)
            e.thumb = bmp
        }
        grid.invalidate()
    }

    /** The thumbnails, check circles and delete badges, with drag-to-reorder. */
    private inner class Grid : View(activity) {
        private val rect = RectF()
        private val path = Path()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val gap = context.dp(22f)
        private val checkR = context.dp(12f)
        private val badgeR = context.dp(12f)
        private var thumbW = 0f
        private var thumbH = 0f
        private var cols = 2

        private var downX = 0f
        private var downY = 0f
        private var downEntry: Entry? = null
        private var dragging: Entry? = null
        private var grabX = 0f
        private var grabY = 0f
        private var moved = false
        private val slop = ViewConfiguration.get(activity).scaledTouchSlop
        private val longPress = Runnable { downEntry?.let { startDrag(it) } }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            relayout(animate = false)
        }

        private fun relayout(animate: Boolean) {
            val pages = activity.home.pages
            val aspect = if (pages.width > 0) pages.height / pages.width.toFloat() else 1.8f
            val n = entries.size
            cols = if (n <= 4) 2 else 3
            val rows = (n + cols - 1) / cols
            val side = context.dp(36f)
            val topSpace = context.dp(110f)
            val below = checkR * 2 + context.dp(14f)
            thumbW = (width - side * 2 - gap * (cols - 1)) / cols
            thumbH = thumbW * aspect
            // Shrink to fit the screen height when there are many pages.
            val maxH = (height - topSpace * 2 - (rows - 1) * gap - rows * below) / rows
            if (thumbH > maxH && maxH > 0) { thumbH = maxH; thumbW = thumbH / aspect }
            val totalW = cols * thumbW + (cols - 1) * gap
            val totalH = rows * (thumbH + below) + (rows - 1) * gap
            val left0 = (width - totalW) / 2f
            val top0 = (height - totalH) / 2f
            entries.forEachIndexed { i, e ->
                if (e === dragging) return@forEachIndexed
                val col = i % cols
                val row = i / cols
                // A last row with fewer pages is centred, like iOS.
                val inRow = if (row == rows - 1) n - row * cols else cols
                val rowLeft = left0 + (totalW - (inRow * thumbW + (inRow - 1) * gap)) / 2f
                val x = rowLeft + col * (thumbW + gap)
                val y = top0 + row * (thumbH + below + gap)
                if (animate && e.placed) { e.sx.animateTo(x); e.sy.animateTo(y) } else { e.sx.snapTo(x); e.sy.snapTo(y); e.placed = true }
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            for (e in entries) if (e !== dragging) drawEntry(canvas, e, 1f)
            dragging?.let { drawEntry(canvas, it, 1.06f) }
            if (entries.any { it.sx.isRunning || it.sy.isRunning }) postInvalidateOnAnimation()
        }

        private fun drawEntry(canvas: Canvas, e: Entry, scale: Float) {
            val radius = context.dp(18f)
            canvas.save()
            canvas.scale(scale, scale, e.x + thumbW / 2, e.y + thumbH / 2)
            rect.set(e.x, e.y, e.x + thumbW, e.y + thumbH)
            Squircle.roundRect(rect, radius, path)
            val a = if (e.hidden) 0.45f else 1f
            canvas.saveLayerAlpha(rect, (a * 255).toInt())
            canvas.clipPath(path)
            val backdrop = launcher.wallpaper.current
            val pages = activity.home.pages
            if (backdrop != null) {
                canvas.save()
                canvas.translate(rect.left, rect.top)
                val root = activity.home
                canvas.scale(thumbW / pages.width.coerceAtLeast(1), thumbH / pages.height.coerceAtLeast(1))
                // The page area's slice of the full-screen wallpaper.
                val loc = IntArray(2)
                pages.getLocationInWindow(loc)
                canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
                backdrop.draw(canvas, root.width, root.height)
                canvas.restore()
            } else {
                paint.color = Color.WHITE.withAlpha(0.18f)
                canvas.drawRect(rect, paint)
            }
            e.thumb?.let { canvas.drawBitmap(it, null, rect, paint) }
            canvas.restore()
            stroke.strokeWidth = context.dp(1f)
            stroke.color = Color.WHITE.withAlpha(0.35f)
            canvas.drawPath(path, stroke)

            // Check circle under the page.
            val cx = rect.centerX()
            val cy = rect.bottom + context.dp(14f) + checkR
            if (e.hidden) {
                stroke.strokeWidth = context.dp(1.6f)
                stroke.color = Color.WHITE.withAlpha(0.9f)
                canvas.drawCircle(cx, cy, checkR, stroke)
            } else {
                paint.color = Color.WHITE
                canvas.drawCircle(cx, cy, checkR, paint)
                stroke.strokeWidth = context.dp(2.2f)
                stroke.color = Color.rgb(20, 20, 24)
                stroke.strokeCap = Paint.Cap.ROUND
                path.reset()
                path.moveTo(cx - checkR * 0.42f, cy + checkR * 0.02f)
                path.lineTo(cx - checkR * 0.1f, cy + checkR * 0.36f)
                path.lineTo(cx + checkR * 0.45f, cy - checkR * 0.32f)
                canvas.drawPath(path, stroke)
            }

            // Hidden pages can be deleted.
            if (e.hidden && e !== dragging) {
                paint.color = Color.rgb(236, 236, 241)
                canvas.drawCircle(rect.left, rect.top, badgeR, paint)
                stroke.strokeWidth = context.dp(2.2f)
                stroke.color = Color.rgb(30, 30, 34)
                canvas.drawLine(rect.left - badgeR * 0.45f, rect.top, rect.left + badgeR * 0.45f, rect.top, stroke)
            }
            canvas.restore()
        }

        private fun entryAt(x: Float, y: Float) = entries.lastOrNull {
            x >= it.x && x <= it.x + thumbW && y >= it.y && y <= it.y + thumbH + checkR * 2 + context.dp(14f)
        }

        private fun badgeAt(x: Float, y: Float) = entries.firstOrNull { it.hidden && hypot(x - it.x, y - it.y) < badgeR * 1.7f }

        private fun startDrag(e: Entry) {
            dragging = e
            grabX = downX - e.x
            grabY = downY - e.y
            e.sx.cancel(); e.sy.cancel()
            thud()
            invalidate()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; moved = false
                    downEntry = entryAt(ev.x, ev.y)
                    if (downEntry != null) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong() / 2)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(ev.x - downX, ev.y - downY) > slop) {
                        moved = true
                        if (dragging == null) removeCallbacks(longPress)
                    }
                    dragging?.let { d ->
                        d.x = ev.x - grabX
                        d.y = ev.y - grabY
                        reorder(d)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    val d = dragging
                    if (d != null) {
                        dragging = null
                        d.sx.snapTo(d.x); d.sy.snapTo(d.y)
                        relayout(animate = true)
                    } else if (!moved) {
                        val badge = badgeAt(ev.x, ev.y)
                        val e = downEntry
                        when {
                            badge != null -> confirmDelete(badge)
                            e != null -> toggle(e)
                            else -> dismiss()
                        }
                    }
                    downEntry = null
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    dragging?.let { d -> dragging = null; d.sx.snapTo(d.x); d.sy.snapTo(d.y); relayout(animate = true) }
                    downEntry = null
                }
            }
            return true
        }

        /** Moves [d] to the slot nearest its centre. */
        private fun reorder(d: Entry) {
            val cx = d.x + thumbW / 2
            val cy = d.y + thumbH / 2
            var best = entries.indexOf(d)
            var bestDist = Float.MAX_VALUE
            entries.forEachIndexed { i, e ->
                val ex = e.sx.target
                val ey = e.sy.target
                val dist = hypot(cx - (ex + thumbW / 2), cy - (ey + thumbH / 2))
                if (dist < bestDist) { bestDist = dist; best = i }
            }
            val from = entries.indexOf(d)
            if (best != from) {
                entries.removeAt(from)
                entries.add(best, d)
                tick()
                relayout(animate = true)
            }
        }

        private fun toggle(e: Entry) {
            if (!e.hidden && entries.count { !it.hidden } <= 1) return // keep one page visible
            e.hidden = !e.hidden
            tick()
            invalidate()
        }

        private fun confirmDelete(e: Entry) {
            GlassAlert.show(activity, "Remove This Page?",
                "The apps on this page will stay in your App Library.",
                GlassAlert.Button("Remove", destructive = true) {
                    entries.remove(e)
                    relayout(animate = true)
                },
                GlassAlert.Button("Cancel", cancel = true))
        }
    }

    companion object {
        fun open(activity: LauncherActivity) {
            if ((0 until activity.overlay.childCount).any { activity.overlay.getChildAt(it) is PageOverview }) return
            activity.overlay.addView(PageOverview(activity), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }
}
