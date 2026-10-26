package com.pixelgd.glassgate.edit

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.FrameLayout
import com.pixelgd.glassgate.DismissibleOverlay
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.LauncherActivity
import com.pixelgd.glassgate.folders.Folders
import com.pixelgd.glassgate.glass.GlassDrawable
import com.pixelgd.glassgate.glass.GlassStyle
import com.pixelgd.glassgate.home.AppIconView
import com.pixelgd.glassgate.home.FolderIconView
import com.pixelgd.glassgate.home.HomeViewConfig
import com.pixelgd.glassgate.home.PageGridView
import com.pixelgd.glassgate.layout.HomeItem
import com.pixelgd.glassgate.layout.HomeLayout
import com.pixelgd.glassgate.layout.HomePage
import com.pixelgd.glassgate.menu.ContextMenu
import com.pixelgd.glassgate.menu.Glyph
import com.pixelgd.glassgate.menu.MenuItem
import com.pixelgd.glassgate.menu.Removal
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.dpi
import com.pixelgd.glassgate.util.isDarkMode
import com.pixelgd.glassgate.util.lerp
import com.pixelgd.glassgate.util.thud
import com.pixelgd.glassgate.util.tick
import com.pixelgd.glassgate.util.withAlpha
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The jiggle-mode layer over the home screen (see [EditMode]). It draws the "−" badges, the
 * dragged icon, the folder-making plate and the page dots pill, and owns every touch while edit
 * mode is on: icon touches become taps or drags here, the rest is passed to the pager so pages
 * still swipe.
 */
@SuppressLint("ViewConstructor")
class EditLayer(
    private val activity: LauncherActivity,
    private val mode: EditMode,
) : FrameLayout(activity), DismissibleOverlay {

    private val launcher = Launcher.of(activity)
    private val home = activity.home
    private val dark = activity.isDarkMode

    // ---- what's on screen ----

    /** One app or folder icon on a page or in the dock. */
    private class Icon(
        val view: View,
        val size: Int,
        val item: HomeItem?,
        /** App key for apps (pages and dock), null for folders. */
        val key: String?,
        val grid: PageGridView?,
    ) {
        val inDock get() = grid == null
        /** Untransformed icon square in layer coordinates, filled by [locate]. */
        val rect = RectF()
        /** The whole view (icon and label), for hit testing. */
        val bounds = RectF()
    }

    private val loc = IntArray(2)
    private val ploc = IntArray(2)

    private fun icons(): List<Icon> {
        val out = ArrayList<Icon>()
        val pages = home.pages
        for (i in 0 until pages.childCount) {
            val grid = pages.getChildAt(i) as? PageGridView ?: continue
            for (j in 0 until grid.childCount) {
                val v = grid.getChildAt(j)
                val item = (v.layoutParams as? PageGridView.LayoutParams)?.item ?: continue
                when (v) {
                    is AppIconView -> out += Icon(v, v.iconSizePx, item, (item as? HomeItem.App)?.key, grid)
                    is FolderIconView -> out += Icon(v, v.iconSizePx, item, null, grid)
                }
            }
        }
        if (home.dock.visibility == VISIBLE) {
            for (j in 0 until home.dock.childCount) {
                val v = home.dock.getChildAt(j) as? AppIconView ?: continue
                out += Icon(v, v.iconSizePx, null, v.app?.key ?: continue, null)
            }
        }
        for (ic in out) locate(ic)
        return out
    }

    private fun locate(ic: Icon) {
        getLocationInWindow(loc)
        (ic.view.parent as View).getLocationInWindow(ploc)
        val x = ploc[0] - loc[0] + ic.view.left + (ic.view.width - ic.size) / 2f
        val y = (ploc[1] - loc[1] + ic.view.top).toFloat()
        ic.rect.set(x, y, x + ic.size, y + ic.size)
        val left = (ploc[0] - loc[0] + ic.view.left).toFloat()
        ic.bounds.set(left, y, left + ic.view.width, y + ic.view.height)
    }

    /** A view's position in this layer, ignoring its own transform. */
    private fun originOf(v: View, out: FloatArray) {
        getLocationInWindow(loc)
        (v.parent as View).getLocationInWindow(ploc)
        out[0] = (ploc[0] - loc[0] + v.left).toFloat()
        out[1] = (ploc[1] - loc[1] + v.top).toFloat()
    }

    private fun viewRect(v: View, out: RectF) {
        getLocationInWindow(loc)
        v.getLocationInWindow(ploc)
        out.set((ploc[0] - loc[0]).toFloat(), (ploc[1] - loc[1]).toFloat(),
            (ploc[0] - loc[0] + v.width).toFloat(), (ploc[1] - loc[1] + v.height).toFloat())
    }

    // ---- appearance: jiggle, badges, buttons ----

    private var shown = 0f
    private val appear = Spring(Spring.STIFF_UI, Spring.DAMP_UI) { shown = it; onAppear(it) }
    private var leaving = false

    private val badgeR = activity.dp(11f)
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) Color.rgb(86, 86, 92) else Color.rgb(236, 236, 241)
        setShadowLayer(activity.dp(3f), 0f, activity.dp(1f), Color.argb(70, 0, 0, 0))
    }
    private val badgeMinus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) Color.WHITE else Color.rgb(30, 30, 34)
        strokeWidth = activity.dp(2.2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val editButton = GlassButton(activity, "Edit")
    private val doneButton = GlassButton(activity, "Done")
    private val dotsPill = DotsPill()

    private val startTime = SystemClock.uptimeMillis()
    private val origin = FloatArray(2)

    init {
        setWillNotDraw(false)
        clipChildren = false
        addView(editButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(doneButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(dotsPill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        doneButton.setOnClickListener { dismiss() }
        editButton.setOnClickListener { showEditMenu() }
        dotsPill.setOnClickListener { PageOverview.open(activity) }
        onAppear(0f)
    }

    private fun onAppear(t: Float) {
        val a = t.coerceIn(0f, 1f)
        editButton.alpha = a
        doneButton.alpha = a
        dotsPill.alpha = a
        home.searchPill.alpha = 1f - a
        invalidate()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val insets = rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val top = (insets?.top ?: 0) + context.dpi(6f)
        val side = context.dpi(16f)
        editButton.layout(side, top, side + editButton.measuredWidth, top + editButton.measuredHeight)
        doneButton.layout(r - l - side - doneButton.measuredWidth, top, r - l - side, top + doneButton.measuredHeight)
        // The dots pill takes the Search pill's place.
        viewRect(home.searchPill, tmpRect)
        val pw = dotsPill.measuredWidth
        val ph = dotsPill.measuredHeight
        val cx = tmpRect.centerX().toInt()
        val cy = tmpRect.centerY().toInt()
        dotsPill.layout(cx - pw / 2, cy - ph / 2, cx - pw / 2 + pw, cy - ph / 2 + ph)
    }

    private val tmpRect = RectF()

    private fun showEditMenu() {
        val items = ArrayList<MenuItem>()
        mode.onAddWidget?.let { add -> items += MenuItem("Add Widget", Glyph.ADD_WIDGET) { add() } }
        items += MenuItem("Customize", Glyph.CUSTOMIZE) { CustomizeSheet.open(activity) }
        items += MenuItem("Edit Pages", Glyph.PAGES) { PageOverview.open(activity) }
        ContextMenu.showActions(activity, editButton, listOf(items))
    }

    // ---- frame loop ----

    private val ticker = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isAttachedToWindow) return
            onFrame()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private var frameIcons: List<Icon> = emptyList()

    private fun onFrame() {
        val now = SystemClock.uptimeMillis()
        val icons = icons()
        frameIcons = icons
        val ownsAlpha = activity.overlay.let { it.getChildAt(it.childCount - 1) === this }
        val t = (now - startTime) / 1000.0
        for (ic in icons) {
            val v = ic.view
            v.pivotX = v.width / 2f
            v.pivotY = ic.size / 2f
            val seed = (ic.key ?: (ic.item as? HomeItem.Folder)?.id?.toString() ?: "").hashCode()
            val period = 0.25 + (seed and 0xFF) / 255.0 * 0.05
            val phase = ((seed ushr 8) and 0xFF) / 255.0 * 2 * PI
            v.rotation = (sin(t * 2 * PI / period + phase) * JIGGLE_DEGREES * shown).toFloat()
            if (ownsAlpha) {
                v.alpha = when {
                    drag?.matches(ic) == true || landing?.matches(ic) == true -> 0f
                    mergeHides(ic) -> 0f
                    overflowItem != null && ic.item === overflowItem -> 0.35f
                    else -> 1f
                }
            }
            if (v is FolderIconView) {
                val s = if (merge?.onto === ic.item) 1.12f else 1f
                if (v.scaleX != s) { v.scaleX = s; v.scaleY = s }
            }
        }
        drag?.let { d ->
            resolveTarget(d)
            edgePaging(d, now)
        }
        landing?.let { stepLanding(it) }
        home.searchPill.alpha = 1f - shown.coerceIn(0f, 1f)
        dotsPill.update()
        invalidate()
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        val b = shown.coerceIn(0f, 1.2f)
        merge?.let { drawMergePlate(canvas, it) }
        if (b > 0.01f) {
            for (ic in frameIcons) {
                if (drag?.matches(ic) == true || landing?.matches(ic) == true || mergeHides(ic)) continue
                val v = ic.view
                originOf(v, origin)
                canvas.save()
                canvas.translate(origin[0] + v.translationX, origin[1] + v.translationY)
                canvas.rotate(v.rotation, v.pivotX, v.pivotY)
                val cx = (v.width - ic.size) / 2f + badgeR * 0.35f
                val cy = badgeR * 0.35f
                canvas.scale(b, b, cx, cy)
                canvas.drawCircle(cx, cy, badgeR, badgeFill)
                canvas.drawLine(cx - badgeR * 0.45f, cy, cx + badgeR * 0.45f, cy, badgeMinus)
                canvas.restore()
            }
        }
        drag?.let { drawFloating(canvas, it.bitmap, it.drawX, it.drawY, it.scale, it.lift) }
        landing?.let { drawFloating(canvas, it.bitmap, it.drawX, it.drawY, it.scale, it.lift) }
    }

    /** The picked-up icon, centred on ([x], [y]), with a shadow that grows with [lift]. */
    private fun drawFloating(canvas: Canvas, bitmap: Bitmap, x: Float, y: Float, scale: Float, lift: Float) {
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        shadowPaint.setShadowLayer(context.dp(16f) * lift + 0.01f, 0f, context.dp(8f) * lift, Color.BLACK.withAlpha(0.3f * lift))
        tmpRect.set(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f)
        canvas.drawBitmap(bitmap, null, tmpRect, shadowPaint)
    }

    private fun mergeHides(ic: Icon): Boolean {
        val m = merge ?: return false
        return m.onto is HomeItem.App && ic.item === m.onto
    }

    private val plate = GlassDrawable(this, GlassStyle.FOLDER_ICON)

    /** iOS shows a folder forming under the dragged icon: a glass plate with the target app in it. */
    private fun drawMergePlate(canvas: Canvas, m: Target.Merge) {
        val ic = frameIcons.firstOrNull { it.item === m.onto } ?: return
        if (m.onto !is HomeItem.App) return
        val r = ic.rect
        val grow = r.width() * 0.06f
        plate.setBounds((r.left - grow).toInt(), (r.top - grow).toInt(), (r.right + grow).toInt(), (r.bottom + grow).toInt())
        plate.draw(canvas)
        val app = launcher.apps[m.onto.key] ?: return
        val bmp = launcher.icons.cached(app, ic.size) ?: return
        val size = r.width() + grow * 2
        val pad = size * 0.14f
        val mini = (size - pad * 2 - size * 0.12f) / 3f
        val x = r.left - grow + pad
        val y = r.top - grow + pad
        canvas.drawBitmap(bmp, null, RectF(x, y, x + mini, y + mini), bitmapPaint)
    }

    // ---- touch ----

    private enum class Gesture { NONE, BADGE, ICON, PAGES, DOCK_SPACE }

    private var gesture = Gesture.NONE
    private var touched: Icon? = null
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private val slop = ViewConfiguration.get(activity).scaledTouchSlop

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (leaving) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downTime = ev.eventTime; moved = false
                if (drag != null || landing != null) { gesture = Gesture.NONE; return true }
                val icons = icons()
                val badge = icons.firstOrNull { badgeHit(it, ev.x, ev.y) }
                // The whole view, label included, so no touch on an icon ever reaches it (and launches).
                val icon = icons.firstOrNull { it.bounds.contains(ev.x, ev.y) }
                touched = badge ?: icon
                gesture = when {
                    badge != null -> Gesture.BADGE
                    icon != null -> Gesture.ICON
                    inDock(ev.y) -> Gesture.DOCK_SPACE
                    else -> Gesture.PAGES
                }
                if (gesture == Gesture.PAGES) forwardToPages(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(ev.x - downX, ev.y - downY) > slop) moved = true
                when (gesture) {
                    Gesture.PAGES -> forwardToPages(ev)
                    Gesture.ICON -> if (moved && !draggingFromLayer) {
                        val ic = touched
                        if (ic != null && beginDragAt(ic, downX, downY)) draggingFromLayer = true else gesture = Gesture.NONE
                    }
                    else -> Unit
                }
                if (draggingFromLayer) dragToLocal(ev.x, ev.y)
            }
            MotionEvent.ACTION_UP -> {
                val tap = !moved && ev.eventTime - downTime < ViewConfiguration.getLongPressTimeout()
                when {
                    draggingFromLayer -> { draggingFromLayer = false; endDrag(cancel = false) }
                    gesture == Gesture.BADGE && tap -> touched?.let { onBadge(it) }
                    gesture == Gesture.ICON && tap -> touched?.let { onIconTap(it) }
                    gesture == Gesture.PAGES -> {
                        // A vertical swipe would open Spotlight; jiggle mode has none, so cancel it.
                        val vertical = abs(ev.y - downY) > abs(ev.x - downX)
                        forwardToPages(ev, if (vertical) MotionEvent.ACTION_CANCEL else null)
                        if (tap) dismiss()
                    }
                    gesture == Gesture.DOCK_SPACE && tap -> dismiss()
                }
                gesture = Gesture.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (draggingFromLayer) { draggingFromLayer = false; endDrag(cancel = true) }
                if (gesture == Gesture.PAGES) forwardToPages(ev)
                gesture = Gesture.NONE
            }
        }
        return true
    }

    private var draggingFromLayer = false

    private fun badgeHit(ic: Icon, x: Float, y: Float): Boolean {
        val cx = ic.rect.left + badgeR * 0.35f
        val cy = ic.rect.top + badgeR * 0.35f
        return hypot(x - cx, y - cy) < badgeR * 1.6f
    }

    private fun inDock(y: Float): Boolean {
        if (home.dock.visibility != VISIBLE) return false
        viewRect(home.dock, tmpRect)
        return y >= tmpRect.top
    }

    private fun forwardToPages(ev: MotionEvent, overrideAction: Int? = null) {
        val pages = home.pages
        getLocationInWindow(loc)
        pages.getLocationInWindow(ploc)
        val e = MotionEvent.obtain(ev)
        overrideAction?.let { e.action = it }
        e.offsetLocation((loc[0] - ploc[0]).toFloat(), (loc[1] - ploc[1]).toFloat())
        pages.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun onBadge(ic: Icon) {
        when (val item = ic.item) {
            is HomeItem.Folder -> Removal.confirmFolder(activity, item)
            else -> {
                val app = ic.key?.let { launcher.apps[it] } ?: return
                Removal.confirmApp(activity, app, item as? HomeItem.App)
            }
        }
    }

    private fun onIconTap(ic: Icon) {
        val f = ic.item as? HomeItem.Folder ?: return
        activity.openFolder(f, ic.view)
    }

    // ---- dragging ----

    private sealed class Target {
        data class Cell(val grid: PageGridView, val cx: Int, val cy: Int) : Target()
        data class Merge(val grid: PageGridView, val onto: HomeItem) : Target()
        data class Dock(val index: Int) : Target()
        object None : Target()
    }

    private inner class Drag(
        val bitmap: Bitmap,
        /** The dragged page item, or null when it comes from the dock. */
        val item: HomeItem?,
        val key: String?,
        val fromDock: Boolean,
        val grabX: Float,
        val grabY: Float,
    ) {
        /** Finger position in layer coordinates. */
        var fingerX = 0f
        var fingerY = 0f
        var drawX = 0f
        var drawY = 0f
        var scale = 1f
        var lift = 0f
        val isApp get() = key != null

        val grow = Spring(Spring.STIFF_UI, Spring.DAMP_UI) { v -> scale = 1f + 0.12f * v; lift = v.coerceIn(0f, 1f) }

        /** Centre of the dragged icon (not the finger), which picks the target cell. */
        val centerX get() = fingerX - grabX + bitmap.width / 2f
        val centerY get() = fingerY - grabY + bitmap.height / 2f

        fun matches(ic: Icon): Boolean =
            if (fromDock) ic.inDock && ic.key == key else item != null && ic.item === item
    }

    /** The drop animation: flies the dragged bitmap into wherever the icon landed. */
    private inner class Landing(
        val bitmap: Bitmap,
        val startX: Float,
        val startY: Float,
        val startScale: Float,
        /** What it landed as: a page item, or a dock key. */
        val item: HomeItem?,
        val dockKey: String?,
        /** True when it went into a folder, so it shrinks into the folder icon. */
        val intoFolder: Boolean,
    ) {
        var drawX = startX
        var drawY = startY
        var scale = startScale
        var lift = 1f
        var t = 0f
        val spring = Spring(Spring.STIFF_UI, 0.9f) { v -> t = v }

        fun matches(ic: Icon): Boolean =
            if (dockKey != null) ic.inDock && ic.key == dockKey else item != null && ic.item === item
    }

    private var drag: Drag? = null
    private var landing: Landing? = null
    private var pending: Target = Target.None
    private var pendingSince = 0L
    private var applied: Target = Target.None
    private var merge: Target.Merge? = null
    private var overflowItem: HomeItem? = null
    private var tempPage: PageGridView? = null
    private var edgeSince = 0L
    private val offsets = WeakHashMap<View, Pair<Spring, Spring>>()

    /** Starts dragging [source] with the finger at raw screen ([rawX], [rawY]). */
    fun beginDrag(source: View, rawX: Float, rawY: Float): Boolean {
        val ic = icons().firstOrNull { it.view === source } ?: return false
        getLocationOnScreen(loc)
        val x = rawX - loc[0]
        val y = rawY - loc[1]
        if (!beginDragAt(ic, x, y)) return false
        dragToLocal(x, y)
        return true
    }

    private fun beginDragAt(ic: Icon, x: Float, y: Float): Boolean {
        if (drag != null || landing != null) return false
        val bmp = ContextMenu.snapshot(ic.view) ?: return false
        val d = Drag(bmp, ic.item, ic.key, ic.inDock, x - ic.rect.left, y - ic.rect.top)
        d.fingerX = x
        d.fingerY = y
        drag = d
        d.grow.animateTo(1f, 0f)
        pending = Target.None
        applied = Target.None
        ic.view.alpha = 0f
        thud()
        return true
    }

    /** Raw screen coordinates, from a gesture another view owns (see [EditMode.continueDrag]). */
    fun dragTo(rawX: Float, rawY: Float) {
        getLocationOnScreen(loc)
        dragToLocal(rawX - loc[0], rawY - loc[1])
    }

    private fun dragToLocal(x: Float, y: Float) {
        val d = drag ?: return
        d.fingerX = x
        d.fingerY = y
        d.drawX = d.centerX
        d.drawY = d.centerY
        resolveTarget(d)
        invalidate()
    }

    private fun resolveTarget(d: Drag) {
        val t = findTarget(d)
        if (t != pending) {
            pending = t
            pendingSince = SystemClock.uptimeMillis()
        }
        val wait = when (pending) {
            is Target.Merge -> MERGE_DWELL_MS
            is Target.Cell -> REFLOW_DWELL_MS
            else -> 0L
        }
        if (pending != applied && SystemClock.uptimeMillis() - pendingSince >= wait) {
            applied = pending
            preview(d, applied)
            if (applied is Target.Merge || applied is Target.Dock) tick()
        }
    }

    private fun findTarget(d: Drag): Target {
        val cx = d.centerX
        val cy = d.centerY
        val layout = launcher.layout.layout
        if (inDock(cy)) {
            val others = dockOthers(d)
            if (!d.isApp || others.size >= HomeLayout.DOCK_MAX) return Target.None
            viewRect(home.dock, tmpRect)
            val n = others.size + 1
            var best = 0
            var bestDist = Float.MAX_VALUE
            for (i in 0 until n) {
                val c = tmpRect.left + Reflow.dockCenter(i, n, home.dock.width, dockPad, HomeViewConfig.DOCK_SLOTS)
                val dist = abs(cx - c)
                if (dist < bestDist) { bestDist = dist; best = i }
            }
            return Target.Dock(best)
        }
        val grid = home.pages.pageAt(home.pages.currentPage) as? PageGridView ?: return Target.None
        val page = grid.page ?: return Target.None
        // Use the settled position of the current page, so a page flip in progress is ignored.
        getLocationInWindow(loc)
        home.pages.getLocationInWindow(ploc)
        val px = cx - (ploc[0] - loc[0])
        val py = cy - (ploc[1] - loc[1])
        val cell = grid.cellAt(px, py) ?: return Target.None
        val occupant = page.items.firstOrNull { it !== d.item && it.covers(cell.first, cell.second) }
        if (occupant is HomeItem.Widget) return Target.None
        if (occupant != null && d.isApp) {
            val v = viewFor(grid, occupant)
            if (v != null) {
                val size = (v as? AppIconView)?.iconSizePx ?: (v as? FolderIconView)?.iconSizePx ?: 0
                val ox = v.left + v.width / 2f
                val oy = v.top + size / 2f
                if (hypot(px - ox, py - oy) < size * MERGE_RADIUS) {
                    return Target.Merge(grid, occupant)
                }
            }
        }
        return Target.Cell(grid, cell.first, cell.second)
    }

    private val dockPad = activity.dpi(14f)

    private fun viewFor(grid: PageGridView, item: HomeItem): View? {
        for (i in 0 until grid.childCount) {
            val c = grid.getChildAt(i)
            if ((c.layoutParams as? PageGridView.LayoutParams)?.item === item) return c
        }
        return null
    }

    private fun dockOthers(d: Drag): List<View> {
        val out = ArrayList<View>()
        for (i in 0 until home.dock.childCount) {
            val v = home.dock.getChildAt(i) as? AppIconView ?: continue
            if (d.fromDock && v.app?.key == d.key) continue
            out += v
        }
        return out
    }

    /** Moves icons to where they would end up if the drag dropped on [t] now. */
    private fun preview(d: Drag, t: Target) {
        val want = HashMap<View, Pair<Float, Float>>()
        overflowItem = null
        merge = t as? Target.Merge

        if (t is Target.Cell) {
            val page = t.grid.page
            val push = page?.let { Reflow.push(it, t.grid.cols, t.grid.rows, t.cx, t.cy, d.item) }
            if (push != null) {
                for ((item, cell) in push.moves) {
                    val v = viewFor(t.grid, item) ?: continue
                    want[v] = (cell.first - item.x) * t.grid.cellWidth.toFloat() to (cell.second - item.y) * t.grid.cellHeight.toFloat()
                }
                overflowItem = push.overflow
            }
        }

        // The dock makes room for, or closes up after, the dragged icon.
        val others = dockOthers(d)
        val gapAt = (t as? Target.Dock)?.index
        if (d.fromDock || gapAt != null) {
            val n = others.size + if (gapAt != null) 1 else 0
            val count = home.dock.childCount
            others.forEachIndexed { i, v ->
                val newIndex = if (gapAt != null && i >= gapAt) i + 1 else i
                val from = Reflow.dockCenter(home.dock.indexOfChild(v), count, home.dock.width, dockPad, HomeViewConfig.DOCK_SLOTS)
                val to = Reflow.dockCenter(newIndex, n, home.dock.width, dockPad, HomeViewConfig.DOCK_SLOTS)
                want[v] = (to - from).toFloat() to 0f
            }
        }

        for (ic in icons()) {
            val (dx, dy) = want[ic.view] ?: (0f to 0f)
            offsetSprings(ic.view).let { (sx, sy) -> sx.animateTo(dx); sy.animateTo(dy) }
        }
    }

    private fun offsetSprings(v: View) = offsets.getOrPut(v) {
        Spring(Spring.STIFF_UI, Spring.DAMP_UI) { v.translationX = it }.also { it.snapTo(v.translationX) } to
            Spring(Spring.STIFF_UI, Spring.DAMP_UI) { v.translationY = it }.also { it.snapTo(v.translationY) }
    }

    private fun relaxPreview() {
        overflowItem = null
        merge = null
        for ((sx, sy) in offsets.values) { sx.animateTo(0f); sy.animateTo(0f) }
    }

    private fun clearPreview() {
        overflowItem = null
        merge = null
        for ((v, s) in offsets.entries.toList()) {
            s.first.cancel(); s.second.cancel()
            v.translationX = 0f; v.translationY = 0f
        }
        offsets.clear()
    }

    /** Hold the icon at a screen edge to turn the page; past the last page makes a new one. */
    private fun edgePaging(d: Drag, now: Long) {
        val edge = context.dp(26f)
        val pages = home.pages
        val dir = when {
            inDock(d.centerY) -> 0
            d.fingerX < edge -> -1
            d.fingerX > width - edge -> 1
            else -> 0
        }
        if (dir == 0) { edgeSince = 0L; return }
        if (edgeSince == 0L) { edgeSince = now; return }
        if (now - edgeSince < EDGE_FLIP_MS) return
        edgeSince = now + EDGE_REPEAT_MS - EDGE_FLIP_MS
        val next = pages.currentPage + dir
        if (next < 0) return
        val nextGrid = pages.pageAt(next) as? PageGridView
        if (nextGrid == null && dir > 0) {
            val cur = pages.pageAt(pages.currentPage) as? PageGridView ?: return
            // A new page only if this one has something other than the dragged icon.
            if (tempPage != null || cur.page?.items.orEmpty().none { it !== d.item }) return
            val layout = launcher.layout.layout
            val grid = PageGridView(activity, activity, layout.cols, layout.rows).apply {
                setPadding(cur.paddingLeft, cur.paddingTop, cur.paddingRight, cur.paddingBottom)
                bind(HomePage(), cur.childAt0IconSize(), launcher.prefs.showLabels)
            }
            tempPage = grid
            pages.addView(grid, next)
        }
        pages.snapToPage(next)
        tick()
        pending = Target.None
        applied = Target.None
        preview(d, Target.None)
    }

    private fun PageGridView.childAt0IconSize(): Int =
        (0 until childCount).firstNotNullOfOrNull { (getChildAt(it) as? AppIconView)?.iconSizePx }
            ?: context.dpi(launcher.prefs.iconSizeDp.toFloat())

    /** Drops (or with [cancel], returns) the dragged icon. */
    fun endDrag(cancel: Boolean) {
        val d = drag ?: return
        drag = null
        var t = if (cancel) Target.None else pending
        // A merge only counts once the dwell has passed; before that it's a drop on the cell.
        if (t is Target.Merge && applied != t) t = Target.Cell(t.grid, t.onto.x, t.onto.y)
        if (t is Target.Cell && t.grid.page?.let { Reflow.push(it, t.grid.cols, t.grid.rows, t.cx, t.cy, d.item) } == null) {
            t = Target.None
        }
        val temp = tempPage
        tempPage = null
        // Dropped nowhere: everything slides back. Otherwise the rebuild puts icons in place.
        if (t is Target.None) relaxPreview() else clearPreview()

        var landedItem: HomeItem? = d.item
        var landedKey: String? = if (d.fromDock) d.key else null
        var intoFolder = false

        val target = t
        if (target !is Target.None) {
            launcher.layout.edit { l ->
                val moving: HomeItem = d.item?.also { item -> for (p in l.pages) p.items.removeAll { it === item } }
                    ?: HomeItem.App(d.key!!).also { l.dock.remove(d.key) }
                landedItem = null
                landedKey = null
                when (target) {
                    is Target.Cell -> {
                        val page = target.grid.page!!
                        if (l.pages.none { it === page }) l.pages += page
                        val push = Reflow.push(page, l.cols, l.rows, target.cx, target.cy, null)!!
                        for ((item, cell) in push.moves) { item.x = cell.first; item.y = cell.second }
                        push.overflow?.let { o ->
                            page.items.remove(o)
                            l.placeFirstFree(o, l.pages.indexOf(page) + 1)
                        }
                        moving.x = target.cx
                        moving.y = target.cy
                        page.items += moving
                        landedItem = moving
                    }
                    is Target.Dock -> {
                        val key = (moving as HomeItem.App).key
                        l.dock.remove(key)
                        l.dock.add(target.index.coerceIn(0, l.dock.size), key)
                        landedKey = key
                    }
                    is Target.Merge -> {
                        val key = (moving as HomeItem.App).key
                        intoFolder = true
                        landedItem = when (val onto = target.onto) {
                            is HomeItem.App -> Folders.create(l, onto, key, launcher.layout.newFolderId(),
                                Folders.suggestName(listOfNotNull(launcher.apps[onto.key], launcher.apps[key])))
                            is HomeItem.Folder -> onto.also { Folders.add(l, it, key) }
                            else -> null
                        }
                        if (landedItem == null) {
                            intoFolder = false
                            l.placeFirstFree(moving, l.pages.indexOfFirst { it === target.grid.page }.coerceAtLeast(0))
                            landedItem = moving
                        }
                    }
                    Target.None -> Unit
                }
            }
        } else if (temp != null) {
            home.pages.removeView(temp)
            home.pages.snapToPage(home.pages.currentPage)
        }

        val l = Landing(d.bitmap, d.drawX, d.drawY, d.scale, landedItem, landedKey, intoFolder)
        landing = l
        l.spring.animateTo(1f, 0f) {
            if (landing === l) landing = null
            invalidate()
        }
        invalidate()
    }

    private fun stepLanding(l: Landing) {
        val ic = frameIcons.firstOrNull { l.matches(it) }
        if (ic == null) {
            // Nowhere visible to land (e.g. pushed to another page): just fade out.
            l.lift = 1f - l.t
            l.scale = l.startScale * (1f - 0.4f * l.t)
            return
        }
        val v = ic.view
        val tx = ic.rect.centerX() + v.translationX
        val ty = ic.rect.centerY() + v.translationY
        l.drawX = lerp(l.startX, tx, l.t)
        l.drawY = lerp(l.startY, ty, l.t)
        val end = if (l.intoFolder) 0.3f else 1f
        l.scale = lerp(l.startScale, end, l.t)
        l.lift = 1f - l.t
    }

    // ---- lifecycle ----

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        appear.animateTo(1f, 0f)
        Choreographer.getInstance().postFrameCallback(ticker)
        home.thud()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(ticker)
        appear.cancel()
        drag = null
        landing = null
        tempPage?.let { home.pages.removeView(it) }
        tempPage = null
        clearPreview()
        for (ic in icons()) {
            ic.view.rotation = 0f
            ic.view.alpha = 1f
            ic.view.scaleX = 1f
            ic.view.scaleY = 1f
            ic.view.resetPivot()
        }
        home.searchPill.alpha = 1f
        mode.onLayerGone(this)
        super.onDetachedFromWindow()
    }

    override fun dismiss() {
        if (leaving) { removeNow(); return }
        leaving = true
        mode.onLayerGone(this)
        if (drag != null) endDrag(cancel = true)
        appear.animateTo(0f) { removeNow() }
    }

    private fun removeNow() {
        (parent as? android.view.ViewGroup)?.removeView(this)
    }

    /** The glass dots pill that replaces Search in jiggle mode; tap it to edit pages. */
    private inner class DotsPill : View(activity) {
        private val glass = GlassDrawable(this, GlassStyle.PILL)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dot = context.dp(7f)
        private val gap = context.dp(9f)

        init {
            background = glass
            isClickable = true
            contentDescription = "Edit pages"
        }

        private var measuredCount = -1

        private fun count(): Int {
            var n = 0
            for (i in 0 until home.pages.childCount) if (home.pages.getChildAt(i) is PageGridView) n++
            return n.coerceAtLeast(1)
        }

        fun update() {
            if (count() != measuredCount) requestLayout()
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            measuredCount = count()
            // Never narrower than three dots, so a one-page home still gets a tappable pill.
            val n = measuredCount.coerceAtLeast(3)
            val w = context.dp(18f) * 2 + n * (dot + gap) - gap
            setMeasuredDimension(w.toInt(), context.dpi(34f))
        }

        override fun onDraw(canvas: Canvas) {
            val n = count()
            val pos = if (home.pages.width == 0) 0f else home.pages.scrollX / home.pages.width.toFloat()
            val total = n * dot + (n - 1) * gap
            var x = (width - total) / 2f + dot / 2f
            for (i in 0 until n) {
                val near = (1f - abs(pos - i)).coerceIn(0f, 1f)
                paint.color = Color.WHITE.withAlpha(0.4f + 0.6f * near)
                canvas.drawCircle(x, height / 2f, dot / 2f, paint)
                x += dot + gap
            }
        }
    }

    private companion object {
        const val JIGGLE_DEGREES = 1.6
        const val MERGE_DWELL_MS = 320L
        const val REFLOW_DWELL_MS = 140L
        const val EDGE_FLIP_MS = 520L
        const val EDGE_REPEAT_MS = 900L
        /** How close (in icon sizes) the dragged icon's centre must come to make a folder. */
        const val MERGE_RADIUS = 0.36f
    }
}
