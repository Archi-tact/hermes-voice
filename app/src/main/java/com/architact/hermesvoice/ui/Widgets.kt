package com.architact.hermesvoice.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.architact.hermesvoice.R

fun Context.dp(value: Float) = value * resources.displayMetrics.density
fun Context.dpi(value: Int) = (value * resources.displayMetrics.density).toInt()
fun Context.color(id: Int) = getColor(id)

/** Keeps content at a readable width on the unfolded Fold7 screen while staying full-width on the cover screen. */
class MaxWidthLayout(context: Context, private val maxWidthPx: Int) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val width = if (available > maxWidthPx) MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.EXACTLY) else widthMeasureSpec
        super.onMeasure(width, heightMeasureSpec)
    }
}

fun Context.rounded(fill: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
    cornerRadius = radius
    setColor(fill)
    if (stroke != null) setStroke(dpi(1), stroke)
}

fun Context.withRipple(content: GradientDrawable, radius: Float) =
    RippleDrawable(ColorStateList.valueOf(color(R.color.hv_ripple)), content, rounded(0xFFFFFFFF.toInt(), radius))

/** Pill button: filled (primary emphasis) or tonal/outlined (secondary). Min 48dp touch height. */
class PillButton(context: Context, filled: Boolean, icon: Int? = null) : TextView(context) {
    init {
        val fg = context.color(if (filled) R.color.hv_on_primary else R.color.hv_on_background)
        val bg = context.color(if (filled) R.color.hv_primary else R.color.hv_surface)
        val radius = context.dp(24f)
        background = context.withRipple(context.rounded(bg, radius, if (filled) null else context.color(R.color.hv_outline)), radius)
        setTextColor(fg)
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        minHeight = context.dpi(48)
        setPadding(context.dpi(20), 0, context.dpi(20), 0)
        isClickable = true
        isFocusable = true
        if (icon != null) {
            val drawable = context.getDrawable(icon)!!.mutate().apply {
                setTint(fg)
                setBounds(0, 0, context.dpi(20), context.dpi(20))
            }
            setCompoundDrawablesRelative(drawable, null, null, null)
            compoundDrawablePadding = context.dpi(8)
        }
    }
}

/** The main round action (72dp) with an always-visible text caption underneath. */
class MainAction(context: Context) : LinearLayout(context) {
    private val button = ImageButton(context)
    private val caption = TextView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val size = context.dpi(72)
        val radius = size / 2f
        button.background = context.withRipple(context.rounded(context.color(R.color.hv_primary), radius), radius)
        button.scaleType = android.widget.ImageView.ScaleType.CENTER
        button.imageTintList = ColorStateList.valueOf(context.color(R.color.hv_on_primary))
        button.elevation = context.dp(3f)
        addView(button, LayoutParams(size, size))
        caption.setTextColor(context.color(R.color.hv_on_muted))
        caption.textSize = 13f
        caption.gravity = Gravity.CENTER
        caption.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(caption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(8) })
    }

    fun bind(icon: Int, label: String, onClick: () -> Unit) {
        button.setImageResource(icon)
        button.contentDescription = label
        caption.text = label
        button.setOnClickListener { onClick() }
    }
}

/** A 48dp icon-only button with an accessible name. */
fun Context.iconButton(icon: Int, label: String, onClick: () -> Unit) = ImageButton(this).apply {
    setImageResource(icon)
    imageTintList = ColorStateList.valueOf(color(R.color.hv_on_muted))
    val radius = dp(24f)
    background = withRipple(rounded(0x00000000, radius), radius)
    contentDescription = label
    setOnClickListener { onClick() }
    layoutParams = FrameLayout.LayoutParams(dpi(48), dpi(48))
}
