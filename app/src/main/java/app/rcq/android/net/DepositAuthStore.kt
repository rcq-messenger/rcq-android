package app.rcq.android.net

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * F3 deposit-auth client — mints + caches anonymous blinded deposit tokens
 * ([BlindToken]) to attach to cross-island sealed deposits, so the recipient
 * island can rate-limit us WITHOUT learning who we are. See
 * `RCQ/docs/deposit-auth-design.md` + `rcq-server-ref app/routers/deposit_auth.py`.
 *
 * Flow per island host: GET /deposit-auth/params (epoch pubkey + PoW difficulty);
 * prepare a random token, blind it, solve the SHA-256 hashcash bound to the blinded
 * value, POST /deposit-auth/issue, unblind -> a token. Tokens are minted a small
 * BATCH at a time (amortise the PoW) into an in-memory reserve and popped on send.
 * Best-effort + self-gating: an island without deposit-auth (404 on /params) is
 * remembered and skipped, and a mint failure just returns null (the deposit then
 * rides the legacy per-IP path — additive, never blocks a send).
 *
 * In-memory only (no persistence): a reserve is cheap to re-mint and we never want
 * tokens surviving uninstall. Blocking (PoW + HTTP) — call off the main thread; the
 * cross-island send path already runs on Dispatchers.IO.
 */
object DepositAuthStore {
    private const val BATCH = 4
    private val JSON = "application/json".toMediaType()

    private class Params(val epochId: String, val n: BigInteger, val e: BigInteger, val difficulty: Int)

    private val params = ConcurrentHashMap<String, Params>()         // host -> cached params
    private val disabled = ConcurrentHashMap.newKeySet<String>()     // hosts without deposit-auth
    private val reserve = ConcurrentHashMap<String, ArrayDeque<JsonObject>>()  // host -> spare tokens

    /** A `deposit_token` object `{epoch_id, prepared, sig}` for [host], or null when
     *  the island doesn't offer deposit-auth or minting failed. */
    fun tokenFor(host: String, http: OkHttpClient): JsonObject? {
        if (host in disabled) return null
        reserve[host]?.let { dq -> synchronized(dq) { if (dq.isNotEmpty()) return dq.removeFirst() } }
        val minted = mintBatch(host, http)
        if (minted.isNullOrEmpty()) return null
        if (minted.size > 1) {
            val dq = reserve.getOrPut(host) { ArrayDeque() }
            synchronized(dq) { dq.addAll(minted.subList(1, minted.size)) }
        }
        return minted.first()
    }

    private fun mintBatch(host: String, http: OkHttpClient): List<JsonObject>? {
        val p = ensureParams(host, http) ?: return null
        val out = ArrayList<JsonObject>(BATCH)
        repeat(BATCH) {
            val tok = mintOne(host, p, http) ?: return out.ifEmpty { null }
            out.add(tok)
        }
        return out
    }

    private fun ensureParams(host: String, http: OkHttpClient): Params? {
        params[host]?.let { return it }
        val req = Request.Builder().url("https://$host/deposit-auth/params").get().build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) { disabled.add(host); return null }   // island doesn't offer it
                if (!resp.isSuccessful) return null
                val o = JsonParser.parseString(resp.body?.string() ?: return null).asJsonObject
                val pk = o.getAsJsonObject("pubkey")
                val n = BigInteger(1, Base64.decode(pk.get("n").asString, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                val parsed = Params(
                    epochId = o.get("epoch_id").asString,
                    n = n,
                    e = BigInteger(pk.get("e").asLong.toString()),
                    difficulty = o.getAsJsonObject("pow").get("difficulty").asInt,
                )
                params[host] = parsed
                parsed
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun mintOne(host: String, p: Params, http: OkHttpClient): JsonObject? {
        val prepared = BlindToken.prepare()
        val b = BlindToken.blind(p.n, p.e, prepared)
        val blindedB64 = Base64.encodeToString(b.blinded, Base64.NO_WRAP)
        val nonce = BlindToken.solvePow("${p.epochId}:$blindedB64", p.difficulty)
        val reqBody = JsonObject().apply {
            addProperty("epoch_id", p.epochId)
            addProperty("blinded", blindedB64)
            addProperty("pow_nonce", nonce)
        }.toString().toRequestBody(JSON)
        val req = Request.Builder().url("https://$host/deposit-auth/issue").post(reqBody).build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 409) { params.remove(host); return null }   // epoch rotated — drop cache, retry later
                if (!resp.isSuccessful) return null
                val o = JsonParser.parseString(resp.body?.string() ?: return null).asJsonObject
                val sig = BlindToken.finalize(Base64.decode(o.get("blind_sig").asString, Base64.NO_WRAP), b.blindInv, p.n)
                JsonObject().apply {
                    addProperty("epoch_id", p.epochId)
                    addProperty("prepared", Base64.encodeToString(prepared, Base64.NO_WRAP))
                    addProperty("sig", Base64.encodeToString(sig, Base64.NO_WRAP))
                }
            }
        } catch (t: Throwable) {
            null
        }
    }
}
