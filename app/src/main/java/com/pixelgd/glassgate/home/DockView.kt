package com.pixelgd.glassgate.home

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.glass.GlassDrawable
import com.pixelgd.glassgate.glass.GlassStyle
import com.pixelgd.glassgate.util.dpi

/** The floating glass dock: up to four apps, no labels, evenly spaced. */
@SuppressLint("ViewConstructor")
class DockView(context: Context, private val actions: HomeActions) : ViewGroup(context) {

    private val launcher = Launcher.of(context)
    private val pad = context.dpi(14f)

    init {
        background = GlassDrawable(this, GlassStyle.DOCK)
        clipToPadding = false
    }

    fun bind(keys: List<String>, iconSizePx: Int) {
        removeAllViews()
        for (key in keys) {
            val app = launcher.apps[key] ?: continue
            addView(AppIconView(context, iconSizePx, showLabel = false).apply {
                this.app = app
                setOnClickListener { actions.launch(app, it) }
                setOnLongClickListener { actions.showContextMenu(null, app, it); true }
            })
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        var iconH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            iconH = maxOf(iconH, c.measuredHeight)
        }
        if (iconH == 0) iconH = context.dpi(60f)
        setMeasuredDimension(w, iconH + pad * 2)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val n = childCount
        if (n == 0) return
        val w = r - l
        // Same column centres as the 4-wide grid above, like iOS.
        val slot = (w - pad * 2) / HomeViewConfig.DOCK_SLOTS
        val used = slot * n
        val start = (w - used) / 2
        for (i in 0 until n) {
            val c = getChildAt(i)
            val cx = start + slot * i + slot / 2
            val left = cx - c.measuredWidth / 2
            c.layout(left, pad, left + c.measuredWidth, pad + c.measuredHeight)
        }
    }
}
