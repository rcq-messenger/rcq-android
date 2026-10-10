package app.rcq.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Keys learned off incoming rows for a number blocked on our island. A v=1
 * row's key is whatever its sender put in it, so a stream of rows under fresh
 * keys grew the set (and the prefs file rewritten on each add) without bound.
 * Past the cap new row keys are refused, and nothing is evicted: a forger
 * flooding keys must not push the person's real key out.
 */
class BlockedKeyCapTest {

    private fun k(i: Int) = "K$i"

    @Test fun row_keys_stop_at_the_cap_and_nothing_is_evicted() {
        var set: Set<String> = setOf("7:REAL")
        for (i in 1..20) set = LocalStores.withBlockedKey(set, 7, k(i), fromRow = true) ?: set
        assertEquals(LocalStores.MAX_ROW_KEYS_PER_BLOCK, set.count { it.startsWith("7:") })
        assertEquals(true, "7:REAL" in set)
    }

    @Test fun a_trusted_key_is_not_limited() {
        var set: Set<String> = (1..LocalStores.MAX_ROW_KEYS_PER_BLOCK).map { "7:${k(it)}" }.toSet()
        set = LocalStores.withBlockedKey(set, 7, "CONTACT", fromRow = false) ?: set
        assertEquals(true, "7:CONTACT" in set)
    }

    @Test fun the_cap_is_per_number() {
        val full = (1..LocalStores.MAX_ROW_KEYS_PER_BLOCK).map { "7:${k(it)}" }.toSet()
        val out = LocalStores.withBlockedKey(full, 8, "OTHER", fromRow = true)
        assertEquals(true, out != null && "8:OTHER" in out)
    }

    @Test fun a_key_already_held_writes_nothing() {
        assertNull(LocalStores.withBlockedKey(setOf("7:K1"), 7, "K1", fromRow = true))
        assertNull(LocalStores.withBlockedKey(setOf("7:K1"), 7, "K1", fromRow = false))
    }
}
