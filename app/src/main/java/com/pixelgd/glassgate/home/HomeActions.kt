package com.pixelgd.glassgate.home

import android.view.View
import com.pixelgd.glassgate.apps.AppEntry
import com.pixelgd.glassgate.layout.HomeItem

/**
 * The integration points between the home screen and the feature modules. The home views call
 * these; LauncherActivity implements them and routes to each module. When you build a module,
 * replace the matching stub in LauncherActivity rather than reaching into the home views.
 */
interface HomeActions {
    /** Tap on an app icon anywhere (home, dock, folder, library, search). */
    fun launch(app: AppEntry, source: View)

    /** Search pill tap or swipe down on a home page. Module: spotlight. */
    fun openSpotlight()

    /** Tap on a folder icon. Module: folders. */
    fun openFolder(folder: HomeItem.Folder, source: View)

    /** Long-press on an icon or folder. Module: menu (context menus). */
    fun showContextMenu(item: HomeItem?, app: AppEntry?, source: View)

    /** Long-press on empty space, or "Edit Home Screen" in a menu. Module: edit (jiggle mode). */
    fun enterEditMode()

    /**
     * A page that follows the last home page, or null for none. Module: library (App Library).
     * Called once whenever the pages are rebuilt.
     */
    fun createTrailingPage(): View?
}
