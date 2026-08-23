package app.rcq.android.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sections merge, the same cases the web's `cli/test/sections.mjs` runs.
 *
 * The merge has to be COMMUTATIVE and IDEMPOTENT, and it has to be the same
 * function on all three clients: two devices that disagree about one section do
 * not merely render differently, they write the slot back and forth at each
 * other until the island 429s. So every case here asserts merge(a, b) against
 * merge(b, a) as well.
 */
class SectionsMergeTest {

    private val now = 1_756_000_000_000L

    private fun tree(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    /**
     * ⚠ The invariant is CONTENT, not bytes, and that is deliberate rather than
     * a weaker test. A record only one side carries is passed through
     * untouched, so `merge(a, b)` and `merge(b, a)` can serialise their keys in
     * different orders while saying exactly the same thing; `merge(x, x)` is
     * the normal form that flattens that out, and [Sections.sameContent] is
     * what both the read path and the write path actually compare. A client
     * that decided to write on a byte difference would get into a rewrite war
     * with whichever client serialised the blob.
     */
    private fun assertCommutative(a: JsonObject, b: JsonObject) {
        val ab = Sections.merge(a, b)
        val ba = Sections.merge(b, a)
        assertTrue("merge is not commutative", Sections.sameContent(ab, ba))
        assertTrue("merge is not idempotent", Sections.sameContent(ab, Sections.merge(ab, ab)))
        // ...and the normal form itself is stable to the byte, which is what
        // makes sameContent a decision two clients can both make.
        assertEquals(
            Sections.merge(ab, ab).toString(),
            Sections.merge(Sections.merge(ab, ab), Sections.merge(ab, ab)).toString(),
        )
    }

    @Test fun empty_tree_round_trips() {
        val t = Sections.emptyTree()
        assertEquals("""{"v":1,"s":[],"d":{},"w":0}""", t.toString())
        assertEquals(t.toString(), Sections.decode(Sections.encode(t))!!.toString())
    }

    @Test fun a_newer_format_is_unreadable_and_never_overwritten() {
        assertNull(Sections.decode("""{"v":2,"s":[]}""".toByteArray()))
        assertNull(Sections.decode("not json at all".toByteArray()))
        // An ABSENT slot is an empty tree, which is readable and writable.
        assertEquals(Sections.emptyTree().toString(), Sections.decode(null)!!.toString())
    }

    @Test fun member_keys_carry_the_host() {
        // ⚠⚠ A uin is per-island. These are two different people and this is the
        // bug that has already been paid for twice.
        assertEquals("p:1234", Sections.peerKey(1234, null))
        assertEquals("p:1234@is2.rcq.app", Sections.peerKey(1234, "IS2.RCQ.APP"))
        assertEquals("g:57", Sections.groupKey(57))
        assertEquals("g:9@is2.rcq.app", Sections.groupKey(9, "is2.rcq.app"))
        assertTrue(Sections.peerKey(1234) != Sections.peerKey(1234, "is2.rcq.app"))
    }

    @Test fun rename_here_and_reorder_there_both_survive() {
        val a = tree("""{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"Work","o":7168,"t":{"n":$now,"o":1}}],"d":{},"w":0}""")
        val b = tree("""{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"old","o":9999,"t":{"n":1,"o":$now}}],"d":{},"w":0}""")
        val m = Sections.merge(a, b)
        val rec = Sections.recordFor(m, "aaaaaaaa")!!
        assertEquals("Work", Sections.nameOf(rec))
        assertEquals(9999L, Sections.orderOf(rec))
        assertCommutative(a, b)
    }

    @Test fun equal_timestamps_break_by_the_larger_value() {
        // Exists only to keep merge commutative; never the interesting case.
        val a = tree("""{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"aaa","t":{"n":5}}],"d":{},"w":0}""")
        val b = tree("""{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"zzz","t":{"n":5}}],"d":{},"w":0}""")
        assertEquals("zzz", Sections.nameOf(Sections.recordFor(Sections.merge(a, b), "aaaaaaaa")!!))
        assertCommutative(a, b)
    }

    @Test fun a_tombstone_beats_a_record_nobody_touched_since() {
        val a = tree("""{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"Work","t":{"n":100}}],"d":{},"w":0}""")
        val b = tree("""{"v":1,"s":[],"d":{"aaaaaaaa":200},"w":0}""")
        assertNull(Sections.recordFor(Sections.merge(a, b), "aaaaaaaa"))
        assertCommutative(a, b)
    }

    @Test fun a_rename_after_the_delete_brings_the_section_back() {
        val a = tree("""{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"Work","t":{"n":300}}],"d":{},"w":0}""")
        val b = tree("""{"v":1,"s":[],"d":{"aaaaaaaa":200},"w":0}""")
        assertEquals("Work", Sections.nameOf(Sections.recordFor(Sections.merge(a, b), "aaaaaaaa")!!))
        assertCommutative(a, b)
    }

    @Test fun a_filing_after_the_delete_also_brings_it_back() {
        // `m` counts as "somebody was using this section": losing an offline
        // device's filing to a delete it had not heard about is the worse of the
        // two outcomes.
        val a = tree("""{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"Work","t":{"n":100},"m":{"p:5":300}}],"d":{},"w":0}""")
        val b = tree("""{"v":1,"s":[],"d":{"aaaaaaaa":200},"w":0}""")
        assertEquals("Work", Sections.nameOf(Sections.recordFor(Sections.merge(a, b), "aaaaaaaa")!!))
        assertCommutative(a, b)
    }

    @Test fun a_removal_after_the_delete_does_NOT_bring_it_back() {
        // ⚠ `x` deliberately does not count. Counting it meant that unticking one
        // chat in the picker on an offline device resurrected a section deleted
        // on another one, stably, carrying whatever was left in it.
        val a = tree("""{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"Work","t":{"n":100},"m":{},"x":{"p:5":300}}],"d":{},"w":0}""")
        val b = tree("""{"v":1,"s":[],"d":{"aaaaaaaa":200},"w":0}""")
        assertNull(Sections.recordFor(Sections.merge(a, b), "aaaaaaaa"))
        assertCommutative(a, b)
    }

    @Test fun a_removal_racing_an_add_leaves_the_chat_out() {
        val a = tree("""{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"W","m":{"p:5":100},"x":{}}],"d":{},"w":0}""")
        val b = tree("""{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"W","m":{},"x":{"p:5":100}}],"d":{},"w":0}""")
        // On a TIE the tombstone wins: the user can add it again, but a chat
        // resurrected into a section they emptied is not undoable by hand.
        assertEquals(emptySet<String>(), Sections.membersOf(Sections.merge(a, b), "aaaaaaaa"))
        assertCommutative(a, b)
        val later = tree("""{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"W","m":{"p:5":101},"x":{}}],"d":{},"w":0}""")
        assertEquals(setOf("p:5"), Sections.membersOf(Sections.merge(b, later), "aaaaaaaa"))
        assertCommutative(b, later)
    }

    @Test fun one_chat_lives_in_exactly_one_section() {
        val a = tree("""{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"A","m":{"p:5":100},"x":{}}],"d":{},"w":0}""")
        val b = tree("""{"v":1,"s":[{"id":"bbbbbbbb","k":"u","n":"B","m":{"p:5":200},"x":{}}],"d":{},"w":0}""")
        val m = Sections.merge(a, b)
        assertEquals(emptySet<String>(), Sections.membersOf(m, "aaaaaaaa"))
        assertEquals(setOf("p:5"), Sections.membersOf(m, "bbbbbbbb"))
        assertEquals("bbbbbbbb", Sections.memberIndex(m)["p:5"])
        assertCommutative(a, b)
    }

    @Test fun a_tie_on_the_same_chat_goes_to_the_smaller_id() {
        val a = tree("""{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"A","m":{"p:5":100},"x":{}}],"d":{},"w":0}""")
        val b = tree("""{"v":1,"s":[{"id":"bbbbbbbb","k":"u","n":"B","m":{"p:5":100},"x":{}}],"d":{},"w":0}""")
        assertEquals("aaaaaaaa", Sections.memberIndex(Sections.merge(a, b))["p:5"])
        assertCommutative(a, b)
    }

    @Test fun keys_this_build_does_not_know_survive() {
        // §2.1: patch, do not rebuild. A record written by a newer client keeps
        // whatever it carries, at the top level and inside a record.
        val a = tree("""{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"A","zz":{"future":1}}],"d":{},"w":0,"qq":7}""")
        val b = tree("""{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"A"}],"d":{},"w":0}""")
        val m = Sections.merge(a, b)
        assertEquals("""{"future":1}""", Sections.recordFor(m, "aaaaaaaa")!!.get("zz").toString())
        assertEquals(7, m.get("qq").asInt)
        assertCommutative(a, b)
    }

    @Test fun a_built_in_this_client_does_not_render_is_written_back() {
        // Saved Messages is a section on Android and a pinned row on the web.
        // Dropping the record would delete the other client's ordering.
        val a = tree("""{"v":1,"s":[{"id":"sys.saved","k":"d","o":4096,"t":{"o":$now}}],"d":{},"w":0}""")
        val m = Sections.merge(a, Sections.emptyTree())
        assertEquals(4096L, Sections.orderOf(Sections.recordFor(m, "sys.saved")!!))
        assertCommutative(a, Sections.emptyTree())
    }

    @Test fun ordered_sections_synthesise_the_missing_built_ins() {
        val ids = Sections.orderedSections(Sections.emptyTree()).map { Sections.idOf(it) }
        assertEquals(
            listOf("sys.saved", "sys.fav", "sys.ci", "sys.groups", "sys.online", "sys.offline", "sys.archive"),
            ids,
        )
    }

    @Test fun order_is_total_and_ties_break_by_id() {
        val t = tree(
            """{"v":1,"s":[{"id":"bbbbbbbb","k":"u","n":"B","o":100},{"id":"aaaaaaaa","k":"u","n":"A","o":100}],"d":{},"w":0}""",
        )
        // Built-ins are synthesised into the same list, so this asks about the
        // user's own two: equal `o`, and the id decides.
        val user = Sections.userSections(t).map { Sections.idOf(it) }
        assertEquals(listOf("aaaaaaaa", "bbbbbbbb"), user)
    }

    @Test fun names_clamp_by_scalars_not_by_utf16_units() {
        // The pinned-message 422 of 22.08: a slot measured in one unit and
        // filled in another. Each of these emoji is TWO UTF-16 units.
        val name = "👍".repeat(40)
        assertEquals(32, Sections.clampName(name).codePointCount(0, Sections.clampName(name).length))
    }

    @Test fun creating_and_deleting_leaves_a_tombstone_but_frees_the_cap() {
        var t = Sections.emptyTree()
        repeat(Sections.MAX_SECTIONS) { t = Sections.createSection(t, "s$it", now) }
        // The cap counts what EXISTS.
        var threw = false
        try {
            Sections.createSection(t, "one too many", now)
        } catch (e: Sections.SectionsException) {
            threw = true
            assertEquals("too_many_sections", e.code)
        }
        assertTrue(threw)
        // Delete one and there is room again, even though the tombstone stays:
        // counting tombstones dead-ends the feature on pure churn.
        val victim = Sections.idOf(Sections.userSections(t).first())
        t = Sections.deleteSection(t, victim, now)
        t = Sections.createSection(t, "fits now", now)
        assertEquals(Sections.MAX_SECTIONS, Sections.userSections(t).size)
    }

    @Test fun adding_a_chat_moves_it_out_of_its_old_section() {
        var t = Sections.createSection(Sections.emptyTree(), "A", now)
        val a = Sections.idOf(Sections.userSections(t).first())
        t = Sections.createSection(t, "B", now + 1)
        val b = Sections.userSections(t).map { Sections.idOf(it) }.first { it != a }
        t = Sections.addMembers(t, a, listOf("p:5"), now + 2)
        t = Sections.addMembers(t, b, listOf("p:5"), now + 3)
        assertEquals(emptySet<String>(), Sections.membersOf(t, a))
        assertEquals(setOf("p:5"), Sections.membersOf(t, b))
        assertEquals(1, Sections.totalMembers(t))
        // ...and the local outcome is the one the merge would have computed.
        assertTrue(Sections.sameContent(t, Sections.merge(t, t)))
    }

    @Test fun forget_member_only_touches_the_section_that_held_it() {
        var t = Sections.createSection(Sections.emptyTree(), "A", now)
        val a = Sections.idOf(Sections.userSections(t).first())
        t = Sections.addMembers(t, a, listOf("p:5", "p:6"), now)
        val pruned = Sections.forgetMember(t, "p:5", now + 1)!!
        assertEquals(setOf("p:6"), Sections.membersOf(pruned, a))
        // A key nobody holds is not a write at all: the caller then stamps `w`
        // instead, so the island cannot tell a filed removal from an unfiled one.
        assertNull(Sections.forgetMember(pruned, "p:999", now + 2))
    }

    @Test fun tombstones_are_bounded_by_age_and_by_count() {
        var t = Sections.createSection(Sections.emptyTree(), "A", now)
        val a = Sections.idOf(Sections.userSections(t).first())
        // One expired member tombstone plus a live one.
        t = Sections.addMembers(t, a, listOf("p:1", "p:2"), now)
        t = Sections.removeMemberFrom(t, a, "p:1", now - Sections.TOMBSTONE_TTL_MS - 1)
        t = Sections.removeMemberFrom(t, a, "p:2", now)
        val dropped = Sections.dropExpired(t, now)
        val x = Sections.plainMap(Sections.recordFor(dropped, a)!!.get("x"))
        assertEquals(setOf("p:2"), x.keys)
    }

    @Test fun same_content_ignores_key_order() {
        // A blob the web or iOS serialised with its keys in another order is not
        // a difference. A client that writes on a byte difference gets into a
        // rewrite war with the client that wrote it.
        val a = tree("""{"v":1,"s":[{"id":"aaaaaaaa","k":"u","n":"A","o":1024,"t":{"n":1,"o":1}}],"d":{},"w":5}""")
        val b = tree("""{"w":5,"d":{},"s":[{"t":{"o":1,"n":1},"o":1024,"n":"A","k":"u","id":"aaaaaaaa"}],"v":1}""")
        assertTrue(Sections.sameContent(a, b))
        assertTrue(!Sections.sameTree(a, b))
    }

    @Test fun set_order_only_stamps_what_moved() {
        var t = Sections.setOrder(Sections.emptyTree(), mapOf("sys.fav" to 2048L), now)
        val stamped = Sections.recordFor(t, "sys.fav")!!
        assertEquals(now, stamped.getAsJsonObject("t").get("o").asLong)
        // A second call with the same order is not a second stamp.
        t = Sections.setOrder(t, mapOf("sys.fav" to 2048L), now + 1000)
        assertEquals(now, Sections.recordFor(t, "sys.fav")!!.getAsJsonObject("t").get("o").asLong)
    }

    @Test fun a_pin_flag_merges_per_field() {
        val a = tree("""{"v":1,"s":[{"id":"sys.archive","k":"d","p":1,"t":{"p":$now}}],"d":{},"w":0}""")
        val b = tree("""{"v":1,"s":[{"id":"sys.archive","k":"d","o":9000,"t":{"o":$now}}],"d":{},"w":0}""")
        val m = Sections.merge(a, b)
        assertTrue(Sections.isPinned(m, "sys.archive"))
        assertEquals(9000L, Sections.orderOf(Sections.recordFor(m, "sys.archive")!!))
        assertCommutative(a, b)
    }

    @Test fun a_version_above_one_is_never_merged() {
        var threw = false
        try {
            Sections.merge(tree("""{"v":2,"s":[]}"""), Sections.emptyTree())
        } catch (e: Sections.SectionsException) {
            threw = true
            assertEquals("unreadable", e.code)
        }
        assertTrue(threw)
    }
}
