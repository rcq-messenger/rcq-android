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
     *  cache IS the failover path when the primary island is unreachable.
     *  [recTs] is the signed record's OWN ts (Unix seconds, 0 if unknown), used
     *  as the anti-rollback floor for a self-pushed record (gossip B1). */
    data class PeerHomes(val homes: List<RcqFederation.Home>, val ts: Long, val recTs: Int = 0)

    /** A backup home removed on THIS install, until a record without it is
     *  confirmed on the primary island (see [dropped]). Looked up by [sk], the
     *  account's signing key, which is what the record belongs to and which
     *  survives a promote or a number move; [ownUin] is kept only so the burn
     *  sweep ([wipeOwn]) finds the rows of the number it erases. */
    data class Dropped(val ownUin: Int, val sk: String, val host: String, val at: Long)

    private const val PREFS = "rcq_multihome"
    private const val KEY_HOMES = "homes"
    private const val KEY_PEER_CACHE = "peer_homes"
    private const val KEY_DROPPED = "dropped_homes"
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

    /** Forget every backup island registered under [ownUin].
     *
     *  ⚠⚠ These rows carry a JWT per island, so a burned identity that is not
     *  swept from here leaves LIVE CREDENTIALS to its backup mailboxes on disk,
     *  and the 30s multihome drain keeps presenting them. Burn wipes eight
     *  other stores and missed this one. */
    /** Forget EVERY account's backup islands. Only for the duress wipe, which
     *  leaves no account behind to be selective about. */
    fun wipeAll() {
        if (!::prefs.isInitialized) return
        prefs.edit().remove(KEY_HOMES).remove(KEY_PEER_CACHE).remove(KEY_DROPPED).apply()
    }

    fun wipeOwn(ownUin: Int) {
        if (!::prefs.isInitialized) return
        write(all().filterNot { it.ownUin == ownUin })
        writeDropped(allDropped().filterNot { it.ownUin == ownUin })
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

    /** An island-proven UIN move of the PRIMARY (the migrate response, or
     *  `moved_from` from /auth/refresh, never a socket frame): re-file this
     *  account's backup homes under the new number. Same shape as
     *  [promoteSwap] without the host swap, since the primary island did not
     *  change. Without it the backup drain and the credential lookup for rooms
     *  hosted on a backup island both come back empty after a move (#986(a)).
     *  Must run before start() republishes the signed home record, which is
     *  assembled from this store. */
    fun rekeyOwner(oldOwnUin: Int, newOwnUin: Int) {
        if (!::prefs.isInitialized || oldOwnUin == newOwnUin) return
        val before = all()
        val after = rekeyHomes(before, oldOwnUin, newOwnUin)
        if (after != before) write(after)
    }

    /** Pure half of [rekeyOwner]. An entry the new number already holds for
     *  the same host wins and the old one is dropped: never overwrite, never
     *  keep two tokens for one island. */
    internal fun rekeyHomes(list: List<Home>, oldOwnUin: Int, newOwnUin: Int): List<Home> {
        if (oldOwnUin == newOwnUin) return list
        val taken = list.filter { it.ownUin == newOwnUin }.map { it.host.lowercase() }.toSet()
        return list.mapNotNull {
            when {
                it.ownUin != oldOwnUin -> it
                it.host.lowercase() in taken -> null
                else -> it.copy(ownUin = newOwnUin)
            }
        }
    }

    // ── hosts removed here, kept out of the published record ──
    //
    // ⚠⚠ The signed home record CARRIES OVER every published home this install
    // does not know (Session.publishHomeIslandRecord): a backup switched on in
    // the web or on a second phone must not be unpublished by this one. The
    // same rule made a backup removed HERE come straight back from the old
    // record on the next publish, under a fresh ts, so a removed or deleted
    // copy stayed advertised to senders for good (report 1064). A host removed
    // on this install is therefore left out of the carry-over until the
    // primary island has confirmed a record without it; from then on the
    // published record itself no longer names it, and the entry goes, so a
    // later add on another device is carried over again as before.

    /** Remember that [host] was removed from the account signed by [sk]. */
    fun markDropped(ownUin: Int, sk: String, host: String) {
        if (!::prefs.isInitialized) return
        val h = host.lowercase()
        writeDropped(allDropped().filterNot { it.sk == sk && it.host == h } + Dropped(ownUin, sk, h, System.currentTimeMillis()))
    }

    /** The hosts removed on this install from the account signed by [sk] and
     *  not yet confirmed gone from its published record. */
    fun dropped(sk: String): Set<String> =
        allDropped().filter { it.sk == sk }.map { it.host }.toSet()

    /** [hosts] are confirmed out of the published record, or were added back. */
    fun clearDropped(sk: String, hosts: Collection<String>) {
        if (!::prefs.isInitialized || hosts.isEmpty()) return
        val gone = hosts.map { it.lowercase() }.toSet()
        val before = allDropped()
        val after = before.filterNot { it.sk == sk && it.host in gone }
        if (after.size != before.size) writeDropped(after)
    }

    private fun allDropped(): List<Dropped> = runCatching {
        val raw = prefs.getString(KEY_DROPPED, null) ?: return emptyList()
        gson.fromJson<List<Dropped>>(raw, object : TypeToken<List<Dropped>>() {}.type) ?: emptyList()
    }.getOrDefault(emptyList())

    private fun writeDropped(list: List<Dropped>) {
        prefs.edit().putString(KEY_DROPPED, gson.toJson(list)).apply()
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

    fun cachePeerHomes(peerUin: Int, homes: List<RcqFederation.Home>, recTs: Int = 0) {
        runCatching {
            val raw = prefs.getString(KEY_PEER_CACHE, null)
            val map: MutableMap<String, PeerHomes> =
                if (raw == null) mutableMapOf()
                else gson.fromJson(raw, object : TypeToken<MutableMap<String, PeerHomes>>() {}.type) ?: mutableMapOf()
            map[peerUin.toString()] = PeerHomes(homes, System.currentTimeMillis(), recTs)
            prefs.edit().putString(KEY_PEER_CACHE, gson.toJson(map)).apply()
        }
    }
}
