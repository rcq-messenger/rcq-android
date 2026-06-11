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
    )

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()
    private const val KEY = "contacts.v1"

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
    }

    fun get(uin: Int, host: String): Contact? = loadAll()[ciKey(uin, host)]

    /** Map an incoming sealed message's senderUIN back to a cross-island thread.
     *  Per-island uins can collide in theory; returns the first match. */
    fun findByUin(uin: Int): Contact? = loadAll().values.firstOrNull { it.uin == uin }

    fun list(): List<Contact> = loadAll().values.sortedByDescending { it.addedAt }

    fun remove(uin: Int, host: String) {
        val m = loadAll(); m.remove(ciKey(uin, host)); saveAll(m)
    }

    /** Wipe a specific (possibly non-active) account's contacts (burn / local delete). */
    fun wipeAccount(accountId: String) {
        if (::prefs.isInitialized) prefs.edit().remove(storageKey(accountId)).apply()
    }
}
