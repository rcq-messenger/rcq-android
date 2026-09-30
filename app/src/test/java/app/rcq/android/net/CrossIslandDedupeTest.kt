package app.rcq.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1061: the same person under one number on two islands is one contact. */
class CrossIslandDedupeTest {

    private fun c(uin: Int, host: String, key: String, at: Long) = CrossIslandStore.Contact(
        uin = uin, host = host, nickname = "n", identityKey = "i", signingKey = key,
        signalIdentityKey = null, addedAt = at,
    )

    @Test fun a_backup_copy_with_the_same_number_and_key_goes_and_the_first_added_stays() {
        val home = c(833111503, "is2.rcq.app", "K", 100)
        val copy = c(833111503, "x.sslip.io", "K", 200)
        assertEquals(listOf(copy), CrossIslandStore.samePersonDuplicates(listOf(copy, home)))
    }

    @Test fun the_same_number_under_another_key_is_another_person_and_stays() {
        val a = c(7, "a.example", "K1", 1)
        val b = c(7, "b.example", "K2", 2)
        assertTrue(CrossIslandStore.samePersonDuplicates(listOf(a, b)).isEmpty())
    }

    @Test fun the_same_person_under_two_numbers_is_left_alone() {
        val a = c(134, "api.rcq.app", "K", 1)
        val b = c(969335757, "is2.rcq.app", "K", 2)
        assertTrue(CrossIslandStore.samePersonDuplicates(listOf(a, b)).isEmpty())
    }

    @Test fun three_rows_of_one_person_keep_only_the_oldest() {
        val rows = listOf(c(5, "c", "K", 30), c(5, "a", "K", 10), c(5, "b", "K", 20))
        assertEquals(setOf("b", "c"), CrossIslandStore.samePersonDuplicates(rows).map { it.host }.toSet())
    }
}
