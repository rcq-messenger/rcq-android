package app.rcq.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a key rotation follows across islands (#986, spec 2026-09-15 F3).
 *
 * Every one of these guards a way the old phrase could keep working somewhere
 * after somebody changed it, which is the only failure mode that matters here:
 * a rotation that misses one island is not a smaller rotation, it is a false
 * promise.
 */
class ReissueCascadeTest {

    private fun row(host: String, uin: Int? = 5, token: String? = "t") = Triple(host, uin, token)

    @Test
    fun theHomeIslandIsNeverInTheCascade() {
        val plan = ReissueCascade.plan(
            backups = listOf(row("api.rcq.app"), row("is2.rcq.app")),
            visited = listOf(row("api.rcq.app"), row("third.example")),
            ownHost = "api.rcq.app",
        )
        assertEquals(listOf("is2.rcq.app", "third.example"), plan.map { it.host })
    }

    @Test
    fun anIslandNamedTwiceIsOneTarget() {
        val plan = ReissueCascade.plan(
            backups = listOf(row("is2.rcq.app", 7, null)),
            visited = listOf(row("IS2.rcq.app", 7, "jwt")),
            ownHost = "api.rcq.app",
        )
        assertEquals(1, plan.size)
        // ⚠ The row that carries a token wins: recovering a token costs a round
        // trip and can fail, and one already in hand cannot.
        assertEquals("jwt", plan[0].token)
        assertTrue("a backup stays a backup", plan[0].backup)
    }

    @Test
    fun backupsGoFirst() {
        val plan = ReissueCascade.plan(
            backups = listOf(row("b.example")),
            visited = listOf(row("a.example"), row("c.example")),
            ownHost = "api.rcq.app",
        )
        assertEquals("b.example", plan.first().host)
    }

    @Test
    fun aFrontIsNotAnIsland() {
        val plan = ReissueCascade.plan(
            backups = emptyList(),
            visited = listOf(row("front.example"), row("real.example")),
            ownHost = "api.rcq.app",
            skipHost = { it == "front.example" },
        )
        assertEquals(listOf("real.example"), plan.map { it.host })
    }

    @Test
    fun anEmptyHostIsNotATarget() {
        val plan = ReissueCascade.plan(
            backups = listOf(row("  ")),
            visited = listOf(row("")),
            ownHost = "api.rcq.app",
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun theIslandsAnswersAreReadTheWayTheISLANDSpellsThem() {
        // ⚠ Every one of these codes was read off the live route
        // (routers/auth.py, `reissue`) and checked against two running islands
        // on 19.09. They are `reissue_*`: the short spellings this once looked
        // for matched nothing at all.
        assertEquals(ReissueCascade.Outcome.DONE, ReissueCascade.classify(200, null))
        assertEquals(ReissueCascade.Outcome.GONE, ReissueCascade.classify(404, "user_not_found"))
        assertEquals(ReissueCascade.Outcome.GONE, ReissueCascade.classify(404, "identity_not_found"))
        assertEquals(ReissueCascade.Outcome.GONE, ReissueCascade.classify(404, null))
        assertEquals(ReissueCascade.Outcome.DIFFERENT_KEY, ReissueCascade.classify(409, "reissue_old_key_mismatch"))
        assertEquals(ReissueCascade.Outcome.DIFFERENT_KEY, ReissueCascade.classify(409, "reissue_replayed"))
        assertEquals(ReissueCascade.Outcome.DIFFERENT_KEY, ReissueCascade.classify(403, "reissue_bad_signature"))
        assertEquals(ReissueCascade.Outcome.REFUSED, ReissueCascade.classify(400, "reissue_wrong_host"))
        assertEquals(ReissueCascade.Outcome.REFUSED, ReissueCascade.classify(400, "reissue_proof_version"))
        assertEquals(ReissueCascade.Outcome.REFUSED, ReissueCascade.classify(400, "reissue_proof_malformed"))
        assertEquals(ReissueCascade.Outcome.REFUSED, ReissueCascade.classify(403, "reissue_proof_required"))
        assertEquals(ReissueCascade.Outcome.REFUSED, ReissueCascade.classify(403, "key_proof_required"))
        assertEquals(ReissueCascade.Outcome.REFUSED, ReissueCascade.classify(400, "bad_key"))
        assertEquals(ReissueCascade.Outcome.UNREACHABLE, ReissueCascade.classify(503, "reissue_unavailable"))
        assertEquals(ReissueCascade.Outcome.UNREACHABLE, ReissueCascade.classify(400, "reissue_clock_skew"))
        assertEquals(ReissueCascade.Outcome.UNREACHABLE, ReissueCascade.classify(401, null))
        assertEquals(ReissueCascade.Outcome.UNREACHABLE, ReissueCascade.classify(429, null))
        assertEquals(ReissueCascade.Outcome.UNREACHABLE, ReissueCascade.classify(503, null))
        assertEquals(ReissueCascade.Outcome.UNREACHABLE, ReissueCascade.classify(null, null))
    }

    @Test
    fun aRefusalTheIslandWillRepeatIsNotOfferedAsARetry() {
        assertTrue(ReissueCascade.retryable(ReissueCascade.Outcome.UNREACHABLE))
        assertFalse(ReissueCascade.retryable(ReissueCascade.Outcome.REFUSED))
        assertFalse(ReissueCascade.retryable(ReissueCascade.Outcome.DIFFERENT_KEY))
        assertFalse(ReissueCascade.retryable(ReissueCascade.Outcome.DONE))
    }

    @Test
    fun aRefusedIslandStillKeepsTheOldKeyAlive() {
        // ⚠ The copy there is on the old key, whatever the island's reason was:
        // destroying it would strand that copy for ever.
        assertFalse(ReissueCascade.settled(listOf(ReissueCascade.Outcome.DONE, ReissueCascade.Outcome.REFUSED)))
    }

    @Test
    fun aStaleTokenIsNotAStaleKey() {
        // 401 is the token, not the key: rotating on the strength of it would
        // mark an island done that never heard the request.
        assertEquals(ReissueCascade.Outcome.UNREACHABLE, ReissueCascade.classify(401, "expired"))
    }

    @Test
    fun whatTheIslandThrewIsReadOffTheMessage() {
        // The exact shape RcqApi raises.
        val m = """HTTP 409: {"detail":{"code":"old_key_mismatch"}}"""
        assertEquals(409, ReissueCascade.statusOf(m))
        assertEquals("old_key_mismatch", ReissueCascade.codeOf(m))
        assertEquals(ReissueCascade.Outcome.DIFFERENT_KEY, ReissueCascade.classify(m))
    }

    @Test
    fun aTimeoutHasNoStatusAndIsWorthRetrying() {
        assertEquals(null, ReissueCascade.statusOf("timeout"))
        assertEquals(null, ReissueCascade.codeOf("timeout"))
        assertEquals(ReissueCascade.Outcome.UNREACHABLE, ReissueCascade.classify("timeout"))
        assertEquals(ReissueCascade.Outcome.UNREACHABLE, ReissueCascade.classify(null as String?))
    }

    @Test
    fun aProxysHtmlDoesNotBecomeAnErrorCode() {
        // ⚠ The body of a refusal is not always the island's JSON: a front or a
        // CDN answers with a page. Reading a code out of that must give null
        // and leave the status to decide, not throw.
        val m = "HTTP 502: <html><body>Bad Gateway</body></html>"
        assertEquals(502, ReissueCascade.statusOf(m))
        assertEquals(null, ReissueCascade.codeOf(m))
        assertEquals(ReissueCascade.Outcome.UNREACHABLE, ReissueCascade.classify(m))
    }

    @Test
    fun aCopyThatIsGoneIsReadOffTheBodyNotTheStatusAlone() {
        val m = """HTTP 404: {"detail":{"code":"identity_not_found"}}"""
        assertEquals(ReissueCascade.Outcome.GONE, ReissueCascade.classify(m))
    }

    @Test
    fun theOldKeyLivesUntilEveryIslandIsSettled() {
        assertTrue(ReissueCascade.settled(listOf(ReissueCascade.Outcome.DONE, ReissueCascade.Outcome.GONE)))
        assertTrue("nothing to settle", ReissueCascade.settled(emptyList()))
        assertFalse(ReissueCascade.settled(listOf(ReissueCascade.Outcome.DONE, ReissueCascade.Outcome.UNREACHABLE)))
        // ⚠ The one that reads as "finished" but is not: a copy holding a key
        // we do not recognise is a copy the old phrase may still open.
        assertFalse(ReissueCascade.settled(listOf(ReissueCascade.Outcome.DONE, ReissueCascade.Outcome.DIFFERENT_KEY)))
    }
}
