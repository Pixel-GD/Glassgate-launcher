package com.pixelgd.glassgate.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.util.SparseArray
import android.view.ViewGroup
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.glass.GlassStyle
import com.pixelgd.glassgate.layout.HomeItem
import com.pixelgd.glassgate.layout.HomeLayout
import java.util.WeakHashMap

/** The three iOS widget sizes, in home grid cells. */
enum class WidgetSize(val spanX: Int, val spanY: Int, val label: String) {
    SMALL(2, 2, "Small"),
    MEDIUM(4, 2, "Medium"),
    LARGE(4, 4, "Large");

    companion object {
        fun of(item: HomeItem.Widget): WidgetSize = when {
            item.spanY >= 4 -> LARGE
            item.spanX >= 4 -> MEDIUM
            else -> SMALL
        }
    }
}

/** Glassgate's own widgets, drawn by the launcher instead of hosted from another app. */
enum class BuiltIn(val id: String, val title: String, val blurb: String) {
    CLOCK("clock", "Clock", "See the time at a glance."),
    WEATHER("weather", "Weather", "Current conditions and the forecast where you are."),
    BATTERY("battery", "Batteries", "Your phone's charge and charging status."),
}

/**
 * What a [HomeItem.Widget] shows, decoded from its provider string so the layout model
 * needs no widget-specific fields:
 *
 * - a hosted app widget: `provider` is a flattened ComponentName, `appWidgetId` is real
 * - a built-in: `provider` is `glassgate:clock`, `glassgate:weather` or `glassgate:battery`
 * - a stack: `provider` is `glassgate:stack;` then entries split by `;`, each a built-in
 *   provider or `<appWidgetId>@<component>`
 */
sealed class WidgetSpec {
    data class Hosted(val appWidgetId: Int, val component: ComponentName?) : WidgetSpec()
    data class Glass(val kind: BuiltIn) : WidgetSpec()
    data class Stack(val entries: List<WidgetSpec>) : WidgetSpec()

    /** The provider string for this spec when it is one entry of a stack. */
    fun encodeEntry(): String = when (this) {
        is Hosted -> "$appWidgetId@${component?.flattenToString().orEmpty()}"
        is Glass -> PREFIX + kind.id
        is Stack -> entries.joinToString(";") { it.encodeEntry() }
    }

    /** Hosted app widget ids this spec owns, for cleanup when it is removed. */
    fun hostedIds(): List<Int> = when (this) {
        is Hosted -> listOf(appWidgetId)
        is Glass -> emptyList()
        is Stack -> entries.flatMap { it.hostedIds() }
    }

    fun toItem(size: WidgetSize): HomeItem.Widget = when (this) {
        is Hosted -> HomeItem.Widget(appWidgetId, component?.flattenToString().orEmpty(), size.spanX, size.spanY)
        is Glass -> HomeItem.Widget(NO_ID, PREFIX + kind.id, size.spanX, size.spanY)
        is Stack -> HomeItem.Widget(NO_ID, STACK + entries.joinToString(";") { it.encodeEntry() }, size.spanX, size.spanY)
    }

    companion object {
        const val PREFIX = "glassgate:"
        const val STACK = "glassgate:stack;"
        const val NO_ID = -1

        fun of(item: HomeItem.Widget): WidgetSpec = when {
            item.provider.startsWith(STACK) -> Stack(
                item.provider.removePrefix(STACK).split(';').filter { it.isNotBlank() }.map { entry(it) },
            )
            item.provider.startsWith(PREFIX) -> entry(item.provider)
            else -> Hosted(item.appWidgetId, ComponentName.unflattenFromString(item.provider))
        }

        private fun entry(s: String): WidgetSpec {
            if (s.startsWith(PREFIX)) {
                val id = s.removePrefix(PREFIX)
                return Glass(BuiltIn.entries.firstOrNull { it.id == id } ?: BuiltIn.CLOCK)
            }
            val at = s.indexOf('@')
            val id = s.substring(0, at.coerceAtLeast(0)).toIntOrNull() ?: NO_ID
            return Hosted(id, if (at >= 0) ComponentName.unflattenFromString(s.substring(at + 1)) else null)
        }

        /** The iOS Smart Stack: clock, weather and batteries in one slot. */
        fun smartStack() = Stack(listOf(Glass(BuiltIn.CLOCK), Glass(BuiltIn.WEATHER), Glass(BuiltIn.BATTERY)))
    }
}

/** Glass for widget cards. */
val GlassStyle.Companion.WIDGET: GlassStyle
    get() = GlassStyle(cornerRadius = 22f, blur = 30f, refraction = 10f, bezel = 14f, lightTint = 0.16f, darkTint = 0.26f)

/**
 * Widget services: the AppWidgetHost, cached host views, and the layout operations other
 * modules need (place, remove, stack). Main thread only.
 *
 *     Widgets.of(context).remove(item)        // e.g. from a context menu or edit mode
 *     Widgets.of(context).stackOnto(target, dropped)   // edit mode: drop a widget on one of the same size
 *     WidgetPicker.show(activity)             // the add-widget gallery
 */
class Widgets private constructor(private val app: Context) {

    val manager: AppWidgetManager = AppWidgetManager.getInstance(app)
    val host = AppWidgetHost(app, HOST_ID)
    private val launcher get() = Launcher.of(app)

    /** Host views per activity context, reused across page rebuilds so widgets don't reload. */
    private val views = WeakHashMap<Context, SparseArray<AppWidgetHostView>>()

    fun startListening() = runCatching { host.startListening() }
    fun stopListening() = runCatching { host.stopListening() }

    fun info(appWidgetId: Int): AppWidgetProviderInfo? =
        if (appWidgetId < 0) null else manager.getAppWidgetInfo(appWidgetId)

    /** A host view for [appWidgetId], detached from any old parent, or null if its provider is gone. */
    fun hostView(context: Context, appWidgetId: Int): AppWidgetHostView? {
        val info = info(appWidgetId) ?: return null
        val cache = views.getOrPut(context) { SparseArray() }
        val cached = cache[appWidgetId]
        if (cached != null && cached.appWidgetInfo?.provider == info.provider) {
            (cached.parent as? ViewGroup)?.removeView(cached)
            return cached
        }
        val v = host.createView(context, appWidgetId, info)
        cache.put(appWidgetId, v)
        return v
    }

    /** Puts [item] on [page] if it fits there, else on the next page with room. Returns the page. */
    fun place(item: HomeItem.Widget, page: Int): Int {
        var placedOn = 0
        launcher.layout.edit { l ->
            placedOn = placeIn(l, item, page)
        }
        return placedOn
    }

    private fun placeIn(l: HomeLayout, item: HomeItem.Widget, page: Int): Int {
        val start = page.coerceIn(0, (l.pages.size - 1).coerceAtLeast(0))
        for (pi in start until l.pages.size) {
            val p = l.pages[pi]
            for (y in 0..l.rows - item.spanY) for (x in 0..l.cols - item.spanX) {
                if (p.isFree(x, y, item.spanX, item.spanY, l.cols, l.rows)) {
                    item.x = x; item.y = y
                    p.items += item
                    return pi
                }
            }
        }
        l.placeFirstFree(item, l.pages.size)
        return l.pages.size - 1
    }

    /** Removes a widget from the home screen and releases its app widget ids. */
    fun remove(item: HomeItem.Widget) {
        for (id in WidgetSpec.of(item).hostedIds()) deleteId(id)
        launcher.layout.edit { l -> for (p in l.pages) p.items.removeAll { it === item } }
    }

    /**
     * Drops [dropped] onto [target], making (or growing) a stack in the target's slot, the way
     * iOS stacks widgets of the same size. Returns false when the sizes differ.
     */
    fun stackOnto(target: HomeItem.Widget, dropped: HomeItem.Widget): Boolean {
        if (WidgetSize.of(target) != WidgetSize.of(dropped) || target === dropped) return false
        val entries = flatten(WidgetSpec.of(target)) + flatten(WidgetSpec.of(dropped))
        val stacked = WidgetSpec.Stack(entries).toItem(WidgetSize.of(target)).apply { x = target.x; y = target.y }
        launcher.layout.edit { l ->
            for (p in l.pages) {
                val i = p.items.indexOfFirst { it === target }
                if (i >= 0) p.items[i] = stacked
                p.items.removeAll { it === dropped }
            }
        }
        return true
    }

    /** Takes the widget on top of a stack out of it, back to a single widget in the same slot. */
    fun removeFromStack(stack: HomeItem.Widget, index: Int) {
        val spec = WidgetSpec.of(stack) as? WidgetSpec.Stack ?: return
        val entry = spec.entries.getOrNull(index) ?: return
        entry.hostedIds().forEach { deleteId(it) }
        val rest = spec.entries.filterIndexed { i, _ -> i != index }
        val size = WidgetSize.of(stack)
        val replacement = when (rest.size) {
            0 -> null
            1 -> rest[0].toItem(size)
            else -> WidgetSpec.Stack(rest).toItem(size)
        }?.apply { x = stack.x; y = stack.y }
        launcher.layout.edit { l ->
            for (p in l.pages) {
                val i = p.items.indexOfFirst { it === stack }
                if (i < 0) continue
                if (replacement != null) p.items[i] = replacement else p.items.removeAt(i)
            }
        }
    }

    fun deleteId(id: Int) {
        if (id < 0) return
        runCatching { host.deleteAppWidgetId(id) }
        views.values.forEach { it.remove(id) }
    }

    private fun flatten(s: WidgetSpec): List<WidgetSpec> = if (s is WidgetSpec.Stack) s.entries else listOf(s)

    companion object {
        /** Any stable number; identifies Glassgate's widget host to the system. */
        const val HOST_ID = 0x6C61

        private var instance: Widgets? = null

        fun of(context: Context): Widgets =
            instance ?: Widgets(context.applicationContext).also { instance = it }
    }
}
