package xyz.amjmc.hezartoo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import hev.htproxy.TProxyService
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs i2pd and routes the whole phone through it.
 *
 * Lives in its own process (":tunnel"): i2pd can't be cleanly restarted inside
 * one process, so every disconnect ends the process and the next connect gets
 * a fresh one.
 *
 * Order matters: the exit must answer BEFORE the tun comes up, otherwise the
 * user sees "connected" while nothing passes.
 */
class HezartooVpnService : VpnService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var engine: I2pEngine
    private var engineStarted = false
    private var bridge: TunnelBridge? = null
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var cancelled = false
    @Volatile private var finished = false

    private val heartbeat = object : Runnable {
        override fun run() {
            if (finished) return
            Status.beat()
            main.postDelayed(this, 2000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Status.init(this)
        engine = I2pEngine(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown("قطع شد"); return START_NOT_STICKY }
            else -> begin()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // another VPN app took over, or the user switched us off in settings
        shutdown("یک وی‌پی‌ان دیگر جایش را گرفت")
    }

    // ------------------------------------------------------------------ start

    private fun begin() {
        if (worker != null || finished) return
        cancelled = false
        goForeground("در حال روشن شدن…")
        Status.set(Status.Phase.STARTING, "روشن کردن موتور…")
        Status.log("device ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, app ${BuildConfig.VERSION_NAME}")
        main.post(heartbeat)
        worker = Thread({ connect() }, "hezartoo-connect").also { it.start() }
    }

    private fun connect() {
        val t0 = System.currentTimeMillis()
        try {
            // 1) the router
            engine.prepare()
            val firstRun = engine.knownRouters < 25
            if (firstRun) Status.detail("دریافت فهرست اولیه‌ی روترها…")
            val res = engine.start()
            if (res != "ok") return fail("موتور روشن نشد: ${res.take(120)}", null)
            engineStarted = true
            watchNetwork()
            Status.log("engine started in ${since(t0) / 1000}s (first run: $firstRun)")
            if (cancelled) return

            // 2) join I2P and reach the outproxy
            val hint = if (firstRun) "بار اول ممکنه تا ۱۰ دقیقه طول بکشه" else "معمولاً چند دقیقه طول می‌کشه"
            Status.set(Status.Phase.SEARCHING, "ورود به شبکه‌ی I2P… $hint")
            updateNotification("ورود به شبکه…")

            val exitIp = AtomicReference<String?>(null)
            val prober = Thread({
                var tries = 0
                while (!cancelled && exitIp.get() == null && since(t0) < SEARCH_LIMIT_MS) {
                    if (engine.proxyUp()) {
                        tries++
                        val ip = engine.probeExit()
                        if (ip != null) { exitIp.set(ip); break }
                        if (tries <= 3 || tries % 10 == 0) Status.log("exit probe #$tries: ${engine.lastProbeError}")
                    }
                    try { Thread.sleep(6000) } catch (_: InterruptedException) { break }
                }
            }, "hezartoo-probe").apply { isDaemon = true; start() }

            var lastLogged = ""
            while (exitIp.get() == null && since(t0) < SEARCH_LIMIT_MS) {
                if (cancelled) { prober.interrupt(); return }
                val s = engine.stats()
                if (s != null) {
                    val stage = if (s.tunnels > 0) "ساختن مسیر تا خروجی…" else "پیدا کردن گره‌های شبکه…"
                    Status.net(s.routers, s.tunnels, "$stage\n$hint")
                    val key = "${s.routers / 100}/${s.tunnels}/${s.status}"
                    if (key != lastLogged) {
                        Status.log("net ${since(t0) / 1000}s routers=${s.routers} tunnels=${s.tunnels} success=${s.successRate}% status=${s.status}")
                        lastLogged = key
                    }
                } else {
                    Status.detail("روشن کردن موتور…\n$hint")
                }
                Thread.sleep(3000)
            }
            val ip = exitIp.get()
            if (ip == null) {
                prober.interrupt()
                Status.log("engine log tail:\n${engine.logTail()}")
                return fail("به خروجی نرسیدم. اینترنتت رو چک کن و دوباره بزن.", null)
            }
            Status.log("exit reached after ${since(t0) / 1000}s, exit ip $ip")
            if (cancelled) return

            // 3) local bridge, then the tun
            val br = TunnelBridge("127.0.0.1", I2pEngine.PROXY_PORT)
            val socksPort = br.start()
            bridge = br

            val conf = File(filesDir, "tunnel.yml")
            conf.writeText(hevConfig(socksPort))

            val fd = Builder()
                .setSession("Hezartoo")
                .setMtu(MTU)
                .addAddress(TUN_V4, 32)
                .addRoute("0.0.0.0", 0)
                .addAddress(TUN_V6, 128)
                .addRoute("::", 0)
                .addDnsServer(DNS_V4)
                .addDisallowedApplication(packageName) // i2pd itself must not loop into the tun
                .setConfigureIntent(openAppIntent())
                .also { if (Build.VERSION.SDK_INT >= 29) it.setMetered(false) }
                .establish()
                ?: return fail("اجازه‌ی وی‌پی‌ان داده نشده", null)
            tun = fd
            if (!TProxyService.TProxyStartService(conf.absolutePath, fd.fd)) {
                return fail("تونل داخلی بالا نیامد", null)
            }
            // hev exits its thread right away if it rejects the config; give it a moment
            Thread.sleep(400)
            if (!TProxyService.TProxyIsRunning()) {
                return fail("تونل داخلی بلافاصله بسته شد", null)
            }

            Status.set(Status.Phase.ON, "وصلی")
            updateNotification("وصل")
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            fail("خطای غیرمنتظره", t)
        } finally {
            worker = null
        }
    }

    private fun hevConfig(socksPort: Int) = """
        tunnel:
          mtu: $MTU
          ipv4: $TUN_V4
          ipv6: '$TUN_V6'
        socks5:
          port: $socksPort
          address: 127.0.0.1
          udp: 'tcp'
        mapdns:
          address: $DNS_V4
          port: 53
          network: 100.64.0.0
          netmask: 255.192.0.0
          cache-size: 10000
        misc:
          task-stack-size: 81920
          connect-timeout: 95000
          read-write-timeout: 300000
          log-file: stderr
          log-level: warn
    """.trimIndent() + "\n"

    private fun watchNetwork() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { engine.networkChanged(true) }
                override fun onLost(network: Network) {
                    if (cm.activeNetwork == null) engine.networkChanged(false)
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (t: Throwable) {
            Status.log("network watch: ${t.message}")
        }
    }

    // ------------------------------------------------------------------- stop

    private fun fail(msg: String, t: Throwable?) {
        if (t != null) Status.log("ERROR $msg: ${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString().take(2000)}")
        teardown()
        Status.set(Status.Phase.ERROR, msg)
        end()
    }

    private fun shutdown(msg: String) {
        if (finished) return
        Status.set(Status.Phase.STOPPING, "در حال قطع…")
        Thread({
            teardown()
            Status.set(Status.Phase.OFF, msg)
            end()
        }, "hezartoo-stop").start()
    }

    @Synchronized
    private fun teardown() {
        cancelled = true
        worker?.interrupt()
        try { if (tun != null) TProxyService.TProxyStopService() } catch (t: Throwable) { Status.log("hev stop: ${t.message}") }
        try { tun?.close() } catch (_: Throwable) {}
        tun = null
        bridge?.let {
            Status.log("session: opened=${it.opened.get()} failed=${it.failed.get()} skipped=${it.skipped.get()} up=${it.bytesUp.get() / 1024}KB down=${it.bytesDown.get() / 1024}KB failed-by-port=[${it.failureSummary()}]")
            it.stop()
        }
        bridge = null
        netCallback?.let {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Throwable) {}
        }
        netCallback = null
        if (engineStarted) {
            // i2pd's stop can take a while; the process ends right after anyway
            val t = Thread({ engine.stop() }, "engine-stop").apply { isDaemon = true; start() }
            t.join(4000)
            engineStarted = false
        }
    }

    /** Leaves foreground, stops the service, and ends this process so the next connect is fresh. */
    private fun end() {
        finished = true
        main.post {
            main.removeCallbacks(heartbeat)
            stopForegroundCompat()
            stopSelf()
            main.postDelayed({ Process.killProcess(Process.myPid()) }, 300)
        }
    }

    // ------------------------------------------------------------ notification

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "اتصال", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) = main.post {
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text)) } catch (_: Throwable) {}
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HezartooVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("هزارتو")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(null, "قطع", stop).build())
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
    }

    // ---------------------------------------------------------------- helpers

    private fun since(t: Long) = System.currentTimeMillis() - t

    companion object {
        const val ACTION_START = "xyz.amjmc.hezartoo.START"
        const val ACTION_STOP = "xyz.amjmc.hezartoo.STOP"
        private const val CHANNEL = "conn"
        private const val NOTIF_ID = 7
        private const val MTU = 8500
        private const val TUN_V4 = "198.18.0.1"
        private const val TUN_V6 = "fc00::1"
        private const val DNS_V4 = "198.18.0.2"
        private const val SEARCH_LIMIT_MS = 15 * 60_000L

        fun start(ctx: Context) {
            val i = Intent(ctx, HezartooVpnService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, HezartooVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
