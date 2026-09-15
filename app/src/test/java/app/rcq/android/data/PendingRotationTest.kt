package app.rcq.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PendingRotation record (F3, introduced in C0 so the erase guards exist a
 * release before anything can rotate). The wire names are shared with iOS.
 */
class PendingRotationTest {

    private val sample = PendingRotation(
        id = "r1",
        startedAt = 1_757_900_000_000,
        deadlineAt = 1_760_492_000_000,
        oldIdentityPriv = "aWQ=",
        oldSigningPriv = "c2s=",
        newSeed = "c2VlZA==",
        homeHost = "api.rcq.app",
        homeUin = 123456,
        phase = PendingRotation.Phase.HOME_DONE,
        homeProof = PendingRotation.HomeProof(ts = 1_757_900_000, nonce = "n", sig = "s", lastSentAt = null),
        targets = listOf(
            PendingRotation.Target(
                kind = PendingRotation.Target.Kind.VISITED, host = "is2.example", uin = 777,
                status = PendingRotation.Target.Status.FORGOTTEN, reason = null, attempts = 2,
                lastTryAt = 1_757_900_100_000, proof = null,
            ),
        ),
        retiredIdentityUntil = null,
        sibling = false,
    )

    @Test
    fun roundTripsThroughJson() {
        assertEquals(sample, PendingRotation.decode(sample.encode()))
    }

    @Test
    fun usesTheSpecWireNames() {
        val json = sample.encode()
        assertTrue(json, json.contains("\"phase\":\"home_done\""))
        assertTrue(json, json.contains("\"status\":\"forgotten\""))
        assertTrue(json, json.contains("\"kind\":\"visited\""))
    }

    @Test
    fun anUnreadableRecordStillCountsAsPresent() {
        for (raw in listOf("{not json", "{}", "{\"id\":\"r1\"}", "[]")) {
            assertNull(raw, PendingRotation.decode(raw))
            assertTrue(raw, PendingRotation.isPresent(raw))
        }
        assertFalse(PendingRotation.isPresent(null))
        assertFalse(PendingRotation.isPresent(""))
        assertNull(PendingRotation.decode(null))
    }
}
