package com.pixelgd.glassgate.folders

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.pixelgd.glassgate.DismissibleOverlay
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.LauncherActivity
import com.pixelgd.glassgate.glass.GlassDrawable
import com.pixelgd.glassgate.glass.GlassStyle
import com.pixelgd.glassgate.home.AppIconView
import com.pixelgd.glassgate.home.FolderIconView
import com.pixelgd.glassgate.home.PagedView
import com.pixelgd.glassgate.layout.HomeItem
import com.pixelgd.glassgate.layout.HomeLayout
import com.pixelgd.glassgate.layout.LayoutStore
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.dpi
import com.pixelgd.glassgate.util.lerp
import com.pixelgd.glassgate.util.sp
import com.pixelgd.glassgate.util.withAlpha
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An open folder, iOS style: the home screen blurs and dims, the folder's glass icon grows into a
 * large glass card with its apps in 3x3 pages, the editable name sits above it and page dots
 * below. Tapping outside (or Back/Home) shrinks it back into the icon.
 *
 * Open one with [open]; it lives in [LauncherActivity.overlay].
 */
@SuppressLint("ViewConstructor")
class FolderView private constructor(
    private val activity: LauncherActivity,
    folder: HomeItem.Folder,
    private var source: FolderIconView?,
    private val startRenaming: Boolean,
) : ViewGroup(activity), LayoutStore.Listener, DismissibleOverlay {

    private val launcher = Launcher.of(activity)
    private val folderId = folder.id
    private var folder = folder
    private var boundKeys: List<String> = emptyList()

    private val iconPx = activity.dpi(launcher.prefs.iconSizeDp.toFloat())
    private val pad = activity.dpi(14f)
    private val titleGap = activity.dpi(18f)
    private val dotsGap = activity.dpi(14f)

    private val pager = PagedView(activity)
    private val dots = PageDotsView(activity)
    private val card = Card()
    private val glass = GlassDrawable(card, GlassStyle.FOLDER_OPEN)
    private var cardRadius = 0f

    private val title = EditText(activity).apply {
        background = null
        setPadding(0, 0, 0, 0)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.WHITE.withAlpha(0.5f))
        highlightColor = Color.WHITE.withAlpha(0.3f)
        textSize = 30f
        typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        letterSpacing = -0.02f
        setShadowLayer(activity.dp(6f), 0f, activity.dp(1f), Color.argb(60, 0, 0, 0))
        gravity = Gravity.START or Gravity.BOTTOM
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        imeOptions = EditorInfo.IME_ACTION_DONE
        setSelectAllOnFocus(true)
        setText(folder.title)
        setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { stopRenaming(); true } else false
        }
        setOnFocusChangeListener { _, focused -> if (!focused) commitTitle() }
    }

    /** Where the card ends up, and where it grows from (the folder icon). */
    private val dst = Rect()
    private val src = Rect()
    private val loc = IntArray(2)
    private val srcLoc = IntArray(2)

    private val openSpring = Spring(Spring.STIFF_UI, Spring.DAMP_UI) { apply(it) }
    private val closeSpring = Spring(Spring.STIFF_SNAP, Spring.DAMP_SNAP) { apply(it) }
    private var progress = 0f
    private var closing = false
    /** Set when a tap only ended renaming, so its release doesn't also close the folder. */
    private var swallowTap = false

    init {
        isFocusableInTouchMode = true
        card.isClickable = true
        card.background = glass
        card.clipToOutline = true
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cardRadius)
            }
        }
        card.addView(pager)
        pager.addScrollListener(dots)
        addView(card)
        addView(title)
        addView(dots)
        bindPages()
    }

    // ---- content ----

    private fun bindPages() {
        boundKeys = folder.keys.toList()
        val keep = pager.currentPage
        pager.removeAllViews()
        for (chunk in boundKeys.chunked(Folders.PAGE_SIZE)) {
            val page = FolderPageView(context, Folders.COLS, Folders.ROWS)
            for (key in chunk) {
                val app = launcher.apps[key] ?: continue
                page.addView(AppIconView(context, iconPx, true).apply {
                    this.app = app
                    setOnClickListener { activity.launch(app, it) }
                    setOnLongClickListener { activity.showContextMenu(null, app, it); true }
                })
            }
            pager.addView(page)
        }
        pager.snapToPage(keep, animate = false)
        dots.onPageScroll(pager.currentPage.toFloat(), pager.pageCount, false)
    }

    override fun onLayoutChanged(layout: HomeLayout) {
        val f = Folders.find(layout, folderId)
        if (f == null) {
            // Removed elsewhere (uninstall, edit mode): nothing to go back into.
            source = null
            dismiss()
            return
        }
        folder = f
        if (f.keys != boundKeys) bindPages()
        if (!title.hasFocus() && title.text.toString() != f.title) title.setText(f.title)
        // The home screen rebuilt its views; hide the new copy of our icon.
        source = findSource()?.also { it.alpha = 0f }
    }

    // ---- renaming ----

    fun startRenaming() {
        title.requestFocus()
        title.selectAll()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(title, 0)
    }

    private fun stopRenaming() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        requestFocus()
        commitTitle()
    }

    private fun commitTitle() {
        val f = Folders.find(launcher.layout.layout, folderId) ?: return
        val t = title.text.toString().trim()
        if (t.isEmpty()) {
            title.setText(f.title)
        } else if (t != f.title) {
            launcher.layout.edit { l -> Folders.find(l, folderId)?.title = t }
        }
    }

    // ---- open / close ----

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        launcher.layout.addListener(this)
        source?.alpha = 0f
        post {
            if (closing) return@post
            openSpring.animateTo(1f, 0f)
            if (startRenaming) startRenaming()
        }
    }

    override fun onDetachedFromWindow() {
        openSpring.cancel()
        closeSpring.cancel()
        launcher.layout.removeListener(this)
        if (title.hasFocus()) commitTitle()
        activity.home.setRenderEffect(null)
        source?.alpha = 1f
        findSource()?.alpha = 1f
        super.onDetachedFromWindow()
    }

    /** Shrinks back into the folder icon, then leaves. A second call while closing leaves now. */
    override fun dismiss() {
        if (closing) { removeNow(); return }
        closing = true
        if (title.hasFocus()) stopRenaming()
        source = source?.takeIf { it.isAttachedToWindow } ?: findSource()
        updateSource()
        openSpring.cancel()
        closeSpring.snapTo(progress)
        closeSpring.animateTo(0f, openSpring.velocity) { removeNow() }
    }

    private fun removeNow() {
        (parent as? ViewGroup)?.removeView(this)
    }

    private fun findSource(): FolderIconView? = findFolderIcon(activity.home)

    private fun findFolderIcon(v: View): FolderIconView? {
        if (v is FolderIconView) return v.takeIf { it.folder?.id == folderId }
        if (v is ViewGroup) for (i in 0 until v.childCount) findFolderIcon(v.getChildAt(i))?.let { return it }
        return null
    }

    // ---- geometry and animation ----

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)

        val cardW = min(w - context.dpi(36f) * 2, context.dpi(340f))
        val cellW = (cardW - pad * 2) / Folders.COLS
        val cellH = maxOf(cellW, iconPx + context.dpi(5f) + context.sp(12f).roundToInt() * 4 / 3 + context.dpi(14f))
        val cardH = cellH * Folders.ROWS + pad * 2
        val left = (w - cardW) / 2
        val top = (h - cardH) / 2 - context.dpi(12f)
        dst.set(left, top, left + cardW, top + cardH)

        card.measure(MeasureSpec.makeMeasureSpec(cardW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cardH, MeasureSpec.EXACTLY))
        title.measure(MeasureSpec.makeMeasureSpec(cardW - pad, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        dots.measure(MeasureSpec.makeMeasureSpec(cardW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val tl = dst.left + pad / 2
        title.layout(tl, dst.top - titleGap - title.measuredHeight, tl + title.measuredWidth, dst.top - titleGap)
        dots.layout(dst.left, dst.bottom + dotsGap, dst.right, dst.bottom + dotsGap + dots.measuredHeight)
        updateSource()
        apply(progress)
    }

    /** The folder icon's squircle, in this view's coordinates; a small square mid-card if gone. */
    private fun updateSource() {
        val s = source?.takeIf { it.isAttachedToWindow }
        if (s != null) {
            getLocationInWindow(loc)
            s.getLocationInWindow(srcLoc)
            val x = srcLoc[0] - loc[0] + (s.width - s.iconSizePx) / 2
            val y = srcLoc[1] - loc[1]
            src.set(x, y, x + s.iconSizePx, y + s.iconSizePx)
        } else {
            val cx = dst.centerX()
            val cy = dst.centerY()
            src.set(cx - iconPx / 2, cy - iconPx / 2, cx + iconPx / 2, cy + iconPx / 2)
        }
    }

    /** Lays everything out for open progress [t]: 0 is the folder icon, 1 fully open. */
    private fun apply(t: Float) {
        progress = t
        val fade = t.coerceIn(0f, 1f)
        val l = lerp(src.left.toFloat(), dst.left.toFloat(), t).roundToInt()
        val top = lerp(src.top.toFloat(), dst.top.toFloat(), t).roundToInt()
        val r = lerp(src.right.toFloat(), dst.right.toFloat(), t).roundToInt()
        val b = lerp(src.bottom.toFloat(), dst.bottom.toFloat(), t).roundToInt()
        if (r - l < 1 || b - top < 1 || dst.isEmpty) return

        // The pager keeps its open size and is scaled down with the card, so the grid of
        // full icons shrinks into the icon's mini grid.
        val s = (r - l) / dst.width().toFloat()
        pager.pivotX = 0f
        pager.pivotY = 0f
        pager.scaleX = s
        pager.scaleY = s
        pager.translationX = pad * s - pad
        pager.translationY = pad * s - pad

        val radiusDp = lerp(GlassStyle.FOLDER_ICON.cornerRadius, GlassStyle.FOLDER_OPEN.cornerRadius, fade)
        cardRadius = context.dp(radiusDp)
        glass.style = GlassStyle.FOLDER_OPEN.copy(cornerRadius = radiusDp)
        card.layout(l, top, r, b)
        card.invalidateOutline()
        card.invalidate()

        title.alpha = fade
        title.translationY = (1f - fade) * context.dp(14f)
        dots.alpha = fade

        setBackgroundColor(Color.BLACK.withAlpha(0.16f * fade))
        val blur = context.dp(26f) * fade
        activity.home.setRenderEffect(
            if (blur > 0.5f) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) else null,
        )
    }

    // ---- touch ----

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (closing) return true
        // While renaming, the first tap anywhere else just finishes the name.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && title.hasFocus() && !hit(title, ev)) {
            stopRenaming()
            swallowTap = true
            return true
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                if (!swallowTap && !closing && !hit(card, event)) dismiss()
                swallowTap = false
            }
            MotionEvent.ACTION_CANCEL -> swallowTap = false
        }
        return true
    }

    private fun hit(v: View, ev: MotionEvent) =
        ev.x >= v.left && ev.x < v.right && ev.y >= v.top && ev.y < v.bottom

    /** The glass card; its pager is laid out at the open size and scaled by [apply]. */
    private inner class Card : ViewGroup(activity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            pager.measure(MeasureSpec.makeMeasureSpec(w - pad * 2, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h - pad * 2, MeasureSpec.EXACTLY))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            pager.layout(pad, pad, pad + pager.measuredWidth, pad + pager.measuredHeight)
        }
    }

    companion object {
        /**
         * Opens [folder] growing out of [source] (its icon on the home page, if any).
         * With [rename], the name is selected for editing straight away (the menu's "Rename").
         */
        fun open(activity: LauncherActivity, folder: HomeItem.Folder, source: View?, rename: Boolean = false) {
            val overlay = activity.overlay
            for (i in overlay.childCount - 1 downTo 0) {
                if (overlay.getChildAt(i) is FolderView) overlay.removeViewAt(i)
            }
            overlay.addView(FolderView(activity, folder, source as? FolderIconView, rename),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }
}
