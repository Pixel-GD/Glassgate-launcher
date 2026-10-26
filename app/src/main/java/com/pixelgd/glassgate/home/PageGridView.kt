package com.pixelgd.glassgate.home

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.layout.HomeItem
import com.pixelgd.glassgate.layout.HomePage
import com.pixelgd.glassgate.widgets.WidgetViews

/**
 * One home page: lays out icons, folders and widgets on the cols x rows grid. Each child's
 * layout params carry its [HomeItem]; widgets span several cells.
 */
@SuppressLint("ViewConstructor")
class PageGridView(
    context: Context,
    private val actions: HomeActions,
    val cols: Int,
    val rows: Int,
) : ViewGroup(context) {

    class LayoutParams(val item: HomeItem) : ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

    private val launcher = Launcher.of(context)
    var page: HomePage? = null
        private set

    val cellWidth get() = (width - paddingLeft - paddingRight) / cols
    val cellHeight get() = (height - paddingTop - paddingBottom) / rows

    fun bind(page: HomePage, iconSizePx: Int, showLabels: Boolean) {
        this.page = page
        removeAllViews()
        for (item in page.items) {
            val v: View = when (item) {
                is HomeItem.App -> {
                    val app = launcher.apps[item.key] ?: continue
                    AppIconView(context, iconSizePx, showLabels).apply {
                        this.app = app
                        setOnClickListener { actions.launch(app, it) }
                        setOnLongClickListener { actions.showContextMenu(item, app, it); true }
                    }
                }
                is HomeItem.Folder -> FolderIconView(context, iconSizePx, showLabels).apply {
                    folder = item
                    setOnClickListener { actions.openFolder(item, it) }
                    setOnLongClickListener { actions.showContextMenu(item, null, it); true }
                }
                is HomeItem.Widget -> WidgetViews.create(context, actions, item, iconSizePx, showLabels)
            }
            addView(v, LayoutParams(item))
        }
    }

    /** The cell under a point in this view's coordinates, or null outside the grid. */
    fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        val cx = ((x - paddingLeft) / cellWidth).toInt()
        val cy = ((y - paddingTop) / cellHeight).toInt()
        return if (cx in 0 until cols && cy in 0 until rows) cx to cy else null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = (w - paddingLeft - paddingRight) / cols
        val ch = (h - paddingTop - paddingBottom) / rows
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val item = (c.layoutParams as LayoutParams).item
            c.measure(
                MeasureSpec.makeMeasureSpec(cw * item.spanX, MeasureSpec.EXACTLY),
                if (item is HomeItem.Widget) MeasureSpec.makeMeasureSpec(ch * item.spanY, MeasureSpec.EXACTLY)
                else MeasureSpec.makeMeasureSpec(ch, MeasureSpec.AT_MOST),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = cellWidth
        val ch = cellHeight
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val item = (c.layoutParams as LayoutParams).item
            val left = paddingLeft + item.x * cw
            // Icons sit near the top of their cell, like iOS, so labels line up across rows.
            val top = paddingTop + item.y * ch + if (item is HomeItem.Widget) 0 else (ch - c.measuredHeight) / 3
            c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
        }
    }
}
