package app.rcq.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blocks in [CrossIslandRequestsStore] (reports 1064, 1065), through the
 * pure halves the store's writes are made of.
 *
 * What went wrong: the store could block an address and had no way at all to
 * lift one, so a person blocked from a request row and "unblocked" in
 * Settings stayed blocked for good, invisibly. And a `ciack` the island served
 * again late could block somebody again after they were unblocked.
 */
class BlockStoreTest {

    private val s = CrossIslandRequestsStore
    private fun key(b: Int) = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { b.toByte() })

    private class State(
        val blocked: MutableSet<String> = linkedSetOf(),
        val meta: MutableMap<String, CrossIslandRequestsStore.BlockMeta> = linkedMapOf(),
        val unblocked: MutableMap<String, Long> = linkedMapOf(),
    )

    private fun State.block(k: String, at: Long, local: Boolean, keys: List<String> = emptyList()) =
        s.applyBlock(blocked, meta, unblocked, k, keys, at, local)

    private fun State.unblock(k: String, at: Long, local: Boolean) =
        s.applyUnblock(blocked, meta, unblocked, k, at, local)

    @Test fun unblock_lifts_exactly_one_address() {
        val st = State()
        st.block("100:7@a.example", 10, local = true)
        st.block("100:7@b.example", 10, local = true)
        st.block("100:7@", 10, local = true)
        assertTrue(st.unblock("100:7@a.example", 20, local = true))
        // Not the same number on another island, not our own island's.
        assertEquals(setOf("100:7@b.example", "100:7@"), st.blocked)
        assertNull(st.meta["100:7@a.example"])
        assertEquals(20L, st.unblocked["100:7@a.example"])
    }

    @Test fun unblock_of_an_address_that_is_not_blocked_lifts_nothing() {
        val st = State()
        st.block("100:7@a.example", 10, local = true)
        assertFalse(st.unblock("100:7@b.example", 20, local = true))
        assertEquals(setOf("100:7@a.example"), st.blocked)
    }

    @Test fun a_block_served_again_after_an_unblock_does_not_block_again() {
        val st = State()
        st.block("100:7@a.example", 10, local = true)
        st.unblock("100:7@a.example", 20, local = true)
        // The echo of the block at 10, drained after the unblock at 20.
        assertFalse(st.block("100:7@a.example", 10, local = false))
        // A build that sends no time: the older answer too.
        assertFalse(st.block("100:7@a.example", 0, local = false))
        assertTrue(st.blocked.isEmpty())
        // A block made on another device after the unblock is a new answer.
        assertTrue(st.block("100:7@a.example", 30, local = false))
        assertTrue("100:7@a.example" in st.blocked)
    }

    @Test fun a_stale_unblock_does_not_lift_a_newer_block() {
        val st = State()
        st.block("100:7@a.example", 30, local = true)
        assertFalse(st.unblock("100:7@a.example", 20, local = false))
        assertTrue("100:7@a.example" in st.blocked)
        assertTrue(st.unblock("100:7@a.example", 40, local = false))
        assertTrue(st.blocked.isEmpty())
    }

    @Test fun any_unblock_lifts_a_block_an_older_build_wrote() {
        // Written by 0.212: in the set, no metadata. This is everybody stuck
        // with report 1064, and their way out must not depend on a time.
        val st = State(blocked = linkedSetOf("100:7@a.example"))
        assertTrue(st.unblock("100:7@a.example", 1, local = false))
        assertTrue(st.blocked.isEmpty())
    }

    @Test fun an_unblock_that_arrives_first_still_orders_a_late_block() {
        val st = State()
        // A sibling unblocked at 20; nothing was blocked here yet.
        assertFalse(st.unblock("100:7@a.example", 20, local = false))
        assertFalse(st.block("100:7@a.example", 10, local = false))
        assertTrue(st.blocked.isEmpty())
    }

    @Test fun a_local_block_always_lands() {
        val st = State(unblocked = linkedMapOf("100:7@a.example" to 50L))
        assertTrue(st.block("100:7@a.example", 40, local = true))
        assertTrue("100:7@a.example" in st.blocked)
        assertNull(st.unblocked["100:7@a.example"])
    }

    @Test fun keys_are_canonical_and_merged() {
        val st = State()
        val k = key(1)
        st.block("100:7@a.example", 10, local = true, keys = listOf(k.trimEnd('=')))
        st.block("100:7@a.example", 5, local = true, keys = listOf(k, key(2), "not a key"))
        val m = st.meta.getValue("100:7@a.example")
        assertEquals(listOf(k, key(2)), m.keys)
        assertEquals(10L, m.at)
    }

    @Test fun a_timeless_echo_keeps_what_a_legacy_block_let_through() {
        val k = "100:7@a.example"
        val st = State(
            blocked = linkedSetOf(k),
            meta = linkedMapOf(k to CrossIslandRequestsStore.BlockMeta(passContact = true)),
        )
        st.block(k, 0, local = false)
        assertTrue(st.meta.getValue(k).passContact)
        // A block made now is a block.
        st.block(k, 10, local = true)
        assertFalse(st.meta.getValue(k).passContact)
    }

    @Test fun unblocks_are_forgotten_after_the_queue_could_still_serve_a_block() {
        val day = 24L * 3600 * 1000
        val now = 100 * day
        val m = linkedMapOf("old" to now - 46 * day, "recent" to now - 2 * day)
        s.pruneUnblocked(m, now)
        assertEquals(setOf("recent"), m.keys)
    }

    @Test fun the_list_is_one_account_and_every_island() {
        val blocked = setOf("acct-A:7@a.example", "acct-A:8@", "acct-B:9@a.example", "acct-A:junk", "acct-A:x@b")
        val meta = mapOf("acct-A:7@a.example" to CrossIslandRequestsStore.BlockMeta(at = 5))
        val out = s.listBlocked(blocked, meta, "acct-A")
        assertEquals(listOf(8 to "", 7 to "a.example"), out.map { it.uin to it.host })
        assertEquals(5L, out[1].meta.at)
        // A block with no metadata reads as a plain one.
        assertEquals(CrossIslandRequestsStore.BlockMeta(), out[0].meta)
    }

    @Test fun legacy_blocks_are_stamped_and_nothing_is_lifted() {
        val blocked = setOf("acct-A:7@a.example", "acct-A:8@b.example", "acct-A:9@c.example", "acct-A:10@", "acct-A:11@d.example")
        val meta = mutableMapOf("acct-A:11@d.example" to CrossIslandRequestsStore.BlockMeta(at = 3))
        val contacts = setOf(7 to "a.example", 8 to "b.example", 10 to "")
        val changed = s.stampLegacyMeta(blocked, meta, "acct-A", { u, h -> (u to h) in contacts }, bare = setOf(8))
        assertTrue(changed)
        // Added back as a contact, and nothing bare in the way: its messages
        // flowed before this build, and keep flowing.
        assertTrue(meta.getValue("acct-A:7@a.example").passContact)
        // The bare number dropped everything from it: still blocked.
        assertFalse(meta.getValue("acct-A:8@b.example").passContact)
        // Not a contact: blocked as before.
        assertFalse(meta.getValue("acct-A:9@c.example").passContact)
        // Our own island's stranger: no gate there to have let them through.
        assertFalse(meta.getValue("acct-A:10@").passContact)
        // Already stamped: untouched.
        assertEquals(CrossIslandRequestsStore.BlockMeta(at = 3), meta.getValue("acct-A:11@d.example"))
        assertFalse(s.stampLegacyMeta(blocked, meta, "acct-A", { _, _ -> true }, bare = emptySet()))
    }

    // Two accounts here hold 1234, on rcq.app and on is2. A's block of N@is2
    // (filed under is2, another island for A) is not B's: for B is2 is home.
    @Test fun two_accounts_with_one_number_keep_their_own_blocks() {
        val blocked = setOf("acct-A:55@is2.rcq.app")
        val meta = mapOf("acct-A:55@is2.rcq.app" to CrossIslandRequestsStore.BlockMeta(at = 5, keys = listOf(key(1))))
        assertEquals(1, s.listBlocked(blocked, meta, "acct-A").size)
        assertTrue(s.listBlocked(blocked, meta, "acct-B").isEmpty())
    }

    @Test fun at_the_same_time_the_state_here_stays() {
        val st = State()
        st.block("A:7@a.example", 10, local = true)
        // An unblock at the very time of the block: the block stays.
        assertFalse(st.unblock("A:7@a.example", 10, local = false))
        assertTrue("A:7@a.example" in st.blocked)
        st.unblock("A:7@a.example", 20, local = true)
        // A block at the very time of the unblock: stays unblocked.
        assertFalse(st.block("A:7@a.example", 20, local = false))
        assertTrue(st.blocked.isEmpty())
    }

    // A ciack with no time of its own comes in at its deposit time, and is
    // still the timeless echo of a legacy block as far as passContact goes.
    @Test fun a_block_timed_by_its_deposit_keeps_what_a_legacy_block_let_through() {
        val k = "A:7@a.example"
        val st = State(
            blocked = linkedSetOf(k),
            meta = linkedMapOf(k to CrossIslandRequestsStore.BlockMeta(passContact = true)),
        )
        assertTrue(s.applyBlock(st.blocked, st.meta, st.unblocked, k, emptyList(), 500, local = false, timeless = true))
        assertTrue(st.meta.getValue(k).passContact)
        assertEquals(500L, st.meta.getValue(k).at)
        // One that carries its own time is a block.
        st.block(k, 600, local = false)
        assertFalse(st.meta.getValue(k).passContact)
    }

    @Test fun rows_keyed_by_number_are_told_apart() {
        assertTrue(s.isLegacyBlockKey("1234:7@a.example"))
        assertTrue(s.isLegacyBlockKey("1234:7@"))
        assertFalse(s.isLegacyBlockKey("8f3c1a64-2d5b-4e07-9a18-c6b0d7e42f95:7@a.example"))
        assertFalse(s.isLegacyBlockKey("acct-A:7@a.example"))
        assertFalse(s.isLegacyBlockKey(":7@a.example"))
        assertFalse(s.isLegacyBlockKey("nothing"))
    }

    // Builds before this one keyed blocks by the number. Every account here
    // holding it gets a copy (which one made it cannot be told, and nothing
    // may be lifted by a migration); one with another number gets none.
    @Test fun blocks_keyed_by_number_go_to_every_account_holding_it() {
        val blocked = linkedSetOf("1234:55@is2.rcq.app", "1234:9@", "777:3@x.example")
        val meta = linkedMapOf(
            "1234:55@is2.rcq.app" to CrossIslandRequestsStore.BlockMeta(at = 5, keys = listOf(key(1))),
        )
        val unblocked = linkedMapOf("1234:8@is3.rcq.app" to 40L)
        val done = mutableSetOf<String>()
        val owners = mapOf("acct-A" to 1234, "acct-B" to 1234, "acct-C" to 4321, "acct-D" to null)
        assertTrue(s.migrateLegacyOwners(blocked, meta, unblocked, owners, done, complete = true))
        for (o in listOf("acct-A", "acct-B")) {
            assertTrue("$o:55@is2.rcq.app" in blocked)
            assertTrue("$o:9@" in blocked)
            assertEquals(5L, meta.getValue("$o:55@is2.rcq.app").at)
            assertEquals(listOf(key(1)), meta.getValue("$o:55@is2.rcq.app").keys)
            assertEquals(40L, unblocked["$o:8@is3.rcq.app"])
        }
        assertTrue(blocked.none { it.startsWith("acct-C:") || it.startsWith("acct-D:") })
        // Complete: nothing keyed by number is left for an account added later.
        assertTrue(blocked.none { s.isLegacyBlockKey(it) })
        assertTrue(meta.keys.none { s.isLegacyBlockKey(it) })
        assertTrue(unblocked.keys.none { s.isLegacyBlockKey(it) })
        assertEquals(setOf("acct-A", "acct-B", "acct-C", "acct-D"), done)
    }

    // An account that could not be read keeps the rows keyed by number for a
    // later try, and the accounts already done are not copied to again: a
    // block they lifted since must not come back.
    @Test fun an_unreadable_account_keeps_the_old_rows_for_a_later_try() {
        val blocked = linkedSetOf("1234:55@is2.rcq.app")
        val meta = linkedMapOf<String, CrossIslandRequestsStore.BlockMeta>()
        val unblocked = linkedMapOf<String, Long>()
        val done = mutableSetOf<String>()
        s.migrateLegacyOwners(blocked, meta, unblocked, mapOf("acct-A" to 1234), done, complete = false)
        assertTrue("1234:55@is2.rcq.app" in blocked)
        assertTrue("acct-A:55@is2.rcq.app" in blocked)
        // A lifts it, and the remembered unblock is pruned in time.
        blocked.remove("acct-A:55@is2.rcq.app")
        // The next try: B is readable now, A is done.
        s.migrateLegacyOwners(blocked, meta, unblocked, mapOf("acct-A" to 1234, "acct-B" to 1234), done, complete = true)
        assertFalse("acct-A:55@is2.rcq.app" in blocked)
        assertTrue("acct-B:55@is2.rcq.app" in blocked)
        assertFalse("1234:55@is2.rcq.app" in blocked)
    }

    // A row the account already has under its own key wins over the copy.
    @Test fun an_accounts_own_row_wins_over_the_old_one() {
        val blocked = linkedSetOf("1234:55@is2.rcq.app")
        val meta = linkedMapOf("1234:55@is2.rcq.app" to CrossIslandRequestsStore.BlockMeta(at = 5))
        val unblocked = linkedMapOf("acct-A:55@is2.rcq.app" to 50L)
        s.migrateLegacyOwners(blocked, meta, unblocked, mapOf("acct-A" to 1234), mutableSetOf(), complete = true)
        assertFalse("acct-A:55@is2.rcq.app" in blocked)
        assertEquals(50L, unblocked["acct-A:55@is2.rcq.app"])
    }

    @Test fun one_spelling_per_key() {
        val k = key(7)
        assertEquals(k, s.canonicalKey(k))
        assertEquals(k, s.canonicalKey(k.trimEnd('=')))
        assertEquals(k, s.canonicalKey(k.replace('+', '-').replace('/', '_')))
        assertEquals(k, s.canonicalKey(" ${k.take(10)}\n${k.drop(10)} "))
        assertNull(s.canonicalKey(java.util.Base64.getEncoder().encodeToString(ByteArray(31))))
        assertNull(s.canonicalKey("not base64 !"))
        assertNull(s.canonicalKey(""))
        assertNull(s.canonicalKey(null))
    }

    @Test fun a_request_row_gives_every_key_it_was_signed_with() {
        val r = CrossIslandRequestsStore.Request(
            100, 7, "a.example", 0L,
            mutableListOf(
                CrossIslandRequestsStore.Held("p1", "", spub = key(1)),
                CrossIslandRequestsStore.Held("p2", "", spub = ""),
                CrossIslandRequestsStore.Held("p3", "", spub = key(1)),
            ),
            sk = key(2),
        )
        assertEquals(listOf(key(2), key(1)), s.keysOf(r))
        assertEquals(emptyList<String>(), s.keysOf(null))
    }

    @Test fun block_metadata_follows_a_number_move() {
        val m = linkedMapOf(
            "100:7@a.example" to CrossIslandRequestsStore.BlockMeta(at = 1),
            "200:7@a.example" to CrossIslandRequestsStore.BlockMeta(at = 2),
            "100:8@" to CrossIslandRequestsStore.BlockMeta(at = 3),
            "300:9@a.example" to CrossIslandRequestsStore.BlockMeta(at = 4),
        )
        val out = s.rekeyMap(m, 100, 200)
        assertEquals(2L, out.getValue("200:7@a.example").at)
        assertEquals(3L, out.getValue("200:8@").at)
        assertEquals(4L, out.getValue("300:9@a.example").at)
        assertEquals(3, out.size)
    }
}
