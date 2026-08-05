package app.rcq.android.net

import android.content.Context
import android.util.Base64
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
    // Which keys may sign this payload lives in [SigningKeys]; it is a SET, so
    // the signing key can be changed without a release. See that file for why
    // the set is compiled in rather than carried by the payload itself.

    // The two mirrors compiled into the app. Tried in order; first
    // signature-valid payload wins. GitHub raw is primary (hit far less by RU
    // DPI than Cloudflare); CF is the secondary mirror.
    //
    // ⚠ These two names are also the whole attack surface of the delivery
    // channel: a censor who blocks both leaves a client with nothing but the
    // bundled pool and a hand-pasted token. That is what [remoteSources] is
    // for — see [effectiveSources].
    private val BUNDLED_SOURCES = listOf(
        Source("https", "https://raw.githubusercontent.com/rcq-messenger/rcq-ios/main/relay-config.json"),
        Source("https", "https://relay.rcq.app/v1/config"),
    )

    /** Where a payload can be read from. `https` is a mirror URL; `dns-txt` is a
     *  name whose TXT record carries a signed seed, read over DoH — a channel
     *  that survives both mirror names being blocked, since it rides resolvers
     *  half the internet needs to stay up. */
    data class Source(val kind: String, val value: String)

    /** DoH endpoints, tried in order. RFC 8484 wire format rather than any
     *  resolver's JSON API, because the JSON one is Cloudflare's and Google's
     *  alone — and those two are the most likely to be unreachable exactly where
     *  this channel matters. The domestic resolver is in the list for the same
     *  reason: a resolver cannot forge a signed payload, so asking one that
     *  answers beats insisting on one that does not. */
    private val DOH_RESOLVERS = listOf(
        "https://cloudflare-dns.com/dns-query",
        "https://common.dns.yandex.net/dns-query",
        "https://dns.google/dns-query",
    )

    /** Extra mirrors carried BY the signed config, so a new delivery channel
     *  (a domain bought at another registrar, a mirror somewhere expensive to
     *  block wholesale) reaches installed clients without an app release.
     *
     *  Null until a signature-valid payload has been parsed. */
    @Volatile
    private var remoteSources: List<Source>? = null

    /** How many mirrors we are willing to walk in one refresh. A refresh runs
     *  before the transport is up, so each dead entry costs its full timeout;
     *  a payload listing fifty of them would turn startup into a stall. */
    private const val MAX_SOURCES = 8

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

    /** Bundled relay pool — a copy of the live signed config, last synced with
     *  **v131 (2026-08-05)**.
     *
     *  This is the last-resort fallback when no verified remote/disk list is
     *  available, and it is CRITICAL for a blocked user: both signed-config
     *  mirrors (github raw, Cloudflare relay.rcq.app) and the broker
     *  (api.rcq.app, fetched direct) are themselves DPI-blockable, so a
     *  censored fresh install may ONLY ever see this list.
     *
     *  ⚠ Which is exactly why it must not rot. Until 2026-08-05 it still led
     *  with the domestic RU relay 45.151.101.221, retired from the signed
     *  config on 06-18 and dead since — so the first thing a censored client
     *  did was dial a corpse. The oracle VLESS entry also carried the wrong
     *  SNI (www.microsoft.com where the signed config says www.apple.com),
     *  which is the failure that once broke VLESS while Hysteria2 kept
     *  working on the same box.
     *
     *  ⚠ The SNI has to be plausible for the ADDRESS, not just innocuous on its
     *  own. This pair presented `www.yandex.ru` from a DigitalOcean address
     *  until 2026-08-05: Yandex is never served out of DigitalOcean, so a
     *  passive observer finds every one of our connections with a single join
     *  of SNI against the address owner, and Reality's resistance to ACTIVE
     *  probing does nothing about it. It now presents a name that genuinely
     *  lives on the same ASN and in the same region as the relay. Oracle and
     *  GCP still carry Akamai-hosted names for want of admin access to those
     *  two machines.
     *
     *  Keep in step with the signed config: `curl -s
     *  https://relay.rcq.app/v1/config` and compare tag / server / sni / keys.
     *  Order mirrors the signed priorities (Hysteria2 first on each host). */
    private val bundled = listOf(
        SingBoxTransport.Relay("relay-do-fra-spaces-hy2", "hysteria2", "165.22.90.214", 443, "fra1.digitaloceanspaces.com", password = "JN0qzA4LJfhHPKKN3QHj4eN8", obfsPassword = "jXfGkLToOkTihpeJzDiNf8Bb"),
        SingBoxTransport.Relay("relay-do-fra-spaces", "vless", "165.22.90.214", 443, "fra1.digitaloceanspaces.com", uuid = "2081b3c4-faaa-4cce-a0ab-607197b28237", publicKey = "n33TZTLNrc6X7jTGrKWex_sk8aIQ6Qqz-eC8lqYMii8", shortId = "aa5d483441e59ac7", flow = "xtls-rprx-vision"),
        SingBoxTransport.Relay("relay-oracle-il-hy2", "hysteria2", "129.159.143.135", 443, "www.microsoft.com", password = "bvuvu74CVsiXdcJazcYphnO5", obfsPassword = "PaEHrZABTk36orhfFON7Jure"),
        SingBoxTransport.Relay("relay-oracle-il", "vless", "129.159.143.135", 443, "www.apple.com", uuid = "ff005e0c-175e-4475-a166-eeac88f514e2", publicKey = "_Hhc-2pjkvR914mddMdmuoOVaT74vWR8Gby7KmJp9F8", shortId = "318567678ac9878e", flow = "xtls-rprx-vision"),
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
        for (source in effectiveSources()) {
            val body = when (source.kind) {
                "https" -> runCatching {
                    fetchClient.newCall(Request.Builder().url(source.value).get().build()).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.string() else null
                    }
                }.getOrNull()
                "dns-txt" -> runCatching {
                    DnsTxt.fetch(source.value, DOH_RESOLVERS, fetchClient)
                        ?.let { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }
                }.getOrNull()
                else -> null
            } ?: continue
            // The floor is whatever we already trust, so a mirror serving a stale
            // but genuinely signed payload cannot move us backwards.
            val relays = verifyAndParse(body, minVersion = version) ?: continue
            cached = relays
            runCatching { File(ctx.filesDir, CACHE_FILE).writeText(body) }
            break
        }
    }

    /** The mirrors to walk, freshest knowledge first: the ones the signed
     *  config named, then the compiled-in pair.
     *
     *  ★ The bundled pair is ALWAYS appended and never replaced. A published
     *  source list is an ADDITION, not a substitution — otherwise one bad push
     *  (a typo'd host, a domain that lapses) would point every installed
     *  client at a dead mirror with no route back, and no later push could
     *  reach them to fix it. Being additive means the worst a bad entry costs
     *  is one timeout before the known-good names are tried.
     *
     *  Config entries lead because they are the reason this exists: the two
     *  bundled names are exactly what a censor enumerates and blocks first, so
     *  on the network that needs them the new mirror is the one likely to
     *  answer. */
    fun effectiveSources(): List<Source> {
        val remote = remoteSources ?: return BUNDLED_SOURCES
        return (remote + BUNDLED_SOURCES).distinct().take(MAX_SOURCES)
    }

    /** Parse the optional `sources` array: `[{"type":"https","url":"..."}]`.
     *  Unknown types are skipped rather than rejected, so a payload that adds a
     *  channel this build does not implement stays usable for everything else.
     *  Absent or empty → null, i.e. the bundled pair alone. */
    private fun parseSources(root: JsonElement): List<Source>? = runCatching {
        val arr = root.asJsonObject.getAsJsonArray("sources") ?: return null
        arr.mapNotNull { el ->
            val o = el.asJsonObject
            fun s(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString
            when (s("type") ?: "https") {
                "https" -> s("url")?.takeIf { it.startsWith("https://") }?.let { Source("https", it) }
                "dns-txt" -> s("name")?.takeIf { it.isNotBlank() }?.let { Source("dns-txt", it) }
                else -> null   // a channel this build cannot speak; the rest stays usable
            }
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Verify the Ed25519 signature, then parse relays (sorted by priority).
     *  Returns null on any failure — a bad signature is treated as no list.
     *
     *  [minVersion] refuses a payload older than one we already trust. A
     *  signature proves a payload came from us; it says nothing about WHEN.
     *  Anyone who can answer for a mirror, or sit on the path to one, can replay
     *  an OLD signed payload and walk a client back onto a relay set we retired
     *  months ago — no forgery required, just an old truth served late.
     *
     *  Both app updaters were always safe from this because they compare against
     *  what is installed (Android refuses `vc <= current`, and the OS refuses a
     *  downgrade install; the Tauri plugin requires `remote > current`). This
     *  list had no such check on EITHER side until now, and the guard added to
     *  the signer today only stops us doing it to ourselves by accident.
     *
     *  Null means no floor, which is what disk-priming and the tests want.
     *  Recovering from a bad push is done by publishing a HIGHER version
     *  carrying corrected content, never by re-publishing an older number. */
    fun verifyAndParse(text: String, minVersion: Int? = null): List<SingBoxTransport.Relay>? = runCatching {
        val root = JsonParser.parseString(text).asJsonObject
        val sigB64 = root.get("sig")?.asString ?: return null
        // Sign over everything except `sig`.
        val signed = root.deepCopy()
        signed.remove("sig")
        val message = canonical(signed).toByteArray(Charsets.UTF_8)
        if (!SigningKeys.verify(SigningKeys.Role.RELAY_CONFIG, message, sigB64)) return null

        val parsedVersion = root.get("version")?.takeIf { !it.isJsonNull }
            ?.let { runCatching { it.asInt }.getOrNull() }
        if (parsedVersion != null && minVersion != null && parsedVersion < minVersion) return null
        if (parsedVersion != null) version = parsedVersion
        // Optional onion policy (O3): `{"onion":{"enabled":true}}`. Absent → off.
        onionEnabled = runCatching {
            root.getAsJsonObject("onion")?.get("enabled")?.asBoolean ?: false
        }.getOrDefault(false)
        // Only replace the known mirrors when this payload actually carries a
        // list; a config without `sources` must not wipe one an earlier payload
        // published, or a rollback would silently narrow the channel back down
        // to the two compiled-in names.
        parseSources(root)?.let { remoteSources = it }
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
