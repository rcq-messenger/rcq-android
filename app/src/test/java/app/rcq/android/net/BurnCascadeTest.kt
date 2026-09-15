package app.rcq.android.net

import app.rcq.android.net.BurnCascade.IslandBurnResult
import app.rcq.android.net.BurnCascade.Reason
import app.rcq.android.net.BurnCascade.RecoverAnswer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * F2: the per-island burn state machine and the plan, over a fake transport.
 * No request leaves the JVM.
 */
class BurnCascadeTest {

    private val nf = RecoverAnswer.NotFound(AuthRefusal.IDENTITY_NOT_FOUND)
    private val rotated = RecoverAnswer.NotFound(AuthRefusal.IDENTITY_ROTATED)

    private fun key(tag: Int) = BurnCascade.BurnKey(ByteArray(32) { 9 }, ByteArray(32) { tag.toByte() })

    private val k1 = key(1)
    private val kOld = key(2)
    private val kNew = key(3)

    /** Scripted answers: deletes in order, recovers in order per key. An
     *  empty script answers 204 / identity_not_found. */
    private class Fake(val hang: Boolean = false) : BurnCascade.Transport {
        val deletes = ArrayDeque<Any>()
        val recovers = HashMap<Byte, ArrayDeque<Any>>()
        var deleteCalls = 0
        var recoverCalls = 0

        fun recoversFor(k: BurnCascade.BurnKey, vararg a: Any) {
            recovers.getOrPut(k.pub[0]) { ArrayDeque() }.addAll(a)
        }

        override suspend fun deleteAccount(host: String, token: String): Int {
            deleteCalls++
            if (hang) delay(60_000)
            return when (val n = deletes.removeFirstOrNull() ?: 204) {
                is Throwable -> throw n
                else -> n as Int
            }
        }

        override suspend fun recover(host: String, key: BurnCascade.BurnKey): RecoverAnswer {
            recoverCalls++
            if (hang) delay(60_000)
            return when (val n = recovers[key.pub[0]]?.removeFirstOrNull() ?: RecoverAnswer.NotFound(AuthRefusal.IDENTITY_NOT_FOUND)) {
                is Throwable -> throw n
                else -> n as RecoverAnswer
            }
        }
    }

    private fun target(tokens: List<String> = emptyList(), keys: List<BurnCascade.BurnKey> = listOf(k1), host: String = "b.example") =
        BurnCascade.BurnTarget(host, 5, tokens, keys)

    private fun one(t: BurnCascade.BurnTarget, f: BurnCascade.Transport, retry: Boolean = true) =
        runBlocking { BurnCascade.burnOne(t, f, retry) }

    @Test fun token_204_then_recover_404_is_confirmed() {
        val f = Fake().apply { deletes.add(204) }
        assertEquals(IslandBurnResult.Confirmed(1), one(target(tokens = listOf("t")), f))
    }

    @Test fun token_401_then_recover_and_delete_is_confirmed() {
        val f = Fake().apply {
            deletes.addAll(listOf(401, 204))
            recoversFor(k1, RecoverAnswer.Token("fresh"), nf)
        }
        assertEquals(IslandBurnResult.Confirmed(1), one(target(tokens = listOf("t")), f))
        assertEquals(2, f.deleteCalls)
    }

    @Test fun a_first_recover_404_is_already_gone() {
        val f = Fake().apply { recoversFor(k1, nf) }
        assertEquals(IslandBurnResult.AlreadyGone, one(target(), f))
    }

    @Test fun a_challenge_404_is_too_old_never_already_gone() {
        val f = Fake().apply { recoversFor(k1, RecoverAnswer.ChallengeMissing) }
        assertEquals(IslandBurnResult.Failed(Reason.TOO_OLD), one(target(), f))
        // A recover 404 that carries no code is an island without the route too.
        val g = Fake().apply { recoversFor(k1, RecoverAnswer.NotFound(null)) }
        assertEquals(IslandBurnResult.Failed(Reason.TOO_OLD), one(target(), g))
    }

    @Test fun two_io_exceptions_are_offline_after_exactly_two_tries() {
        val f = Fake().apply { recoversFor(k1, IOException("a"), IOException("b"), nf) }
        assertEquals(IslandBurnResult.Failed(Reason.OFFLINE), one(target(), f))
        assertEquals(2, f.recoverCalls)
        // Without retry, one.
        val g = Fake().apply { recoversFor(k1, IOException("a"), nf) }
        assertEquals(IslandBurnResult.Failed(Reason.OFFLINE), one(target(), g, retry = false))
        assertEquals(1, g.recoverCalls)
    }

    @Test fun one_5xx_is_retried() {
        val f = Fake().apply { recoversFor(k1, RecoverAnswer.Refused(502), nf) }
        assertEquals(IslandBurnResult.AlreadyGone, one(target(), f))
    }

    @Test fun a_suspended_account_is_reported() {
        val f = Fake().apply { deletes.add(403) }
        assertEquals(IslandBurnResult.Failed(Reason.SUSPENDED), one(target(tokens = listOf("t")), f))
    }

    @Test fun a_hanging_transport_returns_by_the_deadline() = runBlocking {
        val targets = (1..7).map { target(host = "h$it.example") }
        val started = System.nanoTime()
        val out = BurnCascade.run(targets, Fake(hang = true), deadlineMs = 300)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs < 1_500)
        assertEquals(7, out.size)
        // Six run at once: those time out, the seventh never started.
        assertEquals(6, out.values.count { it == IslandBurnResult.Failed(Reason.TIMEOUT) })
        assertEquals(1, out.values.count { it == IslandBurnResult.NotTried })
    }

    @Test fun five_rows_stop_at_four_with_limit() {
        val f = Fake().apply { recoversFor(k1, *Array(6) { RecoverAnswer.Token("t$it") }) }
        assertEquals(IslandBurnResult.Failed(Reason.LIMIT), one(target(), f))
        assertEquals(4, f.deleteCalls)
    }

    @Test fun the_same_host_in_both_stores_is_one_target_with_both_tokens() {
        val visited = listOf(VisitedIslandsStore.Visited("b.example", 5, "tv", 0L))
        val backups = listOf(MultihomeStore.Home(100, "B.example", 5, "tb", 0L))
        val out = BurnCascade.planTargets(BurnCascade.copiesOf(visited, backups), ownHost = "a.example", keysFor = { listOf(k1) })
        assertEquals(1, out.size)
        assertEquals("b.example", out[0].host)
        assertEquals(listOf("tv", "tb"), out[0].tokens)
    }

    @Test fun the_home_host_and_a_front_are_never_remote_targets() {
        val visited = listOf(
            VisitedIslandsStore.Visited("A.example", 1, "t1", 0L),
            VisitedIslandsStore.Visited("front.example", 2, "t2", 0L),
            VisitedIslandsStore.Visited("c.example", 3, "t3", 0L),
        )
        val out = BurnCascade.planTargets(
            BurnCascade.copiesOf(visited, emptyList()),
            ownHost = "a.example",
            skipHost = { it == "front.example" },
            keysFor = { listOf(k1) },
        )
        assertEquals(listOf("c.example"), out.map { it.host })
    }

    /** An island that holds [rows] copies under [holder] and knows no other key. */
    private class Island(var rows: Int, val holder: BurnCascade.BurnKey) : BurnCascade.Transport {
        override suspend fun deleteAccount(host: String, token: String): Int {
            if (token != "row" || rows == 0) return 401
            rows--
            return 204
        }

        override suspend fun recover(host: String, key: BurnCascade.BurnKey): RecoverAnswer =
            if (key.samePub(holder) && rows > 0) RecoverAnswer.Token("row") else RecoverAnswer.NotFound(AuthRefusal.IDENTITY_NOT_FOUND)
    }

    @Test fun a_pending_rotation_deletes_the_copy_under_the_old_key_whatever_the_order() {
        // Not done: the copy is still under the old key, which goes first.
        val notDone = BurnCascade.keyOrder(current = kNew, old = kOld, targetDone = false)
        assertEquals(listOf(kOld, kNew), notDone)
        val a = Island(1, holder = kOld)
        assertEquals(IslandBurnResult.Confirmed(1), one(target(keys = notDone), a))
        assertEquals(0, a.rows)
        // The new key answering identity_not_found first is NOT already gone.
        val b = Island(1, holder = kOld)
        assertEquals(IslandBurnResult.Confirmed(1), one(target(keys = listOf(kNew, kOld)), b))
        assertEquals(0, b.rows)
        // Done: new key first, the old one still tried.
        assertEquals(listOf(kNew, kOld), BurnCascade.keyOrder(current = kNew, old = kOld, targetDone = true))
        val c = Island(1, holder = kNew)
        assertEquals(IslandBurnResult.Confirmed(1), one(target(keys = listOf(kNew, kOld)), c))
        // No rotation, or one that kept the same key: one key.
        assertEquals(listOf(kNew), BurnCascade.keyOrder(kNew, null, null))
        assertEquals(listOf(kNew), BurnCascade.keyOrder(kNew, key(3), false))
    }

    @Test fun identity_rotated_on_the_old_key_tries_the_new_key() {
        val f = Fake().apply {
            recoversFor(kOld, rotated)
            recoversFor(kNew, RecoverAnswer.Token("t"), nf)
        }
        assertEquals(IslandBurnResult.Confirmed(1), one(target(keys = listOf(kOld, kNew)), f))
        // Old (rotated), new (token, then not found), and the old key once more
        // after that delete, which then says not found.
        assertEquals(4, f.recoverCalls)
        // Only rotated answers: the copy sits under a key we do not hold.
        val g = Fake().apply { recoversFor(k1, rotated) }
        assertEquals(IslandBurnResult.Failed(Reason.SERVER), one(target(), g))
    }

    @Test fun rotated_on_one_key_and_not_found_on_the_other_is_not_gone() {
        // The row moved from the old key to a key this device does not hold,
        // and the new key knows nothing: the copy is still there.
        val f = Fake().apply {
            recoversFor(kOld, rotated)
            recoversFor(kNew, nf)
        }
        assertEquals(IslandBurnResult.Failed(Reason.SERVER), one(target(keys = listOf(kOld, kNew)), f))
        // Either order.
        val g = Fake().apply {
            recoversFor(kNew, nf)
            recoversFor(kOld, rotated)
        }
        assertEquals(IslandBurnResult.Failed(Reason.SERVER), one(target(keys = listOf(kNew, kOld)), g))
        // A token delete does not settle it either: the old key still says
        // rotated after it, so another copy remains.
        val h = Fake().apply {
            deletes.add(204)
            recoversFor(kOld, rotated, rotated)
            recoversFor(kNew, nf)
        }
        assertEquals(IslandBurnResult.Failed(Reason.SERVER), one(target(tokens = listOf("t"), keys = listOf(kOld, kNew)), h))
        // A delete through the new key, and the old key still rotated when asked again.
        val i = Fake().apply {
            recoversFor(kOld, rotated, rotated)
            recoversFor(kNew, RecoverAnswer.Token("t"), nf)
        }
        assertEquals(IslandBurnResult.Failed(Reason.SERVER), one(target(keys = listOf(kOld, kNew)), i))
    }

    @Test fun backup_tokens_are_not_used_when_another_identity_holds_the_number() {
        val me = BurnCascade.RosterEntry(1234, sameKey = true)
        // 1234@A (being burned) and 1234@C, a different identity on this device.
        val other = BurnCascade.RosterEntry(1234, sameKey = false)
        assertFalse(BurnCascade.backupTokensUsable(1234, listOf(me, other)))
        // A same-key account with that number is burned too: its tokens are fine.
        assertTrue(BurnCascade.backupTokensUsable(1234, listOf(me, BurnCascade.RosterEntry(1234, sameKey = true))))
        // A different identity with another number does not matter.
        assertTrue(BurnCascade.backupTokensUsable(1234, listOf(me, BurnCascade.RosterEntry(77, sameKey = false))))
        // A store that could not be read cannot rule it out.
        assertFalse(BurnCascade.backupTokensUsable(1234, listOf(me, BurnCascade.RosterEntry(null, false, readable = false))))

        // Without the tokens the island is still a target, reached by keys only.
        val backups = listOf(MultihomeStore.Home(1234, "c.example", 1234, "q-token", 0L))
        val out = BurnCascade.planTargets(
            BurnCascade.copiesOf(emptyList(), backups, backupTokens = false),
            ownHost = "a.example",
            keysFor = { listOf(k1) },
        )
        assertEquals(listOf("c.example"), out.map { it.host })
        assertTrue(out[0].tokens.isEmpty())
        // So the other identity's row is never deleted: only our key is proven,
        // and the island does not know it.
        val island = Island(1, holder = kOld)
        assertEquals(IslandBurnResult.AlreadyGone, one(out[0], island))
        assertEquals(1, island.rows)
    }

    @Test fun the_wipe_pin_runs_remotes_detached_and_zeroes_the_keys() = runBlocking {
        val priv = ByteArray(32) { 5 }
        val t = BurnCascade.BurnTarget("b.example", 5, listOf("tok"), listOf(BurnCascade.BurnKey(priv, ByteArray(32) { 1 })))
        val ran = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val job = BurnCascade.runDetached(
            BurnCascade.Snapshot(emptyList(), listOf(t)),
            deadlineMs = 2_000,
            delete = { },
            remote = { ran.set(true) },
            onFinished = { finished.set(true) },
        )!!
        withTimeout(3_000) { job.join() }
        assertTrue(ran.get())
        assertTrue(finished.get())
        assertTrue(priv.all { it == 0.toByte() })
        assertFalse(t.toString().contains("tok"))
    }
}
