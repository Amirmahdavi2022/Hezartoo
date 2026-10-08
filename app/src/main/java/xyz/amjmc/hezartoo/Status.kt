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
    /** Live i2pd numbers while connecting, -1 when unknown. */
    @Volatile var routers: Int = -1
        private set
    @Volatile var tunnels: Int = -1
        private set
    /** When this connect attempt began (0 when idle). */
    @Volatile var beganAt: Long = 0L
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
        if (p == Phase.STARTING) { beganAt = System.currentTimeMillis(); routers = -1; tunnels = -1 }
        if (p == Phase.OFF || p == Phase.ERROR) { beganAt = 0L; routers = -1; tunnels = -1 }
        log("[$p] $d")
        write()
    }

    /** Updates the line under the button without logging it (used for live counters). */
    fun detail(d: String, prog: Float = -1f) {
        detail = d
        progress = prog
        write()
    }

    /** Live network numbers plus the line under the maze. */
    fun net(r: Int, t: Int, d: String) {
        routers = r; tunnels = t; detail = d
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
                    .put("routers", routers)
                    .put("tunnels", tunnels)
                    .put("beganAt", beganAt)
                    .put("beat", System.currentTimeMillis())
                    .toString()
            )
            tmp.renameTo(stateFile())
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------- screen side

    /** Re-reads the file. Returns true if anything visible changed. */
    fun refresh(): Boolean {
        val before = listOf(phase, detail, startedAt, progress, routers, tunnels)
        try {
            val f = stateFile()
            if (!f.exists()) {
                phase = Phase.OFF; detail = ""; startedAt = 0L; progress = -1f
                routers = -1; tunnels = -1; beganAt = 0L
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
                val busy = p == Phase.STARTING || p == Phase.SEARCHING
                routers = if (busy || p == Phase.ON) j.optInt("routers", -1) else -1
                tunnels = if (busy || p == Phase.ON) j.optInt("tunnels", -1) else -1
                beganAt = if (busy) j.optLong("beganAt", 0L) else 0L
            }
        } catch (_: Throwable) {}
        return before != listOf(phase, detail, startedAt, progress, routers, tunnels)
    }

    /** Marks the state as off from the screen side (before the service has written anything). */
    fun reset() {
        phase = Phase.OFF; detail = ""; startedAt = 0L; progress = -1f
        routers = -1; tunnels = -1; beganAt = 0L
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
