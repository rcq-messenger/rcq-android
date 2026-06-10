package app.rcq.android.net

import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * RCQ Federation Layer B (F1) — addressing + the self-signed home-island record.
 *
 * Pure functions only. NOTHING here is wired into a live send/receive path yet;
 * this mirrors `web-chat/src/lib/federation.ts`, `iOS .../RcqFederation.swift`,
 * and `docs/federation-protocol.md` byte-for-byte. The canonical JSON reuses the
 * exact scheme already proven byte-equal across Python/iOS/Android in
 * [RelayConfigStore]; the Ed25519 signature uses the same BouncyCastle primitive
 * the v=1 sealed sender already uses ([SealedSender]). Cross-platform record
 * verification was verified on iOS<->web (an Ed25519 signature over the identical
 * canonical bytes verifies on every client).
 *
 * Trust model (spec §2.4): the record is Ed25519-signed by the account's
 * `signing_key` (the v=1 signer). It carries both that key (`sk`) and the
 * libsignal identity key (`ik`, the safety-number key). A verifier checks the
 * signature AND cross-checks `sk`/`ik` against keys it anchored independently
 * (the peer's bundle + safety number). A mailbox or mirror can withhold or serve
 * a stale-but-valid record; it can never forge one.
 */
object RcqFederation {

    const val FLAGSHIP_HOST = "api.rcq.app"

    // ───────────────────────── addressing (spec §1) ─────────────────────────

    data class Address(val uin: Int, val host: String)

    class FedException(msg: String) : Exception(msg)

    private fun isAllDigits(s: String): Boolean =
        s.isNotEmpty() && s.all { it in '0'..'9' }

    private fun isValidHost(s: String): Boolean {
        if (s.isEmpty()) return false
        // reg-name + optional :port; no spaces, scheme, or path.
        return s.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' }
    }

    /** Parse "777" -> flagship handle, "777@host" -> cross-island. Strict: any
     *  other shape throws, so a caller never silently mis-routes. */
    fun parseAddress(input: String): Address {
        val s = input.trim()
        val at = s.indexOf('@')
        if (at >= 0) {
            val uinPart = s.substring(0, at)
            val host = s.substring(at + 1)
            if (!isAllDigits(uinPart)) throw FedException("bad uin")
            if (!isValidHost(host)) throw FedException("bad host")
            return Address(uinPart.toInt(), host.lowercase())
        }
        if (!isAllDigits(s)) throw FedException("not a uin")
        return Address(s.toInt(), FLAGSHIP_HOST)
    }

    /** Display/storage form. The `@api.rcq.app` suffix is hidden by default. */
    fun formatAddress(a: Address, showFlagship: Boolean = false): String =
        if (a.host == FLAGSHIP_HOST && !showFlagship) a.uin.toString() else "${a.uin}@${a.host}"

    fun isFlagship(host: String): Boolean = host.lowercase() == FLAGSHIP_HOST

    // ─────────────────── canonical JSON (spec §2.2) ───────────────────
    // Recursively sorted keys, compact, numbers as source text, slashes +
    // non-ASCII unescaped, UTF-8. Byte-for-byte identical to the relay-config
    // writer in RelayConfigStore (kept in sync deliberately).

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

    private fun canonicalBytes(o: JsonObject): ByteArray = canonical(o).toByteArray(Charsets.UTF_8)

    // ─────────────────── the home-island record (spec §2) ───────────────────

    data class Home(val host: String, val uin: Int)

    /** The exact subset that gets signed, reconstructed explicitly (never
     *  "doc minus sig") so injected fields can't enter the signed bytes. */
    private fun signedPart(ik: String, sk: String, homes: List<Home>, ts: Int): JsonObject {
        val o = JsonObject()
        o.addProperty("v", 1)
        o.addProperty("ik", ik)
        o.addProperty("sk", sk)
        val arr = JsonArray()
        for (h in homes) {
            val ho = JsonObject()
            ho.addProperty("host", h.host)
            ho.addProperty("uin", h.uin)
            arr.add(ho)
        }
        o.add("homes", arr)
        o.addProperty("ts", ts)
        return o
    }

    /** Build and Ed25519-sign a record. [signingPriv] is the account's 32-byte
     *  Ed25519 private (the v=1 signing key); [sk] must be its public base64;
     *  [ik] is base64 of signal_identity_key. */
    fun buildRecord(ik: String, sk: String, signingPriv: ByteArray, homes: List<Home>, ts: Int): JsonObject {
        if (homes.isEmpty()) throw FedException("record needs at least one home")
        val part = signedPart(ik, sk, homes, ts)
        val canon = canonicalBytes(part)
        val signer = Ed25519Signer().apply {
            init(true, Ed25519PrivateKeyParameters(signingPriv, 0))
            update(canon, 0, canon.size)
        }
        val sig = signer.generateSignature()
        part.addProperty("sig", Base64.encodeToString(sig, Base64.NO_WRAP))
        return part
    }

    sealed class VerifyResult {
        data class Ok(val doc: JsonObject) : VerifyResult()
        data class Fail(val reason: String) : VerifyResult()
    }

    /** Verify a record per spec §2.4. The signature check alone proves only
     *  internal consistency; pass expectedSk/expectedIk (anchored from the
     *  peer's bundle + safety number) to bind it to the real peer, and minTs to
     *  reject a rollback to a stale island list. */
    fun verifyRecord(
        doc: JsonObject,
        expectedIk: String? = null,
        expectedSk: String? = null,
        minTs: Int? = null,
    ): VerifyResult {
        if (doc.get("v")?.takeIf { it.isJsonPrimitive }?.asInt != 1) return VerifyResult.Fail("unsupported version")
        val ik = doc.get("ik")?.takeIf { it.isJsonPrimitive }?.asString
        val sk = doc.get("sk")?.takeIf { it.isJsonPrimitive }?.asString
        val sigB64 = doc.get("sig")?.takeIf { it.isJsonPrimitive }?.asString
        if (ik == null || sk == null || sigB64 == null) return VerifyResult.Fail("missing ik/sk/sig")
        val ts = doc.get("ts")?.takeIf { it.isJsonPrimitive }?.asInt
        if (ts == null || ts <= 0) return VerifyResult.Fail("invalid ts")
        val rawHomes = doc.get("homes")?.takeIf { it.isJsonArray }?.asJsonArray
        if (rawHomes == null || rawHomes.size() == 0) return VerifyResult.Fail("missing homes")
        val homes = ArrayList<Home>(rawHomes.size())
        for (el in rawHomes) {
            if (!el.isJsonObject) return VerifyResult.Fail("malformed home entry")
            val ho = el.asJsonObject
            val host = ho.get("host")?.takeIf { it.isJsonPrimitive }?.asString
            val uin = ho.get("uin")?.takeIf { it.isJsonPrimitive }?.asInt
            if (host == null || uin == null) return VerifyResult.Fail("malformed home entry")
            homes.add(Home(host, uin))
        }
        val canon = canonicalBytes(signedPart(ik, sk, homes, ts))
        val ok = try {
            val pub = Ed25519PublicKeyParameters(Base64.decode(sk, Base64.NO_WRAP), 0)
            val sig = Base64.decode(sigB64, Base64.NO_WRAP)
            Ed25519Signer().apply {
                init(false, pub)
                update(canon, 0, canon.size)
            }.verifySignature(sig)
        } catch (_: Exception) {
            return VerifyResult.Fail("bad sk/sig encoding")
        }
        if (!ok) return VerifyResult.Fail("bad signature")
        if (expectedSk != null && sk != expectedSk) return VerifyResult.Fail("sk not anchored")
        if (expectedIk != null && ik != expectedIk) return VerifyResult.Fail("ik not anchored")
        if (minTs != null && ts < minTs) return VerifyResult.Fail("stale ts")
        return VerifyResult.Ok(doc)
    }

    // ─────────────────── contact link / QR (spec §5) ───────────────────
    // Backward compatible: path stays a bare flagship UIN (old clients add the
    // flagship handle); federation data rides in ignored query params
    //   h = host, k = base64url Ed25519 signing_key (sk), i = base64url ik.

    private fun b64ToUrl(b64: String): String =
        b64.replace('+', '-').replace('/', '_').trimEnd('=')

    private fun urlToB64(u: String): String {
        val s = u.replace('-', '+').replace('_', '/')
        val pad = (4 - (s.length % 4)) % 4
        return s + "=".repeat(pad)
    }

    /** Build the contact deep link. Flagship + no keys yields exactly the legacy
     *  `https://rcq.app/u/<uin>`. */
    fun buildContactLink(a: Address, sk: String? = null, ik: String? = null, base: String = "https://rcq.app"): String {
        val params = ArrayList<String>()
        if (!isFlagship(a.host)) params.add("h=${a.host}")
        if (sk != null) params.add("k=${b64ToUrl(sk)}")
        if (ik != null) params.add("i=${b64ToUrl(ik)}")
        val q = params.joinToString("&")
        return "$base/u/${a.uin}" + if (q.isEmpty()) "" else "?$q"
    }

    data class ParsedContactLink(val address: Address, val sk: String?, val ik: String?)

    /** Parse a scanned contact link. [uin] is the path segment; [query] the raw
     *  query string (with or without a leading '?'). */
    fun parseContactLink(uin: String, query: String): ParsedContactLink {
        if (!isAllDigits(uin)) throw FedException("bad uin")
        val q = if (query.startsWith("?")) query.substring(1) else query
        val dict = HashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            if (idx > 0) dict[pair.substring(0, idx)] = pair.substring(idx + 1)
        }
        val host = dict["h"] ?: FLAGSHIP_HOST
        val address = parseAddress("$uin@$host")
        return ParsedContactLink(address, dict["k"]?.let { urlToB64(it) }, dict["i"]?.let { urlToB64(it) })
    }
}
