package xyz.amjmc.hezartoo

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import java.io.File

/**
 * Records why the app died last time, so "it just closed" turns into something
 * we can actually read: Java crashes via the uncaught handler, and native
 * crashes / system kills via ApplicationExitInfo (Android 11+).
 */
class HezartooApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Status.init(this)

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, CRASH_FILE).writeText(
                    "Java crash in thread ${t.name}\n${e.stackTraceToString().take(6000)}"
                )
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }

        if (Build.VERSION.SDK_INT >= 30) recordLastExit()
    }

    private fun recordLastExit() {
        try {
            val am = getSystemService(ActivityManager::class.java)
            val info = am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull() ?: return
            val seen = File(filesDir, "last_exit_seen")
            val stamp = info.timestamp.toString()
            if (seen.exists() && seen.readText() == stamp) return
            seen.writeText(stamp)

            val unexpected = info.reason in setOf(
                ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                // not REASON_SIGNALED: the tunnel process ends itself that way on every disconnect
            )
            if (!unexpected) return

            val sb = StringBuilder()
            sb.append("Last exit (${info.processName}): reason=${reasonName(info.reason)} status=${info.status} ")
            sb.append("importance=${info.importance} desc=${info.description}\n")
            if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && Build.VERSION.SDK_INT >= 31) {
                // first part of the tombstone: signal, abort message and the backtrace
                info.traceInputStream?.use { s ->
                    val txt = s.readBytes().toString(Charsets.ISO_8859_1)
                    sb.append(txt.lineSequence().filter { it.isNotBlank() }
                        .filter { l -> l.contains("signal") || l.contains("Abort") || l.trimStart().startsWith("#") }
                        .take(40).joinToString("\n"))
                }
            }
            val f = File(filesDir, CRASH_FILE)
            if (!f.exists()) f.writeText(sb.toString()) else f.appendText("\n" + sb)
        } catch (_: Throwable) {}
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "java crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init failure"
        else -> r.toString()
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
