package app.rcq.android.net

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
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
    private val gson = Gson()
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
            val body = client.newCall(
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
}
