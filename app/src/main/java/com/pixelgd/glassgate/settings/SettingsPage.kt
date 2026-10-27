package com.pixelgd.glassgate.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.menu.Glyph
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.dpi
import com.pixelgd.glassgate.util.withAlpha

/**
 * One screen of the Settings app, iOS style: a large title that hands off to a small one in
 * the bar as you scroll, a round back button, and inset grouped cards. [SettingsActivity]
 * stacks these and slides them in and out like a UINavigationController.
 *
 * Subclasses add their sections in [build], which runs once when the page is first pushed.
 */
@SuppressLint("ViewConstructor")
abstract class SettingsPage(val host: SettingsActivity, val title: String) : FrameLayout(host) {

    protected val launcher = Launcher.of(host)
    protected val prefs = launcher.prefs
    protected val colors: IosColors = host.colors
    protected val list: IosList = host.list

    val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(host).apply {
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        addView(content)
    }
    private val topBar = FrameLayout(host)
    private val smallTitle = TextView(host)
    private lateinit var largeTitle: TextView
    private var built = false

    /** Root pages have no back button. */
    open val showsBack = true

    init {
        setBackgroundColor(colors.background)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // iOS 26 fades content out under the bar instead of drawing a hard edge.
        topBar.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(colors.background, colors.background, colors.background.withAlpha(0f)))
        topBar.background.alpha = 0
        smallTitle.apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(colors.label)
            typeface = IosList.BOLD
            alpha = 0f
        }
        topBar.addView(smallTitle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT, dpi(52f), Gravity.TOP))
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> onScrolled(y) }
    }

    fun ensureBuilt() {
        if (built) return
        built = true
        if (showsBack) {
            val back = BackButton(host, colors).apply {
                setOnClickListener { host.pop() }
                contentDescription = "Back"
            }
            topBar.addView(back, LayoutParams(dpi(44f), dpi(44f), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                marginStart = dpi(16f)
            })
        }
        largeTitle = TextView(host).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            setTextColor(colors.label)
            typeface = IosList.BOLD
            letterSpacing = -0.01f
            setPadding(dpi(4f), dpi(4f), 0, 0)
        }
        content.addView(largeTitle)
        build()
    }

    protected abstract fun build()

    /** Called each time the page comes to the front (pushed, popped back to, or app resumed). */
    open fun onShown() {}

    fun applyInsets(bars: Insets) {
        val side = dpi(16f)
        scroll.setPadding(side + bars.left, bars.top + dpi(52f), side + bars.right, bars.bottom + dpi(36f))
        topBar.setPadding(bars.left, bars.top, bars.right, 0)
        topBar.layoutParams.height = bars.top + dpi(52f)
        topBar.requestLayout()
    }

    private fun onScrolled(y: Int) {
        // The large title slides under the bar; the small one takes over as it disappears.
        val t = ((y - dp(18f)) / dp(22f)).coerceIn(0f, 1f)
        smallTitle.alpha = t
        topBar.background.alpha = Math.round(255 * (y / dp(24f)).coerceIn(0f, 1f))
        if (::largeTitle.isInitialized) largeTitle.alpha = 1f - t
    }

    // ---- builders shared by every page ----

    protected fun dp(v: Float) = host.dp(v)
    protected fun dpi(v: Float) = host.dpi(v)

    protected fun section(header: String?, card: View, footer: String? = null) {
        header?.let { content.addView(list.header(it)) } ?: content.addView(View(host), LinearLayout.LayoutParams(1, dpi(26f)))
        content.addView(card)
        footer?.let { content.addView(list.footer(it)) }
    }

    protected fun switchRow(
        title: String,
        get: () -> Boolean,
        subtitle: String? = null,
        icon: View? = null,
        set: (Boolean) -> Unit,
    ): IosList.Row {
        val sw = IosSwitch(host, colors).apply {
            setOn(get())
            onChange = set
        }
        return list.row(title, subtitle, leading = icon, trailing = sw, onClick = { sw.toggle() })
    }

    /** A row that pushes [page] (built on tap). */
    protected fun navRow(title: String, icon: View? = null, value: String? = null, page: () -> SettingsPage): IosList.Row =
        list.row(title, leading = icon, value = value, chevron = true, onClick = { host.push(page()) })

    /** A card of mutually exclusive rows with a checkmark on the chosen one. */
    protected fun <T> checkCard(options: List<Pair<String, T>>, get: () -> T, set: (T) -> Unit): LinearLayout {
        val card = list.card()
        val checks = ArrayList<Pair<T, View>>()
        fun refresh() = checks.forEach { (v, c) -> c.visibility = if (v == get()) View.VISIBLE else View.INVISIBLE }
        for ((label, value) in options) {
            val check = list.check()
            checks += value to check
            card.addView(list.row(label, trailing = check, onClick = {
                if (get() != value) {
                    set(value)
                    refresh()
                }
            }))
        }
        refresh()
        return card
    }

    protected fun icon(color: Int, glyph: Glyph) = SettingsIcon(host, color, glyph)

    protected fun caption(text: String) = TextView(host).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(colors.secondary)
        setPadding(dpi(4f), 0, dpi(4f), 0)
    }

    /** A slider in its own card cell, with small captions at both ends. */
    protected fun sliderCell(value: Float, left: String, right: String, onEnd: (Float) -> Unit, onMove: ((Float) -> Unit)? = null): LinearLayout {
        val slider = IosSlider(host, colors).apply {
            this.value = value
            onChange = onMove
            onChangeEnd = onEnd
        }
        val ends = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(caption(left), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(caption(right).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16f), dpi(12f), dpi(16f), dpi(10f))
            addView(slider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(ends)
        }
    }

    /** The iOS 26 round back button: a raised circle with a chevron. */
    private class BackButton(context: Context, private val colors: IosColors) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(2.6f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = colors.label
        }
        private val path = Path()

        init {
            isClickable = true
            elevation = context.dp(3f)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(this@BackButton.colors.card)
            }
        }

        override fun onDraw(canvas: Canvas) {
            val s = width.toFloat()
            // A faint top highlight so it reads as glass rather than a flat disc.
            fill.shader = LinearGradient(0f, 0f, 0f, s, Color.WHITE.withAlpha(if (colors.dark) 0.10f else 0.5f),
                Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(s / 2, s / 2, s / 2, fill)
            path.reset()
            path.moveTo(s * 0.56f, s * 0.30f)
            path.lineTo(s * 0.38f, s * 0.5f)
            path.lineTo(s * 0.56f, s * 0.70f)
            canvas.drawPath(path, stroke)
        }

        private val press = Spring(Spring.STIFF_BOUNCY, Spring.DAMP_BOUNCY) { scaleX = it; scaleY = it }
            .also { it.snapTo(1f) }

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            press.animateTo(if (pressed) 0.88f else 1f)
        }
    }
}

/** The small coloured squircle with a white glyph that leads each row on the Settings root. */
@SuppressLint("ViewConstructor")
class SettingsIcon(context: Context, private val color: Int, private val glyph: Glyph) : View(context) {
    private val size = context.dpi(30f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = android.graphics.RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(size, size)

    override fun onDraw(canvas: Canvas) {
        val s = size.toFloat()
        rect.set(0f, 0f, s, s)
        com.pixelgd.glassgate.glass.Squircle.roundRect(rect, s * 0.26f, path)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 0f, s, lighten(color), color, Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        paint.shader = null
        glyph.draw(canvas, s / 2, s / 2, s * 0.56f, Color.WHITE)
    }

    private fun lighten(c: Int) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * 0.18f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * 0.18f).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * 0.18f).toInt(),
    )
}
