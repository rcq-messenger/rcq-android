package app.rcq.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The incoming block decision, [BlockRules.blocks] (reports 1064, 1065).
 *
 * A person is a number plus a pinned signing key; the island is only where
 * their rows come from. So the bare number blocks on our own island only, an
 * address on another island blocks there only, and a blocked key blocks its
 * owner whatever island they write from or claim in the unsigned `from_host`.
 */
class BlockRulesTest {

    private val me = 100
    private val own = listOf("api.rcq.app", "cdn.rcq.app")
    private fun raw(b: Int) = ByteArray(32) { b.toByte() }
    private fun b64(b: Int) = java.util.Base64.getEncoder().encodeToString(raw(b))

    private fun view(
        bare: Set<Int> = emptySet(),
        entries: Map<Pair<Int, String>, CrossIslandRequestsStore.BlockMeta> = emptyMap(),
        keys: Set<String> = emptySet(),
        pinned: Map<Pair<Int, String>, String> = emptyMap(),
        bareAnyIsland: Boolean = false,
    ) = BlockRules.View(
        meUin = me,
        ownHosts = own,
        bare = { it in bare },
        entry = { u, h -> entries[u to h] },
        keys = keys,
        pinnedAt = { u, h -> pinned[u to h] },
        bareAnyIsland = bareAnyIsland,
    )

    private val plain = CrossIslandRequestsStore.BlockMeta()

    @Test fun a_bare_number_blocks_on_our_own_island_only() {
        val v = view(bare = setOf(7))
        assertTrue(BlockRules.blocks(v, 7, null, null))
        assertTrue(BlockRules.blocks(v, 7, "api.rcq.app", raw(1)))
        assertTrue(BlockRules.blocks(v, 7, "API.rcq.app", raw(1)))
        // The front is our island by another road.
        assertTrue(BlockRules.blocks(v, 7, "cdn.rcq.app", raw(1)))
        // 1065: somebody else entirely.
        assertFalse(BlockRules.blocks(v, 7, "is2.rcq.app", raw(1)))
    }

    @Test fun before_the_sort_a_bare_number_keeps_its_old_meaning() {
        val v = view(bare = setOf(7), bareAnyIsland = true)
        assertTrue(BlockRules.blocks(v, 7, "is2.rcq.app", raw(1)))
    }

    @Test fun an_address_on_another_island_blocks_there_only() {
        val v = view(entries = mapOf((7 to "is2.rcq.app") to plain))
        assertTrue(BlockRules.blocks(v, 7, "is2.rcq.app", raw(1)))
        assertTrue(BlockRules.blocks(v, 7, "IS2.rcq.app", raw(1)))
        // The same number here, and on a third island: other people.
        assertFalse(BlockRules.blocks(v, 7, null, null))
        assertFalse(BlockRules.blocks(v, 7, "api.rcq.app", raw(1)))
        assertFalse(BlockRules.blocks(v, 7, "ams.example", raw(1)))
    }

    @Test fun our_own_islands_stranger_blocked_from_the_request_row() {
        // Written by an older build under host "", and left behind by the
        // old Unblock, which cleared only the bare number.
        val v = view(entries = mapOf((7 to "") to plain))
        assertTrue(BlockRules.blocks(v, 7, null, null))
        assertTrue(BlockRules.blocks(v, 7, "api.rcq.app", raw(1)))
        assertFalse(BlockRules.blocks(v, 7, "is2.rcq.app", raw(1)))
    }

    @Test fun a_stranger_filed_under_our_island_s_own_name_is_ours() {
        val v = view(entries = mapOf((7 to "api.rcq.app") to plain))
        assertTrue(BlockRules.blocks(v, 7, null, null))
        assertTrue(BlockRules.blocks(v, 7, "cdn.rcq.app", raw(1)))
        assertFalse(BlockRules.blocks(v, 7, "is2.rcq.app", raw(1)))
    }

    @Test fun a_blocked_key_is_the_person_wherever_they_write_from() {
        val v = view(keys = setOf(b64(5)))
        assertTrue(BlockRules.blocks(v, 7, "is3.example", raw(5)))
        assertTrue(BlockRules.blocks(v, 9, "api.rcq.app", raw(5)))
        assertFalse(BlockRules.blocks(v, 7, "is3.example", raw(6)))
    }

    @Test fun a_blocked_neighbour_cannot_slip_through_by_naming_another_island() {
        // Blocked here by number, with the key known: `from_host` is not
        // signed, the key is.
        val v = view(bare = setOf(7), keys = setOf(b64(5)))
        assertTrue(BlockRules.blocks(v, 7, "made-up.example", raw(5)))
        // The same number under another key over there is somebody else.
        assertFalse(BlockRules.blocks(v, 7, "made-up.example", raw(6)))
    }

    @Test fun a_row_with_no_key_is_judged_by_address_alone() {
        val v = view(keys = setOf(b64(5)), entries = mapOf((7 to "is2.rcq.app") to plain))
        assertFalse(BlockRules.blocks(v, 9, null, null))
        assertFalse(BlockRules.blocks(v, 9, null, ByteArray(0)))
        assertTrue(BlockRules.blocks(v, 7, "is2.rcq.app", null))
    }

    @Test fun a_legacy_block_lets_the_contact_added_back_through_and_nobody_else() {
        val legacy = CrossIslandRequestsStore.BlockMeta(passContact = true)
        val v = view(
            entries = mapOf((7 to "is2.rcq.app") to legacy),
            pinned = mapOf((7 to "is2.rcq.app") to b64(5)),
        )
        // The contact, proven by the pinned key: through, as before this build.
        assertFalse(BlockRules.blocks(v, 7, "is2.rcq.app", raw(5)))
        // Their address under another key, or with no key: blocked.
        assertTrue(BlockRules.blocks(v, 7, "is2.rcq.app", raw(6)))
        assertTrue(BlockRules.blocks(v, 7, "is2.rcq.app", null))
        // A block made now has no such exemption.
        val now = view(entries = mapOf((7 to "is2.rcq.app") to plain), pinned = mapOf((7 to "is2.rcq.app") to b64(5)))
        assertTrue(BlockRules.blocks(now, 7, "is2.rcq.app", raw(5)))
    }

    @Test fun never_ourselves() {
        val v = view(bare = setOf(me), keys = setOf(b64(5)), entries = mapOf((me to "") to plain), bareAnyIsland = true)
        assertFalse(BlockRules.blocks(v, me, null, raw(5)))
    }

    @Test fun the_store_host_of_a_sender() {
        assertEquals("", BlockRules.storeHost(null, own))
        assertEquals("", BlockRules.storeHost("", own))
        assertEquals("", BlockRules.storeHost("CDN.rcq.app", own))
        assertEquals("is2.rcq.app", BlockRules.storeHost("IS2.rcq.app", own))
    }

    @Test fun block_and_unblock_from_another_device_are_ordered() {
        // Nothing here yet: either lands.
        assertTrue(BlockRules.answerLands(null, true, 0))
        assertTrue(BlockRules.answerLands(null, false, 5))
        // Unblocked here at 20.
        assertFalse(BlockRules.answerLands(-20, true, 10))
        assertFalse(BlockRules.answerLands(-20, true, 0))
        assertTrue(BlockRules.answerLands(-20, true, 30))
        assertTrue(BlockRules.answerLands(-20, false, 10))
        // Blocked here at 20.
        assertFalse(BlockRules.answerLands(20, false, 10))
        assertTrue(BlockRules.answerLands(20, false, 30))
        assertTrue(BlockRules.answerLands(20, true, 0))
    }

    // Cross-platform rule: strictly newer wins; at the same time the state
    // here stays, so an echo of our own act at its own time changes nothing.
    @Test fun at_the_same_time_the_state_here_stays() {
        assertFalse(BlockRules.answerLands(-20, true, 20))
        assertFalse(BlockRules.answerLands(20, false, 20))
    }

    // A ciack without a time (older iOS) counts from when it reached us: the
    // deposit stamp, else now. Read as 0 it could never lift a block.
    @Test fun a_timeless_ack_takes_the_deposit_time() {
        assertEquals(30L, BlockRules.ackTime(30, 50, 99))
        assertEquals(50L, BlockRules.ackTime(0, 50, 99))
        assertEquals(99L, BlockRules.ackTime(0, null, 99))
        assertEquals(99L, BlockRules.ackTime(0, 0, 99))
        // Blocked here at 20; the web or the iPhone unblocked afterwards.
        assertTrue(BlockRules.answerLands(20, false, BlockRules.ackTime(0, 40, 99)))
        // A copy of an unblock deposited before the block here: still older.
        assertFalse(BlockRules.answerLands(20, false, BlockRules.ackTime(0, 10, 99)))
    }

    // Blocked from the request row at 1 (ciack sent), unblocked at 2 (ciack
    // sent), blocked again from the card at 3 (local). The island serves the
    // block of 1 and then the unblock of 2 again: neither may move the time
    // of the block at 3 back, or the unblock of 2 lifts it.
    @Test fun a_late_older_answer_does_not_pull_the_time_back() {
        var last: Long? = null
        fun apply(on: Boolean, at: Long, local: Boolean = false) {
            if (local || BlockRules.answerLands(last, on, at)) last = BlockRules.stampAnswer(last, on, at)
        }
        apply(true, 1, local = true)
        apply(false, 2, local = true)
        apply(true, 3, local = true)
        apply(true, 1)
        assertEquals(3L, last)
        apply(false, 2)
        assertEquals(3L, last)
        // A newer unblock still lifts it.
        apply(false, 4)
        assertEquals(-4L, last)
        // And a late older unblock does not pull the unblock time back.
        apply(false, 2)
        assertEquals(-4L, last)
        assertFalse(BlockRules.answerLands(last, true, 3))
    }

    @Test fun a_stamp_is_never_zero() {
        assertEquals(1L, BlockRules.stampAnswer(null, true, 0))
        assertEquals(-1L, BlockRules.stampAnswer(null, false, 0))
        // Changing direction takes the new time as it is.
        assertEquals(-5L, BlockRules.stampAnswer(9, false, 5))
        assertEquals(5L, BlockRules.stampAnswer(-9, true, 5))
    }
}
