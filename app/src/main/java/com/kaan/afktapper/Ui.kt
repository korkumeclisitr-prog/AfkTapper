package com.kaan.afktapper

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Koyu tema renkleri ve küçük arayüz yardımcıları (XML yok, hepsi kodla). */
object Ui {
    val BG = 0xFF0E0F1A.toInt()
    val CARD = 0xFF181A2B.toInt()
    val FIELD = 0xFF222640.toInt()
    val STROKE = 0xFF2E3250.toInt()
    val ACCENT = 0xFF7C5CFF.toInt()
    val ACCENT2 = 0xFFFF5CA8.toInt()
    val TEXT = 0xFFF2F3FA.toInt()
    val MUTED = 0xFF9AA0C0.toInt()
    val GOOD = 0xFF34D399.toInt()
    val BAD = 0xFFFF6B6B.toInt()

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun rounded(c: Context, color: Int, radius: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(c, radius).toFloat()
            setColor(color)
            if (stroke != 0) setStroke(dp(c, 1), stroke)
        }

    fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    fun ripple(content: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null)

    fun label(c: Context, s: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun field(c: Context, value: String, hint: String, inputType: Int): EditText =
        EditText(c).apply {
            this.inputType = inputType
            setText(value)
            this.hint = hint
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            textSize = 15f
            background = rounded(c, FIELD, 12, STROKE)
            setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        }

    fun primaryButton(c: Context, s: String): TextView =
        TextView(c).apply {
            text = s
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACCENT, ACCENT2))
            g.cornerRadius = dp(c, 16).toFloat()
            background = ripple(g)
            setPadding(0, dp(c, 16), 0, dp(c, 16))
            isClickable = true
        }

    fun chip(c: Context, s: String): TextView =
        TextView(c).apply {
            text = s
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = ripple(rounded(c, ACCENT, 12))
            setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8))
            isClickable = true
        }
}

/** Yan yana seçenekler (sekme gibi). */
class Segmented(
    ctx: Context,
    options: List<String>,
    selected: Int,
    private val onChange: (Int) -> Unit
) : LinearLayout(ctx) {

    private val tabs = ArrayList<TextView>()
    var index: Int = selected
        private set

    init {
        orientation = HORIZONTAL
        background = Ui.rounded(ctx, Ui.FIELD, 14)
        val pad = Ui.dp(ctx, 4)
        setPadding(pad, pad, pad, pad)
        options.forEachIndexed { i, name ->
            val t = TextView(ctx).apply {
                text = name
                textSize = 13.5f
                gravity = Gravity.CENTER
                setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 11), Ui.dp(ctx, 6), Ui.dp(ctx, 11))
                isClickable = true
                setOnClickListener { select(i) }
            }
            tabs.add(t)
            addView(t, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        paint()
    }

    fun select(i: Int, notify: Boolean = true) {
        index = i
        paint()
        if (notify) onChange(i)
    }

    private fun paint() {
        tabs.forEachIndexed { i, t ->
            val on = i == index
            t.setTextColor(if (on) Color.WHITE else Ui.MUTED)
            t.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            t.background = if (on) Ui.rounded(context, Ui.ACCENT, 11) else null
        }
    }
}
