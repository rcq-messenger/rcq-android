package app.rcq.android.push

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import app.rcq.android.data.AccountManager
import app.rcq.android.data.SecureStore
import app.rcq.android.net.RcqApi
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Push rows of accounts that left this device, still to be taken off their
 * islands (report 1066).
 *
 * A push row is (number, endpoint), and the endpoint is this installation's.
 * Only the account itself can remove its row, and once its store is wiped
 * there is nothing left to authenticate with, so the DELETE has to be made
 * with credentials copied out BEFORE the wipe. One fire-and-forget attempt was
 * not enough: a phone offline at that moment, or an island that was down, kept
 * the row for ever, and the island went on waking this phone for an account
 * that was no longer on it.
 *
 * So the removal is written down first, as {island, number, token, endpoint,
 * when}, and retried: at once, at every process start, on every unlock and
 * whenever the network comes back. An entry goes when the island answers 2xx
 * (done), 401 or 403 (this token can no longer speak for the account: install
 * revoked, key changed, account deleted, and nothing will change that) or 404,
 * and after [TTL_MS] whatever happened.
 *
 * ⚠ The bearer tokens of removed accounts sit here for up to a week. In
 * SecureStore and never in plain prefs, and every duress path [purge]s the
 * lot: a coerced phone must not keep the islands and tokens of the accounts it
 * once carried. None of those paths ADDS an entry either. The retry is for an
 * ordinary removal only.
 *
 * ⚠ An entry dies the moment the same number on the same island is back on
 * this device under another account (added again by its phrase, say). The
 * island moves no epoch for that, so the old token still works, and a retry
 * would delete the row the returning account has just registered: same number,
 * same endpoint, same row. Whose key holds the number does not matter here
 * for the same reason, the row is keyed by the number.
 */
internal object PendingDeregister {

    /** How long a removal is retried before it is given up. */
    const val TTL_MS = 7L * 24 * 3600 * 1000

    /** One row to take off. [accountId] is the local id of the account that
     *  left, so that only ANOTHER account holding the number counts as it
     *  coming back: the one being removed is still in the roster for the few
     *  milliseconds between the record and its wipe. */
    data class Entry(
        val accountId: String,
        val host: String,
        val uin: Int,
        val token: String,
        val endpoint: String,
        val at: Long,
    )

    // ── pure decisions (PendingDeregisterTest) ──────────────────────────

    private val gson = Gson()

    /** Gson fills a missing field with null whatever Kotlin says, so a row is
     *  read through nullable twins and dropped when anything is missing. */
    private data class Raw(
        val accountId: String?,
        val host: String?,
        val uin: Int?,
        val token: String?,
        val endpoint: String?,
        val at: Long?,
    )

    fun decode(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        val rows = runCatching {
            gson.fromJson<List<Raw?>>(raw, object : TypeToken<List<Raw?>>() {}.type)
        }.getOrNull() ?: return emptyList()
        return rows.mapNotNull { r ->
            if (r == null) return@mapNotNull null
            Entry(
                accountId = r.accountId ?: return@mapNotNull null,
                host = r.host?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                uin = r.uin ?: return@mapNotNull null,
                token = r.token?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                endpoint = r.endpoint?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                at = r.at ?: return@mapNotNull null,
            )
        }
    }

    /** Null for an empty list: the key goes, rather than an empty array
     *  saying there once was something here. */
    fun encode(list: List<Entry>): String? = if (list.isEmpty()) null else gson.toJson(list)

    /** The same row is the same island, number and endpoint; the newer
     *  record replaces the older one. */
    fun plus(list: List<Entry>, e: Entry): List<Entry> =
        list.filterNot { sameRow(it, e.host, e.uin) && it.endpoint == e.endpoint } + e

    fun sameRow(e: Entry, host: String?, uin: Int): Boolean =
        e.uin == uin && Push.islandKey(e.host) == Push.islandKey(host)

    /** Is the row gone, or beyond this token for good? See the class note. */
    fun isDone(code: Int): Boolean = code in 200..299 || code == 401 || code == 403 || code == 404

    /** Past its week. A clock set back by more than a week counts too, or the
     *  entry would never age out. */
    fun expired(e: Entry, now: Long): Boolean {
        val age = now - e.at
        return age >= TTL_MS || age <= -TTL_MS
    }

    /** Is this number on this island held by ANOTHER account on the device?
     *  Then the row is that account's as much as it was the leaver's. */
    fun heldByAnother(e: Entry, roster: List<Push.LocalNumber>): Boolean =
        roster.any { it.accountId != e.accountId && it.uin == e.uin && Push.islandKey(it.host) == Push.islandKey(e.host) }

    // ── storage ─────────────────────────────────────────────────────────

    private val lock = Any()

    private fun load(ctx: Context): List<Entry> =
        decode(runCatching { SecureStore.loadPendingPushDeregisters(ctx) }.getOrNull())

    private fun update(ctx: Context, change: (List<Entry>) -> List<Entry>) {
        synchronized(lock) {
            val before = load(ctx)
            val after = change(before)
            if (after != before) runCatching { SecureStore.savePendingPushDeregisters(ctx, encode(after)) }
        }
    }

    /**
     * Write down [accountId]'s row for removal. Reads the account's
     * credentials synchronously, so it must run BEFORE its store is wiped;
     * kick [flush] once the account is out of the roster.
     *
     * Nothing is written when there is no endpoint (push off, or the gap
     * between a cleared endpoint and the next one): there is no row to name,
     * and the endpoint any old row carries is one this device has already let
     * go of. Nor when another account here holds the same number on the same
     * island, because then the row is still wanted.
     */
    fun record(ctx: Context, accountId: String) {
        val app = ctx.applicationContext
        val endpoint = Push.savedEndpoint(app) ?: return
        val store = SecureStore(app, accountId)
        val token = store.token ?: return
        val uin = store.uin ?: return
        val host = store.serverHost ?: RcqApi.DEFAULT_HOST
        val entry = Entry(accountId, host, uin, token, endpoint, System.currentTimeMillis())
        if (heldByAnother(entry, Push.localNumbers(app))) return
        update(app) { plus(it, entry) }
    }

    /** The number is registering on this device again under [keepAccountId]:
     *  forget any removal queued for the same row by another account. */
    fun cancel(ctx: Context, host: String?, uin: Int, keepAccountId: String) {
        val app = ctx.applicationContext
        update(app) { list -> list.filterNot { it.accountId != keepAccountId && sameRow(it, host, uin) } }
    }

    /** Forget every queued removal, tokens and all. For the duress paths and
     *  for the last account leaving: see the class note. */
    fun purge(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(lock) { runCatching { SecureStore.savePendingPushDeregisters(app, null) } }
        unwatch(app)
    }

    // ── retrying ────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Conflated: a burst of kicks (start, unlock, a network that flaps) is
     *  one pass, and a kick during a pass is one more after it. One worker, so
     *  two passes never send the same DELETE side by side. */
    private val kicks = Channel<Context>(Channel.CONFLATED)
    private val worker by lazy {
        scope.launch {
            for (app in kicks) {
                runCatching { flushOnce(app) }
                runCatching { watch(app) }
            }
        }
    }

    /** Try every queued removal now. Cheap when there are none. */
    fun flush(ctx: Context) {
        worker
        kicks.trySend(ctx.applicationContext)
    }

    /** ⚠ Never from a locked app or a duress view. These are islands the
     *  person was on, and a coerced phone dialling them is exactly the trace
     *  the duress paths exist not to leave. The next unlock picks it up. */
    private fun mayDial(): Boolean =
        !app.rcq.android.security.PanicPinService.isLocked &&
            !AccountManager.isDecoyMode &&
            !app.rcq.android.security.DuressGate.isActive

    private suspend fun flushOnce(app: Context) {
        if (!mayDial()) return
        val due = synchronized(lock) { load(app) }
        if (due.isEmpty()) return
        val roster = Push.localNumbers(app)
        val now = System.currentTimeMillis()
        for (e in due) {
            // Asked again before every request, not once per pass: a duress
            // path that [purge]s while a pass is under way must stop it there,
            // not after the rest of the list has been dialled.
            if (!mayDial()) return
            if (synchronized(lock) { e !in load(app) }) continue
            val drop = when {
                expired(e, now) -> true
                heldByAnother(e, roster) -> true
                else -> {
                    val code = try {
                        RcqApi("https://${e.host}").apply { setToken(e.token) }.deletePushTokenStatus(e.endpoint)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Exception) {
                        null
                    }
                    // The island, the number and the token stay out of the log.
                    android.util.Log.i("RCQpush", "removed account's push row: ${code ?: "no answer"}")
                    code != null && isDone(code)
                }
            }
            if (drop) update(app) { list -> list.filterNot { it == e } }
        }
    }

    // ── the network coming back ─────────────────────────────────────────

    private val watchLock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Listen for a network while anything is queued, and stop once nothing
     *  is. A callback registered with a network already up fires at once,
     *  which costs one extra pass and nothing else. */
    private fun watch(app: Context) {
        val pending = synchronized(lock) { load(app).isNotEmpty() }
        if (!pending) { unwatch(app); return }
        synchronized(watchLock) {
            if (callback != null) return
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = flush(app)
            }
            if (runCatching { cm.registerDefaultNetworkCallback(cb) }.isSuccess) callback = cb
        }
    }

    private fun unwatch(app: Context) {
        synchronized(watchLock) {
            val cb = callback ?: return
            callback = null
            runCatching { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
    }
}
