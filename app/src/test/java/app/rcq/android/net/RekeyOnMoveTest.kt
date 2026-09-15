package app.rcq.android.net

import app.rcq.android.crypto.SenderKeyStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stores that file state under the account's NUMBER, re-keyed on an
 * island-proven move (#986(a)). Every case pins the same two rules: the old
 * number's entries arrive under the new one, and an entry the new number
 * already holds is never overwritten. Other numbers are never touched.
 */
class RekeyOnMoveTest {

    @Test fun backup_homes_follow_the_move() {
        val list = listOf(
            MultihomeStore.Home(100, "a.example", 1, "ja", 0L),
            MultihomeStore.Home(100, "b.example", 2, "jb", 0L, auto = true),
            MultihomeStore.Home(300, "a.example", 9, "other", 0L),
        )
        val out = MultihomeStore.rekeyHomes(list, 100, 200)
        assertEquals(listOf(200, 200, 300), out.map { it.ownUin })
        assertEquals(listOf("ja", "jb", "other"), out.map { it.jwt })
        assertEquals(true, out[1].auto)
    }

    @Test fun a_home_the_new_number_holds_wins() {
        val list = listOf(
            MultihomeStore.Home(100, "a.example", 1, "old", 0L),
            MultihomeStore.Home(200, "A.example", 1, "new", 0L),
        )
        val out = MultihomeStore.rekeyHomes(list, 100, 200)
        assertEquals(listOf("new"), out.map { it.jwt })
    }

    @Test fun requests_and_blocks_follow_the_move() {
        fun req(own: Int, uin: Int, host: String, note: String) =
            CrossIslandRequestsStore.Request(own, uin, host, 0L, mutableListOf(), note = note)
        val map = linkedMapOf(
            "100:7@b.example" to req(100, 7, "b.example", "old"),
            "100:8@b.example" to req(100, 8, "b.example", "moves"),
            "200:7@b.example" to req(200, 7, "b.example", "kept"),
            "1000:9@b.example" to req(1000, 9, "b.example", "someone else"),
        )
        val out = CrossIslandRequestsStore.rekeyRequests(map, 100, 200)
        assertEquals(setOf("200:7@b.example", "200:8@b.example", "1000:9@b.example"), out.keys)
        assertEquals("kept", out["200:7@b.example"]?.note)
        assertEquals(200, out["200:8@b.example"]?.ownUin)
        // A prefix of another number is not this number.
        assertEquals(1000, out["1000:9@b.example"]?.ownUin)

        val blocked = CrossIslandRequestsStore.rekeyBlocked(setOf("100:7@b.example", "200:7@b.example", "1000:1@c"), 100, 200)
        assertEquals(setOf("200:7@b.example", "1000:1@c"), blocked)
    }

    @Test fun inbound_chains_and_owned_kids_merge_target_first() {
        val merged = SenderKeyStore.mergeTargetWins(mapOf("k1" to "new", "k3" to "n3"), mapOf("k1" to "old", "k2" to "o2"))
        assertEquals(mapOf("k1" to "new", "k2" to "o2", "k3" to "n3"), merged)

        assertEquals(listOf("n1", "o1", "o2"), SenderKeyStore.mergeOwned(listOf("n1"), listOf("o1", "n1", "o2"), 64))
        assertEquals(listOf("n1", "o1"), SenderKeyStore.mergeOwned(listOf("n1"), listOf("o1", "o2"), 2))
    }

    @Test fun own_outbound_chains_of_the_old_number_are_dropped_not_carried() {
        val out = mapOf("100:5" to "a", "100:6" to "b", "1000:5" to "c", "200:5" to "d")
        assertEquals(mapOf("1000:5" to "c", "200:5" to "d"), SenderKeyStore.dropOwner(out, 100))
    }
}
