package app.rcq.android.ui

import app.rcq.android.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The pieces ChatScreen's smoothness rests on: resolvers that answer as the
 *  linear searches they replaced did, and rows handed back as the very objects
 *  the list drew last time when nothing about them changed. */
class ChatThreadStateTest {

    @Test fun the_first_row_for_a_number_wins_as_the_linear_search_did() {
        // Two people can hold one number on two islands; the first listed wins.
        val nicks = firstNickByUin(listOf(7 to "ann", 8 to "ben", 7 to "ann elsewhere"))
        assertEquals("ann", nicks[7])
        assertEquals("ben", nicks[8])
    }

    @Test fun a_member_nick_is_preferred_to_a_contact_nick() {
        val resolve = mentionNickResolver(mapOf(7 to "contact ann", 9 to "cid"), mapOf(7 to "member ann"))
        assertEquals("member ann", resolve(7))
        assertEquals("cid", resolve(9))
        assertNull(resolve(10))
        // Outside a room there are no members to ask.
        assertEquals("contact ann", mentionNickResolver(mapOf(7 to "contact ann"), null)(7))
    }

    @Test fun an_empty_nick_still_answers_and_does_not_fall_through() {
        // The old `?.nickname ?:` chain stopped at an empty string, not only at null.
        assertEquals("", mentionNickResolver(mapOf(7 to "contact"), mapOf(7 to ""))(7))
    }

    @Test fun at_mentions_take_the_longest_nick_with_a_word_boundary() {
        val match = mentionMatcher(listOf(1 to "bob", 2 to "Bob Smith", 3 to "", 4 to "al"))
        assertEquals(2 to 9, match("hi @bob smith!", 3))
        assertEquals(1 to 3, match("@BOB, hi", 0))
        // No match inside a longer word.
        assertNull(match("@bobsled", 0))
        assertNull(match("@zed", 0))
    }

    @Test fun ties_go_to_the_earlier_roster_row() {
        val match = mentionMatcher(listOf(5 to "Kim", 6 to "kim"))
        assertEquals(5 to 3, match("@kim", 0))
    }

    private data class Row(val key: String?, val payload: List<Int>)

    @Test fun an_equal_keyed_row_comes_back_as_last_times_object() {
        val prev = listOf(Row("a", listOf(1, 2)), Row(null, listOf(3)), Row("b", listOf(4)))
        val next = listOf(Row("a", listOf(1, 2)), Row(null, listOf(3)), Row("b", listOf(4, 5)))
        val out = reuseEqualRows(prev, next) { it.key }
        assertSame(prev[0], out[0])
        // Unkeyed rows are left as built, and a changed row is the new one.
        assertSame(next[1], out[1])
        assertSame(next[2], out[2])
        assertEquals(next, out)
    }

    @Test fun nothing_to_reuse_returns_the_built_list_itself() {
        val next = listOf(Row("a", listOf(1)))
        assertSame(next, reuseEqualRows(null, next) { it.key })
        assertSame(next, reuseEqualRows(listOf(Row("a", listOf(2))), next) { it.key })
        assertSame(next, reuseEqualRows(listOf(Row("x", listOf(1))), next) { it.key })
    }

    private fun msg(id: String, kind: String = "photo", mediaId: String? = "m$id") =
        ChatMessage(id = id, peerUin = 7, fromMe = false, body = "", sentAt = 1L, kind = kind, mediaId = mediaId)

    @Test fun a_covered_tile_never_shares_a_key_with_the_plain_one() {
        val m = msg("1")
        assertNotEquals(tileKey(m, 360, covered = false), tileKey(m, 360, covered = true))
        assertNotEquals(tileKey(m, 360, covered = false), tileKey(m, 720, covered = false))
        // The same picture forwarded is the same tile.
        assertEquals(tileKey(m, 360, false), tileKey(msg("2", mediaId = "m1"), 360, false))
        // A clip's poster belongs to its message, not to the clip's media id.
        assertNotEquals(tileKey(msg("3", kind = "video", mediaId = "m1"), 360, false), tileKey(m, 360, false))
    }
}
