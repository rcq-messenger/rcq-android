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
        val blocked = setOf("100:7@a.example", "100:8@", "200:9@a.example", "100:junk", "100:x@b")
        val meta = mapOf("100:7@a.example" to CrossIslandRequestsStore.BlockMeta(at = 5))
        val out = s.listBlocked(blocked, meta, 100)
        assertEquals(listOf(8 to "", 7 to "a.example"), out.map { it.uin to it.host })
        assertEquals(5L, out[1].meta.at)
        // A block with no metadata reads as a plain one.
        assertEquals(CrossIslandRequestsStore.BlockMeta(), out[0].meta)
    }

    @Test fun legacy_blocks_are_stamped_and_nothing_is_lifted() {
        val blocked = setOf("100:7@a.example", "100:8@b.example", "100:9@c.example", "100:10@", "100:11@d.example")
        val meta = mutableMapOf("100:11@d.example" to CrossIslandRequestsStore.BlockMeta(at = 3))
        val contacts = setOf(7 to "a.example", 8 to "b.example", 10 to "")
        val changed = s.stampLegacyMeta(blocked, meta, 100, { u, h -> (u to h) in contacts }, bare = setOf(8))
        assertTrue(changed)
        // Added back as a contact, and nothing bare in the way: its messages
        // flowed before this build, and keep flowing.
        assertTrue(meta.getValue("100:7@a.example").passContact)
        // The bare number dropped everything from it: still blocked.
        assertFalse(meta.getValue("100:8@b.example").passContact)
        // Not a contact: blocked as before.
        assertFalse(meta.getValue("100:9@c.example").passContact)
        // Our own island's stranger: no gate there to have let them through.
        assertFalse(meta.getValue("100:10@").passContact)
        // Already stamped: untouched.
        assertEquals(CrossIslandRequestsStore.BlockMeta(at = 3), meta.getValue("100:11@d.example"))
        assertFalse(s.stampLegacyMeta(blocked, meta, 100, { _, _ -> true }, bare = emptySet()))
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
