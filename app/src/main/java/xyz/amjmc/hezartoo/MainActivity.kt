package xyz.amjmc.hezartoo

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val easeOut = PathInterpolator(0.23f, 1f, 0.32f, 1f)

    private lateinit var regular: Typeface
    private lateinit var bold: Typeface

    private lateinit var maze: MazeView
    private lateinit var stateChip: TextView
    private lateinit var stateDot: View
    private lateinit var detailText: TextView
    private lateinit var routersVal: TextView
    private lateinit var tunnelsVal: TextView
    private lateinit var timeVal: TextView
    private lateinit var timeLabel: TextView
    private lateinit var action: TextView
    private lateinit var crashCard: LinearLayout
    private var lastPhase: Status.Phase? = null

    // the tunnel runs in another process, so the screen polls its state file
    private val tick = object : Runnable {
        override fun run() {
            if (Status.refresh()) render() else renderTimer()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Status.init(this)
        regular = resources.getFont(R.font.vazir_regular)
        bold = resources.getFont(R.font.vazir_bold)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BG))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // draw behind the bars on every version, not only Android 15
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#15173A"), Color.parseColor("#0D0E22"), BG)
            )
        }
        // Android 15 draws apps edge to edge: keep content clear of the system bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars())
                       else null
            val top = bars?.top ?: insets.systemWindowInsetTop
            val bottom = bars?.bottom ?: insets.systemWindowInsetBottom
            v.setPadding(dp(24), top + dp(20), dp(24), bottom + dp(12))
            insets
        }

        // ---- header: the name, set like a title page, and a small state chip
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("هزارتو", 30f, CREAM, bold = true))
            addView(text("راهی از دل شبکه‌ی I2P", 13f, MUTED).apply { setPadding(0, dp(4), 0, 0) })
        }
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        stateDot = View(this).apply { background = oval(MUTED) }
        stateChip = text("", 13f, CREAM).apply { setPadding(dp(8), 0, 0, 0) }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.parseColor("#14163A"), dp(20).toFloat(), LINE)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            addView(stateDot, LinearLayout.LayoutParams(dp(7), dp(7)))
            addView(stateChip)
        })
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ---- crash card (only after an unexpected exit)
        crashCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#2A1820"), dp(18).toFloat(), Color.parseColor("#4A2A33"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = View.GONE
            addView(text("دفعه‌ی قبل برنامه یهو بسته شد", 14f, Color.parseColor("#F6D2CD"), bold = true))
            addView(text("گزارشش رو کپی کن و بفرست تا درستش کنم.", 13f, Color.parseColor("#D9AFA9")).apply {
                setPadding(0, dp(2), 0, 0)
            })
            addView(link("کپی گزارش خرابی", Color.parseColor("#F6D2CD")) { copyCrash() },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        root.addView(crashCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })

        // ---- the labyrinth
        maze = MazeView(this)
        root.addView(maze, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        detailText = text("", 14f, MUTED).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0); minLines = 2
        }
        root.addView(detailText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ---- live numbers
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Color.parseColor("#11132E"), dp(18).toFloat(), LINE)
            setPadding(0, dp(12), 0, dp(12))
        }
        routersVal = statValue(); tunnelsVal = statValue(); timeVal = statValue()
        timeLabel = statLabel("زمان")
        strip.addView(stat(statLabel("گره‌ها"), routersVal), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        strip.addView(divider(), LinearLayout.LayoutParams(dp(1), MATCH_PARENT))
        strip.addView(stat(statLabel("تونل‌ها"), tunnelsVal), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        strip.addView(divider(), LinearLayout.LayoutParams(dp(1), MATCH_PARENT))
        strip.addView(stat(timeLabel, timeVal), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(strip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })

        // ---- the one action, low on the screen where the thumb is
        action = text("", 16f, INK, bold = true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { toggle() }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(120).setInterpolator(easeOut).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(easeOut).start()
                }
                false
            }
        }
        root.addView(action, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(12) })

        // ---- quiet links
        val links = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
            addView(link("کپی گزارش") { copyLog() })
            addView(text("·", 13f, Color.parseColor("#3A3E62")).apply { setPadding(dp(4), 0, dp(4), 0) })
            addView(link("کانال تلگرام") {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CHANNEL))) } catch (_: Throwable) {}
            })
            addView(text("·", 13f, Color.parseColor("#3A3E62")).apply { setPadding(dp(4), 0, dp(4), 0) })
            addView(text("نسخه ${BuildConfig.VERSION_NAME}", 12f, Color.parseColor("#4C5070")).apply {
                setPadding(dp(8), dp(10), dp(8), dp(10))
            })
        }
        root.addView(links, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
        enter(listOf(header, maze, detailText, strip, action, links))

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onStart() {
        super.onStart()
        Status.refresh()
        render()
        crashCard.visibility = if (crashFile().exists()) View.VISIBLE else View.GONE
        main.post(tick)
    }

    override fun onStop() {
        main.removeCallbacks(tick)
        super.onStop()
    }

    // ------------------------------------------------------------------ actions

    private fun toggle() {
        when (Status.phase) {
            Status.Phase.OFF, Status.Phase.ERROR -> {
                val ask = VpnService.prepare(this)
                if (ask != null) startActivityForResult(ask, REQ_VPN) else HezartooVpnService.start(this)
            }
            Status.Phase.STOPPING -> {}
            else -> HezartooVpnService.stop(this)
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) HezartooVpnService.start(this)
            else toast("بدون اجازه‌ی وی‌پی‌ان نمی‌تونم وصل کنم")
        }
    }

    private fun copyLog() {
        val crash = crashFile().takeIf { it.exists() }?.readText()?.let { "\n\n=== last crash ===\n$it" } ?: ""
        val engineLog = try {
            File(filesDir, "i2pd/i2pd.log").takeIf { it.exists() }?.readLines()?.takeLast(80)?.joinToString("\n")
        } catch (_: Throwable) { null }
        val tail = if (engineLog.isNullOrBlank()) "" else "\n\n=== i2pd ===\n$engineLog"
        copy("hezartoo-log", "Hezartoo ${BuildConfig.VERSION_NAME}\n" + Status.fullReport() + crash + tail)
        toast("گزارش کپی شد")
    }

    private fun copyCrash() {
        val f = crashFile()
        copy("hezartoo-crash", (f.takeIf { it.exists() }?.readText() ?: "") + "\n\n=== log ===\n" + Status.fullReport())
        f.delete()
        crashCard.animate().alpha(0f).translationY(-dp(6).toFloat()).setDuration(180).setInterpolator(easeOut)
            .withEndAction { crashCard.visibility = View.GONE; crashCard.alpha = 1f; crashCard.translationY = 0f }
            .start()
        toast("گزارش خرابی کپی شد")
    }

    // ------------------------------------------------------------------- render

    private fun render() {
        val p = Status.phase
        maze.setPhase(p)
        if (p == Status.Phase.STARTING) maze.setTarget(0.05f)
        if (p == Status.Phase.SEARCHING) {
            // how far the light walks follows the real network numbers
            val r = if (Status.routers < 0) 0f else (Status.routers / 150f).coerceAtMost(1f)
            val t = if (Status.tunnels < 0) 0f else (Status.tunnels / 4f).coerceAtMost(1f)
            maze.setTarget(0.08f + 0.42f * r + 0.38f * t)
        }

        val chip = when (p) {
            Status.Phase.OFF -> "بیرون"
            Status.Phase.STARTING, Status.Phase.SEARCHING -> "در مسیر"
            Status.Phase.ON -> "رسیدی"
            Status.Phase.STOPPING -> "برگشت…"
            Status.Phase.ERROR -> "گیر کرد"
        }
        val dotColor = when (p) {
            Status.Phase.ON, Status.Phase.STARTING, Status.Phase.SEARCHING -> MazeView.AMBER
            Status.Phase.ERROR -> MazeView.ROSE
            else -> MUTED
        }
        val detail = when (p) {
            Status.Phase.OFF ->
                if (Status.detail.isBlank() || Status.detail == "قطع شد") "دکمه رو بزن تا راه رو از دل شبکه پیدا کنم"
                else Status.detail
            Status.Phase.ON -> "همه‌ی اپ‌ها از دل هزارتو رد میشن"
            else -> Status.detail
        }
        val changed = p != lastPhase && lastPhase != null
        if (changed) { swapText(stateChip, chip); swapText(detailText, detail) }
        else { stateChip.text = chip; if (detailText.text != detail) detailText.text = detail }
        stateDot.background = oval(dotColor)

        // the button
        val (label, fill, fg, stroke) = when (p) {
            Status.Phase.OFF -> Quad("ورود به هزارتو", MazeView.AMBER, INK, null)
            Status.Phase.ERROR -> Quad("دوباره امتحان کن", MazeView.AMBER, INK, null)
            Status.Phase.STARTING, Status.Phase.SEARCHING -> Quad("لغو", Color.TRANSPARENT, MazeView.AMBER, MazeView.AMBER)
            Status.Phase.ON -> Quad("خروج از هزارتو", Color.parseColor("#1A1C40"), CREAM, LINE)
            Status.Phase.STOPPING -> Quad("در حال قطع…", Color.parseColor("#1A1C40"), MUTED, LINE)
        }
        if (changed) swapText(action, label) else action.text = label
        action.setTextColor(fg)
        action.background = rounded(fill, dp(18).toFloat(), stroke)
        action.isEnabled = p != Status.Phase.STOPPING

        routersVal.text = num(Status.routers)
        tunnelsVal.text = num(Status.tunnels)
        lastPhase = p
        renderTimer()
    }

    private data class Quad(val a: String, val b: Int, val c: Int, val d: Int?)

    private fun num(v: Int) = if (v < 0) "—" else v.toString()

    /** Short fade + lift so text changes don't jump. */
    private fun swapText(v: TextView, s: String) {
        if (v.text == s) return
        v.animate().cancel()
        v.alpha = 0f
        v.translationY = dp(4).toFloat()
        v.text = s
        v.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(easeOut).start()
    }

    private fun renderTimer() {
        val p = Status.phase
        val since = when {
            p == Status.Phase.ON && Status.startedAt > 0 -> Status.startedAt
            (p == Status.Phase.STARTING || p == Status.Phase.SEARCHING) && Status.beganAt > 0 -> Status.beganAt
            else -> 0L
        }
        timeLabel.text = if (p == Status.Phase.ON) "مدت اتصال" else "زمان"
        timeVal.text = if (since == 0L) "—" else {
            val s = (System.currentTimeMillis() - since) / 1000
            if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
            else String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
        }
    }

    /** Staggered fade-up on first open. */
    private fun enter(views: List<View>) {
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = dp(10).toFloat()
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(60L * i).setDuration(320).setInterpolator(easeOut).start()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun crashFile() = File(filesDir, HezartooApp.CRASH_FILE)

    private fun copy(label: String, s: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(label, s))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        typeface = if (bold) this@MainActivity.bold else regular
        includeFontPadding = false
        setLineSpacing(0f, 1.15f)
    }

    private fun link(label: String, color: Int = MUTED, onClick: () -> Unit) = text(label, 13f, color).apply {
        setPadding(dp(8), dp(10), dp(8), dp(10))
        isClickable = true
        setOnClickListener { onClick() }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().alpha(0.55f).setDuration(100).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().alpha(1f).setDuration(160).start()
            }
            false
        }
    }

    private fun statValue() = text("—", 18f, CREAM, bold = true).apply { gravity = Gravity.CENTER }
    private fun statLabel(s: String) = text(s, 12f, MUTED).apply { gravity = Gravity.CENTER }

    private fun stat(label: TextView, value: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(value, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(label, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
    }

    private fun divider() = View(this).apply { setBackgroundColor(LINE) }

    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun rounded(color: Int, r: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = r
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_VPN = 1
        private val BG = Color.parseColor("#090A18")
        private val CREAM = Color.parseColor("#F4E9D8")
        private val INK = Color.parseColor("#1C1305")
        private val MUTED = Color.parseColor("#8A8DAA")
        private val LINE = Color.parseColor("#262A52")
        private const val CHANNEL = "https://t.me/parsv2r"
    }
}
