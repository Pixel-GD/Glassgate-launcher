package com.pixelgd.glassgate.badges

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.pixelgd.glassgate.apps.AppEntry

/**
 * Unread notification counts per app, filled in by [BadgeService] once the user grants
 * notification access. Counts are per package and profile, so every icon of an app shows the
 * same number, like iOS. Main thread only.
 *
 *     Badges.count(app)            // 0 when there is nothing to show
 *     Badges.total(folderApps)     // for a folder's badge
 */
object Badges {

    fun interface Listener {
        /** [changed] holds badge keys (see [keyOf]) whose count changed. */
        fun onBadgesChanged(changed: Set<String>)
    }

    private var counts: Map<String, Int> = emptyMap()
    private val listeners = LinkedHashSet<Listener>()

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    fun count(app: AppEntry): Int = counts[keyOf(app)] ?: 0

    /** Sum over several apps, counting each package once. */
    fun total(apps: Iterable<AppEntry>): Int =
        apps.mapTo(HashSet()) { keyOf(it) }.sumOf { counts[it] ?: 0 }

    fun keyOf(app: AppEntry): String = keyOf(app.packageName, app.userSerial)

    /** [userSerial] uses AppEntry's convention: 0 for the launcher's own profile. */
    fun keyOf(packageName: String, userSerial: Long): String =
        if (userSerial == 0L) packageName else "$packageName#$userSerial"

    /** Replaces every count and tells listeners which keys changed. */
    internal fun replaceAll(next: Map<String, Int>) {
        val changed = HashSet<String>()
        for ((k, v) in next) if (counts[k] != v) changed += k
        for (k in counts.keys) if (k !in next) changed += k
        counts = next
        if (changed.isNotEmpty()) listeners.toList().forEach { it.onBadgesChanged(changed) }
    }

    fun isAccessGranted(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, BadgeService::class.java))

    /** Opens the system screen where the user allows notification access for Glassgate. */
    fun accessSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, BadgeService::class.java).flattenToString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Fallback for devices without the per-app detail screen. */
    fun accessListIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
