package com.pixelgd.glassgate.folders

import android.content.pm.ApplicationInfo
import com.pixelgd.glassgate.apps.AppEntry
import com.pixelgd.glassgate.layout.HomeItem
import com.pixelgd.glassgate.layout.HomeLayout
import com.pixelgd.glassgate.layout.HomePage

/**
 * Folder edits on a [HomeLayout]. Call these inside `launcher.layout.edit { ... }` so the
 * change is saved and the home screen rebuilds. The edit module uses [create] and [add] when an
 * icon is dropped on another icon or on a folder.
 */
object Folders {
    /** Apps per page of an open folder (3x3, like iOS). */
    const val COLS = 3
    const val ROWS = 3
    const val PAGE_SIZE = COLS * ROWS

    fun find(layout: HomeLayout, id: Long): HomeItem.Folder? {
        for (p in layout.pages) for (item in p.items) if (item is HomeItem.Folder && item.id == id) return item
        return null
    }

    /**
     * Drops [key] onto the app icon [target]: a new folder takes target's cell, holding target
     * first and then [key]. [key] is taken from wherever it was (dock, page or another folder).
     */
    fun create(layout: HomeLayout, target: HomeItem.App, key: String, id: Long, title: String): HomeItem.Folder? {
        val page = layout.pages.firstOrNull { p -> p.items.any { it === target } } ?: return null
        if (key == target.key) return null
        takeOut(layout, key)
        val folder = HomeItem.Folder(id, title, mutableListOf(target.key, key), target.x, target.y)
        page.items[page.items.indexOf(target)] = folder
        return folder
    }

    /** Adds [key] to the end of [folder], taking it from wherever it was. */
    fun add(layout: HomeLayout, folder: HomeItem.Folder, key: String) {
        if (key in folder.keys) return
        takeOut(layout, key)
        folder.keys += key
    }

    /**
     * Takes [key] out of [folder] and puts it on the home screen, on [toPage] or the first page
     * with room after it. A folder left empty is removed.
     */
    fun remove(layout: HomeLayout, folder: HomeItem.Folder, key: String, toPage: Int = 0) {
        if (!folder.keys.remove(key)) return
        if (folder.keys.isEmpty()) for (p in layout.pages) p.items.remove(folder)
        layout.placeFirstFree(HomeItem.App(key), toPage.coerceIn(0, (layout.pages.size - 1).coerceAtLeast(0)))
    }

    /** Moves [key] to [index] inside [folder] (reordering in edit mode). */
    fun move(folder: HomeItem.Folder, key: String, index: Int) {
        if (!folder.keys.remove(key)) return
        folder.keys.add(index.coerceIn(0, folder.keys.size), key)
    }

    /** The name iOS would give a new folder: the App Store category its apps share. */
    fun suggestName(apps: List<AppEntry>): String {
        val best = apps.map { it.category }
            .filter { it != ApplicationInfo.CATEGORY_UNDEFINED }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key
        return when (best) {
            ApplicationInfo.CATEGORY_GAME -> "Games"
            ApplicationInfo.CATEGORY_AUDIO -> "Music"
            ApplicationInfo.CATEGORY_VIDEO -> "Entertainment"
            ApplicationInfo.CATEGORY_IMAGE -> "Photo & Video"
            ApplicationInfo.CATEGORY_SOCIAL -> "Social"
            ApplicationInfo.CATEGORY_NEWS -> "News"
            ApplicationInfo.CATEGORY_MAPS -> "Navigation"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
            ApplicationInfo.CATEGORY_ACCESSIBILITY -> "Accessibility"
            else -> if (apps.isNotEmpty() && apps.all { it.isSystem }) "Utilities" else "Folder"
        }
    }

    /** Removes [key] from the dock, pages and folders; folders left empty go too. */
    private fun takeOut(layout: HomeLayout, key: String) {
        layout.dock.remove(key)
        for (p in layout.pages) removeFrom(p, key)
    }

    private fun removeFrom(page: HomePage, key: String) {
        page.items.removeAll { it is HomeItem.App && it.key == key }
        for (item in page.items) if (item is HomeItem.Folder) item.keys.remove(key)
        page.items.removeAll { it is HomeItem.Folder && it.keys.isEmpty() }
    }
}
