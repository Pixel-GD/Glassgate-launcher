package com.pixelgd.glassgate.badges

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.home.AppIconView
import com.pixelgd.glassgate.util.Spring
import java.text.NumberFormat

/**
 * The iOS notification badge: a red circle (a capsule for bigger numbers) with a white count,
 * sitting over the icon's top-right corner and springing in when the first notification lands.
 * [attach] wires one into an [AppIconView] through its badgeDrawer hook.
 */
class BadgeDrawer private constructor(private val view: AppIconView) : Badges.Listener {

    private val launcher = Launcher.of(view.context)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
    private val rect = RectF()

    private var shown = 0
    private var label = ""
    private val scale = Spring(Spring.STIFF_BOUNCY, Spring.DAMP_BOUNCY) { view.invalidate() }

    private fun currentCount(): Int {
        if (!launcher.prefs.showBadges) return 0
        return view.app?.let { Badges.count(it) } ?: 0
    }

    /** Syncs to the store; animates only when the badge appears or disappears. */
    private fun sync(animate: Boolean) {
        val n = currentCount()
        if (n == shown) return
        val was = shown
        shown = n
        if (n > 0) label = format(n)
        when {
            !animate -> scale.snapTo(if (n > 0) 1f else 0f)
            was == 0 -> scale.animateTo(1f)
            n == 0 -> scale.animateTo(0f, onEnd = { view.invalidate() })
            else -> {
                // A new count gives a small bump, like iOS.
                scale.snapTo(1.18f)
                scale.animateTo(1f)
            }
        }
        view.invalidate()
    }

    override fun onBadgesChanged(changed: Set<String>) {
        val app = view.app ?: return
        if (Badges.keyOf(app) in changed) sync(animate = view.isShown)
    }

    private fun draw(canvas: Canvas, iconLeft: Float, iconTop: Float, iconSize: Float) {
        // The view can be re-bound to another app; catch up without animating.
        if (currentCount() != shown && !scale.isRunning) sync(animate = false)
        val s = scale.value
        if (s <= 0.01f || label.isEmpty()) return

        val d = iconSize * 0.39f
        text.textSize = d * 0.64f
        val w = maxOf(d, text.measureText(label) + d * 0.62f)
        // Hangs over the corner by about a third of its height, as on iOS.
        val right = iconLeft + iconSize + d * 0.30f
        val top = iconTop - d * 0.30f
        val cx = right - w / 2f
        val cy = top + d / 2f

        canvas.save()
        canvas.scale(s, s, cx, cy)
        rect.set(right - w, top, right, top + d)
        canvas.drawRoundRect(rect, d / 2f, d / 2f, fill)
        val fm = text.fontMetrics
        canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, text)
        canvas.restore()
    }

    private fun format(n: Int): String =
        if (n > MAX) NumberFormat.getIntegerInstance().format(MAX) + "+"
        else NumberFormat.getIntegerInstance().format(n)

    companion object {
        /** iOS systemRed. */
        val RED = Color.rgb(255, 59, 48)
        private const val MAX = 9999

        /** Gives [view] a badge that follows [Badges] while the view is attached. */
        fun attach(view: AppIconView) {
            val drawer = BadgeDrawer(view)
            view.badgeDrawer = drawer::draw
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    Badges.addListener(drawer)
                    // The badge overhangs the icon, so let the parent draw past this view's edges.
                    (v.parent as? ViewGroup)?.clipChildren = false
                    drawer.sync(animate = false)
                }

                override fun onViewDetachedFromWindow(v: View) {
                    Badges.removeListener(drawer)
                    drawer.scale.snapTo(if (drawer.shown > 0) 1f else 0f)
                }
            })
        }
    }
}
