package com.pixelgd.glassgate.layout

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.pixelgd.glassgate.Launcher
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

    /** Saved layouts before (and after undone) user edits, for iOS 27's Undo and Redo. */
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    /**
     * Applies [block] to the layout, saves, and notifies listeners. User edits are [undoable];
     * the launcher's own bookkeeping (installs, removals) isn't.
     */
    fun edit(undoable: Boolean = true, block: (HomeLayout) -> Unit) {
        val before = if (undoable) layout.toJson().toString() else null
        block(layout)
        layout.pages.removeAll { it.items.isEmpty() && layout.pages.size > 1 }
        if (layout.pages.isEmpty()) layout.pages += HomePage()
        if (before != null && before != layout.toJson().toString()) {
            undoStack.addLast(before)
            if (undoStack.size > HISTORY) undoStack.removeFirst()
            redoStack.clear()
        }
        save()
        notifyChanged()
    }

    /** Puts the home screen back the way it was before the last edit. */
    fun undo() = step(undoStack, redoStack)

    /** Re-applies the last undone edit. */
    fun redo() = step(redoStack, undoStack)

    private fun step(from: ArrayDeque<String>, to: ArrayDeque<String>): Boolean {
        val json = from.removeLastOrNull() ?: return false
        to.addLast(layout.toJson().toString())
        layout = HomeLayout.fromJson(JSONObject(json))
        // Apps may have come or gone since that snapshot.
        if (apps.isLoaded) reconcile(apps.all, notify = false)
        save()
        notifyChanged()
        return true
    }

    /** Replaces the whole layout, e.g. from a backup. Undoable. */
    fun replace(json: JSONObject) {
        undoStack.addLast(layout.toJson().toString())
        redoStack.clear()
        layout = HomeLayout.fromJson(json)
        if (apps.isLoaded) reconcile(apps.all, notify = false)
        save()
        notifyChanged()
    }

    /** Back to a fresh iPhone-style layout: dock defaults, then every app A to Z. */
    fun reset() {
        undoStack.addLast(layout.toJson().toString())
        redoStack.clear()
        layout = HomeLayout(layout.cols, layout.rows)
        reconcile(apps.all, notify = false, forceFirstRun = true)
        save()
        notifyChanged()
    }

    private fun notifyChanged() = listeners.toList().forEach { it.onLayoutChanged(layout) }

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
    private fun reconcile(installed: List<AppEntry>, notify: Boolean = true, forceFirstRun: Boolean = false) {
        val keys = installed.mapTo(HashSet()) { it.key }
        val firstRun = forceFirstRun || layout.pages.isEmpty() && layout.dock.isEmpty()
        val known = knownKeys()
        val toHome = firstRun || Launcher.of(context).prefs.newAppsToHome
        val block = { l: HomeLayout ->
            if (firstRun) l.dock += defaultDock(installed)
            l.dock.retainAll(keys)
            l.libraryOnly.retainAll(keys)
            for (p in l.pages) {
                for (item in p.items) if (item is HomeItem.Folder) item.keys.retainAll(keys)
                p.items.removeAll { it is HomeItem.App && it.key !in keys }
                p.items.removeAll { it is HomeItem.Folder && it.keys.isEmpty() }
            }
            if (l.pages.isEmpty()) l.pages += HomePage()
            val present = l.allKeys()
            for (app in installed) {
                if (app.key in present || app.key in l.libraryOnly) continue
                // iOS "App Library Only": new downloads skip the home screen. Apps we've seen
                // before (e.g. un-hidden or re-enabled ones) come back as usual.
                if (!toHome && known.isNotEmpty() && app.key !in known) l.libraryOnly += app.key
                else l.placeFirstFree(HomeItem.App(app.key))
            }
        }
        if (notify) edit(undoable = false, block) else block(layout)
        rememberKnown(keys)
    }

    // Every app key seen so far, so "new" means newly installed rather than newly visible.
    private val knownFile = File(context.filesDir, "known_apps.txt")

    private fun knownKeys(): Set<String> =
        runCatching { knownFile.readLines().filterTo(HashSet()) { it.isNotBlank() } }.getOrDefault(emptySet())

    private fun rememberKnown(keys: Set<String>) {
        val all = knownKeys() + keys
        runCatching { knownFile.writeText(all.joinToString("\n")) }
    }

    private companion object {
        const val HISTORY = 30
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
