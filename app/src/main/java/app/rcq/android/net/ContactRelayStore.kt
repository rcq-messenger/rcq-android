package app.rcq.android.net

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

/**
 * In-chat bridge sharing — relays a contact handed you (or you imported by
 * hand) that AUGMENT the signed relay-config pool. See
 * RCQ/docs/bridge-sharing-design.md.
 *
 * Device-level (NOT account-scoped): the transport is per device, and a relay
 * works regardless of which account is active. Persists accepted relays plus who
 * shared each + when. Deduped by `proto:server:port`, capped at [MAX]. Each gets
 * a unique sing-box outbound tag (`shared-<server>-<port>`) so it can never
 * collide with a signed-config tag.
 *
 * [SingBoxTransport.relays] appends [relays] to the verified/​bundled pool, so a
 * shared relay is extra FALLBACK capacity at the back of the priority-sorted
 * list: it never displaces a canary-verified relay, and (pool-order) is never
 * the onion sticky ENTRY. Its only exposure is metadata + DoS (sealed sender +
 * E2E mean it can't read or forge), so accepting one from a trusted contact is
 * safe within the relay trust model.
 */
object ContactRelayStore {

    data class Entry(
        val relay: SingBoxTransport.Relay,
        val fromUin: Int,        // who shared it; 0 = manual import
        val fromName: String?,   // display label for the source
        val addedAt: Long,
    )

    private const val PREFS = "rcq_contact_relays"
    private const val KEY = "relays"
    private const val MAX = 30
    private val gson = Gson()
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun isReady() = ::prefs.isInitialized

    private fun load(): MutableList<Entry> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        gson.fromJson<MutableList<Entry>>(raw, object : TypeToken<MutableList<Entry>>() {}.type) ?: mutableListOf()
    }.getOrDefault(mutableListOf())

    private fun save(list: List<Entry>) = prefs.edit().putString(KEY, gson.toJson(list)).apply()

    private fun key(r: SingBoxTransport.Relay) = "${r.proto}:${r.server}:${r.port}"

    fun list(): List<Entry> = if (isReady()) load().sortedByDescending { it.addedAt } else emptyList()

    /** Relays for the transport pool (just the descriptors). */
    fun relays(): List<SingBoxTransport.Relay> = if (isReady()) load().map { it.relay } else emptyList()

    fun count(): Int = if (isReady()) load().size else 0

    fun has(r: SingBoxTransport.Relay): Boolean = isReady() && load().any { key(it.relay) == key(r) }

    /** Add a shared/imported relay. De-dups by proto:server:port (a re-add
     *  refreshes the source/label), assigns a unique tag, caps the list.
     *  Returns true when something new was stored. */
    fun add(r: SingBoxTransport.Relay, fromUin: Int, fromName: String?): Boolean {
        if (!isReady()) return false
        val tagged = r.copy(tag = "shared-${sanitize(r.server)}-${r.port}")
        val list = load()
        val existingIdx = list.indexOfFirst { key(it.relay) == key(tagged) }
        if (existingIdx >= 0) {
            list[existingIdx] = list[existingIdx].copy(fromUin = fromUin, fromName = fromName)
            save(list)
            return false
        }
        list.add(0, Entry(tagged, fromUin, fromName, System.currentTimeMillis()))
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(list)
        return true
    }

    fun remove(tag: String) {
        if (!isReady()) return
        save(load().filterNot { it.relay.tag == tag })
    }

    private fun sanitize(s: String) = s.replace(Regex("[^A-Za-z0-9]"), "-")

    // ---- Relay <-> wire JSON (the `relay` object inside a relay_share envelope) ----

    /** Serialize a relay to the `relay_share` inner object. Terse keys, shared
     *  byte-for-byte with iOS. Omits empty fields. */
    fun relayToJson(r: SingBoxTransport.Relay): JsonObject = JsonObject().apply {
        addProperty("proto", r.proto)
        addProperty("server", r.server)
        addProperty("port", r.port)
        addProperty("sni", r.sni)
        r.uuid?.takeIf { it.isNotEmpty() }?.let { addProperty("uuid", it) }
        r.publicKey?.takeIf { it.isNotEmpty() }?.let { addProperty("pbk", it) }
        r.shortId?.takeIf { it.isNotEmpty() }?.let { addProperty("sid", it) }
        r.flow?.takeIf { it.isNotEmpty() }?.let { addProperty("flow", it) }
        r.password?.takeIf { it.isNotEmpty() }?.let { addProperty("pw", it) }
        r.obfsPassword?.takeIf { it.isNotEmpty() }?.let { addProperty("obfs", it) }
        r.tag.takeIf { it.isNotEmpty() }?.let { addProperty("label", it) }
    }

    /** Parse a relay from a `relay_share` inner object. Returns null if it lacks
     *  the minimum a transport outbound needs. */
    fun relayFromJson(o: JsonObject): SingBoxTransport.Relay? = runCatching {
        fun s(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString
        val proto = (s("proto") ?: "vless").let { if (it == "hy2") "hysteria2" else it }
        val server = s("server") ?: return null
        val port = o.get("port")?.asInt ?: return null
        val sni = s("sni") ?: return null
        if (proto == "vless" && s("uuid").isNullOrEmpty()) return null
        if (proto == "hysteria2" && s("pw").isNullOrEmpty()) return null
        SingBoxTransport.Relay(
            tag = s("label")?.takeIf { it.isNotEmpty() } ?: "shared-$server-$port",
            proto = proto, server = server, port = port, sni = sni,
            uuid = s("uuid"), publicKey = s("pbk"), shortId = s("sid"), flow = s("flow"),
            password = s("pw"), obfsPassword = s("obfs"),
        )
    }.getOrNull()

    // ---- Text token (rcq-relay://) for manual import / QR / out-of-band ----

    fun relayToToken(r: SingBoxTransport.Relay): String {
        val proto = if (r.proto == "hysteria2") "hy2" else r.proto
        val b = Uri.Builder().scheme("rcq-relay").authority(proto)
        b.appendQueryParameter("s", r.server)
        b.appendQueryParameter("p", r.port.toString())
        b.appendQueryParameter("sni", r.sni)
        r.uuid?.takeIf { it.isNotEmpty() }?.let { b.appendQueryParameter("id", it) }
        r.publicKey?.takeIf { it.isNotEmpty() }?.let { b.appendQueryParameter("pbk", it) }
        r.shortId?.takeIf { it.isNotEmpty() }?.let { b.appendQueryParameter("sid", it) }
        r.flow?.takeIf { it.isNotEmpty() }?.let { b.appendQueryParameter("fl", it) }
        r.password?.takeIf { it.isNotEmpty() }?.let { b.appendQueryParameter("pw", it) }
        r.obfsPassword?.takeIf { it.isNotEmpty() }?.let { b.appendQueryParameter("obfs", it) }
        return b.build().toString()
    }

    /** Parse an `rcq-relay://` token. Returns null on anything malformed. */
    fun relayFromToken(token: String): SingBoxTransport.Relay? = runCatching {
        val u = Uri.parse(token.trim())
        if (u.scheme != "rcq-relay") return null
        val proto = when (u.authority?.lowercase()) {
            "hy2", "hysteria2" -> "hysteria2"
            "vless", null -> "vless"
            else -> return null
        }
        val server = u.getQueryParameter("s") ?: return null
        val port = u.getQueryParameter("p")?.toIntOrNull() ?: return null
        val sni = u.getQueryParameter("sni") ?: return null
        val uuid = u.getQueryParameter("id")
        val pw = u.getQueryParameter("pw")
        if (proto == "vless" && uuid.isNullOrEmpty()) return null
        if (proto == "hysteria2" && pw.isNullOrEmpty()) return null
        SingBoxTransport.Relay(
            tag = "shared-$server-$port", proto = proto, server = server, port = port, sni = sni,
            uuid = uuid, publicKey = u.getQueryParameter("pbk"), shortId = u.getQueryParameter("sid"),
            flow = u.getQueryParameter("fl"), password = pw, obfsPassword = u.getQueryParameter("obfs"),
        )
    }.getOrNull()

    fun parseJsonElement(s: String): JsonObject? = runCatching { JsonParser.parseString(s).asJsonObject }.getOrNull()
}
