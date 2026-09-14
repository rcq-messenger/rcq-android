package app.rcq.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule of the relay access key: a network failure is never reported
 * as a bad key. Only the island's own word ("unknown", "expired") may remove
 * one; everything that is not an answer about the key keeps it.
 *
 * ⚠ 429 is in the "keeps it" set on purpose. The broker allows 30 asks a
 * minute per client address, and through the tunnel the address is the
 * relay's, shared with everybody riding it.
 */
class BrokerVerdictTest {

    @Test fun noKeySentMeansNoVerdict() {
        assertNull(BrokerVerdict.of(200, "ok", keySent = false))
        assertNull(BrokerVerdict.of(null, null, keySent = false))
        assertNull(BrokerVerdict.ofResponse(200, """{"relays":[],"key":null}""", keySent = false))
    }

    @Test fun noHttpAnswerIsOffline() {
        assertEquals(BrokerVerdict.OFFLINE, BrokerVerdict.of(null, null, keySent = true))
        assertEquals(BrokerVerdict.OFFLINE, BrokerVerdict.ofResponse(null, null, keySent = true))
    }

    @Test fun rateLimitAndServerErrorsAreOffline() {
        for (status in listOf(429, 500, 502, 503, 504, 404, 403, 401)) {
            assertEquals("status $status", BrokerVerdict.OFFLINE, BrokerVerdict.of(status, null, keySent = true))
            assertEquals(
                "status $status with a body",
                BrokerVerdict.OFFLINE,
                BrokerVerdict.ofResponse(status, """{"key":"unknown"}""", keySent = true),
            )
        }
    }

    @Test fun explicitAnswersPassThrough() {
        assertEquals(BrokerVerdict.OK, BrokerVerdict.of(200, "ok", keySent = true))
        assertEquals(BrokerVerdict.EXPIRED, BrokerVerdict.of(200, "expired", keySent = true))
        assertEquals(BrokerVerdict.UNKNOWN, BrokerVerdict.of(200, "unknown", keySent = true))
        assertEquals(BrokerVerdict.OK, BrokerVerdict.ofResponse(200, """{"relays":[],"key":"ok","private_count":0}""", keySent = true))
        assertEquals(BrokerVerdict.EXPIRED, BrokerVerdict.ofResponse(200, """{"relays":[],"key":"expired"}""", keySent = true))
    }

    @Test fun aTwoHundredThatDoesNotJudgeTheKeyIsUnknown() {
        // The island answered and did not say "ok": the key does not work here.
        assertEquals(BrokerVerdict.UNKNOWN, BrokerVerdict.of(200, null, keySent = true))
        assertEquals(BrokerVerdict.UNKNOWN, BrokerVerdict.ofResponse(200, """{"relays":[]}""", keySent = true))
        assertEquals(BrokerVerdict.UNKNOWN, BrokerVerdict.ofResponse(200, """{"relays":[],"key":null}""", keySent = true))
    }

    @Test fun aBodyThatIsNotJsonIsNoAnswer() {
        // A proxy's error page with a 200 on it, or a captive portal.
        assertEquals(BrokerVerdict.OFFLINE, BrokerVerdict.ofResponse(200, "<html>blocked</html>", keySent = true))
        assertEquals(BrokerVerdict.OFFLINE, BrokerVerdict.ofResponse(200, null, keySent = true))
    }

    @Test fun onlyTheIslandsOwnWordRemovesTheKey() {
        assertTrue(BrokerVerdict.removesKey(BrokerVerdict.UNKNOWN))
        assertTrue(BrokerVerdict.removesKey(BrokerVerdict.EXPIRED))
        assertFalse(BrokerVerdict.removesKey(BrokerVerdict.OK))
        assertFalse(BrokerVerdict.removesKey(BrokerVerdict.OFFLINE))
        assertFalse(BrokerVerdict.removesKey(null))
    }
}
