package app.rcq.android.net

import app.rcq.android.model.GroupMember
import app.rcq.android.model.RcqGroup
import app.rcq.android.net.GuestPendingRequests.AfterAccept
import app.rcq.android.net.GuestPendingRequests.Deposit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F1: which rows a guest poll shows, withdraws or retries, and how the
 *  island's answers are read. */
class GuestPendingRequestsTest {

    private fun row(id: Int, uin: Int = id + 1000) = GuestPendingRequests.Row(id, uin, "n$id")

    private fun plan(
        rows: List<GuestPendingRequests.Row>,
        canWithdraw: Boolean = true,
        blocked: Set<Int> = emptySet(),
        answered: Set<Int> = emptySet(),
        contacts: Set<Int> = emptySet(),
        tries: Map<Int, Int> = emptyMap(),
    ) = GuestPendingRequests.plan(
        rows, canWithdraw,
        isBlocked = { it in blocked },
        isAnswered = { it in answered },
        hasContact = { it in contacts },
        acceptTries = { _, id -> tries[id] ?: 0 },
    ).map { it.action }

    @Test fun a_new_request_is_shown() {
        assertEquals(listOf(GuestPendingRequests.Action.UPSERT), plan(listOf(row(1))))
    }

    @Test fun a_blocked_sender_is_withdrawn_when_the_island_can_and_never_shown() {
        assertEquals(listOf(GuestPendingRequests.Action.WITHDRAW), plan(listOf(row(1)), blocked = setOf(1001)))
        assertEquals(listOf(GuestPendingRequests.Action.SKIP), plan(listOf(row(1)), canWithdraw = false, blocked = setOf(1001)))
    }

    @Test fun an_answered_request_the_island_still_lists_is_withdrawn_again() {
        // The island lists only pending rows, so an answered id still listed is
        // a withdraw that did not land (lost answer, 429, a process restart in
        // between). No in-memory record is needed to retry it.
        assertEquals(listOf(GuestPendingRequests.Action.WITHDRAW), plan(listOf(row(1)), answered = setOf(1)))
        // Even when the accept tries on the row would otherwise redeposit.
        assertEquals(listOf(GuestPendingRequests.Action.WITHDRAW), plan(listOf(row(1)), answered = setOf(1), tries = mapOf(1 to 1)))
        // An island without the capability is never asked, and never declined instead.
        assertEquals(listOf(GuestPendingRequests.Action.SKIP), plan(listOf(row(1)), canWithdraw = false, answered = setOf(1)))
        // Answered rows share the per-poll budget with every other withdraw.
        val rows = (1..5).map { row(it) }
        val out = plan(rows, answered = rows.map { it.id }.toSet())
        assertEquals(GuestPendingRequests.MAX_WITHDRAWS_PER_POLL, out.count { it == GuestPendingRequests.Action.WITHDRAW })
    }

    @Test fun a_contact_we_already_hold_is_withdrawn_only() {
        assertEquals(listOf(GuestPendingRequests.Action.WITHDRAW), plan(listOf(row(1)), contacts = setOf(1001)))
        assertEquals(listOf(GuestPendingRequests.Action.SKIP), plan(listOf(row(1)), canWithdraw = false, contacts = setOf(1001)))
    }

    @Test fun an_accept_that_did_not_leave_is_redeposited_until_it_gives_up() {
        assertEquals(listOf(GuestPendingRequests.Action.REDEPOSIT), plan(listOf(row(1)), contacts = setOf(1001), tries = mapOf(1 to 1)))
        assertEquals(listOf(GuestPendingRequests.Action.REDEPOSIT), plan(listOf(row(1)), tries = mapOf(1 to 2)))
        assertEquals(listOf(GuestPendingRequests.Action.KEEP), plan(listOf(row(1)), tries = mapOf(1 to 3)))
    }

    @Test fun withdraws_per_poll_are_capped_and_the_rest_wait() {
        val rows = (1..5).map { row(it) }
        val out = plan(rows, blocked = rows.map { it.fromUin }.toSet())
        assertEquals(3, out.count { it == GuestPendingRequests.Action.WITHDRAW })
        assertEquals(2, out.count { it == GuestPendingRequests.Action.SKIP })
    }

    @Test fun at_most_twenty_rows_per_island() {
        assertEquals(GuestPendingRequests.MAX_ROWS_PER_HOST, plan((1..25).map { row(it) }).size)
    }

    @Test fun withdraw_answers() {
        assertEquals(GuestPendingRequests.Withdraw.DONE, GuestPendingRequests.withdrawOutcome(204, null))
        assertEquals(GuestPendingRequests.Withdraw.DONE, GuestPendingRequests.withdrawOutcome(404, "no_such_request"))
        // A 404 without the endpoint's code is a missing route, not "done".
        assertEquals(GuestPendingRequests.Withdraw.ROUTE_LOST, GuestPendingRequests.withdrawOutcome(404, null))
        assertEquals(GuestPendingRequests.Withdraw.UNAUTHORIZED, GuestPendingRequests.withdrawOutcome(401, null))
        assertEquals(GuestPendingRequests.Withdraw.RATE_LIMITED, GuestPendingRequests.withdrawOutcome(429, "rate_limited"))
        assertEquals(GuestPendingRequests.Withdraw.RETRY, GuestPendingRequests.withdrawOutcome(502, null))
    }

    @Test fun after_accept() {
        assertEquals(AfterAccept.FINISH, GuestPendingRequests.afterAccept(Deposit.SENT, canWithdraw = true, triesBefore = 0))
        assertEquals(AfterAccept.FINISH_HIDE_ONLY, GuestPendingRequests.afterAccept(Deposit.SENT, canWithdraw = false, triesBefore = 0))
        assertEquals(AfterAccept.RETRYING, GuestPendingRequests.afterAccept(Deposit.ADDED_ONLY, canWithdraw = true, triesBefore = 0))
        assertEquals(AfterAccept.RETRYING, GuestPendingRequests.afterAccept(Deposit.ADDED_ONLY, canWithdraw = true, triesBefore = 1))
        assertEquals(AfterAccept.GAVE_UP, GuestPendingRequests.afterAccept(Deposit.ADDED_ONLY, canWithdraw = true, triesBefore = 2))
        assertEquals(AfterAccept.FAILED, GuestPendingRequests.afterAccept(Deposit.FAILED, canWithdraw = true, triesBefore = 0))
    }

    private fun member(uin: Int, sk: String? = null) =
        GroupMember(uin = uin, nickname = "m$uin", role = "member", identityKey = "ik", signingKey = sk)

    @Test fun shared_rooms_come_from_cached_rosters_on_that_island_only() {
        val groups = listOf(
            RcqGroup(id = -1000, name = "Hikers", ownerUin = 1, host = "b.example", members = listOf(member(7))),
            RcqGroup(id = -1001, name = "Empty roster", ownerUin = 1, host = "b.example"),
            RcqGroup(id = -1002, name = "Other island", ownerUin = 1, host = "c.example", members = listOf(member(7))),
            RcqGroup(id = 5, name = "Home room", ownerUin = 1, members = listOf(member(7))),
        )
        assertEquals(listOf("Hikers"), GuestPendingRequests.sharedRooms(groups, "B.example", 7))
        assertTrue(GuestPendingRequests.sharedRooms(groups, "b.example", 8).isEmpty())
    }

    @Test fun key_comparison_is_by_bytes() {
        val key = ByteArray(32) { (it * 7 + 250).toByte() }
        val std = java.util.Base64.getEncoder().encodeToString(key)
        val url = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key)
        val other = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        assertFalse(GuestPendingRequests.keyDiffers(std, emptyList()))
        assertFalse(GuestPendingRequests.keyDiffers(std, listOf(url)))
        assertFalse(GuestPendingRequests.keyDiffers(url, listOf(other, std)))
        assertTrue(GuestPendingRequests.keyDiffers(std, listOf(other)))
    }
}
