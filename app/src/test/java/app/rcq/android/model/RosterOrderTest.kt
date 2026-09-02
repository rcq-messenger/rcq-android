package app.rcq.android.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one rule behind the member list of a group: the owner, then the
 * moderators, then whoever is around, then everyone else, by name inside a
 * tier (founder, 02.09: the same order on every client). Host JVM
 * (`./gradlew testDebugUnitTest`), no emulator.
 */
class RosterOrderTest {

    private fun member(
        uin: Int,
        nickname: String,
        role: String = "member",
        status: String? = null,
        permissions: List<String> = emptyList(),
    ) = GroupMember(uin = uin, nickname = nickname, role = role, status = status, identityKey = "", permissions = permissions)

    private fun names(members: List<GroupMember>, ownerUin: Int) =
        orderedRoster(members, ownerUin).map { it.nickname }

    @Test
    fun ownerThenModeratorsThenPresentThenRest() {
        val roster = listOf(
            member(5, "zed", status = "offline"),
            member(4, "yan", status = "online"),
            member(3, "bob", role = "admin", status = "offline"),
            member(2, "amy", status = "away"),
            member(1, "owen", status = "offline"),
        )
        // The owner is offline and last by name; he still comes first. The
        // admin is offline too and outranks everyone who is around.
        assertEquals(listOf("owen", "bob", "amy", "yan", "zed"), names(roster, ownerUin = 1))
    }

    @Test
    fun anyGrantedCapIsAModerator() {
        val roster = listOf(
            member(3, "carl", status = "online"),
            member(2, "beth", permissions = listOf("info")),
            member(1, "ann"),
        )
        // A member the owner trusted with part of the room sits with the
        // moderators, not with the online crowd - the same set the composer
        // exempts from the room rules.
        assertEquals(listOf("ann", "beth", "carl"), names(roster, ownerUin = 1))
    }

    @Test
    fun awayAndDndCountAsAround_invisibleAndUnknownDoNot() {
        val roster = listOf(
            member(6, "f", status = "invisible"),
            member(5, "e", status = null),
            member(4, "d", status = "dnd"),
            member(3, "c", status = "away"),
            member(2, "b", status = "online"),
            member(1, "a"),
        )
        // Inside the around tier it is by name only: "b" online, "c" away and
        // "d" dnd keep alphabetical order, not online-first.
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), names(roster, ownerUin = 1))
    }

    @Test
    fun namesCompareCaseInsensitively() {
        val roster = listOf(
            member(4, "bob"),
            member(3, "Alice"),
            member(2, "charlie"),
            member(1, "Owner"),
        )
        assertEquals(listOf("Owner", "Alice", "bob", "charlie"), names(roster, ownerUin = 1))
    }

    @Test
    fun equalNamesBreakTheTieByUin() {
        // Two members with the same name would otherwise keep the roster's
        // arrival order, which is random, and the group would shuffle between
        // openings (#688).
        val a = listOf(member(9, "sam"), member(2, "sam"), member(1, "own"))
        val b = listOf(member(2, "sam"), member(1, "own"), member(9, "sam"))
        val ua = orderedRoster(a, ownerUin = 1).map { it.uin }
        val ub = orderedRoster(b, ownerUin = 1).map { it.uin }
        assertEquals(listOf(1, 2, 9), ua)
        assertEquals(ua, ub)
    }

    @Test
    fun crownIsReadOffOwnerUinNotRole() {
        // A compact membership event carries the new owner alone: the roster
        // rows still say "owner" on the old one and "member" on the new one.
        val roster = listOf(
            member(2, "new", role = "member"),
            member(1, "old", role = "owner"),
        )
        assertEquals(listOf("new", "old"), names(roster, ownerUin = 2))
    }
}
