package com.pixelgd.glassgate.layout

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.pixelgd.glassgate.apps.AppEntry
import com.pixelgd.glassgate.apps.AppRepository
import org.json.JSONObject
import java.io.File

/**
 * Loads, reconciles and saves the [HomeLayout]. All edits go through [edit] so views
 * hear about them and the file is written once per change. Main thread only.
 */
class LayoutStore(private val context: Context, private val apps: AppRepository) {

    fun interface Listener {
        fun onLayoutChanged(layout: HomeLayout)
    }

    private val file = File(context.filesDir, "home_layout.json")
    private val listeners = LinkedHashSet<Listener>()

    var layout: HomeLayout = load()
        private set

    init {
        apps.addListener { reconcile(it) }
    }

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    /** Applies [block] to the layout, saves, and notifies listeners. */
    fun edit(block: (HomeLayout) -> Unit) {
        block(layout)
        layout.pages.removeAll { it.items.isEmpty() && layout.pages.size > 1 }
        if (layout.pages.isEmpty()) layout.pages += HomePage()
        save()
        listeners.toList().forEach { it.onLayoutChanged(layout) }
    }

    /** Folder ids only need to be unique within this layout. */
    fun newFolderId(): Long = System.currentTimeMillis()

    private fun load(): HomeLayout = try {
        if (file.exists()) HomeLayout.fromJson(JSONObject(file.readText())) else HomeLayout()
    } catch (e: Exception) {
        HomeLayout()
    }

    private fun save() {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(layout.toJson().toString())
        tmp.renameTo(file)
    }

    /**
     * Drops uninstalled apps and places new ones. First run puts phone, messages, browser
     * and camera in the dock and every other app on the pages A to Z, like a fresh iPhone
     * with "Add to Home Screen" on.
     */
    private fun reconcile(installed: List<AppEntry>) {
        val keys = installed.mapTo(HashSet()) { it.key }
        val firstRun = layout.pages.isEmpty() && layout.dock.isEmpty()
        edit { l ->
            if (firstRun) l.dock += defaultDock(installed)
            l.dock.retainAll(keys)
            for (p in l.pages) {
                for (item in p.items) if (item is HomeItem.Folder) item.keys.retainAll(keys)
                p.items.removeAll { it is HomeItem.App && it.key !in keys }
                p.items.removeAll { it is HomeItem.Folder && it.keys.isEmpty() }
            }
            if (l.pages.isEmpty()) l.pages += HomePage()
            val present = l.allKeys()
            for (app in installed) if (app.key !in present) l.placeFirstFree(HomeItem.App(app.key))
        }
    }

    private fun defaultDock(installed: List<AppEntry>): List<String> {
        val pm = context.packageManager
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
        )
        val out = ArrayList<String>()
        for (intent in intents) {
            val pkg = pm.resolveActivity(intent, 0)?.activityInfo?.packageName ?: continue
            installed.firstOrNull { it.packageName == pkg && it.userSerial == 0L }
                ?.let { if (it.key !in out) out += it.key }
        }
        return out.take(HomeLayout.DOCK_MAX)
    }
}
