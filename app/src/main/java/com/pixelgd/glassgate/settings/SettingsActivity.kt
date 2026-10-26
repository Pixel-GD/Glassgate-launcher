package com.pixelgd.glassgate.settings

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.pixelgd.glassgate.Launcher
import com.pixelgd.glassgate.badges.Badges
import com.pixelgd.glassgate.glass.Backdrop
import com.pixelgd.glassgate.glass.WallpaperSource
import com.pixelgd.glassgate.icons.IconStyle
import com.pixelgd.glassgate.util.Main
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.dpi
import com.pixelgd.glassgate.util.isDarkMode
import com.pixelgd.glassgate.util.withAlpha
import java.io.File
import java.util.concurrent.Executors

/**
 * Glassgate's settings, laid out like an iOS Settings page: a large title that hands off to
 * a small one as you scroll, and inset grouped cards. Every control writes straight to
 * [com.pixelgd.glassgate.Prefs] (or the layout / wallpaper), and the home screen rebuilds
 * itself when you come back.
 *
 * Open it with [open]; it also has its own "Home Screen" icon among the apps.
 */
class SettingsActivity : Activity() {

    private val launcher by lazy { Launcher.of(this) }
    private lateinit var colors: IosColors
    private lateinit var list: IosList
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var topBar: FrameLayout
    private lateinit var smallTitle: TextView
    private lateinit var largeTitle: TextView

    private lateinit var iconPicker: IconStylePicker
    private lateinit var wallpaperChecks: Map<String, View>
    private lateinit var photoSwatch: WallpaperSwatch
    private lateinit var accessRow: IosList.Row
    private lateinit var homeRow: IosList.Row

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        colors = IosColors(this)
        list = IosList(this, colors)
        window.setDecorFitsSystemWindows(false)
        val light = if (isDarkMode) 0 else
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(light,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)

        val root = FrameLayout(this).apply { setBackgroundColor(colors.background) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(content)
        }
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        buildTopBar(root)
        buildContent()
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val side = dpi(16f)
            scroll.setPadding(side + bars.left, bars.top + dpi(52f), side + bars.right, bars.bottom + dpi(36f))
            topBar.setPadding(bars.left, bars.top, bars.right, 0)
            topBar.layoutParams.height = bars.top + dpi(52f)
            topBar.requestLayout()
            insets
        }
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> onScrolled(y) }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---- Chrome: back button and the scroll-edge title bar ----

    private fun buildTopBar(root: FrameLayout) {
        topBar = FrameLayout(this)
        // iOS 26 fades content out under the bar instead of drawing a hard edge.
        topBar.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(colors.background, colors.background, colors.background.withAlpha(0f)))
        topBar.background.alpha = 0
        smallTitle = TextView(this).apply {
            text = TITLE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(colors.label)
            typeface = IosList.BOLD
            alpha = 0f
        }
        topBar.addView(smallTitle, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val back = BackButton(this, colors).apply {
            setOnClickListener { finish() }
            contentDescription = "Back"
        }
        topBar.addView(back, FrameLayout.LayoutParams(dpi(44f), dpi(44f), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = dpi(16f)
        })
        root.addView(topBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dpi(52f), Gravity.TOP))
    }

    private fun onScrolled(y: Int) {
        // The large title slides under the bar; the small one takes over as it disappears.
        val t = ((y - dp(18f)) / dp(22f)).coerceIn(0f, 1f)
        smallTitle.alpha = t
        topBar.background.alpha = Math.round(255 * (y / dp(24f)).coerceIn(0f, 1f))
        largeTitle.alpha = 1f - t
    }

    // ---- Content ----

    private fun buildContent() {
        largeTitle = TextView(this).apply {
            text = TITLE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            setTextColor(colors.label)
            typeface = IosList.BOLD
            letterSpacing = -0.01f
            setPadding(dpi(4f), dpi(4f), 0, 0)
        }
        content.addView(largeTitle)

        iconsSection()
        homeScreenSection()
        glassSection()
        wallpaperSection()
        badgesSection()
        aboutSection()
    }

    private fun section(header: String?, card: LinearLayout, footer: String? = null) {
        header?.let { content.addView(list.header(it)) } ?: content.addView(View(this), LinearLayout.LayoutParams(1, dpi(26f)))
        content.addView(card)
        footer?.let { content.addView(list.footer(it)) }
    }

    private fun iconsSection() {
        val prefs = launcher.prefs
        val card = list.card()
        iconPicker = IconStylePicker(this, colors).apply {
            select(prefs.iconStyle)
            onSelect = { prefs.iconStyle = it }
        }
        card.addView(iconPicker)
        val labels = IosSwitch(this, colors).apply {
            setOn(prefs.showLabels)
            onChange = { prefs.showLabels = it }
        }
        card.addView(list.row("Show App Names", trailing = labels, onClick = { labels.toggle() }))
        section("App Icons", card, "Clear and Tinted icons take their colour from your wallpaper.")
    }

    private fun homeScreenSection() {
        val prefs = launcher.prefs
        val card = list.card()
        val grids = listOf(4 to 6, 5 to 6)
        val layout = launcher.layout.layout
        val grid = IosSegmented(this, colors, grids.map { "${it.first} × ${it.second}" }).apply {
            select(grids.indexOf(layout.cols to layout.rows).coerceAtLeast(0))
            onSelect = { i ->
                val (cols, rows) = grids[i]
                // Five columns need slightly smaller icons to keep the iOS spacing.
                prefs.iconSizeDp = if (cols >= 5) 54 else 60
                launcher.layout.edit { GridResize.apply(it, cols, rows) }
            }
        }
        card.addView(list.row("Layout", trailing = grid))
        val dock = IosSwitch(this, colors).apply {
            setOn(prefs.showDock)
            onChange = { prefs.showDock = it }
        }
        card.addView(list.row("Show Dock", trailing = dock, onClick = { dock.toggle() }))
        section("Home Screen", card)
    }

    private fun glassSection() {
        val prefs = launcher.prefs
        val card = list.card()
        val slider = IosSlider(this, colors).apply {
            value = prefs.glassIntensity
            onChange = { prefs.glassIntensity = it }
        }
        val ends = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(caption("Clear"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(caption("Tinted").apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16f), dpi(12f), dpi(16f), dpi(10f))
            addView(slider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(ends)
        }
        card.addView(box)
        section("Liquid Glass", card,
            "Clear lets more of the wallpaper through the dock, folders and menus. Tinted frosts them for contrast.")
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(colors.secondary)
        setPadding(dpi(4f), 0, dpi(4f), 0)
    }

    private fun wallpaperSection() {
        val card = list.card(separatorInsetDp = 60f)
        val celosiaCheck = list.check()
        val photoCheck = list.check()
        val systemCheck = list.check()
        wallpaperChecks = mapOf(
            WallpaperSource.MODE_CELOSIA to celosiaCheck,
            WallpaperSource.MODE_PHOTO to photoCheck,
            WallpaperSource.MODE_SYSTEM to systemCheck,
        )
        photoSwatch = WallpaperSwatch(this, null)
        card.addView(list.row("Celosia", "Glassgate's live wallpaper",
            leading = WallpaperSwatch(this, Backdrop.Celosia(isDarkMode)), trailing = celosiaCheck,
            onClick = { setWallpaperMode(WallpaperSource.MODE_CELOSIA) }))
        card.addView(list.row("Photo", "Choose from your library",
            leading = photoSwatch, trailing = photoCheck, onClick = { pickPhoto() }))
        card.addView(list.row("System Wallpaper", "Use the One UI wallpaper",
            leading = WallpaperSwatch(this, null), trailing = systemCheck,
            onClick = { setWallpaperMode(WallpaperSource.MODE_SYSTEM) }))
        section("Wallpaper", card,
            "Liquid Glass can only bend Glassgate's own wallpaper. With the system wallpaper, glass is frosted instead.")
        loadPhotoSwatch()
        showWallpaperMode()
    }

    private fun setWallpaperMode(mode: String) {
        launcher.wallpaper.mode = mode
        showWallpaperMode()
        iconPicker.refresh()
    }

    private fun showWallpaperMode() {
        val mode = launcher.wallpaper.mode
        val effective = if (mode == WallpaperSource.MODE_PHOTO && launcher.wallpaper.current !is Backdrop.Photo)
            WallpaperSource.MODE_CELOSIA else mode
        wallpaperChecks.forEach { (m, v) -> v.visibility = if (m == effective) View.VISIBLE else View.INVISIBLE }
    }

    private fun badgesSection() {
        val prefs = launcher.prefs
        val card = list.card()
        val badges = IosSwitch(this, colors).apply {
            setOn(prefs.showBadges)
            onChange = { prefs.showBadges = it }
        }
        card.addView(list.row("Notification Badges", trailing = badges, onClick = { badges.toggle() }))
        accessRow = list.row("Notification Access", value = "", chevron = true, onClick = { openNotificationAccess() })
        card.addView(accessRow)
        section("Notifications", card,
            "Badges show how many unread notifications an app has. Glassgate needs notification access to count them; it never reads or keeps their content.")
    }

    private fun aboutSection() {
        val card = list.card()
        homeRow = list.row("Default Home App", value = "", chevron = true, onClick = {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        })
        card.addView(homeRow)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        card.addView(list.row("Version", value = version))
        section("Glassgate", card)
    }

    private fun refreshStatus() {
        accessRow.valueView?.text = if (Badges.isAccessGranted(this)) "On" else "Off"
        val isHome = getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
        homeRow.valueView?.text = if (isHome) "Glassgate" else "Not Set"
    }

    private fun openNotificationAccess() {
        try {
            startActivity(Badges.accessSettingsIntent(this))
        } catch (e: Exception) {
            runCatching { startActivity(Badges.accessListIntent()) }
        }
    }

    // ---- Photo wallpaper ----

    private fun pickPhoto() {
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(intent, REQUEST_PHOTO)
        } catch (e: Exception) {
            Toast.makeText(this, "No photo picker found", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_PHOTO || resultCode != RESULT_OK || uri == null) return
        val metrics = resources.displayMetrics
        val maxSide = maxOf(metrics.widthPixels, metrics.heightPixels)
        WORKER.execute {
            val bmp = runCatching { decode(uri, maxSide) }
                .onFailure { Log.w(TAG, "Couldn't decode wallpaper", it) }.getOrNull()
            Main.post {
                if (isDestroyed) return@post
                if (bmp == null) {
                    Toast.makeText(this, "Couldn't open that photo", Toast.LENGTH_SHORT).show()
                    return@post
                }
                launcher.wallpaper.setPhoto(bmp)
                photoSwatch.backdrop = launcher.wallpaper.current
                photoSwatch.invalidate()
                showWallpaperMode()
                iconPicker.refresh()
            }
        }
    }

    /** Decodes to a software bitmap no bigger than the screen needs. */
    private fun decode(uri: Uri, maxSide: Int) =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
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
        val file = File(filesDir, "wallpaper.jpg")
        if (!file.exists()) return
        WORKER.execute {
            val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 8 }) ?: return@execute
            Main.post {
                photoSwatch.backdrop = Backdrop.Photo(bmp)
                photoSwatch.invalidate()
            }
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

    companion object {
        private const val TAG = "Settings"
        private const val TITLE = "Home Screen"
        private const val REQUEST_PHOTO = 1
        private val WORKER = Executors.newSingleThreadExecutor { r -> Thread(r, "settings-io") }

        /** Entry point for menus and edit mode. */
        fun open(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
