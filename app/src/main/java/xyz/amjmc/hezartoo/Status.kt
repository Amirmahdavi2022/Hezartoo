package xyz.amjmc.hezartoo

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Connection state shared between the screen and the VPN service.
 *
 * The service runs in its own process (":tunnel") so every connect starts a
 * fresh i2pd, so the two sides talk through a small file: the service writes
 * it, the screen reads it twice a second. A heartbeat tells the screen when the
 * service process has died without saying goodbye.
 */
object Status {

    enum class Phase { OFF, STARTING, SEARCHING, ON, STOPPING, ERROR }

    @Volatile var phase: Phase = Phase.OFF
        private set
    /** Short Persian line shown under the button. */
    @Volatile var detail: String = ""
        private set
    @Volatile var startedAt: Long = 0L
        private set
    /** 0..1 progress while searching, or -1 when unknown. */
    @Volatile var progress: Float = -1f
        private set

    private var dir: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        if (dir == null) dir = ctx.filesDir
    }

    private fun stateFile() = File(dir, "state.json")
    private fun logFile() = File(dir, "hezartoo.log")

    // ------------------------------------------------------------ service side

    fun set(p: Phase, d: String, prog: Float = -1f) {
        val wasOn = phase == Phase.ON
        phase = p
        detail = d
        progress = prog
        if (p == Phase.ON && !wasOn) startedAt = System.currentTimeMillis()
        if (p != Phase.ON) startedAt = 0L
        log("[$p] $d")
        write()
    }

    /** Updates the line under the button without logging it (used for live counters). */
    fun detail(d: String, prog: Float = -1f) {
        detail = d
        progress = prog
        write()
    }

    /** Called every couple of seconds by the service so the screen knows it's alive. */
    fun beat() = write()

    private fun write() {
        val f = dir ?: return
        try {
            val tmp = File(f, "state.json.tmp")
            tmp.writeText(
                JSONObject()
                    .put("phase", phase.name)
                    .put("detail", detail)
                    .put("startedAt", startedAt)
                    .put("progress", progress.toDouble())
                    .put("beat", System.currentTimeMillis())
                    .toString()
            )
            tmp.renameTo(stateFile())
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------- screen side

    /** Re-reads the file. Returns true if anything visible changed. */
    fun refresh(): Boolean {
        val before = listOf(phase, detail, startedAt, progress)
        try {
            val f = stateFile()
            if (!f.exists()) {
                phase = Phase.OFF; detail = ""; startedAt = 0L; progress = -1f
            } else {
                val j = JSONObject(f.readText())
                var p = Phase.valueOf(j.optString("phase", "OFF"))
                var d = j.optString("detail", "")
                val beat = j.optLong("beat", 0L)
                val alive = System.currentTimeMillis() - beat < STALE_MS
                if (!alive && p != Phase.OFF && p != Phase.ERROR) {
                    // the tunnel process is gone (killed by the system, crashed, or force-stopped)
                    p = Phase.OFF; d = "اتصال قطع شد"
                }
                phase = p
                detail = d
                startedAt = if (p == Phase.ON) j.optLong("startedAt", 0L) else 0L
                progress = j.optDouble("progress", -1.0).toFloat()
            }
        } catch (_: Throwable) {}
        return before != listOf(phase, detail, startedAt, progress)
    }

    /** Marks the state as off from the screen side (before the service has written anything). */
    fun reset() {
        phase = Phase.OFF; detail = ""; startedAt = 0L; progress = -1f
        try { stateFile().delete() } catch (_: Throwable) {}
    }

    // --------------------------------------------------------------------- log

    fun log(msg: String) {
        val line = "${fmt.format(Date())} $msg"
        try {
            val f = logFile()
            if (f.length() > 512 * 1024) f.writeText("")
            f.appendText(line + "\n")
        } catch (_: Throwable) {}
    }

    fun fullReport(): String = try {
        logFile().takeIf { it.exists() }?.readText()?.takeLast(60_000) ?: ""
    } catch (_: Throwable) { "" }

    private const val STALE_MS = 8_000L
}
