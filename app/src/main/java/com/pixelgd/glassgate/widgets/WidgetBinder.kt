package com.pixelgd.glassgate.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.pixelgd.glassgate.LauncherActivity

/**
 * Adds an app widget: allocate an id, get the user's OK to bind it if the system asks, run
 * the widget's configure screen if it has one, then place it. LauncherActivity forwards
 * activity results here.
 */
object WidgetBinder {

    private const val REQUEST_BIND = 0x7701
    private const val REQUEST_CONFIGURE = 0x7702
    private const val TAG = "WidgetBinder"

    private class Pending(val id: Int, val info: AppWidgetProviderInfo, val size: WidgetSize, val page: Int)

    private var pending: Pending? = null

    fun add(activity: LauncherActivity, info: AppWidgetProviderInfo, size: WidgetSize) {
        val widgets = Widgets.of(activity)
        val id = widgets.host.allocateAppWidgetId()
        val p = Pending(id, info, size, activity.home.pages.currentPage)
        pending = p
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
        }
        val bound = runCatching { widgets.manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, options) }
            .getOrDefault(false)
        if (bound) {
            configureOrPlace(activity, p)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options)
            try {
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, REQUEST_BIND)
            } catch (e: Exception) {
                Log.w(TAG, "No bind dialog", e)
                cancel(activity)
            }
        }
    }

    /** Returns true if the result was ours. */
    fun onActivityResult(activity: LauncherActivity, requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST_BIND && requestCode != REQUEST_CONFIGURE) return false
        val p = pending ?: return true
        if (resultCode != Activity.RESULT_OK) {
            cancel(activity)
        } else if (requestCode == REQUEST_BIND) {
            configureOrPlace(activity, p)
        } else {
            place(activity, p)
        }
        return true
    }

    private fun configureOrPlace(activity: LauncherActivity, p: Pending) {
        val optional = (p.info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
        if (p.info.configure == null || optional) return place(activity, p)
        try {
            Widgets.of(activity).host.startAppWidgetConfigureActivityForResult(activity, p.id, 0, REQUEST_CONFIGURE, null)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open widget setup; adding it as is", e)
            place(activity, p)
        }
    }

    private fun place(activity: LauncherActivity, p: Pending) {
        pending = null
        val item = WidgetSpec.Hosted(p.id, p.info.provider).toItem(p.size)
        val page = Widgets.of(activity).place(item, p.page)
        activity.home.pages.snapToPage(page)
    }

    private fun cancel(activity: LauncherActivity) {
        pending?.let { Widgets.of(activity).deleteId(it.id) }
        pending = null
    }
}
