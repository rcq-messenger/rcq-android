package app.rcq.android.net

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The wire of `POST /groups/{id}/transfer-owner` (founder item 23), checked on
 * the JVM against the bodies the island's groups.py sends.
 *
 * The refusal is the half worth pinning. Every one of the island's five codes
 * is a fact about the TARGET or about us rather than a network hiccup, and
 * each is shown as its own sentence, so reading `detail.code` back out of the
 * `HTTP <status>: <body>` message [RcqApi.execute] throws is what stands
 * between "they are not in this group any more" and a raw stack trace on a
 * confirm sheet. It must also survive the two shapes that carry no code at
 * all: the plain-string `detail` most endpoints use, and an island so old that
 * the route does not exist.
 */
class TransferOwnerWireTest {

    private val gson = Gson()

    @Test fun bodyNamesTheTargetAsToUin() {
        val body = JsonParser.parseString(gson.toJson(RcqApi.TransferOwnerBody(500))).asJsonObject
        assertEquals(500, body.get("to_uin").asInt)
        assertEquals("nothing else rides along", 1, body.entrySet().size)
    }

    @Test fun everyRefusalCodeIsRead() {
        val cases = mapOf(
            403 to "owner_only",
            400 to "already_owner",
            404 to "not_a_member",
            409 to "target_suspended",
        )
        for ((status, code) in cases) {
            val refusal = RcqApi.refusalOf("HTTP $status: {\"detail\":{\"code\":\"$code\"}}")
            assertEquals(code, refusal.code)
            assertEquals(status, refusal.status)
            assertNull(refusal.retryAfter)
        }
        // The second 404: a membership row whose account this island cannot
        // resolve, which is a different sentence from "not a member".
        assertEquals(
            "no_such_user",
            RcqApi.refusalOf("HTTP 404: {\"detail\":{\"code\":\"no_such_user\"}}").code,
        )
    }

    @Test fun rateLimitCarriesItsWait() {
        val refusal = RcqApi.refusalOf(
            "HTTP 429: {\"detail\":{\"code\":\"rate_limited\",\"retry_after\":142}}"
        )
        assertEquals("rate_limited", refusal.code)
        assertEquals(142, refusal.retryAfter)
    }

    @Test fun a429StrippedOfItsBodyIsStillA429() {
        // A proxy or an older limiter can eat the JSON; the ceiling is still
        // what happened, which is why the status is kept alongside the code.
        val refusal = RcqApi.refusalOf("HTTP 429: ")
        assertEquals(429, refusal.status)
        assertNull(refusal.code)
        assertNull(refusal.retryAfter)
    }

    @Test fun aProseDetailIsNotACode() {
        // What the rest of the groups router answers with. It is a sentence
        // for a log, not a branch, and reading it as one would show the user
        // English on a Russian phone.
        val refusal = RcqApi.refusalOf("HTTP 403: {\"detail\":\"owner only\"}")
        assertEquals(403, refusal.status)
        assertNull(refusal.code)
    }

    @Test fun anIslandWithoutTheRouteIsNotACode() {
        // FastAPI's own 404 when the endpoint has not been deployed yet.
        val refusal = RcqApi.refusalOf("HTTP 404: {\"detail\":\"Not Found\"}")
        assertEquals(404, refusal.status)
        assertNull(refusal.code)
    }

    @Test fun garbageAndSilenceAreSurvived() {
        for (message in listOf(null, "", "timeout", "HTTP 500: <html>502</html>")) {
            val refusal = RcqApi.refusalOf(message)
            assertNull(refusal.code)
            assertNull(refusal.retryAfter)
        }
    }
}
