package app.rcq.android.data

import app.rcq.android.data.ContactsVault.Blob
import app.rcq.android.data.ContactsVault.Entry
import app.rcq.android.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The mirror-phase fold, same cases as the web's cli/test/vault.mjs: the
 *  server list wins, an entry the server dropped becomes a tombstone, a
 *  re-added one loses it, an unchanged list writes nothing, old tombstones
 *  are dropped. Two clients folding the same list into the same slot must
 *  agree or they take turns rewriting it. */
class ContactsVaultFoldTest {
    private val now = 1_700_000_000_000L
    private fun c(uin: Int, blocked: Boolean = false) = Contact(uin = uin, nickname = "n$uin", identityKey = "", signingKey = null, blocked = blocked)

    @Test fun first_fold_adds_everyone() {
        val next = ContactsVault.fold(Blob(), listOf(c(1), c(2)), now)!!
        assertEquals(mapOf("1" to Entry(now, now, null, "n1"), "2" to Entry(now, now, null, "n2")), next.c)
        assertEquals(emptyMap<String, Long>(), next.g)
    }

    @Test fun same_list_writes_nothing() {
        val next = ContactsVault.fold(Blob(), listOf(c(1), c(2)), now)!!
        assertNull(ContactsVault.fold(next, listOf(c(1), c(2)), now + 5))
        assertNull(ContactsVault.fold(Blob(), emptyList(), now))
    }

    @Test fun a_changed_flag_updates_u_and_keeps_a() {
        val first = ContactsVault.fold(Blob(), listOf(c(1), c(2)), now)!!
        val next = ContactsVault.fold(first, listOf(c(1), c(2, blocked = true)), now + 10)!!
        assertEquals(Entry(now, now + 10, 1, "n2"), next.c["2"])
        assertEquals(Entry(now, now, null, "n1"), next.c["1"])
    }

    @Test fun dropped_becomes_tombstone_and_readded_loses_it() {
        val first = ContactsVault.fold(Blob(), listOf(c(1), c(2)), now)!!
        val gone = ContactsVault.fold(first, listOf(c(2)), now + 20)!!
        assertNull(gone.c["1"])
        assertEquals(now + 20, gone.g["1"])
        val back = ContactsVault.fold(gone, listOf(c(1), c(2)), now + 30)!!
        assertNull(back.g["1"])
        assertEquals(now + 30, back.c["1"]!!.a)
    }

    @Test fun old_tombstones_are_dropped() {
        val next = ContactsVault.fold(Blob(g = mapOf("9" to now)), emptyList(), now + 91L * 24 * 3600 * 1000)!!
        assertEquals(Blob(), next)
    }
}
