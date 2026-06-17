package app.rcq.android.net

import android.content.Context
import android.util.Base64
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Signed remote relay list — so a burned relay can be swapped without an app
 * update (iOS RelayConfigStore parity). Fetches a JSON payload from
 * hard-to-block mirrors, verifies an Ed25519 signature against the embedded
 * public key, caches the verified payload to disk, and falls back through
 * memory -> disk -> a bundled-static pool (a fresh install with every mirror
 * blocked still has a working list).
 *
 * The payload is `{version, issued_at, relays:[...], sig}`. The signature
 * covers the canonical JSON of everything EXCEPT `sig` — sorted keys
 * (recursively), compact separators, no slash/unicode escaping — byte-for-byte
 * matching the Python signer (`tools/sign-relay-config.py`) and the iOS
 * verifier (`JSONSerialization [.sortedKeys, .withoutEscapingSlashes]`).
 */
object RelayConfigStore {
    // Raw 32-byte Ed25519 public key (base64), same key embedded in iOS.
    private const val PUBKEY_B64 = "TY834OFcBvtUqHcnVw/QrPBOaEAZo7a1GAmABMhjkT8="

    // Tried in order; first signature-valid payload wins. GitHub raw is primary
    // (hit far less by RU DPI than Cloudflare); CF is the secondary mirror.
    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/rcq-messenger/rcq-ios/main/relay-config.json",
        "https://relay.rcq.app/v1/config",
    )
    private const val CACHE_FILE = "relay-config.json"

    @Volatile
    private var cached: List<SingBoxTransport.Relay>? = null

    /** Version of the last verified remote payload (null = never fetched a
     *  valid one this process; the list in use is then disk/bundled). For the
     *  diagnostics screen. */
    @Volatile
    var version: Int? = null
        private set

    /** Onion routing policy from the signed config (`onion.enabled`, O3). When
     *  true AND ≥2 VLESS relays exist, [SingBoxTransport] builds a 2-hop chain
     *  (M3 metadata resistance, RCQ/docs/onion-design.md). Default OFF — only a
     *  signature-valid payload that explicitly sets it can flip onion on, so
     *  rollout is a signed-config push to a cohort, ZERO app release. */
    @Volatile
    var onionEnabled: Boolean = false
        private set

    /** True when [currentRelays] is serving a verified remote list (in memory),
     *  false when it's the bundled fallback. Diagnostics only. */
    fun usingRemote(): Boolean = cached != null

    fun relayCount(): Int = currentRelays().size

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    /** Bundled relay pool — mirrors the live signed config (v13). The last-resort
     *  fallback when no verified remote/disk list is available — CRITICAL for a
     *  blocked user: both signed-config mirrors (github raw, Cloudflare relay.rcq.app)
     *  and the broker (api.rcq.app, fetched direct) are themselves DPI-blockable, so
     *  a censored fresh install may ONLY ever see this list. So it leads with the
     *  DOMESTIC (RU) relay — a domestic IP is far costlier for a censor to block
     *  (collateral) than the foreign cloud relays below. Keep in sync with the
     *  signed config (relay-aws-sg 47.129.249.170 was retired in v13). */
    private val bundled = listOf(
        SingBoxTransport.Relay("relay-msk-aeza", "vless", "45.151.101.221", 443, "www.yandex.ru", uuid = "9c7174e7-2cb9-4d03-bffb-259bd534b65b", publicKey = "ord-QgtxD57vOVLMsXwGC6Qj7kaK4kb8Tq3MxImQch4", shortId = "5d88ef2912b4fa39", flow = "xtls-rprx-vision"),
        SingBoxTransport.Relay("relay-do-fra-yandex-hy2", "hysteria2", "165.22.90.214", 443, "www.yandex.ru", password = "JN0qzA4LJfhHPKKN3QHj4eN8", obfsPassword = "jXfGkLToOkTihpeJzDiNf8Bb"),
        SingBoxTransport.Relay("relay-do-fra-yandex", "vless", "165.22.90.214", 443, "www.yandex.ru", uuid = "2081b3c4-faaa-4cce-a0ab-607197b28237", publicKey = "n33TZTLNrc6X7jTGrKWex_sk8aIQ6Qqz-eC8lqYMii8", shortId = "aa5d483441e59ac7", flow = "xtls-rprx-vision"),
        SingBoxTransport.Relay("relay-oracle-il-hy2", "hysteria2", "129.159.143.135", 443, "www.microsoft.com", password = "bvuvu74CVsiXdcJazcYphnO5", obfsPassword = "PaEHrZABTk36orhfFON7Jure"),
        SingBoxTransport.Relay("relay-oracle-il", "vless", "129.159.143.135", 443, "www.microsoft.com", uuid = "ff005e0c-175e-4475-a166-eeac88f514e2", publicKey = "_Hhc-2pjkvR914mddMdmuoOVaT74vWR8Gby7KmJp9F8", shortId = "318567678ac9878e", flow = "xtls-rprx-vision"),
        SingBoxTransport.Relay("relay-gcp-hy2", "hysteria2", "35.238.53.96", 443, "www.apple.com", password = "QaY3uT8EmfZxfON65jaT5bSu", obfsPassword = "fLpJ2c211xjnZcP9VNcNpbZP"),
        SingBoxTransport.Relay("relay-gcp", "vless", "35.238.53.96", 443, "www.apple.com", uuid = "8e3b35d3-18a6-406d-9ac6-c5558a806663", publicKey = "mQZ8CJeMWyf7oYGWJG8oOI52or2kx4yTthl6AGZkSTw", shortId = "b5b8979af1f27aab", flow = "xtls-rprx-vision"),
    )

    /** The relay list to use right now: verified remote (in memory) -> bundled.
     *  Synchronous + allocation-free for [SingBoxTransport.buildConfig]. */
    fun currentRelays(): List<SingBoxTransport.Relay> = cached?.takeIf { it.isNotEmpty() } ?: bundled

    /** Load the last verified payload off disk into memory (fast, call at boot
     *  before building the transport so it uses the freshest known list). */
    fun prime(ctx: Context) {
        if (cached != null) return
        runCatching {
            val f = File(ctx.filesDir, CACHE_FILE)
            if (f.exists()) verifyAndParse(f.readText())?.let { cached = it }
        }
    }

    /** Fetch a fresh list from the mirrors (direct — the transport isn't up yet
     *  and we need relays to build it). First signature-valid payload wins;
     *  persisted to disk + memory for the next launch. Best-effort: on a
     *  blocked network this fails and we keep using disk/bundled. */
    suspend fun refresh(ctx: Context) = withContext(Dispatchers.IO) {
        // Route through the tunnel when it's up. A BLOCKED user can't reach the
        // mirrors directly (github raw + Cloudflare relay.rcq.app are both
        // DPI-throttled in RU), so before this they could NEVER pull a fresh list
        // and were stuck on the bundled pool. Once a bundled relay carries the
        // tunnel, the fetch rides it and picks up the domestic relay + rotations
        // for next launch. Unblocked users (tunnel off) fetch direct as before;
        // the signed config is PUBLIC, so tunnelling it leaks nothing.
        val fetchClient = SingBoxTransport.proxy()?.let { client.newBuilder().proxy(it).build() } ?: client
        for (url in SOURCES) {
            val body = runCatching {
                fetchClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }.getOrNull() ?: continue
            val relays = verifyAndParse(body) ?: continue
            cached = relays
            runCatching { File(ctx.filesDir, CACHE_FILE).writeText(body) }
            break
        }
    }

    /** Verify the Ed25519 signature, then parse relays (sorted by priority).
     *  Returns null on any failure — a bad signature is treated as no list. */
    fun verifyAndParse(text: String): List<SingBoxTransport.Relay>? = runCatching {
        val root = JsonParser.parseString(text).asJsonObject
        val sigB64 = root.get("sig")?.asString ?: return null
        // Sign over everything except `sig`.
        val signed = root.deepCopy()
        signed.remove("sig")
        val message = canonical(signed).toByteArray(Charsets.UTF_8)
        val pub = Ed25519PublicKeyParameters(Base64.decode(PUBKEY_B64, Base64.DEFAULT), 0)
        val signer = Ed25519Signer().apply { init(false, pub); update(message, 0, message.size) }
        if (!signer.verifySignature(Base64.decode(sigB64, Base64.DEFAULT))) return null

        root.get("version")?.takeIf { !it.isJsonNull }?.let { version = runCatching { it.asInt }.getOrNull() }
        // Optional onion policy (O3): `{"onion":{"enabled":true}}`. Absent → off.
        onionEnabled = runCatching {
            root.getAsJsonObject("onion")?.get("enabled")?.asBoolean ?: false
        }.getOrDefault(false)
        val out = ArrayList<Pair<Int, SingBoxTransport.Relay>>()
        for (el in root.getAsJsonArray("relays")) {
            val o = el.asJsonObject
            fun s(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString
            val tag = s("tag") ?: continue
            val proto = s("proto") ?: "vless"
            val server = s("server") ?: continue
            val port = o.get("port")?.asInt ?: continue
            val sni = s("sni") ?: continue
            val priority = o.get("priority")?.asInt ?: 100
            out.add(priority to SingBoxTransport.Relay(
                tag = tag, proto = proto, server = server, port = port, sni = sni,
                uuid = s("uuid"), publicKey = s("public_key"), shortId = s("short_id"),
                flow = s("flow"), password = s("password"), obfsPassword = s("obfs_password"),
            ))
        }
        out.sortBy { it.first }
        out.map { it.second }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Canonical JSON byte-for-byte matching Python `json.dumps(sort_keys=True,
     *  separators=(",",":"), ensure_ascii=False)` and iOS JSONSerialization
     *  `[.sortedKeys, .withoutEscapingSlashes]`: recursively sorted keys,
     *  compact, numbers as their source text, slashes + non-ASCII unescaped. */
    private fun canonical(e: JsonElement): String = StringBuilder().also { write(e, it) }.toString()

    private fun write(e: JsonElement, sb: StringBuilder) {
        when {
            e.isJsonObject -> {
                sb.append('{')
                e.asJsonObject.keySet().sorted().forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    writeString(k, sb); sb.append(':'); write(e.asJsonObject.get(k), sb)
                }
                sb.append('}')
            }
            e.isJsonArray -> {
                sb.append('[')
                e.asJsonArray.forEachIndexed { i, el -> if (i > 0) sb.append(','); write(el, sb) }
                sb.append(']')
            }
            e.isJsonNull -> sb.append("null")
            else -> {
                val p = e.asJsonPrimitive
                when {
                    p.isString -> writeString(p.asString, sb)
                    p.isBoolean -> sb.append(if (p.asBoolean) "true" else "false")
                    else -> sb.append(p.asString) // number: source text (no .0, no exponent)
                }
            }
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }
}
