package app.rcq.android.net

import android.util.Base64
import app.rcq.android.crypto.RecoveryPhrase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.tls.OkHostnameVerifier
import java.io.IOException
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Server-side deletes of a burn (F2 of the 15.09 cross-island spec).
 *
 * Two entry points share one per-island state machine, [burnOne]:
 *  - [run], the interactive burn: every island this account holds a copy on
 *    (guest registrations and backup homes), in parallel, BEFORE the home
 *    island and before any local key is wiped, so a failure can still be
 *    retried with the keys that prove the copy is ours;
 *  - [runDetached], the wipe PIN: after the local wipe, from a memory-only
 *    snapshot, one attempt, never waited for (P0.3).
 *
 * ⚠ A result here is the island's claim. The UI says "island confirmed
 * deletion", never "deleted" (critic 12): an operator controls every answer.
 */
object BurnCascade {

    /** One account's home island, the session token that may delete it there,
     *  and how this phone trusted that island's certificate before the wipe
     *  erased the pin store. No toString: a token must never reach a log line.
     *
     *  [pin] is the [IslandTrust] record on file for the host (null when there
     *  was none) and [caOnly] whether the host is one that is never pinned. Both
     *  are read before the wipe, because the wipe clears the store the live
     *  trust manager consults: a delete judged against the empty store takes
     *  whatever certificate is on the wire as a first use and hands it the
     *  bearer token. */
    class HomeTarget(
        val host: String,
        val token: String,
        val pin: IslandTrust.Record? = null,
        val caOnly: Boolean = false,
    ) {
        override fun toString(): String = "HomeTarget"
    }

    /** A signing key the cascade may prove on an island. Held in memory only
     *  and zeroed when the burn is over. No toString, for the same reason. */
    class BurnKey(val priv: ByteArray, val pub: ByteArray) {
        fun zero() = priv.fill(0)
        fun samePub(other: BurnKey): Boolean = java.security.MessageDigest.isEqual(pub, other.pub)
        override fun toString(): String = "BurnKey"
    }

    /** Every copy of the account on one island: the tokens this device holds
     *  for it (a visited entry and a backup entry can both name the host) and
     *  the keys to prove, in the order to try them. */
    class BurnTarget(
        val host: String,
        val uin: Int?,
        val tokens: List<String>,
        val keys: List<BurnKey>,
        val pin: IslandTrust.Record? = null,
        val caOnly: Boolean = false,
    ) {
        override fun toString(): String = "BurnTarget"
    }

    /** What the wipe read out of the stores BEFORE erasing them. Memory only,
     *  never persisted: a wipe PIN promises nothing is left on disk. */
    class Snapshot(val homes: List<HomeTarget>, val remotes: List<BurnTarget> = emptyList()) {
        val isEmpty: Boolean get() = homes.isEmpty() && remotes.isEmpty()
        fun zero() = remotes.forEach { t -> t.keys.forEach { it.zero() } }
        override fun toString(): String = "Snapshot(${homes.size}, ${remotes.size})"
    }

    enum class Reason { OFFLINE, TIMEOUT, SUSPENDED, TOO_OLD, SERVER, LIMIT }

    sealed class IslandBurnResult {
        /** The island answered 204 to [n] deletes and then knew none of our keys. */
        data class Confirmed(val n: Int) : IslandBurnResult()
        /** Every key answered `identity_not_found` before anything was deleted. */
        object AlreadyGone : IslandBurnResult()
        data class Failed(val reason: Reason) : IslandBurnResult()
        /** The deadline came before this island was started. */
        object NotTried : IslandBurnResult()

        val isDone: Boolean get() = this is Confirmed || this is AlreadyGone
    }

    /** One `/auth/recover` round, as the island gave it. */
    sealed class RecoverAnswer {
        class Token(val token: String) : RecoverAnswer() {
            override fun toString(): String = "Token"
        }
        /** A 404 from recover itself, with its exact detail code. */
        data class NotFound(val code: String?) : RecoverAnswer()
        /** The challenge route answered 404: an island too old to prove a key on. */
        object ChallengeMissing : RecoverAnswer()
        data class Refused(val status: Int) : RecoverAnswer()
    }

    /** The network, injected so the state machine is checked on the JVM. */
    interface Transport {
        /** DELETE /auth/account with [token]: the HTTP status. Throws
         *  IOException when no answer came back. */
        suspend fun deleteAccount(host: String, token: String): Int

        /** Challenge and recover with [key]. Throws IOException when no
         *  answer came back. */
        suspend fun recover(host: String, key: BurnKey): RecoverAnswer
    }

    const val DETACHED_DEADLINE_MS = 8_000L
    const val REMOTE_DEADLINE_MS = 15_000L
    const val CALL_TIMEOUT_SEC = 8L
    const val MAX_DELETES_PER_ISLAND = 4
    const val CONCURRENCY = 6

    /** Process-level, not the Session's scope: the wipe tears the session down,
     *  and a delete that dies with it is a delete that never ran. */
    private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── planning ─────────────────────────────────────────────────────

    /** One copy of the account as a store knows it. */
    class Copy(val host: String, val uin: Int?, val token: String?) {
        override fun toString(): String = "Copy"
    }

    /** [backupTokens] false keeps the backup islands as targets but drops their
     *  stored tokens, so only recover-then-DELETE with our keys reaches them.
     *  See [backupTokensUsable]. */
    fun copiesOf(
        visited: List<VisitedIslandsStore.Visited>,
        backups: List<MultihomeStore.Home>,
        backupTokens: Boolean = true,
    ): List<Copy> =
        visited.map { Copy(it.host, it.uin, it.jwt) } +
            backups.map { Copy(it.host, it.uin, if (backupTokens) it.jwt else null) }

    /** One roster account as a burn plan sees it: its number, whether its
     *  signing key is the one being burned, and whether its store could be
     *  read at all. */
    class RosterEntry(val uin: Int?, val sameKey: Boolean, val readable: Boolean = true)

    /**
     * May the backup entries [MultihomeStore] files under [number] be deleted
     * with the tokens stored in them?
     *
     * ⚠⚠ That store is keyed by number alone, and the roster allows two
     * accounts with one number on different islands (1234@A and 1234@C, two
     * identities). Burning one of them would hand the OTHER one's backup tokens
     * to a DELETE that proves no key, and the island would delete a live
     * account nobody asked to burn, then report it as confirmed. So when any
     * account with a different key holds [number], or a store could not be
     * read to rule that out, the tokens are not used. Recover-then-DELETE still
     * runs there and can only reach rows that carry our key.
     */
    fun backupTokensUsable(number: Int, roster: List<RosterEntry>): Boolean =
        roster.none { !it.readable || (!it.sameKey && it.uin == number) }

    /**
     * The remote targets: one per island, hosts compared lowercased, every
     * token any store holds for it, and never the home island ([ownHost]) or a
     * front ([skipHost]), which is the home island by another road. The home is
     * deleted last, on its own, by the session.
     */
    fun planTargets(
        copies: List<Copy>,
        ownHost: String,
        skipHost: (String) -> Boolean = { false },
        keysFor: (host: String) -> List<BurnKey>,
        pinOf: (String) -> IslandTrust.Record? = { null },
        caOnlyOf: (String) -> Boolean = { false },
    ): List<BurnTarget> {
        val own = ownHost.trim().lowercase()
        return copies
            .filter { it.host.isNotBlank() }
            .groupBy { it.host.trim().lowercase() }
            .filterKeys { it != own && !skipHost(it) }
            .map { (h, cs) ->
                BurnTarget(
                    host = h,
                    uin = cs.firstNotNullOfOrNull { it.uin },
                    tokens = cs.mapNotNull { it.token?.takeIf { t -> t.isNotEmpty() } }.distinct(),
                    keys = keysFor(h),
                    pin = pinOf(h),
                    caOnly = caOnlyOf(h),
                )
            }
    }

    /**
     * Which keys to prove on an island, first to last. With no rotation under
     * way there is one. While a PendingRotation exists (critic, cross-feature
     * finding) the island that has not taken the new key yet holds the copy
     * under the OLD one, so that goes first; an island marked done takes the
     * new one first. Both are always tried: see [burnOne].
     */
    fun keyOrder(current: BurnKey, old: BurnKey?, targetDone: Boolean?): List<BurnKey> = when {
        old == null || old.samePub(current) -> listOf(current)
        targetDone == true -> listOf(current, old)
        else -> listOf(old, current)
    }

    // ── the state machine ────────────────────────────────────────────

    private val runScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Every target in parallel (at most [CONCURRENCY] at once) under ONE
     * deadline. Returns a result for every target: an island still working at
     * the deadline is [Reason.TIMEOUT], one never started is [IslandBurnResult.NotTried].
     *
     * ⚠ The islands are not children of the waiting coroutine, for the reason
     * [runDetached] gives: a blocking call that ignores cancellation would hold
     * a `coroutineScope` open well past the deadline. The wait is a cancellable
     * join, and the deadline then cancels what is left.
     */
    suspend fun run(
        targets: List<BurnTarget>,
        transport: Transport,
        deadlineMs: Long = REMOTE_DEADLINE_MS,
        retry: Boolean = true,
    ): Map<String, IslandBurnResult> {
        if (targets.isEmpty()) return emptyMap()
        val results = ConcurrentHashMap<String, IslandBurnResult>()
        val started = ConcurrentHashMap.newKeySet<String>()
        val gate = Semaphore(CONCURRENCY)
        val jobs = targets.map { t ->
            runScope.launch {
                gate.withPermit {
                    started.add(t.host)
                    results[t.host] = try {
                        burnOne(t, transport, retry)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        IslandBurnResult.Failed(Reason.SERVER)
                    }
                }
            }
        }
        try {
            withTimeoutOrNull(deadlineMs) { jobs.joinAll() }
        } finally {
            jobs.forEach { it.cancel() }
        }
        val out = LinkedHashMap<String, IslandBurnResult>()
        targets.forEach { t ->
            out[t.host] = results[t.host]
                ?: if (t.host in started) IslandBurnResult.Failed(Reason.TIMEOUT) else IslandBurnResult.NotTried
        }
        return out
    }

    private class Budget(var retries: Int, var deletes: Int = 0)

    private sealed class Try<out T> {
        class Ok<T>(val v: T) : Try<T>()
        object Offline : Try<Nothing>()
        object Server : Try<Nothing>()
    }

    /** One call, once more on no answer or a 5xx while the island's single
     *  retry is unspent. */
    private suspend fun <T> attempt(b: Budget, serverError: (T) -> Boolean, call: suspend () -> T): Try<T> {
        while (true) {
            val outcome: Try<T> = try {
                val v = call()
                if (serverError(v)) Try.Server else Try.Ok(v)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                Try.Offline
            }
            if (outcome is Try.Ok || b.retries <= 0) return outcome
            b.retries--
        }
    }

    /**
     * One island:
     *  1. each stored token: DELETE. 204 counts; 401 or 404 moves on to recover
     *     (tokens die with an epoch bump, and a deleted row's token is 404);
     *     403 is a suspended account.
     *  2. each key in order: recover, delete what it opened, recover again,
     *     until that key answers `identity_not_found` or `identity_rotated`.
     *     At most [MAX_DELETES_PER_ISLAND] deletes in all, then [Reason.LIMIT].
     *  3. The copy is gone only when EVERY key's last answer is
     *     `identity_not_found`. `identity_rotated` says a row moved from that
     *     key to another one. When a later key then found and deleted a row,
     *     the rotated key is asked once more, since that row may be the one
     *     it pointed to (a burn purges the rotation marker with the row). A key
     *     that still says `identity_rotated` means a copy sits under a key this
     *     device does not hold: [Reason.SERVER], never "no copy".
     *  4. A 404 from the CHALLENGE, or a recover 404 with no code, is an island
     *     too old ([Reason.TOO_OLD]), never "no copy".
     */
    internal suspend fun burnOne(t: BurnTarget, tr: Transport, retry: Boolean): IslandBurnResult {
        val b = Budget(if (retry) 1 else 0)
        for (token in t.tokens) {
            when (val r = attempt(b, { it >= 500 }) { tr.deleteAccount(t.host, token) }) {
                Try.Offline -> return IslandBurnResult.Failed(Reason.OFFLINE)
                Try.Server -> return IslandBurnResult.Failed(Reason.SERVER)
                is Try.Ok -> when (r.v) {
                    in 200..299 -> b.deletes++
                    401, 404 -> Unit
                    403 -> return IslandBurnResult.Failed(Reason.SUSPENDED)
                    else -> return IslandBurnResult.Failed(Reason.SERVER)
                }
            }
        }
        if (t.keys.isEmpty()) {
            return if (b.deletes > 0) IslandBurnResult.Confirmed(b.deletes) else IslandBurnResult.Failed(Reason.SERVER)
        }
        // Per key: its last terminal answer, and how many deletes had been
        // confirmed when it gave it.
        val rotatedAt = HashMap<Int, Int>()
        for ((i, key) in t.keys.withIndex()) {
            when (val o = proveKey(t, key, tr, b)) {
                KeyOutcome.NotFound -> rotatedAt.remove(i)
                KeyOutcome.Rotated -> rotatedAt[i] = b.deletes
                is KeyOutcome.Stop -> return o.result
            }
        }
        // A rotated answer given before a later delete may be stale: ask once more.
        for ((i, before) in rotatedAt.entries.toList()) {
            if (b.deletes == before) continue
            when (val o = proveKey(t, t.keys[i], tr, b)) {
                KeyOutcome.NotFound -> rotatedAt.remove(i)
                KeyOutcome.Rotated -> Unit
                is KeyOutcome.Stop -> return o.result
            }
        }
        return when {
            rotatedAt.isNotEmpty() -> IslandBurnResult.Failed(Reason.SERVER)
            b.deletes > 0 -> IslandBurnResult.Confirmed(b.deletes)
            else -> IslandBurnResult.AlreadyGone
        }
    }

    private sealed class KeyOutcome {
        object NotFound : KeyOutcome()
        object Rotated : KeyOutcome()
        class Stop(val result: IslandBurnResult) : KeyOutcome()
    }

    /** One key on one island: recover, delete what it opened, recover again,
     *  until the island refuses the key. */
    private suspend fun proveKey(t: BurnTarget, key: BurnKey, tr: Transport, b: Budget): KeyOutcome {
        fun stop(r: IslandBurnResult) = KeyOutcome.Stop(r)
        var recovers = 0
        while (true) {
            if (recovers++ > MAX_DELETES_PER_ISLAND) return stop(IslandBurnResult.Failed(Reason.LIMIT))
            val ans = when (val r = attempt(b, { it is RecoverAnswer.Refused && it.status >= 500 }) { tr.recover(t.host, key) }) {
                Try.Offline -> return stop(IslandBurnResult.Failed(Reason.OFFLINE))
                Try.Server -> return stop(IslandBurnResult.Failed(Reason.SERVER))
                is Try.Ok -> r.v
            }
            when (ans) {
                is RecoverAnswer.Token -> {
                    if (b.deletes >= MAX_DELETES_PER_ISLAND) return stop(IslandBurnResult.Failed(Reason.LIMIT))
                    when (val d = attempt(b, { it >= 500 }) { tr.deleteAccount(t.host, ans.token) }) {
                        Try.Offline -> return stop(IslandBurnResult.Failed(Reason.OFFLINE))
                        Try.Server -> return stop(IslandBurnResult.Failed(Reason.SERVER))
                        is Try.Ok -> when (d.v) {
                            in 200..299 -> b.deletes++
                            // A token minted a moment ago that is already
                            // stale: another delete raced us. Recover again.
                            401, 404 -> Unit
                            403 -> return stop(IslandBurnResult.Failed(Reason.SUSPENDED))
                            else -> return stop(IslandBurnResult.Failed(Reason.SERVER))
                        }
                    }
                }
                is RecoverAnswer.NotFound -> return when (ans.code) {
                    AuthRefusal.IDENTITY_NOT_FOUND -> KeyOutcome.NotFound
                    AuthRefusal.IDENTITY_ROTATED -> KeyOutcome.Rotated
                    else -> stop(IslandBurnResult.Failed(Reason.TOO_OLD))
                }
                RecoverAnswer.ChallengeMissing -> return stop(IslandBurnResult.Failed(Reason.TOO_OLD))
                is RecoverAnswer.Refused ->
                    return stop(IslandBurnResult.Failed(if (ans.status == 403) Reason.SUSPENDED else Reason.SERVER))
            }
        }
    }

    internal fun recoverAnswerOf(status: Int, code: String?, token: String?, challengeMissing: Boolean): RecoverAnswer = when {
        challengeMissing -> RecoverAnswer.ChallengeMissing
        token != null -> RecoverAnswer.Token(token)
        status == 404 -> RecoverAnswer.NotFound(code)
        else -> RecoverAnswer.Refused(status)
    }

    /** The interactive transport: [RcqApi] with an 8 s call ceiling, over the
     *  same route ladder every foreign-island call takes. */
    object ApiTransport : Transport {
        private fun api(host: String) = RcqApi("https://$host", callTimeoutSeconds = CALL_TIMEOUT_SEC)

        override suspend fun deleteAccount(host: String, token: String): Int =
            api(host).apply { setToken(token) }.deleteAccountStatus()

        override suspend fun recover(host: String, key: BurnKey): RecoverAnswer {
            val sk = Base64.encodeToString(key.pub, Base64.NO_WRAP)
            val r = api(host).recoverForBurn(sk) { RecoveryPhrase.signChallenge(key.priv, it) }
            return recoverAnswerOf(r.status, r.code, r.token, r.challengeMissing)
        }
    }

    // ── the wipe PIN ─────────────────────────────────────────────────

    /**
     * ⚠⚠ Called only AFTER the local wipe has finished, and it returns at once.
     * The founder's rule for the wipe PIN (decision 2): the network never
     * blocks it. The old order ran the deletes first with an 8 s wait, so on a
     * hostile network the person entering the PIN watched a live account on
     * screen for eight seconds.
     *
     * One deadline for everything, homes and the islands every account visited
     * all in parallel, no retry, and no logs that name an account or a host.
     * [onFinished] runs when every delete is over or when the deadline fires,
     * whichever is first; the snapshot's key bytes are zeroed just before it.
     *
     * ⚠ The deletes are NOT children of the coroutine that waits. A child stuck
     * in a thread that ignores cancellation holds a `coroutineScope` open until
     * that thread returns, and "8 s" quietly became OkHttp's 30 or 90 s. Here
     * the wait is a cancellable join under the deadline, and the deadline then
     * cancels each delete, which for the real transport cancels its call.
     */
    fun runDetached(
        snapshot: Snapshot,
        deadlineMs: Long = DETACHED_DEADLINE_MS,
        delete: suspend (HomeTarget) -> Unit = { deleteHome(it, deadlineMs) },
        remote: suspend (BurnTarget) -> Unit = { burnOne(it, DetachedTransport(it, deadlineMs), retry = false) },
        onFinished: () -> Unit = {},
    ): Job? {
        if (snapshot.isEmpty) return null
        val deletes = snapshot.homes.map { t -> detachedScope.launch { quietly { delete(t) } } } +
            snapshot.remotes.map { t -> detachedScope.launch { quietly { remote(t) } } }
        return detachedScope.launch {
            try {
                withTimeoutOrNull(deadlineMs) { deletes.joinAll() }
            } finally {
                deletes.forEach { it.cancel() }
                snapshot.zero()
                runCatching { onFinished() }
            }
        }
    }

    /** One island failing never stops the others, and nothing is logged: the
     *  exception text can carry the host. */
    private suspend inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    /**
     * May the detached delete talk to a server presenting a leaf with
     * fingerprint [leafFp]? The live rule of [IslandTrust.decide] with its one
     * writing branch taken out: nothing here is a first use, and nothing is
     * written. [caValid] is chain AND name, as in [IslandTrust].
     *  - A host that is never pinned: an authority, and only an authority.
     *  - A fingerprint the person typed: that fingerprint, CA or not, as live.
     *  - Otherwise an authority is enough, as live (it is how an island moves
     *    to a CA), or a leaf that hashes to the pin on file.
     *  - No record and no authority: refused. Live, that is the first-use
     *    branch; after a wipe it is exactly what a man in the middle presents.
     */
    fun detachedTrusts(pin: IslandTrust.Record?, caOnly: Boolean, caValid: Boolean, leafFp: String): Boolean {
        if (caOnly) return caValid
        if (pin?.source == IslandTrust.Source.TYPED) return pin.fp != null && pin.fp == leafFp
        if (caValid) return true
        return pin?.mode == IslandTrust.Mode.PINNED && pin.fp != null && pin.fp == leafFp
    }

    /**
     * The delete itself, on a client of its own rather than [RcqApi]:
     *  - trust comes from the snapshot ([detachedTrusts]) and never writes the
     *    pin store, so a wipe leaves no record of the island it dialled;
     *  - one attempt with `callTimeout` at the deadline, and no relay engage.
     *    [RcqApi]'s route ladder retried through a freshly started sing-box, so
     *    one delete ran for minutes and brought the tunnel up on a phone that
     *    now looks freshly installed. A tunnel that is ALREADY up is used,
     *    because going direct around it is the leak it exists to prevent;
     *  - `enqueue` under a cancellable suspension, so the deadline cancels the
     *    call instead of waiting for a blocked `execute()` to give up.
     */
    private suspend fun deleteHome(t: HomeTarget, deadlineMs: Long) {
        app.rcq.android.security.DuressGate.check()
        val client = detachedClient(t.host, t.pin, t.caOnly, deadlineMs)
        val req = Request.Builder()
            .url("https://${t.host}/auth/account")
            .delete()
            .header("Authorization", "Bearer ${t.token}")
            .build()
        callDetached(client, req)
    }

    private val JSON = "application/json".toMediaType()

    /** The islands a wipe-PIN account visited: the same [burnOne] as the
     *  interactive burn, over the snapshot-trust client. Recover proves the key
     *  from memory; nothing it learns is written anywhere. */
    private class DetachedTransport(t: BurnTarget, deadlineMs: Long) : Transport {
        private val client = detachedClient(t.host, t.pin, t.caOnly, deadlineMs)

        override suspend fun deleteAccount(host: String, token: String): Int {
            app.rcq.android.security.DuressGate.check()
            val req = Request.Builder().url("https://$host/auth/account").delete()
                .header("Authorization", "Bearer $token").build()
            return callDetached(client, req).first
        }

        override suspend fun recover(host: String, key: BurnKey): RecoverAnswer {
            app.rcq.android.security.DuressGate.check()
            val sk = Base64.encodeToString(key.pub, Base64.NO_WRAP)
            val chBody = com.google.gson.JsonObject().apply { addProperty("signing_key", sk) }.toString()
            val (cs, cText) = callDetached(
                client,
                Request.Builder().url("https://$host/auth/recover/challenge").post(chBody.toRequestBody(JSON)).build(),
            )
            if (cs == 404) return RecoverAnswer.ChallengeMissing
            if (cs !in 200..299) return RecoverAnswer.Refused(cs)
            val challenge = runCatching {
                com.google.gson.JsonParser.parseString(cText).asJsonObject.get("challenge")?.asString
            }.getOrNull() ?: return RecoverAnswer.Refused(cs)
            val body = com.google.gson.JsonObject().apply {
                addProperty("signing_key", sk)
                addProperty("challenge", challenge)
                addProperty("signature", RecoveryPhrase.signChallenge(key.priv, challenge))
            }.toString()
            val (rs, rText) = callDetached(
                client,
                Request.Builder().url("https://$host/auth/recover").post(body.toRequestBody(JSON)).build(),
            )
            val token = if (rs in 200..299) runCatching {
                com.google.gson.JsonParser.parseString(rText).asJsonObject.get("token")?.asString
            }.getOrNull()?.takeIf { it.isNotEmpty() } else null
            val code = if (rs in 200..299) null else RcqApi.refusalOf("HTTP $rs: $rText").code
            return recoverAnswerOf(rs, code, token, challengeMissing = false)
        }
    }

    /** One call under a cancellable suspension: status and body text. */
    private suspend fun callDetached(client: OkHttpClient, req: Request): Pair<Int, String> {
        val call = client.newCall(req)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val out = response.use { it.code to (runCatching { it.body?.string() }.getOrNull() ?: "") }
                    if (cont.isActive) cont.resume(out)
                }
            })
        }
    }

    private val platform: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun detachedClient(host: String, pin: IslandTrust.Record?, caOnly: Boolean, deadlineMs: Long): OkHttpClient {
        // Bound to the one host this client exists for, so the decision never
        // depends on what the socket reports.
        val bareHost = IslandTrust.hostAndPort(host).first
        val tm = SnapshotTrustManager(platform, bareHost, pin, caOnly)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return OkHttpClient.Builder()
            .connectTimeout(deadlineMs, TimeUnit.MILLISECONDS)
            .readTimeout(deadlineMs, TimeUnit.MILLISECONDS)
            .writeTimeout(deadlineMs, TimeUnit.MILLISECONDS)
            .callTimeout(deadlineMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .proxy(SingBoxTransport.proxy())
            // A closed (masquerade) island answers only with its access header.
            .addInterceptor(AccessTokenInterceptor)
            .addInterceptor(UserAgentInterceptor)
            .sslSocketFactory(ssl.socketFactory, tm)
            // The trust manager already ran the name gate on the CA branch, and
            // a pinned leaf is the identity itself; the verifier repeats the
            // same rule from the session so neither half can be skipped.
            .hostnameVerifier { _, session ->
                val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
                    ?: return@hostnameVerifier false
                val nameOk = runCatching { OkHostnameVerifier.verify(bareHost, leaf) }.getOrDefault(false)
                val fp = IslandTrust.sha256Hex(leaf.encoded)
                // The chain was already validated by the trust manager; here
                // only the name half of `caValid` is still open.
                detachedTrusts(pin, caOnly, caValid = nameOk && tm.chainWasCaValid, leafFp = fp)
            }
            .build()
    }

    private class SnapshotTrustManager(
        private val platform: X509TrustManager,
        private val host: String,
        private val pin: IslandTrust.Record?,
        private val caOnly: Boolean,
    ) : X509ExtendedTrustManager() {

        @Volatile var chainWasCaValid: Boolean = false

        override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("client auth not used")

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) =
            throw CertificateException("client auth not used")

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
            throw CertificateException("client auth not used")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            judge(chain) { platform.checkServerTrusted(chain, authType) }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) =
            judge(chain) {
                (platform as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, socket)
                    ?: platform.checkServerTrusted(chain, authType)
            }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
            judge(chain) {
                (platform as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, engine)
                    ?: platform.checkServerTrusted(chain, authType)
            }

        private fun judge(chain: Array<X509Certificate>, platformCheck: () -> Unit) {
            if (chain.isEmpty()) throw CertificateException("empty chain")
            val chainOk = runCatching(platformCheck).isSuccess
            chainWasCaValid = chainOk
            val nameOk = runCatching { OkHostnameVerifier.verify(host, chain[0]) }.getOrDefault(false)
            val fp = IslandTrust.sha256Hex(chain[0].encoded)
            if (!detachedTrusts(pin, caOnly, caValid = chainOk && nameOk, leafFp = fp)) {
                throw CertificateException("detached delete: certificate not trusted")
            }
        }
    }
}
