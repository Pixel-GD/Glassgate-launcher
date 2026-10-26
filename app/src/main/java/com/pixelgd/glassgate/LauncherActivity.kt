package com.pixelgd.glassgate

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.PopupMenu
import com.pixelgd.glassgate.apps.AppEntry
import com.pixelgd.glassgate.folders.FolderView
import com.pixelgd.glassgate.home.HomeActions
import com.pixelgd.glassgate.home.HomeView
import com.pixelgd.glassgate.layout.HomeItem

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
    }

    override fun onResume() {
        super.onResume()
        launcher.tilt.start()
    }

    override fun onPause() {
        launcher.tilt.stop()
        super.onPause()
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
        launcher.apps.launch(app, source)
    }

    override fun openSpotlight() {
        // TODO(spotlight module)
    }

    override fun openFolder(folder: HomeItem.Folder, source: View) {
        FolderView.open(this, folder, source)
    }

    override fun showContextMenu(item: HomeItem?, app: AppEntry?, source: View) {
        // Placeholder until the glass context menu module lands.
        val menu = PopupMenu(this, source)
        if (app != null) menu.menu.add(0, 1, 0, "App info")
        menu.menu.add(0, 2, 1, "Edit Home Screen")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> app?.let { a -> launcher.apps.openAppInfo(a) }
                2 -> enterEditMode()
            }
            true
        }
        menu.show()
    }

    override fun enterEditMode() {
        // TODO(edit module): jiggle mode
    }

    override fun createTrailingPage(): View? {
        // TODO(library module): App Library page
        return null
    }
}

/**
 * An overlay that animates itself out: [LauncherActivity.dismissOverlays] calls [dismiss]
 * instead of removing it, and the overlay removes itself when done.
 */
interface DismissibleOverlay {
    fun dismiss()
}
