package app.rcq.android.data

import app.rcq.android.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #982: a member who left the group kept their name in quotes and lost it over
 * their own bubbles. The resolution order and the last-known store behind it.
 * Host JVM (`./gradlew testSideloadDebugUnitTest`), no emulator.
 */
class MemberNameCacheTest {

    private fun msg(
        id: String,
        sender: Int?,
        at: Long,
        fromMe: Boolean = false,
        replyTo: String? = null,
        replyAuthor: String? = null,
    ) = ChatMessage(
        id = id, peerUin = 0, fromMe = fromMe, body = "", sentAt = at, groupId = 7,
        senderUin = sender, replyToId = replyTo, replyToAuthor = replyAuthor,
    )

    // ── the chain ────────────────────────────────────────────────────

    @Test
    fun firstRealAnswerWinsInOrder() {
        assertEquals("alias", resolveMemberName(5, "alias", "roster", "last", "contact", "quote"))
        assertEquals("roster", resolveMemberName(5, null, "roster", "last", "contact", "quote"))
        assertEquals("last", resolveMemberName(5, null, null, "last", "contact", "quote"))
        assertEquals("contact", resolveMemberName(5, null, null, null, "contact", "quote"))
        assertEquals("quote", resolveMemberName(5, null, null, null, null, "quote"))
        assertEquals("5", resolveMemberName(5, null, null, null, null, null))
    }

    @Test
    fun formerMemberKeepsTheLastKnownName() {
        // Left the group: no roster row, no contact, nothing but the store.
        assertEquals("Anna", resolveMemberName(1234, null, null, "Anna", null, null))
    }

    @Test
    fun blankAndPlaceholderNamesFallThrough() {
        // The roster mapper writes the bare number when the island sent no nick.
        assertEquals("Anna", resolveMemberName(1234, "  ", "1234", "Anna", null, null))
        assertEquals("1234", resolveMemberName(1234, "", "", null, " ", null))
    }

    // ── quotes as the last named source ──────────────────────────────

    @Test
    fun quoteOfTheMemberNamesThem() {
        val rows = listOf(
            msg("a", sender = 1234, at = 1),
            msg("b", sender = 99, at = 2, replyTo = "a", replyAuthor = "Anna"),
        )
        assertEquals(mapOf(1234 to "Anna"), quotedAuthorNames(rows))
    }

    @Test
    fun newestQuoteWinsAndDigitsAreIgnored() {
        val rows = listOf(
            msg("a", sender = 1234, at = 1),
            msg("c", sender = 99, at = 3, replyTo = "a", replyAuthor = "Anya"),
            msg("b", sender = 98, at = 2, replyTo = "a", replyAuthor = "Anna"),
            // Written by the bug itself: a number, never a name.
            msg("d", sender = 97, at = 4, replyTo = "a", replyAuthor = "1234"),
        )
        assertEquals(mapOf(1234 to "Anya"), quotedAuthorNames(rows))
    }

    @Test
    fun quotesOfMineOrOfUnloadedRowsNameNobody() {
        val rows = listOf(
            msg("mine", sender = 42, at = 1, fromMe = true),
            msg("b", sender = 99, at = 2, replyTo = "mine", replyAuthor = "Me"),
            msg("c", sender = 99, at = 3, replyTo = "gone", replyAuthor = "Ghost"),
        )
        assertTrue(quotedAuthorNames(rows).isEmpty())
    }

    // ── the store ────────────────────────────────────────────────────

    @Test
    fun recordWritesOnlyWhatChangedAndNeverPrunes() {
        val cache = MemberNameCache()
        cache.load("rcq.app", emptyMap())
        val writes = mutableListOf<Map<Int, String>>()
        cache.record(cache.generation(), "rcq.app", null, 7, listOf(1 to "Anna", 2 to "Bob")) { _, c -> writes += c }
        // Same roster again: nothing to write.
        cache.record(cache.generation(), "rcq.app", null, 7, listOf(1 to "Anna", 2 to "Bob")) { _, c -> writes += c }
        // Bob left, Anna renamed: only the rename is written, Bob stays known.
        cache.record(cache.generation(), "rcq.app", null, 7, listOf(1 to "Anya")) { _, c -> writes += c }
        assertEquals(listOf(mapOf(1 to "Anna", 2 to "Bob"), mapOf(1 to "Anya")), writes)
        assertEquals("Anya", cache.lookup(null, 7, 1))
        assertEquals("Bob", cache.lookup(null, 7, 2))
    }

    @Test
    fun keyedByIslandHost() {
        val cache = MemberNameCache()
        cache.load("rcq.app", emptyMap())
        cache.record(cache.generation(), "rcq.app", "is2.rcq.app", -3, listOf(1 to "Stranger")) { _, _ -> }
        cache.record(cache.generation(), "rcq.app", null, -3, listOf(1 to "Friend")) { _, _ -> }
        assertEquals("Stranger", cache.lookup("IS2.rcq.app", -3, 1))
        assertEquals("Friend", cache.lookup(null, -3, 1))
        assertEquals("Friend", cache.lookup("rcq.app", -3, 1))
        assertNull(cache.lookup("other.island", -3, 1))
    }

    @Test
    fun staleWritesAfterClearAreDropped() {
        val cache = MemberNameCache()
        cache.load("rcq.app", emptyMap())
        val before = cache.generation()
        cache.clear()
        var wrote = false
        cache.record(before, "rcq.app", null, 7, listOf(1 to "Anna")) { _, _ -> wrote = true }
        assertTrue(!wrote)
        assertNull(cache.lookup("rcq.app", 7, 1))
    }

    @Test
    fun forgetDropsOneGroupOnly() {
        val cache = MemberNameCache()
        cache.load("rcq.app", emptyMap())
        cache.record(cache.generation(), "rcq.app", null, 7, listOf(1 to "Anna")) { _, _ -> }
        cache.record(cache.generation(), "rcq.app", null, 8, listOf(1 to "Anna")) { _, _ -> }
        var forgotten: MemberNameCache.Key? = null
        cache.forget(cache.generation(), "rcq.app", null, 7) { forgotten = it }
        assertEquals(MemberNameCache.Key("rcq.app", 7), forgotten)
        assertNull(cache.lookup(null, 7, 1))
        assertEquals("Anna", cache.lookup(null, 8, 1))
    }

    @Test
    fun emptyBeforeFirstLoadFallsThrough() {
        assertNull(MemberNameCache().lookup(null, 7, 1))
    }
}
