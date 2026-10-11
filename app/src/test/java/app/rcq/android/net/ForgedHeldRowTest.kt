package app.rcq.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One forged row must not take Accept and Block away from a real person's
 * request: a forged row is marked on its own, and a same-island request reads
 * "key changed" only while it holds nothing but forged rows.
 */
class ForgedHeldRowTest {

    private val S = CrossIslandRequestsStore
    private val keyS = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    private val keyX = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 2 })

    private fun req(host: String = "") = CrossIslandRequestsStore.Request(100, 7, host, 1_000L, mutableListOf())
    private fun genuine(text: String) = CrossIslandRequestsStore.Held("p-$text", text, id = text, spub = keyS)
    private fun forged(text: String) = CrossIslandRequestsStore.Held("p-$text", text, id = text, spub = keyX, forged = true)

    @Test fun a_forged_row_next_to_a_genuine_one_keeps_accept_and_block() {
        var r = S.withHeld(req(), genuine("hi from S"), sk = keyS)
        assertFalse(r.keyChanged)
        r = S.withHeld(r, forged("impostor"), sk = keyX)
        assertFalse("a genuine row is held: Accept and Block stay", r.keyChanged)
        // The impostor's text never shows under S's number, and the block
        // takes S's key, not the forger's.
        assertEquals("hi from S", r.preview)
        assertEquals(keyS, r.sk)
        assertEquals(listOf(keyS), S.keysOf(r))
    }

    @Test fun a_same_island_request_of_forged_rows_only_reads_key_changed() {
        var r = S.withHeld(req(), forged("impostor"), sk = keyX)
        assertTrue(r.keyChanged)
        assertTrue(S.keysOf(r).isEmpty())
        // The real person writes next: the request becomes theirs again.
        r = S.withHeld(r, genuine("it is me"), sk = keyS)
        assertFalse(r.keyChanged)
        assertEquals("it is me", r.preview)
    }

    @Test fun a_forger_cannot_push_the_real_messages_out() {
        var r = S.withHeld(req(), genuine("keep me"), sk = keyS)
        repeat(50) { r = S.withHeld(r, forged("spam $it"), sk = keyX) }
        assertTrue(r.msgs.any { it.preview == "keep me" })
        assertFalse(r.keyChanged)
    }

    @Test fun another_island_keeps_the_sticky_flag() {
        var r = S.withHeld(req("is2.rcq.app"), forged("mismatch"), sk = null)
        assertTrue(r.keyChanged)
        r = S.withHeld(r, genuine("later"), sk = null)
        assertTrue(r.keyChanged)
    }

    @Test fun rows_stored_before_are_genuine() {
        val legacy = com.google.gson.Gson().fromJson(
            """{"payload":"p","preview":"old","id":"a","spub":"$keyS"}""",
            CrossIslandRequestsStore.Held::class.java,
        )
        assertFalse(legacy.forged)
    }
}
