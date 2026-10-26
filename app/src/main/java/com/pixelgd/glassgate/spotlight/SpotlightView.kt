package com.pixelgd.glassgate.spotlight

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.pixelgd.glassgate.DismissibleOverlay
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.LauncherActivity
import com.pixelgd.glassgate.apps.AppEntry
import com.pixelgd.glassgate.apps.AppRepository
import com.pixelgd.glassgate.glass.GlassDrawable
import com.pixelgd.glassgate.glass.GlassStyle
import com.pixelgd.glassgate.home.AppIconView
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.dpi
import com.pixelgd.glassgate.util.tick
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * iOS Spotlight. Opens over the home screen as a full-screen frosted glass sheet: the home
 * blurs away behind it, "Siri Suggestions" fill the top, and a Liquid Glass search field
 * rides on top of the keyboard, as in iOS 26. Typing shows live results for apps, contacts
 * (when allowed), system settings, a calculator and a web search.
 *
 * Open it with [SpotlightView.open]; it removes itself on close, Back, Home or a launch.
 */
@SuppressLint("ViewConstructor")
class SpotlightView private constructor(private val activity: LauncherActivity) : FrameLayout(activity),
    AppRepository.Listener, DismissibleOverlay {

    private val launcher = Launcher.of(activity)
    private val history = SpotlightHistory.of(activity)
    private val palette = SpotlightPalette(activity)
    private val prefs = activity.getSharedPreferences("spotlight", 0)

    private val sheetGlass: GlassDrawable
    private val sheet = View(activity)
    private val scroll = ScrollView(activity)
    private val content = LinearLayout(activity)
    private val bar = LinearLayout(activity)
    private val field = EditText(activity)
    private val clear: CrossView
    private val fieldPill = LinearLayout(activity)

    private val iconPx = activity.dpi(60f)
    private val sideMargin = activity.dpi(16f)
    private val barHeight = activity.dpi(50f)
    private var insetTop = 0
    private var insetBottom = 0
    private var imeAnimating = false
    private var lastInsets: WindowInsets? = null

    private var progress = 0f
    private val spring = Spring(Spring.STIFF_SNAP, Spring.DAMP_SNAP) { applyProgress(it) }
    private var closing = false
    private var savedSoftInputMode = 0
    private val backCallback = OnBackInvokedCallback { close() }

    private var results: SpotlightResults? = null
    private var contacts: List<ContactHit> = emptyList()
    private var contactsGen = 0

    init {
        clipChildren = false
        sheetGlass = GlassDrawable(sheet, GlassStyle.SHEET).also { it.progress = 0f }
        sheet.background = sheetGlass
        sheet.foreground = ColorDrawable(palette.scrim)
        addView(sheet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        content.orientation = LinearLayout.VERTICAL
        content.setPadding(sideMargin, 0, sideMargin, 0)
        content.isClickable = true
        content.setOnClickListener { onEmptyTap() }
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.clipToPadding = false
        scroll.isFillViewport = true
        scroll.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        hideKeyboardOnDrag()
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        clear = CrossView(activity, Color.WHITE, palette.secondary)
        buildSearchBar()
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, barHeight, Gravity.BOTTOM).apply {
            leftMargin = sideMargin
            rightMargin = sideMargin
        })

        setOnApplyWindowInsetsListener { _, insets ->
            lastInsets = insets
            if (!imeAnimating) applyInsets(insets)
            insets
        }
        setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(animation: WindowInsetsAnimation) {
                if (animation.typeMask and WindowInsets.Type.ime() != 0) imeAnimating = true
            }

            override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>): WindowInsets {
                if (imeAnimating) applyInsets(insets)
                return insets
            }

            override fun onEnd(animation: WindowInsetsAnimation) {
                if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                    imeAnimating = false
                    lastInsets?.let { applyInsets(it) }
                }
            }
        })

        applyProgress(0f)
        render()
    }

    private fun buildSearchBar() {
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.clipChildren = false

        fieldPill.orientation = LinearLayout.HORIZONTAL
        fieldPill.gravity = Gravity.CENTER_VERTICAL
        fieldPill.background = GlassDrawable(fieldPill, GlassStyle.PILL)
        fieldPill.setPadding(activity.dpi(16f), 0, activity.dpi(10f), 0)
        fieldPill.setOnClickListener { focusField() }
        fieldPill.addView(MagnifierView(activity, palette.secondary), LinearLayout.LayoutParams(activity.dpi(18f), activity.dpi(18f)).apply {
            marginEnd = activity.dpi(8f)
        })

        field.background = null
        field.hint = "Search"
        field.setHintTextColor(palette.secondary)
        field.setTextColor(palette.text)
        field.textSize = 17f
        field.isSingleLine = true
        field.setPadding(0, 0, 0, 0)
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        field.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { openBestResult(); true } else false
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onQueryChanged()
        })
        fieldPill.addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        clear.visibility = GONE
        clear.contentDescription = "Clear text"
        clear.setOnClickListener { field.text.clear(); focusField() }
        fieldPill.addView(clear, LinearLayout.LayoutParams(activity.dpi(22f), activity.dpi(22f)))

        bar.addView(fieldPill, LinearLayout.LayoutParams(0, barHeight, 1f))

        val close = FrameLayout(activity).apply {
            background = GlassDrawable(this, GlassStyle.BUTTON)
            contentDescription = "Close"
            setOnClickListener { close() }
            addView(CrossView(activity, palette.text), LayoutParams(activity.dpi(22f), activity.dpi(22f), Gravity.CENTER))
        }
        bar.addView(close, LinearLayout.LayoutParams(barHeight, barHeight).apply { marginStart = activity.dpi(10f) })
    }

    // ---- Lifecycle ----

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        launcher.apps.addListener(this)
        savedSoftInputMode = activity.window.attributes.softInputMode
        // Edge to edge: the keyboard arrives as insets, which we animate the bar with.
        @Suppress("DEPRECATION")
        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
        requestApplyInsets()
        spring.animateTo(1f)
        post { focusField() }
    }

    override fun onDetachedFromWindow() {
        launcher.apps.removeListener(this)
        hideKeyboard()
        activity.window.setSoftInputMode(savedSoftInputMode)
        activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        spring.cancel()
        activity.home.setRenderEffect(null)
        contactsGen++
        super.onDetachedFromWindow()
    }

    /** Coming back from the contacts permission dialog: pick up the new answer. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && field.text.isNotBlank()) loadContacts(field.text.toString())
    }

    override fun onAppsChanged(apps: List<AppEntry>) = onQueryChanged()

    /** Back, Home, or another module closing overlays. */
    override fun dismiss() = close()

    /** Animates out, then removes itself. */
    fun close() {
        if (closing) return
        closing = true
        hideKeyboard()
        spring.animateTo(0f) { removeSelf() }
    }

    private fun removeSelf() {
        (parent as? android.view.ViewGroup)?.removeView(this)
    }

    private fun applyProgress(raw: Float) {
        val p = raw.coerceIn(0f, 1f)
        progress = p
        sheetGlass.progress = p
        sheet.foreground?.alpha = (p * 255).toInt()
        val blur = activity.dp(26f) * p
        activity.home.setRenderEffect(
            if (blur > 0.5f) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) else null,
        )
        scroll.alpha = p
        scroll.translationY = -activity.dp(28f) * (1f - p)
        scroll.scaleX = 0.96f + 0.04f * p
        scroll.scaleY = scroll.scaleX
        bar.alpha = p
        updateBarPosition()
    }

    // ---- Insets and keyboard ----

    private fun applyInsets(insets: WindowInsets) {
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val ime = insets.getInsets(WindowInsets.Type.ime())
        insetTop = bars.top
        insetBottom = max(bars.bottom, ime.bottom)
        updateBarPosition()
        val pad = insetBottom + barHeight + activity.dpi(24f)
        if (scroll.paddingTop != insetTop + activity.dpi(8f) || scroll.paddingBottom != pad) {
            scroll.setPadding(0, insetTop + activity.dpi(8f), 0, pad)
        }
    }

    private fun updateBarPosition() {
        val gap = activity.dp(10f)
        bar.translationY = -(insetBottom + gap) + activity.dp(60f) * (1f - progress)
        // Glass samples the backdrop at its window position, so redraw as the bar moves.
        fieldPill.invalidate()
        bar.getChildAt(1)?.invalidate()
    }

    private fun focusField() {
        field.requestFocus()
        activity.window.insetsController?.show(WindowInsets.Type.ime())
    }

    private fun hideKeyboard() {
        field.clearFocus()
        activity.window.insetsController?.hide(WindowInsets.Type.ime())
    }

    private fun keyboardVisible() = rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true

    @SuppressLint("ClickableViewAccessibility")
    private fun hideKeyboardOnDrag() {
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        var downY = 0f
        scroll.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downY = e.y
                MotionEvent.ACTION_MOVE -> if (abs(e.y - downY) > slop && keyboardVisible()) hideKeyboard()
            }
            false
        }
    }

    private fun onEmptyTap() {
        when {
            keyboardVisible() -> hideKeyboard()
            field.text.isEmpty() -> close()
        }
    }

    // ---- Searching ----

    private fun onQueryChanged() {
        val q = field.text.toString()
        clear.visibility = if (q.isEmpty()) GONE else VISIBLE
        results = SpotlightSearch.search(q, launcher.apps.all, history.scores())
        contacts = emptyList()
        render()
        loadContacts(q)
    }

    private fun loadContacts(q: String) {
        val gen = ++contactsGen
        if (q.isBlank() || !SpotlightSearch.canReadContacts(activity)) return
        val appContext = activity.applicationContext
        WORKER.execute {
            val found = SpotlightSearch.contacts(appContext, q)
            post {
                if (gen == contactsGen && isAttachedToWindow) {
                    contacts = found
                    render()
                }
            }
        }
    }

    // ---- Rendering ----

    private fun render() {
        content.removeAllViews()
        val r = results
        if (r == null || r.query.isBlank()) renderSuggestions() else renderResults(r)
    }

    private fun renderSuggestions() {
        val apps = history.suggestions(launcher.apps.all, 8)
        if (apps.isEmpty()) return
        content.addView(sectionHeader(activity, "Siri Suggestions", palette))
        val card = card(activity, palette)
        card.addView(appGrid(activity, palette, apps, 4, iconPx) { cell, app -> bindApp(cell.icon, cell, app) })
        content.addView(card, matchWidth())
    }

    private fun renderResults(r: SpotlightResults) {
        r.calculation?.let { value ->
            content.addView(sectionHeader(activity, "Calculator", palette))
            val card = card(activity, palette)
            val row = ResultRow(activity, palette, GlyphIconView(activity, Color.rgb(255, 159, 10), Color.rgb(240, 120, 0), GlyphIconView.Glyph.EQUALS),
                44f, "= $value", r.query.trim(), big = true)
            row.setOnClickListener { copy(value) }
            card.addView(row)
            content.addView(card, matchWidth())
        }

        r.topHit?.let { app ->
            content.addView(sectionHeader(activity, "Top Hit", palette))
            val card = card(activity, palette, dividerInsetDp = 76f)
            val icon = AppIconView(activity, activity.dpi(50f), showLabel = false).also { it.app = app }
            val row = ResultRow(activity, palette, icon, 50f, app.label, "Application", big = true)
            bindApp(icon, row, app)
            card.addView(row)
            content.addView(card, matchWidth())
        }

        val more = r.apps.drop(1).take(8)
        if (more.isNotEmpty()) {
            content.addView(sectionHeader(activity, "Apps", palette))
            val card = card(activity, palette)
            card.addView(appGrid(activity, palette, more, 4, iconPx) { cell, app -> bindApp(cell.icon, cell, app) })
            content.addView(card, matchWidth())
        }

        val canReadContacts = SpotlightSearch.canReadContacts(activity)
        if (contacts.isNotEmpty() || (!canReadContacts && !prefs.getBoolean(KEY_ASKED_CONTACTS, false))) {
            content.addView(sectionHeader(activity, "Contacts", palette))
            val card = card(activity, palette)
            if (contacts.isEmpty()) {
                val row = ResultRow(activity, palette,
                    GlyphIconView(activity, Color.rgb(160, 166, 178), Color.rgb(120, 126, 138), GlyphIconView.Glyph.PERSON),
                    36f, "Search Contacts", "Allow access to find people here", trailing = "Allow")
                row.setOnClickListener {
                    prefs.edit().putBoolean(KEY_ASKED_CONTACTS, true).apply()
                    activity.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
                }
                card.addView(row)
            }
            for (c in contacts) {
                val row = ResultRow(activity, palette, AvatarView(activity, c.name, c.photo), 36f, c.name, "Contact")
                row.setOnClickListener { start(c.intent()) }
                card.addView(row)
            }
            content.addView(card, matchWidth())
        }

        if (r.settings.isNotEmpty()) {
            content.addView(sectionHeader(activity, "Settings", palette))
            val card = card(activity, palette)
            val settingsApp = SpotlightSearch.settingsPackage(activity)
                ?.let { pkg -> launcher.apps.all.firstOrNull { it.packageName == pkg } }
            for (s in r.settings) {
                val icon: View = settingsApp?.let { AppIconView(activity, activity.dpi(36f), showLabel = false).also { v -> v.app = it } }
                    ?: GlyphIconView(activity, Color.rgb(142, 142, 147), Color.rgb(99, 99, 102), GlyphIconView.Glyph.GEAR)
                val row = ResultRow(activity, palette, icon, 36f, s.title, s.subtitle)
                row.setOnClickListener { start(s.intent()) }
                card.addView(row)
            }
            content.addView(card, matchWidth())
        }

        if (r.apps.isEmpty() && r.settings.isEmpty() && contacts.isEmpty() && r.calculation == null) {
            content.addView(TextView(activity).apply {
                text = "No Results"
                setTextColor(palette.secondary)
                textSize = 17f
                gravity = Gravity.CENTER
                setPadding(0, activity.dpi(28f), 0, activity.dpi(4f))
            }, matchWidth())
        }

        content.addView(sectionHeader(activity, "Search the Web", palette))
        val web = card(activity, palette)
        val webRow = ResultRow(activity, palette,
            GlyphIconView(activity, Color.rgb(64, 156, 255), Color.rgb(10, 110, 230), GlyphIconView.Glyph.SEARCH),
            36f, "“${r.query.trim()}”", "Search the web")
        webRow.setOnClickListener { start(SpotlightSearch.webSearch(activity, r.query.trim())) }
        web.addView(webRow)
        content.addView(web, matchWidth())
    }

    private fun bindApp(icon: AppIconView, target: View, app: AppEntry) {
        target.setOnClickListener { launch(app, icon) }
        target.setOnLongClickListener { activity.showContextMenu(null, app, icon); true }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    // ---- Actions ----

    /** Return on the keyboard: a sum copies its answer, else the top app opens, else the web. */
    private fun openBestResult() {
        val r = results ?: return
        when {
            r.calculation != null -> copy(r.calculation)
            r.topHit != null -> launch(r.topHit!!, null)
            r.query.isNotBlank() -> start(SpotlightSearch.webSearch(activity, r.query.trim()))
        }
    }

    private fun launch(app: AppEntry, source: View?) {
        if (source != null) activity.launch(app, source) else {
            history.record(app)
            launcher.apps.launch(app, null)
        }
        dismissNow()
    }

    private fun start(intent: Intent) {
        try {
            activity.startActivity(intent)
            dismissNow()
        } catch (e: Exception) {
            Toast.makeText(activity, "Nothing can open that", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copy(value: String) {
        val cm = activity.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("Result", value))
        field.tick()
        // Android 13+ shows its own clipboard confirmation, so no toast.
    }

    /** After launching something: vanish at once, so returning home shows the home screen. */
    private fun dismissNow() {
        closing = true
        hideKeyboard()
        post { removeSelf() }
    }

    companion object {
        private const val KEY_ASKED_CONTACTS = "asked_contacts"
        private const val REQUEST_CONTACTS = 4101
        private val WORKER = Executors.newSingleThreadExecutor { r -> Thread(r, "spotlight-search") }

        /** Shows Spotlight over [activity]'s home screen, unless it is already open. */
        fun open(activity: LauncherActivity) {
            val overlay = activity.overlay
            for (i in 0 until overlay.childCount) {
                val v = overlay.getChildAt(i)
                if (v is SpotlightView && !v.closing) return
            }
            overlay.addView(SpotlightView(activity), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }
}
