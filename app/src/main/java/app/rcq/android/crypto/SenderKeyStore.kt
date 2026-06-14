package app.rcq.android.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persistent sender-key chain state. Outbound chains (one per group I post to)
 * + inbound chains (one per kid handed to me via SKDM). Mirrors the web
 * sender-key-store.ts and the iOS SenderKeyStore. SharedPreferences-backed
 * like [app.rcq.android.net.MultihomeStore]; chain keys are base64 on disk.
 *
 * ALL three maps (outbound, inbound, owned-kids) are scoped by [ownUin] so a
 * multi-account device never mixes a chain across accounts. This matters even
 * though kids are globally random: when two accounts on ONE device are both in
 * the same group, the SAME kid is delivered to BOTH (one broadcast + a per-member
 * SKDM each). If the inbound chain were keyed by kid alone, the active account
 * ratcheting it forward would leave the other account unable to derive its own
 * queued copies (index < chain index), and a kid the other account created would
 * be mistaken for "my own echo". Keying every map by ownUin keeps each account's
 * view of a chain independent.
 */
object SenderKeyStore {

    private const val PREFS = "rcq_sender_keys"
    private const val KEY_OUT = "out"
    private const val KEY_IN = "in"
    private const val KEY_OWNED = "owned"
    private const val OWNED_CAP = 64
    private val gson = Gson()
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Own outbound chain for a group. [ck] is the chain key AT [index]. */
    private data class OutChain(
        val kid: String,
        val epoch: Int,
        var index: Int,
        var ck: String,
        var distributed: MutableList<Int>,
    )

    /** Inbound chain handed to me via an SKDM. [ck] is the chain key AT [index]
     *  (the next index this chain can derive); [skipped] caches out-of-order
     *  message keys by index. */
    private data class InChain(
        val gid: Int,
        val senderUin: Int,
        var spub: String,
        val epoch: Int,
        var index: Int,
        var ck: String,
        var skipped: MutableMap<Int, String>,
    )

    private fun outKey(ownUin: Int, gid: Int) = "$ownUin:$gid"
    // Inbound chains + owned kids live in a per-account prefs slot so two
    // accounts on one device keep independent views of the same kid.
    private fun inKey(ownUin: Int) = "$KEY_IN.$ownUin"
    private fun ownedKey(ownUin: Int) = "$KEY_OWNED.$ownUin"

    private fun loadOut(): MutableMap<String, OutChain> = runCatching {
        val raw = prefs.getString(KEY_OUT, null) ?: return mutableMapOf()
        gson.fromJson<MutableMap<String, OutChain>>(raw, object : TypeToken<MutableMap<String, OutChain>>() {}.type) ?: mutableMapOf()
    }.getOrDefault(mutableMapOf())

    /** One-time upgrade: chains used to live in a single global slot ([legacy]).
     *  The FIRST account to read after the per-account split adopts that data,
     *  then the legacy slot is cleared so a second account doesn't inherit a
     *  stranger's chains. Single-account users (the common case) keep every
     *  chain; multi-account users self-heal the rest via SKNACK recovery. */
    private fun migrateLegacy(legacy: String, perAccount: String) {
        if (!prefs.contains(perAccount) && prefs.contains(legacy)) {
            prefs.edit().putString(perAccount, prefs.getString(legacy, null)).remove(legacy).apply()
        }
    }

    private fun loadIn(ownUin: Int): MutableMap<String, InChain> = runCatching {
        migrateLegacy(KEY_IN, inKey(ownUin))
        val raw = prefs.getString(inKey(ownUin), null) ?: return mutableMapOf()
        gson.fromJson<MutableMap<String, InChain>>(raw, object : TypeToken<MutableMap<String, InChain>>() {}.type) ?: mutableMapOf()
    }.getOrDefault(mutableMapOf())

    private fun loadOwned(ownUin: Int): MutableList<String> = runCatching {
        migrateLegacy(KEY_OWNED, ownedKey(ownUin))
        val raw = prefs.getString(ownedKey(ownUin), null) ?: return mutableListOf()
        gson.fromJson<MutableList<String>>(raw, object : TypeToken<MutableList<String>>() {}.type) ?: mutableListOf()
    }.getOrDefault(mutableListOf())

    private fun saveOut(m: Map<String, OutChain>) = prefs.edit().putString(KEY_OUT, gson.toJson(m)).apply()
    private fun saveIn(ownUin: Int, m: Map<String, InChain>) = prefs.edit().putString(inKey(ownUin), gson.toJson(m)).apply()
    private fun saveOwned(ownUin: Int, l: List<String>) = prefs.edit().putString(ownedKey(ownUin), gson.toJson(l)).apply()

    /** Result of preparing the outbound chain for one send. */
    data class OwnSendStep(
        val kid: String,
        val epoch: Int,
        val index: Int,
        val mk: ByteArray,
        /** Capable members who still need this chain's SKDM. */
        val needDistribution: List<Int>,
        /** Chain key AT [index], base64 — what a new member's SKDM carries. */
        val ckAtI: String,
    )

    /** Resolve the outbound chain for a group send. Rotates (fresh kid, e+1,
     *  i=0) when a previously-distributed member is gone (forward secrecy),
     *  then returns the message key for the current index + who needs the SKDM.
     *  Caller MUST call [advanceOwn] after the broadcast lands. */
    @Synchronized
    fun prepareOwnSend(ownUin: Int, gid: Int, capableUins: List<Int>): OwnSendStep {
        val out = loadOut()
        val k = outKey(ownUin, gid)
        val capable = capableUins.toSet()
        var c = out[k]
        val rotate = c == null || c.distributed.any { it !in capable }
        if (rotate) {
            val newKid = SenderKeys.newKid()
            c = OutChain(newKid, (c?.epoch ?: -1) + 1, 0, SenderKeys.b64(SenderKeys.randomChainKey()), mutableListOf())
            val owned = loadOwned(ownUin)
            owned.remove(newKid)
            owned.add(0, newKid)
            saveOwned(ownUin, owned.take(OWNED_CAP))
        }
        out[k] = c!!
        saveOut(out)
        val mk = SenderKeys.deriveMessageKey(b64d(c.ck))
        val need = capableUins.filter { it !in c.distributed }
        return OwnSendStep(c.kid, c.epoch, c.index, mk, need, c.ck)
    }

    @Synchronized
    fun markDistributed(ownUin: Int, gid: Int, uins: List<Int>) {
        val out = loadOut()
        val c = out[outKey(ownUin, gid)] ?: return
        val set = c.distributed.toMutableSet()
        set.addAll(uins)
        c.distributed = set.toMutableList()
        saveOut(out)
    }

    /** Ratchet the outbound chain one step after a successful send. */
    @Synchronized
    fun advanceOwn(ownUin: Int, gid: Int) {
        val out = loadOut()
        val c = out[outKey(ownUin, gid)] ?: return
        c.ck = SenderKeys.b64(SenderKeys.nextChainKey(b64d(c.ck)))
        c.index += 1
        saveOut(out)
    }

    /** Store / refresh an inbound chain from an SKDM, bound to its
     *  authenticated sender. Returns false if a known kid is claimed by a
     *  DIFFERENT sender (rejected). */
    @Synchronized
    fun acceptSkdm(ownUin: Int, kid: String, gid: Int, senderUin: Int, spub: String, epoch: Int, index: Int, ck: String): Boolean {
        val inn = loadIn(ownUin)
        val existing = inn[kid]
        if (existing != null && existing.senderUin != senderUin) return false
        if (existing != null && existing.epoch == epoch && existing.index >= index) {
            existing.spub = spub
            saveIn(ownUin, inn)
            return true
        }
        inn[kid] = InChain(gid, senderUin, spub, epoch, index, ck, mutableMapOf())
        saveIn(ownUin, inn)
        return true
    }

    data class InboundKey(val mk: ByteArray, val spub: ByteArray, val senderUin: Int)

    /** Derive the message key for (kid, epoch, index), ratcheting forward and
     *  caching skipped keys. Returns null when the kid is unknown (caller
     *  should NACK), the epoch mismatches, the index is in the past with no
     *  cached key (replay), or it's beyond MAX_SKIP. */
    @Synchronized
    fun deriveInbound(ownUin: Int, kid: String, epoch: Int, index: Int): InboundKey? {
        val inn = loadIn(ownUin)
        val c = inn[kid] ?: return null
        if (c.epoch != epoch) return null

        c.skipped.remove(index)?.let { cached ->
            saveIn(ownUin, inn)
            return InboundKey(b64d(cached), b64d(c.spub), c.senderUin)
        }
        if (index < c.index) return null
        if (index - c.index > SenderKeys.MAX_SKIP) return null

        var ck = b64d(c.ck)
        var idx = c.index
        while (idx < index) {
            c.skipped[idx] = SenderKeys.b64(SenderKeys.deriveMessageKey(ck))
            ck = SenderKeys.nextChainKey(ck)
            idx++
        }
        val mk = SenderKeys.deriveMessageKey(ck)
        c.ck = SenderKeys.b64(SenderKeys.nextChainKey(ck))
        c.index = index + 1
        saveIn(ownUin, inn)
        return InboundKey(mk, b64d(c.spub), c.senderUin)
    }

    @Synchronized
    fun knowsKid(ownUin: Int, kid: String): Boolean = loadIn(ownUin).containsKey(kid)

    /** True if THIS account on THIS device created [kid] (a gmsg under it is my
     *  own message echoed back; own multi-device sync rides carbons, drop it). */
    @Synchronized
    fun ownsKid(ownUin: Int, kid: String): Boolean = loadOwned(ownUin).contains(kid)

    /** The kid I currently own for a group (answers a NACK). */
    fun ownKidForGroup(ownUin: Int, gid: Int): String? = loadOut()[outKey(ownUin, gid)]?.kid

    data class OwnSnapshot(val kid: String, val epoch: Int, val index: Int, val ck: String)

    fun ownChainSnapshot(ownUin: Int, gid: Int): OwnSnapshot? =
        loadOut()[outKey(ownUin, gid)]?.let { OwnSnapshot(it.kid, it.epoch, it.index, it.ck) }

    /** Wipe all chains for an account (account burn / logout). */
    @Synchronized
    fun clearForAccount(ownUin: Int) {
        val out = loadOut()
        val prefix = "$ownUin:"
        val kept = out.filterKeys { !it.startsWith(prefix) }
        saveOut(kept)
        // Inbound chains + owned kids are now per-account — drop this account's.
        prefs.edit().remove(inKey(ownUin)).remove(ownedKey(ownUin)).apply()
    }

    private fun b64d(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
