package com.pixelgd.glassgate.edit

import com.pixelgd.glassgate.layout.HomeItem
import com.pixelgd.glassgate.layout.HomePage

/**
 * Where icons go when one is dropped on a page. Like iOS 18+, icons can sit anywhere: dropping
 * in an empty cell just takes it, and dropping on an icon pushes that icon (and any run of icons
 * behind it) one cell forward in reading order, skipping widgets, until a gap absorbs the push.
 * If the push runs off the end of the page, the last icon overflows to the next page.
 */
object Reflow {

    class Push(
        /** New cells for the icons that move, as (x, y). */
        val moves: Map<HomeItem, Pair<Int, Int>>,
        /** The icon pushed off the page, if the page was full from the drop cell on. */
        val overflow: HomeItem?,
    )

    /**
     * Plans dropping a 1x1 icon at ([cx], [cy]) on [page], ignoring [ignore] (the icon being
     * dragged, when it comes from this page). Null when the cell is covered by a widget.
     */
    fun push(page: HomePage, cols: Int, rows: Int, cx: Int, cy: Int, ignore: HomeItem?): Push? {
        val n = cols * rows
        val fixed = BooleanArray(n)
        val cells = arrayOfNulls<HomeItem>(n)
        for (item in page.items) {
            if (item === ignore) continue
            if (item.spanX > 1 || item.spanY > 1) {
                for (i in item.x until item.x + item.spanX) for (j in item.y until item.y + item.spanY) {
                    if (i in 0 until cols && j in 0 until rows) fixed[j * cols + i] = true
                }
            } else if (item.x in 0 until cols && item.y in 0 until rows) {
                cells[item.y * cols + item.x] = item
            }
        }
        val start = cy * cols + cx
        if (start !in 0 until n || fixed[start]) return null

        val moves = LinkedHashMap<HomeItem, Pair<Int, Int>>()
        var carry = cells[start]
        var i = start
        while (carry != null) {
            i++
            while (i < n && fixed[i]) i++
            if (i >= n) return Push(moves, carry)
            val next = cells[i]
            moves[carry] = (i % cols) to (i / cols)
            carry = next
        }
        return Push(moves, null)
    }

    /** X centre of dock slot [index] when the dock holds [count] icons; mirrors DockView.onLayout. */
    fun dockCenter(index: Int, count: Int, dockWidth: Int, pad: Int, slots: Int): Int {
        val slot = (dockWidth - pad * 2) / slots
        val start = (dockWidth - slot * count) / 2
        return start + slot * index + slot / 2
    }
}
