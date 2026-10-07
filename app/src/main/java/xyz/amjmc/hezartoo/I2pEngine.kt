package xyz.amjmc.hezartoo

import android.content.Context
import org.purplei2p.i2pd.I2PD_JNI
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * The I2P router (i2pd) running inside the app, set up the way we tested by hand:
 * its HTTP proxy sends everything outside I2P to the StormyCloud outproxy.
 *
 * Ports are deliberately not i2pd's defaults (4444 / 7070) so Hezartoo doesn't
 * clash with the official i2pd app if both are installed.
 */
class I2pEngine(private val ctx: Context) {

    val dataDir = File(ctx.filesDir, "i2pd")
    /** Set by prepare() when this start reseeds from the bundled file. */
    var seedFile: File? = null
        private set
    /** Routers already saved from earlier runs; few means a first (slow) start. */
    var knownRouters = 0
        private set
    private val logFile = File(dataDir, "i2pd.log")

    /** Copies the reseed certificates and writes our config. Safe to call every start. */
    fun prepare() {
        dataDir.mkdirs()
        val stamp = File(dataDir, "assets.version")
        val version = BuildConfig.VERSION_NAME
        if (!stamp.exists() || stamp.readText() != version) {
            File(dataDir, "certificates").deleteRecursively()
            copyAssetDir("i2pd/certificates", File(dataDir, "certificates"))
            // the addressbook is only a head start; i2pd keeps its own copy updated
            if (!File(dataDir, "addressbook").exists()) {
                copyAssetDir("i2pd/addressbook", File(dataDir, "addressbook"))
            }
            stamp.writeText(version)
        }
        val certs = File(dataDir, "certificates/reseed").listFiles()?.size ?: 0
        Status.log("engine data: reseed certs=$certs, netDb=${File(dataDir, "netDb").exists()}")

        // First start (or a netDb too small to use): reseed from the bundle shipped in the
        // APK instead of reseed servers. Only once: if that didn't get us going, the next
        // start falls back to the servers.
        val known = File(dataDir, "netDb").walkTopDown().count { it.isFile && it.name.endsWith(".dat") }
        knownRouters = known
        val tries = File(dataDir, "seed.tries")
        val usedBefore = tries.exists()
        seedFile = null
        if (known < 25 && !usedBefore) {
            val f = File(dataDir, "seed.su3")
            try {
                ctx.assets.open("i2pd/seed.su3").use { inp -> f.outputStream().use { inp.copyTo(it) } }
                seedFile = f
                tries.writeText("1")
            } catch (t: Throwable) {
                Status.log("no bundled seed: ${t.message}")
            }
        }
        Status.log("netDb routers on disk: $known, reseed from ${if (seedFile != null) "bundle" else "servers (if needed)"}")

        File(dataDir, "i2pd.conf").writeText(config())
        File(dataDir, "tunnels.conf").writeText("# no extra tunnels\n")
        // the native start() waits up to 10 s for this file
        File(dataDir, "assets.ready").writeText(version)
        logFile.delete()
    }

    /** Blocking. Returns "ok" or i2pd's error text. */
    fun start(): String {
        I2PD_JNI.loadLibraries()
        Status.log("engine abi: ${I2PD_JNI.getABICompiledWith()}")
        I2PD_JNI.setDataDir(dataDir.absolutePath)
        I2PD_JNI.setLanguage("english")
        return I2PD_JNI.startDaemon()
    }

    fun stop() {
        try { I2PD_JNI.stopDaemon() } catch (t: Throwable) { Status.log("engine stop: ${t.message}") }
    }

    fun proxyUp(): Boolean = try { I2PD_JNI.getHTTPProxyState() } catch (_: Throwable) { false }

    fun networkChanged(online: Boolean) {
        try { I2PD_JNI.onNetworkStateChanged(online) } catch (_: Throwable) {}
    }

    data class Stats(val routers: Int, val tunnels: Int, val successRate: Int, val status: String)

    /** Reads the numbers off i2pd's own web console page. Null if it isn't up yet. */
    fun stats(): Stats? = try {
        val c = URL("http://127.0.0.1:$CONSOLE_PORT/").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        c.connectTimeout = 2000; c.readTimeout = 3000
        val html = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val text = html.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ")
        fun num(label: String) = Regex("$label:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val status = Regex("Network status:\\s*([A-Za-z -]+?)\\s+(?:Network status v6|Tunnel creation)").find(text)
            ?.groupValues?.get(1)?.trim() ?: ""
        Stats(num("Routers"), num("Client Tunnels"), num("Tunnel creation success rate"), status)
    } catch (_: Throwable) { null }

    /**
     * The real test: fetch a page over HTTPS through i2pd's proxy, which means
     * I2P tunnels + the outproxy + the open internet all work. Returns the exit
     * IP, or null.
     */
    fun probeExit(): String? = try {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", PROXY_PORT))
        val c = URL("https://api.ipify.org").openConnection(proxy) as HttpsURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        val ip = c.inputStream.bufferedReader().use { it.readText() }.trim()
        c.disconnect()
        ip.takeIf { it.length in 7..45 && it.none { ch -> ch == '<' } }
    } catch (t: Throwable) {
        lastProbeError = "${t.javaClass.simpleName}: ${t.message}"
        null
    }

    @Volatile var lastProbeError: String = ""
        private set

    /** Last lines of i2pd's own log, for the copied report. */
    fun logTail(): String = try {
        logFile.takeIf { it.exists() }?.readLines()?.takeLast(60)?.joinToString("\n") ?: ""
    } catch (_: Throwable) { "" }

    private fun config() = """
        # written by Hezartoo on every start
        log = file
        logfile = ${logFile.absolutePath}
        loglevel = warn
        ipv4 = true
        ipv6 = false
        bandwidth = L
        notransit = false

        [ntcp2]
        enabled = true

        [ssu2]
        enabled = true
        published = true

        [http]
        enabled = true
        address = 127.0.0.1
        port = $CONSOLE_PORT
        auth = false

        [httpproxy]
        enabled = true
        address = 127.0.0.1
        port = $PROXY_PORT
        outproxy = $OUTPROXY
        addresshelper = false
        inbound.length = 1
        inbound.quantity = 5
        outbound.length = 1
        outbound.quantity = 5
        signaturetype = 7
        i2cp.leaseSetType = 3
        i2cp.leaseSetEncType = 0,4
        keys = proxy-keys.dat

        [socksproxy]
        enabled = false

        [sam]
        enabled = false

        [bob]
        enabled = false

        [i2cp]
        enabled = false

        [i2pcontrol]
        enabled = false

        [upnp]
        enabled = false

        [precomputation]
        elgamal = false

        [reseed]
        verify = true
        ${seedFile?.let { "file = " + it.absolutePath } ?: ""}

        [limits]
        transittunnels = 50

        [persist]
        profiles = false
    """.trimIndent() + "\n"

    private fun copyAssetDir(path: String, dest: File) {
        val list = ctx.assets.list(path) ?: return
        if (list.isEmpty()) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(path).use { inp -> dest.outputStream().use { inp.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (name in list) copyAssetDir("$path/$name", File(dest, name))
    }

    companion object {
        const val PROXY_PORT = 14444
        const val CONSOLE_PORT = 17070
        /** StormyCloud's outproxy, by its b32 address so no addressbook lookup is needed. */
        const val OUTPROXY = "http://5d4s7pcvfdpftfk7npc7hllyujhufsdprtrf4o53i44rgsa2xbwa.b32.i2p"
    }
}
