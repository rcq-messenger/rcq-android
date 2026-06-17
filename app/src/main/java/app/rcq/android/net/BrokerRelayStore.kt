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
    private const val REPORT_INTERVAL_MS = 60 * 60 * 1000L  // at most hourly
    private const val PROBE_TIMEOUT_MS = 2500
    private const val MAX_PROBE = 20
    private val gson = Gson()
    private val jsonMedia = "application/json".toMediaType()
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun isReady() = ::prefs.isInitialized

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY)   // direct: the transport isn't up yet, and a blocked fetch just falls back
        .build()

    /** Relays for the transport pool (cached from the last successful fetch). */
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

    /** Best-effort: pull a few bridges from the broker + cache them. No-op on any
     *  network/parse failure (we keep whatever we had). Call off the main thread. */
    fun refresh() {
        if (!isReady()) return
        runCatching {
            // Through the tunnel when it's up: a BLOCKED user can't reach
            // api.rcq.app directly, so without this they NEVER receive broker
            // bridges (incl. the community relays operators raise). Once a bundled
            // relay carries the tunnel, the fetch rides it. Tradeoff: the broker
            // then buckets by the relay IP, not the user IP (weaker anti-enum) —
            // acceptable, since some bridges beats none. Unblocked: direct, per
            // the NO_PROXY base client.
            val fetchClient = SingBoxTransport.proxy()?.let { client.newBuilder().proxy(it).build() } ?: client
            val body = fetchClient.newCall(
                Request.Builder().url("https://$BROKER_HOST/broker/bridges?n=$WANT").get().build(),
            ).execute().use { resp -> if (resp.isSuccessful) resp.body?.string() else null } ?: return
            val arr = JsonParser.parseString(body).asJsonObject.getAsJsonArray("relays") ?: return
            val out = ArrayList<SingBoxTransport.Relay>()
            val trusted = HashSet<String>()
            for (el in arr) {
                val obj = el.asJsonObject
                val r = ContactRelayStore.relayFromJson(obj) ?: continue
                // Collision-proof tag, independent of the descriptor's label.
                val tag = "broker-${r.server.replace(Regex("[^A-Za-z0-9]"), "-")}-${r.port}"
                out.add(r.copy(tag = tag))
                if (runCatching { obj.get("tier")?.asString }.getOrNull() == "trusted") trusted.add(tag)
            }
            // Replace the cache with the freshest set (empty list = broker had none).
            prefs.edit()
                .putString(KEY, gson.toJson(out))
                .putStringSet(KEY_TRUSTED, trusted)
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
            val payload = JsonObject().apply { add("reports", reports) }
            val postClient = SingBoxTransport.proxy()?.let { client.newBuilder().proxy(it).build() } ?: client
            postClient.newCall(
                Request.Builder().url("https://$BROKER_HOST/broker/reachability")
                    .post(payload.toString().toRequestBody(jsonMedia)).build(),
            ).execute().use { /* best-effort: success just stamps the throttle below */ }
            prefs.edit().putLong(KEY_REPORT_TS, nowMs).apply()
        }
    }
}
