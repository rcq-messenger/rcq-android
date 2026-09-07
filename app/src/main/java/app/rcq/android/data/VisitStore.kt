package app.rcq.android.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local-only "people viewed your profile" tally — the Android analogue of
 * the iOS VisitStore. When someone opens our profile their client sends a
 * sealed `visit` envelope; [Session] decrypts it and calls [record]. The
 * server never sees the count or that it exists.
 *
 * Multi-account: the tally is per-identity, so storage is keyed by the
 * active [Account.id]. [bindAccount] swaps which account's visits the flow
 * reflects (and is re-called on every account switch by [Session]).
 *
 * Same-viewer pings within [DEDUP_MS] collapse into one (unique view);
 * visits older than [PRUNE_MS] are dropped on every save. [recentViews] is
 * the rolling count within [WINDOW_MS] (7 days).
 *
 * ⚠⚠ ONLY THE COUNT LEAVES THIS STORE. A visit ping carries the viewer's
 * number, so the numbers are on disk, and report #939 asked for the list they
 * would make. It was built and then taken straight back out: telling somebody
 * WHO has been checking their profile is a different promise from telling them
 * how often it happened, and it is not one this app makes (founder, 07.09).
 * If that is ever revisited, the second half has to ship with it: nothing
 * anywhere tells the person opening a card that a ping is sent at all.
 *
 * Call [init] once from MainActivity.onCreate, then [bindAccount].
 */
object VisitStore {
    private lateinit var prefs: SharedPreferences

    /** Active account prefix; null before any account is bound. */
    private var acct: String? = null

    /** (viewerUin, atEpochMillis) pairs. */
    private val _visits = MutableStateFlow<List<Pair<Int, Long>>>(emptyList())

    private val _recentViews = MutableStateFlow(0)
    val recentViews: StateFlow<Int> = _recentViews.asStateFlow()

    /** One recorded view: who opened our profile, and when (epoch millis). */


    /**
     * The same views [recentViews] counts, newest first.
     *
     * The identities were always here: a visit ping is a sealed 1:1 envelope
     * and [record] is handed its sender. Nothing ever asked the store for them,
     * which is the whole of #939 - the profile row promised "people who opened
     * your profile" and could only ever show a number.
     *
     * ⚠ This is a LOCAL tally and must stay one. Do not enrich a row here by
     * asking the island who a number belongs to: that would hand the server the
     * two facts the tally exists to withhold, that we keep it at all and whose
     * numbers are in it. A name comes from the local roster or not at all.
     */

    const val DEDUP_MS = 60L * 60 * 1000           // 1h unique-visitor window
    const val PRUNE_MS = 30L * 86_400 * 1000        // keep 30 days
    const val WINDOW_MS = 7L * 86_400 * 1000        // "last 7 days" count

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("rcq_visits", Context.MODE_PRIVATE)
    }

    /** Point the tally at [accountId]'s slot and reload it. null resets. */
    fun bindAccount(accountId: String?) {
        acct = accountId
        _visits.value = if (accountId == null) emptyList() else load(key())
        recompute()
    }

    /** Record a profile view from [viewer] at [atMillis] (epoch). Future
     *  timestamps are clamped to now; repeats within [DEDUP_MS] are ignored. */
    fun record(viewer: Int, atMillis: Long) {
        if (!::prefs.isInitialized || acct == null) return
        val now = System.currentTimeMillis()
        val clamped = minOf(atMillis, now)
        val last = _visits.value.lastOrNull { it.first == viewer }?.second
        if (last != null && clamped - last < DEDUP_MS) return
        _visits.value = (_visits.value + (viewer to clamped)).filter { now - it.second <= PRUNE_MS }
        persist()
        recompute()
    }

    /**
     * Re-age the 7-day window without waiting for a new ping.
     *
     * ⚠ [recompute] otherwise runs only on [bindAccount] and [record], so a
     * process that has been alive for days still reports views that have since
     * aged out of the window. Screens that show the count or the list call this
     * as they open.
     */
    fun refresh() = recompute()

    /** Wipe the bound account's tally (burn). */
    fun wipe() {
        _visits.value = emptyList()
        recompute()
        if (::prefs.isInitialized && acct != null) prefs.edit().remove(key()).apply()
    }

    /** Wipe a specific (possibly non-active) account's tally (local delete). */
    fun wipeAccount(accountId: String) {
        if (::prefs.isInitialized) prefs.edit().remove("$accountId.$K_VISITS").apply()
        if (accountId == acct) { _visits.value = emptyList(); recompute() }
    }

    /** Lift the legacy unprefixed tally under [accountId]. Idempotent. */
    fun migrateLegacyToAccount(accountId: String) {
        if (!::prefs.isInitialized || !prefs.contains(K_VISITS)) return
        prefs.getStringSet(K_VISITS, emptySet())?.let {
            prefs.edit().putStringSet("$accountId.$K_VISITS", it.toSet()).remove(K_VISITS).apply()
        }
    }

    private fun key() = "$acct.$K_VISITS"

    /** The count and the list are derived from the same slice in the same
     *  place, so the number on the row and the people behind it cannot drift
     *  apart. Sorting is cheap: a window this narrow holds a handful of rows. */
    private fun recompute() {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        val inWindow = _visits.value.filter { it.second >= cutoff }
        _recentViews.value = inWindow.size
    }

    private fun persist() {
        prefs.edit().putStringSet(key(), _visits.value.map { "${it.first}:${it.second}" }.toSet()).apply()
    }

    private fun load(storageKey: String): List<Pair<Int, Long>> =
        prefs.getStringSet(storageKey, emptySet())!!.mapNotNull { e ->
            val i = e.lastIndexOf(':')
            if (i <= 0) return@mapNotNull null
            val u = e.substring(0, i).toIntOrNull() ?: return@mapNotNull null
            val t = e.substring(i + 1).toLongOrNull() ?: return@mapNotNull null
            u to t
        }.sortedBy { it.second }

    private const val K_VISITS = "visits"
}
