package app.rcq.android.net

import app.rcq.android.model.GroupMember

/**
 * Who left a room, and when this device saw them gone.
 *
 * A room log is drained late as a rule (a phone that was off for a day), and
 * by then a member who posted and then left is no longer in the roster. Their
 * rows used to be decided against that roster, so everything they said while
 * they WERE a member was dropped and acked for whoever drained after they
 * left. A row the island stamped before the moment we saw them go is let
 * through, under the key they held as a member; anything deposited after it,
 * a row with no island stamp (a live frame), and a row under another key are
 * not. Pure, so the rule is checked on the JVM; LocalStores keeps the records.
 */
object RoomDepartures {

    /** [key]: the signing key they held as a member (base64), null when the
     *  roster had none. [atMs]: when this device first saw them gone. */
    data class Left(val key: String?, val atMs: Long)

    /** A record outlives anything the island can still serve: it keeps a
     *  queued row for 30 days. */
    const val MEMORY_MS = 35L * 24 * 3600 * 1000

    fun recordKey(room: Int, uin: Int) = "$room:$uin"

    /**
     * The records after the roster of [room] went from [old] to [new]. Only
     * when both are known (a cold roster, or a frame that came without one,
     * says nothing about who left). Somebody missing from [new] is recorded
     * once, the FIRST time they are seen gone, under [keyOf] their row in
     * [old]; somebody in [new] is cleared, so a rejoin starts over. Records
     * older than [MEMORY_MS] are pruned.
     */
    fun afterRosterChange(
        records: Map<String, Left>,
        room: Int,
        old: List<GroupMember>?,
        new: List<GroupMember>?,
        now: Long,
        keyOf: (GroupMember) -> String? = { it.signingKey },
    ): Map<String, Left> {
        val pruned = records.filterValues { now - it.atMs < MEMORY_MS }
        if (old.isNullOrEmpty() || new.isNullOrEmpty()) return pruned
        val next = pruned.toMutableMap()
        val present = new.mapTo(HashSet()) { it.uin }
        for (m in old) {
            if (m.uin in present) continue
            next.putIfAbsent(recordKey(room, m.uin), Left(keyOf(m)?.takeIf { it.isNotBlank() }, now))
        }
        for (uin in present) next.remove(recordKey(room, uin))
        return next
    }

    /**
     * Whether a row from somebody no longer in the roster may still be read:
     * the island stamped it ([depositAtMs]) before we saw them go, and it is
     * under the key they held as a member. [v2]: a v=2 row, whose identity
     * our island already vouched for, which counts only in a room of our own
     * island ([ownRoom]).
     */
    fun leftAfterDeposit(rec: Left?, depositAtMs: Long?, spub: ByteArray, v2: Boolean, ownRoom: Boolean): Boolean {
        val at = depositAtMs ?: return false
        val r = rec ?: return false
        if (at >= r.atMs) return false
        return if (v2) ownRoom else spub.size == 32 && CrossIslandGate.signingKeyMatches(r.key, spub)
    }

    /** One record as LocalStores writes it: "room:uin=atMs:key". */
    fun encode(k: String, v: Left): String = "$k=${v.atMs}:${v.key.orEmpty()}"

    fun decode(rows: Collection<String>?): Map<String, Left> = (rows ?: emptyList()).mapNotNull { row ->
        val eq = row.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val colon = row.indexOf(':', eq + 1)
        if (colon <= eq) return@mapNotNull null
        val at = row.substring(eq + 1, colon).toLongOrNull() ?: return@mapNotNull null
        row.substring(0, eq) to Left(row.substring(colon + 1).ifBlank { null }, at)
    }.toMap()
}
