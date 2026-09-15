package app.rcq.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * P0.2 of the 15.09 cross-island spec: the one decision that can erase a live
 * account. The messages are the exact `HTTP <status>: <body>` form RcqApi
 * throws, with the body the island sends.
 */
class AuthRefusalTest {

    private fun refused(code: String, status: Int = 404) = "HTTP $status: {\"detail\":{\"code\":\"$code\"}}"
    private val rotated = "HTTP 404: {\"detail\":{\"code\":\"identity_rotated\",\"uin\":123456}}"
    private val notFound = refused(AuthRefusal.IDENTITY_NOT_FOUND)
    private val ambiguous = refused(AuthRefusal.IDENTITY_AMBIGUOUS)
    private val bools = listOf(false, true)

    @Test
    fun identityRotatedNeverErases() {
        for (moved in bools) for (pending in bools) {
            assertNotEquals(AuthRefusal.ProbeOutcome.ERASE, AuthRefusal.probeOutcome(rotated, moved, pending))
            assertNotEquals(AuthRefusal.FollowOutcome.MOVE_REFUSED, AuthRefusal.followOutcome(rotated, pending))
        }
        assertEquals(AuthRefusal.ProbeOutcome.ROTATED_ELSEWHERE, AuthRefusal.probeOutcome(rotated, false, false))
        assertEquals(AuthRefusal.ProbeOutcome.ROTATED_ELSEWHERE, AuthRefusal.probeOutcome(rotated, true, false))
        assertEquals(AuthRefusal.FollowOutcome.ROTATED_ELSEWHERE, AuthRefusal.followOutcome(rotated, false))
    }

    @Test
    fun identityAmbiguousNeverErases() {
        for (moved in bools) for (pending in bools) {
            assertEquals(AuthRefusal.ProbeOutcome.MOVE_REFUSED, AuthRefusal.probeOutcome(ambiguous, moved, pending))
        }
    }

    @Test
    fun aPendingRotationNeverErasesWhateverTheCode() {
        val any = listOf(rotated, notFound, ambiguous, refused("something_else"), "HTTP 404: {}", null)
        for (m in any) for (moved in bools) {
            assertNotEquals("$m", AuthRefusal.ProbeOutcome.ERASE, AuthRefusal.probeOutcome(m, moved, rotationPending = true))
            assertNotEquals("$m", AuthRefusal.ProbeOutcome.ROTATED_ELSEWHERE, AuthRefusal.probeOutcome(m, moved, rotationPending = true))
        }
    }

    @Test
    fun identityNotFoundErasesOnlyWithoutEitherMarker() {
        assertEquals(AuthRefusal.ProbeOutcome.ERASE, AuthRefusal.probeOutcome(notFound, false, false))
        assertEquals(AuthRefusal.ProbeOutcome.MOVE_REFUSED, AuthRefusal.probeOutcome(notFound, true, false))
        assertEquals(AuthRefusal.ProbeOutcome.KEEP, AuthRefusal.probeOutcome(notFound, false, true))
    }

    @Test
    fun onlyTheExactCodeOfA404CanErase() {
        val lookalikes = listOf(
            // The old substring match wiped on every one of these.
            refused("identity_not_found_v2"),
            refused("not_identity_not_found"),
            refused(AuthRefusal.IDENTITY_NOT_FOUND, status = 500),
            refused(AuthRefusal.IDENTITY_NOT_FOUND, status = 403),
            "HTTP 404: {\"detail\":\"identity_not_found\"}",
            "HTTP 404: identity_not_found",
            "timeout identity_not_found",
            "HTTP 404: {\"detail\":{\"code\":\"identity_not_fou",
            "HTTP 404: ",
            "",
            null,
        )
        for (m in lookalikes) {
            assertEquals("$m", AuthRefusal.ProbeOutcome.KEEP, AuthRefusal.probeOutcome(m, false, false))
        }
    }

    @Test
    fun followingAMoveNeverErasesAndMapsTheCodes() {
        assertEquals(AuthRefusal.FollowOutcome.MOVE_REFUSED, AuthRefusal.followOutcome(ambiguous, false))
        // From an island too old to say `identity_ambiguous`.
        assertEquals(AuthRefusal.FollowOutcome.MOVE_REFUSED, AuthRefusal.followOutcome(notFound, false))
        assertEquals(AuthRefusal.FollowOutcome.RETRY_LATER, AuthRefusal.followOutcome(rotated, true))
        assertEquals(AuthRefusal.FollowOutcome.RETRY_LATER, AuthRefusal.followOutcome("HTTP 503: busy", false))
        assertEquals(AuthRefusal.FollowOutcome.RETRY_LATER, AuthRefusal.followOutcome(null, false))
    }
}
