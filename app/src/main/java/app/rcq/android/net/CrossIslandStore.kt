package app.rcq.android.net

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Federation Layer B (F2) — local store of cross-island contacts.
 *
 * A peer on ANOTHER island is not a flagship user, so it can't live in the
 * server-side /contacts list (there's no cross-island contact-request handshake).
 * We keep cross-island contacts on-device, keyed `uin@host` (uin is per-island, so
 * the host is part of the handle's identity). Mirrors web-chat's crossisland-store.
 *
 * Per-account: a cross-island contact added on one local account must NOT bleed
 * into another (founder report on iOS: `911@api` added on the is2 account showed
 * up on the 911 account, where it read as "I added myself"). Storage is keyed by
 * the active account id; [bindAccount] re-points the slot on launch and on every
 * account switch (Session.rebindTo). The old single global "contacts.v1" key is
 * left orphaned, same as iOS.
 *
 * Carries the peer's pinned public keys (from their island's open key card) for
 * sealing + display; the actual send re-resolves via [CrossIslandSender] so a
 * moved peer is still reached. Wire by calling [init] once with the app context.
 */
object CrossIslandStore {

    data class Contact(
        val uin: Int,
        val host: String,
        val nickname: String,
        val identityKey: String,         // v=1 X25519 public, base64 (seal to them)
        val signingKey: String,          // v=1 Ed25519 public, base64
        val signalIdentityKey: String?,  // v=2 libsignal / safety-number key, base64
        val addedAt: Long,
        // §5c display, from the open card (gson leaves them null for old entries).
        val gender: String? = null,
        val statusMessage: String? = null,
        // §5e display: the peer's picture, DEPOSITED by them into OUR island
        // (§5b PUT /media/{id}) and named by the sealed `profile` envelope. Not
        // on the open card — the key is the whole access decision, so it never
        // goes anywhere unauthenticated. Fetched with host = null, like any
        // blob of ours. Gson leaves both null for rows written before §5e.
        val avatarMediaId: String? = null,
        val avatarMediaKey: String? = null,
        // Sender ts (epoch SECONDS) of the newest `profile` we applied for this
        // peer. The stale guard: an offline-queue drain can hand us an old
        // envelope after a newer one, and applying it would put the old name back.
        val profileTs: Long = 0L,
    )

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()
    private const val KEY = "contacts.v1"

    /** Removals have to be remembered, or the next vault sync fetches the row
     *  straight back off the island and deleting a cross-island contact undoes
     *  itself on the following boot. Per account, next to the rows. */
    private const val GRAVES = "graves.v1"

    /** §5e inbound bounds. A media id is a client-chosen uuid4 (with or without
     *  dashes) or an island-assigned id; it goes straight into `/media/{id}`,
     *  so the charset is restricted on the way IN. Identical to web's gate. */
    private val MEDIA_ID_RE = Regex("^[A-Za-z0-9_-]{1,64}$")
    private const val MEDIA_KEY_MAX = 128
    private const val NICKNAME_MAX = 64

    /** Active account id; null before any account is bound (all reads empty). */
    private var acct: String? = null

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) {
            prefs = ctx.applicationContext.getSharedPreferences("rcq_crossisland", Context.MODE_PRIVATE)
        }
    }

    /** Point the store at [accountId]'s slot. null = no account (empty store). */
    fun bindAccount(accountId: String?) {
        acct = accountId
    }

    private fun storageKey(accountId: String) = "$accountId.$KEY"
    private fun gravesKey(accountId: String) = "$accountId.$GRAVES"

    /** A local write the vault mirror has to publish. Set by this store,
     *  cleared by nothing: the mirror registers a listener rather than being
     *  imported here, because this file is also read by the push receiver,
     *  where there is no session and no vault. */
    @Volatile private var listener: ((removed: Pair<Int, String>?) -> Unit)? = null

    fun setListener(fn: ((removed: Pair<Int, String>?) -> Unit)?) { listener = fn }

    private fun notify(removed: Pair<Int, String>? = null) {
        runCatching { listener?.invoke(removed) }
    }

    fun tombstones(): Map<String, Long> {
        val a = acct ?: return emptyMap()
        if (!::prefs.isInitialized) return emptyMap()
        val raw = prefs.getString(gravesKey(a), "{}") ?: "{}"
        val type = object : TypeToken<MutableMap<String, Long>>() {}.type
        return runCatching { gson.fromJson<MutableMap<String, Long>>(raw, type) }.getOrNull() ?: emptyMap()
    }

    private fun bury(uin: Int, host: String) {
        val a = acct ?: return
        if (!::prefs.isInitialized) return
        val g = HashMap(tombstones())
        g[ciKey(uin, host)] = System.currentTimeMillis()
        prefs.edit().putString(gravesKey(a), gson.toJson(g)).apply()
    }

    /**
     * The vault sync writing the merged set back. Deliberately silent: it is
     * the listener's own result coming home, and announcing it would loop.
     *
     * ⚠ Takes the account explicitly and drops the write when the stores have
     * been rebound since the merge started (an account switch, or the duress
     * PIN): the rows about to land are the ones the SYNCING account's slot
     * holds, and writing them into whoever is bound now would hand one
     * account's cross-island contacts to another.
     */
    fun replaceAll(accountId: String, rows: List<Contact>, graves: Map<String, Long>) {
        if (!::prefs.isInitialized || acct != accountId) return
        val m = LinkedHashMap<String, Contact>()
        for (r in rows) m[ciKey(r.uin, r.host)] = r
        prefs.edit()
            .putString(storageKey(accountId), gson.toJson(m))
            .putString(gravesKey(accountId), gson.toJson(graves))
            .apply()
    }

    private fun ciKey(uin: Int, host: String) = "$uin@${host.lowercase()}"

    private fun loadAll(): MutableMap<String, Contact> {
        val a = acct ?: return mutableMapOf()
        val raw = prefs.getString(storageKey(a), "{}") ?: "{}"
        val type = object : TypeToken<MutableMap<String, Contact>>() {}.type
        return runCatching { gson.fromJson<MutableMap<String, Contact>>(raw, type) }.getOrNull() ?: mutableMapOf()
    }

    private fun saveAll(m: Map<String, Contact>) {
        val a = acct ?: return
        prefs.edit().putString(storageKey(a), gson.toJson(m)).apply()
    }

    fun save(c: Contact) {
        val m = loadAll(); m[ciKey(c.uin, c.host)] = c; saveAll(m)
        notify()
    }

    fun get(uin: Int, host: String): Contact? = loadAll()[ciKey(uin, host)]

    /**
     * §5e cross-island profile refresh: apply an inbound `profile` envelope.
     *
     * Updates ONLY the display fields — nickname and the picture — of an
     * EXISTING row. Deliberate properties, each one a rule the spec spells out:
     *
     *  - The pinned `identityKey` / `signingKey` / `signalIdentityKey` are
     *    NEVER touched. They are the anti-impersonation anchor, and an inbound
     *    envelope with a write path to them would BE an impersonation path.
     *  - A `profile` from someone we do not hold as an accepted cross-island
     *    contact is DROPPED (returns false): no row is created, nothing is
     *    quarantined. It is cosmetic data from a stranger.
     *  - Stale envelopes (older [ts] than the one we last applied) are ignored,
     *    so a queue drain replaying an old deposit cannot restore an old name.
     *  - The write is `commit()`, not `apply()`: the push receiver reads this
     *    snapshot off DISK with no live Session (see [getFor]), so an
     *    in-memory-only update would leave notifications on the old name.
     *
     * Returns true when something actually changed (the caller refreshes the
     * roster), false when the envelope was dropped or was a no-op.
     */
    fun applyProfile(
        uin: Int,
        host: String,
        nickname: String?,
        avatarMediaId: String?,
        avatarMediaKey: String?,
        ts: Long,
    ): Boolean {
        val a = acct ?: return false
        if (!::prefs.isInitialized) return false
        val m = loadAll()
        val k = ciKey(uin, host)
        val cur = m[k] ?: return false
        if (ts < cur.profileTs) return false
        // Half a picture is no picture: an id without its key is unopenable,
        // and a key without an id names nothing.
        //
        // The id is charset- and length-gated because it is interpolated
        // straight into `/media/{id}`: a peer-supplied `../…` must not be able
        // to steer that fetch anywhere but at a blob. Same gate web applies, so
        // the three clients accept exactly the same set of ids.
        val id = avatarMediaId?.trim()?.takeIf { MEDIA_ID_RE.matches(it) }
        val key = avatarMediaKey?.trim()?.takeIf { it.isNotEmpty() && it.length <= MEDIA_KEY_MAX }
        val both = id != null && key != null
        val next = cur.copy(
            // An empty nickname is not a rename to nothing — keep what we have.
            // Bounded like web's: a peer must not be able to write an unbounded
            // string into our roster and onto our disk.
            nickname = nickname?.trim()?.takeIf { it.isNotEmpty() }?.take(NICKNAME_MAX) ?: cur.nickname,
            avatarMediaId = if (both) id else null,
            avatarMediaKey = if (both) key else null,
            profileTs = maxOf(ts, cur.profileTs),
        )
        if (next == cur) return false
        m[k] = next
        prefs.edit().putString(storageKey(a), gson.toJson(m)).commit()
        notify()
        return true
    }

    /** Map an incoming sealed message's senderUIN back to a cross-island thread.
     *  Per-island uins can collide in theory; returns the first match. */
    fun findByUin(uin: Int): Contact? = loadAll().values.firstOrNull { it.uin == uin }

    fun list(): List<Contact> = loadAll().values.sortedByDescending { it.addedAt }

    /** Headless lookup in a SPECIFIC account's slot, bypassing [bindAccount].
     *  The push receiver runs in a process that may have no Session and no
     *  bound account, and the wake it is handling can be for a sibling local
     *  account — reading through [acct] there would silently answer "not an
     *  accepted contact" for everyone and make every cross-island wake
     *  anonymous. Opens its own prefs handle for the same reason. */
    fun getFor(ctx: Context, accountId: String, uin: Int, host: String): Contact? {
        val p = ctx.applicationContext.getSharedPreferences("rcq_crossisland", Context.MODE_PRIVATE)
        val raw = p.getString(storageKey(accountId), "{}") ?: "{}"
        val type = object : TypeToken<MutableMap<String, Contact>>() {}.type
        val all = runCatching { gson.fromJson<MutableMap<String, Contact>>(raw, type) }.getOrNull()
        return all?.get(ciKey(uin, host))
    }

    fun remove(uin: Int, host: String) {
        val m = loadAll(); m.remove(ciKey(uin, host)); saveAll(m)
        bury(uin, host)
        notify(uin to host)
    }

    /** Wipe a specific (possibly non-active) account's contacts (burn / local delete). */
    fun wipeAccount(accountId: String) {
        if (::prefs.isInitialized) {
            prefs.edit().remove(storageKey(accountId)).remove(gravesKey(accountId)).apply()
        }
    }
}
