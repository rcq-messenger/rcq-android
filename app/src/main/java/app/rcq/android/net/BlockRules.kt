package app.rcq.android.net

/**
 * Whether a sender is somebody this account blocked. Pure, so the rule is
 * pinned in a JVM test, and ONE rule for every place that asks: the 1:1
 * ingest, room messages and the push banner (reports 1064, 1065).
 *
 * ⚠⚠ A person is a number plus a pinned signing key, and the island is only
 * where their rows come from. Two blocks used to stand in for that, and both
 * were wrong:
 *  - the bare number in [app.rcq.android.data.LocalStores.blocked], checked
 *    before any host was looked at, so blocking N@is2 also silenced whoever
 *    holds N on our own island, in 1:1 and in rooms;
 *  - the per-address block in [CrossIslandRequestsStore], which nothing could
 *    lift and which an accepted contact walked straight past.
 * Now the bare number speaks only for OUR island, an address on another island
 * is blocked in the request store, and a key blocks its owner wherever they
 * write from. The key half is what catches the same person on another of their
 * islands, and a blocked neighbour who puts somebody else's island in
 * `from_host`: that field is outside the v=1 signature, the key is not.
 */
object BlockRules {

    /** No host, our island's name, or the front to it: one of ours. */
    fun isOwnHost(host: String?, ownHosts: Collection<String>): Boolean =
        host.isNullOrBlank() || ownHosts.any { it.equals(host, ignoreCase = true) }

    /** The host a block of this sender is filed under in
     *  [CrossIslandRequestsStore]: "" for our own island, as the same-island
     *  request rows have always been. */
    fun storeHost(host: String?, ownHosts: Collection<String>): String =
        if (isOwnHost(host, ownHosts)) "" else host!!.lowercase()

    /**
     * What one account knows about its blocks, as lookups so a caller hands in
     * its live stores and a test hands in maps.
     *
     * [bare]: the account's own-island list (bare numbers). [entry]: the block
     * filed at exactly (uin, storeHost), or null. [keys]: every blocked
     * person's signing key, canonical base64. [pinnedAt]: the signing key
     * pinned for the accepted contact at exactly (uin, host), or null.
     * [bareAnyIsland]: the account's blocks have not been sorted by island yet
     * (Session.scopeLegacyBlocks), so a bare number keeps the meaning it had
     * when it was written, the number on every island. Only a process that
     * has not run the sort (a cold push) sees this.
     */
    class View(
        val meUin: Int,
        val ownHosts: Collection<String>,
        val bare: (uin: Int) -> Boolean,
        val entry: (uin: Int, host: String) -> CrossIslandRequestsStore.BlockMeta?,
        val keys: Set<String>,
        val pinnedAt: (uin: Int, host: String) -> String?,
        val bareAnyIsland: Boolean = false,
    )

    /**
     * [senderHost] is the host the row is attributed to (after the guest
     * mailbox rule), [spub] the verified Ed25519 key it was signed with, or
     * null/empty when there is none (v=2 names no key: the ratchet, and our
     * own island, vouch for the sender).
     */
    fun blocks(v: View, senderUin: Int, senderHost: String?, spub: ByteArray?): Boolean {
        // Our own rows (carbons) are never "blocked"; a row that uses our
        // number under another key is dropped before this is asked.
        if (senderUin == v.meUin) return false
        val own = isOwnHost(senderHost, v.ownHosts)
        if ((own || v.bareAnyIsland) && v.bare(senderUin)) return true
        val host = storeHost(senderHost, v.ownHosts)
        // Our own island's strangers are filed under "", but a sibling build
        // may have named the island instead: the same people.
        val filed = if (own) (listOf("") + v.ownHosts.map { it.lowercase() }).firstNotNullOfOrNull { v.entry(senderUin, it) }
        else v.entry(senderUin, host)
        filed?.let { e ->
            // A block an older build wrote at an address the person added back
            // afterwards: it never stopped that contact's messages, and it does
            // not start now (see [CrossIslandRequestsStore.stampLegacyMeta]).
            val exempt = e.passContact && !own && spub != null &&
                CrossIslandGate.signingKeyMatches(v.pinnedAt(senderUin, host), spub)
            if (!exempt) return true
        }
        if (spub != null && spub.size == ED25519_PUB_LEN && v.keys.isNotEmpty()) {
            if (java.util.Base64.getEncoder().encodeToString(spub) in v.keys) return true
        }
        return false
    }

    /**
     * Whether a block ([on]) or an unblock another device made at [at] (epoch
     * ms, 0 from a build that sends none) may be applied over [last], the
     * latest answer here for the same person: its time, negative for an
     * unblock, null when there was none. Same rule as
     * [CrossIslandRequestsStore.applyBlock] / [CrossIslandRequestsStore.applyUnblock]:
     * the newer answer wins, and a timeless block loses to any unblock.
     */
    fun answerLands(last: Long?, on: Boolean, at: Long): Boolean {
        if (last == null) return true
        return if (on) last > 0 || at > -last else last < 0 || at > last
    }

    private const val ED25519_PUB_LEN = 32
}
