package app.rcq.android.net

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.internal.tls.OkHostnameVerifier
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * An island trusted by its certificate's fingerprint, not by a certificate
 * authority (docs/island-fingerprint-design.md; spec §11.6).
 *
 * The one place every connection to an island passes through. A chain the
 * platform trusts for the host it dialled is accepted as before, and the pin
 * store only governs certificates the platform does NOT trust, with one
 * exception: a fingerprint the person typed into the address (`host#fp`) wins
 * over an authority's signature, because they handed the island's identity to
 * the client out of band and nothing that arrives over the network may replace
 * it. Whoever can obtain a certificate the platform trusts for the address
 * (Let's Encrypt issues for IP literals) would otherwise have replaced the pin
 * silently and taken the session token.
 *
 * ⚠ `decide` runs on the SUCCESS branch of platform validation too, and the
 * `ca` record it writes there is the point: a client that consults the store
 * only when the platform refuses has no `ca` records, and for it every island
 * used for months over Let's Encrypt is still an unknown island that an
 * attacker's self-signed certificate takes on first use.
 *
 * ⚠ `caValid` is chain AND name. OkHttp never sets an endpoint identification
 * algorithm, so the platform's `checkServerTrusted` does no SAN matching, and a
 * valid certificate for `attacker.example` presented on the path to a pinned
 * island would have written a `ca` record over the pin. [Manager] runs
 * [OkHostnameVerifier] itself before it believes the platform.
 *
 * A refusal is a refusal: a connection that carries a session token cannot be
 * judged the way a rendered `.rcq` page can, so a changed certificate is not
 * connected to at all until the person decides. It is also NOT a blocked route
 * ([SingBoxTransport.Reachability.REFUSED]): the ladder must not pull relays
 * and re-attempt the same refused handshake through every one of them.
 *
 * The store is keyed `host:port`, lowercase, device-wide and not per account,
 * for the same reason as [app.rcq.android.sites.SitePins]: a pin is a statement
 * about an island, not about the reader, and a per-account store would reset
 * every warning on an account switch, the one moment a warning is worth most.
 * Plain storage on purpose: a pin is not a secret, losing it costs a re-trust.
 *
 * The pure parts ([parseFingerprint], [displayFingerprint], [splitAddress],
 * [decide] over a map) touch nothing of Android and have JUnit tests beside
 * `RelayInputTest`.
 */
object IslandTrust {

    private const val PREFS = "rcq_island_pins"
    private const val K_RECORDS = "records.v1"
    const val DEFAULT_PORT = 443

    enum class Mode {
        @SerializedName("ca") CA,
        @SerializedName("pinned") PINNED,
    }

    enum class Source {
        @SerializedName("tofu") TOFU,
        @SerializedName("typed") TYPED,
        @SerializedName("accepted") ACCEPTED,
    }

    /** One line of the store, the record of design §4. */
    data class Record(
        val mode: Mode,
        val fp: String? = null,
        val source: Source? = null,
        /** Unix seconds. */
        val since: Long = 0L,
        /** The first-use notice was shown and dismissed for this host. */
        val noticed: Boolean = false,
    )

    enum class Reason { CA_ONLY, CHANGED }

    sealed class Decision {
        object Accept : Decision()
        data class AcceptFirstUse(val fp: String) : Decision()
        data class Refuse(
            val reason: Reason,
            /** Fingerprint on file; null stands for "a certificate authority". */
            val old: String?,
            val new: String,
            /** The refused chain was CA-valid: accepting it records `ca`. */
            val ca: Boolean,
            /** The record on file was typed by the person. */
            val typed: Boolean,
        ) : Decision()
    }

    /** What the banner draws: a host refused until the person chooses. */
    data class Changed(
        val host: String,
        val port: Int,
        /** On file; null is "a certificate authority". */
        val old: String?,
        val new: String,
        val ca: Boolean,
        val typed: Boolean,
        /** [new] was typed into an address form against a record that
         *  disagrees (design §3), rather than presented by the island: nothing
         *  was dialled, and accepting writes it with source `typed`. */
        val typedNew: Boolean = false,
    ) {
        val key: String get() = key(host, port)
        val hostPort: String get() = hostPort(host, port)
    }

    /** A first connection that pinned on first use and has not been said out
     *  loud yet. */
    data class FirstUse(val host: String, val port: Int, val fp: String) {
        val key: String get() = key(host, port)
        val hostPort: String get() = hostPort(host, port)
    }

    /** A REFUSE, as the handshake throws it. A [CertificateException] so
     *  Conscrypt wraps it in the `SSLHandshakeException` OkHttp hands back, and
     *  OkHttp does not retry it on another route (it never retries a
     *  CertificateException cause). Find it again with [refusalOf]. */
    class IslandTrustRefused(
        val host: String,
        val port: Int,
        val reason: Reason,
        val old: String?,
        val new: String,
        val ca: Boolean,
        val typed: Boolean,
    ) : CertificateException(
        "island ${hostPort(host, port)} refused (${reason.name.lowercase()}): " +
            "on file ${old ?: "ca"}, presented $new",
    )

    // ── Pure parts ────────────────────────────────────────────────────────

    /** Canonical fingerprint out of anything a person pastes: `openssl`'s
     *  colon form, any case, spaces. Null when it is not 64 hex characters. */
    fun parseFingerprint(raw: String?): String? {
        if (raw == null) return null
        val s = raw.trim().lowercase().filter { it != ':' && !it.isWhitespace() }
        if (s.length != 64) return null
        if (!s.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return s
    }

    /** 16 groups of 4, four groups to a line, for a monospace label. */
    fun displayFingerprint(fp: String): String =
        fp.chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") }

    /** `host[:port]#fragment`, taken apart. [fragment] is the raw text after
     *  `#` (null when there is none); [fp] is what it parses to. */
    data class Address(val host: String, val port: Int?, val fragment: String?) {
        val fp: String? get() = parseFingerprint(fragment)
        /** `host` or `host:port`, the way the rest of the app addresses it. */
        val hostPort: String get() = if (port != null) "$host:$port" else host
        val key: String get() = key(host, port ?: DEFAULT_PORT)
    }

    /**
     * Split the fragment off FIRST, then normalise the rest (scheme, path,
     * query, trailing slash gone; `host:port` kept). ⚠ The normalisers this
     * replaces (`java.net.URI(…).host`, `substringBefore('/')`) drop a fragment
     * without a word, and a dropped fragment connects with a first-use pin
     * while the person believes they pinned. IPv6 literals stay bracketed.
     * Null for nothing typed or a host that cannot be one.
     */
    fun splitAddress(raw: String?): Address? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val hash = s.indexOf('#')
        val fragment = if (hash >= 0) s.substring(hash + 1).trim() else null
        if (hash >= 0) s = s.substring(0, hash).trim()
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        s = s.substringBefore('/').substringBefore('?').trim()
        if (s.contains('@')) s = s.substringAfterLast('@')
        if (s.isEmpty()) return null
        val host: String
        val port: Int?
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            host = s.substring(0, end + 1).lowercase()
            val rest = s.substring(end + 1)
            port = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1).toIntOrNull() ?: return null
                else -> return null
            }
        } else {
            val colon = s.lastIndexOf(':')
            if (colon >= 0) {
                // A bare IPv6 literal has more than one colon and no port to
                // tell apart from its last group; it is written bracketed.
                if (s.indexOf(':') != colon) return null
                host = s.substring(0, colon).lowercase()
                port = s.substring(colon + 1).toIntOrNull() ?: return null
            } else {
                host = s.lowercase()
                port = null
            }
        }
        if (host.isEmpty() || host.length < 2 && host.startsWith("[")) return null
        if (port != null && port !in 1..65535) return null
        return Address(host, port, fragment)
    }

    /** Store key: lowercase host, brackets kept on an IPv6 literal, the port
     *  always spelled out. OkHttp hands the verifier an UNbracketed IPv6 host
     *  (`HttpUrl.host`), so the brackets are put back here. */
    fun key(host: String, port: Int): String = "${bracketed(host)}:$port"

    /** `host` when the port is the default, else `host:port`. */
    fun hostPort(host: String, port: Int): String =
        if (port == DEFAULT_PORT) bracketed(host) else "${bracketed(host)}:$port"

    /** The key for a `host[:port]` string as the app carries it. */
    fun keyOf(hostPort: String): String {
        val a = splitAddress(hostPort) ?: return "${hostPort.trim().lowercase()}:$DEFAULT_PORT"
        return a.key
    }

    /** Bare host (no brackets) and port out of `host[:port]`, for a socket. */
    fun hostAndPort(hostPort: String): Pair<String, Int> {
        val a = splitAddress(hostPort) ?: return hostPort.trim() to DEFAULT_PORT
        return bare(a.host) to (a.port ?: DEFAULT_PORT)
    }

    private fun bracketed(host: String): String {
        val h = host.trim().lowercase()
        return if (h.contains(':') && !h.startsWith("[")) "[$h]" else h
    }

    private fun bare(host: String): String = host.removePrefix("[").removeSuffix("]")

    /** Hosts that are never pinned, typed or not: the flagship, its CDN front,
     *  and anything under `rcq.app`. [front] is the front host the signed
     *  config names; the default reads it from [RelayConfigStore]. */
    fun isCaOnly(host: String, front: String? = RelayConfigStore.frontHost): Boolean {
        val h = bare(host).trim().lowercase()
        if (h == RcqApi.DEFAULT_HOST) return true
        if (front != null && h == front.trim().lowercase()) return true
        return h == "rcq.app" || h.endsWith(".rcq.app")
    }

    /**
     * The rule of design §1, over [records]. Writes the `ca` record on the
     * platform's success and the `tofu` pin on a first use; the caller
     * persists when it returns.
     *
     * The typed branch comes BEFORE `caValid` on purpose: a typed pin is the
     * identity the person was handed, and a chain that does not hash to it is
     * a change whether or not an authority signed it.
     */
    fun decide(
        host: String,
        port: Int,
        fp: String,
        caValid: Boolean,
        caOnly: Boolean,
        records: MutableMap<String, Record>,
        now: Long = System.currentTimeMillis() / 1000,
    ): Decision {
        val k = key(host, port)
        val rec = records[k]
        if (caOnly) {
            return if (caValid) Decision.Accept
            else Decision.Refuse(Reason.CA_ONLY, old = null, new = fp, ca = false, typed = false)
        }
        if (rec != null && rec.source == Source.TYPED) {
            if (rec.fp == fp) return Decision.Accept
            return Decision.Refuse(Reason.CHANGED, old = rec.fp, new = fp, ca = caValid, typed = true)
        }
        if (caValid) {
            // Overwrites a tofu or accepted pin: the island moved to a CA. Not
            // rewritten on every handshake once it is there.
            if (rec?.mode != Mode.CA) records[k] = Record(Mode.CA, since = now)
            return Decision.Accept
        }
        if (rec == null) {
            records[k] = Record(Mode.PINNED, fp = fp, source = Source.TOFU, since = now)
            return Decision.AcceptFirstUse(fp)
        }
        if (rec.mode == Mode.CA) {
            return Decision.Refuse(Reason.CHANGED, old = null, new = fp, ca = false, typed = false)
        }
        if (rec.fp == fp) return Decision.Accept
        return Decision.Refuse(Reason.CHANGED, old = rec.fp, new = fp, ca = false, typed = false)
    }

    fun sha256Hex(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    // ── The store ─────────────────────────────────────────────────────────

    private val gson = Gson()
    private val lock = Any()
    private var prefs: SharedPreferences? = null
    /** Guarded by [lock]. */
    private val pins = HashMap<String, Record>()

    private val _records = MutableStateFlow<Map<String, Record>>(emptyMap())
    /** Every record, for the Settings row. */
    val records: StateFlow<Map<String, Record>> = _records.asStateFlow()

    private val _changed = MutableStateFlow<Map<String, Changed>>(emptyMap())
    /** Hosts refused until the person chooses, by key. The UI draws from this
     *  and nothing else in the app has to remember to check. */
    val changed: StateFlow<Map<String, Changed>> = _changed.asStateFlow()

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    /** "Not now": the banner is out of the way, the island stays refused. A
     *  fresh refusal for the host brings the banner back. */
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    private val _firstUse = MutableStateFlow<List<FirstUse>>(emptyList())
    /** First-use pins nobody has been told about yet, oldest first. */
    val firstUse: StateFlow<List<FirstUse>> = _firstUse.asStateFlow()

    /** Idempotent, and called from [app.rcq.android.RcqApp.onCreate] — the one
     *  entry point every process start runs, headless ones included — so no
     *  handshake can be judged before the store is on the table. [evaluate]
     *  fails closed if one somehow is. */
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val type = object : TypeToken<Map<String, Record>>() {}.type
        val loaded: Map<String, Record> = runCatching {
            gson.fromJson<Map<String, Record>>(p.getString(K_RECORDS, "{}") ?: "{}", type)
        }.getOrNull() ?: emptyMap()
        synchronized(lock) {
            prefs = p
            // ⚠ The FILE wins a collision, not the in-memory record. Anything
            // written before the store was loaded was judged against an empty
            // map, so for a host that has a record on file it can only be a
            // first-use pin taken over the operator's real fingerprint —
            // merging it the other way round persisted that pin over the
            // genuine one and made the downgrade permanent.
            val unsaved = pins.keys.any { it !in loaded }
            for ((k, v) in loaded) pins[k] = v
            if (unsaved) persistLocked()
            publishLocked()
        }
    }

    private fun persistLocked() {
        prefs?.edit()?.putString(K_RECORDS, gson.toJson(pins))?.apply()
    }

    private fun publishLocked() {
        _records.value = HashMap(pins)
        _firstUse.value = pins.entries
            .filter { it.value.mode == Mode.PINNED && it.value.source == Source.TOFU && !it.value.noticed && it.value.fp != null }
            .sortedBy { it.value.since }
            .map { (k, v) ->
                val (h, p) = hostAndPort(k)
                FirstUse(h, p, v.fp!!)
            }
    }

    /** The record for a `host[:port]` as the app carries it, or null. */
    fun recordOf(hostPort: String): Record? = synchronized(lock) { pins[keyOf(hostPort)] }

    /** True while the island is refused and waiting on the person. */
    fun isRefused(hostPort: String): Boolean = _changed.value.containsKey(keyOf(hostPort))

    /** True while ANY port of [hostPort]'s host is refused. The blocked-route
     *  memos ([RcqApi] and [CrossIslandSender] both keep one) are keyed on the
     *  bare host, so a guard against them has to ask the same question or a
     *  refused island on `:8443` would still force the tunnel on through the
     *  memo for `:443`. */
    fun isHostRefused(hostPort: String): Boolean {
        val h = splitAddress(hostPort)?.host ?: return false
        val bare = bare(h)
        return _changed.value.values.any { bare(it.host) == bare }
    }

    /** The live rule: [decide] over the store, persisted, with the `changed`
     *  and first-use state kept for the UI. Throws on REFUSE. */
    fun evaluate(host: String, port: Int, leafDer: ByteArray, caValid: Boolean) {
        val fp = sha256Hex(leafDer)
        val caOnly = isCaOnly(host)
        val decision = synchronized(lock) {
            // ⚠ Refuse rather than judge an island against a store that has not
            // been read yet: every record would come back null, so a host with
            // a `pinned` or `ca` record on file would be taken as a FIRST USE
            // and whatever is on the wire pinned in its place — the silent
            // downgrade the `ca` write exists to prevent. Not an
            // [IslandTrustRefused]: nothing changed, the client simply cannot
            // tell yet, so this stays an ordinary TLS failure and raises no
            // banner. A CA-only host consults no record and keeps working.
            if (prefs == null && !caOnly) {
                throw CertificateException("island pin store not loaded for ${hostPort(host, port)}")
            }
            val before = HashMap(pins)
            val d = decide(host, port, fp, caValid, caOnly, pins)
            if (pins != before) { persistLocked(); publishLocked() }
            d
        }
        val k = key(host, port)
        when (decision) {
            is Decision.Accept, is Decision.AcceptFirstUse -> {
                // The island answered with what is on file (or moved to a CA
                // the way the rule allows): whatever banner stood for it is
                // stale. The first-use list was rebuilt under the lock.
                if (_changed.value.containsKey(k)) {
                    _changed.update { it - k }
                    _hidden.update { it - k }
                }
            }
            is Decision.Refuse -> {
                if (decision.reason == Reason.CHANGED) {
                    val ch = Changed(bracketed(host), port, decision.old, decision.new, decision.ca, decision.typed)
                    _changed.update { it + (k to ch) }
                    _hidden.update { it - k }
                }
                throw IslandTrustRefused(
                    bracketed(host), port, decision.reason, decision.old, decision.new,
                    decision.ca, decision.typed,
                )
            }
        }
    }

    /** "Trust the new fingerprint". Writes `ca` when the refused chain was
     *  CA-valid (a typed pin against an island that moved to an authority):
     *  pinning a leaf an authority rotates would bring the banner back at the
     *  next renewal. A value typed into a form is written back as typed. */
    fun accept(key: String) {
        val ch = _changed.value[key] ?: return
        val now = System.currentTimeMillis() / 1000
        synchronized(lock) {
            pins[key] = when {
                ch.typedNew -> Record(Mode.PINNED, fp = ch.new, source = Source.TYPED, since = now)
                ch.ca -> Record(Mode.CA, since = now)
                else -> Record(Mode.PINNED, fp = ch.new, source = Source.ACCEPTED, since = now)
            }
            persistLocked(); publishLocked()
        }
        _changed.update { it - key }
        _hidden.update { it - key }
    }

    /** "Not now": the banner goes, the refusal stays. */
    fun later(key: String) {
        _hidden.update { it + key }
    }

    /** The first-use notice for this host was seen; never again. */
    fun noticed(key: String) {
        synchronized(lock) {
            val rec = pins[key] ?: return
            if (rec.noticed) return
            pins[key] = rec.copy(noticed = true)
            persistLocked(); publishLocked()
        }
    }

    // ── The address form (design §3) ──────────────────────────────────────

    sealed class Entry {
        /** Connect to this `host[:port]`; a fragment, if any, is on file. */
        data class Ok(val hostPort: String) : Entry()
        /** Nothing typed: the caller's default island. */
        object Empty : Entry()
        /** Not a host. */
        object Malformed : Entry()
        /** The fragment does not normalise to 64 hex characters. */
        object NotAFingerprint : Entry()
        /** A fragment on a host that is never pinned. */
        data class CaOnly(val host: String) : Entry()
        /** The store disagrees with the fragment: banner, nothing dialled. */
        data class Disagrees(val changed: Changed) : Entry()
    }

    /**
     * What a typed address means, with the store consulted. With [commit] a
     * fragment against a null record is pinned as typed BEFORE anything is
     * dialled, and a fragment against a record that disagrees is recorded for
     * the banner; without it nothing is written, for a form that validates as
     * the person types. A fragment equal to what is on file is a no-op. Only a
     * null record is ever pre-pinned silently: an address that arrives in a
     * chat or an invite for an island this device already trusts must not be
     * able to replace that trust because somebody opened it.
     */
    fun inspect(raw: String?, commit: Boolean): Entry {
        if (raw.isNullOrBlank()) return Entry.Empty
        val a = splitAddress(raw) ?: return Entry.Malformed
        if (a.fragment == null) return Entry.Ok(a.hostPort)
        val fp = a.fp ?: return Entry.NotAFingerprint
        if (isCaOnly(a.host)) return Entry.CaOnly(a.host)
        val port = a.port ?: DEFAULT_PORT
        val rec = synchronized(lock) { pins[a.key] }
        if (rec == null) {
            if (commit) synchronized(lock) {
                if (pins[a.key] == null) {
                    pins[a.key] = Record(Mode.PINNED, fp = fp, source = Source.TYPED, since = System.currentTimeMillis() / 1000)
                    persistLocked(); publishLocked()
                }
            }
            return Entry.Ok(a.hostPort)
        }
        if (rec.mode == Mode.PINNED && rec.fp == fp) return Entry.Ok(a.hostPort)
        val ch = Changed(
            host = a.host, port = port,
            old = if (rec.mode == Mode.CA) null else rec.fp,
            new = fp, ca = false, typed = rec.source == Source.TYPED, typedNew = true,
        )
        if (commit) {
            _changed.update { it + (a.key to ch) }
            _hidden.update { it - a.key }
        }
        return Entry.Disagrees(ch)
    }

    /** [inspect] that writes: the form's submit. */
    fun adopt(raw: String?): Entry = inspect(raw, commit = true)

    // ── TLS ───────────────────────────────────────────────────────────────

    private val platform: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** The trust manager of design §7.1, wrapping the platform default. */
    val trustManager: X509ExtendedTrustManager by lazy { Manager(platform) }

    /** A factory whose sockets validate through [trustManager]. For OkHttp via
     *  [islandTrust], and for the raw probe socket in [SingBoxTransport]. */
    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    }

    /** OkHttp's own matcher says yes, or the store holds a `pinned` record for
     *  `host:port` whose fingerprint is the leaf's. Both gates run before a
     *  byte of the request is sent. */
    val hostnameVerifier: HostnameVerifier = HostnameVerifier { host, session ->
        if (OkHostnameVerifier.verify(host, session)) return@HostnameVerifier true
        val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
            ?: return@HostnameVerifier false
        val rec = synchronized(lock) { pins[key(host, session.peerPort)] }
        rec?.mode == Mode.PINNED && rec.fp == sha256Hex(leaf.encoded)
    }

    /**
     * ⚠ Reads the dialled host and port from the socket's handshake session,
     * exactly how Android's own RootTrustManager learns the hostname. Through
     * the sing-box SOCKS proxy the wrapped socket still reports the island's
     * port (a SOCKS `java.net.Socket` answers with its external address), so
     * the key is the island's, not the proxy's. Not offered to OkHttp's
     * certificate chain cleaner (no `checkServerTrusted(chain, authType,
     * host)` here): OkHttp falls back to its basic cleaner, which only ever
     * runs for certificate pins, of which this app has none.
     */
    private class Manager(private val platform: X509TrustManager) : X509ExtendedTrustManager() {

        override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            platform.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) =
            (platform as? X509ExtendedTrustManager)?.checkClientTrusted(chain, authType, socket)
                ?: platform.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
            (platform as? X509ExtendedTrustManager)?.checkClientTrusted(chain, authType, engine)
                ?: platform.checkClientTrusted(chain, authType)

        /** No socket, so no host to key on: the platform's answer alone, which
         *  is what a CA-only host gets anyway. Conscrypt does not take this
         *  path for an [X509ExtendedTrustManager]. */
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            platform.checkServerTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {
            val session = (socket as? SSLSocket)?.handshakeSession
            val host = session?.peerHost
            if (host.isNullOrBlank()) {
                platformCheck(chain, authType, socket, null); return
            }
            evaluate(chain, host, session.peerPort) { platformCheck(chain, authType, socket, null) }
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {
            val host = engine.peerHost
            if (host.isNullOrBlank()) {
                platformCheck(chain, authType, null, engine); return
            }
            evaluate(chain, host, engine.peerPort) { platformCheck(chain, authType, null, engine) }
        }

        private fun platformCheck(chain: Array<X509Certificate>, authType: String, socket: Socket?, engine: SSLEngine?) {
            val ext = platform as? X509ExtendedTrustManager
            when {
                ext != null && socket != null -> ext.checkServerTrusted(chain, authType, socket)
                ext != null && engine != null -> ext.checkServerTrusted(chain, authType, engine)
                else -> platform.checkServerTrusted(chain, authType)
            }
        }

        private fun evaluate(chain: Array<X509Certificate>, host: String, port: Int, platformCheck: () -> Unit) {
            if (chain.isEmpty()) throw CertificateException("empty chain")
            val chainOk = runCatching(platformCheck).isSuccess
            // The name gate, for the host that was dialled and not for whatever
            // the chain happens to name. IP literals match their IP SANs.
            val nameOk = runCatching { OkHostnameVerifier.verify(bare(host), chain[0]) }.getOrDefault(false)
            val p = if (port > 0) port else DEFAULT_PORT
            IslandTrust.evaluate(host, p, chain[0].encoded, caValid = chainOk && nameOk)
        }
    }

    /** The refusal inside whatever OkHttp or a raw socket threw, or null.
     *  Conscrypt wraps it in an `SSLHandshakeException`; OkHttp may wrap that
     *  once more. */
    fun refusalOf(t: Throwable?): IslandTrustRefused? {
        var cur = t
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur is IslandTrustRefused) return cur
            for (s in cur.suppressed) refusalOf(s)?.let { return it }
            cur = cur.cause
            depth++
        }
        return null
    }

    /** A refusal that waits on the person (design §5.5): the ladder treats it
     *  as terminal. A CA-only host's failure is the platform's ordinary TLS
     *  failure and stays what it always was, a route that did not answer. */
    fun isChangedRefusal(t: Throwable?): Boolean = refusalOf(t)?.reason == Reason.CHANGED
}

/** Install the island trust manager and hostname verifier. Every builder that
 *  dials an island calls this (design §6); `newBuilder()` twins inherit it. */
fun OkHttpClient.Builder.islandTrust(): OkHttpClient.Builder =
    sslSocketFactory(IslandTrust.socketFactory, IslandTrust.trustManager)
        .hostnameVerifier(IslandTrust.hostnameVerifier)
