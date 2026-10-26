package com.pixelgd.glassgate.widgets

import android.annotation.SuppressLint
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.UserManager
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.pixelgd.glassgate.LauncherActivity
import com.pixelgd.glassgate.glass.GlassDrawable
import com.pixelgd.glassgate.glass.GlassStyle
import com.pixelgd.glassgate.home.PageGridView
import com.pixelgd.glassgate.util.Main
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.dpi
import com.pixelgd.glassgate.util.tick
import com.pixelgd.glassgate.util.withAlpha
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The widget gallery, iOS style: a glass sheet with a size switch, Glassgate's own widgets
 * as live previews, then every app's widgets. Tapping one adds it to the current page.
 *
 *     WidgetPicker.show(activity)   // edit mode's "+" button, or a menu
 */
@SuppressLint("ViewConstructor")
class WidgetPicker private constructor(private val activity: LauncherActivity) : FrameLayout(activity) {

    private val widgets = Widgets.of(activity)
    private val scrim = View(activity).apply { setBackgroundColor(Color.BLACK) }
    private val sheet = FrameLayout(activity)
    private val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val sizeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    private val builtInRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    private val appList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var size = WidgetSize.SMALL
    private val sheetRadius = activity.dp(38f)
    private val rows = ArrayList<Row>()
    private var opening = false

    private val slide = Spring(Spring.STIFF_UI, Spring.DAMP_SNAP) { v ->
        sheet.translationY = v * sheet.height.coerceAtLeast(height)
        scrim.alpha = 0.35f * (1f - v)
        sheet.invalidate()
    }

    init {
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scrim.setOnClickListener { dismiss() }
        sheet.background = GlassDrawable(sheet, GlassStyle.FOLDER_OPEN.copy(cornerRadius = 38f))
        sheet.isClickable = true
        addView(sheet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            // Push the bottom corners off screen so only the top ones show.
            bottomMargin = -sheetRadius.roundToInt()
        })
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        // Glass rows sample the backdrop where they are, so they redraw as the list scrolls.
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            sizeRow.invalidate()
            for (r in rows) r.view.invalidate()
        }
        scroll.addView(content)
        sheet.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val side = activity.dpi(20f)
        content.setPadding(side, activity.dpi(22f), side, sheetRadius.roundToInt() + activity.dpi(28f))
        content.addView(header())
        content.addView(sizeRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = activity.dpi(14f)
        })
        content.addView(sectionLabel("Glassgate"))
        content.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(builtInRow)
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(sectionLabel("Apps"))
        content.addView(appList)

        setOnApplyWindowInsetsListener { _, insets ->
            val top = insets.getInsets(WindowInsets.Type.systemBars()).top
            (sheet.layoutParams as LayoutParams).topMargin = top + activity.dpi(12f)
            sheet.requestLayout()
            insets
        }

        buildSizes()
        buildBuiltIns()
        loadApps()
    }

    private fun header() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(activity).apply {
            text = "Widgets"
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, 800, false)
            letterSpacing = -0.02f
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(activity).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, 700, false)
            background = GlassDrawable(this, GlassStyle.BUTTON)
            contentDescription = "Close"
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(activity.dpi(34f), activity.dpi(34f)))
    }

    private fun sectionLabel(text: String) = TextView(activity).apply {
        this.text = text
        setTextColor(Color.WHITE.withAlpha(0.7f))
        textSize = 13f
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        isAllCaps = true
        letterSpacing = 0.04f
        setPadding(activity.dpi(4f), activity.dpi(24f), 0, activity.dpi(10f))
    }

    /** Small / Medium / Large segmented switch. */
    private fun buildSizes() {
        sizeRow.removeAllViews()
        sizeRow.background = GlassDrawable(sizeRow, GlassStyle.BUTTON)
        val pad = activity.dpi(3f)
        sizeRow.setPadding(pad, pad, pad, pad)
        for (s in WidgetSize.entries) {
            val selected = s == size
            sizeRow.addView(TextView(activity).apply {
                text = s.label
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, 600, false)
                setTextColor(if (selected) Color.BLACK else Color.WHITE)
                background = if (selected) GradientDrawable().apply {
                    cornerRadius = activity.dp(999f)
                    setColor(Color.WHITE.withAlpha(0.92f))
                } else null
                setOnClickListener {
                    if (size != s) {
                        it.tick()
                        size = s
                        buildSizes()
                        buildBuiltIns()
                        refreshFit()
                    }
                }
            }, LinearLayout.LayoutParams(0, activity.dpi(32f), 1f))
        }
    }

    /** Live previews of the built-in widgets at the chosen size. */
    private fun buildBuiltIns() {
        builtInRow.removeAllViews()
        val (w, h) = previewSize()
        val entries: List<Triple<WidgetSpec, String, String>> = listOf(
            Triple(WidgetSpec.smartStack(), "Smart Stack", "Clock, Weather and Batteries. Swipe to flip."),
        ) + BuiltIn.entries.map { Triple(WidgetSpec.Glass(it), it.title, it.blurb) }
        for ((spec, title, blurb) in entries) {
            val column = TapCatcher(activity).apply {
                val inner = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                inner.addView(WidgetCard(activity).apply { setContent(WidgetViews.content(activity, spec, size)) },
                    LinearLayout.LayoutParams(w, h))
                inner.addView(TextView(activity).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    typeface = Typeface.create(Typeface.DEFAULT, 600, false)
                    setPadding(activity.dpi(4f), activity.dpi(10f), 0, 0)
                })
                inner.addView(TextView(activity).apply {
                    text = blurb
                    setTextColor(Color.WHITE.withAlpha(0.65f))
                    textSize = 12f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(activity.dpi(4f), activity.dpi(2f), 0, 0)
                }, LinearLayout.LayoutParams(w, LayoutParams.WRAP_CONTENT))
                addView(inner)
                contentDescription = "Add $title widget"
                setOnClickListener { addBuiltIn(spec) }
            }
            builtInRow.addView(column, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                rightMargin = activity.dpi(14f)
            })
        }
    }

    /** Preview card size: the real card's shape, a small one 140dp across. */
    private fun previewSize(): Pair<Int, Int> {
        val (cellW, icon) = cellMetrics()
        val small = cellW + icon
        val wide = cellW * 3 + icon
        val unit = activity.dp(140f) / small
        val w = (if (size == WidgetSize.SMALL) small else wide) * unit
        val h = (if (size == WidgetSize.LARGE) wide else small) * unit
        return w.roundToInt() to h.roundToInt()
    }

    /** Cell width and icon size on the current page, with sane values before first layout. */
    private fun cellMetrics(): Pair<Float, Float> {
        val icon = activity.dp(com.pixelgd.glassgate.Launcher.of(activity).prefs.iconSizeDp.toFloat())
        val grid = activity.home.pages.pageAt(activity.home.pages.currentPage) as? PageGridView
        val cellW = grid?.cellWidth?.takeIf { it > 0 }?.toFloat()
            ?: ((resources.displayMetrics.widthPixels - activity.dp(24f)) / 4f)
        return cellW to icon
    }

    /** The card a hosted widget would get at [s], in px. */
    private fun cardPx(s: WidgetSize): Pair<Float, Float> {
        val (cellW, icon) = cellMetrics()
        val small = cellW + icon
        val wide = cellW * 3 + icon
        return (if (s == WidgetSize.SMALL) small else wide) to (if (s == WidgetSize.LARGE) wide else small)
    }

    private fun addBuiltIn(spec: WidgetSpec) {
        val page = widgets.place(spec.toItem(size), activity.home.pages.currentPage)
        dismiss()
        activity.home.pages.snapToPage(page)
    }

    // ---- App widgets ----

    private fun loadApps() {
        val pm = activity.packageManager
        val users = activity.getSystemService(UserManager::class.java)?.userProfiles.orEmpty()
        val providers = users.flatMap { widgets.manager.getInstalledProvidersForProfile(it) }
            .filter { (it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0 }
        val appName = HashMap<String, String>()
        fun app(info: AppWidgetProviderInfo) = appName.getOrPut(info.provider.packageName) {
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString() }
                .getOrDefault(info.provider.packageName)
        }
        val sorted = providers.sortedWith(compareBy({ app(it).lowercase() }, { it.loadLabel(pm).lowercase() }))
        if (sorted.isEmpty()) {
            appList.addView(TextView(activity).apply {
                text = "No apps with widgets are installed."
                setTextColor(Color.WHITE.withAlpha(0.7f))
                textSize = 14f
            })
            return
        }
        var lastApp: String? = null
        for (info in sorted) {
            val name = app(info)
            if (name != lastApp) {
                lastApp = name
                appList.addView(TextView(activity).apply {
                    text = name
                    setTextColor(Color.WHITE)
                    textSize = 17f
                    typeface = Typeface.create(Typeface.DEFAULT, 700, false)
                    setPadding(activity.dpi(4f), activity.dpi(14f), 0, activity.dpi(6f))
                })
            }
            val row = providerRow(info)
            rows += Row(info, row)
            appList.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = activity.dpi(8f)
            })
        }
        refreshFit()
    }

    private fun providerRow(info: AppWidgetProviderInfo): View {
        val pm = activity.packageManager
        val preview = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = activity.dpi(12f)
            setPadding(p, p, p, p)
            background = GlassDrawable(this, GlassStyle.MENU.copy(cornerRadius = 20f, lightTint = 0.12f, darkTint = 0.18f))
            addView(preview, LinearLayout.LayoutParams(activity.dpi(84f), activity.dpi(64f)))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dpi(14f), 0, 0, 0)
                addView(TextView(activity).apply {
                    text = info.loadLabel(pm)
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    typeface = Typeface.create(Typeface.DEFAULT, 600, false)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                info.loadDescription(activity)?.let { d ->
                    addView(TextView(activity).apply {
                        text = d
                        setTextColor(Color.WHITE.withAlpha(0.65f))
                        textSize = 12f
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    })
                }
                addView(TextView(activity).apply {
                    tag = FIT_TAG
                    setTextColor(Color.WHITE.withAlpha(0.55f))
                    textSize = 12f
                })
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { addHosted(info) }
        }
        val density = resources.displayMetrics.densityDpi
        IO.execute {
            val d: Drawable? = runCatching { info.loadPreviewImage(activity, density) ?: info.loadIcon(activity, density) }.getOrNull()
            Main.post { preview.setImageDrawable(d) }
        }
        return row
    }

    /** Smallest size this provider will draw at, in px. */
    private fun minPx(info: AppWidgetProviderInfo): Pair<Int, Int> {
        val horiz = (info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0
        val vert = (info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL) != 0
        val w = if (horiz && info.minResizeWidth > 0) info.minResizeWidth else info.minWidth
        val h = if (vert && info.minResizeHeight > 0) info.minResizeHeight else info.minHeight
        return w to h
    }

    private fun fits(info: AppWidgetProviderInfo, s: WidgetSize): Boolean {
        val (cw, ch) = cardPx(s)
        val (mw, mh) = minPx(info)
        // Providers round their minimums up to whole cells of a stock launcher; allow some slack.
        return mw <= cw * 1.15f && mh <= ch * 1.15f
    }

    private fun refreshFit() {
        for (r in rows) {
            val ok = fits(r.info, size)
            r.view.alpha = if (ok) 1f else 0.45f
            r.view.findViewWithTag<TextView>(FIT_TAG)?.text =
                if (ok) "Add as ${size.label}" else WidgetSize.entries.firstOrNull { fits(r.info, it) }
                    ?.let { "Needs ${it.label}" } ?: "May not fit"
        }
    }

    private fun addHosted(info: AppWidgetProviderInfo) {
        if (!fits(info, size)) {
            val better = WidgetSize.entries.firstOrNull { fits(info, it) }
            if (better != null) {
                Toast.makeText(activity, "This widget needs the ${better.label} size.", Toast.LENGTH_SHORT).show()
                size = better
                buildSizes(); buildBuiltIns(); refreshFit()
                return
            }
        }
        dismiss()
        WidgetBinder.add(activity, info, size)
    }

    // ---- Show and dismiss ----

    private fun open() {
        opening = true
        slide.snapTo(1f)
    }

    /** Start sliding up once we know how tall the sheet is. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (opening) {
            opening = false
            slide.snapTo(1f)
            slide.animateTo(0f)
        }
    }

    fun dismiss() {
        slide.animateTo(1f) { (parent as? ViewGroup)?.removeView(this) }
    }

    private class Row(val info: AppWidgetProviderInfo, val view: View)

    /** Swallows touches meant for a preview so the whole column acts as one button. */
    private class TapCatcher(context: Context) : FrameLayout(context) {
        override fun onInterceptTouchEvent(ev: MotionEvent) = true
    }

    companion object {
        private const val FIT_TAG = "fit"
        private val IO = Executors.newSingleThreadExecutor { r -> Thread(r, "widget-previews") }

        fun show(activity: LauncherActivity) {
            activity.dismissOverlays()
            val picker = WidgetPicker(activity)
            activity.overlay.addView(picker, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            picker.requestApplyInsets()
            picker.open()
        }
    }
}
