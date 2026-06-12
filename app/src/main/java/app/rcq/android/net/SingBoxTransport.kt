package app.rcq.android.net

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Embedded censorship-circumvention transport. Runs sing-box in-process (via
 * the gomobile-bound [rcqbox] wrapper — same Go core the iOS client uses),
 * exposing a local SOCKS/mixed proxy on 127.0.0.1:[LOCAL_PORT]. RcqApi /
 * RcqSocket route their OkHttp traffic through [proxy] when the transport is
 * active, so a VLESS+Reality / Hysteria2 relay carries the API + WebSocket to
 * the backend — defeating DPI that blocks `api.rcq.app` directly.
 *
 * Opt-in (a Settings toggle persisted in prefs); off by default = zero effect,
 * the app connects directly exactly as before. Engaged at boot in
 * [app.rcq.android.Session.start] BEFORE the API/socket are built, so they pick
 * up the proxy. The relay list is bundled here for now; a fetched + Ed25519
 * verified remote config (iOS RelayConfigStore parity) is the next phase.
 */
object SingBoxTransport {
    const val LOCAL_PORT = 1089
    private const val PREFS = "rcq_singbox"
    private const val KEY_ENABLED = "enabled"

    @Volatile
    var isActive = false
        private set

    private var box: rcqbox.BoxService? = null

    data class Relay(
        val tag: String,
        val proto: String,            // "vless" | "hysteria2"
        val server: String,
        val port: Int,
        val sni: String,
        val uuid: String? = null,
        val publicKey: String? = null,
        val shortId: String? = null,
        val flow: String? = null,
        val password: String? = null,
        val obfsPassword: String? = null,
    )

    /** Relay pool: the verified remote list when available, else the bundled
     *  fallback — both resolved by [RelayConfigStore]. */
    private fun relays(): List<Relay> = RelayConfigStore.currentRelays()

    /** SOCKS proxy pointing at the local sing-box inbound, or null when the
     *  transport is off (OkHttp treats null as a direct connection). */
    fun proxy(): Proxy? =
        if (isActive) Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", LOCAL_PORT)) else null

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    // Forces a direct connection (NO_PROXY) so the probe is never routed
    // through an already-engaged transport. Short timeout — a DPI'd network
    // often hangs rather than refusing, and we don't want to stall boot.
    private val probeClient = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY)
        .build()

    /** Is the backend reachable DIRECTLY (no transport)? Drives boot-time
     *  auto-engage: a `false` here means the network is blocking RCQ, so we
     *  bring the tunnel up without the user touching anything (they couldn't
     *  reach Settings to flip the toggle anyway). Blocking — call off-main. */
    fun probeDirect(host: String): Boolean {
        // Retry before concluding the network is BLOCKING RCQ. A cold first
        // connection (emulator boot, first DNS+TLS handshake) can exceed the
        // 5s budget without the network being censored — a single-shot probe
        // misread that as "blocked" and auto-engaged the (slow) tunnel, which
        // is exactly the "login is slow + bypass turns on when it shouldn't"
        // report. A real DPI block keeps timing out across all attempts, so it
        // still auto-engages; a slow-but-working network succeeds on a warm
        // retry and stays direct. Returns as soon as one attempt succeeds.
        repeat(3) {
            val ok = runCatching {
                probeClient.newCall(Request.Builder().url("https://$host/health").get().build())
                    .execute().use { it.isSuccessful }
            }.getOrElse { false }
            if (ok) return true
        }
        return false
    }

    /** Reach the backend through whatever route is live RIGHT NOW — the tunnel
     *  if engaged, else direct. Used by the diagnostics screen. Blocking. */
    fun probeCurrentRoute(host: String): Boolean = runCatching {
        OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).proxy(proxy() ?: Proxy.NO_PROXY).build()
            .newCall(Request.Builder().url("https://$host/health").get().build())
            .execute().use { it.isSuccessful }
    }.getOrElse { false }

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** Start the in-process sing-box. Blocking (call off the main thread).
     *  Returns true once the local proxy is listening (idempotent). */
    @Synchronized
    fun start(): Boolean {
        if (isActive) return true
        return runCatching {
            val svc = rcqbox.Rcqbox.newBoxService()
            svc.start(buildConfig())
            box = svc
            isActive = true
            android.util.Log.i("RCQsingbox", "started — local proxy 127.0.0.1:$LOCAL_PORT")
            true
        }.getOrElse {
            android.util.Log.w("RCQsingbox", "start failed: ${it.message}")
            isActive = false
            box = null
            false
        }
    }

    @Synchronized
    fun stop() {
        isActive = false
        box?.let { runCatching { it.stop() } }
        box = null
    }

    /** sing-box config: a local mixed inbound + one outbound per relay, behind
     *  a `urltest` selector that probes /health through each and picks the
     *  fastest (re-evaluated every 5 min). The urltest outbound is first =
     *  the default route. Format mirrors the iOS buildConfig exactly. */
    private fun buildConfig(): String {
        val rs = relays()
        val outbounds = JSONArray()
        val vless = rs.filter { it.proto == "vless" }
        // ONION (M3): when the signed config turns it on AND we have ≥2 VLESS
        // relays, route through a 2-hop chain so no single relay sees the
        // client IP AND the destination island together. A STICKY entry (the
        // first VLESS relay — O4 will refine selection) carries opaque tunnels
        // to a set of EXIT relays (each `detour`ed through the entry); a urltest
        // races the EXIT chains so the exit rotates while the entry stays
        // sticky (Tor guard lesson). The entry sees only "forward to the exit
        // relay"; the exit sees only "from the entry IP → island". Falls back
        // to the single-hop path below when onion is off or we lack 2 VLESS
        // relays, so connectivity is never worse than today. Proven via a local
        // sing-box prototype (RCQ/docs/onion-design.md).
        if (RelayConfigStore.onionEnabled && vless.size >= 2) {
            val entry = vless.first()
            val exits = vless.drop(1)
            outbounds.put(JSONObject().apply {
                put("type", "urltest")
                put("tag", "out")
                put("outbounds", JSONArray(exits.map { "onion-${it.tag}" }))
                put("url", "https://api.rcq.app/health")
                put("interval", "5m")
                put("tolerance", 50)
            })
            outbounds.put(vlessOutbound(entry).apply { put("tag", "onion-entry") })
            exits.forEach { ex ->
                outbounds.put(vlessOutbound(ex).apply {
                    put("tag", "onion-${ex.tag}")
                    put("detour", "onion-entry")
                })
            }
        } else {
            outbounds.put(JSONObject().apply {
                put("type", "urltest")
                put("tag", "out")
                put("outbounds", JSONArray(rs.map { it.tag }))
                put("url", "https://api.rcq.app/health")
                put("interval", "5m")
                put("tolerance", 50)
            })
            rs.forEach { outbounds.put(if (it.proto == "hysteria2") hysteria2Outbound(it) else vlessOutbound(it)) }
        }

        val inbound = JSONObject().apply {
            put("type", "mixed")
            put("tag", "in")
            put("listen", "127.0.0.1")
            put("listen_port", LOCAL_PORT)
        }
        return JSONObject().apply {
            put("log", JSONObject().put("level", "warn"))
            put("inbounds", JSONArray().put(inbound))
            put("outbounds", outbounds)
        }.toString()
    }

    private fun vlessOutbound(r: Relay): JSONObject = JSONObject().apply {
        put("type", "vless")
        put("tag", r.tag)
        put("server", r.server)
        put("server_port", r.port)
        put("uuid", r.uuid ?: "")
        put("flow", r.flow ?: "xtls-rprx-vision")
        put("tls", JSONObject().apply {
            put("enabled", true)
            put("server_name", r.sni)
            put("utls", JSONObject().apply { put("enabled", true); put("fingerprint", "chrome") })
            put("reality", JSONObject().apply {
                put("enabled", true)
                put("public_key", r.publicKey ?: "")
                put("short_id", r.shortId ?: "")
            })
        })
    }

    /** Hysteria2 outbound: UDP + Salamander obfs (every QUIC packet XOR-wrapped
     *  so DPI can't fingerprint the handshake). insecure=true — the relay has a
     *  self-signed cert; auth is the user + obfs password, not PKI. */
    private fun hysteria2Outbound(r: Relay): JSONObject = JSONObject().apply {
        put("type", "hysteria2")
        put("tag", r.tag)
        put("server", r.server)
        put("server_port", r.port)
        put("password", r.password ?: "")
        put("tls", JSONObject().apply {
            put("enabled", true)
            put("server_name", r.sni)
            put("insecure", true)
        })
        r.obfsPassword?.takeIf { it.isNotEmpty() }?.let {
            put("obfs", JSONObject().apply { put("type", "salamander"); put("password", it) })
        }
    }
}
