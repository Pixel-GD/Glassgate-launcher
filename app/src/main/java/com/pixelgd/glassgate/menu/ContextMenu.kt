package com.pixelgd.glassgate.menu

import android.annotation.SuppressLint
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import com.pixelgd.glassgate.DismissibleOverlay
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.LauncherActivity
import com.pixelgd.glassgate.apps.AppEntry
import com.pixelgd.glassgate.folders.FolderView
import com.pixelgd.glassgate.home.AppIconView
import com.pixelgd.glassgate.home.DockView
import com.pixelgd.glassgate.home.FolderIconView
import com.pixelgd.glassgate.layout.HomeItem
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.isDarkMode
import com.pixelgd.glassgate.util.lerp
import com.pixelgd.glassgate.util.thud
import com.pixelgd.glassgate.util.withAlpha
import com.pixelgd.glassgate.widgets.WidgetPicker
import com.pixelgd.glassgate.widgets.Widgets
import kotlin.math.hypot

/**
 * The iOS long-press menu: the home screen blurs, the pressed icon lifts out of it, and a glass
 * menu of the app's shortcuts and actions grows from the icon's side.
 *
 * Like iOS, the long-press that opened it keeps working: slide onto a row and lift to pick it,
 * or drag the icon away to drop into jiggle mode carrying it ([LauncherActivity.editMode]).
 */
@SuppressLint("ViewConstructor")
class ContextMenu private constructor(
    private val activity: LauncherActivity,
    private val source: View,
    sections: List<List<MenuItem>>,
) : FrameLayout(activity), DismissibleOverlay {

    private val launcher = Launcher.of(activity)
    private val menu = GlassMenuView(activity, sections) { pick(it) }
    private val preview = Preview()
    private val iconRect = RectF()
    private val loc = IntArray(2)
    private val srcLoc = IntArray(2)

    private val openSpring = Spring(Spring.STIFF_UI, Spring.DAMP_UI) { apply(it) }
    private val closeSpring = Spring(Spring.STIFF_SNAP, Spring.DAMP_SNAP) { apply(it) }
    private var progress = 0f
    private var closing = false
    private var picked = false
    private var handedOff = false
    private var menuBelow = true
    private var afterClose: (() -> Unit)? = null

    /** Where the long-press finger was when the menu opened, in raw screen coordinates. */
    private var downRawX = Float.NaN
    private var downRawY = 0f

    init {
        isClickable = true
        clipChildren = false
        preview.bitmap = snapshot(source)
        addView(preview, LayoutParams(0, 0))
        addView(menu, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        apply(0f)
    }

    // ---- geometry ----

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        getLocationInWindow(loc)
        source.getLocationInWindow(srcLoc)
        val iconSize = iconSizeOf(source)
        if (iconSize != null) {
            val x = srcLoc[0] - loc[0] + (source.width - iconSize) / 2f
            val y = (srcLoc[1] - loc[1]).toFloat()
            iconRect.set(x, y, x + iconSize, y + iconSize)
        } else {
            val x = (srcLoc[0] - loc[0]).toFloat()
            val y = (srcLoc[1] - loc[1]).toFloat()
            iconRect.set(x, y, x + source.width, y + source.height)
        }
        preview.layout(iconRect.left.toInt(), iconRect.top.toInt(), iconRect.right.toInt(), iconRect.bottom.toInt())

        val insets = rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())
        val margin = context.dp(16f)
        val gap = context.dp(12f)
        val mw = menu.measuredWidth
        val mh = menu.measuredHeight
        val w = r - l
        val h = b - t
        val bottomLimit = h - (insets?.bottom ?: 0) - margin
        menuBelow = iconRect.bottom + gap + mh <= bottomLimit
        val top = if (menuBelow) iconRect.bottom + gap else maxOf((insets?.top ?: 0) + margin, iconRect.top - gap - mh)
        val onLeft = iconRect.centerX() < w / 2f
        val left = (if (onLeft) iconRect.left else iconRect.right - mw).coerceIn(margin, w - margin - mw)
        menu.layout(left.toInt(), top.toInt(), left.toInt() + mw, top.toInt() + mh)
        // Grow out of the corner nearest the icon.
        menu.pivotX = if (onLeft) 0f else mw.toFloat()
        menu.pivotY = if (menuBelow) 0f else mh.toFloat()
    }

    private fun apply(t: Float) {
        progress = t
        val a = t.coerceIn(0f, 1f)
        val dark = context.isDarkMode
        setBackgroundColor(Color.BLACK.withAlpha((if (dark) 0.30f else 0.12f) * a))
        val blur = context.dp(22f) * a
        activity.home.setRenderEffect(
            if (blur > 0.5f) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) else null,
        )
        val lift = lerp(1f, 1.06f, t)
        preview.scaleX = lift
        preview.scaleY = lift
        preview.lift = a
        val s = lerp(0.55f, 1f, t)
        menu.scaleX = s
        menu.scaleY = s
        menu.alpha = a
    }

    // ---- lifecycle ----

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (preview.bitmap != null) source.alpha = 0f
        source.thud()
        openSpring.animateTo(1f, 0f)
    }

    override fun onDetachedFromWindow() {
        openSpring.cancel()
        closeSpring.cancel()
        activity.home.setRenderEffect(null)
        if (!handedOff) source.alpha = 1f
        if (source.getTag(TOUCH_TAG) == this) {
            source.setOnTouchListener(null)
            source.setTag(TOUCH_TAG, null)
        }
        super.onDetachedFromWindow()
    }

    override fun dismiss() {
        if (closing) { removeNow(); return }
        closing = true
        openSpring.cancel()
        closeSpring.snapTo(progress)
        closeSpring.animateTo(0f, openSpring.velocity) { removeNow() }
    }

    private fun removeNow() {
        (parent as? ViewGroup)?.removeView(this)
        afterClose?.let { afterClose = null; it() }
    }

    private fun pick(item: MenuItem) {
        if (closing) return
        picked = true
        if (item.afterClose) afterClose = item.action
        dismiss()
        if (!item.afterClose) item.action()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && !closing) dismiss()
        return true
    }

    // ---- the long-press gesture, which keeps going after the menu opens ----

    @SuppressLint("ClickableViewAccessibility")
    private fun followGesture() {
        // Keep the pager from turning the held finger into a page swipe.
        source.parent?.requestDisallowInterceptTouchEvent(true)
        source.setTag(TOUCH_TAG, this)
        source.setOnTouchListener { _, ev -> onSourceTouch(ev) }
    }

    private fun onSourceTouch(ev: MotionEvent): Boolean {
        if (handedOff || closing && !picked) return false
        if (downRawX.isNaN()) { downRawX = ev.rawX; downRawY = ev.rawY }
        getLocationOnScreen(loc)
        val x = ev.rawX - loc[0]
        val y = ev.rawY - loc[1]
        val mx = x - menu.left
        val my = y - menu.top
        val up = ev.actionMasked == MotionEvent.ACTION_UP
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (inMenuZone(x, y)) {
                    menu.trackFinger(mx, my, up)
                } else {
                    menu.trackFinger(-1f, -1f, false)
                    val moved = hypot(ev.rawX - downRawX, ev.rawY - downRawY)
                    if (!up && moved > context.dp(20f) && canDrag()) {
                        handOff(ev)
                        return true
                    }
                }
            }
        }
        if (up || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            source.setOnTouchListener(null)
            source.setTag(TOUCH_TAG, null)
        }
        return false
    }

    /** The menu plus the gap between it and the icon, so moving toward it never starts a drag. */
    private fun inMenuZone(x: Float, y: Float): Boolean {
        if (x < menu.left || x > menu.right) return false
        return if (menuBelow) y >= iconRect.bottom && y <= menu.bottom else y >= menu.top && y <= iconRect.top
    }

    private fun canDrag() = iconSizeOf(source) != null && activity.editMode.canDrag(source)

    private fun handOff(ev: MotionEvent) {
        handedOff = true
        source.setOnTouchListener(null)
        source.setTag(TOUCH_TAG, null)
        // The icon view saw a press; let it spring back before it's hidden.
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        source.onTouchEvent(cancel)
        cancel.recycle()
        activity.editMode.enter()
        activity.editMode.continueDrag(source, ev)
        dismiss()
    }

    /** The lifted icon, drawn over the blur with a soft shadow. */
    private inner class Preview : View(activity) {
        var bitmap: Bitmap? = null
        var lift = 0f
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val b = bitmap ?: return
            paint.setShadowLayer(context.dp(18f) * lift, 0f, context.dp(8f) * lift, Color.BLACK.withAlpha(0.28f * lift))
            canvas.drawBitmap(b, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
        }
    }

    companion object {
        private val TOUCH_TAG = "glassgate.menu.touch".hashCode()

        /** The long-press hook: builds the menu for an app icon, dock icon, folder or search row. */
        fun show(activity: LauncherActivity, item: HomeItem?, app: AppEntry?, source: View) {
            val overlay = activity.overlay
            for (i in overlay.childCount - 1 downTo 0) {
                if (overlay.getChildAt(i) is ContextMenu) overlay.removeViewAt(i)
            }
            val sections = when {
                item is HomeItem.Folder -> folderItems(activity, item, source)
                item is HomeItem.Widget -> widgetItems(activity, item)
                app != null -> appItems(activity, item as? HomeItem.App, app, source)
                else -> return
            }
            val menu = ContextMenu(activity, source, sections)
            overlay.addView(menu, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            menu.followGesture()
        }

        /** A plain glass menu of [sections] anchored to [anchor], e.g. jiggle mode's Edit button. */
        fun showActions(activity: LauncherActivity, anchor: View, sections: List<List<MenuItem>>) {
            activity.overlay.addView(ContextMenu(activity, anchor, sections),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        private fun appItems(activity: LauncherActivity, item: HomeItem.App?, app: AppEntry, source: View): List<List<MenuItem>> {
            val launcher = Launcher.of(activity)
            val onHome = item != null || source.parent is DockView
            val quick = shortcuts(activity, app).map { s ->
                MenuItem(s.shortLabel?.toString() ?: s.longLabel?.toString().orEmpty(), icon = shortcutIcon(activity, s)) {
                    launcher.apps.launchShortcut(s, source)
                }
            }
            val actions = ArrayList<MenuItem>()
            if (onHome) actions += MenuItem("Edit Home Screen", Glyph.EDIT_HOME) { activity.enterEditMode() }
            else if (app.key !in launcher.layout.layout.allKeys()) {
                actions += MenuItem("Add to Home Screen", Glyph.ADD) {
                    launcher.layout.edit { l -> l.placeFirstFree(HomeItem.App(app.key)) }
                }
            }
            val locked = app.key in launcher.prefs.lockedApps
            actions += MenuItem(if (locked) "Don't Require Face ID" else "Require Face ID", Glyph.LOCK, afterClose = true) {
                AppLocking.offer(activity, app)
            }
            actions += MenuItem("Share App", Glyph.SHARE) { shareApp(activity, app) }
            actions += MenuItem("App Info", Glyph.INFO) { launcher.apps.openAppInfo(app, source) }
            if (onHome) actions += MenuItem("Remove App", Glyph.REMOVE, destructive = true) { Removal.confirmApp(activity, app, item) }
            else if (Removal.canUninstall(app)) {
                actions += MenuItem("Delete App", Glyph.DELETE, destructive = true) { Removal.confirmApp(activity, app, null) }
            }
            return listOf(quick, actions)
        }

        private fun folderItems(activity: LauncherActivity, folder: HomeItem.Folder, source: View) = listOf(
            listOf(
                // After the menu has gone, so the folder's own blur takes over cleanly.
                MenuItem("Rename", Glyph.RENAME, afterClose = true) { FolderView.open(activity, folder, source, rename = true) },
                MenuItem("Edit Home Screen", Glyph.EDIT_HOME) { activity.enterEditMode() },
            ),
            listOf(MenuItem("Remove Folder", Glyph.REMOVE, destructive = true) { Removal.confirmFolder(activity, folder) }),
        )

        private fun widgetItems(activity: LauncherActivity, widget: HomeItem.Widget) = listOf(
            listOf(
                MenuItem("Edit Home Screen", Glyph.EDIT_HOME) { activity.enterEditMode() },
                MenuItem("Add Widget", Glyph.ADD_WIDGET, afterClose = true) { WidgetPicker.show(activity) },
            ),
            listOf(MenuItem("Remove Widget", Glyph.REMOVE, destructive = true) { Widgets.of(activity).remove(widget) }),
        )

        /** iOS "Share App": the store link through the share sheet. */
        private fun shareApp(activity: LauncherActivity, app: AppEntry) {
            val link = "https://play.google.com/store/apps/details?id=${app.packageName}"
            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, link)
                .putExtra(android.content.Intent.EXTRA_TITLE, app.label)
            runCatching { activity.startActivity(android.content.Intent.createChooser(send, null)) }
        }

        /** iOS shows at most four quick actions. */
        private fun shortcuts(activity: LauncherActivity, app: AppEntry): List<ShortcutInfo> =
            Launcher.of(activity).apps.shortcuts(app)
                .filter { it.isEnabled && (it.isDynamic || it.isDeclaredInManifest) }
                .take(4)

        private fun shortcutIcon(activity: LauncherActivity, s: ShortcutInfo): Drawable? = try {
            activity.getSystemService(LauncherApps::class.java)
                ?.getShortcutIconDrawable(s, activity.resources.displayMetrics.densityDpi)
        } catch (e: Exception) {
            null
        }

        private fun iconSizeOf(v: View): Int? = when (v) {
            is AppIconView -> v.iconSizePx
            is FolderIconView -> v.iconSizePx
            else -> null
        }

        /** The icon square of an app or folder view (no label), or null for other sources. */
        fun snapshot(v: View): Bitmap? {
            val size = iconSizeOf(v) ?: return null
            if (v is AppIconView) {
                val app = v.app ?: return null
                val launcher = Launcher.of(v.context)
                return launcher.icons.cached(app, size) ?: runCatching { launcher.icons.render(app, size) }.getOrNull()
            }
            if (v.width == 0) return null
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.translate(-(v.width - size) / 2f, 0f)
            v.draw(c)
            return bmp
        }
    }
}
