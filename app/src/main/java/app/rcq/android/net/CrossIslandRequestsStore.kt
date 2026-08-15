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
    ) {
        val preview: String get() = msgs.firstOrNull()?.preview ?: note.orEmpty()
    }

    private const val PREFS = "rcq_ci_requests"
    private const val KEY = "requests"
    private const val KEY_BLOCKED = "blocked"
    private const val MAX_HELD = 20
    /** §5f anti-abuse: a deposit is open, so a stranger's request costs one
     *  HTTP call. Bound the pending list per account — a flood fills a list
     *  that stops growing, not the disk. */
    private const val MAX_REQUESTS = 50
    private val gson = Gson()
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun reqKey(ownUin: Int, uin: Int, host: String) = "$ownUin:$uin@${host.lowercase()}"

    private fun all(): MutableMap<String, Request> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return mutableMapOf()
        gson.fromJson<MutableMap<String, Request>>(raw, object : TypeToken<MutableMap<String, Request>>() {}.type) ?: mutableMapOf()
    }.getOrDefault(mutableMapOf())

    private fun writeAll(m: Map<String, Request>) = prefs.edit().putString(KEY, gson.toJson(m)).apply()

    private fun blockedSet(): MutableSet<String> = runCatching {
        val raw = prefs.getString(KEY_BLOCKED, null) ?: return mutableSetOf()
        gson.fromJson<MutableSet<String>>(raw, object : TypeToken<MutableSet<String>>() {}.type) ?: mutableSetOf()
    }.getOrDefault(mutableSetOf())

    fun isBlocked(ownUin: Int, uin: Int, host: String): Boolean = blockedSet().contains(reqKey(ownUin, uin, host))

    /** Quarantine one sealed payload. Returns false (caller drops it) when blocked. */
    fun hold(ownUin: Int, uin: Int, host: String, payload: String, preview: String): Boolean {
        if (isBlocked(ownUin, uin, host)) return false
        val map = all()
        val k = reqKey(ownUin, uin, host)
        val r = map[k] ?: Request(ownUin, uin, host, System.currentTimeMillis(), mutableListOf())
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
    fun holdContactRequest(ownUin: Int, uin: Int, host: String, nickname: String?, note: String?): Boolean {
        if (isBlocked(ownUin, uin, host)) return false
        val map = all()
        val k = reqKey(ownUin, uin, host)
        val old = map[k]
        if (old == null && map.values.count { it.ownUin == ownUin } >= MAX_REQUESTS) return false
        map[k] = Request(
            ownUin = ownUin,
            uin = uin,
            host = host,
            firstAt = old?.firstAt ?: System.currentTimeMillis(),
            msgs = old?.msgs ?: mutableListOf(),
            nickname = nickname?.takeIf { it.isNotBlank() } ?: old?.nickname,
            note = note?.takeIf { it.isNotBlank() } ?: old?.note,
            contactReq = true,
        )
        writeAll(map)
        return true
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
