package app.rcq.android.net

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Multihoming (federation v1) — local store of this account's BACKUP island
 * homes plus the resolved-peer-homes cache.
 *
 * A multi-homed account lives on ≥2 islands at once: the primary (this
 * session's island) plus backup islands registered with the SAME keypair.
 * Identity is the key; the per-island uin is just a local handle. Entries are
 * scoped by [Home.ownUin] (the PRIMARY uin) so multi-account setups don't mix
 * tokens. Mirrors web-chat/src/lib/multihome.ts.
 */
object MultihomeStore {

    /** One backup home of OUR account. [uin]/[jwt] are our handle + token on
     *  THAT island; [ownUin] is the primary-island uin this entry belongs to.
     *  [auto] marks homes picked by the catalogue auto-pick toggle (vs a
     *  manually-entered host); the toggle only adds/removes its own homes. */
    data class Home(
        val ownUin: Int,
        val host: String,
        val uin: Int,
        val jwt: String,
        val addedAt: Long,
        val auto: Boolean = false,
    )

    /** Resolved peer homes + when. Stale entries are still served — a stale
     *  cache IS the failover path when the primary island is unreachable. */
    data class PeerHomes(val homes: List<RcqFederation.Home>, val ts: Long)

    private const val PREFS = "rcq_multihome"
    private const val KEY_HOMES = "homes"
    private const val KEY_PEER_CACHE = "peer_homes"
    private val gson = Gson()
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun list(ownUin: Int): List<Home> = all().filter { it.ownUin == ownUin }

    fun save(home: Home) {
        write(all().filterNot { it.ownUin == home.ownUin && it.host == home.host } + home)
    }

    fun remove(ownUin: Int, host: String) {
        write(all().filterNot { it.ownUin == ownUin && it.host == host })
    }

    /** Replace the token (and per-island uin) after a /auth/recover refresh. */
    fun updateCreds(ownUin: Int, host: String, uin: Int, jwt: String) {
        write(all().map { if (it.ownUin == ownUin && it.host == host) it.copy(uin = uin, jwt = jwt) else it })
    }

    /** Promote bookkeeping (§5a.5): drop the promoted host's backup entry,
     *  re-key this account's remaining entries to the NEW primary uin, and file
     *  the old primary as a manual backup home (senders keep depositing there
     *  until they re-resolve the record, so its mailbox must stay drained). */
    fun promoteSwap(oldOwnUin: Int, newOwnUin: Int, promotedHost: String, oldPrimary: Home) {
        write(
            all().filterNot { it.ownUin == oldOwnUin && it.host == promotedHost }
                .map { if (it.ownUin == oldOwnUin) it.copy(ownUin = newOwnUin) else it } + oldPrimary,
        )
    }

    private fun all(): List<Home> = runCatching {
        val raw = prefs.getString(KEY_HOMES, null) ?: return emptyList()
        gson.fromJson<List<Home>>(raw, object : TypeToken<List<Home>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    private fun write(list: List<Home>) {
        prefs.edit().putString(KEY_HOMES, gson.toJson(list)).apply()
    }

    // ── resolved peer-homes cache (for deposit-to-all + send failover) ──

    fun cachedPeerHomes(peerUin: Int): PeerHomes? = runCatching {
        val raw = prefs.getString(KEY_PEER_CACHE, null) ?: return null
        val map: Map<String, PeerHomes> =
            gson.fromJson(raw, object : TypeToken<Map<String, PeerHomes>>() {}.type) ?: return null
        map[peerUin.toString()]
    }.getOrNull()

    fun cachePeerHomes(peerUin: Int, homes: List<RcqFederation.Home>) {
        runCatching {
            val raw = prefs.getString(KEY_PEER_CACHE, null)
            val map: MutableMap<String, PeerHomes> =
                if (raw == null) mutableMapOf()
                else gson.fromJson(raw, object : TypeToken<MutableMap<String, PeerHomes>>() {}.type) ?: mutableMapOf()
            map[peerUin.toString()] = PeerHomes(homes, System.currentTimeMillis())
            prefs.edit().putString(KEY_PEER_CACHE, gson.toJson(map)).apply()
        }
    }
}
