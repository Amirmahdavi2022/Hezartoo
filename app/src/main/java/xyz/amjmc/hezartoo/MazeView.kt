package xyz.amjmc.hezartoo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * The labyrinth on the main screen.
 *
 * A fixed maze (same seed every time, so it reads as the app's own mark) with
 * the shortest route from the gate at the bottom to the chamber in the middle.
 * While connecting, a lit line walks that route. How far it walks follows the
 * real numbers from i2pd (routers known, tunnels built), fed in through
 * [setTarget]; it reaches the centre only when the exit actually answers.
 */
class MazeView(ctx: Context) : View(ctx) {

    private val d = resources.displayMetrics.density

    // grid: odd size so the centre cell is a real cell
    private val n = 9
    private val walls = Array(n) { Array(n) { BooleanArray(4) { true } } } // N E S W
    private val route = ArrayList<IntArray>()

    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val wallPath = Path()
    private val routePath = Path()
    private val partPath = Path()
    private val measure = PathMeasure()
    private var routeLen = 0f
    private var cell = 0f
    private var ox = 0f
    private var oy = 0f

    private var phase = Status.Phase.OFF
    private var target = 0f      // where the light should be (0..1 along the route)
    private var shown = 0f       // where it is drawn now
    private var tint = AMBER
    private var lastFrame = 0L
    private var t = 0f           // seconds, for the gentle pulse

    private val motion = ValueAnimator.areAnimatorsEnabled()

    init {
        carve()
        solve()
        contentDescription = "هزارتو"
    }

    // ------------------------------------------------------------------ state

    fun setPhase(p: Status.Phase) {
        if (p == phase) return
        phase = p
        when (p) {
            Status.Phase.ON -> { target = 1f; tint = AMBER }
            Status.Phase.ERROR -> tint = ROSE
            Status.Phase.OFF, Status.Phase.STOPPING -> { target = 0f; tint = AMBER }
            else -> tint = AMBER
        }
        if (!motion) shown = target
        kick()
    }

    /** 0..1, from the live network numbers. Never moves backwards while connecting. */
    fun setTarget(v: Float) {
        if (phase != Status.Phase.STARTING && phase != Status.Phase.SEARCHING) return
        val c = v.coerceIn(0f, 0.92f)
        if (c > target) { target = c; if (!motion) shown = c; kick() }
    }

    private fun kick() {
        lastFrame = 0L
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------- maze

    /** Depth-first carve with a fixed seed. */
    private fun carve() {
        var seed = 0x4E5A7L
        fun rnd(k: Int): Int {
            seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
            return ((seed ushr 8) % k).toInt()
        }
        val seen = Array(n) { BooleanArray(n) }
        val stack = ArrayDeque<IntArray>()
        stack.push(intArrayOf(n / 2, n - 1)); seen[n / 2][n - 1] = true
        val dx = intArrayOf(0, 1, 0, -1); val dy = intArrayOf(-1, 0, 1, 0)
        while (stack.isNotEmpty()) {
            val (x, y) = stack.peek().let { it[0] to it[1] }
            val opts = (0..3).filter {
                val nx = x + dx[it]; val ny = y + dy[it]
                nx in 0 until n && ny in 0 until n && !seen[nx][ny]
            }
            if (opts.isEmpty()) { stack.pop(); continue }
            val dir = opts[rnd(opts.size)]
            val nx = x + dx[dir]; val ny = y + dy[dir]
            walls[x][y][dir] = false
            walls[nx][ny][(dir + 2) % 4] = false
            seen[nx][ny] = true
            stack.push(intArrayOf(nx, ny))
        }
        // the gate: open the bottom wall of the start cell
        walls[n / 2][n - 1][2] = false
    }

    /** Breadth-first route from the gate to the centre. */
    private fun solve() {
        val prev = Array(n) { arrayOfNulls<IntArray>(n) }
        val seen = Array(n) { BooleanArray(n) }
        val q = ArrayDeque<IntArray>()
        val s = intArrayOf(n / 2, n - 1); val goal = n / 2
        q.add(s); seen[s[0]][s[1]] = true
        val dx = intArrayOf(0, 1, 0, -1); val dy = intArrayOf(-1, 0, 1, 0)
        while (q.isNotEmpty()) {
            val c = q.poll()!!
            if (c[0] == goal && c[1] == goal) break
            for (dir in 0..3) {
                if (walls[c[0]][c[1]][dir]) continue
                val nx = c[0] + dx[dir]; val ny = c[1] + dy[dir]
                if (nx !in 0 until n || ny !in 0 until n || seen[nx][ny]) continue
                seen[nx][ny] = true; prev[nx][ny] = c; q.add(intArrayOf(nx, ny))
            }
        }
        var c: IntArray? = intArrayOf(goal, goal)
        while (c != null) { route.add(0, c); c = prev[c[0]][c[1]] }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val side = min(w, h) - 24f * d
        cell = side / n
        ox = (w - side) / 2f
        oy = (h - side) / 2f

        wallPath.reset()
        for (x in 0 until n) for (y in 0 until n) {
            val l = ox + x * cell; val tp = oy + y * cell; val r = l + cell; val b = tp + cell
            if (walls[x][y][0]) { wallPath.moveTo(l, tp); wallPath.lineTo(r, tp) }
            if (walls[x][y][3]) { wallPath.moveTo(l, tp); wallPath.lineTo(l, b) }
            if (x == n - 1 && walls[x][y][1]) { wallPath.moveTo(r, tp); wallPath.lineTo(r, b) }
            if (y == n - 1 && walls[x][y][2]) { wallPath.moveTo(l, b); wallPath.lineTo(r, b) }
        }

        routePath.reset()
        // start just outside the gate
        val g = route.first()
        routePath.moveTo(cx(g[0]), oy + n * cell + cell * 0.45f)
        for (c in route) routePath.lineTo(cx(c[0]), cy(c[1]))
        measure.setPath(routePath, false)
        routeLen = measure.length

        wallPaint.strokeWidth = 1.6f * d
        linePaint.strokeWidth = 3f * d
        glowPaint.strokeWidth = 10f * d
    }

    /** Soft light without blur filters (those force software drawing every frame). */
    private fun halo(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int, a: Float) {
        haloPaint.shader = RadialGradient(
            x, y, radius, intArrayOf(withAlpha(color, a), withAlpha(color, 0f)), null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, radius, haloPaint)
    }

    private fun cx(x: Int) = ox + (x + 0.5f) * cell
    private fun cy(y: Int) = oy + (y + 0.5f) * cell

    // ------------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1e9f).coerceAtMost(0.05f)
        lastFrame = now
        t += dt

        // ease the light toward its target: quick to follow, never a jump
        val gap = target - shown
        shown += gap * min(1f, dt * if (gap < 0) 6f else 2.6f)
        if (abs(target - shown) < 0.0008f) shown = target

        val on = phase == Status.Phase.ON
        val busy = phase == Status.Phase.STARTING || phase == Status.Phase.SEARCHING

        // walls: dim at rest, warmer once connected
        wallPaint.color = if (on) withAlpha(AMBER, 0.32f) else withAlpha(WALL, 1f)
        canvas.drawPath(wallPath, wallPaint)

        // the centre chamber
        val c = n / 2
        val mx = cx(c); val my = cy(c)
        val pulse = if (on && motion) 0.5f + 0.5f * sin(t * 1.8f) else 1f
        if (on) {
            halo(canvas, mx, my, cell * 1.1f, AMBER, 0.30f + 0.15f * pulse)
        }
        corePaint.color = if (on) AMBER else withAlpha(WALL_HI, 1f)
        val r = cell * 0.17f
        canvas.save(); canvas.rotate(45f, mx, my)
        canvas.drawRect(mx - r, my - r, mx + r, my + r, corePaint)
        canvas.restore()

        // the lit route
        if (shown > 0.001f && routeLen > 0f) {
            partPath.reset()
            measure.getSegment(0f, routeLen * shown, partPath, true)
            glowPaint.color = withAlpha(tint, 0.16f)
            glowPaint.strokeWidth = 12f * d
            canvas.drawPath(partPath, glowPaint)
            glowPaint.color = withAlpha(tint, 0.26f)
            glowPaint.strokeWidth = 7f * d
            canvas.drawPath(partPath, glowPaint)
            linePaint.color = tint
            canvas.drawPath(partPath, linePaint)

            if (!on) {
                val pos = FloatArray(2)
                measure.getPosTan(routeLen * shown, pos, null)
                val flick = if (busy && motion) 0.75f + 0.25f * sin(t * 6f) else 1f
                halo(canvas, pos[0], pos[1], 14f * d, tint, 0.7f * flick)
                dotPaint.color = withAlpha(Color.WHITE, 0.92f)
                canvas.drawCircle(pos[0], pos[1], 3.2f * d, dotPaint)
            }
        } else {
            // a resting spark at the gate
            val g = route.first()
            dotPaint.color = withAlpha(AMBER, 0.75f)
            canvas.drawCircle(cx(g[0]), oy + n * cell + cell * 0.45f, 3f * d, dotPaint)
        }

        val moving = shown != target || (motion && (busy || on))
        if (moving) postInvalidateOnAnimation()
    }

    private fun withAlpha(c: Int, a: Float) =
        Color.argb((Color.alpha(c) * a).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    companion object {
        val AMBER = Color.parseColor("#F2B45A")
        val ROSE = Color.parseColor("#E8786B")
        val WALL = Color.parseColor("#2A2F4A")
        val WALL_HI = Color.parseColor("#4A5070")
    }
}
