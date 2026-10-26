package com.pixelgd.glassgate.library

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.pixelgd.glassgate.glass.GlassDrawable
import com.pixelgd.glassgate.glass.GlassStyle
import com.pixelgd.glassgate.home.AppIconView
import com.pixelgd.glassgate.home.HomeActions
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.sp

/**
 * One App Library folder: a glass tile split into four quadrants with the category name under
 * it. The first three quadrants are big icons that open their app straight away; the fourth is
 * a cluster of four small icons that opens the whole category. A folder with four or fewer apps
 * shows them all big, like iOS.
 */
@SuppressLint("ViewConstructor")
class CategoryTileView(
    context: Context,
    val group: LibraryGroup,
    private val tilePx: Int,
    private val actions: HomeActions,
    private val onOpen: (CategoryTileView) -> Unit,
) : ViewGroup(context) {

    private val glass = GlassDrawable(this, GlassStyle.LIBRARY_TILE)
    private val pad = tilePx * 0.085f
    private val gap = tilePx * 0.075f
    private val quad = ((tilePx - pad * 2 - gap) / 2f).toInt()
    private val title = context.getString(group.category.title)
    private val labelGap = context.dp(7f)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = context.sp(12.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        setShadowLayer(context.dp(3f), 0f, context.dp(0.5f), Color.argb(90, 0, 0, 0))
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        val all = group.apps
        val bigCount = if (all.size <= 4) all.size else 3
        for (app in all.take(bigCount)) {
            addView(AppIconView(context, quad, showLabel = false).apply {
                this.app = app
                setOnClickListener { actions.launch(app, it) }
                setOnLongClickListener { actions.showContextMenu(null, app, it); true }
            })
        }
        if (all.size > 4) {
            addView(ClusterView(context, quad, all.subList(3, minOf(all.size, 7))).apply {
                contentDescription = title
                setOnClickListener { onOpen(this@CategoryTileView) }
            })
        }
        contentDescription = title
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = MeasureSpec.makeMeasureSpec(quad, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(spec, spec)
        setMeasuredDimension(tilePx, (tilePx + labelGap + labelPaint.fontSpacing).toInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val x = (pad + (i % 2) * (quad + gap)).toInt()
            val y = (pad + (i / 2) * (quad + gap)).toInt()
            getChildAt(i).layout(x, y, x + quad, y + quad)
        }
    }

    override fun onDraw(canvas: Canvas) {
        glass.setBounds(0, 0, tilePx, tilePx)
        glass.draw(canvas)
        val text = TextUtils.ellipsize(title, labelPaint, width.toFloat(), TextUtils.TruncateAt.END)
        canvas.drawText(text, 0, text.length, width / 2f, tilePx + labelGap - labelPaint.ascent(), labelPaint)
    }

    /** The glass samples the wallpaper at its window position, so redraw when it moves. */
    fun onMoved() = invalidate()
}

/**
 * The fourth quadrant of a tile: up to four small icons on a 2x2 grid that open the category.
 * Takes all touches itself so the small icons don't do their own press animation.
 */
@SuppressLint("ViewConstructor")
class ClusterView(context: Context, sizePx: Int, apps: List<com.pixelgd.glassgate.apps.AppEntry>) : ViewGroup(context) {

    private val gap = sizePx * 0.1f
    private val mini = ((sizePx - gap) / 2f).toInt()

    private val pressSpring = Spring(Spring.STIFF_BOUNCY, Spring.DAMP_BOUNCY) { v ->
        scaleX = v; scaleY = v
    }.also { it.snapTo(1f) }

    init {
        isClickable = true
        isHapticFeedbackEnabled = true
        for (app in apps) {
            addView(AppIconView(context, mini, showLabel = false).apply {
                this.app = app
                isClickable = false
                isLongClickable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = MeasureSpec.makeMeasureSpec(mini, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(spec, spec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val x = ((i % 2) * (mini + gap)).toInt()
            val y = ((i / 2) * (mini + gap)).toInt()
            getChildAt(i).layout(x, y, x + mini, y + mini)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressSpring.animateTo(0.88f, 0f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressSpring.animateTo(1f)
        }
        return super.onTouchEvent(event)
    }
}
