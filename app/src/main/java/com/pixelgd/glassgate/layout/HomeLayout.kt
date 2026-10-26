package com.pixelgd.glassgate.layout

import org.json.JSONArray
import org.json.JSONObject

/** Something placed on a home page. */
sealed class HomeItem {
    abstract var x: Int
    abstract var y: Int
    open val spanX: Int get() = 1
    open val spanY: Int get() = 1

    data class App(val key: String, override var x: Int = 0, override var y: Int = 0) : HomeItem()

    data class Folder(
        val id: Long,
        var title: String,
        val keys: MutableList<String>,
        override var x: Int = 0,
        override var y: Int = 0,
    ) : HomeItem()

    data class Widget(
        val appWidgetId: Int,
        val provider: String,
        override var spanX: Int,
        override var spanY: Int,
        override var x: Int = 0,
        override var y: Int = 0,
    ) : HomeItem()

    fun covers(cx: Int, cy: Int) = cx >= x && cx < x + spanX && cy >= y && cy < y + spanY
}

class HomePage(val items: MutableList<HomeItem> = ArrayList()) {
    fun itemAt(x: Int, y: Int): HomeItem? = items.firstOrNull { it.covers(x, y) }
    fun isFree(x: Int, y: Int, spanX: Int, spanY: Int, cols: Int, rows: Int, ignore: HomeItem? = null): Boolean {
        if (x < 0 || y < 0 || x + spanX > cols || y + spanY > rows) return false
        for (i in x until x + spanX) for (j in y until y + spanY) {
            val hit = itemAt(i, j)
            if (hit != null && hit !== ignore) return false
        }
        return true
    }
}

/**
 * The whole home screen: a grid of [cols] x [rows] cells per page, plus the dock.
 * Edit it through [LayoutStore] so changes are saved and views are told.
 */
class HomeLayout(
    var cols: Int = 4,
    var rows: Int = 6,
    val pages: MutableList<HomePage> = ArrayList(),
    val dock: MutableList<String> = ArrayList(),
) {
    /** Every app key on the home screen, in pages, folders and the dock. */
    fun allKeys(): Set<String> {
        val out = LinkedHashSet<String>(dock)
        for (p in pages) for (item in p.items) when (item) {
            is HomeItem.App -> out += item.key
            is HomeItem.Folder -> out += item.keys
            is HomeItem.Widget -> Unit
        }
        return out
    }

    /** Puts [item] (1x1 unless it has a span) in the first free cell, adding a page if needed. */
    fun placeFirstFree(item: HomeItem, fromPage: Int = 0) {
        for (pi in fromPage until pages.size) {
            val p = pages[pi]
            for (y in 0 until rows) for (x in 0 until cols) {
                if (p.isFree(x, y, item.spanX, item.spanY, cols, rows)) {
                    item.x = x; item.y = y
                    p.items += item
                    return
                }
            }
        }
        pages += HomePage()
        item.x = 0; item.y = 0
        pages.last().items += item
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("cols", cols)
        put("rows", rows)
        put("dock", JSONArray(dock))
        put("pages", JSONArray().apply {
            for (p in pages) put(JSONArray().apply { for (i in p.items) put(itemToJson(i)) })
        })
    }

    companion object {
        const val DOCK_MAX = 4

        fun fromJson(o: JSONObject): HomeLayout {
            val l = HomeLayout(o.optInt("cols", 4), o.optInt("rows", 6))
            o.optJSONArray("dock")?.let { a -> for (i in 0 until a.length()) l.dock += a.getString(i) }
            o.optJSONArray("pages")?.let { pa ->
                for (i in 0 until pa.length()) {
                    val page = HomePage()
                    val items = pa.getJSONArray(i)
                    for (j in 0 until items.length()) itemFromJson(items.getJSONObject(j))?.let { page.items += it }
                    l.pages += page
                }
            }
            return l
        }

        private fun itemToJson(i: HomeItem) = JSONObject().apply {
            put("x", i.x)
            put("y", i.y)
            when (i) {
                is HomeItem.App -> { put("t", "app"); put("key", i.key) }
                is HomeItem.Folder -> {
                    put("t", "folder"); put("id", i.id); put("title", i.title); put("keys", JSONArray(i.keys))
                }
                is HomeItem.Widget -> {
                    put("t", "widget"); put("wid", i.appWidgetId); put("provider", i.provider)
                    put("sx", i.spanX); put("sy", i.spanY)
                }
            }
        }

        private fun itemFromJson(o: JSONObject): HomeItem? {
            val x = o.optInt("x")
            val y = o.optInt("y")
            return when (o.optString("t")) {
                "app" -> HomeItem.App(o.getString("key"), x, y)
                "folder" -> {
                    val a = o.getJSONArray("keys")
                    HomeItem.Folder(o.getLong("id"), o.optString("title"),
                        MutableList(a.length()) { a.getString(it) }, x, y)
                }
                "widget" -> HomeItem.Widget(o.getInt("wid"), o.optString("provider"),
                    o.optInt("sx", 2), o.optInt("sy", 2), x, y)
                else -> null
            }
        }
    }
}
