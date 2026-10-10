package app.rcq.android.net

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Variant A — cross-island "message requests" (consent).
 *
 * Cross-island delivery is permissionless (open mailbox + sealed deposit, like
 * email): anyone who knows your uin@host can seal a message into your queue.
 * Same-island has a contact-request approval flow; cross-island has none. So
 * rather than auto-surfacing an unknown cross-island sender into the chat list,
 * we QUARANTINE their messages here until the user Accepts (the sender becomes a
 * normal cross-island contact and the held messages replay) or Blocks. We hold
 * the SEALED payload (re-fed through ingest verbatim on Accept, so it files with
 * the correct sender + kind) plus a plaintext preview captured at quarantine.
 * Entries scoped by [Request.ownUin] (multi-account safety). Mirrors web-chat's
 * crossisland-requests.ts + iOS CrossIslandRequestsStore.
 */
object CrossIslandRequestsStore {

    /** [id] and [spub] (the sealing key, base64; empty for a v=2 row, whose
     *  ratchet vouches for the sender) let the sender take a held message back
     *  with a delete for everyone, see [retract]. Null on rows held before. */
    data class Held(val payload: String, val preview: String, val id: String? = null, val spub: String? = null)

    data class Request(
        val ownUin: Int,
        val uin: Int,
        val host: String,
        val firstAt: Long,
        val msgs: MutableList<Held>,
        // §5f: a row opened by an explicit `contactreq` rather than by
        // quarantining a message. [nickname]/[note] come from the envelope, so
        // the row renders a name and a greeting with no card fetch. gson leaves
        // them null/false on entries written before §5f.
        val nickname: String? = null,
        val note: String? = null,
        val contactReq: Boolean = false,
        // The row claims the address of a contact we already accepted, but was
        // signed by a different key than the one pinned for them. Shown on the
        // row: it is a stranger until the person looks, never a silent merge.
        val keyChanged: Boolean = false,
        // F1 (15.09 spec): the row also stands for pending request [srvReqId]
        // on island [host], addressed to our guest copy [srvGuestUin] there.
        // Null on every row that did not come from a guest poll.
        val srvReqId: Int? = null,
        val srvGuestUin: Int? = null,
        /** Accepts that added the contact here but did not reach them. */
        val srvAcceptTries: Int = 0,
        /** The card fetched on accept differs from a key this device saw for
         *  that number in a room on the island: the person has to confirm. */
        val viaKeyChanged: Boolean = false,
        /** The verified signing key (base64) the latest row was sealed under,
         *  where one was: a §5f request and a v=1 same-island row keep it here,
         *  because neither puts one in [Held.spub]. Blocking the row blocks
         *  this key too, see [keysOf]. */
        val sk: String? = null,
    ) {
        val preview: String get() = msgs.firstOrNull()?.preview ?: note.orEmpty()
        val fromServer: Boolean get() = srvReqId != null
    }

    /**
     * What a block knows besides its address.
     *
     * [at] orders it against an unblock (epoch ms; 0 on a block written before
     * this existed), so a `ciack` the island serves again late cannot undo a
     * newer answer. [keys] are the signing keys (base64) of the person blocked:
     * the address is only where their rows came from, and the same key writing
     * from another island, or behind a `from_host` it made up, is the same
     * person. [passContact] marks a block from before Unblock could lift one,
     * at an address that is an accepted contact again: see [stampLegacyMeta].
     */
    data class BlockMeta(
        val at: Long = 0L,
        val keys: List<String> = emptyList(),
        val passContact: Boolean = false,
    )

    /** One blocked address of an account. [host] "" is our own island. */
    data class Blocked(val uin: Int, val host: String, val meta: BlockMeta)

    // ⚠⚠ The blocks (and their metadata and unblock times) are keyed by the
    // ACCOUNT, "<account id>:uin@host", and the requests by the bare number.
    // Two accounts on one phone may hold the same number on two islands, and
    // with the blocks keyed by that number they read and wrote each other's:
    // A = 1234@rcq.app blocking N@is2 silenced N for B = 1234@is2, whose own
    // island that is, handed A's blocked keys to B, and B's Unblock lifted
    // A's block. Builds before this one wrote "<number>:..." rows; [init]
    // copies them to every account holding that number, once, see
    // [migrateLegacyOwners].

    private const val PREFS = "rcq_ci_requests"
    private const val KEY = "requests"
    private const val KEY_BLOCKED = "blocked"
    /** reqKey -> [BlockMeta]. An entry of [KEY_BLOCKED] without one is a block
     *  an older build wrote. */
    private const val KEY_BLOCK_META = "blocked_meta"
    /** reqKey -> when it was last unblocked (epoch ms), see [applyUnblock]. */
    private const val KEY_UNBLOCKED = "unblocked"
    /** An unblock is remembered this long against a late `ciack`: the island
     *  keeps a queued row for 30 days, so nothing older can still arrive. */
    private const val UNBLOCK_MEMORY_MS = 45L * 24 * 3600 * 1000
    /** Server request ids this device already answered, "own:host#id". */
    private const val KEY_ANSWERED = "answered"
    /** Accounts whose blocks keyed by number were copied to their own key. */
    private const val KEY_OWNERS_DONE = "block_owners_done"
    /** Every account's blocks were; nothing keyed by number is left. */
    private const val KEY_OWNERS_MIGRATED = "block_owners_migrated"
    private const val MAX_HELD = 20
    /** §5f anti-abuse: a deposit is open, so a stranger's request costs one
     *  HTTP call. Bound the pending list per account — a flood fills a list
     *  that stops growing, not the disk. */
    internal const val MAX_REQUESTS = 50
    private val gson = Gson()
    private lateinit var prefs: SharedPreferences

    @Synchronized
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Before anything reads a block: a cold push reads them too.
        runCatching { migrateOwners(ctx.applicationContext) }
            .onFailure { android.util.Log.w("RCQci", "block owners: ${it.javaClass.simpleName}") }
    }

    private fun reqKey(ownUin: Int, uin: Int, host: String) = "$ownUin:$uin@${host.lowercase()}"

    /** A block row of account [owner] (its id, never its number). */
    private fun blockKey(owner: String, uin: Int, host: String) = "$owner:$uin@${host.lowercase()}"

    /** Copy the blocks older builds keyed by number to the accounts holding
     *  that number, see [migrateLegacyOwners]. */
    private fun migrateOwners(ctx: Context) {
        if (prefs.getBoolean(KEY_OWNERS_MIGRATED, false)) return
        val accounts = app.rcq.android.data.AccountManager.accounts.value
        val blocked = blockedSet()
        val meta = metaMap()
        val unblocked = unblockedMap()
        val anyLegacy = (blocked + meta.keys + unblocked.keys).any { isLegacyBlockKey(it) }
        if (!anyLegacy) {
            prefs.edit().putBoolean(KEY_OWNERS_MIGRATED, true).apply()
            return
        }
        // A roster not loaded (or gone) is no answer: nothing is copied or
        // dropped until there is one.
        if (accounts.isEmpty()) return
        var complete = true
        val owners = HashMap<String, Int?>()
        for (a in accounts) {
            val n = runCatching { app.rcq.android.data.SecureStore.peekUin(ctx, a.id) }
            if (n.isFailure) complete = false else owners[a.id] = n.getOrNull()
        }
        val done = stringSet(KEY_OWNERS_DONE)
        migrateLegacyOwners(blocked, meta, unblocked, owners, done, complete)
        prefs.edit()
            .putString(KEY_BLOCKED, gson.toJson(blocked))
            .putString(KEY_BLOCK_META, gson.toJson(meta))
            .putString(KEY_UNBLOCKED, gson.toJson(unblocked))
            .putString(KEY_OWNERS_DONE, gson.toJson(done))
            .putBoolean(KEY_OWNERS_MIGRATED, complete)
            .apply()
        blocksChanged()
    }

    internal fun answeredKey(ownUin: Int, host: String, id: Int) = "$ownUin:${host.lowercase()}#$id"

    /** Forget everything held for [ownUin]: pending cross-island requests and
     *  the numbers this identity blocked.
     *
     *  ⚠⚠ Burn wipes SecureStore, the message database, the libsignal stores,
     *  visits, cross-island contacts, visited islands and the roster caches,
     *  and this store was simply missing from that list, so §5f requests
     *  addressed to a burned identity outlived it on disk. Same shape as the
     *  fourth hole of the iOS cross-island leak (f50a7b0).
     *
     *  ⚠ The blocked entries go with it, which is right: they are this
     *  identity's refusals, and a new identity has made none. Nothing here is
     *  a privacy SETTING - a wipe must never quietly turn one of those off for
     *  somebody who turned it on. Only the rows older builds keyed by number
     *  go here; the account's own are swept by id, [wipeOwner]. */
    /** Forget EVERY account's held requests and blocks. Only for the duress
     *  wipe, which leaves no account behind to be selective about. */
    fun wipeAll() {
        if (!::prefs.isInitialized) return
        prefs.edit().remove(KEY).remove(KEY_BLOCKED).remove(KEY_ANSWERED)
            .remove(KEY_BLOCK_META).remove(KEY_UNBLOCKED).remove(KEY_OWNERS_DONE)
            .putBoolean(KEY_OWNERS_MIGRATED, true).apply()
        blocksChanged()
    }

    /** Forget the blocks of account [owner] (its id): its burn, its removal
     *  from this device, the end of a duress view. The requests are swept by
     *  number in [wipeOwn]. */
    fun wipeOwner(owner: String) {
        if (!::prefs.isInitialized || owner.isEmpty()) return
        val pre = "$owner:"
        prefs.edit()
            .putString(KEY_BLOCKED, gson.toJson(blockedSet().filterTo(mutableSetOf()) { !it.startsWith(pre) }))
            .putString(KEY_BLOCK_META, gson.toJson(metaMap().filterKeys { !it.startsWith(pre) }))
            .putString(KEY_UNBLOCKED, gson.toJson(unblockedMap().filterKeys { !it.startsWith(pre) }))
            .apply()
        blocksChanged()
    }

    fun wipeOwn(ownUin: Int) {
        if (!::prefs.isInitialized) return
        val pre = "$ownUin:"
        writeAll(all().filterKeys { !it.startsWith(pre) })
        val keptBlocks = blockedSet().filterTo(mutableSetOf()) { !it.startsWith(pre) }
        val keptAnswered = answeredSet().filterTo(mutableSetOf()) { !it.startsWith(pre) }
        prefs.edit()
            .putString(KEY_BLOCKED, gson.toJson(keptBlocks))
            .putString(KEY_ANSWERED, gson.toJson(keptAnswered))
            .putString(KEY_BLOCK_META, gson.toJson(metaMap().filterKeys { !it.startsWith(pre) }))
            .putString(KEY_UNBLOCKED, gson.toJson(unblockedMap().filterKeys { !it.startsWith(pre) }))
            .apply()
        blocksChanged()
    }

    // ── blocks ─────────────────────────────────────────────────────────
    //
    // ⚠ Read on every ingested row (Session's block decision) and by the push
    // path, so the three block structures are parsed once per change, not once
    // per read. Every write goes through this object, which bumps [blockRev].

    private val blockRev = java.util.concurrent.atomic.AtomicInteger(0)
    private class BlockCache(val rev: Int, val blocked: Set<String>, val meta: Map<String, BlockMeta>)
    @Volatile private var blockCache: BlockCache? = null
    private val _blockRevision = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Moves on every change to the blocks: the roster memo and the Blocked
     *  users screen key on it, because nothing else they watch moves. */
    fun blockRevision(): Int = blockRev.get()

    /** [blockRevision] for a screen to collect. */
    val blockRevisionFlow: kotlinx.coroutines.flow.StateFlow<Int> get() = _blockRevision

    private fun blocksChanged() {
        blockCache = null
        _blockRevision.value = blockRev.incrementAndGet()
    }

    private fun cached(): BlockCache {
        val rev = blockRev.get()
        blockCache?.takeIf { it.rev == rev }?.let { return it }
        return BlockCache(rev, blockedSet(), metaMap()).also { blockCache = it }
    }

    private fun metaMap(): MutableMap<String, BlockMeta> = runCatching {
        val raw = prefs.getString(KEY_BLOCK_META, null) ?: return mutableMapOf()
        gson.fromJson<MutableMap<String, BlockMeta>>(raw, object : TypeToken<MutableMap<String, BlockMeta>>() {}.type)
            // gson leaves a list it did not find null, whatever Kotlin says.
            ?.mapValuesTo(LinkedHashMap()) { (_, m) ->
                @Suppress("SENSELESS_COMPARISON")
                if (m.keys == null) m.copy(keys = emptyList()) else m
            }
            ?: mutableMapOf()
    }.getOrDefault(mutableMapOf())

    private fun unblockedMap(): MutableMap<String, Long> = runCatching {
        val raw = prefs.getString(KEY_UNBLOCKED, null) ?: return mutableMapOf()
        gson.fromJson<MutableMap<String, Long>>(raw, object : TypeToken<MutableMap<String, Long>>() {}.type) ?: mutableMapOf()
    }.getOrDefault(mutableMapOf())

    private fun writeBlocks(blocked: Set<String>, meta: Map<String, BlockMeta>, unblocked: Map<String, Long>) {
        prefs.edit()
            .putString(KEY_BLOCKED, gson.toJson(blocked))
            .putString(KEY_BLOCK_META, gson.toJson(meta))
            .putString(KEY_UNBLOCKED, gson.toJson(unblocked))
            .apply()
        blocksChanged()
    }

    private fun all(): MutableMap<String, Request> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return mutableMapOf()
        gson.fromJson<MutableMap<String, Request>>(raw, object : TypeToken<MutableMap<String, Request>>() {}.type) ?: mutableMapOf()
    }.getOrDefault(mutableMapOf())

    private fun writeAll(m: Map<String, Request>) = prefs.edit().putString(KEY, gson.toJson(m)).apply()

    private fun stringSet(key: String): MutableSet<String> = runCatching {
        val raw = prefs.getString(key, null) ?: return mutableSetOf()
        gson.fromJson<MutableSet<String>>(raw, object : TypeToken<MutableSet<String>>() {}.type) ?: mutableSetOf()
    }.getOrDefault(mutableSetOf())

    private fun blockedSet(): MutableSet<String> = stringSet(KEY_BLOCKED)

    private fun answeredSet(): MutableSet<String> = stringSet(KEY_ANSWERED)

    /** Whether account [owner] (its id) blocked exactly [uin] at [host]. */
    fun isBlocked(owner: String, uin: Int, host: String): Boolean =
        ::prefs.isInitialized && cached().blocked.contains(blockKey(owner, uin, host))

    /** The block at exactly (uin, host), or null when there is none. A block an
     *  older build wrote, and nothing has stamped yet, reads as a plain one. */
    fun blockAt(owner: String, uin: Int, host: String): BlockMeta? {
        if (!::prefs.isInitialized) return null
        val c = cached()
        val k = blockKey(owner, uin, host)
        return if (k in c.blocked) c.meta[k] ?: BlockMeta() else null
    }

    /** Every address account [owner] blocked, our own island's strangers as
     *  host "". */
    fun blockedFor(owner: String): List<Blocked> {
        if (!::prefs.isInitialized) return emptyList()
        val c = cached()
        return listBlocked(c.blocked, c.meta, owner)
    }

    /** The signing keys of everybody account [owner] blocked, canonical base64. */
    fun blockedKeys(owner: String): Set<String> =
        blockedFor(owner).flatMapTo(HashSet()) { it.meta.keys }

    /** Quarantine one sealed payload. Returns false (caller drops it) when
     *  account [owner] blocked the address (null: no account bound, nothing
     *  is blocked). */
    fun hold(
        ownUin: Int, uin: Int, host: String, payload: String, preview: String, keyChanged: Boolean = false,
        id: String? = null, spub: String? = null, sk: String? = null, owner: String?,
    ): Boolean {
        if (owner != null && isBlocked(owner, uin, host)) return false
        val map = all()
        val k = reqKey(ownUin, uin, host)
        val base = map[k] ?: Request(ownUin, uin, host, System.currentTimeMillis(), mutableListOf())
        // Sticky: one mismatched row is enough to warn about the whole request.
        val marked = if (keyChanged && !base.keyChanged) base.copy(keyChanged = true) else base
        val r = if (!sk.isNullOrBlank() && sk != marked.sk) marked.copy(sk = sk) else marked
        r.msgs.add(Held(payload, preview, id, spub))
        while (r.msgs.size > MAX_HELD) r.msgs.removeAt(0)
        map[k] = r
        writeAll(map)
        return true
    }

    /**
     * §5f: open (or refresh) a PENDING cross-island contact request. Unlike
     * [hold] it carries no sealed payload — a `contactreq` is not a message and
     * never enters the message store; the row exists so the request can be
     * Accepted or Blocked in the same list a same-island pending request shows
     * up in. A second `request` from the same sender refreshes the one row
     * rather than adding another. Returns false when the sender is blocked or
     * the pending list is full (caller drops the envelope).
     */
    fun holdContactRequest(
        ownUin: Int, uin: Int, host: String, nickname: String?, note: String?, keyChanged: Boolean = false, sk: String? = null,
        owner: String?,
    ): Boolean {
        if (owner != null && isBlocked(owner, uin, host)) return false
        val map = all()
        val k = reqKey(ownUin, uin, host)
        val old = map[k]
        if (old == null && map.values.count { it.ownUin == ownUin } >= MAX_REQUESTS) return false
        // `copy` of the old row, so the fields of a guest-poll row that
        // already stands at this address (F1) survive a §5f refresh.
        val base = old ?: Request(ownUin, uin, host, System.currentTimeMillis(), mutableListOf())
        map[k] = base.copy(
            nickname = nickname?.takeIf { it.isNotBlank() } ?: old?.nickname,
            note = note?.takeIf { it.isNotBlank() } ?: old?.note,
            contactReq = true,
            keyChanged = keyChanged || old?.keyChanged == true,
            sk = sk?.takeIf { it.isNotBlank() } ?: old?.sk,
        )
        writeAll(map)
        return true
    }

    // ── F1: pending requests on the islands this device visited ──────

    fun isAnswered(ownUin: Int, host: String, id: Int): Boolean = answeredSet().contains(answeredKey(ownUin, host, id))

    /** This device answered server request [id] on [host] (accept, decline or
     *  block, here or on a sibling device): the poll never shows it again. */
    fun markAnswered(ownUin: Int, host: String, id: Int) {
        if (!::prefs.isInitialized || id <= 0) return
        val s = answeredSet()
        if (s.add(answeredKey(ownUin, host, id))) prefs.edit().putString(KEY_ANSWERED, gson.toJson(s)).apply()
    }

    /** Merge one row the island listed into the request list. Returns true when
     *  the list changed. See [mergeServerRow]. */
    fun upsertServerRequest(ownUin: Int, uin: Int, host: String, id: Int, guestUin: Int, nickname: String?): Boolean {
        if (!::prefs.isInitialized) return false
        val map = all()
        val changed = mergeServerRow(map, blockedSet(), answeredSet(), ownUin, uin, host, id, guestUin, nickname, System.currentTimeMillis())
        if (changed) writeAll(map)
        return changed
    }

    /**
     * Pure half of [upsertServerRequest].
     *
     * ⚠ One row per (number, island). A §5f or held row the same sender already
     * has here absorbs the server fields and keeps its own [Request.firstAt],
     * greeting and held messages; the island's nickname only fills a gap,
     * because a name inside a signed envelope beats a name the island serves.
     * Blocked senders, answered ids and a full list are refused.
     */
    internal fun mergeServerRow(
        map: MutableMap<String, Request>,
        blocked: Set<String>,
        answered: Set<String>,
        ownUin: Int,
        uin: Int,
        host: String,
        id: Int,
        guestUin: Int,
        nickname: String?,
        now: Long,
    ): Boolean {
        val k = reqKey(ownUin, uin, host)
        if (k in blocked || answeredKey(ownUin, host, id) in answered) return false
        val old = map[k]
        if (old == null && map.values.count { it.ownUin == ownUin } >= MAX_REQUESTS) return false
        val sameId = old?.srvReqId == id
        val next = (old ?: Request(ownUin, uin, host.lowercase(), now, mutableListOf())).copy(
            nickname = old?.nickname?.takeIf { it.isNotBlank() } ?: nickname?.takeIf { it.isNotBlank() },
            srvReqId = id,
            srvGuestUin = guestUin,
            srvAcceptTries = if (sameId) old!!.srvAcceptTries else 0,
            viaKeyChanged = if (sameId) old!!.viaKeyChanged else false,
        )
        if (next == old) return false
        map[k] = next
        return true
    }

    /** After a successful poll of [host]: server ids the island no longer lists
     *  are stripped from their rows (a row that was nothing else goes), and
     *  answered ids for them are forgotten so the set does not grow forever. */
    fun reconcileServerRequests(ownUin: Int, host: String, liveIds: Set<Int>) {
        if (!::prefs.isInitialized) return
        val map = all()
        if (reconcileRows(map, ownUin, host, liveIds)) writeAll(map)
        val before = answeredSet()
        val after = pruneAnswered(before, ownUin, host, liveIds)
        if (after.size != before.size) prefs.edit().putString(KEY_ANSWERED, gson.toJson(after)).apply()
    }

    internal fun reconcileRows(map: MutableMap<String, Request>, ownUin: Int, host: String, liveIds: Set<Int>): Boolean {
        var changed = false
        for ((k, r) in map.entries.toList()) {
            if (r.ownUin != ownUin || !r.host.equals(host, ignoreCase = true)) continue
            val id = r.srvReqId ?: continue
            if (id in liveIds) continue
            changed = true
            if (r.msgs.isEmpty() && !r.contactReq) map.remove(k)
            else map[k] = r.copy(srvReqId = null, srvGuestUin = null, srvAcceptTries = 0, viaKeyChanged = false)
        }
        return changed
    }

    internal fun pruneAnswered(set: Set<String>, ownUin: Int, host: String, liveIds: Set<Int>): Set<String> {
        val pre = "$ownUin:${host.lowercase()}#"
        return set.filterTo(LinkedHashSet()) { e ->
            if (!e.startsWith(pre)) return@filterTo true
            val id = e.removePrefix(pre).toIntOrNull()
            id != null && id in liveIds
        }
    }

    /** Rewrite the row at (uin, host), if there is one. */
    fun updateRow(ownUin: Int, uin: Int, host: String, f: (Request) -> Request) {
        if (!::prefs.isInitialized) return
        val map = all()
        val k = reqKey(ownUin, uin, host)
        val old = map[k] ?: return
        val next = f(old)
        if (next != old) {
            map[k] = next
            writeAll(map)
        }
    }

    /** An island-proven UIN move (the migrate response, or `moved_from` from
     *  /auth/refresh, never a socket frame): re-file this account's pending
     *  requests under the new number, or they vanish from the list after the
     *  move. The answered server ids go with them (F1), or the poll would offer
     *  every answered request again. The blocks are keyed by the account and
     *  stay where they are; only rows an older build keyed by the number, not
     *  copied yet, move with it. */
    fun rekeyOwner(oldOwnUin: Int, newOwnUin: Int) {
        if (!::prefs.isInitialized || oldOwnUin == newOwnUin) return
        val reqs = all()
        val blocked = blockedSet()
        val nextReqs = rekeyRequests(reqs, oldOwnUin, newOwnUin)
        val nextBlocked = rekeyBlocked(blocked, oldOwnUin, newOwnUin)
        val nextAnswered = rekeyBlocked(answeredSet(), oldOwnUin, newOwnUin)
        prefs.edit()
            .putString(KEY, gson.toJson(nextReqs))
            .putString(KEY_BLOCKED, gson.toJson(nextBlocked))
            .putString(KEY_ANSWERED, gson.toJson(nextAnswered))
            .putString(KEY_BLOCK_META, gson.toJson(rekeyMap(metaMap(), oldOwnUin, newOwnUin)))
            .putString(KEY_UNBLOCKED, gson.toJson(rekeyMap(unblockedMap(), oldOwnUin, newOwnUin)))
            .apply()
        blocksChanged()
    }

    /** [rekeyBlocked] for the maps beside the blocks. An entry the new number
     *  already holds wins, as for the requests. */
    internal fun <V> rekeyMap(map: Map<String, V>, oldOwnUin: Int, newOwnUin: Int): Map<String, V> {
        if (oldOwnUin == newOwnUin) return map
        val pre = "$oldOwnUin:"
        val out = LinkedHashMap<String, V>()
        map.forEach { (k, v) -> if (!k.startsWith(pre)) out[k] = v }
        map.forEach { (k, v) ->
            if (!k.startsWith(pre)) return@forEach
            val nk = "$newOwnUin:" + k.removePrefix(pre)
            if (nk !in out) out[nk] = v
        }
        return out
    }

    /** Pure half of [rekeyOwner] for the requests. A row the new number
     *  already holds for the same sender wins; the old one is dropped. */
    internal fun rekeyRequests(map: Map<String, Request>, oldOwnUin: Int, newOwnUin: Int): Map<String, Request> {
        if (oldOwnUin == newOwnUin) return map
        val pre = "$oldOwnUin:"
        val out = LinkedHashMap<String, Request>()
        map.forEach { (k, v) -> if (!k.startsWith(pre)) out[k] = v }
        map.forEach { (k, v) ->
            if (!k.startsWith(pre)) return@forEach
            val nk = "$newOwnUin:" + k.removePrefix(pre)
            if (nk !in out) out[nk] = v.copy(ownUin = newOwnUin)
        }
        return out
    }

    /** Pure half of [rekeyOwner] for the blocks (and the answered ids, which
     *  share the "own:" prefix): a set, so an entry both numbers hold simply
     *  stays one entry. */
    internal fun rekeyBlocked(set: Set<String>, oldOwnUin: Int, newOwnUin: Int): Set<String> {
        if (oldOwnUin == newOwnUin) return set
        val pre = "$oldOwnUin:"
        return set.mapTo(LinkedHashSet()) { if (it.startsWith(pre)) "$newOwnUin:" + it.removePrefix(pre) else it }
    }

    fun list(ownUin: Int): List<Request> = all().values.filter { it.ownUin == ownUin }.sortedByDescending { it.firstAt }

    fun count(ownUin: Int): Int = all().values.count { it.ownUin == ownUin }

    /** Drop a request and return it (after Accept replays its messages). */
    /**
     * The sender deleted a held message for everyone before we answered. It
     * used to stay in the request, because a control from somebody not yet
     * accepted is dropped at the gate, and accepting then replayed a message
     * its author had taken back. Only the key that sealed the held message
     * can retract it. A row left with nothing in it goes too, unless it is an
     * explicit contact request or stands for one on an island's pending list.
     * Returns true when something was removed.
     */
    fun retract(ownUin: Int, uin: Int, host: String, targetId: String, spub: String): Boolean {
        val map = all()
        val k = reqKey(ownUin, uin, host)
        val r = map[k] ?: return false
        val before = r.msgs.size
        r.msgs.removeAll { it.id == targetId && it.spub == spub }
        if (r.msgs.size == before) return false
        if (r.msgs.isEmpty() && !r.contactReq && r.srvReqId == null) map.remove(k) else map[k] = r
        writeAll(map)
        return true
    }

    fun clear(ownUin: Int, uin: Int, host: String): Request? {
        val map = all()
        val k = reqKey(ownUin, uin, host)
        val r = map.remove(k)
        writeAll(map)
        return r
    }

    /** The signing keys a request row was sealed under, for the block that
     *  answers it. Every one of them signed a row the person was shown as this
     *  request, so each belongs to whoever sent it. */
    fun keysOf(r: Request?): List<String> =
        if (r == null) emptyList()
        else (listOf(r.sk) + r.msgs.map { it.spub }).mapNotNull { canonicalKey(it) }.distinct()

    /**
     * Block a sender for account [owner]: drop [ownUin]'s request and remember
     * the address, plus [keys], the signing keys known for the person (see
     * [BlockMeta]).
     *
     * [at] null is a block made HERE, now, and always lands. A block another
     * device made carries its own time and lands only when it is newer than
     * the last unblock of that address, see [applyBlock]. [timeless]: that
     * time is not the sender's but when the answer reached us (a build that
     * sends none), see [applyBlock]. Returns true when it landed.
     */
    @Synchronized
    fun block(
        owner: String, ownUin: Int, uin: Int, host: String, keys: Collection<String> = emptyList(),
        at: Long? = null, timeless: Boolean = false,
    ): Boolean {
        if (!::prefs.isInitialized) return false
        val blocked = blockedSet()
        val meta = metaMap()
        val unblocked = unblockedMap()
        val now = System.currentTimeMillis()
        val k = blockKey(owner, uin, host)
        if (!applyBlock(blocked, meta, unblocked, k, keys, at ?: now, local = at == null, timeless = timeless)) return false
        clear(ownUin, uin, host)
        writeBlocks(blocked, meta, pruneUnblocked(unblocked, now))
        return true
    }

    /**
     * Lift the block at exactly (uin, host), and nothing else: not the same
     * number on another island, not a key. [at] as for [block]: an unblock
     * another device made lands only when it is newer than the block here.
     * Returns true when a block was lifted.
     *
     * ⚠⚠ There was no way to do this at all. Unblock in Settings cleared the
     * bare number only, so an address blocked from a request row stayed
     * blocked for good, invisibly, and everything that person sent was
     * dropped after the ack: one tick, and a "New message" that led nowhere
     * (report 1064).
     */
    @Synchronized
    fun unblock(owner: String, uin: Int, host: String, at: Long? = null): Boolean {
        if (!::prefs.isInitialized) return false
        val blocked = blockedSet()
        val meta = metaMap()
        val unblocked = unblockedMap()
        val now = System.currentTimeMillis()
        val lifted = applyUnblock(blocked, meta, unblocked, blockKey(owner, uin, host), at ?: now, local = at == null)
        writeBlocks(blocked, meta, pruneUnblocked(unblocked, now))
        return lifted
    }

    /**
     * Give every block of [ownUin] an older build wrote its [BlockMeta], once.
     *
     * ⚠ Nothing is lifted here. The question is only what such a block did
     * before this build, so that it keeps doing exactly that: older builds had
     * no way to lift a block from a request row, and the person's way back to
     * somebody they blocked was to add them again. Their messages flowed after
     * that (an accepted contact passed the gate before the block was looked
     * at), unless the bare number the same builds also wrote was still there.
     * So a block at an address that is an accepted contact, with no bare entry
     * for the number, keeps letting that contact's messages through
     * ([BlockMeta.passContact]); everything else stays blocked. Either way the
     * block is listed in Settings, where the person can lift it.
     */
    @Synchronized
    fun stampLegacyBlocks(owner: String, contactAt: (uin: Int, host: String) -> Boolean, bare: Set<Int>) {
        if (!::prefs.isInitialized) return
        val blocked = blockedSet()
        val meta = metaMap()
        if (!stampLegacyMeta(blocked, meta, owner, contactAt, bare)) return
        writeBlocks(blocked, meta, unblockedMap())
    }

    // ── pure halves, pinned in BlockStoreTest ──────────────────────────

    /** One base64 spelling per key (padding, line breaks and the URL alphabet
     *  all name the same 32 bytes), or null for anything that is not an
     *  Ed25519 public key. */
    fun canonicalKey(b64: String?): String? {
        if (b64.isNullOrBlank()) return null
        val t = b64.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/')
        // The basic decoder takes the padding or its absence, and nothing else.
        val raw = runCatching { java.util.Base64.getDecoder().decode(t) }.getOrNull() ?: return null
        return if (raw.size == 32) java.util.Base64.getEncoder().encodeToString(raw) else null
    }

    internal fun applyBlock(
        blocked: MutableSet<String>,
        meta: MutableMap<String, BlockMeta>,
        unblocked: MutableMap<String, Long>,
        k: String,
        keys: Collection<String>,
        at: Long,
        local: Boolean,
        timeless: Boolean = false,
    ): Boolean {
        // A block from another device that is not newer than the last unblock
        // here is the old answer coming round again (a carbon the island
        // served late), not a new one. Strictly newer: at the same time the
        // state here stays as it is.
        if (!local && at <= (unblocked[k] ?: Long.MIN_VALUE)) return false
        val old = meta[k]?.takeIf { k in blocked }
        blocked.add(k)
        meta[k] = BlockMeta(
            at = maxOf(old?.at ?: 0L, at),
            keys = (old?.keys.orEmpty() + keys.mapNotNull { canonicalKey(it) }).distinct(),
            // A block made now is a block, whatever an older one let through.
            // A timeless one from another device (no time of its own; it may
            // carry when it reached us) is most likely the echo of that older
            // block itself, and changes nothing about it.
            passContact = if (!local && (at == 0L || timeless)) old?.passContact == true else false,
        )
        unblocked.remove(k)
        return true
    }

    internal fun applyUnblock(
        blocked: MutableSet<String>,
        meta: MutableMap<String, BlockMeta>,
        unblocked: MutableMap<String, Long>,
        k: String,
        at: Long,
        local: Boolean,
    ): Boolean {
        // Strictly newer than the block, as for [applyBlock].
        if (!local && k in blocked && at <= (meta[k]?.at ?: 0L)) return false
        val lifted = blocked.remove(k)
        meta.remove(k)
        // Remembered even when nothing was blocked here: a block made on a
        // sibling before this unblock may still be on its way.
        unblocked[k] = maxOf(unblocked[k] ?: 0L, at)
        return lifted
    }

    internal fun pruneUnblocked(unblocked: MutableMap<String, Long>, now: Long): MutableMap<String, Long> {
        unblocked.entries.removeAll { now - it.value > UNBLOCK_MEMORY_MS }
        return unblocked
    }

    internal fun listBlocked(blocked: Set<String>, meta: Map<String, BlockMeta>, owner: String): List<Blocked> {
        val pre = "$owner:"
        return blocked.mapNotNull { k ->
            if (!k.startsWith(pre)) return@mapNotNull null
            val addr = k.removePrefix(pre)
            val at = addr.indexOf('@')
            if (at <= 0) return@mapNotNull null
            val uin = addr.substring(0, at).toIntOrNull() ?: return@mapNotNull null
            Blocked(uin, addr.substring(at + 1), meta[k] ?: BlockMeta())
        }.sortedWith(compareBy({ it.host }, { it.uin }))
    }

    /** Pure half of [stampLegacyBlocks]. Returns true when [meta] changed. */
    internal fun stampLegacyMeta(
        blocked: Set<String>,
        meta: MutableMap<String, BlockMeta>,
        owner: String,
        contactAt: (uin: Int, host: String) -> Boolean,
        bare: Set<Int>,
    ): Boolean {
        var changed = false
        for (b in listBlocked(blocked, meta, owner)) {
            val k = blockKey(owner, b.uin, b.host)
            if (k in meta) continue
            meta[k] = BlockMeta(
                at = 0L,
                passContact = b.host.isNotEmpty() && b.uin !in bare && contactAt(b.uin, b.host),
            )
            changed = true
        }
        return changed
    }

    /** A block row an older build keyed by the bare number. */
    internal fun isLegacyBlockKey(k: String): Boolean {
        val i = k.indexOf(':')
        return i > 0 && k.substring(0, i).toIntOrNull() != null
    }

    /**
     * Pure half of [migrateOwners]: copy every block, its metadata and its
     * unblock time keyed by a bare number to each account in [owners] (id ->
     * its number, null for one holding none) that holds that number and is not
     * in [done]. Two accounts here with that number both get it: which one
     * made it cannot be told, and a block must never be lifted by a migration.
     * A row the account already has in its own key wins. Every account looked
     * at goes into [done]. [complete] (every account on the device could be
     * read): the rows keyed by number go, so no account added later inherits
     * them. Returns true when anything changed.
     */
    internal fun migrateLegacyOwners(
        blocked: MutableSet<String>,
        meta: MutableMap<String, BlockMeta>,
        unblocked: MutableMap<String, Long>,
        owners: Map<String, Int?>,
        done: MutableSet<String>,
        complete: Boolean,
    ): Boolean {
        var changed = false
        val legacy = (blocked + meta.keys + unblocked.keys).filterTo(LinkedHashSet()) { isLegacyBlockKey(it) }
        for (k in legacy) {
            val n = k.substringBefore(':').toInt()
            val rest = k.substringAfter(':')
            for ((id, held) in owners) {
                if (held != n || id in done) continue
                val nk = "$id:$rest"
                if (nk in blocked || nk in unblocked) continue
                if (k in blocked) {
                    blocked.add(nk)
                    meta[k]?.let { meta[nk] = it }
                    changed = true
                }
                unblocked[k]?.let { unblocked[nk] = it; changed = true }
            }
        }
        if (done.addAll(owners.keys)) changed = true
        if (complete && legacy.isNotEmpty()) {
            blocked.removeAll(legacy)
            legacy.forEach { meta.remove(it); unblocked.remove(it) }
            changed = true
        }
        return changed
    }
}
