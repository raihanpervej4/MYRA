package com.myra.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class OrbAnimationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State { IDLE, LISTENING, SPEAKING, THINKING, ACTIVE }

    private var state = State.IDLE
    private var amplitude = 0f
    private var pulse = 1f
    private var glowAlpha = 120
    private var rotationAngle = 0f
    private var waveOffset = 0f
    private var thinkAngle = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val wavePath = Path()
    private val rect = RectF()

    private val animators = mutableListOf<ValueAnimator>()

    private fun colors(): Pair<Int, Int> = when (state) {
        State.IDLE -> Color.parseColor("#B71C1C") to Color.parseColor("#880E4F")
        State.LISTENING, State.ACTIVE -> Color.parseColor("#FF1744") to Color.parseColor("#D500F9")
        State.SPEAKING -> Color.parseColor("#E040FB") to Color.parseColor("#FF1744")
        State.THINKING -> Color.parseColor("#40C4FF") to Color.parseColor("#00B0FF")
    }

    fun setState(s: State) { state = s; invalidate() }
    fun setAmplitude(a: Float) { amplitude = a.coerceIn(0f, 1f); invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animators += anim(0f, 1f, 1500, repeatReverse = true) {
            pulse = 1f + 0.15f * it; glowAlpha = (120 + 100 * it).toInt(); invalidate()
        }
        animators += anim(0f, 360f, 8000) { rotationAngle = it; invalidate() }
        animators += anim(0f, (2 * PI).toFloat(), 2000) { waveOffset = it; invalidate() }
        animators += anim(0f, 360f, 900) { thinkAngle = it; invalidate() }
    }

    override fun onDetachedFromWindow() {
        animators.forEach { it.cancel() }
        animators.clear()
        super.onDetachedFromWindow()
    }

    private fun anim(from: Float, to: Float, dur: Long, repeatReverse: Boolean = false, onUpdate: (Float) -> Unit) =
        ValueAnimator.ofFloat(from, to).apply {
            duration = dur
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = if (repeatReverse) ValueAnimator.REVERSE else ValueAnimator.RESTART
            addUpdateListener { onUpdate(it.animatedValue as Float) }
            start()
        }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val active = state != State.IDLE
        val scaleAmp = if (state == State.SPEAKING || state == State.LISTENING) amplitude * 0.12f else 0f
        val r = min(width, height) / 2f * 0.42f * (if (state == State.IDLE) pulse else 1.05f + scaleAmp)
        val (c1, c2) = colors()

        // 1. glow
        paint.shader = RadialGradient(cx, cy, r * 1.6f, intArrayOf(withAlpha(c1, glowAlpha), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 1.6f, paint)

        // 2. core
        paint.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.3f, c1, c2, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        // 3. rotating dashed rings
        if (active) {
            ringPaint.strokeWidth = 3f
            for (k in 0 until 3) {
                ringPaint.color = withAlpha(c1, 190 - k * 40)
                val rr = r * (1.15f + k * 0.15f)
                rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
                val dir = if (k % 2 == 0) 1f else -1f
                for (seg in 0 until 4) {
                    canvas.drawArc(rect, rotationAngle * dir * (1 + k * 0.4f) + seg * 90f, 50f, false, ringPaint)
                }
            }
        }

        // 4. wave rings
        val amp = if (active) 3f + amplitude * 14f else 2f
        ringPaint.strokeWidth = 2f
        ringPaint.color = withAlpha(c2, if (active) 150 else 70)
        wavePath.reset()
        var a = 0
        while (a <= 360) {
            val rad = a * PI / 180.0
            val rr = r * 1.05f + (sin(rad * 6 + waveOffset) * amp).toFloat()
            val x = cx + rr * cos(rad).toFloat()
            val y = cy + rr * sin(rad).toFloat()
            if (a == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
            a += 4
        }
        wavePath.close()
        canvas.drawPath(wavePath, ringPaint)

        // 5. thinking arcs
        if (state == State.THINKING) {
            ringPaint.strokeWidth = 6f
            ringPaint.color = withAlpha(c1, 230)
            val rr = r * 1.25f
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            canvas.drawArc(rect, thinkAngle, 90f, false, ringPaint)
            canvas.drawArc(rect, thinkAngle + 180f, 90f, false, ringPaint)
        }

        // 6. particles
        if (state == State.ACTIVE || state == State.SPEAKING || state == State.LISTENING) {
            paint.color = withAlpha(c1, 220)
            for (i in 0 until 12) {
                val ang = (rotationAngle * 1.5f + i * 30f) * PI / 180.0
                val dist = r * 1.55f + (sin(waveOffset + i.toDouble()) * amplitude * 10).toFloat()
                canvas.drawCircle(cx + dist * cos(ang).toFloat(), cy + dist * sin(ang).toFloat(), 3f + amplitude * 3f, paint)
            }
        }

        // 7. highlight
        paint.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.35f, r * 0.5f, Color.argb(110, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx - r * 0.35f, cy - r * 0.35f, r * 0.5f, paint)
        paint.shader = null
    }
}
