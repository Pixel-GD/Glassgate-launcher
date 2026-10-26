package com.pixelgd.glassgate.apps

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.graphics.drawable.Drawable
import android.os.UserHandle

/**
 * One launchable activity. [key] is the stable id used in the saved home layout,
 * folders, badges and search; it survives reinstalls and label changes.
 */
class AppEntry(
    val info: LauncherActivityInfo,
    val userSerial: Long,
) {
    val component: ComponentName get() = info.componentName
    val packageName: String get() = info.componentName.packageName
    val user: UserHandle get() = info.user
    val label: String = info.label?.toString()?.trim().orEmpty().ifEmpty { packageName }
    val key: String = keyOf(info.componentName, userSerial)

    /** ApplicationInfo.CATEGORY_* (GAME, SOCIAL, PRODUCTIVITY...), or -1 when unknown. */
    val category: Int get() = info.applicationInfo.category

    val isSystem: Boolean
        get() = info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0

    val firstInstallTime: Long get() = info.firstInstallTime

    /** The app's own icon at full density. Prefer IconRenderer for anything on screen. */
    fun loadRawIcon(density: Int): Drawable = info.getIcon(density)

    override fun equals(other: Any?) = other is AppEntry && other.key == key
    override fun hashCode() = key.hashCode()
    override fun toString() = "AppEntry($key)"

    companion object {
        fun keyOf(component: ComponentName, userSerial: Long): String =
            if (userSerial == 0L) component.flattenToShortString()
            else "${component.flattenToShortString()}#$userSerial"
    }
}
