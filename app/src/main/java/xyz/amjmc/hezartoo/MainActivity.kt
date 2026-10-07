package xyz.amjmc.hezartoo

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import network.loki.lokinet.LokinetDaemon
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Probe screen: start the Lokinet engine, show what it sees, and test whether
 * traffic really leaves through an exit. Deliberately shows the raw log.
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val easeOut = PathInterpolator(0.23f, 1f, 0.32f, 1f)

    private lateinit var regular: Typeface
    private lateinit var bold: Typeface

    private lateinit var button: TextView
    private lateinit var stateText: TextView
    private lateinit var nodesVal: TextView
    private lateinit var peersVal: TextView
    private lateinit var pathsVal: TextView
    private lateinit var exitField: EditText
    private lateinit var testText: TextView
    private lateinit var logText: TextView

    private var polling = false
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        regular = resources.getFont(R.font.vazir_regular)
        bold = resources.getFont(R.font.vazir_bold)
        window.setBackgroundDrawable(ColorDrawable(BG))
        window.statusBarColor = BG
        window.navigationBarColor = BG
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        polling = true
        main.post(poll)
    }

    override fun onPause() {
        super.onPause()
        polling = false
        main.removeCallbacks(poll)
    }

    // ---------- UI ----------

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.brand_mark)
                clipToOutline = true
                background = round(CARD, dp(14).toFloat())
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                addView(text("هزارتو", 22f, TEXT, bold))
                addView(text("نسخه آزمایشی · شبکه Lokinet", 13f, MUTED, regular))
            })
        })

        button = text("اتصال", 18f, BG, bold).apply {
            gravity = Gravity.CENTER
            background = gradient()
            setOnClickListener { toggle() }
            pressable(this)
        }
        root.addView(button, lp(MATCH_PARENT, dp(58), top = 26))

        stateText = text("", 14f, MUTED, regular).apply { gravity = Gravity.CENTER }
        root.addView(stateText, lp(MATCH_PARENT, WRAP_CONTENT, top = 12))

        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nodesVal = stat(stats, "نود شناخته‌شده")
        peersVal = stat(stats, "اتصال مستقیم")
        pathsVal = stat(stats, "مسیر آماده")
        root.addView(stats, lp(MATCH_PARENT, WRAP_CONTENT, top = 18))

        root.addView(text("نود خروجی", 13f, MUTED, regular), lp(MATCH_PARENT, WRAP_CONTENT, top = 18))
        exitField = EditText(this).apply {
            setText(prefs().getString("exit", "exit.loki"))
            setTextColor(TEXT)
            textSize = 15f
            typeface = Typeface.MONOSPACE
            textDirection = View.TEXT_DIRECTION_LTR
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = round(CARD, dp(12).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isSingleLine = true
        }
        root.addView(exitField, lp(MATCH_PARENT, WRAP_CONTENT, top = 6))

        val test = text("تست خروجی (IP و کشور)", 15f, TEXT, bold).apply {
            gravity = Gravity.CENTER
            background = round(CARD, dp(12).toFloat())
            setOnClickListener { runTest() }
            pressable(this)
        }
        root.addView(test, lp(MATCH_PARENT, dp(50), top = 14))
        testText = text("", 13f, MUTED, regular).apply { textDirection = View.TEXT_DIRECTION_LTR }
        root.addView(testText, lp(MATCH_PARENT, WRAP_CONTENT, top = 8))

        val logHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("لاگ موتور", 13f, MUTED, regular), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(text("کپی", 13f, ACCENT, bold).apply {
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { copyLog() }
            })
        }
        root.addView(logHead, lp(MATCH_PARENT, WRAP_CONTENT, top = 18))
        logText = TextView(this).apply {
            setTextColor(0xFF9AA7B4.toInt())
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            textDirection = View.TEXT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTextIsSelectable(true)
            background = round(CARD, dp(12).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(logText, lp(MATCH_PARENT, WRAP_CONTENT, top = 6))

        root.addView(text("کانال: @parsv2r", 12f, MUTED, regular).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/parsv2r"))) }
            }
        }, lp(MATCH_PARENT, WRAP_CONTENT, top = 18))

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }

    private fun stat(row: LinearLayout, label: String): TextView {
        val v = text("–", 20f, TEXT, bold).apply { gravity = Gravity.CENTER }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = round(CARD, dp(12).toFloat())
            setPadding(dp(6), dp(12), dp(6), dp(12))
            addView(v)
            addView(text(label, 11f, MUTED, regular).apply { gravity = Gravity.CENTER })
        }
        val p = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        p.marginStart = dp(4); p.marginEnd = dp(4)
        row.addView(box, p)
        return v
    }

    // ---------- actions ----------

    private fun toggle() {
        val s = LokinetDaemon.state
        if (s == "running" || s == "starting" || s == "configuring") {
            startService(Intent(this, LokinetDaemon::class.java).setAction(LokinetDaemon.ACTION_STOP))
            return
        }
        val ask = VpnService.prepare(this)
        if (ask != null) startActivityForResult(ask, 1) else start()
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) start()
    }

    private fun start() {
        val exit = exitField.text.toString().trim().ifEmpty { "exit.loki" }
        prefs().edit().putString("exit", exit).apply()
        testText.text = ""
        startForegroundService(Intent(this, LokinetDaemon::class.java).putExtra(LokinetDaemon.EXTRA_EXIT, exit))
    }

    private fun runTest() {
        testText.setTextColor(MUTED)
        testText.text = "در حال تست…"
        bg.execute {
            val t0 = System.currentTimeMillis()
            val out = try {
                val c = URL("https://ipwho.is/").openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "Hezartoo")
                val body = c.inputStream.bufferedReader().readText()
                val j = JSONObject(body)
                val ms = System.currentTimeMillis() - t0
                "OK ${ms}ms\nIP: ${j.optString("ip")}\n${j.optString("country")} · ${j.optString("city")}\n" +
                    j.optJSONObject("connection")?.optString("org").orEmpty()
            } catch (e: Exception) {
                "FAILED after ${System.currentTimeMillis() - t0}ms: $e"
            }
            main.post {
                testText.setTextColor(if (out.startsWith("OK")) ACCENT else ERROR)
                testText.text = out
            }
        }
    }

    private fun copyLog() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = "state=${LokinetDaemon.state} err=${LokinetDaemon.lastError}\n" +
            "${nodesVal.text}/${peersVal.text}/${pathsVal.text}\n${testText.text}\n\n" + tail(400)
        cm.setPrimaryClip(ClipData.newPlainText("hezartoo log", text))
        Toast.makeText(this, "کپی شد", Toast.LENGTH_SHORT).show()
    }

    // ---------- status ----------

    private fun refresh() {
        val s = LokinetDaemon.state
        val running = s == "running"
        val busy = running || s == "starting" || s == "configuring"
        button.text = if (busy) "قطع" else "اتصال"
        stateText.setTextColor(if (s == "failed") ERROR else MUTED)
        val secs = if (busy) (System.currentTimeMillis() - LokinetDaemon.startedAt) / 1000 else 0
        stateText.text = when (s) {
            "idle" -> "خاموش"
            "starting", "configuring" -> "در حال راه‌اندازی موتور… ${secs}s"
            "running" -> "موتور روشن است · ${secs}s"
            "stopping" -> "در حال خاموش شدن…"
            "failed" -> "خطا: ${LokinetDaemon.lastError}"
            else -> s
        }
        val d = LokinetDaemon.instance
        bg.execute {
            val stats = if (running && d != null) runCatching { parse(d.DumpStatus()) }.getOrNull() else null
            val log = tail(60)
            main.post {
                nodesVal.text = stats?.get(0)?.toString() ?: "–"
                peersVal.text = stats?.get(1)?.toString() ?: "–"
                pathsVal.text = stats?.get(2)?.toString() ?: "–"
                if (logText.text.toString() != log) logText.text = log.ifEmpty { "(هنوز لاگی نیست)" }
            }
        }
    }

    /** [nodes known, established sessions, ready paths] */
    private fun parse(json: String): IntArray {
        if (json.isBlank()) return intArrayOf(0, 0, 0)
        val o = JSONObject(json)
        var sessions = 0
        o.optJSONArray("links")?.let { types ->
            for (i in 0 until types.length()) {
                val links = types.optJSONArray(i) ?: continue
                for (k in 0 until links.length()) {
                    val l = links.optJSONObject(k) ?: continue
                    sessions += l.optJSONObject("sessions")?.optJSONArray("established")?.length() ?: 0
                }
            }
        }
        return intArrayOf(o.optInt("numNodesKnown"), sessions, countReady(o))
    }

    private fun countReady(v: Any?): Int = when (v) {
        is JSONObject -> {
            var n = if (v.optBoolean("ready", false) && v.has("hops")) 1 else 0
            for (k in v.keys()) n += countReady(v.opt(k))
            n
        }
        is JSONArray -> (0 until v.length()).sumOf { countReady(v.opt(it)) }
        else -> 0
    }

    private fun tail(lines: Int): String {
        val f = LokinetDaemon.logFile(filesDir)
        if (!f.exists()) return ""
        return runCatching {
            RandomAccessFile(f, "r").use { r ->
                val len = r.length()
                val from = maxOf(0L, len - 24_000L)
                r.seek(from)
                val buf = ByteArray((len - from).toInt())
                r.readFully(buf)
                String(buf).lines().takeLast(lines).joinToString("\n")
            }
        }.getOrDefault("")
    }

    // ---------- helpers ----------

    private fun prefs() = getSharedPreferences("p", MODE_PRIVATE)

    private fun text(s: String, size: Float, color: Int, tf: Typeface) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); typeface = tf
    }

    private fun round(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

    private fun gradient() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF5CF2D6.toInt(), 0xFF2E8BFF.toInt())
    ).apply { cornerRadius = dp(16).toFloat() }

    /** subtle press feedback: scale to 0.97 on press, ease-out back */
    private fun pressable(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.97f).scaleY(0.97f)
                    .setDuration(120).setInterpolator(easeOut).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f)
                    .setDuration(160).setInterpolator(easeOut).start()
            }
            false
        }
    }

    private fun lp(w: Int, h: Int, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        val BG = 0xFF05070A.toInt()
        val CARD = 0xFF10151C.toInt()
        val TEXT = 0xFFE8EEF4.toInt()
        val MUTED = 0xFF7C8896.toInt()
        val ACCENT = 0xFF5CF2D6.toInt()
        val ERROR = 0xFFFF6B6B.toInt()
    }
}
