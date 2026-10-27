package com.pixelgd.glassgate.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.Prefs
import com.pixelgd.glassgate.apps.AppRepository
import com.pixelgd.glassgate.glass.Backdrop
import com.pixelgd.glassgate.glass.WallpaperSource
import com.pixelgd.glassgate.layout.HomeLayout
import com.pixelgd.glassgate.layout.LayoutStore
import com.pixelgd.glassgate.util.StatusBar
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.dpi
import com.pixelgd.glassgate.util.isDarkMode

object HomeViewConfig {
    const val DOCK_SLOTS = 4
}

/**
 * Draws the launcher's own wallpaper (when it has one) full screen, with the iOS Home Screen
 * wallpaper options: legibility blur, and dimming while the system is dark.
 */
class WallpaperView(context: Context) : View(context), WallpaperSource.Listener {
    private val launcher = Launcher.of(context)

    /** Re-reads the blur and dim settings. */
    fun applyPrefs() {
        val prefs = launcher.prefs
        val r = context.dp(36f)
        setRenderEffect(if (prefs.blurWallpaper && launcher.wallpaper.current != null)
            RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        launcher.wallpaper.addListener(this)
    }

    override fun onDetachedFromWindow() {
        launcher.wallpaper.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onBackdropChanged(backdrop: Backdrop?) = invalidate()

    override fun onDraw(canvas: Canvas) {
        launcher.wallpaper.current?.draw(canvas, width, height)
        // Also dims the system wallpaper when it shows through instead.
        if (launcher.prefs.dimWallpaperInDark && context.isDarkMode) canvas.drawColor(Color.argb(64, 0, 0, 0))
    }
}

/**
 * The home screen: pages of icons, the glass Search pill and the glass dock, over the
 * launcher's wallpaper. Rebuilds itself when apps, layout or settings change.
 */
@SuppressLint("ViewConstructor")
class HomeView(context: Context, private val actions: HomeActions) : FrameLayout(context),
    LayoutStore.Listener, AppRepository.Listener {

    private val launcher = Launcher.of(context)
    val wallpaper = WallpaperView(context)
    val pages = PagedView(context)
    val searchPill = SearchPillView(context)
    val dock = DockView(context, actions)

    private val sideMargin = context.dpi(12f)
    private val dockBottomGap = context.dpi(10f)
    private val pillGap = context.dpi(14f)
    private var insetTop = 0
    private var insetBottom = 0

    init {
        addView(wallpaper, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(pages, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(searchPill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM))
        addView(dock, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        pages.addScrollListener(searchPill)
        pages.onSwipeDown = { x -> onSwipeDown(x) }
        pages.onLongPressEmpty = { actions.enterEditMode() }
        searchPill.setOnClickListener { actions.openSpotlight() }
        searchPill.onScrub = { page -> pages.snapToPage(page) }

        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insetTop = bars.top
            insetBottom = bars.bottom
            applyInsets()
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        launcher.layout.addListener(this)
        launcher.apps.addListener(this)
        rebuild()
    }

    override fun onDetachedFromWindow() {
        launcher.layout.removeListener(this)
        launcher.apps.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onLayoutChanged(layout: HomeLayout) = rebuild()
    override fun onAppsChanged(apps: List<com.pixelgd.glassgate.apps.AppEntry>) = rebuild()

    /** Settings > Gestures > Swipe Down. iOS always opens Search. */
    private fun onSwipeDown(x: Float) {
        when (launcher.prefs.swipeDown) {
            Prefs.SwipeAction.SEARCH -> actions.openSpotlight()
            Prefs.SwipeAction.NOTIFICATIONS -> StatusBar.expandNotifications(context)
            // One UI's split panel, and iOS's Notification Center / Control Center split.
            Prefs.SwipeAction.SPLIT -> if (x < 0.5f) StatusBar.expandNotifications(context) else StatusBar.expandQuickSettings(context)
            Prefs.SwipeAction.NOTHING -> Unit
        }
    }

    /** Re-creates pages and dock from the saved layout and current settings. */
    fun rebuild() {
        val layout = launcher.layout.layout
        val prefs = launcher.prefs
        val iconPx = context.dpi(prefs.iconSizeDp.toFloat())
        val keep = pages.currentPage

        pages.removeAllViews()
        for (page in layout.pages) {
            if (page.hidden) continue
            pages.addView(PageGridView(context, actions, layout.cols, layout.rows).apply {
                setPadding(sideMargin, 0, sideMargin, 0)
                bind(page, iconPx, prefs.showLabels)
            })
        }
        if (prefs.showAppLibrary) actions.createTrailingPage()?.let { pages.addView(it) }
        pages.snapToPage(keep, animate = false)
        searchPill.dotsOnly = !prefs.showSearchPill
        searchPill.setPageCount(pages.childCount)
        wallpaper.applyPrefs()

        dock.visibility = if (prefs.showDock) VISIBLE else GONE
        dock.bind(layout.dock, iconPx)
        applyInsets()
    }

    private fun applyInsets() {
        val dockLp = dock.layoutParams as LayoutParams
        dockLp.setMargins(sideMargin, 0, sideMargin, insetBottom + dockBottomGap)
        dock.layoutParams = dockLp

        dock.measure(MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1) - sideMargin * 2, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val dockH = if (dock.visibility == VISIBLE) dock.measuredHeight + dockBottomGap else 0
        val pillLp = searchPill.layoutParams as LayoutParams
        pillLp.bottomMargin = insetBottom + dockH + pillGap
        searchPill.layoutParams = pillLp

        // Pages fill the screen so a trailing page (App Library) can too; the icon grids keep
        // clear of the status bar, pill and dock with padding instead.
        val pageTop = insetTop + context.dpi(14f)
        val pageBottom = pillLp.bottomMargin + context.dpi(34f) + pillGap
        for (i in 0 until pages.childCount) {
            (pages.getChildAt(i) as? PageGridView)?.setPadding(sideMargin, pageTop, sideMargin, pageBottom)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { applyInsets() }
    }

    /** Back to page one, like pressing Home on an iPhone that's already home. */
    fun goHome() {
        pages.snapToPage(0)
    }
}
