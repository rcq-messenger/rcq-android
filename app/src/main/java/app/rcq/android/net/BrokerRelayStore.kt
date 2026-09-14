package app.rcq.android.net

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Relays pulled from the BROKER (`GET /broker/bridges`) — the per-request,
 * anti-enumeration distribution channel that complements the fully-public signed
 * relay-config (so a censor can't scrape + block the whole pool). See
 * `RCQ/docs/relay-broker-design.md`. Composes with [ContactRelayStore] (social
 * bridge sharing): both feed the transport pool with off-config relays.
 *
 * Device-level. Best-effort fetch at boot (direct — the transport may not be up
 * yet; if the flagship is blocked the fetch just fails and we keep the cached /
 * signed-config / bundled relays). The bucket is derived SERVER-SIDE from the
 * requester IP, so the client sends no identity — just `?n=`. Parsed via the
 * shared [ContactRelayStore.relayFromJson] (the broker descriptor uses the same
 * keys), retagged `broker-<server>-<port>` for a collision-proof sing-box tag.
 */
object BrokerRelayStore {
    // The broker lives on the flagship; clients fetch their few bridges there.
    private const val BROKER_HOST = "api.rcq.app"
    private const val WANT = 3
    private const val PREFS = "rcq_broker_relays"
    private const val KEY = "relays"
    // Tags of broker relays the broker marked tier=="trusted" (admin-promoted).
    // Only these may become an onion ENTRY (an entry sees the client IP, so it
    // must be vetted); community broker relays stay exits / fallback. The tier
    // rides the TLS-authenticated /broker/bridges response (broker.py serves it).
    private const val KEY_TRUSTED = "trusted_tags"
    // Region-scoped liveness: report which relays are reachable FROM THIS network
    // so the broker serves them where they actually work (POST /broker/reachability).
    private const val KEY_REPORT_TS = "reach_report_ts"
    // The paid tenant key, if the user has one. Device-level like everything
    // else here: it buys network access, not an identity, and a person with two
    // accounts on one phone bought it once.
    private const val KEY_TENANT = "tenant_key"
    private const val KEY_PRIVATE = "private_tags"
    private const val KEY_VERDICT = "key_verdict"
    private const val REPORT_INTERVAL_MS = 60 * 60 * 1000L  // at most hourly

    /** How the route ended up last time it was settled — the half a TCP probe
     *  cannot answer.
     *
     *  ★ A reachability vote says "the port answered". DPI usually lets the TCP
     *  handshake through and kills the connection at the TLS/Reality stage, so
     *  a fleet can read fully reachable while carrying nobody — which is
     *  exactly why the relay share falling from 68% to 20% could not be
     *  explained from probes alone. Session already works this out for its own
     *  routing; this is where it is left for the next report to carry.
     *
     *  In memory on purpose: it describes THIS run's network, and a stale value
     *  restored from disk on a different network would be a lie. */
    @Volatile
    private var lastOutcome: String? = null

    /** Called by Session once the route is settled. One of the closed set the
     *  island accepts (broker.py `_TRANSPORT_OUTCOMES`); anything else is
     *  dropped there, so keep the two in step. */
    fun noteTransportOutcome(outcome: String) {
        lastOutcome = outcome
    }
    private const val PROBE_TIMEOUT_MS = 2500
    private const val MAX_PROBE = 20
    private val gson = Gson()
    private val jsonMedia = "application/json".toMediaType()
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun isReady() = ::prefs.isInitialized

    /** The paid access key, or null. */
    fun tenantKey(): String? =
        if (!isReady()) null else prefs.getString(KEY_TENANT, null)?.takeIf { it.isNotBlank() }

    /** Store (or clear, with null) the paid access key and pull the endpoints it
     *  unlocks right away — a key that only takes effect at the next boot looks
     *  broken to the person who just pasted it. */
    fun setTenantKey(key: String?) {
        if (!isReady()) return
        val clean = key?.trim()?.takeIf { it.isNotEmpty() }
        val edit = prefs.edit().putString(KEY_TENANT, clean)
        // A verdict belongs to the key it was given for. Left in place, an
        // earlier "ok" was read back as THIS key's answer whenever the refresh
        // that follows failed to reach the island, so a mistyped key pasted
        // over a working one looked accepted.
        edit.remove(KEY_VERDICT)
        // Clearing the key drops the paid endpoints with it (as the desktop
        // does): keeping them would go on routing through nodes the person no
        // longer holds a key for, which is the state "remove" is meant to end.
        if (clean == null) {
            // The endpoints themselves too, not only their tags: the transport
            // builds its pool from relays(), and a paid node left in that list
            // would keep carrying traffic with nothing marking it as paid.
            val paid = privateTags()
            if (paid.isNotEmpty()) edit.putString(KEY, gson.toJson(relays().filterNot { it.tag in paid }))
            edit.remove(KEY_PRIVATE)
        }
        edit.apply()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY)   // direct: the transport isn't up yet, and a blocked fetch just falls back
        .build()

    /** What the broker made of the key we last sent: null (none sent, or never
     *  asked), else one of [BrokerVerdict]: "ok", "unknown", "expired", or
     *  "offline" when the island could not be asked (the key stays). */
    fun keyVerdict(): String? = if (!isReady()) null else prefs.getString(KEY_VERDICT, null)

    /** Tags of the endpoints this account pays for. */
    fun privateTags(): Set<String> =
        if (!isReady()) emptySet() else prefs.getStringSet(KEY_PRIVATE, emptySet()) ?: emptySet()

    /** The paid endpoints alone. */
    fun privateRelays(): List<SingBoxTransport.Relay> =
        privateTags().let { tags -> relays().filter { it.tag in tags } }

    fun relays(): List<SingBoxTransport.Relay> = if (!isReady()) emptyList() else runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        gson.fromJson<List<SingBoxTransport.Relay>>(raw, object : TypeToken<List<SingBoxTransport.Relay>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    /** The cached broker relays marked trusted — onion-entry eligible. */
    fun trustedRelays(): List<SingBoxTransport.Relay> {
        if (!isReady()) return emptyList()
        val tags = prefs.getStringSet(KEY_TRUSTED, emptySet()) ?: emptySet()
        if (tags.isEmpty()) return emptyList()
        return relays().filter { it.tag in tags }
    }

    fun count(): Int = relays().size

    /** One round trip to the broker: the HTTP status and body, or null when
     *  the request never completed (no network, timeout, a thrown exception). */
    private fun ask(key: String?): Pair<Int, String?>? = runCatching {
        // Through the tunnel when it's up: a BLOCKED user can't reach
        // api.rcq.app directly, so without this they NEVER receive broker
        // bridges (incl. the community relays operators raise). Once a bundled
        // relay carries the tunnel, the fetch rides it. Tradeoff: the broker
        // then buckets by the relay IP, not the user IP (weaker anti-enum) —
        // acceptable, since some bridges beats none. Unblocked: direct, per
        // the NO_PROXY base client.
        val fetchClient = SingBoxTransport.proxy()?.let { client.newBuilder().proxy(it).build() } ?: client
        // The paid key, when there is one, rides in Authorization — the
        // broker adds that tenant's private endpoints to the ordinary
        // answer. A header rather than a query parameter because proxies
        // redact this one, and a relay key in an access log is the same
        // mistake as a session token in one.
        val req = Request.Builder().url("https://$BROKER_HOST/broker/bridges?n=$WANT").get()
        key?.let { req.header("Authorization", "Bearer $it") }
        fetchClient.newCall(req.build()).execute().use { resp -> resp.code to resp.body?.string() }
    }.getOrNull()

    /** Best-effort: pull a few bridges from the broker + cache them. On any
     *  network failure the relays we had are kept. Call off the main thread.
     *
     *  ⚠ A failure is not silent about the KEY any more. It used to return
     *  before writing anything, so whoever read [keyVerdict] next saw whatever
     *  the previous ask had left: Settings read a stale "ok" as this key's
     *  answer, or a null as "not one of ours" and deleted a good key because
     *  the network was down. Now a key that could not be checked is marked
     *  [BrokerVerdict.OFFLINE] and kept. */
    fun refresh() {
        if (!isReady()) return
        val key = tenantKey()
        val answer = ask(key)
        val status = answer?.first
        val body = answer?.second?.takeIf { status != null && status in 200..299 }
        val verdict = BrokerVerdict.ofResponse(status, body, keySent = key != null)
        if (body == null || verdict == BrokerVerdict.OFFLINE) {
            // The island was not reached, or answered with something that is
            // not about relays (a 5xx, the 30-a-minute rate limit as a 429).
            // Relays untouched; only the key's verdict is written, and only
            // when there is a key for it to be about.
            if (key != null) prefs.edit().putString(KEY_VERDICT, verdict).apply()
            return
        }
        runCatching {
            val root = JsonParser.parseString(body).asJsonObject
            val arr = root.getAsJsonArray("relays") ?: return
            val out = ArrayList<SingBoxTransport.Relay>()
            val trusted = HashSet<String>()
            val private = HashSet<String>()
            for (el in arr) {
                val obj = el.asJsonObject
                val r = ContactRelayStore.relayFromJson(obj) ?: continue
                // Collision-proof tag, independent of the descriptor's label.
                val tag = "broker-${r.server.replace(Regex("[^A-Za-z0-9]"), "-")}-${r.port}"
                out.add(r.copy(tag = tag))
                if (runCatching { obj.get("tier")?.asString }.getOrNull() == "trusted") trusted.add(tag)
                // Nodes this account PAID for. The broker started saying which
                // ones they are because without it a bought node was just
                // another entry in the same latency race as the fourteen
                // everybody gets, and lost it about as often as it won.
                if (runCatching { obj.get("private")?.asBoolean }.getOrNull() == true) private.add(tag)
            }
            // Replace the cache with the freshest set (empty list = broker had
            // none). The verdict is the island's word on the key: null when
            // we sent none, else ok | unknown | expired. Before this existed
            // a mistyped key and a working one produced the same answer, so
            // the app reported both as accepted.
            prefs.edit()
                .putString(KEY, gson.toJson(out))
                .putStringSet(KEY_TRUSTED, trusted)
                .putStringSet(KEY_PRIVATE, private)
                .putString(KEY_VERDICT, verdict)
                .apply()
        }
    }

    /** Report which known relays are reachable FROM THIS NETWORK so the broker can
     *  serve them region-by-region (POST /broker/reachability). Probes the relays
     *  the client knows (signed-config + shared + broker) with a DIRECT TCP connect
     *  — that IS the reachability measurement — then posts the ok/fail verdicts
     *  THROUGH the tunnel when it's up (a blocked user can't reach the flagship
     *  direct). Best-effort, throttled hourly, off the main thread.
     *
     *  SKIPPED under a user local proxy (Tor/I2P): a direct probe to relay IPs
     *  would bypass the proxy and leak the real IP — the Tor-leak rule. */
    fun reportReachability() {
        if (!isReady() || SingBoxTransport.localProxyMode()) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - prefs.getLong(KEY_REPORT_TS, 0L) < REPORT_INTERVAL_MS) return
        runCatching {
            val seen = HashSet<String>()
            val targets = (RelayConfigStore.currentRelays() + ContactRelayStore.relays() + relays())
                .filter { it.server.isNotBlank() && it.port in 1..65535 && seen.add("${it.server}:${it.port}") }
                .take(MAX_PROBE)
            if (targets.isEmpty()) return
            // Direct TCP reachability probes, in parallel + time-bounded.
            val pool = Executors.newFixedThreadPool(minOf(6, targets.size))
            val verdicts = try {
                pool.invokeAll(
                    targets.map { r ->
                        java.util.concurrent.Callable {
                            val ok = runCatching {
                                Socket().use { it.connect(InetSocketAddress(r.server, r.port), PROBE_TIMEOUT_MS); true }
                            }.getOrDefault(false)
                            Triple(r.server, r.port, ok)
                        }
                    },
                    (PROBE_TIMEOUT_MS * 2).toLong(), TimeUnit.MILLISECONDS,
                )
            } finally {
                pool.shutdownNow()
            }
            val reports = JsonArray()
            for (f in verdicts) {
                val v = runCatching { f.get() }.getOrNull() ?: continue
                reports.add(JsonObject().apply {
                    addProperty("server", v.first); addProperty("port", v.second); addProperty("ok", v.third)
                })
            }
            if (reports.size() == 0) return
            val payload = JsonObject().apply {
                add("reports", reports)
                // What the route actually did, if this run has settled one yet.
                lastOutcome?.let { addProperty("transport", it) }
            }
            val postClient = SingBoxTransport.proxy()?.let { client.newBuilder().proxy(it).build() } ?: client
            postClient.newCall(
                Request.Builder().url("https://$BROKER_HOST/broker/reachability")
                    .post(payload.toString().toRequestBody(jsonMedia)).build(),
            ).execute().use { /* best-effort: success just stamps the throttle below */ }
            prefs.edit().putLong(KEY_REPORT_TS, nowMs).apply()
        }
    }
}
