package app.rcq.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The merge that keeps a person able to write to their contacts after a
 * reinstall.
 *
 * Cards other people gave us are the ONLY way to reach them on a closed
 * island, and losing one is invisible: a closed island answers a caller with
 * no card by saying "no such number", which is exactly what the refusal looks
 * like when it is working correctly. So this merge has one job — never to lose
 * one — and the same vectors are pinned on the web in
 * cli/test/guest-card.mjs.
 */
class GuestCardVaultMergeTest {

    @Test fun the_union_keeps_what_each_device_holds_alone() {
        assertEquals(
            mapOf("1@a" to "x", "2@b" to "y"),
            GuestCardVault.merge(mapOf("1@a" to "x"), mapOf("2@b" to "y")),
        )
    }

    @Test fun a_card_this_device_just_received_wins() {
        // Somebody revoked and re-shared; the device holding the new one is right.
        assertEquals(
            mapOf("1@a" to "new"),
            GuestCardVault.merge(mapOf("1@a" to "new"), mapOf("1@a" to "old")),
        )
    }

    @Test fun an_empty_slot_never_erases_the_device() {
        assertEquals(mapOf("1@a" to "x"), GuestCardVault.merge(mapOf("1@a" to "x"), emptyMap()))
    }

    @Test fun an_empty_device_is_filled_from_the_slot() {
        // This one IS the reinstall.
        assertEquals(
            mapOf("1@a" to "x", "2@b" to "y"),
            GuestCardVault.merge(emptyMap(), mapOf("1@a" to "x", "2@b" to "y")),
        )
    }

    @Test fun the_byte_order_is_stable_so_two_devices_do_not_rewrite_at_each_other() {
        val a = GuestCardVault.merge(mapOf("2@b" to "y"), mapOf("1@a" to "x"))
        val b = GuestCardVault.merge(mapOf("1@a" to "x"), mapOf("2@b" to "y"))
        assertEquals(a.keys.toList(), b.keys.toList())
        assertEquals(listOf("1@a", "2@b"), a.keys.toList())
    }
}
