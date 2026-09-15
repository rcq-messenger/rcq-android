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

    data class Held(val payload: String, val preview: String)

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
    ) {
        val preview: String get() = msgs.firstOrNull()?.preview ?: note.orEmpty()
        val fromServer: Boolean get() = srvReqId != null
    }

    private const val PREFS = "rcq_ci_requests"
    private const val KEY = "requests"
    private const val KEY_BLOCKED = "blocked"
    /** Server request ids this device already answered, "own:host#id". */
    private const val KEY_ANSWERED = "answered"
    private const val MAX_HELD = 20
    /** §5f anti-abuse: a deposit is open, so a stranger's request costs one
     *  HTTP call. Bound the pending list per account — a flood fills a list
     *  that stops growing, not the disk. */
    internal const val MAX_REQUESTS = 50
    private val gson = Gson()
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun reqKey(ownUin: Int, uin: Int, host: String) = "$ownUin:$uin@${host.lowercase()}"

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
     *  ⚠ The blocked entries go with it BY NUMBER, which is right: they are
     *  this identity's refusals, and a new identity has made none. Nothing
     *  here is a privacy SETTING - a wipe must never quietly turn one of those
     *  off for somebody who turned it on. */
    /** Forget EVERY account's held requests and blocks. Only for the duress
     *  wipe, which leaves no account behind to be selective about. */
    fun wipeAll() {
        if (!::prefs.isInitialized) return
        prefs.edit().remove(KEY).remove(KEY_BLOCKED).remove(KEY_ANSWERED).apply()
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
            .apply()
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

    fun isBlocked(ownUin: Int, uin: Int, host: String): Boolean = blockedSet().contains(reqKey(ownUin, uin, host))

    /** Quarantine one sealed payload. Returns false (caller drops it) when blocked. */
    fun hold(ownUin: Int, uin: Int, host: String, payload: String, preview: String, keyChanged: Boolean = false): Boolean {
        if (isBlocked(ownUin, uin, host)) return false
        val map = all()
        val k = reqKey(ownUin, uin, host)
        val base = map[k] ?: Request(ownUin, uin, host, System.currentTimeMillis(), mutableListOf())
        // Sticky: one mismatched row is enough to warn about the whole request.
        val r = if (keyChanged && !base.keyChanged) base.copy(keyChanged = true) else base
        r.msgs.add(Held(payload, preview))
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
    fun holdContactRequest(ownUin: Int, uin: Int, host: String, nickname: String?, note: String?, keyChanged: Boolean = false): Boolean {
        if (isBlocked(ownUin, uin, host)) return false
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
     *  requests and blocks under the new number, or they vanish from the list
     *  after the move and a blocked sender's deposits start landing again. The
     *  answered server ids go with them (F1), or the poll would offer every
     *  answered request again. */
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
            .apply()
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
    fun clear(ownUin: Int, uin: Int, host: String): Request? {
        val map = all()
        val k = reqKey(ownUin, uin, host)
        val r = map.remove(k)
        writeAll(map)
        return r
    }

    /** Block a sender: drop the request + remember so future deposits are dropped. */
    fun block(ownUin: Int, uin: Int, host: String) {
        clear(ownUin, uin, host)
        val b = blockedSet().apply { add(reqKey(ownUin, uin, host)) }
        prefs.edit().putString(KEY_BLOCKED, gson.toJson(b)).apply()
    }
}
