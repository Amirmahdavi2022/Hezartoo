package xyz.amjmc.hezartoo

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Tiny SOCKS5 server on 127.0.0.1 that hev-socks5-tunnel talks to.
 *
 * Each SOCKS CONNECT becomes an HTTP CONNECT to i2pd's local HTTP proxy, which
 * carries it through I2P to the StormyCloud outproxy and out to the internet.
 * hev hands us host names (its mapdns), so names are resolved at the exit, not
 * by the filtered local DNS.
 */
class TunnelBridge(private val engineHost: String, private val enginePort: Int) {

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "bridge").apply { isDaemon = true } }
    private var server: ServerSocket? = null
    @Volatile private var running = false

    val active = AtomicInteger(0)
    val bytesUp = AtomicLong(0)
    val bytesDown = AtomicLong(0)
    val opened = AtomicLong(0)
    val failed = AtomicLong(0)

    /** Starts listening and returns the local port. */
    fun start(): Int {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress("127.0.0.1", 0), 256)
        server = s
        running = true
        pool.execute {
            while (running) {
                val c = try { s.accept() } catch (_: IOException) { break }
                pool.execute { handle(c) }
            }
        }
        return s.localPort
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Throwable) {}
        pool.shutdownNow()
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = 30_000
            val cin = client.getInputStream()
            val cout = client.getOutputStream()

            // greeting: VER NMETHODS METHODS...
            if (cin.read() != 5) return
            val n = cin.read()
            if (n < 0) return
            readFully(cin, n)
            cout.write(byteArrayOf(5, 0)); cout.flush()

            // request: VER CMD RSV ATYP DST.ADDR DST.PORT
            val hdr = readFully(cin, 4)
            val cmd = hdr[1].toInt()
            val host = when (hdr[3].toInt()) {
                1 -> InetAddress.getByAddress(readFully(cin, 4)).hostAddress
                3 -> String(readFully(cin, cin.read()), Charsets.US_ASCII)
                4 -> "[" + (InetAddress.getByAddress(readFully(cin, 16)) as Inet6Address).hostAddress + "]"
                else -> { reply(cout, 8); return }
            }
            val pb = readFully(cin, 2)
            val port = ((pb[0].toInt() and 0xff) shl 8) or (pb[1].toInt() and 0xff)

            if (cmd != 1) { reply(cout, 7); return }            // only CONNECT, no UDP

            val target = "$host:$port"
            // Addresses the exit can never reach (local ranges, Iran's block-page sinkhole,
            // stale fake-DNS answers cached from another VPN): fail at once so the app
            // looks the name up again instead of waiting on I2P for a sure "no".
            if (unreachable(host)) {
                if (skipped.incrementAndGet() <= 10) Status.log("skip $target (unreachable address)")
                reply(cout, 4)
                return
            }
            // the exit refused this port before; don't spend an I2P stream asking again
            if (port in refusedPorts) { reply(cout, 2); return }
            // plain HTTP: the exit only allows CONNECT to 443, so send it as a normal proxy request
            if (port == 80) { httpForward(client, cin, cout, host); return }
            val up = Socket()
            upstream = up
            up.tcpNoDelay = true
            up.connect(InetSocketAddress(engineHost, enginePort), 5_000)
            // I2P is slow to open a stream; the first answer can take a while.
            up.soTimeout = 90_000
            val uout = up.getOutputStream()
            val uin = up.getInputStream()
            uout.write(
                ("CONNECT $target HTTP/1.1\r\n" +
                 "Host: $target\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            uout.flush()

            val status = readStatus(uin)
            if (status !in 200..299) {
                failed.incrementAndGet()
                noteFailure(target, port, status)
                // 403 = the exit's port policy, not a one-off; remember it (443 is never given up)
                if (status == 403 && port != 443 && refusedPorts.add(port)) {
                    Status.log("exit refuses port $port; failing it fast from now on")
                }
                reply(cout, 5)
                return
            }
            reply(cout, 0)
            opened.incrementAndGet()

            client.soTimeout = 0
            up.soTimeout = 0
            active.incrementAndGet()
            try {
                val t = Thread({ pipe(cin, uout, bytesUp, up, client) }, "bridge-up")
                t.isDaemon = true
                t.start()
                pipe(uin, cout, bytesDown, client, up)
                t.join(2_000)
            } finally {
                active.decrementAndGet()
            }
        } catch (_: Throwable) {
            // client gave up, engine dropped, timeout — nothing useful to do
        } finally {
            try { client.close() } catch (_: Throwable) {}
            try { upstream?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Port 80: read the client's request head, rewrite it for a forward proxy
     * (absolute URL, one request per connection) and hand it to i2pd's proxy,
     * which passes it to the exit as an ordinary HTTP request.
     */
    private fun httpForward(client: Socket, cin: InputStream, cout: OutputStream, host: String) {
        reply(cout, 0) // the client only sends its request after this
        val buf = ByteArray(HEAD_LIMIT)
        var len = 0
        var end = -1
        while (end < 0) {
            if (len == buf.size) return // absurd header, drop it
            val n = cin.read(buf, len, buf.size - len)
            if (n < 0) return
            len += n
            end = indexOfHeadEnd(buf, len)
        }
        val head = String(buf, 0, end, Charsets.ISO_8859_1)
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 3) return
        val hostHeader = lines.drop(1).firstOrNull { it.startsWith("host:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() } ?: host
        val uri = if (parts[1].startsWith("/")) "http://$hostHeader${parts[1]}" else parts[1]
        val sb = StringBuilder("${parts[0]} $uri ${parts[2]}\r\n")
        for (l in lines.drop(1)) {
            if (l.isEmpty()) continue
            val name = l.substringBefore(':').trim().lowercase()
            if (name == "connection" || name == "proxy-connection" || name == "keep-alive") continue
            sb.append(l).append("\r\n")
        }
        sb.append("Connection: close\r\n\r\n")

        val up = Socket()
        try {
            up.tcpNoDelay = true
            up.connect(InetSocketAddress(engineHost, enginePort), 5_000)
            val uout = up.getOutputStream()
            uout.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            // body bytes that arrived together with the head
            val rest = end + 4
            if (len > rest) uout.write(buf, rest, len - rest)
            uout.flush()
            opened.incrementAndGet()
            client.soTimeout = 0
            active.incrementAndGet()
            try {
                val t = Thread({ pipe(cin, uout, bytesUp, up, client) }, "bridge-up")
                t.isDaemon = true
                t.start()
                pipe(up.getInputStream(), cout, bytesDown, client, up)
                t.join(2_000)
            } finally {
                active.decrementAndGet()
            }
        } finally {
            try { up.close() } catch (_: Throwable) {}
        }
    }

    private fun indexOfHeadEnd(b: ByteArray, len: Int): Int {
        for (i in 0..len - 4) {
            if (b[i] == 13.toByte() && b[i + 1] == 10.toByte() && b[i + 2] == 13.toByte() && b[i + 3] == 10.toByte()) return i
        }
        return -1
    }

    private fun unreachable(host: String): Boolean {
        if (host.startsWith("[")) {
            val h = host.lowercase()
            return h.startsWith("[2001:4188:2:600:10:10:34:") || h == "[::1]" ||
                h.startsWith("[fc") || h.startsWith("[fd") || h.startsWith("[fe80:")
        }
        val o = host.split('.')
        if (o.size != 4) return false // a name, not an address
        val a = o[0].toIntOrNull() ?: return false
        val b = o[1].toIntOrNull() ?: return false
        return a == 10 || a == 127 || a == 0 ||
            (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) ||
            (a == 169 && b == 254) ||
            (a == 198 && (b == 18 || b == 19)) // benchmark range: only ever fake-DNS answers
    }

    private fun pipe(src: InputStream, dst: OutputStream, counter: AtomicLong, a: Socket, b: Socket) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                dst.flush()
                counter.addAndGet(n.toLong())
            }
        } catch (_: Throwable) {
        } finally {
            // half-close what we can, then let the other side finish
            try { a.shutdownOutput() } catch (_: Throwable) {}
            try { b.shutdownInput() } catch (_: Throwable) {}
        }
    }

    /** Reads the HTTP response head byte by byte so no tunnel bytes are swallowed. */
    private fun readStatus(inp: InputStream): Int {
        val sb = StringBuilder()
        var last4 = 0
        while (sb.length < 8192) {
            val b = inp.read()
            if (b < 0) return -1
            sb.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0d0a0d0a) break
        }
        val first = sb.lineSequence().firstOrNull() ?: return -1
        return first.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
    }

    private fun reply(out: OutputStream, code: Int) {
        try {
            out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()
        } catch (_: Throwable) {}
    }

    private fun readFully(inp: InputStream, len: Int): ByteArray {
        if (len < 0) throw IOException("eof")
        val b = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = inp.read(b, off, len - off)
            if (n < 0) throw IOException("eof")
            off += n
        }
        return b
    }

    private val refusedPorts: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val skipped = AtomicLong(0)

    /** Per-port failure counts, so the report shows which kinds of traffic the exit refuses. */
    private val failedByPort = java.util.concurrent.ConcurrentHashMap<Int, AtomicInteger>()
    private val loggedFailures = AtomicInteger(0)

    private fun noteFailure(target: String, port: Int, status: Int) {
        failedByPort.getOrPut(port) { AtomicInteger(0) }.incrementAndGet()
        // log the first few in full, then stay quiet
        if (loggedFailures.incrementAndGet() <= 40) Status.log("tunnel $target -> proxy said $status")
    }

    companion object {
        private const val HEAD_LIMIT = 64 * 1024
    }

    fun failureSummary(): String =
        failedByPort.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}:${it.value.get()}" }
}
