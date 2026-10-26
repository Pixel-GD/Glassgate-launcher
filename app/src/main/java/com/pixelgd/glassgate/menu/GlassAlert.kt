package com.pixelgd.glassgate.menu

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.pixelgd.glassgate.DismissibleOverlay
import com.pixelgd.glassgate.LauncherActivity
import com.pixelgd.glassgate.glass.GlassDrawable
import com.pixelgd.glassgate.glass.GlassStyle
import com.pixelgd.glassgate.util.Spring
import com.pixelgd.glassgate.util.dp
import com.pixelgd.glassgate.util.dpi
import com.pixelgd.glassgate.util.isDarkMode
import com.pixelgd.glassgate.util.withAlpha

/**
 * An iOS 26 alert: a glass card with a bold title, a short message, an optional text field and
 * capsule buttons stacked vertically. It pops in with a spring over a light dim.
 *
 *     GlassAlert.show(activity, "Remove “Maps”?", "…",
 *         GlassAlert.Button("Remove from Home Screen") { … },
 *         GlassAlert.Button("Cancel", cancel = true))
 */
@SuppressLint("ViewConstructor")
class GlassAlert private constructor(
    private val activity: LauncherActivity,
    title: String,
    message: String?,
    private val textField: EditText?,
    buttons: List<Button>,
) : FrameLayout(activity), DismissibleOverlay {

    class Button(
        val label: String,
        val destructive: Boolean = false,
        val cancel: Boolean = false,
        val action: (() -> Unit)? = null,
    )

    private val dark = activity.isDarkMode
    private val ink = if (dark) Color.WHITE else Color.rgb(20, 20, 24)
    private val card = LinearLayout(activity)
    private var leaving = false
    private val spring = Spring(Spring.STIFF_UI, Spring.DAMP_UI) { apply(it) }
    private val fade = Spring(Spring.STIFF_SNAP, Spring.DAMP_SNAP) { alpha = it }

    init {
        card.orientation = LinearLayout.VERTICAL
        card.background = GlassDrawable(card, GlassStyle.MENU.copy(cornerRadius = 30f))
        val p = activity.dpi(20f)
        card.setPadding(p, p, p, activity.dpi(14f))
        card.isClickable = true

        card.addView(text(title, 17f, Typeface.BOLD))
        if (!message.isNullOrEmpty()) {
            card.addView(text(message, 13.5f, Typeface.NORMAL).apply { alpha = 0.85f }, wrap(top = 6f))
        }
        textField?.let { f ->
            f.setTextColor(ink)
            f.setHintTextColor(ink.withAlpha(0.4f))
            f.textSize = 16f
            f.isSingleLine = true
            f.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            f.imeOptions = EditorInfo.IME_ACTION_DONE
            f.setSelectAllOnFocus(true)
            f.background = capsule(ink.withAlpha(if (dark) 0.14f else 0.08f))
            f.setPadding(activity.dpi(14f), activity.dpi(10f), activity.dpi(14f), activity.dpi(10f))
            card.addView(f, wrap(top = 14f))
        }
        buttons.forEachIndexed { i, b -> card.addView(button(b), wrap(top = if (i == 0) 18f else 8f)) }

        addView(card, LayoutParams(activity.dpi(290f), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        isClickable = true
        apply(0f)
    }

    private fun text(s: String, size: Float, style: Int) = TextView(activity).apply {
        text = s
        textSize = size
        setTextColor(ink)
        typeface = Typeface.create(Typeface.DEFAULT, style)
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun wrap(top: Float) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = activity.dpi(top) }

    private fun capsule(color: Int) = GradientDrawable().apply {
        cornerRadius = activity.dp(999f)
        setColor(color)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun button(b: Button) = TextView(activity).apply {
        text = b.label
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(if (b.destructive) Color.rgb(255, 59, 48) else ink)
        typeface = Typeface.create(Typeface.DEFAULT, if (b.cancel) Typeface.BOLD else Typeface.NORMAL)
        val normal = ink.withAlpha(if (dark) 0.14f else 0.08f)
        val pressed = ink.withAlpha(if (dark) 0.26f else 0.18f)
        background = capsule(normal)
        minHeight = activity.dpi(46f)
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> (v.background as GradientDrawable).setColor(pressed)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> (v.background as GradientDrawable).setColor(normal)
            }
            false
        }
        setOnClickListener {
            if (leaving) return@setOnClickListener
            dismiss()
            b.action?.invoke()
        }
    }

    /** The text field's current text (alerts made with [prompt]). */
    val input: String get() = textField?.text?.toString()?.trim().orEmpty()

    private fun apply(t: Float) {
        val a = t.coerceIn(0f, 1f)
        setBackgroundColor(Color.BLACK.withAlpha(0.22f * a))
        card.alpha = a
        val s = 1.12f - 0.12f * t
        card.scaleX = s
        card.scaleY = s
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spring.animateTo(1f, 0f)
        textField?.let { f ->
            f.requestFocus()
            f.post { f.windowInsetsController?.show(WindowInsets.Type.ime()) }
        }
    }

    override fun onDetachedFromWindow() {
        spring.cancel()
        fade.cancel()
        super.onDetachedFromWindow()
    }

    override fun dismiss() {
        if (leaving) return
        leaving = true
        textField?.let { it.windowInsetsController?.hide(WindowInsets.Type.ime()) }
        spring.cancel()
        // Fade without the pop: iOS alerts just dissolve.
        fade.snapTo(alpha)
        fade.animateTo(0f) { (parent as? ViewGroup)?.removeView(this) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true // modal: taps outside do nothing

    companion object {
        fun show(activity: LauncherActivity, title: String, message: String?, vararg buttons: Button): GlassAlert =
            add(activity, GlassAlert(activity, title, message, null, buttons.toList()))

        /** An alert with a text field prefilled with [initial]; [onDone] gets the trimmed text. */
        fun prompt(activity: LauncherActivity, title: String, initial: String, ok: String, onDone: (String) -> Unit): GlassAlert {
            val field = EditText(activity).apply { setText(initial) }
            lateinit var alert: GlassAlert
            alert = GlassAlert(activity, title, null, field, listOf(
                Button(ok, cancel = true) { onDone(alert.input) },
                Button("Cancel"),
            ))
            field.setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_DONE) { alert.dismiss(); onDone(alert.input); true } else false
            }
            return add(activity, alert)
        }

        private fun add(activity: LauncherActivity, alert: GlassAlert): GlassAlert {
            activity.overlay.addView(alert, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            return alert
        }
    }
}
