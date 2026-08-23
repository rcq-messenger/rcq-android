package app.rcq.android.net

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigInteger
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * F3 deposit-auth client: mints + caches anonymous blinded deposit tokens
 * ([BlindToken]) to attach to cross-island sealed deposits, so the recipient
 * island can rate-limit us WITHOUT learning who we are. See
 * `RCQ/docs/deposit-auth-design.md` + `rcq-server-ref app/routers/deposit_auth.py`.
 *
 * Flow per island host: GET /deposit-auth/params (epoch pubkey + PoW difficulty);
 * prepare a random token, blind it, solve the SHA-256 hashcash bound to the blinded
 * value, POST /deposit-auth/issue, unblind -> a token. Tokens live in an in-memory
 * reserve per host that is kept a small BATCH deep: the first token a caller needs
 * is minted inline and handed over the moment it exists, the rest of the batch is
 * minted on a background thread, and a pop that empties the reserve tops it up
 * again. So a send pays one PoW at most, and in the steady state none.
 * Best-effort + self-gating: an island without deposit-auth (404 on /params) is
 * rested for a while and skipped, and a mint failure just returns null (the deposit
 * then rides the legacy per-IP path; additive, never blocks a send).
 *
 * Stage 3 of the core-metadata plan (server 2026.08.23.4) spends the same token
 * on the OWN island: a peer's prekey bundle is fetched with no session token and
 * `X-Deposit-Token` instead, so the island no longer learns whose keys we asked
 * for. See [headerValue], [forget], [giveBack] and [warm] for the pieces that
 * path needs.
 *
 * The mint is single-flight per host (one PoW at a time, foreground or
 * background), and every token carries the epoch it was minted under: a refusal
 * drops only the tokens of THAT epoch, a return is accepted only for the epoch
 * the cached params name. Two threads racing a rotation therefore cannot throw
 * away each other's fresh batch.
 *
 * In-memory only (no persistence): a reserve is cheap to re-mint and we never want
 * tokens surviving uninstall. Blocking (PoW + HTTP): call off the main thread; the
 * cross-island send path already runs on Dispatchers.IO. Pure JDK (no Android
 * classes) so the JVM unit test can drive a whole mint through a fake island.
 */
object DepositAuthStore {
    private const val BATCH = 4
    /** How long a 404 on /deposit-auth/params is trusted before the island is
     *  asked again. Not forever: the answer may predate an upgrade, and a
     *  token-less island only costs one extra probe every so often. */
    private const val REST_MS = 10 * 60_000L
    private val JSON = "application/json".toMediaType()

    private class Params(val epochId: String, val n: BigInteger, val e: BigInteger, val difficulty: Int)

    private val params = ConcurrentHashMap<String, Params>()         // host -> cached params
    private val restingUntil = ConcurrentHashMap<String, Long>()     // host -> when its 404 stops being trusted
    private val reserve = ConcurrentHashMap<String, ArrayDeque<JsonObject>>()  // host -> spare tokens
    private val mintLocks = ConcurrentHashMap<String, Any>()         // host -> single-flight mint lock
    private val topUps = ConcurrentHashMap.newKeySet<String>()       // hosts with a top-up in flight
    /** One daemon thread for every background mint: a top-up is never urgent,
     *  and one PoW at a time is all the CPU it may take from the app. */
    private val background = Executors.newSingleThreadExecutor { r ->
        Thread(r, "deposit-auth-mint").apply { isDaemon = true }
    }

    /** A `deposit_token` object `{epoch_id, prepared, sig}` for [host], or null when
     *  the island doesn't offer deposit-auth or minting failed.
     *
     *  Reserve first. Otherwise ONE token is minted inline under the host's
     *  mint lock and handed over as soon as it exists; the rest of the batch
     *  follows in the background. A caller that finds the lock taken waits for
     *  at most one PoW, then reads the reserve again: whoever held the lock
     *  either left a token there or found the island does not issue any. */
    fun tokenFor(host: String, http: OkHttpClient): JsonObject? {
        if (resting(host)) return null
        pop(host)?.let { tok ->
            // The last spare went: refill before the next caller needs one.
            if (reserveSize(host) == 0) topUp(host, http)
            return tok
        }
        synchronized(lockFor(host)) {
            pop(host)?.let { return it }
            if (resting(host)) return null
            val p = ensureParams(host, http) ?: return null
            val first = mintOne(host, p, http) ?: return null
            topUp(host, http)
            return first
        }
    }

    /** Fill the reserve for [host] in the background, so the first fetch that
     *  needs a token finds one waiting instead of paying the PoW inline. Called
     *  when the island's capabilities say it takes anonymous key lookups. A
     *  no-op for a rested host, a full reserve, or a top-up already running. */
    fun warm(host: String, http: OkHttpClient) {
        if (resting(host)) return
        topUp(host, http)
    }

    /** Put back a token the island did NOT look at: a bundle fetch that never
     *  got an answer, or answered 404 or 429, never reached the verifier, and
     *  the token is as good as new. Goes to the front of the reserve so it is
     *  the next one used. Only a token of the epoch the cached params name is
     *  taken back: one minted under an epoch that has since rotated is dead,
     *  and returning it would cost the next fetch a 403 round trip. */
    fun giveBack(host: String, token: JsonObject) {
        val cur = params[host] ?: return
        if (cur.epochId != epochOf(token)) return
        val dq = reserve.getOrPut(host) { ArrayDeque() }
        synchronized(dq) { dq.addFirst(token) }
    }

    /** The island refused [token] (a spend answered 403): the epoch rotated
     *  under it, or the island stopped issuing. Drop the cached params if they
     *  are the ones the token was minted under, and every reserve token of
     *  that same epoch; a batch another thread has meanwhile minted under the
     *  NEW epoch is left alone. The host is also allowed to be probed again: a
     *  rest put on it by a 404 on /params may predate an upgrade. The next
     *  [tokenFor] re-reads the params and mints afresh. */
    fun forget(host: String, token: JsonObject) {
        val epoch = epochOf(token)
        restingUntil.remove(host)
        params[host]?.let { if (it.epochId == epoch) params.remove(host, it) }
        reserve[host]?.let { dq -> synchronized(dq) { dq.removeAll { epochOf(it) == epoch } } }
    }

    /** Drop everything cached for [host]: params, reserve and rest. */
    fun forget(host: String) {
        params.remove(host)
        reserve.remove(host)
        restingUntil.remove(host)
    }

    /** Spare tokens held for [host] right now. */
    fun reserveSize(host: String): Int = reserve[host]?.let { dq -> synchronized(dq) { dq.size } } ?: 0

    /** The `X-Deposit-Token` header value for [token]: base64url, no padding,
     *  of the same `{epoch_id, prepared, sig}` JSON a sealed deposit carries in
     *  its body. The server base64url-decodes with padding tolerance, so the
     *  unpadded form is the canonical one. */
    fun headerValue(token: JsonObject): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(token.toString().toByteArray(Charsets.UTF_8))

    private fun epochOf(token: JsonObject): String? = token.get("epoch_id")?.asString

    private fun lockFor(host: String): Any = mintLocks.getOrPut(host) { Any() }

    private fun resting(host: String): Boolean {
        val until = restingUntil[host] ?: return false
        if (System.currentTimeMillis() < until) return true
        restingUntil.remove(host, until)
        return false
    }

    private fun pop(host: String): JsonObject? {
        val dq = reserve[host] ?: return null
        synchronized(dq) { return if (dq.isEmpty()) null else dq.removeFirst() }
    }

    /** A freshly minted token joins the reserve only while its epoch is still
     *  the current one: a top-up that straddled a rotation would otherwise
     *  leave dead tokens at the back of the queue. */
    private fun push(host: String, token: JsonObject) {
        if (params[host]?.epochId != epochOf(token)) return
        val dq = reserve.getOrPut(host) { ArrayDeque() }
        synchronized(dq) { dq.addLast(token) }
    }

    /** Mint in the background until the reserve holds [BATCH] tokens. One
     *  top-up per host at a time; the mint lock is taken per token, so a
     *  foreground caller waits for one PoW at most and then finds the token
     *  the top-up just left. Params are re-read each round: a rotation (409
     *  on issue, or a [forget]) in the middle of a batch switches the rest of
     *  it to the new epoch instead of minting dead tokens. */
    private fun topUp(host: String, http: OkHttpClient) {
        if (reserveSize(host) >= BATCH) return
        if (!topUps.add(host)) return
        background.execute {
            try {
                // Bounded: a caller draining the reserve while it fills must
                // not keep this thread minting forever.
                repeat(BATCH * 2) {
                    if (reserveSize(host) >= BATCH || resting(host)) return@execute
                    synchronized(lockFor(host)) {
                        val p = ensureParams(host, http) ?: return@execute
                        val tok = mintOne(host, p, http) ?: return@execute
                        push(host, tok)
                    }
                }
            } finally {
                topUps.remove(host)
            }
        }
    }

    private fun ensureParams(host: String, http: OkHttpClient): Params? {
        params[host]?.let { return it }
        val req = Request.Builder().url("https://$host/deposit-auth/params").get().build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) {   // island doesn't offer it: rest, then ask again
                    restingUntil[host] = System.currentTimeMillis() + REST_MS
                    return null
                }
                if (!resp.isSuccessful) return null
                val o = JsonParser.parseString(resp.body?.string() ?: return null).asJsonObject
                val pk = o.getAsJsonObject("pubkey")
                val n = BigInteger(1, Base64.getUrlDecoder().decode(pk.get("n").asString))
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
        val blindedB64 = Base64.getEncoder().encodeToString(b.blinded)
        val nonce = BlindToken.solvePow("${p.epochId}:$blindedB64", p.difficulty)
        val reqBody = JsonObject().apply {
            addProperty("epoch_id", p.epochId)
            addProperty("blinded", blindedB64)
            addProperty("pow_nonce", nonce)
        }.toString().toRequestBody(JSON)
        val req = Request.Builder().url("https://$host/deposit-auth/issue").post(reqBody).build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 409) {
                    // Epoch rotated between /params and /issue: these params
                    // are dead and so is every token minted under them.
                    params.remove(host, p)
                    reserve[host]?.let { dq -> synchronized(dq) { dq.removeAll { epochOf(it) == p.epochId } } }
                    return null
                }
                if (!resp.isSuccessful) return null
                val o = JsonParser.parseString(resp.body?.string() ?: return null).asJsonObject
                val sig = BlindToken.finalize(Base64.getDecoder().decode(o.get("blind_sig").asString), b.blindInv, p.n)
                JsonObject().apply {
                    addProperty("epoch_id", p.epochId)
                    addProperty("prepared", Base64.getEncoder().encodeToString(prepared))
                    addProperty("sig", Base64.getEncoder().encodeToString(sig))
                }
            }
        } catch (t: Throwable) {
            null
        }
    }
}
