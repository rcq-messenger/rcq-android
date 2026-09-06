package app.rcq.android.net

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Guest cards: how a stranger is allowed to write to you on a CLOSED island.
 *
 * On a closed island the key that seals an envelope to somebody is withheld
 * from strangers, so knowing a number stops being enough. A card is what a
 * resident hands out to be reachable anyway: 32 random bytes THIS DEVICE
 * generates, of which the island is told only the sha256.
 *
 * ⚠⚠ THE RAW CARD NEVER GOES TO THE ISLAND. Not at registration, not at use.
 * The island stores a digest and compares digests; the value itself travels
 * only between people: in the FRAGMENT of a shared contact link (a fragment is
 * never sent to a server) and in the clear INSIDE the first sealed envelope we
 * send somebody, which is what turns "I wrote to you first" into "you may
 * write back" with no server state and no screen. It rides the
 * `X-RCQ-Guest-Card` header and never a query string: it is a live credential
 * with no expiry, and a query string is an access log.
 *
 * Mirrors web-chat's `src/lib/guest-card.ts` field for field, including the
 * one-card-for-everybody decision: a card PER CONTACT would let a resident cut
 * off exactly one person, but it would also hand the island a stable
 * per-relationship identifier it could count and time, which is the metadata
 * this design exists to avoid. Cutting off one person is a block, client-side.
 */
object GuestCardStore {

    private const val FILE = "rcq_guest_cards"
    private const val K_MINE = "mine.v1"
    private const val K_THEIRS = "theirs.v1"
    private const val CARD_BYTES = 32
    private const val CARD_MAX = 128

    data class MyCard(val card: String, val hash: String, val label: String? = null, val createdAt: Long = 0)

    private var prefs: SharedPreferences? = null
    private val gson = Gson()
    private var acct: String? = null

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun bindAccount(accountId: String?) { acct = accountId }

    private fun key(suffix: String): String? = acct?.let { "$it.$suffix" }

    // ── minting ────────────────────────────────────────────────────────────

    fun newCard(): String {
        val b = ByteArray(CARD_BYTES)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    }

    /** sha256-hex. ⚠ Must equal the island's `models/guest_card.hash_card` and
     *  the web's `hashCard`, or a card this client registers opens nothing —
     *  and the symptom is a stranger being told "no such number", which is
     *  exactly what a working refusal looks like. */
    fun hashCard(raw: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(raw.trim().toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    // ── ours ───────────────────────────────────────────────────────────────

    private fun loadMine(): MutableList<MyCard> {
        val k = key(K_MINE) ?: return mutableListOf()
        val raw = prefs?.getString(k, "[]") ?: "[]"
        val type = object : TypeToken<MutableList<MyCard>>() {}.type
        return runCatching { gson.fromJson<MutableList<MyCard>>(raw, type) }.getOrNull() ?: mutableListOf()
    }

    private fun saveMine(list: List<MyCard>) {
        val k = key(K_MINE) ?: return
        prefs?.edit()?.putString(k, gson.toJson(list))?.apply()
    }

    fun myCards(): List<MyCard> = loadMine().sortedByDescending { it.createdAt }

    /**
     * The card to put in a link or an envelope, minting one the first time.
     * [register] is called with the digest when a new card is created, and
     * only then: it is the caller's job to talk to the island.
     */
    fun shareableCard(register: (String) -> Unit): String? {
        if (acct == null) return null
        loadMine().lastOrNull()?.let { return it.card }
        val card = newCard()
        val hash = hashCard(card)
        // ⚠ Registered BEFORE it is stored locally. A card we kept but never
        // told the island about opens nothing, and we would hand it out
        // believing it works.
        runCatching { register(hash) }.getOrElse { return null }
        saveMine(loadMine() + MyCard(card, hash, "shared", System.currentTimeMillis()))
        return card
    }

    fun forgetMine(hash: String) = saveMine(loadMine().filterNot { it.hash == hash })

    // ── theirs ─────────────────────────────────────────────────────────────

    fun handleOf(uin: Int, host: String?): String =
        if (host.isNullOrBlank()) uin.toString() else "$uin@${host.lowercase()}"

    private fun loadTheirs(): MutableMap<String, String> {
        val k = key(K_THEIRS) ?: return mutableMapOf()
        val raw = prefs?.getString(k, "{}") ?: "{}"
        val type = object : TypeToken<MutableMap<String, String>>() {}.type
        return runCatching { gson.fromJson<MutableMap<String, String>>(raw, type) }.getOrNull() ?: mutableMapOf()
    }

    private fun saveTheirs(m: Map<String, String>) {
        val k = key(K_THEIRS) ?: return
        prefs?.edit()?.putString(k, gson.toJson(m))?.apply()
    }

    /** Remember the card somebody handed us, from a link or inside an envelope. */
    fun rememberTheirCard(uin: Int, host: String?, card: String) {
        val c = card.trim()
        if (c.isEmpty() || c.length > CARD_MAX) return
        val m = loadTheirs()
        val k = handleOf(uin, host)
        if (m[k] == c) return
        m[k] = c
        saveTheirs(m)
    }

    /** The card to present when asking an island about this person, or null. */
    fun theirCard(uin: Int, host: String? = null): String? = loadTheirs()[handleOf(uin, host)]

    fun allTheirCards(): Map<String, String> = loadTheirs()

    fun replaceTheirCards(m: Map<String, String>) = saveTheirs(m)

    /** Burn: a card is a credential of the account that is going away. */
    fun wipeAccount(accountId: String) {
        prefs?.edit()?.remove("$accountId.$K_MINE")?.remove("$accountId.$K_THEIRS")?.apply()
    }
}
