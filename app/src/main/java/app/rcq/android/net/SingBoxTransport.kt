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
    private const val KEY_ENTRY = "onion_entry"   // sticky onion guard (O4)
    private const val KEY_ONION_OPTIN = "onion_optin"   // legacy per-device onion opt-in (O5); migrated into KEY_MODE
    // Unified transport topology (once KEY_ENABLED): which outbound shape buildConfig emits.
    private const val KEY_MODE = "transport_mode"   // RELAYS | ONION | LOCAL_PROXY
    private const val KEY_LP_HOST = "lp_host"
    private const val KEY_LP_PORT = "lp_port"
    private const val KEY_LP_TYPE = "lp_type"       // socks | http

    enum class Mode { RELAYS, ONION, LOCAL_PROXY }

    @Volatile
    var isActive = false
        private set

    // App context captured at startup so the onion config build can read/write
    // the sticky-entry pref without threading a Context through start().
    @Volatile
    private var appCtx: Context? = null

    // Onion single-hop-first: whether the chosen sticky ENTRY was reachable at the
    // last [selectEntryIfNeeded] probe. When false (entry blocked/down, or no trusted
    // entry at all), the 2-hop onion chain can't carry traffic, so [buildConfig]
    // degrades to a single-hop race over the TRUSTED signed-config relays instead of
    // building a dead chain. Connectivity-first, but it NEVER single-hops through the
    // untrusted shared/community pool (that would expose an onion user's IP+island to
    // a relay they never vouched for).
    @Volatile
    private var onionEntryReachable = false

    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    private fun prefs(): android.content.SharedPreferences? =
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The selected transport topology. KEY_MODE is the single source of truth;
     *  the first read after upgrade migrates the legacy onion opt-in bool. */
    private fun modeFrom(p: android.content.SharedPreferences): Mode {
        p.getString(KEY_MODE, null)?.let { return runCatching { Mode.valueOf(it) }.getOrDefault(Mode.RELAYS) }
        return if (p.getBoolean(KEY_ONION_OPTIN, false)) Mode.ONION else Mode.RELAYS
    }
    fun mode(): Mode = prefs()?.let { modeFrom(it) } ?: Mode.RELAYS
    fun setMode(ctx: Context, m: Mode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, m.name).apply()
    }

    /** Onion routing is ON when this device selected it OR the signed config
     *  enables it (cohort flip) — EXCEPT an explicit local-proxy choice always
     *  wins (never silently route a Tor-only user through relays). Default OFF. */
    fun onionMode(): Boolean =
        mode() == Mode.ONION || (RelayConfigStore.onionEnabled && mode() != Mode.LOCAL_PROXY)

    /** Route everything through the user's own local SOCKS5/HTTP proxy (Tor/i2p);
     *  exclusive of relays/onion. */
    fun localProxyMode(): Boolean = mode() == Mode.LOCAL_PROXY
    fun localProxyHost(): String = prefs()?.getString(KEY_LP_HOST, "127.0.0.1") ?: "127.0.0.1"
    fun localProxyPort(): Int = prefs()?.getInt(KEY_LP_PORT, 9050) ?: 9050
    fun localProxyType(): String = prefs()?.getString(KEY_LP_TYPE, "socks") ?: "socks"
    fun setLocalProxy(ctx: Context, host: String, port: Int, type: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LP_HOST, host.trim())
            .putInt(KEY_LP_PORT, port)
            .putString(KEY_LP_TYPE, if (type == "http") "http" else "socks")
            .apply()
    }

    /** One-shot reachability check of a user proxy WITHOUT touching the live
     *  transport: dial the proxy directly and GET /health (judging on a 2xx,
     *  not a bare socket-open — a SOCKS port can accept yet the Tor circuit be
     *  down). Hard timeout; a dead/DPI'd proxy hangs. Blocking — call off-main. */
    fun testLocalProxy(host: String, port: Int, type: String): Boolean = runCatching {
        val pType = if (type == "http") Proxy.Type.HTTP else Proxy.Type.SOCKS
        // 25s, not 6s: i2p/Tor can take many seconds to build the first circuit,
        // so a too-short Test wrongly reports a WORKING proxy as unreachable (the
        // i2p "works in Telegram but the RCQ Test fails" report).
        OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS)
            .proxy(Proxy(pType, InetSocketAddress(host.trim(), port))).build()
            .newCall(Request.Builder().url("https://api.rcq.app/health").get().build())
            .execute().use { it.isSuccessful }
    }.getOrElse { false }

    // Legacy onion opt-in shims (the existing Settings onion row + any caller):
    // route through the unified mode so they can never disagree.
    fun isOnionOptIn(ctx: Context): Boolean =
        modeFrom(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)) == Mode.ONION

    fun setOnionOptIn(ctx: Context, on: Boolean) {
        setMode(ctx, if (on) Mode.ONION else Mode.RELAYS)
    }

    /** Sticky onion ENTRY guard (Tor lesson: pin the entry, don't reshuffle it
     *  every launch). Returns the persisted entry tag if it's still a VLESS
     *  relay in [pool]; otherwise picks the highest-priority VLESS (pool is
     *  priority-sorted), persists it, and returns that. */
    private fun stickyEntry(pool: List<Relay>): Relay {
        val persisted = prefs()?.getString(KEY_ENTRY, null)
        pool.firstOrNull { it.tag == persisted }?.let { return it }
        val pick = pool.first()
        prefs()?.edit()?.putString(KEY_ENTRY, pick.tag)?.apply()
        return pick
    }

    /** Rotate the onion ENTRY guard to the next VLESS relay (round-robin),
     *  persisting the choice. Called when the current entry is confirmed
     *  blocked/dead (the whole onion path dies with its single entry). Returns
     *  true when a different entry was selected. Caller restarts the transport
     *  to rebuild the chain. */
    fun rotateEntry(): Boolean {
        if (localProxyMode()) return false   // no onion entry to rotate under a user proxy
        // Rotate only among TRUSTED entries — never onto a community/shared relay
        // that would then see the client IP.
        val candidates = trustedVlessEntries()
        if (candidates.size < 2) return false
        val cur = prefs()?.getString(KEY_ENTRY, null)
        val idx = candidates.indexOfFirst { it.tag == cur }
        val next = candidates[(idx + 1).mod(candidates.size)]
        if (next.tag == cur) return false
        prefs()?.edit()?.putString(KEY_ENTRY, next.tag)?.apply()
        android.util.Log.i("RCQsingbox", "onion entry rotated -> ${next.tag}")
        return true
    }

    /** VLESS relays eligible to be the onion ENTRY (hydra step 3). An entry sees
     *  the client IP (never the destination), so it must be VETTED: the
     *  signed-config relays ([RelayConfigStore], Ed25519-curated = trusted by
     *  provenance) plus broker relays the operator promoted to tier=trusted
     *  ([BrokerRelayStore.trustedRelays]). Social-shared relays ([ContactRelayStore])
     *  are excluded — they only ever serve as exits / fallback. Dedup by server:port. */
    private fun trustedVlessEntries(): List<Relay> =
        (RelayConfigStore.currentRelays() + BrokerRelayStore.trustedRelays())
            .filter { it.proto == "vless" }
            .distinctBy { "${it.server}:${it.port}" }

    /** TCP-connect latency to [host]:[port] in ms, or null on failure/timeout.
     *  Ranks trusted onion-entry candidates. Blocking — call off-main. */
    private fun probeLatencyMs(host: String, port: Int, timeoutMs: Int = 4000): Long? = runCatching {
        java.net.Socket().use { sock ->
            val start = System.nanoTime()
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            (System.nanoTime() - start) / 1_000_000
        }
    }.getOrNull()

    /** Hydra step 3: pick the onion ENTRY among TRUSTED VLESS relays by
     *  reachability + NEAREST-with-SPREAD, persisted as the sticky guard. Run
     *  once before building the onion config (in [start]). A no-op when a valid
     *  trusted entry is already pinned — preserves the Tor-guard property (pick
     *  once, keep; don't reshuffle every launch). Only the FIRST pick (or a pick
     *  after the pinned entry leaves the trusted set) probes; confirmed-block
     *  rotation is handled separately by [rotateEntry]. With a single trusted
     *  entry this degrades to today's behaviour; it spreads only once >1 trusted
     *  entry exists (e.g. гидра promotes more domestic relays to trusted).
     *  Blocking — called from [start] which already runs off-main. */
    private fun selectEntryIfNeeded() {
        onionEntryReachable = false
        if (!onionMode() || localProxyMode()) return
        val candidates = trustedVlessEntries()
        if (candidates.isEmpty()) return   // no trusted entry -> onion can't form -> single-hop fallback
        val p = prefs()
        val cur = p?.getString(KEY_ENTRY, null)
        candidates.firstOrNull { it.tag == cur }?.let { pinned ->
            // Keep the pinned guard (Tor-guard property), but confirm it's reachable —
            // the verdict gates onion-vs-single-hop. A blocked guard => single-hop.
            onionEntryReachable = probeLatencyMs(pinned.server, pinned.port) != null
            return
        }
        // (Re)select: probe reachability + latency in parallel.
        val exec = java.util.concurrent.Executors.newFixedThreadPool(minOf(candidates.size, 6))
        val measured: List<Pair<String, Long>> = try {
            candidates.map { c ->
                exec.submit(java.util.concurrent.Callable { c.tag to probeLatencyMs(c.server, c.port) })
            }.mapNotNull { f -> runCatching { f.get(6, TimeUnit.SECONDS) }.getOrNull() }
                .mapNotNull { (tag, ms) -> if (ms != null) tag to ms else null }
        } finally {
            exec.shutdownNow()
        }
        val pickTag = if (measured.isEmpty()) {
            // Every probe failed (the relay port may itself be filtered): still
            // SPREAD — a random trusted candidate beats always camping pool.first().
            candidates.random().tag
        } else {
            // NEAREST with SPREAD: random among entries within `tolerance` of the
            // fastest, so near-equals share load while a clearly-closer (e.g.
            // domestic) entry still wins.
            val best = measured.minOf { it.second }
            val tolerance = 50L   // ms — mirrors the urltest tolerance
            measured.filter { it.second <= best + tolerance }.random().first
        }
        p?.edit()?.putString(KEY_ENTRY, pickTag)?.apply()
        // The chosen entry carries traffic only if it was among the reachable probes;
        // an all-probes-failed random pick is NOT reachable -> single-hop fallback.
        onionEntryReachable = measured.any { it.first == pickTag }
        android.util.Log.i("RCQsingbox", "onion entry selected -> $pickTag (trusted=${candidates.size}, reachable=${measured.size})")
    }

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
     *  fallback (both resolved by [RelayConfigStore]), PLUS any relays a contact
     *  shared / the user imported ([ContactRelayStore]). Shared relays append at
     *  the BACK of the priority-sorted list = extra fallback capacity that never
     *  displaces a canary-verified relay nor becomes the onion sticky entry; if
     *  every signed-config relay is blocked, the urltest race lets a working
     *  shared relay win. */
    private fun relays(): List<Relay> =
        (RelayConfigStore.currentRelays() + ContactRelayStore.relays() + BrokerRelayStore.relays())
            .distinctBy { "${it.proto}:${it.server}:${it.port}" }

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
            // Hydra step 3: settle the sticky onion ENTRY (nearest trusted, spread)
            // before the config is built. A no-op when an entry is already pinned
            // or onion is off, so it adds latency only on the first onion engage.
            selectEntryIfNeeded()
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
        // client IP AND the destination island together. A STICKY entry (O4:
        // a persisted guard, [stickyEntry], rotated only on confirmed block)
        // carries opaque tunnels to a set of EXIT relays (each `detour`ed
        // through the entry); a urltest
        // races the EXIT chains so the exit rotates while the entry stays
        // sticky (Tor guard lesson). The entry sees only "forward to the exit
        // relay"; the exit sees only "from the entry IP → island". Falls back
        // to the single-hop path below when onion is off or we lack 2 VLESS
        // relays, so connectivity is never worse than today. Proven via a local
        // sing-box prototype (RCQ/docs/onion-design.md).
        if (localProxyMode()) {
            // LOCAL PROXY: a single socks/http outbound to the user's own
            // Tor/i2p; no relays, no urltest, no onion. The user's proxy IS the
            // circumvention + metadata layer. sing-box just forwards the local
            // mixed inbound (1089) → user proxy, so proxy()/call sites are
            // untouched. NO automatic fallback to relays (that would leak around
            // Tor) — if the proxy is down, requests fail until the user fixes it.
            outbounds.put(JSONObject().apply {
                put("type", if (localProxyType() == "http") "http" else "socks")
                put("tag", "out")
                put("server", localProxyHost())
                put("server_port", localProxyPort())
                if (localProxyType() != "http") put("version", "5")
            })
        } else if (onionMode() && vless.size >= 2 && onionEntryReachable) {
            val entry = stickyEntry(vless)          // O4: persisted guard, not just vless.first()
            val exits = vless.filter { it.tag != entry.tag }
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
        } else if (onionMode()) {
            // Onion DESIRED but the 2-hop chain can't form (sticky entry unreachable,
            // or <2 VLESS): single-hop race over the TRUSTED signed-config/bundled relays
            // ONLY. Connectivity-first (a trusted single hop beats a dead chain), but it
            // NEVER races the untrusted shared/community pool here — single-hopping an
            // onion user through a relay they didn't vouch for would expose their IP +
            // destination island. The domestic bundled entry keeps this reachable for a
            // blocked user even when the foreign trusted relays are down.
            val trusted = RelayConfigStore.currentRelays()
            outbounds.put(JSONObject().apply {
                put("type", "urltest")
                put("tag", "out")
                put("outbounds", JSONArray(trusted.map { it.tag }))
                put("url", "https://api.rcq.app/health")
                put("interval", "5m")
                put("tolerance", 50)
            })
            trusted.forEach { outbounds.put(if (it.proto == "hysteria2") hysteria2Outbound(it) else vlessOutbound(it)) }
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
