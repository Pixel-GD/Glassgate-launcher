package com.pixelgd.glassgate.settings

import com.pixelgd.glassgate.layout.HomeItem
import com.pixelgd.glassgate.layout.HomeLayout
import com.pixelgd.glassgate.layout.HomePage

/**
 * Changes the home grid size and reflows every page into it, like iOS when the icon size
 * changes: widgets keep their place where they fit, everything else fills in reading order,
 * and whatever no longer fits spills onto a new page right after.
 */
object GridResize {

    fun apply(l: HomeLayout, cols: Int, rows: Int) {
        if (l.cols == cols && l.rows == rows) return
        l.cols = cols
        l.rows = rows
        val old = l.pages.toList()
        l.pages.clear()
        for (p in old) {
            var page = HomePage().also { l.pages += it }
            val widgets = p.items.filterIsInstance<HomeItem.Widget>()
            val others = p.items.filter { it !is HomeItem.Widget }.sortedWith(compareBy({ it.y }, { it.x }))
            for (w in widgets) w.spanX = w.spanX.coerceAtMost(cols)
            for (w in widgets) w.spanY = w.spanY.coerceAtMost(rows)
            for (item in widgets + others) {
                val keepsPlace = item is HomeItem.Widget &&
                    page.isFree(item.x, item.y, item.spanX, item.spanY, cols, rows)
                if (keepsPlace) { page.items += item; continue }
                if (!placeIn(page, item, cols, rows)) {
                    page = HomePage().also { l.pages.add(l.pages.indexOf(page) + 1, it) }
                    placeIn(page, item, cols, rows)
                }
            }
        }
    }

    private fun placeIn(page: HomePage, item: HomeItem, cols: Int, rows: Int): Boolean {
        for (y in 0 until rows) for (x in 0 until cols) {
            if (page.isFree(x, y, item.spanX, item.spanY, cols, rows)) {
                item.x = x
                item.y = y
                page.items += item
                return true
            }
        }
        return false
    }
}
