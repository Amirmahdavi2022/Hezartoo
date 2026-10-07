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
import android.widget.FrameLayout
import android.widget.ImageView
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

    private lateinit var power: PowerButton
    private lateinit var phaseText: TextView
    private lateinit var detailText: TextView
    private lateinit var timerChip: TextView
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
                intArrayOf(Color.parseColor("#101A1E"), BG, BG)
            )
        }
        // Android 15 draws apps edge to edge: keep content clear of the system bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars())
                       else null
            val top = bars?.top ?: insets.systemWindowInsetTop
            val bottom = bars?.bottom ?: insets.systemWindowInsetBottom
            v.setPadding(dp(22), top + dp(18), dp(22), bottom + dp(14))
            insets
        }

        // ---- header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.brand_mark)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        header.addView(FrameLayout(this).apply {
            background = rounded(Color.parseColor("#17202A"), dp(14).toFloat())
            clipToOutline = true
            addView(logo, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            addView(text("هزارتو", 22f, Color.WHITE, bold = true))
            addView(text("بدون سرور، از راه شبکه‌ی I2P", 13f, MUTED))
        }
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ---- crash card (only after an unexpected exit)
        crashCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.parseColor("#2A1A1B"), dp(18).toFloat(), Color.parseColor("#4A2A2B"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = View.GONE
            addView(text("دفعه‌ی قبل برنامه یهو بسته شد", 14f, Color.parseColor("#F6D2CD"), bold = true))
            addView(text("گزارشش رو کپی کن و بفرست تا درستش کنم.", 13f, Color.parseColor("#D9AFA9")).apply {
                setPadding(0, dp(2), 0, 0)
            })
            addView(pill("کپی گزارش خرابی", Color.parseColor("#3A2425")) { copyCrash() },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        root.addView(crashCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) })

        // ---- centre
        val centre = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        power = PowerButton(this).apply { setOnClickListener { toggle() } }
        centre.addView(power, LinearLayout.LayoutParams(dp(280), dp(280)))
        phaseText = text("", 24f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
        detailText = text("", 14f, MUTED).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(4), dp(16), 0)
        }
        timerChip = text("", 14f, Color.parseColor("#BFF3DA")).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#12261E"), dp(16).toFloat(), Color.parseColor("#1E4434"))
            setPadding(dp(14), dp(5), dp(14), dp(5))
            letterSpacing = 0.06f
            visibility = View.INVISIBLE
        }
        centre.addView(phaseText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = -dp(6) })
        centre.addView(detailText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        centre.addView(timerChip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        root.addView(FrameLayout(this).apply {
            addView(centre, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // ---- bottom
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(pill("کپی گزارش") { copyLog() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        row.addView(pill("کانال تلگرام") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CHANNEL))) } catch (_: Throwable) {}
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val version = text("نسخه ${BuildConfig.VERSION_NAME}", 11f, Color.parseColor("#4E5864")).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0)
        }
        root.addView(version, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
        enter(listOf(header, centre, row, version))

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
        power.setPhase(p)
        val title = when (p) {
            Status.Phase.OFF -> "خاموش"
            Status.Phase.STARTING -> "در حال روشن شدن…"
            Status.Phase.SEARCHING -> "در حال اتصال…"
            Status.Phase.ON -> "وصلی"
            Status.Phase.STOPPING -> "در حال قطع…"
            Status.Phase.ERROR -> "وصل نشد"
        }
        val detail = when (p) {
            Status.Phase.OFF -> if (Status.detail.isBlank() || Status.detail == "قطع شد") "برای وصل شدن دکمه رو بزن" else Status.detail
            Status.Phase.ON -> "همه‌ی اپ‌ها از هزارتو رد میشن"
            else -> Status.detail
        }
        phaseText.setTextColor(
            when (p) {
                Status.Phase.ON -> Color.parseColor("#E9FFF5")
                Status.Phase.ERROR -> Color.parseColor("#FFD9D5")
                else -> Color.WHITE
            }
        )
        if (p != lastPhase && lastPhase != null) {
            swapText(phaseText, title)
            swapText(detailText, detail)
        } else {
            phaseText.text = title
            detailText.text = detail
        }
        lastPhase = p
        renderTimer()
    }

    /** Short fade + lift so the status line changes don't jump. */
    private fun swapText(v: TextView, s: String) {
        if (v.text == s) return
        v.animate().cancel()
        v.alpha = 0f
        v.translationY = dp(4).toFloat()
        v.text = s
        v.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(easeOut).start()
    }

    private fun renderTimer() {
        val t = Status.startedAt
        val on = Status.phase == Status.Phase.ON && t > 0
        if (on) {
            val s = (System.currentTimeMillis() - t) / 1000
            timerChip.text = String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        }
        val want = if (on) View.VISIBLE else View.INVISIBLE
        if (timerChip.visibility != want) {
            if (on) {
                timerChip.alpha = 0f; timerChip.scaleX = 0.95f; timerChip.scaleY = 0.95f
                timerChip.visibility = View.VISIBLE
                timerChip.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(easeOut).start()
            } else {
                timerChip.animate().alpha(0f).setDuration(150).setInterpolator(easeOut)
                    .withEndAction { timerChip.visibility = View.INVISIBLE }.start()
            }
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

    private fun pill(label: String, bg: Int = Color.parseColor("#161C23"), onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        typeface = regular
        includeFontPadding = false
        setTextColor(Color.parseColor("#D7DEE6"))
        gravity = Gravity.CENTER
        background = rounded(bg, dp(16).toFloat(), Color.parseColor("#232B35"))
        setPadding(dp(16), dp(13), dp(16), dp(13))
        isClickable = true
        setOnClickListener { onClick() }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(140).setInterpolator(easeOut).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(easeOut).start()
            }
            false
        }
    }

    private fun rounded(color: Int, r: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = r
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_VPN = 1
        private val BG = Color.parseColor("#0C1015")
        private val MUTED = Color.parseColor("#8A949F")
        private const val CHANNEL = "https://t.me/parsv2r"
    }
}
