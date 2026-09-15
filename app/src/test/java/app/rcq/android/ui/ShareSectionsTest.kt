package app.rcq.android.ui

import app.rcq.android.data.Sections
import app.rcq.android.model.Contact
import app.rcq.android.model.RcqGroup
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share picker reads like the home roster (#987): the same sections in the
 * same order, a search across all of them, and a PIN-gated section that gives
 * nothing away while locked. Host JVM, no emulator.
 */
class ShareSectionsTest {

    private fun person(uin: Int, nick: String, status: String = "online", host: String? = null, blocked: Boolean = false) =
        Contact(uin = uin, nickname = nick, identityKey = "", signingKey = null, status = status, host = host, blocked = blocked)

    private fun room(id: Int, name: String) = RcqGroup(id = id, name = name, ownerUin = 1)

    private val amy = person(10, "amy")
    private val bob = person(11, "bob", status = "offline")
    private val cat = person(12, "cat")
    private val dan = person(13, "dan", status = "offline")
    private val eve = person(14, "eve", blocked = true)
    private val ida = person(15, "ida", host = "is2.rcq.app")
    private val chess = room(1, "Chess club")
    private val books = room(2, "Book swap")

    private fun lists(
        tree: JsonObject = Sections.emptyTree(),
        query: String = "",
        aliases: Map<String, String> = emptyMap(),
        contacts: List<Contact> = listOf(amy, bob, cat, dan, eve, ida),
        groups: List<RcqGroup> = listOf(chess, books),
    ) = buildHomeLists(
        filterShareContacts(contacts, query, aliases),
        filterShareGroups(groups, query),
        unread = emptyMap(),
        favorites = setOf("peer:12", "group:2"),
        archived = setOf("peer:13"),
        sectionsTree = tree,
        sectionsOk = true,
    ) { 0L }

    private fun sections(
        tree: JsonObject = Sections.emptyTree(),
        query: String = "",
        inDecoy: Boolean = false,
        unlocked: Set<String> = emptySet(),
    ) = shareSections(tree, lists(tree, query), sectionsOk = true, inDecoy = inDecoy, unlocked = unlocked, showSaved = query.isBlank())

    private fun ShareSection.names() = groups.map { it.name } + contacts.map { it.nickname }

    @Test
    fun homeOrderWithArchiveLast() {
        val s = sections()
        assertEquals(
            listOf(Sections.SYS_SAVED, Sections.SYS_FAV, Sections.SYS_CI, Sections.SYS_GROUPS, Sections.SYS_ONLINE, Sections.SYS_OFFLINE, Sections.SYS_ARCHIVE),
            s.map { it.id },
        )
        val byId = s.associateBy { it.id }
        // A favourite lives in Favourites only, the same rule as home.
        assertEquals(listOf("Book swap", "cat"), byId.getValue(Sections.SYS_FAV).names())
        assertEquals(listOf("Chess club"), byId.getValue(Sections.SYS_GROUPS).names())
        assertEquals(listOf("amy"), byId.getValue(Sections.SYS_ONLINE).names())
        assertEquals(listOf("bob"), byId.getValue(Sections.SYS_OFFLINE).names())
        assertEquals(listOf("ida"), byId.getValue(Sections.SYS_CI).names())
        assertEquals(listOf("dan"), byId.getValue(Sections.SYS_ARCHIVE).names())
    }

    @Test
    fun blockedIsNeverATarget() {
        assertTrue(sections().none { sec -> sec.contacts.any { it.uin == eve.uin } })
        assertTrue(sections(query = "eve").none { it.size > 0 })
    }

    @Test
    fun emptySectionsAreLeftOut() {
        val tree = Sections.createSection(Sections.emptyTree(), "Work")
        val s = shareSections(tree, lists(tree, contacts = listOf(amy), groups = emptyList()), true, false, emptySet(), showSaved = false)
        assertEquals(listOf(Sections.SYS_ONLINE), s.map { it.id })
    }

    @Test
    fun searchSpansSectionsByNameAliasAndNumber() {
        // Case-insensitive, and archived chats are found too: in Archive.
        assertEquals(listOf(Sections.SYS_ARCHIVE), sections(query = "DAN").map { it.id })
        // "da" is also inside "ida", who lives on another island.
        assertEquals(listOf(Sections.SYS_CI, Sections.SYS_ARCHIVE), sections(query = "da").map { it.id })
        assertEquals(listOf(Sections.SYS_GROUPS), sections(query = "chess").map { it.id })
        val byNumber = sections(query = "11")
        assertEquals(listOf(Sections.SYS_OFFLINE), byNumber.map { it.id })
        val aliased = shareSections(
            Sections.emptyTree(),
            lists(query = "boss", aliases = mapOf("10" to "The Boss")),
            true, false, emptySet(), showSaved = false,
        )
        assertEquals(listOf("amy"), aliased.flatMap { it.names() })
    }

    @Test
    fun userSectionTakesItsMembersOutOfTheDerivedOnes() {
        var tree = Sections.createSection(Sections.emptyTree(), "Work")
        val work = Sections.idOf(Sections.userSections(tree).single())
        tree = Sections.addMembers(tree, work, listOf(Sections.peerKey(amy.uin), Sections.groupKey(chess.id)))
        val s = sections(tree)
        val mine = s.single { it.id == work }
        assertTrue(mine.user)
        assertEquals("Work", mine.name)
        assertEquals(listOf("Chess club", "amy"), mine.names())
        assertFalse(s.any { it.id == Sections.SYS_ONLINE })
        assertFalse(s.any { it.id == Sections.SYS_GROUPS })
    }

    @Test
    fun lockedSectionCarriesNothingAndStaysThroughASearch() {
        var tree = Sections.createSection(Sections.emptyTree(), "Private")
        val id = Sections.idOf(Sections.userSections(tree).single())
        tree = Sections.addMembers(tree, id, listOf(Sections.peerKey(amy.uin)))
        tree = Sections.setPinned(tree, id, true)

        val locked = sections(tree).single { it.id == id }
        assertTrue(locked.gated)
        assertTrue(locked.locked)
        assertEquals(0, locked.size)

        // Searching for the hidden member finds nothing, and the header neither
        // appears nor disappears because of it.
        val searched = sections(tree, query = "amy")
        assertEquals(listOf(id), searched.map { it.id })
        assertEquals(0, searched.single().size)
        assertEquals(listOf(id), sections(tree, query = "zzz").map { it.id })

        val open = sections(tree, unlocked = setOf(id)).single { it.id == id }
        assertFalse(open.locked)
        assertEquals(listOf("amy"), open.names())

        // A decoy session gates nothing.
        val decoy = sections(tree, inDecoy = true).single { it.id == id }
        assertFalse(decoy.gated)
        assertEquals(listOf("amy"), decoy.names())
    }

    @Test
    fun foldStateStartsFromHomeAndSearchOpensIt() {
        val s = sections().associateBy { it.id }
        val offline = s.getValue(Sections.SYS_OFFLINE)
        val archive = s.getValue(Sections.SYS_ARCHIVE)
        val flags = setOf("sec:offline")

        assertTrue(shareCollapsed(offline, "", flags, emptyMap()))
        assertFalse(shareCollapsed(s.getValue(Sections.SYS_ONLINE), "", flags, emptyMap()))
        // Archive is folded unless home says it is open.
        assertTrue(shareCollapsed(archive, "", flags, emptyMap()))
        assertFalse(shareCollapsed(archive, "", setOf("sec:archive:open"), emptyMap()))
        // A tap in the picker wins over the home flag for this visit.
        assertFalse(shareCollapsed(offline, "", flags, mapOf(Sections.SYS_OFFLINE to false)))
        // A typed search opens everything.
        assertFalse(shareCollapsed(offline, "b", flags, emptyMap()))
        assertFalse(shareCollapsed(archive, "d", flags, emptyMap()))

        var tree = Sections.createSection(Sections.emptyTree(), "Private")
        val id = Sections.idOf(Sections.userSections(tree).single())
        tree = Sections.setPinned(tree, id, true)
        val locked = sections(tree).single { it.id == id }
        assertTrue(shareCollapsed(locked, "anything", emptySet(), mapOf(id to false)))
    }

    @Test
    fun savedMatchesItsTitle() {
        assertTrue(shareQueryMatches("", "Saved messages"))
        assertTrue(shareQueryMatches(" saved ", "Saved messages"))
        assertFalse(shareQueryMatches("amy", "Saved messages", "Notes"))
    }
}
