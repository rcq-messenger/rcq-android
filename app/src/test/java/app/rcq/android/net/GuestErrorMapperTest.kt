package app.rcq.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Refusals of the guest routes, as the island words them (spec 2026-09-15,
 * sections 11 and 12.5). Messages are the `HTTP <status>: <body>` form RcqApi
 * throws, with the body the island sends.
 */
class GuestErrorMapperTest {

    private fun refused(status: Int, code: String, extra: String = "") =
        "HTTP $status: {\"detail\":{\"code\":\"$code\"$extra}}"

    // ── what to do next on /auth/guest ───────────────────────────────────

    @Test
    fun aStaleOrBusyChallengeIsRetriedOnce() {
        for (c in listOf("invalid_challenge" to 400, "guest_replayed" to 409, "guest_busy" to 409)) {
            assertEquals(c.first, GuestPath.JoinStep.RETRY_FRESH_CHALLENGE, GuestPath.joinStep(refused(c.second, c.first)))
        }
    }

    @Test
    fun aRotatedKeyIsTheRotatedFlowNeverARefusal() {
        assertEquals(
            GuestPath.JoinStep.ROTATED,
            GuestPath.joinStep("HTTP 404: {\"detail\":{\"code\":\"identity_rotated\",\"uin\":123}}"),
        )
    }

    @Test
    fun aMissingRouteFallsBackToLegacy() {
        assertEquals(GuestPath.JoinStep.LEGACY, GuestPath.joinStep("HTTP 404: {\"detail\":\"Not Found\"}"))
        assertEquals(GuestPath.JoinStep.LEGACY, GuestPath.joinStep("HTTP 405: {\"detail\":\"Method Not Allowed\"}"))
        assertEquals(GuestPath.JoinStep.LEGACY, GuestPath.joinStep("HTTP 404: "))
        // A 404 WITH a code is the island answering, not a missing route.
        assertEquals(GuestPath.JoinStep.REFUSED, GuestPath.joinStep(refused(404, "group_not_found")))
    }

    @Test
    fun serverTroubleAndSilenceTryRecoverOnce() {
        assertEquals(GuestPath.JoinStep.TRANSIENT, GuestPath.joinStep(refused(503, "guest_unavailable")))
        assertEquals(GuestPath.JoinStep.TRANSIENT, GuestPath.joinStep("HTTP 502: <html>bad gateway</html>"))
        assertEquals(GuestPath.JoinStep.TRANSIENT, GuestPath.joinStep("timeout"))
        assertEquals(GuestPath.JoinStep.TRANSIENT, GuestPath.joinStep(null))
    }

    @Test
    fun everythingElseIsTerminal() {
        val terminal = listOf(
            refused(400, "guest_proof_version"), refused(400, "guest_proof_malformed"),
            refused(400, "guest_wrong_host"), refused(401, "bad_signature"), refused(401, "device_revoked"),
            refused(403, "guest_closed"), refused(403, "group_closed"), refused(403, "guest_room_closed"),
            refused(403, "guest_room_full"), refused(429, "guest_room_limit"),
            "HTTP 429: {\"detail\":{\"code\":\"rate_limited\",\"retry_after\":60}}",
            "HTTP 422: {\"detail\":[{\"loc\":[\"body\",\"identity_key\"]}]}",
        )
        for (m in terminal) assertEquals(m, GuestPath.JoinStep.REFUSED, GuestPath.joinStep(m))
    }

    // ── sentences for a join ─────────────────────────────────────────────

    @Test
    fun joinCodesMapToTheirSentences() {
        val table = mapOf(
            refused(403, "guest_closed") to GuestPath.Sentence.CLOSED,
            refused(403, "guest_room_closed") to GuestPath.Sentence.ROOM_CLOSED,
            refused(403, "guest_room_full") to GuestPath.Sentence.ROOM_FULL,
            refused(429, "guest_room_limit") to GuestPath.Sentence.ROOM_LIMIT,
            "HTTP 429: {\"detail\":{\"code\":\"rate_limited\",\"retry_after\":60}}" to GuestPath.Sentence.RATE,
            refused(403, "guest_group_limit") to GuestPath.Sentence.GROUP_LIMIT,
            refused(503, "guest_unavailable") to GuestPath.Sentence.UNAVAILABLE,
            "HTTP 503: {\"detail\":{\"code\":\"island_busy\",\"retry_after\":30}}" to GuestPath.Sentence.UNAVAILABLE,
            refused(403, "guest_restricted") to GuestPath.Sentence.RESTRICTED,
            // The legacy register on an island too old to take guests.
            refused(403, "entry_required") to GuestPath.Sentence.OLD_PAID,
            refused(403, "invite_required") to GuestPath.Sentence.OLD_INVITE,
            // Decision E2, the union of the three tables: the web names these
            // two on the join path as well, so one refusal cannot say two
            // different things depending on which client the person holds.
            refused(403, "invite_invalid") to GuestPath.Sentence.OLD_INVITE,
            refused(409, "target_guest") to GuestPath.Sentence.TARGET_GUEST,
            // Decision D3: still stale or busy after the one silent retry.
            refused(400, "invalid_challenge") to GuestPath.Sentence.UNAVAILABLE,
            refused(409, "guest_replayed") to GuestPath.Sentence.UNAVAILABLE,
            refused(409, "guest_busy") to GuestPath.Sentence.UNAVAILABLE,
            // Decision D2: the rotated-elsewhere notice, never a generic line.
            "HTTP 404: {\"detail\":{\"code\":\"identity_rotated\",\"uin\":5}}" to GuestPath.Sentence.ROTATED,
            // A limiter whose body was eaten on the way.
            "HTTP 429: " to GuestPath.Sentence.RATE,
            // Decision D3, one table on every client: the three refusals the
            // foreign island's own self-join and `/auth/guest` answer with get
            // their own sentence here, as they always did on the web. They used
            // to fall through to the generic line, so the same refusal said two
            // different things depending on which client the person held.
            refused(403, "group_closed") to GuestPath.Sentence.INVITE_ONLY,
            refused(403, "blocked") to GuestPath.Sentence.JOIN_BLOCKED,
            refused(404, "group_not_found") to GuestPath.Sentence.GONE,
            // A join mints no seat, so only the owner-add can really see this;
            // mapped so the table matches the web's one for one.
            refused(409, "guest_key_retired") to GuestPath.Sentence.STALE_KEY,
            "offline" to GuestPath.Sentence.JOIN_FAILED,
            null to GuestPath.Sentence.JOIN_FAILED,
        )
        for ((m, want) in table) assertEquals("$m", want, GuestPath.joinSentence(m))
    }

    /** Decision E2: the door is read off `detail.code`, the way the web and iOS
     *  read it, and no longer only through the strict 403 reader
     *  [BackupIslandPick.doorRefusal], which stays exactly what it is for
     *  choosing a backup island, where a forged body would matter. */
    @Test
    fun aDoorCodeIsTheDoorWhateverStatusCarriesIt() {
        assertEquals(GuestPath.Sentence.OLD_PAID, GuestPath.joinSentence(refused(404, "entry_required")))
        assertEquals(GuestPath.Sentence.OLD_INVITE, GuestPath.joinSentence(refused(404, "invite_required")))
    }

    // ── sentences for an owner-add ───────────────────────────────────────

    @Test
    fun addCodesMapToTheirSentences() {
        val table = mapOf(
            refused(403, "blocked", ",\"message\":\"the group owner has blocked this user\"") to GuestPath.Sentence.BLOCKED,
            refused(403, "invite_contacts_only", ",\"message\":\"x\"") to GuestPath.Sentence.CONTACTS_ONLY,
            refused(403, "invite_nobody", ",\"message\":\"x\"") to GuestPath.Sentence.NOBODY,
            refused(403, "guest_room_closed") to GuestPath.Sentence.ROOM_CLOSED,
            refused(429, "guest_add_limit", ",\"scope\":\"group\"") to GuestPath.Sentence.ADD_LIMIT,
            refused(429, "guest_add_limit", ",\"scope\":\"seat\"") to GuestPath.Sentence.ADD_SEAT_LIMIT,
            refused(429, "guest_add_limit") to GuestPath.Sentence.ADD_LIMIT,
            refused(409, "guest_key_retired") to GuestPath.Sentence.STALE_KEY,
            refused(403, "guest_room_full") to GuestPath.Sentence.ROOM_FULL,
            refused(429, "guest_room_limit") to GuestPath.Sentence.ROOM_LIMIT,
            refused(403, "guest_group_limit") to GuestPath.Sentence.GROUP_LIMIT,
            refused(403, "guest_closed") to GuestPath.Sentence.CLOSED,
            refused(403, "guest_restricted") to GuestPath.Sentence.GUEST_ADDER,
            refused(503, "guest_unavailable") to GuestPath.Sentence.UNAVAILABLE,
            // Decision E2: the web names it on the add path too.
            refused(409, "target_guest") to GuestPath.Sentence.TARGET_GUEST,
            // Decision D3: busy is "not right now", on an add as on a join.
            refused(409, "guest_busy") to GuestPath.Sentence.UNAVAILABLE,
            "HTTP 503: {\"detail\":{\"code\":\"island_busy\",\"retry_after\":30}}" to GuestPath.Sentence.UNAVAILABLE,
            refused(409, "some_future_code") to GuestPath.Sentence.ADD_FAILED,
        )
        for ((m, want) in table) assertEquals(m, want, GuestPath.addSentence(m))
    }

    @Test
    fun theNativeAddKeepsItsProseDetails() {
        assertEquals(GuestPath.Sentence.BLOCKED, GuestPath.addSentence("HTTP 403: {\"detail\":\"the group owner has blocked this user\"}"))
        assertEquals(GuestPath.Sentence.CONTACTS_ONLY, GuestPath.addSentence("HTTP 403: {\"detail\":\"this user only accepts group invites from their contacts\"}"))
        assertEquals(GuestPath.Sentence.NOBODY, GuestPath.addSentence("HTTP 403: {\"detail\":\"this user does not accept group invites\"}"))
        assertEquals(GuestPath.Sentence.ADD_FAILED, GuestPath.addSentence("HTTP 403: {\"detail\":\"not a group member\"}"))
        assertEquals(GuestPath.Sentence.ADD_FAILED, GuestPath.addSentence(null))
        // Decision E2: the web's table names this one, so this one does too.
        assertEquals(GuestPath.Sentence.NO_USER, GuestPath.addSentence("HTTP 404: {\"detail\":\"no such user\"}"))
    }

    // ── the card re-fetch before an add ──────────────────────────────────

    @Test
    fun aCardIsStaleOnlyWhenAKeyReallyChanged() {
        val ik = "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8="
        val ikUnpadded = "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8"
        val sk = "Kay64UG8yvCyLhqU000LxzYeUm0L/hLIl5S8kyKWbdc="
        val other = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
        assertFalse(GuestPath.cardStale(ik, sk, ikUnpadded, sk))
        assertFalse(GuestPath.cardStale(ik, sk, ik, sk))
        assertTrue(GuestPath.cardStale(ik, sk, other, sk))
        assertTrue(GuestPath.cardStale(ik, sk, ik, other))
        assertTrue(GuestPath.cardStale(ik, sk, "garbage", sk))
    }

    // ── POST /auth/guest/settle (9.1, decision D7) ───────────────────────

    @Test
    fun settleCodesMapToTheirSentences() {
        val table = mapOf(
            refused(409, "not_a_guest") to GuestPath.Sentence.SETTLED,
            refused(409, "invite_has_number") to GuestPath.Sentence.NUMBER_INVITE,
            refused(409, "voucher_spent") to GuestPath.Sentence.VOUCHER_SPENT,
            refused(403, "entry_required") to GuestPath.Sentence.ENTRY_REQUIRED,
            refused(403, "invite_required") to GuestPath.Sentence.INVITE_REQUIRED,
            refused(403, "invite_invalid") to GuestPath.Sentence.INVITE_INVALID,
            refused(403, "voucher_other_island") to GuestPath.Sentence.INVITE_INVALID,
            refused(403, "voucher_expired") to GuestPath.Sentence.INVITE_INVALID,
            // Decision E2, from iOS: a voucher that does not verify is, for the
            // person holding it, a code that is not good here.
            refused(401, "bad_signature") to GuestPath.Sentence.INVITE_INVALID,
            refused(503, "guest_unavailable") to GuestPath.Sentence.UNAVAILABLE,
            refused(409, "guest_busy") to GuestPath.Sentence.UNAVAILABLE,
            "HTTP 429: {\"detail\":{\"code\":\"rate_limited\",\"retry_after\":60}}" to GuestPath.Sentence.RATE,
            "HTTP 429: " to GuestPath.Sentence.RATE,
            // Decision E2: the row is restricted, which is what a guest copy
            // IS. Saying "could not become a resident" told them nothing.
            refused(403, "guest_restricted") to GuestPath.Sentence.RESTRICTED,
            "timeout" to GuestPath.Sentence.SETTLE_FAILED,
            null to GuestPath.Sentence.SETTLE_FAILED,
        )
        for ((m, want) in table) assertEquals("$m", want, GuestPath.settleSentence(m))
    }

    // ── a guest copy accepting a contact request ─────────────────────────

    @Test
    fun aRestrictedAcceptSaysAnswerFromHome() {
        assertEquals(GuestPath.Sentence.RESTRICTED_CONTACTS, GuestPath.respondSentence(refused(403, "guest_restricted")))
    }

    @Test
    fun everyOtherRespondFailureStaysSilent() {
        assertEquals(null, GuestPath.respondSentence(refused(403, "blocked")))
        assertEquals(null, GuestPath.respondSentence("HTTP 404: {\"detail\":\"request not found\"}"))
        // A prose detail that merely mentions the code is not the code.
        assertEquals(null, GuestPath.respondSentence("HTTP 403: {\"detail\":\"guest_restricted\"}"))
        assertEquals(null, GuestPath.respondSentence(null))
    }
}
