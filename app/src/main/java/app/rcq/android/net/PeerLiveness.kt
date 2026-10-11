package app.rcq.android.net

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Is a contact on another island still there? (report 1067) The rules, pure,
 * so they are checked on the JVM. The network half is
 * [CrossIslandSender.peerLiveness]; when it is asked and what it marks is
 * [app.rcq.android.Session.probeCrossIslandPeer].
 *
 * When somebody burns their account, their own island drops the contact rows
 * on both sides and the neighbours there lose the row on their next roster
 * read. A contact on ANOTHER island hears nothing at all: that contact lives
 * only on this device, the burned person's island does not know we hold them,
 * and no client says anything to its contacts on the way out. So the one island
 * that knows is asked, lazily: when their chat or their card is opened (at most
 * once a day per address) and when a send to them failed. Never in the
 * background: asking about every contact every day is exactly the traffic
 * sealed sender exists to avoid, while asking when a chat is opened tells their
 * island nothing a send would not.
 *
 *   1. GET /federation/keys/{uin} on their island
 *        200 under the pinned keys: alive.
 *        200 under other keys: not ours to judge here (see [Liveness]).
 *        404 "no such user": go on.
 *   2. GET /federation/uin-for-key?signing_key=<pinned> on the same island
 *        another number: moved, not marked.
 *        404 "no account for key": go on.
 *   3. GET /federation/gossip-record?sk=<pinned> on OUR island
 *        their signed record naming another home: moved, not marked.
 *        404 "no record", or a record naming only this address: gone.
 *   Anything unclear on the way, at any step: unknown, nothing changes.
 *
 * ⚠⚠ Every answer that is not the island's own words for this case is
 * UNKNOWN, and unknown changes nothing. A bare 404 means very little on the
 * open internet: a masquerade island answers a caller without its gateway key
 * with a decoy site, a parked domain answers anything with its own page, a
 * self-host from before `uin-for-key` answers the framework's "Not Found".
 * Reading any of those as "deleted" would mark every contact of such an island
 * deleted the first time their chat is opened.
 *
 * ⚠⚠ NEVER `/messages/sealed` and never `island-record`. A deposit wakes the
 * phone at the other end and answers with presence; the record endpoint is a
 * per-number lookup we have no reason to make. The probe asks only about a
 * number and a key this device already holds. Same rules as the web
 * (web-chat src/lib/peer-liveness-rules.ts).
 */
object PeerLiveness {

    /** What a probe concluded about `uin@host`.
     *
     *  A card under ANOTHER key at the address (a burned number handed to
     *  somebody else, or an island serving another card) is UNKNOWN here: this
     *  client has no "key changed" state yet, and the send path seals to the
     *  pinned key whatever the card says. What matters for the mark is that it
     *  is not "alive" and not "gone". */
    enum class Liveness {
        /** The island serves this number under the pinned keys. */
        ALIVE,
        /** No such number AND no account under the pinned key there, and the
         *  person's own signed record names no other home. */
        GONE,
        /** The pinned key lives on: under another number on that island, or
         *  on another home their signed record names. Not deleted. */
        MOVED,
        /** Anything else: refused, unreachable, not JSON, not the words. */
        UNKNOWN,
    }

    /** One answer from an island: its status, and the body when it parsed as a
     *  JSON object (null when it did not, which is what a decoy page parses
     *  as). A null [Reply] is no answer at all. */
    data class Reply(val status: Int, val body: JsonObject?)

    /** The island's own words for the answers that count, exactly as
     *  `routers/federation.py` raises them and nothing else on the way says. */
    const val NO_SUCH_USER = "no such user"
    const val NO_ACCOUNT_FOR_KEY = "no account for key"
    const val NO_RECORD = "no record"

    /** The open-chat (and open-card) probe runs at most this often per address. */
    const val OPEN_EVERY_MS = 24 * 3600_000L

    /** A failed send asks again sooner, but not on every retry press: each
     *  retry already talked to their island, and the answer does not change
     *  that fast. Counted from the last failure probe, not from the open one. */
    const val FAILURE_EVERY_MS = 5 * 60_000L

    internal enum class Step { ALIVE, KEY_CHANGED, MOVED, ABSENT, UNKNOWN }

    /** Parse a body as a JSON object, or null. */
    fun jsonBody(raw: String?): JsonObject? = runCatching {
        JsonParser.parseString(raw ?: return null).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()

    private fun detailOf(body: JsonObject?): String? =
        body?.get("detail")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun str(body: JsonObject?, key: String): String? =
        body?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    /** Two base64 keys are the same bytes, whichever alphabet and padding each
     *  is spelled in. Empty or undecodable is never the same as anything. */
    fun sameKey(a: String?, b: String?): Boolean {
        val x = decodeKey(a) ?: return false
        val y = decodeKey(b) ?: return false
        return java.security.MessageDigest.isEqual(x, y)
    }

    private fun decodeKey(s: String?): ByteArray? {
        if (s.isNullOrBlank()) return null
        val t = s.trim().replace('-', '+').replace('_', '/').trimEnd('=')
        val padded = t + "=".repeat((4 - t.length % 4) % 4)
        return runCatching { java.util.Base64.getDecoder().decode(padded) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** One spelling of an island address for comparing two of them. */
    fun sameIslandHost(a: String, b: String): Boolean {
        fun canon(h: String): String {
            var s = h.trim().lowercase()
            if (s.endsWith(":443")) s = s.dropLast(4)
            return s.trimEnd('.')
        }
        return canon(a) == canon(b)
    }

    /** Step 1, the open key card on their island. ABSENT only for the island's
     *  own "no such user": 403 `island_closed` is decided before the number is
     *  looked up and says nothing about the person. A row pinned without a
     *  signing key is anchored by its identity key alone. */
    internal fun cardStep(reply: Reply?, pinnedIk: String, pinnedSk: String): Step {
        if (reply == null) return Step.UNKNOWN
        if (reply.status == 200) {
            val ik = str(reply.body, "identity_key")?.takeIf { it.isNotEmpty() } ?: return Step.UNKNOWN
            val sk = str(reply.body, "signing_key")?.takeIf { it.isNotEmpty() } ?: return Step.UNKNOWN
            val ikOk = sameKey(ik, pinnedIk)
            val skOk = pinnedSk.isBlank() || sameKey(sk, pinnedSk)
            return if (ikOk && skOk) Step.ALIVE else Step.KEY_CHANGED
        }
        if (reply.status == 404 && detailOf(reply.body) == NO_SUCH_USER) return Step.ABSENT
        return Step.UNKNOWN
    }

    /** Step 2, the account for the pinned key on the SAME island. Another
     *  number is a person who moved to a new number there. The same number back
     *  would contradict step 1 a moment ago, so it proves nothing either way. */
    internal fun ownerStep(reply: Reply?, uin: Int): Step {
        if (reply == null) return Step.UNKNOWN
        if (reply.status == 200) {
            val n = reply.body?.get("uin")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.let { runCatching { it.asBigDecimal.intValueExact() }.getOrNull() }
            return if (n != null && n != uin) Step.MOVED else Step.UNKNOWN
        }
        if (reply.status == 404 && detailOf(reply.body) == NO_ACCOUNT_FOR_KEY) return Step.ABSENT
        return Step.UNKNOWN
    }

    /** Step 3, the person's own signed record of homes on OUR island's gossip
     *  mirror. [verifiedHomes] checks a 200 body against the PINNED signing key
     *  and returns its homes, or null when it does not verify.
     *
     *  A contact pinned on a backup copy (added that way before the add paths
     *  refused it) answers steps 1 and 2 with two clean 404s once the copy is
     *  deleted or swept, while the person lives on at their primary. Their
     *  record is what says so.
     *
     *  ⚠⚠ ABSENT only for the island's own "no record", or a verified record
     *  that names no other home. A refused, failed or unverifiable read (a
     *  429, a 5xx while the pool is stuck, a dropped connection) is unknown:
     *  read as "no record", it marks a person alive at their primary deleted. */
    internal fun recordStep(
        reply: Reply?,
        pinnedSk: String,
        host: String,
        uin: Int,
        verifiedHomes: (JsonObject) -> List<Pair<String, Int>>?,
    ): Step {
        if (reply == null || pinnedSk.isBlank()) return Step.UNKNOWN
        if (reply.status == 200) {
            val body = reply.body ?: return Step.UNKNOWN
            val homes = runCatching { verifiedHomes(body) }.getOrNull() ?: return Step.UNKNOWN
            val elsewhere = homes.any { (h, u) -> !(sameIslandHost(h, host) && u == uin) }
            return if (elsewhere) Step.MOVED else Step.ABSENT
        }
        if (reply.status == 404 && detailOf(reply.body) == NO_RECORD) return Step.ABSENT
        return Step.UNKNOWN
    }

    /** The three steps in order, each asked only when the one before it found
     *  nothing. The fetches are lambdas so the order (and that a later one is
     *  never made) is part of what the tests pin. */
    fun classify(
        host: String,
        uin: Int,
        pinnedIk: String,
        pinnedSk: String,
        card: () -> Reply?,
        owner: () -> Reply?,
        record: () -> Reply?,
        verifiedHomes: (JsonObject) -> List<Pair<String, Int>>?,
    ): Liveness {
        if (pinnedIk.isBlank() || pinnedSk.isBlank()) return Liveness.UNKNOWN
        when (cardStep(card(), pinnedIk, pinnedSk)) {
            Step.ALIVE -> return Liveness.ALIVE
            Step.ABSENT -> Unit
            else -> return Liveness.UNKNOWN
        }
        when (ownerStep(owner(), uin)) {
            Step.MOVED -> return Liveness.MOVED
            Step.ABSENT -> Unit
            else -> return Liveness.UNKNOWN
        }
        return when (recordStep(record(), pinnedSk, host, uin, verifiedHomes)) {
            Step.ABSENT -> Liveness.GONE
            Step.MOVED -> Liveness.MOVED
            else -> Liveness.UNKNOWN
        }
    }

    /** The card alone, for a send to a contact marked gone that the island
     *  took: the number is there again, and only a card under the pinned keys
     *  says it is still the person we pinned (a reissued number takes the
     *  deposit too). ALIVE or UNKNOWN, never GONE. */
    fun cardOnly(reply: Reply?, pinnedIk: String, pinnedSk: String): Liveness =
        if (pinnedIk.isNotBlank() && cardStep(reply, pinnedIk, pinnedSk) == Step.ALIVE) Liveness.ALIVE
        else Liveness.UNKNOWN

    // ── The marks ────────────────────────────────────────────────────────

    /** What this device knows about one address, per account, keyed
     *  [app.rcq.android.data.LocalStores.aliasKey]: `uin@host` for another
     *  island, never the bare number (the number is per island, and the same
     *  digits here are somebody else, #1061). A bare key is our own island,
     *  where the island itself vouches and there is no pinned key ([sk] "").
     *
     *  [sk] is the pinned signing key the record is about: a mark is ignored
     *  when the row at that address now pins another key, or was (re)added
     *  after the mark ([holds]). Identity is the number AND the key; the
     *  address is only where to deliver. */
    data class Mark(
        val sk: String,
        /** When a probe found the account gone (ms, this device's clock). */
        val gone: Long? = null,
        /** When the last open-chat / open-card probe started (ms). */
        val probedAt: Long? = null,
        /** When the last probe after a FAILED send started (ms). Its own stamp:
         *  a send that fails a minute after the chat was opened (and found
         *  them alive) is exactly when to ask again. */
        val failProbeAt: Long? = null,
    )

    /** Does [m] still speak about the contact pinned at its address now, whose
     *  signing key is [rowSk] and who was added at [rowAddedAt]? A contact
     *  removed and added again starts clean, whichever device removed it. */
    fun holds(m: Mark, rowSk: String?, rowAddedAt: Long): Boolean {
        if (!sameKey(m.sk, rowSk)) return false
        val markedAt = m.gone ?: return true
        return rowAddedAt <= markedAt
    }

    /** Is a probe due? [prevAt] is the stamp of the last one of this kind. A
     *  clock that went backwards reads as "long ago", not as "never again". */
    fun probeDue(prevAt: Long?, now: Long, everyMs: Long): Boolean {
        if (prevAt == null) return true
        val since = now - prevAt
        return since < 0 || since >= everyMs
    }

    /** Is a message deposited at [depositAtMs] (the island's stamp on its queue
     *  row) news after a mark set at [markedAt]? A copy deposited BEFORE the
     *  mark is old mail arriving late, not a sign of life. No stamp at all is a
     *  live delivery. [offsetMs] is this device's clock minus the island's, 0
     *  where it is not known. */
    fun depositAfterMark(depositAtMs: Long?, markedAt: Long, offsetMs: Long = 0L): Boolean =
        depositAtMs == null || depositAtMs + offsetMs >= markedAt

    /** The addresses of [marks] that are marked gone and still hold. [rowOf]
     *  answers the pinned (signing key, added at) for a cross-island address,
     *  or null when no contact is pinned there any more. A bare key (our own
     *  island) has no pin to hold against and holds as it is. */
    fun goneKeys(marks: Map<String, Mark>, rowOf: (String) -> Pair<String, Long>?): Set<String> {
        val out = HashSet<String>()
        for ((k, m) in marks) {
            if (m.gone == null) continue
            if ('@' !in k) { out.add(k); continue }
            val (sk, addedAt) = rowOf(k) ?: continue
            if (holds(m, sk, addedAt)) out.add(k)
        }
        return out
    }

    fun encode(marks: Map<String, Mark>): String {
        val o = JsonObject()
        for ((k, m) in marks) {
            o.add(k, JsonObject().apply {
                addProperty("sk", m.sk)
                m.gone?.let { addProperty("gone", it) }
                m.probedAt?.let { addProperty("probedAt", it) }
                m.failProbeAt?.let { addProperty("failProbeAt", it) }
            })
        }
        return o.toString()
    }

    fun decode(raw: String?): Map<String, Mark> {
        val o = jsonBody(raw) ?: return emptyMap()
        val out = LinkedHashMap<String, Mark>()
        for ((k, v) in o.entrySet()) {
            val m = v.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            fun long(f: String): Long? = m.get(f)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
            out[k] = Mark(
                sk = m.get("sk")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                gone = long("gone"),
                probedAt = long("probedAt"),
                failProbeAt = long("failProbeAt"),
            )
        }
        return out
    }
}
