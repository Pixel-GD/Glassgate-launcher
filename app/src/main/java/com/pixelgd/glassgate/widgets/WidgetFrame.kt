package com.pixelgd.glassgate.widgets

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.SizeF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.glass.GlassDrawable
import com.pixelgd.glassgate.glass.GlassStyle
import com.pixelgd.glassgate.glass.Squircle
import com.pixelgd.glassgate.home.HomeActions
import com.pixelgd.glassgate.layout.HomeItem
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.sp
import com.pixelgd.glassgate.util.thud
import com.pixelgd.glassgate.util.withAlpha
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Builds the view for a widget on a home page. */
object WidgetViews {
    fun create(context: Context, actions: HomeActions, item: HomeItem.Widget, iconSizePx: Int, showLabels: Boolean): View =
        WidgetFrame(context, item, iconSizePx, showLabels).apply {
            card.setContent(content(context, WidgetSpec.of(item), WidgetSize.of(item), item))
            onLongPress = { actions.showContextMenu(item, null, card) }
        }

    /** The inside of a card for [spec]: a hosted app widget, a glass widget, or a stack of them. */
    fun content(context: Context, spec: WidgetSpec, size: WidgetSize, item: HomeItem.Widget? = null): View = when (spec) {
        is WidgetSpec.Glass -> glass(context, spec.kind, size)
        is WidgetSpec.Hosted -> Widgets.of(context).hostView(context, spec.appWidgetId)?.apply {
            setPadding(0, 0, 0, 0)
        } ?: unavailable(context)
        is WidgetSpec.Stack -> WidgetStackView(context, spec, size, item)
    }

    fun glass(context: Context, kind: BuiltIn, size: WidgetSize): View = when (kind) {
        BuiltIn.CLOCK -> ClockWidgetView(context, size)
        BuiltIn.WEATHER -> WeatherWidgetView(context, size)
        BuiltIn.BATTERY -> BatteryWidgetView(context, size)
    }

    private fun unavailable(context: Context) = TextView(context).apply {
        text = "Widget unavailable"
        setTextColor(Color.WHITE.withAlpha(0.8f))
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
    }
}

/**
 * A widget's slot on the page. It fills the widget's cells and places a [WidgetCard] inside
 * them the iOS way: edges line up with the icons in the columns it covers, small widgets are
 * square, a large one is two smalls tall. Long-press on any part of the card is reported
 * through [onLongPress], even when the hosted widget is clickable.
 */
@SuppressLint("ViewConstructor")
class WidgetFrame(
    context: Context,
    val item: HomeItem.Widget,
    private val iconSizePx: Int,
    private val showLabels: Boolean,
) : ViewGroup(context) {

    val card = WidgetCard(context)
    var onLongPress: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private val longPress = Runnable {
        longPressed = true
        thud()
        // From here the frame intercepts, so the widget under the finger gets a cancel, not a click.
        onLongPress?.invoke()
    }

    init {
        addView(card)
        clipChildren = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val r = cardRect(w, h)
        card.measure(
            MeasureSpec.makeMeasureSpec(r.width().roundToInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(r.height().roundToInt(), MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rect = cardRect(r - l, b - t)
        val left = rect.left.roundToInt()
        val top = rect.top.roundToInt()
        card.layout(left, top, left + card.measuredWidth, top + card.measuredHeight)
    }

    private fun cardRect(w: Int, h: Int): RectF {
        val size = WidgetSize.of(item)
        val cellW = w / item.spanX.toFloat()
        val cellH = h / item.spanY.toFloat()
        val icon = min(iconSizePx.toFloat(), cellW)
        val inset = (cellW - icon) / 2f
        val gap = cellW - icon
        val small = cellW + icon
        val cardW = w - inset * 2
        val rowsOfSmall = if (size == WidgetSize.LARGE) 2 else 1
        var cardH = small * rowsOfSmall + gap * (rowsOfSmall - 1)
        // Line the top up with the icons in the same row (they sit a third of the way down).
        val labelH = if (showLabels) context.dp(5f) + context.sp(12f) * 1.2f else 0f
        var top = ((cellH - icon - labelH) / 3f).coerceAtLeast(0f)
        if (top + cardH > h) {
            cardH = min(cardH, h.toFloat())
            top = (h - cardH) / 2f
        }
        return RectF(inset, top, inset + cardW, top + cardH)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                longPressed = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return longPressed
    }

    /**
     * Pass a child's "don't intercept" up to the pager, but keep watching ourselves: a stack
     * or scrolling widget asks for it on every touch, and long-press must still work there.
     */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        parent?.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    /** Swallow touches the card doesn't use, so they don't count as a long-press on empty space. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                longPressed = false
            }
        }
        return true
    }
}

/**
 * A rounded glass card holding one widget. Glass sits behind the content, a bright rim sits
 * on top of it (so even opaque app widgets get the glass edge), and the content is clipped
 * to the card's corners. Usable anywhere, e.g. picker previews.
 */
class WidgetCard(context: Context) : FrameLayout(context) {

    private val glass = GlassDrawable(this, GlassStyle.WIDGET)
    private val rim = RimDrawable(context)
    private val loc = IntArray(2)
    private val lastLoc = IntArray(2)
    /** iOS keeps the same corner radius, about 22pt, on every widget size. */
    private val radius = context.dp(22f)

    /** Glass samples the backdrop at the card's screen position, so redraw when it moves. */
    private val moveWatcher = ViewTreeObserver.OnPreDrawListener {
        getLocationInWindow(loc)
        if (loc[0] != lastLoc[0] || loc[1] != lastLoc[1]) {
            lastLoc[0] = loc[0]; lastLoc[1] = loc[1]
            invalidate()
        }
        true
    }

    init {
        background = glass
        foreground = rim
        rim.radius = radius
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    fun setContent(v: View) {
        removeAllViews()
        addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(moveWatcher)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(moveWatcher)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = context.dp(1f)
        // Tell hosted widgets their real size so they pick the right layout.
        for (i in 0 until childCount) {
            val c = getChildAt(i) as? AppWidgetHostView ?: continue
            c.updateAppWidgetSize(Bundle(), listOf(SizeF(w / density, h / density)))
        }
    }
}

/** The glass card's 1px lit edge, drawn above the content. */
private class RimDrawable(context: Context) : Drawable() {
    private val density = context.dp(1f)
    private val launcher = Launcher.of(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    private val rect = RectF()
    private val path = Path()
    var radius = 0f

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b)
        rect.inset(density * 0.5f, density * 0.5f)
        Squircle.roundRect(rect, radius, path)
        val shift = launcher.tilt.x * b.width() * 0.3f
        paint.shader = LinearGradient(
            b.left + shift, b.top.toFloat(), b.right + shift, b.bottom.toFloat(),
            intArrayOf(Color.WHITE.withAlpha(0.55f), Color.WHITE.withAlpha(0.08f), Color.WHITE.withAlpha(0.28f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
