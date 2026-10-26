package com.pixelgd.glassgate.spotlight

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.pixelgd.glassgate.apps.AppEntry
import com.pixelgd.glassgate.glass.Squircle
import com.pixelgd.glassgate.home.AppIconView
import com.pixelgd.glassgate.util.Main
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.dpi
import com.pixelgd.glassgate.util.isDarkMode
import com.pixelgd.glassgate.util.withAlpha
import java.util.concurrent.Executors

/**
 * Spotlight's colours. Like iOS, the sheet is light frosted glass with dark text in light
 * mode and dark glass with white text in dark mode.
 */
internal class SpotlightPalette(context: Context) {
    val dark = context.isDarkMode
    val text = if (dark) Color.WHITE else Color.rgb(18, 18, 22)
    val secondary = if (dark) Color.WHITE.withAlpha(0.60f) else Color.BLACK.withAlpha(0.52f)
    val card = if (dark) Color.WHITE.withAlpha(0.10f) else Color.WHITE.withAlpha(0.52f)
    val cardRim = Color.WHITE.withAlpha(if (dark) 0.10f else 0.55f)
    val pressed = if (dark) Color.WHITE.withAlpha(0.12f) else Color.BLACK.withAlpha(0.07f)
    val divider = if (dark) Color.WHITE.withAlpha(0.12f) else Color.BLACK.withAlpha(0.10f)
    /** Extra dimming over the sheet glass so white text holds up on bright wallpapers. */
    val scrim = if (dark) Color.BLACK.withAlpha(0.18f) else Color.TRANSPARENT
}

/** A rounded "platter" fill with a hairline rim, the backing for each results group. */
internal class PlatterDrawable(private val fill: Int, private val rim: Int, private val radius: Float) : Drawable() {
    private val path = Path()
    private val rect = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = rim }

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        Squircle.roundRect(rect, radius, path)
        canvas.drawPath(path, fillPaint)
        rimPaint.strokeWidth = radius / 26f
        rect.inset(rimPaint.strokeWidth / 2f, rimPaint.strokeWidth / 2f)
        Squircle.roundRect(rect, radius, path)
        canvas.drawPath(path, rimPaint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Bold section title ("Siri Suggestions", "Top Hit", "Settings"...). */
internal fun sectionHeader(context: Context, title: String, palette: SpotlightPalette) = TextView(context).apply {
    text = title
    setTextColor(palette.text)
    textSize = 15f
    typeface = Typeface.create("sans-serif", Typeface.BOLD)
    letterSpacing = -0.01f
    setPadding(context.dpi(6f), context.dpi(18f), 0, context.dpi(8f))
}

/** A vertical glass-platter card; rows inside get inset hairline dividers. */
internal fun card(context: Context, palette: SpotlightPalette, dividerInsetDp: Float = 62f) = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = PlatterDrawable(palette.card, palette.cardRim, context.dp(24f))
    clipToOutline = true
    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    val line = GradientDrawable().apply {
        setColor(palette.divider)
        setSize(0, context.dpi(0.6f).coerceAtLeast(1))
    }
    dividerDrawable = InsetDrawable(line, context.dpi(dividerInsetDp), 0, 0, 0)
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
}

/** Pressed-state highlight for a tappable row. */
internal fun pressedBackground(palette: SpotlightPalette): Drawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(palette.pressed))
    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
}

/** One result line: leading icon, title, optional subtitle, optional trailing text. */
@SuppressLint("ViewConstructor")
internal class ResultRow(
    context: Context,
    palette: SpotlightPalette,
    leading: View,
    leadingSizeDp: Float,
    title: String,
    subtitle: String?,
    trailing: String? = null,
    big: Boolean = false,
) : LinearLayout(context) {

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dpi(if (big) 76f else 58f)
        setPadding(context.dpi(14f), context.dpi(8f), context.dpi(16f), context.dpi(8f))
        background = pressedBackground(palette)
        isClickable = true
        contentDescription = title

        val size = context.dpi(leadingSizeDp)
        addView(leading, LayoutParams(size, size).apply { marginEnd = context.dpi(12f) })

        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(TextView(context).apply {
            text = title
            setTextColor(palette.text)
            textSize = if (big) 19f else 16f
            typeface = Typeface.create("sans-serif-medium", if (big) Typeface.BOLD else Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (!subtitle.isNullOrEmpty()) {
            texts.addView(TextView(context).apply {
                text = subtitle
                setTextColor(palette.secondary)
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        if (trailing != null) {
            addView(TextView(context).apply {
                text = trailing
                setTextColor(palette.secondary)
                textSize = 13f
            })
        }
    }
}

/** An app icon with a theme-coloured label below, for the suggestion and app grids. */
@SuppressLint("ViewConstructor")
internal class AppCell(context: Context, palette: SpotlightPalette, app: AppEntry, iconPx: Int) : LinearLayout(context) {
    val icon = AppIconView(context, iconPx, showLabel = false).also { it.app = app }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(icon, LayoutParams(iconPx, iconPx))
        addView(TextView(context).apply {
            text = app.label
            setTextColor(palette.text)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(context.dpi(2f), context.dpi(5f), context.dpi(2f), 0)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        contentDescription = app.label
    }
}

/** Rows of up to [columns] app cells, evenly spaced, inside a padded block. */
internal fun appGrid(
    context: Context,
    palette: SpotlightPalette,
    apps: List<AppEntry>,
    columns: Int,
    iconPx: Int,
    bind: (AppCell, AppEntry) -> Unit,
) = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(context.dpi(8f), context.dpi(16f), context.dpi(8f), context.dpi(6f))
    apps.chunked(columns).forEach { chunk ->
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until columns) {
            val app = chunk.getOrNull(i)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (app == null) {
                row.addView(View(context), lp)
            } else {
                row.addView(AppCell(context, palette, app, iconPx).also { bind(it, app) }, lp)
            }
        }
        addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = context.dpi(12f)
        })
    }
}

/** A coloured squircle with a white glyph: settings, calculator and web search icons. */
internal class GlyphIconView(
    context: Context,
    private val top: Int,
    private val bottom: Int,
    private val glyph: Glyph,
) : View(context) {

    enum class Glyph { GEAR, SEARCH, EQUALS, PERSON }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        Squircle.icon(s, s, path)
        fill.shader = LinearGradient(0f, 0f, 0f, s, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawPath(path, fill)
        val c = s / 2f
        stroke.strokeWidth = s * 0.075f
        when (glyph) {
            Glyph.SEARCH -> {
                val r = s * 0.17f
                canvas.drawCircle(c - s * 0.04f, c - s * 0.04f, r, stroke)
                canvas.drawLine(c + r * 0.62f, c + r * 0.62f, c + s * 0.22f, c + s * 0.22f, stroke)
            }
            Glyph.EQUALS -> {
                canvas.drawLine(c - s * 0.18f, c - s * 0.08f, c + s * 0.18f, c - s * 0.08f, stroke)
                canvas.drawLine(c - s * 0.18f, c + s * 0.08f, c + s * 0.18f, c + s * 0.08f, stroke)
            }
            Glyph.GEAR -> {
                stroke.strokeWidth = s * 0.06f
                canvas.drawCircle(c, c, s * 0.20f, stroke)
                canvas.drawCircle(c, c, s * 0.07f, solid)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val x0 = c + Math.cos(a).toFloat() * s * 0.24f
                    val y0 = c + Math.sin(a).toFloat() * s * 0.24f
                    val x1 = c + Math.cos(a).toFloat() * s * 0.31f
                    val y1 = c + Math.sin(a).toFloat() * s * 0.31f
                    canvas.drawLine(x0, y0, x1, y1, stroke)
                }
            }
            Glyph.PERSON -> {
                canvas.drawCircle(c, c - s * 0.10f, s * 0.13f, solid)
                canvas.drawArc(c - s * 0.25f, c + s * 0.07f, c + s * 0.25f, c + s * 0.55f, 180f, 180f, true, solid)
            }
        }
    }
}

/** Round contact avatar: the contact's photo, or initials on iOS's grey gradient. */
internal class AvatarView(context: Context, private val name: String, photo: Uri?) : View(context) {
    private var bitmap: Bitmap? = null
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val initials = name.split(' ').filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.substring(0, 1).uppercase() }

    init {
        contentDescription = name
        if (photo != null) {
            val appContext = context.applicationContext
            PHOTO_THREAD.execute {
                val b = runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(appContext.contentResolver, photo)) { d, _, _ ->
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }.getOrNull()
                if (b != null) Main.post { bitmap = b; invalidate() }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val r = s / 2f
        val b = bitmap
        if (b != null) {
            val scale = s / minOf(b.width, b.height)
            photoPaint.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply {
                    setScale(scale, scale)
                    postTranslate((s - b.width * scale) / 2f, (s - b.height * scale) / 2f)
                })
            }
            canvas.drawCircle(r, r, r, photoPaint)
            return
        }
        bgPaint.shader = LinearGradient(0f, 0f, 0f, s, Color.rgb(166, 171, 182), Color.rgb(134, 139, 150), Shader.TileMode.CLAMP)
        canvas.drawCircle(r, r, r, bgPaint)
        textPaint.textSize = s * 0.40f
        canvas.drawText(initials.ifEmpty { "?" }, r, r - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    companion object {
        private val PHOTO_THREAD = Executors.newSingleThreadExecutor { r -> Thread(r, "spotlight-photos") }
    }
}

/** The magnifier glyph inside the search field. */
internal class MagnifierView(context: Context, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f)
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val r = s * 0.30f
        val c = s * 0.42f
        canvas.drawCircle(c, c, r, paint)
        canvas.drawLine(c + r * 0.72f, c + r * 0.72f, s * 0.88f, s * 0.88f, paint)
    }
}

/** An "x" glyph, for the clear-text and close buttons. */
internal class CrossView(context: Context, color: Int, private val circle: Int = Color.TRANSPARENT) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = circle }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val c = s / 2f
        if (Color.alpha(circle) > 0) canvas.drawCircle(c, c, c, bg)
        val d = s * if (Color.alpha(circle) > 0) 0.16f else 0.22f
        canvas.drawLine(c - d, c - d, c + d, c + d, paint)
        canvas.drawLine(c + d, c - d, c - d, c + d, paint)
    }
}
