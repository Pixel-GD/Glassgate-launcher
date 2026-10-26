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
        prefs.addListener { key -> if (key == Prefs.ICON_STYLE) icons.style = prefs.iconStyle }
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

/**
 * User settings. The Settings screen edits these; listeners hear every change, and views read
 * them when they rebuild.
 */
class Prefs(context: Context) {
    fun interface Listener {
        fun onPrefChanged(key: String)
    }

    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val listeners = LinkedHashSet<Listener>()

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    private fun changed(key: String) = listeners.toList().forEach { it.onPrefChanged(key) }

    var iconStyle: IconStyle
        get() = runCatching { IconStyle.valueOf(sp.getString(ICON_STYLE, null)!!) }.getOrDefault(IconStyle.DEFAULT)
        set(v) { sp.edit().putString(ICON_STYLE, v.name).apply(); changed(ICON_STYLE) }

    var showLabels: Boolean
        get() = sp.getBoolean(SHOW_LABELS, true)
        set(v) { sp.edit().putBoolean(SHOW_LABELS, v).apply(); changed(SHOW_LABELS) }

    /** Icon size in dp; iOS is 60pt on most iPhones. */
    var iconSizeDp: Int
        get() = sp.getInt(ICON_SIZE, 60)
        set(v) { sp.edit().putInt(ICON_SIZE, v).apply(); changed(ICON_SIZE) }

    var showDock: Boolean
        get() = sp.getBoolean(SHOW_DOCK, true)
        set(v) { sp.edit().putBoolean(SHOW_DOCK, v).apply(); changed(SHOW_DOCK) }

    /** Red count badges on icons (needs notification access too). */
    var showBadges: Boolean
        get() = sp.getBoolean(SHOW_BADGES, true)
        set(v) { sp.edit().putBoolean(SHOW_BADGES, v).apply(); changed(SHOW_BADGES) }

    /**
     * How frosted glass is, 0 (clear) to 1 (tinted); 0.5 is the stock look. Cached because
     * every glass surface reads it on every frame.
     */
    @Volatile var glassIntensity: Float = sp.getFloat(GLASS_INTENSITY, 0.5f)
        set(v) {
            field = v.coerceIn(0f, 1f)
            sp.edit().putFloat(GLASS_INTENSITY, field).apply()
            changed(GLASS_INTENSITY)
        }

    companion object {
        const val ICON_STYLE = "icon_style"
        const val SHOW_LABELS = "show_labels"
        const val ICON_SIZE = "icon_size"
        const val SHOW_DOCK = "show_dock"
        const val SHOW_BADGES = "show_badges"
        const val GLASS_INTENSITY = "glass_intensity"
    }
}
