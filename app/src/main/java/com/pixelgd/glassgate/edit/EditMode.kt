package com.pixelgd.glassgate.edit

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.pixelgd.glassgate.LauncherActivity
import com.pixelgd.glassgate.home.AppIconView
import com.pixelgd.glassgate.home.DockView
import com.pixelgd.glassgate.home.FolderIconView
import com.pixelgd.glassgate.home.PageGridView

/**
 * Jiggle mode, iOS style: icons wiggle and get "−" badges, any icon can be dragged to a new
 * cell, another page, the dock or onto another icon to make a folder, and glass "Edit" / "Done"
 * buttons sit in the top corners. Tapping the wallpaper, Done, Home or Back leaves.
 *
 * The UI lives in an [EditLayer] in the activity's overlay, above the real home views, so the
 * home screen code doesn't need to know about edit mode.
 */
class EditMode(private val activity: LauncherActivity) {

    private var layer: EditLayer? = null

    val isActive: Boolean get() = layer != null

    /**
     * Shown as "Add Widget" in the Edit menu when set. The widgets module sets this to open
     * its widget gallery.
     */
    var onAddWidget: (() -> Unit)? = null

    fun enter() {
        if (layer != null) return
        val l = EditLayer(activity, this)
        layer = l
        activity.overlay.addView(l, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /** Animates out of jiggle mode. */
    fun exit() {
        layer?.dismiss()
    }

    internal fun onLayerGone(l: EditLayer) {
        if (layer === l) layer = null
    }

    /** True for app and folder icons on a page or in the dock. */
    fun canDrag(source: View): Boolean =
        (source is AppIconView || source is FolderIconView) && (source.parent is PageGridView || source.parent is DockView)

    /**
     * Picks up [source] mid-gesture (the context menu hands over a long-press that started to
     * move) and follows the rest of that gesture. Call [enter] first.
     */
    fun continueDrag(source: View, ev: MotionEvent) {
        val l = layer ?: return
        if (!l.beginDrag(source, ev.rawX, ev.rawY)) return
        source.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> l.dragTo(e.rawX, e.rawY)
                MotionEvent.ACTION_UP -> { source.setOnTouchListener(null); l.endDrag(cancel = false) }
                MotionEvent.ACTION_CANCEL -> { source.setOnTouchListener(null); l.endDrag(cancel = true) }
            }
            true
        }
    }
}
