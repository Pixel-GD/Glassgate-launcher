package com.pixelgd.glassgate.menu

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.LauncherActivity
import com.pixelgd.glassgate.apps.AppEntry
import com.pixelgd.glassgate.layout.HomeItem
import com.pixelgd.glassgate.layout.HomeLayout

/**
 * The iOS "Remove App" flow, shared by the context menu and the jiggle-mode "−" badges.
 * Removing from the Home Screen keeps the app in the App Library ([HomeLayout.libraryOnly]);
 * "Delete App" asks Android to uninstall it.
 */
object Removal {

    /** Confirms removing [app]: [item] is its icon on a page, or null for the dock or elsewhere. */
    fun confirmApp(activity: LauncherActivity, app: AppEntry, item: HomeItem.App?) {
        val launcher = Launcher.of(activity)
        val onHome = app.key in launcher.layout.layout.allKeys()
        val buttons = ArrayList<GlassAlert.Button>()
        if (canUninstall(app)) buttons += GlassAlert.Button("Delete App", destructive = true) { uninstall(activity, app) }
        if (onHome) buttons += GlassAlert.Button("Remove from Home Screen") { removeFromHome(activity, app.key, item) }
        buttons += GlassAlert.Button("Cancel", cancel = true)
        GlassAlert.show(
            activity, "Remove “${app.label}”?",
            if (onHome) "Removing from Home Screen will keep the app in your App Library." else null,
            *buttons.toTypedArray(),
        )
    }

    /** Confirms removing [folder] from its page; its apps stay in the App Library. */
    fun confirmFolder(activity: LauncherActivity, folder: HomeItem.Folder) {
        GlassAlert.show(
            activity, "Remove “${folder.title.ifEmpty { "Folder" }}”?",
            "The apps in this folder will stay in your App Library.",
            GlassAlert.Button("Remove Folder", destructive = true) {
                Launcher.of(activity).layout.edit { l ->
                    for (p in l.pages) p.items.removeAll { it === folder || (it is HomeItem.Folder && it.id == folder.id) }
                    l.libraryOnly += folder.keys
                }
            },
            GlassAlert.Button("Cancel", cancel = true),
        )
    }

    /** Takes [key] off the Home Screen: [item] if given, else the dock copy, else any copy. */
    fun removeFromHome(activity: LauncherActivity, key: String, item: HomeItem.App?) {
        Launcher.of(activity).layout.edit { l -> takeOff(l, key, item) }
    }

    fun takeOff(l: HomeLayout, key: String, item: HomeItem.App?) {
        val removed = if (item != null) l.pages.any { p -> p.items.removeAll { it === item } } else l.dock.remove(key)
        if (!removed) {
            l.dock.remove(key)
            for (p in l.pages) p.items.removeAll { it is HomeItem.App && it.key == key }
        }
        if (key !in l.allKeys()) l.libraryOnly += key
    }

    /** System apps can only have updates removed, which isn't what "Delete App" means. */
    fun canUninstall(app: AppEntry) = !app.isSystem

    fun uninstall(activity: LauncherActivity, app: AppEntry) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
            .putExtra(Intent.EXTRA_USER, app.user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, "Can't delete ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * iOS 18's "Require Face ID" for an app: verify first, then choose to lock it in place or
 * hide it in Settings > Hidden Apps.
 */
object AppLocking {

    fun offer(activity: LauncherActivity, app: AppEntry) {
        val prefs = Launcher.of(activity).prefs
        if (app.key in prefs.lockedApps) {
            com.pixelgd.glassgate.apps.AppLock.authenticate(activity, app.label) {
                prefs.lockedApps = prefs.lockedApps - app.key
            }
            return
        }
        com.pixelgd.glassgate.apps.AppLock.authenticate(activity, app.label) {
            GlassAlert.show(
                activity, "Require Face ID for “${app.label}”?",
                "Opening it will need your face, fingerprint or screen lock. Hidden apps also leave the Home Screen, App Library and Search, and are listed in Settings › Hidden Apps.",
                GlassAlert.Button("Require Face ID") { prefs.lockedApps = prefs.lockedApps + app.key },
                GlassAlert.Button("Hide and Require Face ID") { prefs.hiddenApps = prefs.hiddenApps + app.key },
                GlassAlert.Button("Cancel", cancel = true),
            )
        }
    }
}
