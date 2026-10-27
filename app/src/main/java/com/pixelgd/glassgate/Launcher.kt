package com.pixelgd.glassgate

import android.app.Application
import android.content.Context
import com.pixelgd.glassgate.apps.AppRepository
import com.pixelgd.glassgate.glass.Tilt
import com.pixelgd.glassgate.glass.WallpaperSource
import com.pixelgd.glassgate.icons.IconRenderer
import com.pixelgd.glassgate.icons.IconStyle
import com.pixelgd.glassgate.layout.LayoutStore
import com.pixelgd.glassgate.util.isDarkMode

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
        wallpaper.addListener { updateIconLook() }
        apps.hidden = prefs.hiddenApps
        updateIconLook()
        prefs.addListener { key ->
            when (key) {
                Prefs.ICON_STYLE, Prefs.ICON_APPEARANCE, Prefs.TINT_COLOR -> updateIconLook()
                Prefs.ICON_PACK -> icons.iconPack = prefs.iconPack
                Prefs.HIDDEN_APPS -> apps.hidden = prefs.hiddenApps
            }
        }
        icons.iconPack = prefs.iconPack
    }

    /**
     * Pushes the Customize choices into the renderer. Call again when the system switches
     * between light and dark, since Auto icons follow it.
     */
    fun updateIconLook() {
        icons.style = prefs.iconStyle
        icons.dark = prefs.iconsDark()
        icons.tint = prefs.tintColor.takeIf { it != 0 } ?: wallpaper.current?.accent ?: icons.tint
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

    private val appContext = context.applicationContext
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val listeners = LinkedHashSet<Listener>()

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    private fun changed(key: String) = listeners.toList().forEach { it.onPrefChanged(key) }

    private fun bool(key: String, default: Boolean) = sp.getBoolean(key, default)
    private fun putBool(key: String, v: Boolean) { sp.edit().putBoolean(key, v).apply(); changed(key) }
    private fun stringSet(key: String) = sp.getStringSet(key, null)?.toSet() ?: emptySet()
    private fun putSet(key: String, v: Set<String>) { sp.edit().putStringSet(key, HashSet(v)).apply(); changed(key) }
    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        runCatching { enumValueOf<E>(sp.getString(key, null)!!) }.getOrDefault(default)
    private fun putEnum(key: String, v: Enum<*>) { sp.edit().putString(key, v.name).apply(); changed(key) }

    // ---- App icons (iOS: long-press > Edit > Customize) ----

    var iconStyle: IconStyle
        get() = enumOf(ICON_STYLE, IconStyle.DEFAULT)
        set(v) = putEnum(ICON_STYLE, v)

    /** iOS 26's Light / Dark / Auto switch under the icon looks. */
    var iconAppearance: Appearance
        get() = enumOf(ICON_APPEARANCE, Appearance.AUTO)
        set(v) = putEnum(ICON_APPEARANCE, v)

    /** Tinted icons' colour; 0 follows the wallpaper, like the eyedropper default on iOS. */
    var tintColor: Int
        get() = sp.getInt(TINT_COLOR, 0)
        set(v) { sp.edit().putInt(TINT_COLOR, v).apply(); changed(TINT_COLOR) }

    /** iOS 18+ "Large" icons: bigger squircles and no names. */
    var largeIcons: Boolean
        get() = bool(LARGE_ICONS, false)
        set(v) = putBool(LARGE_ICONS, v)

    /** The user's "Show App Names" choice, before Large icons hide them. */
    var labelsSetting: Boolean
        get() = bool(SHOW_LABELS, true)
        set(v) = putBool(SHOW_LABELS, v)

    /** What the home screen actually does: Large icons never have names, like iOS. */
    var showLabels: Boolean
        get() = labelsSetting && !largeIcons
        set(v) { labelsSetting = v }

    /** Icon size in dp before the Large multiplier; iOS is 60pt on most iPhones. */
    var baseIconSizeDp: Int
        get() = sp.getInt(ICON_SIZE, 60)
        set(v) { sp.edit().putInt(ICON_SIZE, v).apply(); changed(ICON_SIZE) }

    /** The size every home view draws icons at. */
    var iconSizeDp: Int
        get() = if (largeIcons) Math.round(baseIconSizeDp * LARGE_SCALE) else baseIconSizeDp
        set(v) { baseIconSizeDp = v }

    /** Icon pack package (ADW / Nova format), or null for the apps' own icons. */
    var iconPack: String?
        get() = sp.getString(ICON_PACK, null)
        set(v) { sp.edit().putString(ICON_PACK, v).apply(); changed(ICON_PACK) }

    var labelSizeSp: Float
        get() = sp.getFloat(LABEL_SIZE, 12f)
        set(v) { sp.edit().putFloat(LABEL_SIZE, v).apply(); changed(LABEL_SIZE) }

    // ---- Home Screen & App Library ----

    var showDock: Boolean
        get() = bool(SHOW_DOCK, true)
        set(v) = putBool(SHOW_DOCK, v)

    /** iOS: Settings > Home Screen & App Library > Search > Show on Home Screen. Off shows page dots. */
    var showSearchPill: Boolean
        get() = bool(SHOW_SEARCH_PILL, true)
        set(v) = putBool(SHOW_SEARCH_PILL, v)

    /** iOS: Newly Downloaded Apps > Add to Home Screen (true) or App Library Only (false). */
    var newAppsToHome: Boolean
        get() = bool(NEW_APPS_TO_HOME, true)
        set(v) = putBool(NEW_APPS_TO_HOME, v)

    var showAppLibrary: Boolean
        get() = bool(SHOW_APP_LIBRARY, true)
        set(v) = putBool(SHOW_APP_LIBRARY, v)

    /** What a downward swipe on the home screen does. iOS opens Search. */
    var swipeDown: SwipeAction
        get() = enumOf(SWIPE_DOWN, SwipeAction.SEARCH)
        set(v) = putEnum(SWIPE_DOWN, v)

    /** Red count badges on icons (needs notification access too). */
    var showBadges: Boolean
        get() = bool(SHOW_BADGES, true)
        set(v) = putBool(SHOW_BADGES, v)

    /** iOS: Notification Badges > Show in App Library. */
    var badgesInLibrary: Boolean
        get() = bool(BADGES_IN_LIBRARY, false)
        set(v) = putBool(BADGES_IN_LIBRARY, v)

    // ---- Wallpaper ----

    /** iOS: Wallpaper > Dark Appearance Dims Wallpaper. */
    var dimWallpaperInDark: Boolean
        get() = bool(DIM_WALLPAPER, true)
        set(v) = putBool(DIM_WALLPAPER, v)

    /** iOS: Home Screen wallpaper "Blur" (legibility blur). */
    var blurWallpaper: Boolean
        get() = bool(BLUR_WALLPAPER, false)
        set(v) = putBool(BLUR_WALLPAPER, v)

    // ---- Accessibility and feel ----

    /** iOS: Accessibility > Motion > Reduce Motion. No tilt, cross-fade app launches. */
    var reduceMotion: Boolean
        get() = bool(REDUCE_MOTION, false)
        set(v) = putBool(REDUCE_MOTION, v)

    /** iOS: Accessibility > Display > Reduce Transparency. Glass turns solid frosted. */
    @Volatile var reduceTransparency: Boolean = sp.getBoolean(REDUCE_TRANSPARENCY, false)
        set(v) { field = v; putBool(REDUCE_TRANSPARENCY, v) }

    /** System haptics for ticks and long presses. Read on every tick, so cached. */
    @Volatile var haptics: Boolean = sp.getBoolean(HAPTICS, true)
        set(v) { field = v; putBool(HAPTICS, v) }

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

    // ---- Locked and Hidden apps (iOS 18 "Require Face ID") ----

    var lockedApps: Set<String>
        get() = stringSet(LOCKED_APPS)
        set(v) = putSet(LOCKED_APPS, v)

    var hiddenApps: Set<String>
        get() = stringSet(HIDDEN_APPS)
        set(v) = putSet(HIDDEN_APPS, v)

    /** True when the icons should use their dark look right now. */
    fun iconsDark(): Boolean = when (iconAppearance) {
        Appearance.LIGHT -> false
        Appearance.DARK -> true
        Appearance.AUTO -> appContext.isDarkMode
    }

    enum class Appearance { AUTO, LIGHT, DARK }

    enum class SwipeAction { SEARCH, NOTIFICATIONS, SPLIT, NOTHING }

    companion object {
        const val ICON_STYLE = "icon_style"
        const val ICON_APPEARANCE = "icon_appearance"
        const val TINT_COLOR = "tint_color"
        const val LARGE_ICONS = "large_icons"
        const val SHOW_LABELS = "show_labels"
        const val ICON_SIZE = "icon_size"
        const val ICON_PACK = "icon_pack"
        const val LABEL_SIZE = "label_size"
        const val SHOW_DOCK = "show_dock"
        const val SHOW_SEARCH_PILL = "show_search_pill"
        const val NEW_APPS_TO_HOME = "new_apps_to_home"
        const val SHOW_APP_LIBRARY = "show_app_library"
        const val SWIPE_DOWN = "swipe_down"
        const val SHOW_BADGES = "show_badges"
        const val BADGES_IN_LIBRARY = "badges_in_library"
        const val DIM_WALLPAPER = "dim_wallpaper"
        const val BLUR_WALLPAPER = "blur_wallpaper"
        const val REDUCE_MOTION = "reduce_motion"
        const val REDUCE_TRANSPARENCY = "reduce_transparency"
        const val HAPTICS = "haptics"
        const val GLASS_INTENSITY = "glass_intensity"
        const val LOCKED_APPS = "locked_apps"
        const val HIDDEN_APPS = "hidden_apps"

        /** iOS Large icons are about a fifth bigger. */
        const val LARGE_SCALE = 1.2f
    }
}
