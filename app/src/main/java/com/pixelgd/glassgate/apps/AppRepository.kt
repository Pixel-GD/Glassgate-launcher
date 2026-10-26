package com.pixelgd.glassgate.apps

import android.app.ActivityOptions
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.UserHandle
import android.os.UserManager
import android.view.View
import com.pixelgd.glassgate.util.Main
import java.text.Collator
import java.util.concurrent.Executors

/**
 * The set of installed launchable apps across all profiles (personal, work, Secure Folder).
 * Loads on a background thread; listeners are called on the main thread whenever the set changes.
 */
class AppRepository(private val context: Context) {

    fun interface Listener {
        fun onAppsChanged(apps: List<AppEntry>)
    }

    private val launcherApps = context.getSystemService(LauncherApps::class.java)!!
    private val userManager = context.getSystemService(UserManager::class.java)!!
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "apps-loader") }
    private val listeners = LinkedHashSet<Listener>()

    @Volatile private var byKey: Map<String, AppEntry> = emptyMap()
    @Volatile private var sorted: List<AppEntry> = emptyList()
    var isLoaded = false
        private set

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(p: Array<out String>, user: UserHandle, r: Boolean) = reload()
        override fun onPackagesUnavailable(p: Array<out String>, user: UserHandle, r: Boolean) = reload()
        override fun onPackagesSuspended(p: Array<out String>, user: UserHandle) = reload()
        override fun onPackagesUnsuspended(p: Array<out String>, user: UserHandle) = reload()
    }

    init {
        launcherApps.registerCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
        reload()
    }

    /** All apps, sorted A to Z by label. */
    val all: List<AppEntry> get() = sorted

    operator fun get(key: String): AppEntry? = byKey[key]

    fun addListener(l: Listener) {
        listeners += l
        if (isLoaded) l.onAppsChanged(sorted)
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    fun reload() {
        worker.execute {
            val collator = Collator.getInstance()
            val entries = ArrayList<AppEntry>()
            val selfSerial = userManager.getSerialNumberForUser(android.os.Process.myUserHandle())
            for (user in launcherApps.profiles) {
                if (userManager.isQuietModeEnabled(user)) continue
                val serial = userManager.getSerialNumberForUser(user)
                for (info in launcherApps.getActivityList(null, user)) {
                    if (info.componentName.packageName == context.packageName) continue
                    entries += AppEntry(info, if (serial == selfSerial) 0L else serial)
                }
            }
            entries.sortWith { a, b -> collator.compare(a.label, b.label) }
            val map = entries.associateBy { it.key }
            Main.post {
                byKey = map
                sorted = entries
                isLoaded = true
                listeners.toList().forEach { it.onAppsChanged(entries) }
            }
        }
    }

    /** Launches [app], zooming out of [source] like iOS when a view is given. */
    fun launch(app: AppEntry, source: View? = null) {
        val bounds: Rect?
        val opts = if (source != null) {
            val loc = IntArray(2)
            source.getLocationOnScreen(loc)
            bounds = Rect(loc[0], loc[1], loc[0] + source.width, loc[1] + source.height)
            ActivityOptions.makeScaleUpAnimation(source, 0, 0, source.width, source.height).toBundle()
        } else {
            bounds = null
            null
        }
        try {
            launcherApps.startMainActivity(app.component, app.user, bounds, opts)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Can't open ${app.label}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Opens the system App info page for [app]. */
    fun openAppInfo(app: AppEntry, source: View? = null) {
        launcherApps.startAppDetailsActivity(app.component, app.user, null, null)
    }

    /** Pinned/dynamic shortcuts for long-press menus. Empty unless this is the default launcher. */
    fun shortcuts(app: AppEntry): List<android.content.pm.ShortcutInfo> {
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val q = LauncherApps.ShortcutQuery()
            .setPackage(app.packageName)
            .setActivity(app.component)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
            )
        return try {
            launcherApps.getShortcuts(q, app.user).orEmpty().sortedBy { it.rank }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun launchShortcut(s: android.content.pm.ShortcutInfo, source: View? = null) {
        try {
            launcherApps.startShortcut(s.`package`, s.id, null, null, s.userHandle)
        } catch (e: Exception) {
            // Shortcut was removed or disabled between query and tap.
        }
    }
}
