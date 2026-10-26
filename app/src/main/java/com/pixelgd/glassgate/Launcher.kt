package com.pixelgd.glassgate

import android.app.Application
import android.content.Context
import com.pixelgd.glassgate.apps.AppRepository
import com.pixelgd.glassgate.glass.Tilt
import com.pixelgd.glassgate.glass.WallpaperSource
import com.pixelgd.glassgate.icons.IconRenderer
import com.pixelgd.glassgate.icons.IconStyle
import com.pixelgd.glassgate.layout.LayoutStore

/**
 * Process-wide services. Every module gets what it needs from here:
 *
 *     val launcher = Launcher.of(context)
 *     launcher.apps.all, launcher.layout.edit { ... }, launcher.icons.render(app, px)
 */
class Launcher(context: Context) {
    val prefs = Prefs(context)
    val apps = AppRepository(context)
    val layout = LayoutStore(context, apps)
    val icons = IconRenderer(context).apply { style = prefs.iconStyle }
    val wallpaper = WallpaperSource(context)
    val tilt = Tilt(context)

    init {
        wallpaper.addListener { b -> b?.let { icons.tint = it.accent } }
        wallpaper.current?.let { icons.tint = it.accent }
    }

    companion object {
        fun of(context: Context): Launcher =
            (context.applicationContext as GlassgateApp).launcher
    }
}

class GlassgateApp : Application() {
    lateinit var launcher: Launcher
        private set

    override fun onCreate() {
        super.onCreate()
        launcher = Launcher(this)
    }
}

/** User settings. The Settings screen edits these; views read them when they rebuild. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var iconStyle: IconStyle
        get() = runCatching { IconStyle.valueOf(sp.getString("icon_style", null)!!) }.getOrDefault(IconStyle.DEFAULT)
        set(v) = sp.edit().putString("icon_style", v.name).apply()

    var showLabels: Boolean
        get() = sp.getBoolean("show_labels", true)
        set(v) = sp.edit().putBoolean("show_labels", v).apply()

    /** Icon size in dp; iOS is 60pt on most iPhones. */
    var iconSizeDp: Int
        get() = sp.getInt("icon_size", 60)
        set(v) = sp.edit().putInt("icon_size", v).apply()

    var showDock: Boolean
        get() = sp.getBoolean("show_dock", true)
        set(v) = sp.edit().putBoolean("show_dock", v).apply()
}
