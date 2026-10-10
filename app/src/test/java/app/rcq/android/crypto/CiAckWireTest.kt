package app.rcq.android.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `ciack` time and scope as the three clients agreed on them.
 *
 * The time of the answer is `ts` (epoch ms). The web's first builds put it in
 * `at`, and older iOS sends none: read as 0, an unblock made there never
 * lifted a block here. A block "by key only" (an impostor under a contact's
 * address) is `scope: "key"` with the key in `sk`; the web's first builds
 * sent `keyOnly: true` with the key in `keys`. It must never read as a block
 * of the address.
 */
class CiAckWireTest {

    private fun parse(json: String) = Envelope.fromJsonBytes(json.toByteArray()) as Envelope.CiAck

    @Test fun the_time_is_ts_and_at_is_read_when_ts_is_missing() {
        assertEquals(10L, parse("""{"kind":"ciack","uin":7,"host":"a.example","act":"unblock","ts":10}""").ts)
        assertEquals(20L, parse("""{"kind":"ciack","uin":7,"host":"a.example","act":"unblock","at":20}""").ts)
        assertEquals(10L, parse("""{"kind":"ciack","uin":7,"host":"a.example","act":"unblock","ts":10,"at":20}""").ts)
        // Older iOS: no time at all.
        assertEquals(0L, parse("""{"kind":"ciack","uin":7,"host":"a.example","act":"unblock"}""").ts)
        assertEquals(0L, parse("""{"kind":"ciack","uin":7,"host":"a.example","act":"unblock","ts":-5,"at":"x"}""").ts)
    }

    @Test fun android_writes_ts_only() {
        val json = String(Envelope.CiAck(7, "a.example", "unblock", ts = 42L).toJsonBytes())
        assertTrue(json.contains("\"ts\":42"))
        assertFalse(json.contains("\"at\""))
        assertFalse(json.contains("\"scope\""))
        assertFalse(json.contains("\"sk\""))
    }

    @Test fun an_act_without_a_scope_names_the_address() {
        val a = parse("""{"kind":"ciack","uin":7,"host":"a.example","act":"block","keys":["k1"]}""")
        assertNull(a.scope)
        assertTrue(a.addressScoped)
        assertFalse(a.keyScoped)
        assertTrue(parse("""{"kind":"ciack","uin":7,"host":"a.example","act":"block","scope":"address"}""").addressScoped)
    }

    @Test fun a_key_block_names_the_key_only() {
        val a = parse("""{"kind":"ciack","uin":7,"host":"a.example","act":"block","scope":"key","sk":"KEY1"}""")
        assertTrue(a.keyScoped)
        assertFalse(a.addressScoped)
        assertEquals("KEY1", a.sk)
    }

    @Test fun the_webs_key_only_flag_reads_as_a_key_block() {
        val a = parse("""{"kind":"ciack","uin":7,"host":"a.example","act":"block","keyOnly":true,"keys":["IMPOSTOR"],"at":30}""")
        assertTrue(a.keyScoped)
        assertFalse(a.addressScoped)
        assertEquals("IMPOSTOR", a.sk)
        assertEquals(30L, a.ts)
        // keyOnly false is an ordinary block.
        assertTrue(parse("""{"kind":"ciack","uin":7,"host":"a.example","act":"block","keyOnly":false,"keys":["K"]}""").addressScoped)
    }

    // A scope from a newer build is neither: the act is ignored, never
    // guessed to be a block of the address.
    @Test fun an_unknown_scope_is_neither() {
        val a = parse("""{"kind":"ciack","uin":7,"host":"a.example","act":"block","scope":"room","keys":["K"]}""")
        assertFalse(a.addressScoped)
        assertFalse(a.keyScoped)
    }

    @Test fun a_key_block_round_trips() {
        val out = Envelope.fromJsonBytes(
            Envelope.CiAck(7, "a.example", "block", ts = 5L, scope = Envelope.CIACK_SCOPE_KEY, sk = "KEY1").toJsonBytes(),
        ) as Envelope.CiAck
        assertTrue(out.keyScoped)
        assertEquals("KEY1", out.sk)
        assertEquals(5L, out.ts)
    }
}
