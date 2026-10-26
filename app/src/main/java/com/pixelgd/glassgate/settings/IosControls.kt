package com.pixelgd.glassgate.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.isDarkMode
import com.pixelgd.glassgate.util.lerp
import com.pixelgd.glassgate.util.sp
import com.pixelgd.glassgate.util.tick
import com.pixelgd.glassgate.util.withAlpha

/** iOS system colours for grouped settings screens, light and dark. */
class IosColors(context: Context) {
    val dark = context.isDarkMode
    val background = if (dark) Color.BLACK else Color.rgb(242, 242, 247)
    val card = if (dark) Color.rgb(28, 28, 30) else Color.WHITE
    val cardPressed = if (dark) Color.rgb(44, 44, 46) else Color.rgb(209, 209, 214)
    val label = if (dark) Color.WHITE else Color.BLACK
    val secondary = if (dark) Color.rgb(152, 152, 159) else Color.rgb(138, 138, 142)
    val separator = if (dark) Color.rgb(56, 56, 58) else Color.rgb(198, 198, 200)
    val accent = if (dark) Color.rgb(10, 132, 255) else Color.rgb(0, 122, 255)
    val green = if (dark) Color.rgb(48, 209, 88) else Color.rgb(52, 199, 89)
    val fill = if (dark) Color.rgb(57, 57, 61) else Color.rgb(233, 233, 234)
    val segmentTrack = if (dark) Color.rgb(28, 28, 31) else Color.rgb(238, 238, 239)
    val segmentThumb = if (dark) Color.rgb(99, 99, 102) else Color.WHITE
}

private fun blend(a: Int, b: Int, t: Float): Int = Color.argb(
    Math.round(lerp(Color.alpha(a).toFloat(), Color.alpha(b).toFloat(), t)),
    Math.round(lerp(Color.red(a).toFloat(), Color.red(b).toFloat(), t)),
    Math.round(lerp(Color.green(a).toFloat(), Color.green(b).toFloat(), t)),
    Math.round(lerp(Color.blue(a).toFloat(), Color.blue(b).toFloat(), t)),
)

/**
 * UISwitch: green capsule track and a white knob that springs across. While pressed the knob
 * stretches toward the side it will travel to, like the iOS 26 glass knob.
 */
@SuppressLint("ViewConstructor")
class IosSwitch(context: Context, private val colors: IosColors) : View(context) {

    var onChange: ((Boolean) -> Unit)? = null
    var isOn = false
        private set

    private val trackW = context.dp(51f)
    private val trackH = context.dp(31f)
    private val inset = context.dp(2f)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(context.dp(2.5f), 0f, context.dp(1.5f), Color.BLACK.withAlpha(0.22f))
    }
    private val rect = RectF()
    private var pos = 0f
    private var stretch = 0f
    private val posSpring = Spring(Spring.STIFF_UI, Spring.DAMP_UI) { pos = it; invalidate() }
    private val stretchSpring = Spring(Spring.STIFF_SNAP, Spring.DAMP_SNAP) { stretch = it; invalidate() }

    init {
        isClickable = true
        setOnClickListener { toggle() }
    }

    fun setOn(on: Boolean, animate: Boolean = false) {
        isOn = on
        val t = if (on) 1f else 0f
        if (animate) posSpring.animateTo(t) else posSpring.snapTo(t)
    }

    fun toggle() {
        setOn(!isOn, animate = true)
        tick()
        onChange?.invoke(isOn)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(Math.round(trackW), Math.round(trackH))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> stretchSpring.animateTo(1f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stretchSpring.animateTo(0f)
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val p = pos.coerceIn(0f, 1f)
        track.color = blend(colors.fill, colors.green, p)
        rect.set(0f, 0f, trackW, trackH)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, track)

        val d = trackH - inset * 2
        val w = d + stretch * context.dp(7f)
        val left = lerp(inset, trackW - inset - w, pos)
        rect.set(left, inset, left + w, inset + d)
        canvas.drawRoundRect(rect, d / 2, d / 2, knob)
    }
}

/**
 * UISlider: a thin track filled in the accent colour up to a white capsule knob. The knob
 * swells while dragged. Reports 0..1.
 */
@SuppressLint("ViewConstructor")
class IosSlider(context: Context, private val colors: IosColors) : View(context) {

    var onChange: ((Float) -> Unit)? = null
    var onChangeEnd: ((Float) -> Unit)? = null
    var value = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    private val trackH = context.dp(4f)
    private val knobW = context.dp(38f)
    private val knobH = context.dp(24f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(context.dp(3f), 0f, context.dp(1.5f), Color.BLACK.withAlpha(0.2f))
    }
    private val rect = RectF()
    private var grow = 0f
    private val growSpring = Spring(Spring.STIFF_UI, Spring.DAMP_UI) { grow = it; invalidate() }
    private var lastRegion = -1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Math.round(context.dp(44f)))
    }

    private fun usable() = width - knobW

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                growSpring.animateTo(1f)
                lastRegion = -1
                seek(event.x)
            }
            MotionEvent.ACTION_MOVE -> seek(event.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                growSpring.animateTo(0f)
                onChangeEnd?.invoke(value)
            }
        }
        return true
    }

    private fun seek(x: Float) {
        value = ((x - knobW / 2) / usable()).coerceIn(0f, 1f)
        // A light tick at the ends and when crossing the middle (the stock setting).
        val region = when {
            value <= 0f -> 0
            value >= 1f -> 3
            value < 0.5f -> 1
            else -> 2
        }
        if (lastRegion != -1 && region != lastRegion) tick()
        lastRegion = region
        onChange?.invoke(value)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val cx = knobW / 2 + usable() * value
        paint.color = colors.fill
        rect.set(knobW / 2, cy - trackH / 2, width - knobW / 2, cy + trackH / 2)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, paint)
        paint.color = colors.accent
        rect.right = cx
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, paint)

        val s = 1f + grow * 0.18f
        val w = knobW * s
        val h = knobH * s
        // A dragged knob turns slightly see-through, a nod to the iOS 26 glass knob.
        knob.alpha = Math.round(255 * (1f - grow * 0.25f))
        rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        canvas.drawRoundRect(rect, h / 2, h / 2, knob)
    }
}

/** UISegmentedControl: a capsule track with a sliding raised thumb. */
@SuppressLint("ViewConstructor")
class IosSegmented(
    context: Context,
    private val colors: IosColors,
    private val options: List<String>,
) : View(context) {

    var onSelect: ((Int) -> Unit)? = null
    var selected = 0
        private set

    private val h = context.dp(32f)
    private val pad = context.dp(2f)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.segmentTrack }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.segmentThumb
        setShadowLayer(context.dp(3f), 0f, context.dp(1.5f), Color.BLACK.withAlpha(0.12f))
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.label
        textSize = context.sp(13f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
    private val rect = RectF()
    private var pos = 0f
    private val spring = Spring(Spring.STIFF_UI, Spring.DAMP_UI) { pos = it; invalidate() }

    init {
        isClickable = true
    }

    fun select(i: Int, animate: Boolean = false) {
        selected = i.coerceIn(0, options.lastIndex)
        if (animate) spring.animateTo(selected.toFloat()) else spring.snapTo(selected.toFloat())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minW = options.maxOf { text.measureText(it) } * options.size + context.dp(28f) * options.size
        setMeasuredDimension(resolveSize(Math.round(minW), widthMeasureSpec), Math.round(h))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val i = (event.x / (width / options.size.toFloat())).toInt().coerceIn(0, options.lastIndex)
            if (i != selected) {
                select(i, animate = true)
                tick()
                onSelect?.invoke(i)
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, h / 2, h / 2, track)
        val seg = width / options.size.toFloat()
        rect.set(pos * seg + pad, pad, (pos + 1) * seg - pad, h - pad)
        canvas.drawRoundRect(rect, (h - pad * 2) / 2, (h - pad * 2) / 2, thumb)
        val fm = text.fontMetrics
        options.forEachIndexed { i, s ->
            canvas.drawText(s, seg * (i + 0.5f), h / 2 - (fm.ascent + fm.descent) / 2, text)
        }
    }
}
