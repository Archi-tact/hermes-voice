package com.architact.hermesvoice.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The voice orb: a soft gradient sphere whose motion tells the user what Hermes is doing.
 * Idle breathes slowly, Listening follows the microphone level, Thinking swirls, Speaking
 * pulses, Attention (approval) turns warm amber, Error turns a muted rose.
 * With system animations turned off it renders a still orb.
 */
class OrbView(context: Context) : View(context) {
    enum class Mode { Idle, Listening, Thinking, Speaking, Attention, Error }

    var mode: Mode = Mode.Idle
        set(value) {
            if (field == value) return
            field = value
            fromColors = currentColors()
            toColors = palette(value)
            blendStart = now()
            invalidate()
        }

    /** Microphone level 0..1 while listening; smoothed before drawing. */
    var level: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f) }

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val evaluator = ArgbEvaluator()

    private var fromColors = palette(Mode.Idle)
    private var toColors = fromColors
    private var blendStart = 0L
    private var smoothLevel = 0f

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO // the status text announces state
    }

    private fun now() = System.nanoTime() / 1_000_000L

    private fun palette(mode: Mode): IntArray = when (mode) {
        Mode.Idle -> intArrayOf(0xFF8FB3E6.toInt(), 0xFFB9A8EE.toInt())
        Mode.Listening -> intArrayOf(0xFF7CCBC4.toInt(), 0xFF8FB3E6.toInt())
        Mode.Thinking -> intArrayOf(0xFF8E9BD6.toInt(), 0xFFC7A9E6.toInt())
        Mode.Speaking -> intArrayOf(0xFF8FB3E6.toInt(), 0xFF9FD6C6.toInt())
        Mode.Attention -> intArrayOf(0xFFF0C987.toInt(), 0xFFE7A285.toInt())
        Mode.Error -> intArrayOf(0xFFD39A9A.toInt(), 0xFFB9A8EE.toInt())
    }

    private fun currentColors(): IntArray {
        val t = ((now() - blendStart) / BLEND_MS.toFloat()).coerceIn(0f, 1f)
        return IntArray(2) { evaluator.evaluate(t, fromColors[it], toColors[it]) as Int }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) { if (!ticker.isStarted) ticker.start() } else ticker.cancel()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) * 0.30f
        if (base <= 0f) return
        val seconds = now() / 1000f
        val animated = ValueAnimator.areAnimatorsEnabled()
        smoothLevel += (level - smoothLevel) * 0.25f

        val scale = if (!animated) 1f else when (mode) {
            Mode.Idle -> 1f + 0.035f * sin(seconds * 2f * PI.toFloat() / 4f)
            Mode.Listening -> 1f + 0.03f * sin(seconds * 2f * PI.toFloat() / 2f) + 0.16f * smoothLevel
            Mode.Thinking -> 1f + 0.02f * sin(seconds * 2f * PI.toFloat() / 3f)
            Mode.Speaking -> 1f + 0.06f * abs(sin(seconds * PI.toFloat() * 1.6f)) * (0.6f + 0.4f * abs(sin(seconds * 0.7f)))
            Mode.Attention -> 1f + 0.05f * sin(seconds * 2f * PI.toFloat() / 1.6f)
            Mode.Error -> 1f
        }
        val radius = base * scale
        val (c1, c2) = currentColors().let { it[0] to it[1] }

        // Soft halo behind the orb.
        val glowRadius = radius * 1.75f
        glowPaint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(withAlpha(c1, 0x66), withAlpha(c2, 0x22), Color.TRANSPARENT),
            floatArrayOf(0.45f, 0.75f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        // Listening ripples grow with the voice.
        if (mode == Mode.Listening && animated) {
            for (i in 0..1) {
                val phase = ((seconds / 1.8f) + i * 0.5f) % 1f
                ringPaint.strokeWidth = base * 0.03f
                ringPaint.color = withAlpha(c1, ((1f - phase) * (60 + 120 * smoothLevel)).toInt().coerceIn(0, 255))
                canvas.drawCircle(cx, cy, radius * (1.05f + phase * (0.35f + 0.3f * smoothLevel)), ringPaint)
            }
        }

        // The orb; the gradient slowly turns while Hermes is thinking.
        val angle = if (mode == Mode.Thinking && animated) seconds * 1.4f else (PI / 4).toFloat()
        val dx = cos(angle) * radius
        val dy = sin(angle) * radius
        orbPaint.shader = LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy, c1, c2, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, orbPaint)

        // Gentle highlight for depth.
        shinePaint.shader = RadialGradient(
            cx - radius * 0.35f, cy - radius * 0.4f, radius * 0.9f,
            intArrayOf(0x73FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, shinePaint)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private companion object {
        const val BLEND_MS = 450L
    }
}
