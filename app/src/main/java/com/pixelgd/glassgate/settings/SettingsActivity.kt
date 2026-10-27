package com.pixelgd.glassgate.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Insets
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.isDarkMode
import com.pixelgd.glassgate.util.withAlpha

/**
 * Glassgate's Settings app, built like iOS Settings: a root list of coloured rows that push
 * pages, which slide in from the right and slide back out with Back. With gesture navigation
 * the back swipe drags the page out under your finger, like the iOS edge swipe.
 *
 * Every control writes straight to [com.pixelgd.glassgate.Prefs] (or the layout / wallpaper),
 * and the home screen rebuilds itself when you come back.
 *
 * Open it with [open]; it also has its own icon among the apps, and jiggle mode's Edit menu
 * links to it.
 */
class SettingsActivity : Activity() {

    lateinit var colors: IosColors
        private set
    lateinit var list: IosList
        private set

    private lateinit var container: FrameLayout
    private val stack = ArrayList<SettingsPage>()
    private var insets: Insets = Insets.NONE

    /** 0 = the top page fully in place, 1 = fully slid out to the right. */
    private var slide = 0f
    private val slideSpring = Spring(Spring.STIFF_SNAP, Spring.DAMP_SNAP) { v -> applySlide(v) }
    private var animating = false

    private val backCallback = object : OnBackAnimationCallback {
        override fun onBackStarted(backEvent: BackEvent) {
            slideSpring.cancel()
        }

        override fun onBackProgressed(backEvent: BackEvent) {
            if (stack.size > 1) applySlide(backEvent.progress * 0.85f)
        }

        override fun onBackInvoked() = pop()

        override fun onBackCancelled() {
            slideSpring.snapTo(slide)
            slideSpring.animateTo(0f)
        }
    }
    private var backRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        colors = IosColors(this)
        list = IosList(this, colors)
        window.setDecorFitsSystemWindows(false)
        val light = if (isDarkMode) 0 else
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(light,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)

        container = FrameLayout(this).apply { setBackgroundColor(colors.background) }
        setContentView(container)
        container.setOnApplyWindowInsetsListener { _, wi ->
            insets = wi.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            stack.forEach { it.applyInsets(insets) }
            wi
        }

        push(RootPage(this), animate = false)
        intent?.getStringExtra(EXTRA_PAGE)?.let { id -> pageFor(id)?.let { push(it, animate = false) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val id = intent.getStringExtra(EXTRA_PAGE) ?: return
        while (stack.size > 1) removeTop()
        pageFor(id)?.let { push(it, animate = false) }
    }

    override fun onResume() {
        super.onResume()
        stack.lastOrNull()?.onShown()
    }

    /** Pages that other screens can open directly. */
    private fun pageFor(id: String): SettingsPage? = when (id) {
        PAGE_WALLPAPER -> WallpaperPage(this)
        PAGE_HOME -> HomeScreenPage(this)
        PAGE_ICONS -> AppIconsPage(this)
        else -> null
    }

    // ---- navigation ----

    fun push(page: SettingsPage, animate: Boolean = true) {
        if (animating) return
        page.ensureBuilt()
        page.applyInsets(insets)
        container.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        stack += page
        page.elevation = stack.size.toFloat()
        updateBack()
        page.onShown()
        if (animate && container.width > 0) {
            animating = true
            slideSpring.snapTo(1f)
            slideSpring.animateTo(0f) { animating = false }
        } else {
            applySlide(0f)
        }
    }

    fun pop() {
        if (stack.size <= 1) { finish(); return }
        animating = true
        slideSpring.snapTo(slide)
        slideSpring.animateTo(1f) {
            animating = false
            removeTop()
            stack.lastOrNull()?.onShown()
        }
    }

    private fun removeTop() {
        val top = stack.removeAt(stack.lastIndex)
        container.removeView(top)
        slide = 0f
        stack.forEach { it.translationX = 0f; it.foreground = null }
        updateBack()
    }

    /** Top page offset by [t] of the width; the one under it parallaxes and dims, like UIKit. */
    private fun applySlide(t: Float) {
        slide = t
        val w = container.width.toFloat()
        val top = stack.lastOrNull() ?: return
        top.translationX = w * t
        top.translationZ = 0f
        val under = stack.getOrNull(stack.size - 2) ?: return
        under.translationX = -w * 0.3f * (1f - t)
        under.foreground = ColorDrawable(Color.BLACK.withAlpha(0.10f * (1f - t)))
    }

    private fun updateBack() {
        val want = stack.size > 1
        if (want == backRegistered) return
        backRegistered = want
        if (want) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
    }

    // ---- activity results for pages (photo picker, backup files) ----

    private val pending = HashMap<Int, (Int, Intent?) -> Unit>()
    private var nextRequest = 100

    fun startForResult(intent: Intent, onResult: (resultCode: Int, data: Intent?) -> Unit): Boolean {
        val code = nextRequest++
        pending[code] = onResult
        return try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, code)
            true
        } catch (e: Exception) {
            pending.remove(code)
            false
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        pending.remove(requestCode)?.invoke(resultCode, data)
    }

    companion object {
        private const val EXTRA_PAGE = "page"
        const val PAGE_WALLPAPER = "wallpaper"
        const val PAGE_HOME = "home"
        const val PAGE_ICONS = "icons"

        /** Entry point for menus and edit mode; [page] opens straight onto one screen. */
        fun open(context: Context, page: String? = null) {
            context.startActivity(Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply { page?.let { putExtra(EXTRA_PAGE, it) } })
        }
    }
}

/** Helper for pages that need [View] ids-free visibility toggling. */
internal fun View.shown(on: Boolean) { visibility = if (on) View.VISIBLE else View.GONE }
