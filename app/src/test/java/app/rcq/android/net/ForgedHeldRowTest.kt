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

    // ── requests an older build flagged ────────────────────────────────

    /** The map as an older build wrote it: a same-island request it flagged
     *  "key changed", whose rows carry no mark, and the forger's key in sk. */
    private fun legacyMap(host: String = "", keyChanged: Boolean = true): MutableMap<String, CrossIslandRequestsStore.Request> {
        val json = """{"100:7@$host":{"ownUin":100,"uin":7,"host":"$host","firstAt":1000,
            "msgs":[{"payload":"p1","preview":"impostor one","id":"a","spub":"$keyX"},
                    {"payload":"p2","preview":"impostor two","id":"b","spub":"$keyX"}],
            "contactReq":false,"keyChanged":$keyChanged,"srvAcceptTries":0,"viaKeyChanged":false,"sk":"$keyX"}}"""
        return com.google.gson.Gson().fromJson(
            json,
            object : com.google.gson.reflect.TypeToken<MutableMap<String, CrossIslandRequestsStore.Request>>() {}.type,
        )
    }

    @Test fun a_flagged_request_from_before_stays_key_changed_after_a_new_row() {
        val m = legacyMap()
        assertTrue(S.markLegacyForged(m))
        var r = m.values.single()
        assertTrue(r.msgs.all { it.forged })
        assertTrue(r.keyChanged)
        assertTrue(S.impostorOnly(r))
        // The forger's key the old build wrote into sk is gone: a block of the
        // real number takes nothing of theirs.
        assertTrue(S.keysOf(r).isEmpty())
        // Another forged row used to recompute the flag from the unmarked rows
        // as false and bring Accept and Block back.
        r = S.withHeld(r, forged("impostor three"), sk = keyX)
        assertTrue(r.keyChanged)
        assertTrue(S.impostorOnly(r))
        assertTrue(S.keysOf(r).isEmpty())
        // So did a retraction of one of them.
        r.msgs.removeAll { it.id == "a" }
        assertTrue(S.sameIslandFlag(r.msgs))
    }

    @Test fun the_real_person_writing_next_gets_accept_and_block_back() {
        val m = legacyMap()
        S.markLegacyForged(m)
        val r = S.withHeld(m.values.single(), genuine("it is me"), sk = keyS)
        assertFalse(r.keyChanged)
        assertFalse(S.impostorOnly(r))
        assertEquals("it is me", r.preview)
        assertEquals(listOf(keyS), S.keysOf(r))
    }

    @Test fun marking_is_done_once() {
        val m = legacyMap()
        assertTrue(S.markLegacyForged(m))
        val once = m.toMap()
        assertFalse(S.markLegacyForged(m))
        assertEquals(once, m)
        // Through a write and a read, as the store does it.
        val gson = com.google.gson.Gson()
        val type = object : com.google.gson.reflect.TypeToken<MutableMap<String, CrossIslandRequestsStore.Request>>() {}.type
        val back: MutableMap<String, CrossIslandRequestsStore.Request> = gson.fromJson(gson.toJson(m), type)
        assertFalse(S.markLegacyForged(back))
        assertTrue(back.values.single().msgs.all { it.forged })
    }

    @Test fun only_a_flagged_same_island_request_from_before_is_marked() {
        // Not flagged: its rows were genuine.
        assertFalse(S.markLegacyForged(legacyMap(keyChanged = false)))
        // Another island: the flag is sticky there, nothing recomputes it.
        assertFalse(S.markLegacyForged(legacyMap(host = "is2.rcq.app")))
        // Written by this build: a marked row says which ones were forged.
        val mixed = S.withHeld(S.withHeld(req(), forged("x"), sk = keyX), forged("y"), sk = keyX)
        val m = mutableMapOf("100:7@" to mixed.copy(msgs = mutableListOf(mixed.msgs[0], mixed.msgs[1].copy(forged = false))))
        assertFalse(S.markLegacyForged(m))
    }

    @Test fun a_block_never_answers_a_row_of_forged_rows_only() {
        // Even with the flag stale, the rows say it.
        val r = S.withHeld(req(), forged("impostor"), sk = keyX).copy(keyChanged = false)
        assertTrue(S.impostorOnly(r))
        assertFalse(S.impostorOnly(S.withHeld(req(), genuine("hi"), sk = keyS)))
        assertFalse(S.impostorOnly(null))
    }

    @Test fun rows_stored_before_are_genuine() {
        val legacy = com.google.gson.Gson().fromJson(
            """{"payload":"p","preview":"old","id":"a","spub":"$keyS"}""",
            CrossIslandRequestsStore.Held::class.java,
        )
        assertFalse(legacy.forged)
    }
}
