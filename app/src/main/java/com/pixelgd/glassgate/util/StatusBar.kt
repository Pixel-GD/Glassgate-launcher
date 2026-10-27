package com.pixelgd.glassgate.util

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log

/**
 * Pulls down the system notification shade or quick settings, for the home screen's
 * swipe-down gesture. Android has no public API for this; every third-party launcher calls
 * the StatusBarManager methods below (they need the normal EXPAND_STATUS_BAR permission).
 */
object StatusBar {
    private const val TAG = "StatusBar"

    fun expandNotifications(context: Context) = call(context, "expandNotificationsPanel")

    /** Quick Settings: the closest Android has to Control Center. */
    fun expandQuickSettings(context: Context) = call(context, "expandSettingsPanel")

    @SuppressLint("WrongConstant")
    private fun call(context: Context, method: String): Boolean = try {
        val sbm = context.getSystemService("statusbar")
        sbm!!.javaClass.getMethod(method).invoke(sbm)
        true
    } catch (e: Exception) {
        Log.w(TAG, "$method unavailable", e)
        false
    }
}
