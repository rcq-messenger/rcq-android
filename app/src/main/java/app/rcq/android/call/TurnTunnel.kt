package app.rcq.android.call

import app.rcq.android.net.SingBoxTransport
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Carries call media over the same tunnel that carries the messages.
 *
 * ⚠⚠ The problem this exists for: the obfuscated connection is applied to this
 * app's OkHttp clients, and WebRTC does not use them. It opens its own sockets.
 * So on a network that blocks RCQ, someone turns on обход, their chats start
 * working, and their calls stay dead — the voice goes out beside the tunnel,
 * into the same block the tunnel exists to get around. No setting could fix it
 * and nothing in the app said so.
 *
 * libwebrtc's Java API takes no proxy, but it does take any address we like and
 * speaks TURN over TCP. So the tunnel is put where it will be used: a listener
 * on loopback that WebRTC dials as if it were the relay next door, forwarding
 * every byte through sing-box's SOCKS inbound to the real one. WebRTC sees a
 * TURN server; the network sees the same tunnel it already lets through.
 *
 * ★ Plain `turn:` over TCP on 3478, deliberately, not `turns:` on 443. The
 * tunnel already encrypts and obfuscates what it carries, and the media inside
 * is SRTP either way; a second TLS layer would buy nothing and would break the
 * transparent byte-for-byte forwarding this depends on.
 */
object TurnTunnel {

    private const val TURN_TCP_PORT = 3478
    /** Enough for both ends of a call plus the reachability probe, with room to
     *  spare; each is a short-lived pair of pump threads. */
    private const val MAX_CONNECTIONS = 8

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "rcq-turn-tunnel").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)

    @Volatile private var server: ServerSocket? = null
    @Volatile private var upstreamHost: String? = null

    /** `turn:` URL to hand WebRTC, or null when the tunnel is not carrying
     *  calls (transport off, no TURN host known yet, or the listener failed). */
    @Volatile private var url: String? = null

    /** The URL to use INSTEAD of the island's, or null to use the island's.
     *
     *  Null is the common case and the right default: with no transport in the
     *  way, going straight to the relay is faster and cheaper for everyone. */
    fun activeUrl(): String? = if (SingBoxTransport.isActive) url else null

    /** Point the tunnel at the relay the island handed out, and make sure a
     *  listener is up. Cheap to call repeatedly — it only acts on a change. */
    @Synchronized
    fun ensureRunning(turnHost: String?) {
        if (!SingBoxTransport.isActive || turnHost.isNullOrBlank()) {
            stop()
            return
        }
        if (running.get() && upstreamHost == turnHost && server?.isClosed == false) return
        stop()
        upstreamHost = turnHost
        val srv = runCatching {
            // Loopback only. Nothing outside this device may use us as an open
            // relay into the tunnel.
            ServerSocket(0, MAX_CONNECTIONS, InetAddress.getByName("127.0.0.1"))
        }.getOrElse {
            android.util.Log.e("RCQturn", "tunnel listener failed", it)
            return
        }
        server = srv
        url = "turn:127.0.0.1:${srv.localPort}?transport=tcp"
        running.set(true)
        android.util.Log.i("RCQturn", "tunnelling calls: ${srv.localPort} -> $turnHost:$TURN_TCP_PORT")
        pool.execute { acceptLoop(srv) }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        url = null
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(srv: ServerSocket) {
        while (running.get() && !srv.isClosed) {
            val client = runCatching { srv.accept() }.getOrElse { return }
            android.util.Log.i("RCQturn", "accepted from ${client.inetAddress}")
            pool.execute { bridge(client) }
        }
    }

    private fun bridge(client: Socket) {
        val host = upstreamHost
        val proxy = SingBoxTransport.proxy()
        if (host == null || proxy == null) {
            runCatching { client.close() }
            return
        }
        var upstream: Socket? = null
        try {
            // Through sing-box, not around it — the whole point.
            upstream = Socket(proxy).apply {
                soTimeout = 0
                tcpNoDelay = true          // TURN carries latency-sensitive media
                connect(InetSocketAddress(host, TURN_TCP_PORT), 12_000)
            }
            client.tcpNoDelay = true
            android.util.Log.i("RCQturn", "upstream connected via tunnel")
            val up = upstream
            val t = Thread { pump(client, up) }
            t.isDaemon = true
            t.start()
            pump(up, client)
            t.join(1_000)
        } catch (e: Exception) {
            android.util.Log.w("RCQturn", "tunnel leg failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    private fun pump(from: Socket, to: Socket) {
        val buf = ByteArray(16 * 1024)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
            // Either end closing is the normal way a call ends.
        } finally {
            runCatching { to.shutdownOutput() }
        }
    }
}
