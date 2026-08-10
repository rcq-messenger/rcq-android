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
    // User opted out of boot-time auto-engage: don't turn the tunnel on just
    // because a direct probe failed (they route through their own VPN/proxy and
    // don't want our sing-box stacked on top). The explicit KEY_ENABLED toggle
    // still engages it. iOS parity ("rcq.singbox.autoDisabled").
    private const val KEY_AUTO_DISABLED = "auto_disabled"
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
        // An account with its own nodes pins one of THOSE, even when a public
        // relay was pinned first. The pin predates the purchase, and honouring it
        // is precisely how a paid endpoint ends up carrying nothing.
        val mine = privateVlessEntries().mapNotNull { p -> pool.firstOrNull { it.tag == p.tag } }
        val field = mine.ifEmpty { pool }
        field.firstOrNull { it.tag == persisted }?.let { return it }
        val pick = field.first()
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
        val cur = prefs()?.getString(KEY_ENTRY, null)
        // Rotate only among TRUSTED entries — never onto a community/shared relay
        // that would then see the client IP. A paying account rotates within its
        // OWN nodes while it still has a spare: leaving the paid entry on its
        // first failure would hand it back to the pool it was bought to escape.
        // With one paid node and that node confirmed blocked, the public trusted
        // entries are next in line — connectivity outranks ownership.
        val mine = privateVlessEntries()
        val candidates = if (mine.size >= 2 && mine.any { it.tag == cur }) mine else trustedVlessEntries()
        if (candidates.size < 2) return false
        val idx = candidates.indexOfFirst { it.tag == cur }
        val next = candidates[(idx + 1).mod(candidates.size)]
        if (next.tag == cur) return false
        prefs()?.edit()?.putString(KEY_ENTRY, next.tag)?.apply()
        android.util.Log.i("RCQsingbox", "onion entry rotated -> ${next.tag}")
        return true
    }

    /** The account's own paid VLESS endpoints, which outrank every public
     *  candidate for the onion guard.
     *
     *  The ENTRY is the only relay address the client's network operator ever
     *  sees, so it is the only one worth owning. Every public entry is listed in
     *  the signed config, which is a plain unauthenticated fetch — a blocklist
     *  covering the whole pool costs one download. A private endpoint appears in
     *  no such list (the broker never serves it outside its tenant), so it
     *  outlives that blocklist. The EXIT deliberately stays public: it is
     *  invisible from the client's side, so owning it buys nothing, and it is
     *  where a paying customer's traffic mixes with everybody else's. */
    private fun privateVlessEntries(): List<Relay> =
        BrokerRelayStore.privateRelays().filter { it.proto == "vless" }

    /** VLESS relays eligible to be the onion ENTRY (hydra step 3). An entry sees
     *  the client IP (never the destination), so it must be VETTED — which reads
     *  as "curated by us OR owned by you": the account's own paid endpoints
     *  ([privateVlessEntries]) first, then the signed-config relays
     *  ([RelayConfigStore], Ed25519-curated = trusted by provenance), then broker
     *  relays the operator promoted to tier=trusted ([BrokerRelayStore.trustedRelays]).
     *  Social-shared relays ([ContactRelayStore]) are excluded — they only ever
     *  serve as exits / fallback. Dedup by server:port keeps the first occurrence,
     *  so the paid ordering survives it. */
    private fun trustedVlessEntries(): List<Relay> =
        (privateVlessEntries() + RelayConfigStore.currentRelays() + BrokerRelayStore.trustedRelays())
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

    /** Probe [candidates] in parallel and choose a guard among them. Returns the
     *  chosen tag plus whether that candidate actually answered: an
     *  all-probes-failed pick is a spread guess, not a live entry, and the caller
     *  needs to tell the two apart. Blocking. */
    private fun probeAndPick(candidates: List<Relay>): Pair<String, Boolean> {
        val exec = java.util.concurrent.Executors.newFixedThreadPool(minOf(candidates.size, 6))
        val measured: List<Pair<String, Long>> = try {
            candidates.map { c ->
                exec.submit(java.util.concurrent.Callable { c.tag to probeLatencyMs(c.server, c.port) })
            }.mapNotNull { f -> runCatching { f.get(6, TimeUnit.SECONDS) }.getOrNull() }
                .mapNotNull { (tag, ms) -> if (ms != null) tag to ms else null }
        } finally {
            exec.shutdownNow()
        }
        // Every probe failed (the relay port may itself be filtered): still
        // SPREAD — a random candidate beats always camping on first().
        if (measured.isEmpty()) return candidates.random().tag to false
        // NEAREST with SPREAD: random among entries within `tolerance` of the
        // fastest, so near-equals share load while a clearly-closer (e.g.
        // domestic) entry still wins.
        val best = measured.minOf { it.second }
        val tolerance = 50L   // ms — mirrors the urltest tolerance
        return measured.filter { it.second <= best + tolerance }.random().first to true
    }

    /** Hydra step 3: pick the onion ENTRY among TRUSTED VLESS relays by
     *  reachability + NEAREST-with-SPREAD, persisted as the sticky guard. Run
     *  once before building the onion config (in [start]). A no-op when a valid
     *  trusted entry is already pinned — preserves the Tor-guard property (pick
     *  once, keep; don't reshuffle every launch). Only the FIRST pick (or a pick
     *  after the pinned entry leaves the eligible set) probes; confirmed-block
     *  rotation is handled separately by [rotateEntry]. With a single trusted
     *  entry this degrades to today's behaviour; it spreads only once >1 trusted
     *  entry exists (e.g. гидра promotes more domestic relays to trusted).
     *
     *  An account with its own paid endpoints draws the guard from THOSE, and
     *  only falls back to the public pool when none of them answer — the paid
     *  entry is the product, but a formed chain outranks owning it.
     *  Blocking — called from [start] which already runs off-main. */
    private fun selectEntryIfNeeded() {
        onionEntryReachable = false
        if (!onionMode() || localProxyMode()) return
        val all = trustedVlessEntries()
        if (all.isEmpty()) return   // no trusted entry -> onion can't form -> single-hop fallback
        val mine = privateVlessEntries()
        val field = mine.ifEmpty { all }
        val p = prefs()
        val cur = p?.getString(KEY_ENTRY, null)
        field.firstOrNull { it.tag == cur }?.let { pinned ->
            // Keep the pinned guard (Tor-guard property), but confirm it's reachable —
            // the verdict gates onion-vs-single-hop. A blocked guard => single-hop.
            onionEntryReachable = probeLatencyMs(pinned.server, pinned.port) != null
            // A pinned PAID node that went silent falls through to re-selection
            // (its siblings, then the public pool) instead of collapsing the whole
            // chain onto one dead address. Note a public pin under a paying account
            // never matches `field` at all, so the purchase re-pins on its own.
            if (onionEntryReachable || mine.isEmpty()) return
        }
        var (pickTag, reachable) = probeAndPick(field)
        if (!reachable && mine.isNotEmpty()) {
            probeAndPick(all).let { pickTag = it.first; reachable = it.second }
        }
        p?.edit()?.putString(KEY_ENTRY, pickTag)?.apply()
        // The chosen entry carries traffic only if it answered a probe; an
        // all-probes-failed pick is NOT reachable -> single-hop fallback.
        onionEntryReachable = reachable
        android.util.Log.i("RCQsingbox", "onion entry selected -> $pickTag (trusted=${all.size}, paid=${mine.size}, reachable=$reachable)")
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

    /** Is the backend reachable DIRECTLY (no transport)? Drives boot-time
     *  auto-engage: a `false` here means the network is blocking RCQ, so we
     *  bring the tunnel up without the user touching anything (they couldn't
     *  reach Settings to flip the toggle anyway). Blocking — call off-main.
     *
     *  Asks the question the way the diagnostics screen asks it: open a socket,
     *  finish a TLS handshake against our own name. DPI kills both, so nothing
     *  is given away on sensitivity; what goes away is the false positive.
     *
     *  It used to be a full `GET /health` under one five second OkHttp
     *  `callTimeout`, which covers DNS, connect, handshake, request AND
     *  response. On mobile Rostelecom, open and merely slow, that budget ran
     *  out three times in a row and the app told the user their island was
     *  blocked. Its own diagnostics on the same screen said `dir:ok` in the
     *  same seconds, because the instrument allowed twelve seconds and stopped
     *  at the handshake. Two questions, two budgets, one of them printed as the
     *  verdict. What the user got for it: a tunnel nobody asked for, a banner
     *  saying their island was down, and a move to the standby island where
     *  they have a different number and can only receive.
     *
     *  Budgets grow instead of repeating: 4s, then 11s. A healthy network
     *  answers well inside the first one; a slow one needs the second. The
     *  total is 15s, the same wait a genuinely blocked user had before, so the
     *  patience is paid for out of the retries rather than added on top. */
    fun probeDirect(host: String): Boolean =
        intArrayOf(4_000, 11_000).any { tlsReachable(host, 443, it) }

    /** TCP connect plus a completed TLS handshake, both inside [budgetMs].
     *  Certificate validation is left ON: an intercepting middlebox is not a
     *  route to our island either. */
    private fun tlsReachable(host: String, port: Int, budgetMs: Int): Boolean {
        val deadline = System.currentTimeMillis() + budgetMs
        val sock = java.net.Socket()
        return try {
            sock.connect(java.net.InetSocketAddress(host, port), budgetMs)
            val left = (deadline - System.currentTimeMillis()).toInt()
            if (left <= 0) return false
            sock.soTimeout = left
            val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
            val ssl = factory.createSocket(sock, host, port, true) as javax.net.ssl.SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply {
                serverNames = listOf(javax.net.ssl.SNIHostName(host))
            }
            ssl.startHandshake()
            runCatching { ssl.close() }
            true
        } catch (e: Exception) {
            runCatching { sock.close() }
            false
        }
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

    /** When true, the boot path must NOT auto-engage the tunnel on a failed direct
     *  probe (the explicit [isEnabled] toggle still does). For users on their own
     *  VPN/proxy who don't want our sing-box stacked on top. */
    fun autoEngageDisabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_DISABLED, false)

    fun setAutoEngageDisabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_DISABLED, on).apply()
    }

    /** Bring the tunnel up for a destination that is unreachable directly, for
     *  callers that hold no Context (CrossIslandSender). Same guards as the
     *  boot-time auto-engage: never against the user's opt-out, and never in
     *  local-proxy mode, where the user's own proxy is the only allowed route
     *  and stacking sing-box under it would be a leak. Blocking — call off-main.
     *  Returns true when a proxy is available afterwards. */
    fun engageForBlockedDestination(reason: String): Boolean {
        if (isActive) return true
        val ctx = appCtx ?: return false
        if (localProxyMode() || autoEngageDisabled(ctx)) return false
        RelayConfigStore.prime(ctx)
        val ok = start()
        if (ok) android.util.Log.i("RCQfront", "engaged the tunnel for $reason (direct route blocked)")
        return ok
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
            // The EXITS stay public even for an account that owns nodes. Nothing
            // on the client's side of the wire ever sees an exit address, so a
            // private one buys no reachability; what it costs is the mixing —
            // a private exit would carry one tenant's traffic and nobody else's,
            // which is the opposite of what the second hop is for. It also keeps
            // the chain buildable with no change to the public relays: the paid
            // ENTRY is the only machine that must be allowed to forward onward.
            val mineTags = BrokerRelayStore.privateTags()
            val exits = vless.filter { it.tag != entry.tag && it.tag !in mineTags }
                .ifEmpty { vless.filter { it.tag != entry.tag } }
            outbounds.put(JSONObject().apply {
                put("type", "urltest")
                put("tag", "out")
                put("outbounds", JSONArray(exits.map { "onion-${it.tag}" }))
                put("url", RelayConfigStore.probeUrl)
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
            //
            // The account's OWN endpoints lead this race. Without them a paying
            // customer whose chain failed to form would single-hop over exactly
            // the signed-config addresses a censor downloads in one request — the
            // pool they bought their way out of — while the nodes nobody can
            // enumerate sat unused at the one moment they were needed.
            val trusted = (BrokerRelayStore.privateRelays() + RelayConfigStore.currentRelays())
                .distinctBy { "${it.proto}:${it.server}:${it.port}" }
            outbounds.put(JSONObject().apply {
                put("type", "urltest")
                put("tag", "out")
                put("outbounds", JSONArray(trusted.map { it.tag }))
                put("url", RelayConfigStore.probeUrl)
                put("interval", "5m")
                put("tolerance", 50)
            })
            trusted.forEach { outbounds.put(if (it.proto == "hysteria2") hysteria2Outbound(it) else vlessOutbound(it)) }
        } else {
            // PAID NODES FIRST. Somebody who buys private endpoints was getting
            // them thrown into one latency race against the fourteen everybody
            // has, and losing it about as often as winning: the thing they paid
            // for was carrying a minority of their traffic. What is sold is a
            // route nobody else is on, so it IS the route.
            //
            // The shared pool stays in the config underneath rather than being
            // dropped: a private node that dies or gets blocked must not leave
            // a paying customer worse off than a free one. sing-box picks the
            // best live member of a urltest, so `out` racing only the paid
            // nodes with the public urltest as its last member gives exactly
            // that: theirs while any of theirs answers, everyone's when none do.
            val mine = BrokerRelayStore.privateRelays()
            val shared = rs.filter { r -> mine.none { it.tag == r.tag } }
            if (mine.isNotEmpty() && shared.isNotEmpty()) {
                outbounds.put(JSONObject().apply {
                    put("type", "urltest")
                    put("tag", "shared")
                    put("outbounds", JSONArray(shared.map { it.tag }))
                    put("url", RelayConfigStore.probeUrl)
                    put("interval", "5m")
                    put("tolerance", 50)
                })
                outbounds.put(JSONObject().apply {
                    put("type", "urltest")
                    put("tag", "out")
                    put("outbounds", JSONArray(mine.map { it.tag } + "shared"))
                    put("url", RelayConfigStore.probeUrl)
                    // Wide tolerance so a shared node being a few tens of
                    // milliseconds quicker does not pull a paying customer off
                    // their own node; only a real failure should.
                    put("interval", "5m")
                    put("tolerance", 3000)
                })
            } else {
                outbounds.put(JSONObject().apply {
                    put("type", "urltest")
                    put("tag", "out")
                    put("outbounds", JSONArray(rs.map { it.tag }))
                    put("url", RelayConfigStore.probeUrl)
                    put("interval", "5m")
                    put("tolerance", 50)
                })
            }
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
