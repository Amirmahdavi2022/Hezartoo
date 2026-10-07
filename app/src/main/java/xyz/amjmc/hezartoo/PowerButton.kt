package xyz.amjmc.hezartoo

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/**
 * The one big round button. Colour follows the connection phase:
 * a soft glow behind it, a lit disc, a ring, and a spinning arc while it works.
 */
class PowerButton(ctx: Context) : View(ctx) {

    private val easeOut = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    private val d = resources.displayMetrics.density

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; strokeCap = Paint.Cap.ROUND
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5.5f * d; strokeCap = Paint.Cap.ROUND
    }
    private val box = RectF()

    private var fillColor = OFF_FILL
    private var accent = OFF_ACCENT
    private var glow = 0f          // 0..1, how strong the halo is
    private var colorAnim: ValueAnimator? = null

    private var spinning = false
    private var angle = 0f
    private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    }

    init {
        isClickable = true
        contentDescription = "اتصال"
    }

    fun setPhase(p: Status.Phase) {
        val target = when (p) {
            Status.Phase.ON -> Triple(ON_FILL, ON_ACCENT, 1f)
            Status.Phase.STARTING, Status.Phase.SEARCHING, Status.Phase.STOPPING -> Triple(WAIT_FILL, WAIT_ACCENT, 0.45f)
            Status.Phase.ERROR -> Triple(ERR_FILL, ERR_ACCENT, 0.35f)
            Status.Phase.OFF -> Triple(OFF_FILL, OFF_ACCENT, 0f)
        }
        animateTo(target.first, target.second, target.third)
        val wantSpin = p == Status.Phase.STARTING || p == Status.Phase.SEARCHING || p == Status.Phase.STOPPING
        if (wantSpin && !spinning) { spinning = true; spin.start() }
        if (!wantSpin && spinning) { spinning = false; spin.cancel(); invalidate() }
    }

    private fun animateTo(toFill: Int, toAccent: Int, toGlow: Float) {
        if (toFill == fillColor && toAccent == accent && toGlow == glow) return
        colorAnim?.cancel()
        val f0 = fillColor; val a0 = accent; val g0 = glow
        val ev = ArgbEvaluator()
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = easeOut
            addUpdateListener {
                val t = it.animatedFraction
                fillColor = ev.evaluate(t, f0, toFill) as Int
                accent = ev.evaluate(t, a0, toAccent) as Int
                glow = g0 + (toGlow - g0) * t
                invalidate()
            }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                animate().scaleX(0.97f).scaleY(0.97f).setDuration(140).setInterpolator(easeOut).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(easeOut).start()
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val outer = minOf(width, height) / 2f
        val r = outer * 0.66f

        // halo
        if (glow > 0.01f) {
            glowPaint.shader = RadialGradient(
                cx, cy, outer,
                intArrayOf(withAlpha(accent, (70 * glow).toInt()), withAlpha(accent, (22 * glow).toInt()), Color.TRANSPARENT),
                floatArrayOf(0.55f, 0.78f, 1f), Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, outer, glowPaint)
        }

        // disc, slightly lit from the top
        discPaint.shader = RadialGradient(
            cx, cy - r * 0.45f, r * 1.5f,
            intArrayOf(lighten(fillColor, 0.10f), fillColor), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, discPaint)

        ring.color = withAlpha(accent, 70)
        c.drawCircle(cx, cy, r, ring)

        // progress track + arc just outside the disc
        val tr = r + 10 * d
        box.set(cx - tr, cy - tr, cx + tr, cy + tr)
        if (spinning) {
            track.color = withAlpha(accent, 30)
            c.drawCircle(cx, cy, tr, track)
            arc.color = accent
            c.drawArc(box, angle - 90f, 64f, false, arc)
        }

        // power glyph
        glyph.color = accent
        val g = r * 0.30f
        box.set(cx - g, cy - g, cx + g, cy + g)
        c.drawArc(box, -55f, 290f, false, glyph)
        c.drawLine(cx, cy - g * 1.28f, cx, cy - g * 0.22f, glyph)
    }

    override fun onDetachedFromWindow() {
        spin.cancel(); colorAnim?.cancel()
        super.onDetachedFromWindow()
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    private fun lighten(c: Int, f: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * f).toInt()
    )

    companion object {
        val OFF_FILL = Color.parseColor("#1A1F26")
        val OFF_ACCENT = Color.parseColor("#7D8894")
        val WAIT_FILL = Color.parseColor("#261F12")
        val WAIT_ACCENT = Color.parseColor("#F2B84B")
        val ON_FILL = Color.parseColor("#0F2A20")
        val ON_ACCENT = Color.parseColor("#3DDC97")
        val ERR_FILL = Color.parseColor("#2C1617")
        val ERR_ACCENT = Color.parseColor("#EF6F64")
    }
}
