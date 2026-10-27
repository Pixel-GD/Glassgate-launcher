package com.pixelgd.glassgate.settings

import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.pixelgd.glassgate.Prefs
import com.pixelgd.glassgate.apps.AppEntry
import com.pixelgd.glassgate.apps.AppLock
import com.pixelgd.glassgate.badges.BadgeService
import com.pixelgd.glassgate.badges.Badges
import com.pixelgd.glassgate.edit.CustomizeSheet
import com.pixelgd.glassgate.glass.Backdrop
import com.pixelgd.glassgate.glass.WallpaperSource
import com.pixelgd.glassgate.icons.IconPack
import com.pixelgd.glassgate.icons.IconStyle
import com.pixelgd.glassgate.menu.Glyph
import com.pixelgd.glassgate.util.Main
import com.pixelgd.glassgate.util.isDarkMode
import com.pixelgd.glassgate.util.tick
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

private val WORKER = Executors.newSingleThreadExecutor { r -> Thread(r, "settings-io") }

/** iOS Settings icon colours. */
private object Tints {
    val BLUE = Color.rgb(0, 122, 255)
    val CYAN = Color.rgb(50, 173, 230)
    val INDIGO = Color.rgb(88, 86, 214)
    val PURPLE = Color.rgb(175, 82, 222)
    val ORANGE = Color.rgb(255, 149, 0)
    val RED = Color.rgb(255, 59, 48)
    val GREEN = Color.rgb(52, 199, 89)
    val PINK = Color.rgb(255, 45, 85)
    val GRAY = Color.rgb(142, 142, 147)
    val TEAL = Color.rgb(48, 176, 199)
}

/** Which optional pieces this build has; the Standard edition leaves some out of its manifest. */
internal object Features {
    fun badges(context: Context): Boolean = runCatching {
        context.packageManager.getServiceInfo(ComponentName(context, BadgeService::class.java), 0)
    }.isSuccess
}

// ---- Root ----

/** The Settings home: search, the Glassgate card, then coloured rows that push pages. */
class RootPage(host: SettingsActivity) : SettingsPage(host, "Settings") {
    override val showsBack = false

    private lateinit var groups: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var homeSuggestion: View

    private class Entry(val title: String, val path: String, val keywords: String, val open: () -> SettingsPage)

    private val index: List<Entry> by lazy {
        val h = host
        listOf(
            Entry("Wallpaper", "Wallpaper", "background photo celosia colour color dim blur", { WallpaperPage(h) }),
            Entry("Dark Appearance Dims Wallpaper", "Wallpaper", "dim dark", { WallpaperPage(h) }),
            Entry("Blur", "Wallpaper", "legibility blur", { WallpaperPage(h) }),
            Entry("Home Screen & App Library", "Home Screen", "grid layout dock search pill dots new apps library", { HomeScreenPage(h) }),
            Entry("Newly Downloaded Apps", "Home Screen & App Library", "install add to home app library only", { HomeScreenPage(h) }),
            Entry("Layout", "Home Screen & App Library", "grid rows columns 4x6 5x6", { HomeScreenPage(h) }),
            Entry("Show on Home Screen", "Home Screen & App Library › Search", "search pill page dots", { HomeScreenPage(h) }),
            Entry("Show Dock", "Home Screen & App Library", "dock", { HomeScreenPage(h) }),
            Entry("App Library", "Home Screen & App Library", "app library drawer", { HomeScreenPage(h) }),
            Entry("App Icons", "App Icons", "icon style default dark clear tinted large small size names labels", { AppIconsPage(h) }),
            Entry("Tinted Colour", "App Icons", "tint color hue eyedropper", { AppIconsPage(h) }),
            Entry("Large Icons", "App Icons", "big size small", { AppIconsPage(h) }),
            Entry("Icon Pack", "App Icons", "icon pack theme nova adw", { IconPackPage(h) }),
            Entry("Show App Names", "App Icons", "labels names text", { AppIconsPage(h) }),
            Entry("Liquid Glass", "Liquid Glass", "glass clear tinted frosted transparency", { GlassPage(h) }),
            Entry("Swipe Down", "Gestures", "gesture notifications quick settings control center search spotlight", { GesturesPage(h) }),
            Entry("Notification Badges", "Notifications", "badges dots count unread", { NotificationsPage(h) }),
            Entry("Require Face ID", "Face ID & Privacy", "lock locked fingerprint biometric pin", { PrivacyPage(h) }),
            Entry("Hidden Apps", "Face ID & Privacy", "hide hidden secret", { PrivacyPage(h) }),
            Entry("Reduce Motion", "Accessibility", "motion animation tilt parallax", { AccessibilityPage(h) }),
            Entry("Reduce Transparency", "Accessibility", "transparency glass solid contrast", { AccessibilityPage(h) }),
            Entry("System Haptics", "Sounds & Haptics", "vibration haptic feedback", { HapticsPage(h) }),
            Entry("Back Up Home Screen", "General", "backup export restore import save", { GeneralPage(h) }),
            Entry("Reset Home Screen Layout", "General", "reset default", { GeneralPage(h) }),
            Entry("Default Home App", "General", "launcher default home", { GeneralPage(h) }),
            Entry("About", "General", "version", { GeneralPage(h) }),
        )
    }

    override fun build() {
        content.addView(searchField(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(40f)).apply {
            topMargin = dpi(10f)
        })
        results = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; shown(false) }
        groups = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results)
        content.addView(groups)

        // The Apple Account card, as Glassgate.
        val account = list.card()
        account.addView(list.row("Glassgate", "Liquid Glass Home Screen · About",
            leading = AppBadge(host), chevron = true, onClick = { host.push(GeneralPage(host)) }))
        add(null, account)

        // iOS puts suggestions ("Finish Setting Up") under the account card.
        val suggestion = list.card()
        suggestion.addView(list.row("Set Glassgate as Default Home App", "So Home and the home gesture come back here",
            leading = icon(Tints.BLUE, Glyph.EDIT_HOME), chevron = true, onClick = { askForHomeRole() }))
        homeSuggestion = suggestion
        add(null, suggestion)

        val look = list.card(separatorInsetDp = 60f)
        look.addView(navRow("Wallpaper", icon(Tints.CYAN, Glyph.WALLPAPER)) { WallpaperPage(host) })
        look.addView(navRow("Home Screen & App Library", icon(Tints.INDIGO, Glyph.EDIT_HOME)) { HomeScreenPage(host) })
        look.addView(navRow("App Icons", icon(Tints.ORANGE, Glyph.GRID)) { AppIconsPage(host) })
        look.addView(navRow("Liquid Glass", icon(Tints.TEAL, Glyph.DROP)) { GlassPage(host) })
        add(null, look)

        val behave = list.card(separatorInsetDp = 60f)
        behave.addView(navRow("Gestures", icon(Tints.PURPLE, Glyph.SWIPE)) { GesturesPage(host) })
        if (Features.badges(host)) behave.addView(navRow("Notifications", icon(Tints.RED, Glyph.BELL)) { NotificationsPage(host) })
        behave.addView(navRow("Face ID & Privacy", icon(Tints.GREEN, Glyph.LOCK)) { PrivacyPage(host) })
        behave.addView(navRow("Accessibility", icon(Tints.BLUE, Glyph.PERSON)) { AccessibilityPage(host) })
        behave.addView(navRow("Sounds & Haptics", icon(Tints.PINK, Glyph.WAVE)) { HapticsPage(host) })
        add(null, behave)

        val general = list.card(separatorInsetDp = 60f)
        general.addView(navRow("General", icon(Tints.GRAY, Glyph.SETTINGS)) { GeneralPage(host) })
        add(null, general)
    }

    private fun add(header: String?, card: View) {
        header?.let { groups.addView(list.header(it)) } ?: groups.addView(View(host), LinearLayout.LayoutParams(1, dpi(24f)))
        groups.addView(card)
    }

    override fun onShown() {
        homeSuggestion.shown(!isDefaultHome(host))
    }

    private fun askForHomeRole() {
        val rm = host.getSystemService(RoleManager::class.java)
        val intent = rm?.takeIf { it.isRoleAvailable(RoleManager.ROLE_HOME) }?.createRequestRoleIntent(RoleManager.ROLE_HOME)
        if (intent == null || !host.startForResult(intent) { _, _ -> onShown() }) {
            runCatching { host.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        }
    }

    /** iOS Settings' search field, filtering every setting by name and keywords. */
    private fun searchField(): View {
        val field = EditText(host).apply {
            hint = "Search"
            setHintTextColor(colors.secondary)
            setTextColor(colors.label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dpi(36f), 0, dpi(12f), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })
        return object : android.widget.FrameLayout(host) {
            init {
                background = GradientDrawable().apply {
                    setColor(this@RootPage.colors.fill)
                    cornerRadius = dp(20f)
                }
                addView(field, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                setWillNotDraw(false)
            }
            override fun dispatchDraw(canvas: Canvas) {
                super.dispatchDraw(canvas)
                Glyph.SEARCH.draw(canvas, dp(20f), height / 2f, dp(17f), colors.secondary)
            }
        }
    }

    private fun filter(q: String) {
        val query = q.trim().lowercase()
        results.removeAllViews()
        if (query.isEmpty()) {
            results.shown(false)
            groups.shown(true)
            return
        }
        groups.shown(false)
        results.shown(true)
        val hits = index.filter { e ->
            query.split(' ').filter { it.isNotEmpty() }.all { w ->
                e.title.lowercase().contains(w) || e.keywords.contains(w) || e.path.lowercase().contains(w)
            }
        }
        results.addView(View(host), LinearLayout.LayoutParams(1, dpi(20f)))
        if (hits.isEmpty()) {
            results.addView(list.footer("No Results for “$q”").apply { gravity = Gravity.CENTER; setPadding(0, dpi(40f), 0, 0) })
            return
        }
        val card = list.card()
        for (e in hits) card.addView(list.row(e.title, e.path, chevron = true, onClick = { host.push(e.open()) }))
        results.addView(card)
    }

    /** Glassgate's own icon, in the current icon look. */
    private class AppBadge(context: Context) : View(context) {
        private val size = (context.resources.displayMetrics.density * 58).toInt()
        private var bmp: Bitmap? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        init {
            val launcher = com.pixelgd.glassgate.Launcher.of(context)
            WORKER.execute {
                val raw = runCatching { context.packageManager.getApplicationIcon(context.packageName) }.getOrNull() ?: return@execute
                val b = runCatching {
                    com.pixelgd.glassgate.icons.IconRenderer.render(raw, size, launcher.prefs.iconStyle, launcher.icons.tint, launcher.prefs.iconsDark())
                }.getOrNull()
                Main.post { bmp = b; invalidate() }
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(size, size)
        override fun onDraw(canvas: Canvas) { bmp?.let { canvas.drawBitmap(it, 0f, 0f, paint) } }
    }

    companion object {
        fun isDefaultHome(context: Context): Boolean =
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
    }
}

// ---- Wallpaper ----

class WallpaperPage(host: SettingsActivity) : SettingsPage(host, "Wallpaper") {
    private lateinit var checks: Map<String, View>
    private lateinit var photoSwatch: WallpaperSwatch
    private lateinit var colorSwatch: WallpaperSwatch

    override fun build() {
        val wp = launcher.wallpaper
        val card = list.card(separatorInsetDp = 60f)
        val celosia = list.check()
        val colour = list.check()
        val photo = list.check()
        val system = list.check()
        checks = mapOf(
            WallpaperSource.MODE_CELOSIA to celosia,
            WallpaperSource.MODE_COLOR to colour,
            WallpaperSource.MODE_PHOTO to photo,
            WallpaperSource.MODE_SYSTEM to system,
        )
        photoSwatch = WallpaperSwatch(host, null)
        colorSwatch = WallpaperSwatch(host, Backdrop.Solid(wp.color, host.isDarkMode))
        card.addView(list.row("Celosia", "Glassgate's live wallpaper",
            leading = WallpaperSwatch(host, Backdrop.Celosia(host.isDarkMode)), trailing = celosia,
            onClick = { setMode(WallpaperSource.MODE_CELOSIA) }))
        card.addView(list.row("Colour", "A soft gradient of one colour",
            leading = colorSwatch, trailing = colour, onClick = { setColour(wp.color) }))
        card.addView(list.row("Photo", "Choose from your library",
            leading = photoSwatch, trailing = photo, onClick = { pickPhoto() }))
        card.addView(list.row("System Wallpaper", "Use the One UI wallpaper",
            leading = WallpaperSwatch(host, null), trailing = system,
            onClick = { setMode(WallpaperSource.MODE_SYSTEM) }))
        section("Home Screen Wallpaper", card,
            "Liquid Glass can only bend Glassgate's own wallpapers. With the system wallpaper, glass is frosted instead.")

        val palette = list.card()
        palette.addView(ColourDots(host, PALETTE, { wp.color }) { setColour(it) })
        section("Colour", palette)

        val look = list.card()
        look.addView(switchRow("Blur", { prefs.blurWallpaper }, "Soften the wallpaper behind your apps") { prefs.blurWallpaper = it })
        look.addView(switchRow("Dark Appearance Dims Wallpaper", { prefs.dimWallpaperInDark }) { prefs.dimWallpaperInDark = it })
        section(null, look, "Blur works on Glassgate's wallpapers. Dimming also darkens the system wallpaper.")
        loadPhotoSwatch()
        showMode()
    }

    private fun setMode(mode: String) {
        launcher.wallpaper.mode = mode
        showMode()
    }

    private fun setColour(c: Int) {
        launcher.wallpaper.color = c
        colorSwatch.backdrop = launcher.wallpaper.current
        colorSwatch.invalidate()
        showMode()
    }

    private fun showMode() {
        val mode = launcher.wallpaper.mode
        val effective = if (mode == WallpaperSource.MODE_PHOTO && launcher.wallpaper.current !is Backdrop.Photo)
            WallpaperSource.MODE_CELOSIA else mode
        checks.forEach { (m, v) -> v.visibility = if (m == effective) View.VISIBLE else View.INVISIBLE }
    }

    private fun pickPhoto() {
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        val started = host.startForResult(intent) { code, data ->
            val uri = data?.data
            if (code == android.app.Activity.RESULT_OK && uri != null) usePhoto(uri)
        }
        if (!started) Toast.makeText(host, "No photo picker found", Toast.LENGTH_SHORT).show()
    }

    private fun usePhoto(uri: Uri) {
        val metrics = resources.displayMetrics
        val maxSide = maxOf(metrics.widthPixels, metrics.heightPixels)
        WORKER.execute {
            val bmp = runCatching { decode(uri, maxSide) }
                .onFailure { Log.w("Settings", "Couldn't decode wallpaper", it) }.getOrNull()
            Main.post {
                if (host.isDestroyed) return@post
                if (bmp == null) {
                    Toast.makeText(host, "Couldn't open that photo", Toast.LENGTH_SHORT).show()
                    return@post
                }
                launcher.wallpaper.setPhoto(bmp)
                photoSwatch.backdrop = launcher.wallpaper.current
                photoSwatch.invalidate()
                showMode()
            }
        }
    }

    /** Decodes to a software bitmap no bigger than the screen needs. */
    private fun decode(uri: Uri, maxSide: Int) =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(host.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxSide * 1.25f) {
                val s = maxSide * 1.25f / longest
                decoder.setTargetSize(Math.round(info.size.width * s), Math.round(info.size.height * s))
            }
        }

    /** The last picked photo, even while another wallpaper is in use. */
    private fun loadPhotoSwatch() {
        val current = launcher.wallpaper.current
        if (current is Backdrop.Photo) { photoSwatch.backdrop = current; return }
        // WallpaperSource keeps the picked photo here.
        val file = File(host.filesDir, "wallpaper.jpg")
        if (!file.exists()) return
        WORKER.execute {
            val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 8 }) ?: return@execute
            Main.post {
                photoSwatch.backdrop = Backdrop.Photo(bmp)
                photoSwatch.invalidate()
            }
        }
    }

    companion object {
        /** iOS 26's Colour wallpaper swatches. */
        val PALETTE = intArrayOf(
            Color.rgb(64, 96, 200), Color.rgb(92, 72, 190), Color.rgb(180, 80, 160), Color.rgb(220, 80, 90),
            Color.rgb(235, 140, 60), Color.rgb(220, 190, 80), Color.rgb(70, 170, 110), Color.rgb(60, 160, 190),
            Color.rgb(90, 90, 100),
        )
    }
}

/** A row of colour circles; the chosen one gets a ring. */
private class ColourDots(
    context: Context,
    private val palette: IntArray,
    private val current: () -> Int,
    private val onPick: (Int) -> Unit,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 2.5f
    }
    private val d = context.resources.displayMetrics.density

    init { isClickable = true }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (64 * d).toInt())

    private fun cx(i: Int) = 16 * d + (width - 32 * d) / palette.size * (i + 0.5f)

    override fun onDraw(canvas: Canvas) {
        val r = minOf(14 * d, (width - 32 * d) / palette.size / 2f - 4 * d)
        palette.forEachIndexed { i, c ->
            paint.color = c
            canvas.drawCircle(cx(i), height / 2f, r, paint)
            if (c == current()) {
                ring.color = c
                canvas.drawCircle(cx(i), height / 2f, r + 5 * d, ring)
            }
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
            val i = ((event.x - 16 * d) / ((width - 32 * d) / palette.size)).toInt()
            palette.getOrNull(i)?.let { tick(); onPick(it); invalidate() }
        }
        return true
    }
}

// ---- Home Screen & App Library ----

class HomeScreenPage(host: SettingsActivity) : SettingsPage(host, "Home Screen & App Library") {
    override fun build() {
        section("Newly Downloaded Apps", checkCard(
            listOf("Add to Home Screen" to true, "App Library Only" to false),
            { prefs.newAppsToHome }, { prefs.newAppsToHome = it }))

        val grids = listOf(4 to 6, 4 to 7, 5 to 6, 5 to 7, 6 to 8)
        val sizes = mapOf(4 to 60, 5 to 54, 6 to 46)
        section("Layout", checkCard(
            grids.map { (c, r) -> (if (c == 4 && r == 6) "$c × $r (iPhone)" else "$c × $r") to (c to r) },
            { launcher.layout.layout.let { it.cols to it.rows } },
        ) { (cols, rows) ->
            // Wider grids need slightly smaller icons to keep the iOS spacing.
            prefs.baseIconSizeDp = sizes[cols] ?: 60
            launcher.layout.edit { GridResize.apply(it, cols, rows) }
        }, "Changing the grid reflows every page. Undo it from the Edit menu on the Home Screen.")

        val home = list.card()
        home.addView(switchRow("Show Dock", { prefs.showDock }) { prefs.showDock = it })
        home.addView(switchRow("Show App Library", { prefs.showAppLibrary }, "Swipe past your last page") { prefs.showAppLibrary = it })
        section("Home Screen", home)

        val search = list.card()
        search.addView(switchRow("Show on Home Screen", { prefs.showSearchPill }) { prefs.showSearchPill = it })
        section("Search", search,
            "With Search off the Home Screen, page dots take its place. Touch and hold, then slide, to scrub through pages either way.")

        if (Features.badges(host)) {
            val badges = list.card()
            badges.addView(switchRow("Show in App Library", { prefs.badgesInLibrary }) { prefs.badgesInLibrary = it })
            section("Notification Badges", badges)
        }
    }
}

// ---- App Icons ----

class AppIconsPage(host: SettingsActivity) : SettingsPage(host, "App Icons") {
    private lateinit var picker: IconStylePicker
    private lateinit var packRow: IosList.Row

    override fun build() {
        val looks = list.card()
        picker = IconStylePicker(host, colors).apply {
            select(prefs.iconStyle)
            onSelect = { prefs.iconStyle = it; refreshSamples() }
        }
        looks.addView(picker)
        val modes = listOf(Prefs.Appearance.AUTO, Prefs.Appearance.LIGHT, Prefs.Appearance.DARK)
        val appearance = IosSegmented(host, colors, listOf("Auto", "Light", "Dark")).apply {
            select(modes.indexOf(prefs.iconAppearance))
            onSelect = { prefs.iconAppearance = modes[it]; refreshSamples() }
        }
        looks.addView(list.row("Appearance", trailing = appearance))
        section("Look", looks, "Auto follows the system's light and dark mode. Clear and Tinted take their colour from your wallpaper unless you pick one below.")

        val tint = list.card()
        tint.addView(sliderCell(prefs.tintColor.takeIf { it != 0 }?.let { CustomizeSheet.hueOf(it) } ?: 0.6f,
            "Colour", "", onEnd = { prefs.tintColor = CustomizeSheet.hueColor(it); refreshSamples() }))
        tint.addView(list.row("Use Wallpaper Colour", value = if (prefs.tintColor == 0) "On" else "", onClick = {
            prefs.tintColor = 0
            refreshSamples()
        }))
        section("Tinted Colour", tint)

        val size = list.card()
        val sizes = IosSegmented(host, colors, listOf("Small", "Large")).apply {
            select(if (prefs.largeIcons) 1 else 0)
            onSelect = { prefs.largeIcons = it == 1 }
        }
        size.addView(list.row("Icon Size", trailing = sizes))
        size.addView(sliderCell(((prefs.baseIconSizeDp - 44) / 28f).coerceIn(0f, 1f), "Smaller", "Bigger",
            onEnd = { prefs.baseIconSizeDp = 44 + Math.round(it * 28) }))
        size.addView(switchRow("Show App Names", { prefs.labelsSetting }) { prefs.labelsSetting = it })
        size.addView(sliderCell(((prefs.labelSizeSp - 10f) / 4f).coerceIn(0f, 1f), "Small Names", "Big Names",
            onEnd = { prefs.labelSizeSp = 10f + Math.round(it * 8) / 2f }))
        section("Size", size, "Large icons hide app names, like on iPhone. The slider fine-tunes the size, an Android extra.")

        val more = list.card()
        packRow = navRow("Icon Pack", value = packName()) { IconPackPage(host) }
        more.addView(packRow)
        section("Android", more, "Icon packs from the Play Store (Nova and ADW format) replace app icons. Glassgate still shapes them as squircles.")
    }

    override fun onShown() {
        if (::packRow.isInitialized) packRow.valueView?.text = packName()
        if (::picker.isInitialized) refreshSamples()
    }

    private fun refreshSamples() {
        launcher.updateIconLook()
        picker.refresh()
        picker.select(prefs.iconStyle)
    }

    private fun packName(): String {
        val pkg = prefs.iconPack ?: return "None"
        return runCatching {
            host.packageManager.getApplicationInfo(pkg, 0).loadLabel(host.packageManager).toString()
        }.getOrDefault("None")
    }
}

class IconPackPage(host: SettingsActivity) : SettingsPage(host, "Icon Pack") {
    override fun build() {
        val packs = IconPack.installed(host)
        val options = listOf("None (App Icons)" to null as String?) + packs.map { it.label to it.packageName }
        section(null, checkCard(options, { prefs.iconPack }) { prefs.iconPack = it },
            if (packs.isEmpty()) "No icon packs installed. Any pack that supports Nova Launcher will work." else null)
    }
}

// ---- Liquid Glass ----

class GlassPage(host: SettingsActivity) : SettingsPage(host, "Liquid Glass") {
    override fun build() {
        val card = list.card()
        card.addView(sliderCell(prefs.glassIntensity, "Clear", "Tinted", onEnd = { prefs.glassIntensity = it },
            onMove = { prefs.glassIntensity = it }))
        section(null, card,
            "Clear lets more of the wallpaper through the dock, folders and menus. Tinted frosts them for contrast, like iOS 26.1's Liquid Glass setting.")
        val more = list.card()
        more.addView(navRow("Reduce Transparency") { AccessibilityPage(host) })
        section(null, more)
    }
}

// ---- Gestures ----

class GesturesPage(host: SettingsActivity) : SettingsPage(host, "Gestures") {
    override fun build() {
        section("Swipe Down on Home Screen", checkCard(
            listOf(
                "Search" to Prefs.SwipeAction.SEARCH,
                "Notifications" to Prefs.SwipeAction.NOTIFICATIONS,
                "Notifications or Quick Settings" to Prefs.SwipeAction.SPLIT,
                "Nothing" to Prefs.SwipeAction.NOTHING,
            ),
            { prefs.swipeDown }, { prefs.swipeDown = it }),
            "On iPhone this opens Search. “Notifications or Quick Settings” works like iOS's two corners: swipe on the left half for notifications, the right half for quick settings.")
        val other = list.card()
        other.addView(list.row("Touch and Hold Wallpaper", value = "Edit Home Screen"))
        other.addView(list.row("Touch and Hold Search", value = "Scrub Pages"))
        other.addView(list.row("Swipe Past Last Page", value = if (prefs.showAppLibrary) "App Library" else "Nothing"))
        section("Always", other)
    }
}

// ---- Notifications ----

class NotificationsPage(host: SettingsActivity) : SettingsPage(host, "Notifications") {
    private lateinit var accessRow: IosList.Row

    override fun build() {
        val card = list.card()
        card.addView(switchRow("Notification Badges", { prefs.showBadges }) { prefs.showBadges = it })
        card.addView(switchRow("Show in App Library", { prefs.badgesInLibrary }) { prefs.badgesInLibrary = it })
        accessRow = list.row("Notification Access", value = "", chevron = true, onClick = { openAccess() })
        card.addView(accessRow)
        section("Badges", card,
            "Badges show how many unread notifications an app has. Glassgate needs notification access to count them; it never reads or keeps their content.")
    }

    override fun onShown() {
        if (::accessRow.isInitialized) accessRow.valueView?.text = if (Badges.isAccessGranted(host)) "On" else "Off"
    }

    private fun openAccess() {
        try {
            host.startActivity(Badges.accessSettingsIntent(host))
        } catch (e: Exception) {
            runCatching { host.startActivity(Badges.accessListIntent()) }
        }
    }
}

// ---- Face ID & Privacy ----

class PrivacyPage(host: SettingsActivity) : SettingsPage(host, "Face ID & Privacy") {
    private lateinit var lockedRow: IosList.Row
    private lateinit var hiddenRow: IosList.Row

    override fun build() {
        val card = list.card()
        lockedRow = navRow("Locked Apps", value = "") { LockedAppsPage(host) }
        hiddenRow = list.row("Hidden Apps", value = "", chevron = true, onClick = {
            AppLock.authenticate(host, "Hidden Apps") { host.push(HiddenAppsPage(host)) }
        })
        card.addView(lockedRow)
        card.addView(hiddenRow)
        section("Require Face ID", card,
            "Locked apps ask for your face, fingerprint or screen lock before they open. Hidden apps also leave the Home Screen, App Library and Search. Touch and hold any app and choose Require Face ID.")
    }

    override fun onShown() {
        if (!::lockedRow.isInitialized) return
        lockedRow.valueView?.text = prefs.lockedApps.size.takeIf { it > 0 }?.toString() ?: "None"
        hiddenRow.valueView?.text = prefs.hiddenApps.size.takeIf { it > 0 }?.toString() ?: "None"
    }
}

/** Every app with a switch to require Face ID. */
class LockedAppsPage(host: SettingsActivity) : SettingsPage(host, "Locked Apps") {
    override fun build() {
        val apps = launcher.apps.all
        val card = list.card(separatorInsetDp = 60f)
        for (app in apps) {
            card.addView(switchRow(app.label, { app.key in prefs.lockedApps }, icon = AppRowIcon(host, app)) { on ->
                // Turning a lock off needs the user too, or anyone could unlock it.
                if (on) prefs.lockedApps = prefs.lockedApps + app.key
                else AppLock.authenticate(host, app.label) { prefs.lockedApps = prefs.lockedApps - app.key }
            })
        }
        section(null, card)
    }
}

/** iOS's Hidden folder: the hidden apps, to open or unhide. */
class HiddenAppsPage(host: SettingsActivity) : SettingsPage(host, "Hidden Apps") {
    private lateinit var card: LinearLayout

    override fun build() {
        card = list.card(separatorInsetDp = 60f)
        section(null, card, "Tap an app to open it. Unhide puts it back in the App Library.")
        fill()
    }

    private fun fill() {
        card.removeAllViews()
        val hidden = launcher.apps.hiddenApps
        if (hidden.isEmpty()) {
            card.addView(list.row("No Hidden Apps"))
            return
        }
        for (app in hidden) {
            val unhide = TextView(host).apply {
                text = "Unhide"
                setTextColor(colors.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setPadding(dpi(8f), dpi(8f), 0, dpi(8f))
                setOnClickListener {
                    prefs.hiddenApps = prefs.hiddenApps - app.key
                    card.postDelayed({ fill() }, 300)
                }
            }
            card.addView(list.row(app.label, leading = AppRowIcon(host, app), trailing = unhide, onClick = {
                // Already verified to get here.
                launchVerified(app)
            }))
        }
    }

    private fun launchVerified(app: AppEntry) {
        runCatching {
            host.getSystemService(android.content.pm.LauncherApps::class.java)
                ?.startMainActivity(app.component, app.user, null, null)
        }
    }
}

/** A small app icon for list rows, rendered in the current icon look. */
private class AppRowIcon(context: Context, private val app: AppEntry) : View(context) {
    private val size = (context.resources.displayMetrics.density * 30).toInt()
    private var bmp: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    init {
        val launcher = com.pixelgd.glassgate.Launcher.of(context)
        WORKER.execute {
            val b = runCatching { launcher.icons.render(app, size) }.getOrNull()
            Main.post { bmp = b; invalidate() }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(size, size)
    override fun onDraw(canvas: Canvas) { bmp?.let { canvas.drawBitmap(it, 0f, 0f, paint) } }
}

// ---- Accessibility, Haptics ----

class AccessibilityPage(host: SettingsActivity) : SettingsPage(host, "Accessibility") {
    override fun build() {
        val motion = list.card()
        motion.addView(switchRow("Reduce Motion", { prefs.reduceMotion }) { prefs.reduceMotion = it })
        section("Motion", motion, "Stops the glass highlights following the phone's tilt, and opens apps with a cross-fade instead of a zoom.")
        val display = list.card()
        display.addView(switchRow("Reduce Transparency", { prefs.reduceTransparency }) { prefs.reduceTransparency = it })
        section("Display", display, "Makes the dock, folders, menus and Search solid so they're easier to read.")
    }
}

class HapticsPage(host: SettingsActivity) : SettingsPage(host, "Sounds & Haptics") {
    override fun build() {
        val card = list.card()
        card.addView(switchRow("System Haptics", { prefs.haptics }) { prefs.haptics = it })
        section(null, card, "Play haptics for touch and hold, jiggle mode, switches and page scrubbing.")
    }
}

// ---- General ----

class GeneralPage(host: SettingsActivity) : SettingsPage(host, "General") {
    private lateinit var homeRow: IosList.Row

    override fun build() {
        val about = list.card()
        val version = runCatching { host.packageManager.getPackageInfo(host.packageName, 0).versionName }.getOrNull() ?: "?"
        about.addView(list.row("Version", value = version))
        about.addView(list.row("Apps", value = launcher.apps.all.size.toString()))
        about.addView(list.row("Home Screen Pages", value = launcher.layout.layout.pages.count { !it.hidden }.toString()))
        homeRow = list.row("Default Home App", value = "", chevron = true, onClick = {
            runCatching { host.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        })
        about.addView(homeRow)
        section("About", about)

        val backup = list.card()
        backup.addView(list.row("Back Up Home Screen", onClick = { exportLayout() }).apply { titleView.setTextColor(colors.accent) })
        backup.addView(list.row("Restore Home Screen", onClick = { importLayout() }).apply { titleView.setTextColor(colors.accent) })
        section("Backup", backup, "Saves your pages, folders, dock and widget places to a file you can keep or move to another phone.")

        val reset = list.card()
        reset.addView(list.row("Reset Home Screen Layout", onClick = { confirmReset() }).apply { titleView.setTextColor(colors.accent) })
        section("Transfer or Reset", reset)
    }

    override fun onShown() {
        if (::homeRow.isInitialized) homeRow.valueView?.text = if (RootPage.isDefaultHome(host)) "Glassgate" else "Not Set"
    }

    private fun exportLayout() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "glassgate-home-screen.json")
        host.startForResult(intent) { code, data ->
            val uri = data?.data ?: return@startForResult
            if (code != android.app.Activity.RESULT_OK) return@startForResult
            val json = JSONObject().put("glassgate", 1).put("layout", launcher.layout.layout.toJson()).toString(2)
            val ok = runCatching { host.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }.isSuccess
            Toast.makeText(host, if (ok) "Home Screen saved" else "Couldn't save the file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importLayout() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
        host.startForResult(intent) { code, data ->
            val uri = data?.data ?: return@startForResult
            if (code != android.app.Activity.RESULT_OK) return@startForResult
            val layout = runCatching {
                val text = host.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                JSONObject(text).getJSONObject("layout")
            }.getOrNull()
            if (layout == null) {
                Toast.makeText(host, "That isn't a Glassgate backup", Toast.LENGTH_SHORT).show()
                return@startForResult
            }
            launcher.layout.replace(layout)
            Toast.makeText(host, "Home Screen restored", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(host)
            .setTitle("Reset Home Screen Layout?")
            .setMessage("Your Home Screen goes back to the default dock with every app A to Z. Widgets and folders are removed. You can Undo from the Edit menu.")
            .setPositiveButton("Reset") { _, _ -> launcher.layout.reset() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
