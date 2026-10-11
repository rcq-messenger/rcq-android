package app.rcq.android.ui

import app.rcq.android.model.ChatMessage
import app.rcq.android.model.DeliveryState
import app.rcq.android.model.GroupMember
import app.rcq.android.model.RcqGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Report 1063: what multi-select may tick, what may be forwarded, and the
 *  name a forward carries. */
class ForwardRulesTest {

    private fun msg(
        kind: String = "text",
        body: String = "hello",
        fromMe: Boolean = false,
        state: DeliveryState = DeliveryState.DELIVERED,
        mediaId: String? = null,
        expiresAt: Long? = null,
        fwdName: String? = null,
    ) = ChatMessage(
        id = "M", peerUin = 7, fromMe = fromMe, body = body, sentAt = 1L, state = state, kind = kind,
        mediaId = mediaId, mediaKey = mediaId?.let { "key" }, lat = 1.0, lng = 2.0, expiresAt = expiresAt, fwdName = fwdName,
    )

    private val here: (String?) -> Boolean = { it == "onDisk" }
    private val noInvite: (String) -> Boolean = { false }

    private fun fwd(m: ChatMessage, invite: (String) -> Boolean = noInvite) = ForwardRules.forwardable(m, here, invite)

    @Test fun the_six_content_kinds_forward() {
        assertTrue(fwd(msg()))
        assertTrue(fwd(msg(kind = "location", body = "")))
        listOf("photo", "video", "voice", "file").forEach { k ->
            assertTrue(k, fwd(msg(kind = k, body = "", mediaId = "onDisk")))
        }
        listOf("poll", "relay", "call", "system").forEach { k -> assertFalse(k, fwd(msg(kind = k))) }
    }

    @Test fun never_a_disappearing_message() {
        assertFalse(fwd(msg(expiresAt = 99L)))
        assertFalse(fwd(msg(kind = "photo", mediaId = "onDisk", expiresAt = 99L)))
    }

    @Test fun never_media_that_is_not_here_in_full() {
        assertFalse(fwd(msg(kind = "video", mediaId = "elsewhere")))
        assertFalse(fwd(msg(kind = "file", mediaId = null)))
    }

    @Test fun never_my_own_row_that_has_not_left() {
        assertFalse(fwd(msg(fromMe = true, state = DeliveryState.SENDING)))
        assertFalse(fwd(msg(fromMe = true, state = DeliveryState.FAILED)))
        assertTrue(fwd(msg(fromMe = true, state = DeliveryState.SENT)))
    }

    @Test fun never_a_room_invite_or_an_empty_text() {
        assertFalse(fwd(msg(body = "https://rcq.app/g/42")) { true })
        assertFalse(fwd(msg(body = "   ")))
    }

    @Test fun calls_and_notices_are_not_selectable() {
        assertFalse(ForwardRules.selectable(msg(kind = "call")))
        assertFalse(ForwardRules.selectable(msg(kind = "system")))
        assertTrue(ForwardRules.selectable(msg(kind = "poll")))
    }

    @Test fun retract_for_everyone_follows_the_single_menu() {
        assertTrue(ForwardRules.canRetract(msg(fromMe = true), isSelf = false, moderator = false))
        assertFalse(ForwardRules.canRetract(msg(fromMe = false), isSelf = false, moderator = false))
        assertTrue(ForwardRules.canRetract(msg(fromMe = false), isSelf = false, moderator = true))
        assertFalse(ForwardRules.canRetract(msg(fromMe = true), isSelf = true, moderator = true))
    }

    @Test fun the_credit_is_the_chosen_name_never_a_number() {
        assertEquals("Alice", ForwardRules.credit(msg(), "Alice", emptyList()))
        assertNull(ForwardRules.credit(msg(), "123456", emptyList()))
        assertNull(ForwardRules.credit(msg(), "#123456", emptyList()))
        assertNull(ForwardRules.credit(msg(), "  ", emptyList()))
        assertNull(ForwardRules.credit(msg(), null, emptyList()))
        // A person on another island with no name is stored as number@island:
        // that is their address, not a name.
        assertNull(ForwardRules.credit(msg(), "1234@is2.example", emptyList()))
        assertNull(ForwardRules.credit(msg(), "#1234@is2.example", emptyList()))
        assertEquals("Alice@work", ForwardRules.credit(msg(), "Alice@work", emptyList()))
        assertEquals("@alice", ForwardRules.credit(msg(), "@alice", emptyList()))
        // One line, whatever the name holds.
        assertEquals("Al ice", ForwardRules.credit(msg(), "Al\nice", emptyList()))
    }

    @Test fun a_forward_of_a_forward_keeps_the_original_author() {
        assertEquals("Alice", ForwardRules.credit(msg(fwdName = "Alice"), "Bob", emptyList()))
        // An old forward of mine that carried my alias for somebody is not
        // passed on.
        assertNull(ForwardRules.credit(msg(fromMe = true, fwdName = "my pet name"), "Me", listOf("my pet name")))
        // Somebody else's label that happens to match an alias of mine is
        // theirs to have chosen.
        assertEquals("my pet name", ForwardRules.credit(msg(fromMe = false, fwdName = "my pet name"), "Bob", listOf("my pet name")))
        // A numeric label is no better the second time round.
        assertEquals("Bob", ForwardRules.credit(msg(fwdName = "555"), "Bob", emptyList()))
        assertEquals("Bob", ForwardRules.credit(msg(fwdName = "1234@is2.example"), "Bob", emptyList()))
    }

    private fun room(files: Boolean = true, links: Boolean = true, slow: Int = 0, members: List<GroupMember> = emptyList()) =
        RcqGroup(id = 1, name = "R", ownerUin = 100, filesAllowed = files, linksAllowed = links, slowmodeSec = slow, members = members)

    @Test fun room_rules_turn_the_whole_pick_away() {
        val pic = msg(kind = "photo", body = "", mediaId = "onDisk")
        val link = msg(body = "see https://example.org")
        val voice = msg(kind = "voice", body = "", mediaId = "onDisk")
        assertEquals(ForwardRules.Refusal.FILES, ForwardRules.refusal(room(files = false), 5, listOf(msg(), pic)))
        assertEquals(ForwardRules.Refusal.LINKS, ForwardRules.refusal(room(links = false), 5, listOf(link)))
        // A caption is text too.
        assertEquals(ForwardRules.Refusal.LINKS, ForwardRules.refusal(room(links = false), 5, listOf(pic.copy(body = "http://x"))))
        // A voice note is not a file there, as at the composer.
        assertNull(ForwardRules.refusal(room(files = false), 5, listOf(voice)))
        // The owner is exempt; a 1:1 has no rules.
        assertNull(ForwardRules.refusal(room(files = false, links = false), 100, listOf(pic, link)))
        assertNull(ForwardRules.refusal(null, 5, listOf(pic, link)))
    }

    @Test fun an_album_is_one_post_and_slowmode_takes_one() {
        fun tile(id: String, album: String?) = msg(kind = "photo", body = "", mediaId = "onDisk").copy(id = id, albumId = album)
        val album = listOf(tile("a", "AL"), tile("b", "AL"), tile("c", "AL"))
        assertEquals(1, ForwardRules.posts(album))
        assertEquals(2, ForwardRules.posts(album + msg()))
        // One tile of an album travels as a lone picture.
        assertEquals(2, ForwardRules.posts(listOf(tile("a", "AL"), tile("x", "OTHER"))))
        // A room with slowmode takes one post per window: a whole album goes,
        // two posts are turned away before anything is sent.
        assertNull(ForwardRules.refusal(room(slow = 30), 5, album))
        assertNull(ForwardRules.refusal(room(slow = 30), 5, listOf(msg())))
        assertEquals(ForwardRules.Refusal.SLOWMODE, ForwardRules.refusal(room(slow = 30), 5, album + msg()))
        // The owner and anyone holding a cap are exempt, as on the island.
        assertNull(ForwardRules.refusal(room(slow = 30), 100, album + msg()))
        val mod = GroupMember(uin = 5, nickname = "m", identityKey = "", signingKey = null, role = "member", permissions = listOf("delete"))
        assertNull(ForwardRules.refusal(room(slow = 30, members = listOf(mod)), 5, album + msg()))
    }

    @Test fun ticking_flips_a_whole_album_and_respects_the_cap() {
        val one = setOf("a")
        assertEquals(setOf("a", "b", "c"), SelectionMath.toggle(one, listOf("b", "c"), 50))
        // Half an album ticked: the tap ticks the rest, it does not untick.
        assertEquals(setOf("a", "b", "c"), SelectionMath.toggle(setOf("a", "b"), listOf("b", "c"), 50))
        assertEquals(setOf("a"), SelectionMath.toggle(setOf("a", "b", "c"), listOf("b", "c"), 50))
        assertEquals(emptySet<String>(), SelectionMath.toggle(one, listOf("a"), 50))
        // Past the cap nothing changes, not even half of it.
        assertNull(SelectionMath.toggle(setOf("a", "b"), listOf("c", "d"), 3))
        assertEquals(setOf("a", "b", "c"), SelectionMath.toggle(setOf("a", "b"), listOf("c"), 3))
    }

    @Test fun what_left_the_thread_leaves_the_pick() {
        val ids = setOf("a", "b")
        assertTrue(SelectionMath.retain(ids, setOf("a", "b", "c")) === ids)
        assertEquals(setOf("a"), SelectionMath.retain(ids, setOf("a")))
        assertEquals(emptySet<String>(), SelectionMath.retain(ids, emptySet()))
    }
}
