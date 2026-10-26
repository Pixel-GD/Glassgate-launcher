package com.pixelgd.glassgate

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.window.OnBackInvokedDispatcher
import android.widget.FrameLayout
import com.pixelgd.glassgate.apps.AppEntry
import com.pixelgd.glassgate.edit.EditMode
import com.pixelgd.glassgate.folders.FolderView
import com.pixelgd.glassgate.glass.WallpaperSource
import com.pixelgd.glassgate.home.HomeActions
import com.pixelgd.glassgate.home.HomeView
import com.pixelgd.glassgate.icons.IconStyle
import com.pixelgd.glassgate.layout.HomeItem
import com.pixelgd.glassgate.menu.ContextMenu
import com.pixelgd.glassgate.widgets.WidgetBinder
import com.pixelgd.glassgate.widgets.WidgetPicker
import com.pixelgd.glassgate.widgets.Widgets
import com.pixelgd.glassgate.spotlight.SpotlightHistory
import com.pixelgd.glassgate.spotlight.SpotlightView
import com.pixelgd.glassgate.library.AppUsage
import com.pixelgd.glassgate.library.LibraryPage

/**
 * The home activity. Builds the [HomeView] and an [overlay] layer that feature modules
 * (Spotlight, folders, App Library, menus, edit mode) add their full-screen views to.
 * Each HomeActions method below is the hook for one module; replace the stub when you
 * build that module.
 */
class LauncherActivity : Activity(), HomeActions {

    private lateinit var launcher: Launcher
    lateinit var home: HomeView
        private set

    /** Full-screen layer above the home screen for module UIs. */
    lateinit var overlay: FrameLayout
        private set

    /** Jiggle mode (module: edit). */
    val editMode by lazy { EditMode(this).apply { onAddWidget = { WidgetPicker.show(this@LauncherActivity) } } }
    private var resumed = false
    private var settingsDirty = false
    private val prefsListener = Prefs.Listener { settingsChanged() }
    // Tinted icons take their colour from the wallpaper.
    private val wallpaperListener = WallpaperSource.Listener {
        if (launcher.prefs.iconStyle == IconStyle.TINTED) settingsChanged()
    }
    private var libraryPage: LibraryPage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher = Launcher.of(this)
        window.setDecorFitsSystemWindows(false)

        val root = FrameLayout(this)
        home = HomeView(this, this)
        overlay = FrameLayout(this)
        root.addView(home, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(overlay, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        setContentView(root)
        updateSystemBars()
        launcher.prefs.addListener(prefsListener)
        launcher.wallpaper.addListener(wallpaperListener)
        // With enableOnBackInvokedCallback, onBackPressed isn't called on Android 13+.
        // Back closes the top overlay only (e.g. Edit Pages, then jiggle mode).
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            val top = overlay.getChildAt(overlay.childCount - 1) ?: return@registerOnBackInvokedCallback
            if (top is DismissibleOverlay) top.dismiss() else overlay.removeView(top)
        }
    }

    override fun onStart() {
        super.onStart()
        Widgets.of(this).startListening()
    }

    override fun onStop() {
        Widgets.of(this).stopListening()
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!WidgetBinder.onActivityResult(this, requestCode, resultCode)) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        launcher.prefs.removeListener(prefsListener)
        launcher.wallpaper.removeListener(wallpaperListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (settingsDirty) applySettings()
        launcher.tilt.start()
    }

    override fun onPause() {
        resumed = false
        launcher.tilt.stop()
        super.onPause()
    }

    /** Settings changed (usually from the Settings screen): rebuild now, or when we're back. */
    private fun settingsChanged() {
        settingsDirty = true
        if (resumed) applySettings()
    }

    private fun applySettings() {
        settingsDirty = false
        home.rebuild()
        invalidateAll(home)
    }

    /** Glass backgrounds keep their last frame until redrawn. */
    private fun invalidateAll(v: View) {
        v.invalidate()
        v.background?.invalidateSelf()
        if (v is ViewGroup) for (i in 0 until v.childCount) invalidateAll(v.getChildAt(i))
    }

    /** Home pressed while already home: close any overlay, else go to page one. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Intent.ACTION_MAIN == intent.action && hasWindowFocus()) {
            if (overlay.childCount > 0) dismissOverlays() else home.goHome()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (overlay.childCount > 0) dismissOverlays()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        launcher.wallpaper.refresh()
        launcher.icons.clear()
        updateSystemBars()
        home.rebuild()
    }

    /** Light wallpapers still get white status bar icons, like iOS over a photo. */
    private fun updateSystemBars() {
        window.insetsController?.setSystemBarsAppearance(0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    }

    fun dismissOverlays() {
        for (i in overlay.childCount - 1 downTo 0) {
            val v = overlay.getChildAt(i)
            if (v is DismissibleOverlay) v.dismiss() else overlay.removeView(v)
        }
    }

    // ---- HomeActions: one hook per feature module ----

    override fun launch(app: AppEntry, source: View) {
        SpotlightHistory.of(this).record(app)
        AppUsage.of(this).record(app)
        launcher.apps.launch(app, source)
    }

    override fun openSpotlight() {
        SpotlightView.open(this)
    }

    override fun openFolder(folder: HomeItem.Folder, source: View) {
        FolderView.open(this, folder, source)
    }

    override fun showContextMenu(item: HomeItem?, app: AppEntry?, source: View) {
        if (!editMode.isActive) ContextMenu.show(this, item, app, source)
    }

    override fun enterEditMode() {
        editMode.enter()
    }

    /** The App Library. One instance per activity, re-sorted on each rebuild. */
    override fun createTrailingPage(): View {
        val page = libraryPage ?: LibraryPage(this, this, overlay, home).also {
            libraryPage = it
            home.pages.addScrollListener(it)
        }
        page.refresh()
        return page
    }
}

/**
 * An overlay that animates itself out: [LauncherActivity.dismissOverlays] calls [dismiss]
 * instead of removing it, and the overlay removes itself when done.
 */
interface DismissibleOverlay {
    fun dismiss()
}
