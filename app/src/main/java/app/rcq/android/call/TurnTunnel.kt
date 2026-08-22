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
 * So on a network that blocks RCQ, someone turns on релеи RCQ, their chats start
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
    /** Matches the tunnel budget the relay probe already uses: through SOCKS →
     *  VLESS relay → coturn over TCP a throttled link regularly needs more
     *  than a few seconds, and a too-short probe condemns a working leg. */
    private const val LEG_PROBE_TIMEOUT_MS = 12_000

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "rcq-turn-tunnel").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)

    @Volatile private var server: ServerSocket? = null
    @Volatile private var upstreamHost: String? = null

    /** `turn:` URL to hand WebRTC, or null when the tunnel is not carrying
     *  calls (transport off, no TURN host known yet, the listener failed, or
     *  the leg through the tunnel carries no TURN, see [ensureRunning]). */
    @Volatile private var url: String? = null

    /** The host whose tunnel leg was last measured, and the verdict. One
     *  measurement per engaged tunnel + host, so a dead leg is not re-probed
     *  (its timeout re-waited) on every credential refresh; forgotten when the
     *  transport goes away, because the verdict belongs to that road. */
    @Volatile private var probedHost: String? = null
    @Volatile private var probedLegOk = false

    /** The URL to use INSTEAD of the island's, or null to use the island's.
     *
     *  Null is the common case and the right default: with no transport in the
     *  way, going straight to the relay is faster and cheaper for everyone. */
    fun activeUrl(): String? = if (SingBoxTransport.isActive) url else null

    /** Point the tunnel at the relay the island handed out, and make sure a
     *  listener is up. Cheap to call repeatedly: it only acts on a change (a
     *  change costs one leg probe, blocking up to [LEG_PROBE_TIMEOUT_MS];
     *  call off-main). */
    @Synchronized
    fun ensureRunning(turnHost: String?) {
        if (!SingBoxTransport.isActive || turnHost.isNullOrBlank()) {
            probedHost = null
            stop()
            return
        }
        if (running.get() && upstreamHost == turnHost && server?.isClosed == false) return
        if (turnHost == probedHost && !probedLegOk) return   // measured dead on this tunnel; stay inactive
        stop()
        // ★ Prove the leg BEFORE arming. The listener coming up says nothing
        // about the road behind it: a relay that filters the TURN host accepts
        // the SOCKS request and returns no bytes, and arming on that leg handed
        // WebRTC a loopback relay that could not carry a call, and the relay probe
        // then timed out against it and relay-only was waived. Armed only on a
        // STUN Binding Success through the tunnel; on failure stay inactive, so
        // call setup falls back to the island's own URLs exactly as it does
        // with no tunnel at all.
        if (turnHost != probedHost) {
            probedHost = turnHost
            probedLegOk = legCarriesTurn(turnHost)
        }
        if (!probedLegOk) {
            android.util.Log.w("RCQturn", "tunnel leg to $turnHost:$TURN_TCP_PORT carries no TURN; not arming")
            return
        }
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

    /** One STUN Binding round trip to the relay THROUGH the tunnel: the same
     *  road, asked the same way [bridge] will ask it (by name; see the
     *  UNRESOLVED note there). A relay that filters the TURN host still opens
     *  the SOCKS connection, so only bytes coming back count as a leg. */
    private fun legCarriesTurn(host: String): Boolean {
        val proxy = SingBoxTransport.proxy() ?: return false
        return runCatching {
            Socket(proxy).use { s ->
                s.connect(InetSocketAddress.createUnresolved(host, TURN_TCP_PORT), LEG_PROBE_TIMEOUT_MS)
                s.soTimeout = LEG_PROBE_TIMEOUT_MS
                val txid = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
                val req = java.nio.ByteBuffer.allocate(20)
                    .putShort(0x0001)          // Binding Request
                    .putShort(0)               // length
                    .putInt(0x2112A442)        // magic cookie
                    .put(txid)
                    .array()
                s.getOutputStream().apply { write(req); flush() }
                val buf = ByteArray(64)
                val n = s.getInputStream().read(buf)
                n >= 2 && (((buf[0].toInt() and 0xff) shl 8) or (buf[1].toInt() and 0xff)) == 0x0101
            }
        }.getOrDefault(false)
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
                // ★★ UNRESOLVED, deliberately: the relay must be told the NAME.
                //
                // `InetSocketAddress(host, port)` resolves here, on the phone, and
                // then SOCKS5 carries a bare IPv4 address. Every relay's routing
                // table ends in `reject` and lets through a domain_suffix of
                // rcq.app plus a short list of ip_cidr — the islands, the fleet,
                // four resolvers. The relay's address is not on that list, so a
                // locally-resolved destination arrived at the relay as an address
                // it had no rule for and was dropped, silently and with no bytes
                // back. Left unresolved, the hostname travels to the relay in the
                // SOCKS request and matches the rule that was always there.
                //
                // Measured, not reasoned: a STUN Binding Request through a relay
                // gets a Binding Success by name and a closed connection by
                // address, over one and the same tunnel.
                connect(InetSocketAddress.createUnresolved(host, TURN_TCP_PORT), 12_000)
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
