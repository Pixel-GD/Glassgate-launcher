package com.pixelgd.glassgate.library

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.R
import com.pixelgd.glassgate.apps.AppEntry
import com.pixelgd.glassgate.glass.GlassDrawable
import com.pixelgd.glassgate.glass.GlassStyle
import com.pixelgd.glassgate.home.AppIconView
import com.pixelgd.glassgate.home.HomeActions
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.dpi
import com.pixelgd.glassgate.util.isDarkMode
import com.pixelgd.glassgate.util.sp
import com.pixelgd.glassgate.util.tick
import com.pixelgd.glassgate.util.withAlpha
import java.text.Normalizer

/**
 * The App Library's search: a frosted full-screen sheet with a focused search field and every
 * app in an A to Z list with letter headers and a letter index on the right edge. Typing
 * filters the list; Enter opens the top match. Lives in LauncherActivity.overlay.
 */
@SuppressLint("ViewConstructor")
class LibrarySearchView(context: Context, private val actions: HomeActions) : FrameLayout(context) {

    private val launcher = Launcher.of(context)
    private val dark = context.isDarkMode
    private val fg = if (dark) Color.WHITE else Color.rgb(20, 20, 24)
    private val fgDim = fg.withAlpha(0.55f)
    private val sheet = GlassDrawable(this, GlassStyle.SHEET).apply { progress = 0f }

    private val field = EditText(context)
    private val cancel = TextView(context)
    private val topRow = LinearLayout(context)
    private val list = ListView(context)
    private val index = LetterIndexView(context, fgDim)
    private val adapter = Adapter()

    private val all: List<AppEntry> = launcher.apps.all
    private var rows: List<Row> = emptyList()
    private var closing = false

    private val appear = Spring(Spring.STIFF_UI, 1f) { v ->
        sheet.progress = v
        topRow.alpha = v
        list.alpha = v
        index.alpha = v
        list.translationY = (1f - v) * context.dp(40f)
    }.also { it.snapTo(0f) }

    private val backCallback: Any? = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { close() } else null

    init {
        background = sheet
        isClickable = true // keep touches from reaching the home screen underneath

        val fieldBox = FrameLayout(context).apply {
            background = GlassDrawable(this, GlassStyle.PILL)
        }
        fieldBox.addView(MagnifierView(context, fgDim),
            LayoutParams(context.dpi(36f), LayoutParams.MATCH_PARENT, Gravity.START))
        field.apply {
            background = null
            setPadding(context.dpi(36f), 0, context.dpi(14f), 0)
            setTextColor(fg)
            setHintTextColor(fgDim)
            hint = context.getString(R.string.library_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            highlightColor = fg.withAlpha(0.25f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
            })
            setOnEditorActionListener { v, _, _ ->
                (rows.firstOrNull { it is Row.App } as? Row.App)?.let { launch(it.app, v) }
                true
            }
        }
        fieldBox.addView(field, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        cancel.apply {
            text = context.getString(android.R.string.cancel)
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(context.dpi(14f), 0, context.dpi(4f), 0)
            setOnClickListener { close() }
        }
        topRow.orientation = LinearLayout.HORIZONTAL
        topRow.addView(fieldBox, LinearLayout.LayoutParams(0, context.dpi(42f), 1f))
        topRow.addView(cancel, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, context.dpi(42f)))
        addView(topRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))

        list.apply {
            divider = null
            selector = ColorDrawable(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = OVER_SCROLL_NEVER
            adapter = this@LibrarySearchView.adapter
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, state: Int) {
                    if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
                }
                override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) = Unit
            })
        }
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(index, LayoutParams(context.dpi(22f), LayoutParams.MATCH_PARENT, Gravity.END))
        index.onPick = { letter ->
            val pos = rows.indexOfFirst { it is Row.Header && it.letter == letter }
            if (pos >= 0) list.setSelectionFromTop(pos, 0)
            hideKeyboard()
        }

        filter("")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rootWindowInsets?.let { applyInsets(it) }
        appear.animateTo(1f)
        field.requestFocus()
        post { field.windowInsetsController?.show(WindowInsets.Type.ime()) }
        if (Build.VERSION.SDK_INT >= 33) {
            findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY, backCallback as OnBackInvokedCallback)
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 33) {
            findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(backCallback as OnBackInvokedCallback)
        }
        appear.cancel()
        // Home or Back can remove this without close(); don't leave the keyboard up.
        field.windowInsetsController?.hide(WindowInsets.Type.ime())
        super.onDetachedFromWindow()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        applyInsets(insets)
        return insets
    }

    private fun applyInsets(insets: WindowInsets) {
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val ime = insets.getInsets(WindowInsets.Type.ime())
        val side = context.dpi(16f)
        val top = bars.top + context.dpi(10f)
        (topRow.layoutParams as LayoutParams).setMargins(side, top, side, 0)
        topRow.requestLayout()
        val listTop = top + context.dpi(42f + 12f)
        val bottom = maxOf(bars.bottom, ime.bottom) + context.dpi(12f)
        (list.layoutParams as LayoutParams).topMargin = listTop
        list.setPadding(side, 0, side + context.dpi(10f), bottom)
        (index.layoutParams as LayoutParams).setMargins(0, listTop, context.dpi(2f), bottom)
        list.requestLayout()
        index.requestLayout()
    }

    // ---- data ----

    private fun filter(query: String) {
        val q = fold(query.trim())
        rows = if (q.isEmpty()) sectioned(all) else {
            all.mapNotNull { a ->
                val label = fold(a.label)
                val rank = when {
                    label.startsWith(q) -> 0
                    label.split(' ', '-', '.').any { it.startsWith(q) } -> 1
                    label.contains(q) -> 2
                    else -> return@mapNotNull null
                }
                rank to a
            }.sortedBy { it.first }.map { Row.App(it.second) }
        }
        index.letters = if (q.isEmpty()) rows.filterIsInstance<Row.Header>().map { it.letter } else emptyList()
        adapter.notifyDataSetChanged()
        list.setSelection(0)
    }

    /** Letter sections in the repository's A to Z order, with "#" (digits, symbols) last. */
    private fun sectioned(apps: List<AppEntry>): List<Row> {
        val sections = LinkedHashMap<String, MutableList<AppEntry>>()
        val other = ArrayList<AppEntry>()
        for (a in apps) {
            val c = fold(a.label).firstOrNull()?.uppercaseChar()
            if (c != null && c in 'A'..'Z') sections.getOrPut(c.toString()) { ArrayList() } += a else other += a
        }
        if (other.isNotEmpty()) sections["#"] = other
        val out = ArrayList<Row>(apps.size + sections.size)
        for ((letter, list) in sections) {
            out += Row.Header(letter)
            list.mapTo(out) { Row.App(it) }
        }
        return out
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase()

    // ---- actions ----

    private fun launch(app: AppEntry, source: View) {
        hideKeyboard()
        actions.launch(app, source)
        (parent as? ViewGroup)?.removeView(this)
    }

    private fun hideKeyboard() {
        field.clearFocus()
        field.windowInsetsController?.hide(WindowInsets.Type.ime())
    }

    fun close() {
        if (closing) return
        closing = true
        hideKeyboard()
        appear.animateTo(0f) { (parent as? ViewGroup)?.removeView(this) }
    }

    // ---- list ----

    private sealed class Row {
        class Header(val letter: String) : Row()
        class App(val app: AppEntry) : Row()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is Row.App

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val row = rows[position]) {
                is Row.Header -> ((convertView as? TextView) ?: headerView()).apply { text = row.letter }
                is Row.App -> ((convertView as? AppRowView) ?: AppRowView(context, fg).apply {
                    setOnClickListener { v -> (v as AppRowView).app?.let { launch(it, v.icon) } }
                    setOnLongClickListener { v ->
                        val r = v as AppRowView
                        r.app?.let { actions.showContextMenu(null, it, r.icon) }
                        true
                    }
                }).apply {
                    app = row.app
                    showDivider = rows.getOrNull(position + 1) is Row.App
                }
            }

        private fun headerView() = TextView(context).apply {
            setTextColor(fgDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(context.dpi(4f), context.dpi(14f), 0, context.dpi(4f))
        }
    }

    companion object {
        private val MARKS = Regex("\\p{Mn}+")
    }
}

/** One app in the A to Z list: icon, name and a hairline divider that starts after the icon. */
@SuppressLint("ViewConstructor")
private class AppRowView(context: Context, private val fg: Int) : ViewGroup(context) {
    private val iconPx = context.dpi(40f)
    private val rowH = context.dpi(56f)
    val icon = AppIconView(context, iconPx, showLabel = false).apply {
        isClickable = false
        isLongClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fg
        textSize = context.sp(17f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val linePaint = Paint().apply { color = fg.withAlpha(0.14f) }
    private val textLeft = context.dp(4f) + iconPx + context.dp(14f)

    var app: AppEntry? = null
        set(value) { field = value; icon.app = value; contentDescription = value?.label; invalidate() }
    var showDivider = true
        set(value) { field = value; invalidate() }

    init {
        setWillNotDraw(false)
        isClickable = true
        isLongClickable = true
        background = RippleDrawable(ColorStateList.valueOf(fg.withAlpha(0.12f)), null, ColorDrawable(Color.WHITE))
        addView(icon)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = MeasureSpec.makeMeasureSpec(iconPx, MeasureSpec.EXACTLY)
        icon.measure(spec, spec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), rowH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = context.dpi(4f)
        val top = (rowH - iconPx) / 2
        icon.layout(left, top, left + iconPx, top + iconPx)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    override fun onDraw(canvas: Canvas) {
        val label = app?.label ?: return
        val text = TextUtils.ellipsize(label, textPaint, width - textLeft - context.dp(8f), TextUtils.TruncateAt.END)
        val baseline = height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(text, 0, text.length, textLeft, baseline, textPaint)
        if (showDivider) canvas.drawRect(textLeft, height - context.dp(0.5f), width.toFloat(), height.toFloat(), linePaint)
    }
}

/** The search glyph inside the field. */
private class MagnifierView(context: Context, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f)
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val r = context.dp(6f)
        drawMagnifier(canvas, width / 2f + context.dp(1f), height / 2f - context.dp(1.5f), r, paint)
    }
}

/**
 * The letter strip on the right edge. Touch or drag along it to jump to a section, with a
 * haptic tick per letter, like the iOS index.
 */
private class LetterIndexView(context: Context, color: Int) : View(context) {
    var letters: List<String> = emptyList()
        set(value) { field = value; visibility = if (value.isEmpty()) INVISIBLE else VISIBLE; invalidate() }
    var onPick: ((String) -> Unit)? = null
    private var last: String? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = context.sp(11f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val step get() = minOf(context.dp(16f), height / letters.size.coerceAtLeast(1).toFloat())
    private val firstY get() = (height - step * letters.size) / 2f

    override fun onDraw(canvas: Canvas) {
        val s = step
        var y = firstY + s / 2f - (paint.ascent() + paint.descent()) / 2f
        for (l in letters) {
            canvas.drawText(l, width / 2f, y, paint)
            y += s
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val i = ((event.y - firstY) / step).toInt().coerceIn(0, letters.size - 1)
                val l = letters[i]
                if (l != last) {
                    last = l
                    tick()
                    onPick?.invoke(l)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> last = null
        }
        return true
    }
}
