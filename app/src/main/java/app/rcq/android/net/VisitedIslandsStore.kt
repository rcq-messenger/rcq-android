package app.rcq.android.net

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Cross-island GROUPS (room-host, federation §5c) — local store of GUEST
 * registrations on other islands plus the foreign-group alias map.
 *
 * A group lives entirely on ONE island; joining one from another island means
 * guest-registering there (recover-first, SAME keypair — the multihome
 * mechanic) for a per-island (uin, jwt). Unlike multihome backup homes these
 * are PRIVATE: never published in the signed home-island record (group
 * membership is not an addressing fact).
 *
 * Foreign group ids: per-island ints collide across islands, and threads /
 * unread / routes key groups by a plain int. Each foreign group gets a stable
 * NEGATIVE local alias id (server ids are positive); the API boundary
 * translates alias ↔ (host, remoteId).
 *
 * Per-account from day one ([bindAccount] on launch + every switch), mirroring
 * CrossIslandStore. Mirrors web-chat's visited-islands.ts.
 */
object VisitedIslandsStore {

    data class Visited(
        val host: String,
        val uin: Int,     // per-island uin of this identity (same keys as primary)
        val jwt: String,
        val addedAt: Long,
    )

    data class AliasRef(
        val host: String,
        val remoteId: Int,
        val aliasId: Int, // negative, stable per account
    )

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()
    private const val K_VISITED = "visited.v1"
    private const val K_ALIAS = "fgroup_alias.v1"

    /** Active account id; null before any account is bound (all reads empty). */
    private var acct: String? = null

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) {
            prefs = ctx.applicationContext.getSharedPreferences("rcq_visited", Context.MODE_PRIVATE)
        }
    }

    fun bindAccount(accountId: String?) {
        acct = accountId
    }

    private fun key(suffix: String): String? = acct?.let { "$it.$suffix" }

    // ── visited islands ──────────────────────────────────────────────

    fun list(): List<Visited> {
        val k = key(K_VISITED) ?: return emptyList()
        val raw = prefs.getString(k, "[]") ?: "[]"
        val type = object : TypeToken<List<Visited>>() {}.type
        return runCatching { gson.fromJson<List<Visited>>(raw, type) }.getOrNull() ?: emptyList()
    }

    fun get(host: String): Visited? = list().firstOrNull { it.host == host.lowercase() }

    fun save(v: Visited) {
        val k = key(K_VISITED) ?: return
        val next = list().filterNot { it.host == v.host } + v.copy(host = v.host.lowercase())
        prefs.edit().putString(k, gson.toJson(next)).apply()
    }

    /** Refresh the stored creds after a 401 → recover round-trip. */
    fun updateCreds(host: String, uin: Int, jwt: String) {
        get(host)?.let { save(it.copy(uin = uin, jwt = jwt)) }
    }

    // ── foreign-group alias ids ──────────────────────────────────────

    private fun aliases(): List<AliasRef> {
        val k = key(K_ALIAS) ?: return emptyList()
        val raw = prefs.getString(k, "[]") ?: "[]"
        val type = object : TypeToken<List<AliasRef>>() {}.type
        return runCatching { gson.fromJson<List<AliasRef>>(raw, type) }.getOrNull() ?: emptyList()
    }

    fun isForeignGroupId(id: Int): Boolean = id < 0

    /** Stable local alias for (host, remoteId); allocated on first sight. */
    fun aliasFor(host: String, remoteId: Int): Int {
        val h = host.lowercase()
        val all = aliases()
        all.firstOrNull { it.host == h && it.remoteId == remoteId }?.let { return it.aliasId }
        val k = key(K_ALIAS) ?: return -1
        val aliasId = -(1000 + all.size) // negative: server ids are positive
        prefs.edit().putString(k, gson.toJson(all + AliasRef(h, remoteId, aliasId))).apply()
        return aliasId
    }

    fun refByAlias(aliasId: Int): AliasRef? = aliases().firstOrNull { it.aliasId == aliasId }

    /** Wipe a specific (possibly non-active) account's slots (burn / delete). */
    fun wipeAccount(accountId: String) {
        if (::prefs.isInitialized) {
            prefs.edit()
                .remove("$accountId.$K_VISITED")
                .remove("$accountId.$K_ALIAS")
                .apply()
        }
    }
}
