package app.rcq.android.net

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `decideGuestPath` (spec 2026-09-15, 12.1), the table every client tests the
 * same way: the new routes only where the island advertises
 * `guest_accounts_v1: true`, the legacy paths everywhere else.
 */
class GuestPathDecisionTest {

    private fun info(json: String): RcqApi.ServerInfoResponse =
        Gson().fromJson(json, RcqApi.ServerInfoResponse::class.java)

    @Test
    fun theCapabilityTable() {
        assertEquals(GuestPath.Path.LEGACY, GuestPath.decide(null as RcqApi.ServerInfoResponse?))
        assertEquals(GuestPath.Path.LEGACY, GuestPath.decide(null as Boolean?))
        assertEquals(GuestPath.Path.LEGACY, GuestPath.decide(false))
        assertEquals(GuestPath.Path.GUEST, GuestPath.decide(true))
    }

    @Test
    fun whatAnIslandActuallySends() {
        // An island older than capabilities, older than the field, one that
        // admits no guests right now, a garbled one, and the new one.
        assertEquals(GuestPath.Path.LEGACY, GuestPath.decide(info("""{"name":"old"}""")))
        assertEquals(GuestPath.Path.LEGACY, GuestPath.decide(info("""{"capabilities":{"registration_policy":"paid"}}""")))
        assertEquals(GuestPath.Path.LEGACY, GuestPath.decide(info("""{"capabilities":{"guest_accounts_v1":false}}""")))
        assertEquals(GuestPath.Path.LEGACY, GuestPath.decide(info("""{"capabilities":null}""")))
        assertEquals(GuestPath.Path.LEGACY, GuestPath.decide(info("""{"capabilities":{"guest_accounts_v1":null}}""")))
        assertEquals(GuestPath.Path.GUEST, GuestPath.decide(info("""{"capabilities":{"guest_accounts_v1":true,"registration_policy":"paid"}}""")))
    }

    @Test
    fun aGuestNicknameNeverCarriesANumber() {
        assertEquals("Guest", GuestPath.nicknameFor(null))
        assertEquals("Guest", GuestPath.nicknameFor("   "))
        assertEquals("Anna", GuestPath.nicknameFor(" Anna "))
        assertEquals(64, GuestPath.nicknameFor("x".repeat(100)).length)
        assertFalse(GuestPath.nicknameFor(null).any { it.isDigit() })
        assertFalse(GuestPath.nicknameFor("", 4821).any { it.isDigit() })
    }

    /** Decision D1: the legacy fallback "user-<uin>", stored as the nickname by
     *  a phrase sign-in whose profile read failed, is the HOME number. */
    @Test
    fun aNameThatSpellsTheHomeNumberIsNoName() {
        assertEquals("Guest", GuestPath.nicknameFor("user-4821", 4821))
        assertEquals("Guest", GuestPath.nicknameFor("  4821 ", 4821))
        assertEquals("Guest", GuestPath.nicknameFor("Anna 4821!", 4821))
        // Whole digit runs only: another number is just a name.
        assertEquals("Anna1985", GuestPath.nicknameFor("Anna1985", 198))
        assertEquals("user-4821", GuestPath.nicknameFor("user-4821", 1234))
        // Without the number there is nothing to compare against.
        assertEquals("user-4821", GuestPath.nicknameFor("user-4821"))
        assertTrue(GuestPath.carriesNumber("x-7-y", 7))
        assertFalse(GuestPath.carriesNumber("x-77-y", 7))
    }

    /** Decision E6: the legacy-name repair writes THIS word, not our own
     *  nickname, so one account's copy on one island is called the same thing
     *  whichever client did the repair. Pinned as a literal because the other
     *  two clients pin the same literal (web `GUEST_NICKNAME_PLACEHOLDER`, iOS
     *  `GuestNickname.neutral`); changing it here alone is the divergence. */
    @Test
    fun theNeutralNameIsTheSameWordOnEveryClient() {
        assertEquals("Guest", GuestPath.NEUTRAL_NICKNAME)
        assertFalse(GuestPath.NEUTRAL_NICKNAME.any { it.isDigit() })
    }

    // ── decision D2: a retired key on /auth/recover is not "no account" ──

    @Test
    fun aRotatedRecoverIsNotAnAbsentAccountInsideTheGuestFlow() {
        val rotated = "HTTP 404: {\"detail\":{\"code\":\"identity_rotated\",\"uin\":5}}"
        val absent = "HTTP 404: {\"detail\":{\"code\":\"identity_not_found\"}}"
        assertFalse(GuestPath.recoverAbsent(rotated, rotatedIsError = true))
        assertTrue(GuestPath.recoverAbsent(absent, rotatedIsError = true))
        assertTrue(GuestPath.recoverAbsent("HTTP 404: {\"detail\":\"Not Found\"}", rotatedIsError = true))
        // Every other caller keeps what a 404 always meant.
        assertTrue(GuestPath.recoverAbsent(rotated, rotatedIsError = false))
        assertFalse(GuestPath.recoverAbsent("HTTP 503: x", rotatedIsError = true))
        assertFalse(GuestPath.recoverAbsent(null, rotatedIsError = false))
    }

    // ── decision D5: what a tapped room member may open ──

    private fun member(uin: Int, guest: Boolean = false, invited: Boolean = false) =
        app.rcq.android.model.GroupMember(
            uin = uin, nickname = "m$uin", role = "member", identityKey = "k",
            guest = guest, invited = invited,
        )

    @Test
    fun aGuestCopyOpensAnAddOnlyCardAndASeatNothing() {
        assertEquals(GuestPath.MemberCard.FULL, GuestPath.memberCard(member(1)))
        assertEquals(GuestPath.MemberCard.ADD_ONLY, GuestPath.memberCard(member(2, guest = true)))
        assertEquals(GuestPath.MemberCard.NONE, GuestPath.memberCard(member(3, guest = true, invited = true)))
        assertEquals(GuestPath.MemberCard.NONE, GuestPath.memberCard(member(4, invited = true)))
        assertEquals(GuestPath.MemberCard.FULL, GuestPath.memberCard(null))
    }

    // ── decision D8: the last resident is warned ──

    @Test
    fun theLastResidentIsWarnedBeforeLeaving() {
        val guests = listOf(member(1), member(2, guest = true), member(3, guest = true, invited = true))
        assertTrue(GuestPath.lastResidentLeaving(guests, ownUin = 1, memberCount = 3))
        // Another resident stays: nothing is deleted.
        assertFalse(GuestPath.lastResidentLeaving(guests + member(4), ownUin = 1, memberCount = 4))
        // We are a guest there ourselves: our leaving deletes nothing.
        assertFalse(GuestPath.lastResidentLeaving(guests, ownUin = 2, memberCount = 3))
        // Alone in the room, or not in the roster we hold.
        assertFalse(GuestPath.lastResidentLeaving(listOf(member(1)), ownUin = 1, memberCount = 1))
        assertFalse(GuestPath.lastResidentLeaving(guests, ownUin = 9, memberCount = 3))
        // A partial roster proves nothing.
        assertFalse(GuestPath.lastResidentLeaving(guests, ownUin = 1, memberCount = 40))
    }

    /** Decision E4: three answers, not two. A roster that cannot answer is
     *  FETCHED by the caller (`Session.lastResidentWarning`) and only then
     *  decided on; it is never read as "nothing to warn about", which is what a
     *  room on another island always looked like before its screen was opened. */
    @Test
    fun aRosterThatCannotAnswerAsksForOne() {
        val copies = listOf(member(1), member(2, guest = true), member(3, guest = true, invited = true))
        assertEquals(GuestPath.LeaveCheck.WARN, GuestPath.leaveCheck(copies, ownUin = 1, memberCount = 3))
        // A page of a big room: the members we cannot see are the answer.
        assertEquals(GuestPath.LeaveCheck.NEED_ROSTER, GuestPath.leaveCheck(copies, ownUin = 1, memberCount = 40))
        // No roster at all: a foreign room nobody has opened yet.
        assertEquals(GuestPath.LeaveCheck.NEED_ROSTER, GuestPath.leaveCheck(emptyList(), ownUin = 1, memberCount = 12))
        // We are not in the roster we hold, so it is not all of this room.
        assertEquals(
            GuestPath.LeaveCheck.NEED_ROSTER,
            GuestPath.leaveCheck(listOf(member(2, guest = true)), ownUin = 1, memberCount = 1),
        )
        // One resident anywhere in a partial page settles it without a fetch:
        // somebody who lives there stays, whatever the rest of the page holds.
        assertEquals(GuestPath.LeaveCheck.SAFE, GuestPath.leaveCheck(copies + member(4), ownUin = 1, memberCount = 400))
        // A copy leaving strands nobody, whatever the rest of the room is.
        assertEquals(GuestPath.LeaveCheck.SAFE, GuestPath.leaveCheck(copies, ownUin = 2, memberCount = 400))
        // Alone in the room: nothing is taken from anybody.
        assertEquals(GuestPath.LeaveCheck.SAFE, GuestPath.leaveCheck(listOf(member(1)), ownUin = 1, memberCount = 1))
    }

    @Test
    fun addIsHiddenWhereverOurSessionOnTheRoomsIslandIsACopy() {
        assertTrue(GuestPath.hideAddInRoom("paid.example", true))
        assertFalse(GuestPath.hideAddInRoom("paid.example", false))
        assertFalse(GuestPath.hideAddInRoom("paid.example", null))
        // A foreign room is decided by our copy THERE, never by what the
        // primary session happens to be at home.
        assertTrue(GuestPath.hideAddInRoom("paid.example", true, primaryIsGuest = false))
        assertFalse(GuestPath.hideAddInRoom("paid.example", false, primaryIsGuest = true))
        // ⚠ A room on our own island: the app's own session can BE a guest copy
        // (spec 12.1), and then the rooms it has here refuse its adds too. The
        // guard used to stop at foreign rooms, which left Add on screen for
        // exactly the account that may never use it.
        assertTrue(GuestPath.hideAddInRoom(null, null, primaryIsGuest = true))
        assertFalse(GuestPath.hideAddInRoom(null, true, primaryIsGuest = false))
    }
}
