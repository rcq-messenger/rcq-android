package app.rcq.android.net

import app.rcq.android.net.PeerLiveness.Liveness
import app.rcq.android.net.PeerLiveness.Mark
import app.rcq.android.net.PeerLiveness.Reply
import app.rcq.android.net.PeerLiveness.Step
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Report 1067: is a contact on another island still there? The same cases the
 * web pins (web-chat cli/test/peer-liveness.mjs), so the two clients mark the
 * same people deleted on the same answers.
 *
 *  * "Deleted" only on the island's own words, in JSON: a bare 404 is what a
 *    decoy site, a parked domain or an old self-host answers too.
 *  * A person who moved to another number, or lives on at another home their
 *    signed record names, is not deleted.
 *  * A failed read of our own mirror is unknown, never "no record".
 */
class PeerLivenessTest {

    private fun key(fill: Int) = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { fill.toByte() })

    private val ik = key(1)
    private val sk = key(2)
    private val host = "is2.example"
    private val uin = 222659798

    private fun obj(vararg kv: Pair<String, Any>) = JsonObject().apply {
        for ((k, v) in kv) when (v) {
            is String -> addProperty(k, v)
            is Number -> addProperty(k, v)
            else -> error("unsupported")
        }
    }
    private fun card(i: String, s: String) = Reply(200, obj("uin" to uin, "identity_key" to i, "signing_key" to s))
    private fun detail(status: Int, d: String) = Reply(status, obj("detail" to d))

    /** A "verified" record is any body carrying a `homes` list here; the real
     *  signature check is RcqFederation's and has its own tests. */
    private val homesOf: (JsonObject) -> List<Pair<String, Int>>? = { doc ->
        doc.getAsJsonArray("homes")?.map { val h = it.asJsonObject; h.get("host").asString to h.get("uin").asInt }
    }
    private fun record(vararg homes: Pair<String, Int>) = Reply(200, JsonObject().apply {
        add("homes", com.google.gson.JsonArray().apply {
            homes.forEach { (h, u) -> add(JsonObject().apply { addProperty("host", h); addProperty("uin", u) }) }
        })
    })

    @Test fun a_card_under_the_pinned_keys_is_alive_in_any_base64_spelling() {
        assertEquals(Step.ALIVE, PeerLiveness.cardStep(card(ik, sk), ik, sk))
        val odd = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { if (it % 2 == 1) 0xfb.toByte() else 0xff.toByte() })
        val urlSafe = odd.replace('+', '-').replace('/', '_').trimEnd('=')
        assertEquals(Step.ALIVE, PeerLiveness.cardStep(card(urlSafe, sk), odd, sk))
    }

    @Test fun a_card_under_another_key_is_never_alive() {
        assertEquals(Step.KEY_CHANGED, PeerLiveness.cardStep(card(key(9), sk), ik, sk))
        assertEquals(Step.KEY_CHANGED, PeerLiveness.cardStep(card(ik, key(9)), ik, sk))
    }

    @Test fun a_row_pinned_without_a_signing_key_is_anchored_by_its_identity_key() {
        assertEquals(Step.ALIVE, PeerLiveness.cardStep(card(ik, key(7)), ik, ""))
        assertEquals(Step.KEY_CHANGED, PeerLiveness.cardStep(card(key(7), key(7)), ik, ""))
    }

    @Test fun step_one_is_absent_only_on_no_such_user() {
        assertEquals(Step.ABSENT, PeerLiveness.cardStep(detail(404, "no such user"), ik, sk))
        assertEquals(Step.UNKNOWN, PeerLiveness.cardStep(null, ik, sk))
        assertEquals(Step.UNKNOWN, PeerLiveness.cardStep(detail(403, "island_closed"), ik, sk))
        assertEquals(Step.UNKNOWN, PeerLiveness.cardStep(detail(404, "Not Found"), ik, sk))
        assertEquals(Step.UNKNOWN, PeerLiveness.cardStep(Reply(404, null), ik, sk))
        assertEquals(Step.UNKNOWN, PeerLiveness.cardStep(Reply(200, null), ik, sk))
        assertEquals(Step.UNKNOWN, PeerLiveness.cardStep(Reply(200, obj("identity_key" to ik)), ik, sk))
        assertEquals(Step.UNKNOWN, PeerLiveness.cardStep(detail(429, "no such user"), ik, sk))
        assertEquals(Step.UNKNOWN, PeerLiveness.cardStep(detail(500, "internal_error"), ik, sk))
    }

    @Test fun step_two_another_number_is_a_move_not_a_death() {
        assertEquals(Step.MOVED, PeerLiveness.ownerStep(Reply(200, obj("uin" to 12345)), uin))
        assertEquals(Step.ABSENT, PeerLiveness.ownerStep(detail(404, "no account for key"), uin))
        assertEquals(Step.UNKNOWN, PeerLiveness.ownerStep(Reply(200, obj("uin" to uin)), uin))
        assertEquals(Step.UNKNOWN, PeerLiveness.ownerStep(Reply(200, obj("uin" to "12345")), uin))
        assertEquals(Step.UNKNOWN, PeerLiveness.ownerStep(Reply(200, null), uin))
        assertEquals(Step.UNKNOWN, PeerLiveness.ownerStep(detail(404, "Not Found"), uin))
        assertEquals(Step.UNKNOWN, PeerLiveness.ownerStep(detail(404, "no such user"), uin))
        assertEquals(Step.UNKNOWN, PeerLiveness.ownerStep(null, uin))
    }

    @Test fun step_three_a_record_naming_another_home_means_they_live_on() {
        assertEquals(Step.MOVED, PeerLiveness.recordStep(record(host to uin, "api.example" to uin), sk, host, uin, homesOf))
        assertEquals(Step.MOVED, PeerLiveness.recordStep(record(host to 777), sk, host, uin, homesOf))
    }

    @Test fun step_three_is_absent_only_on_no_record_or_a_record_naming_only_this_address() {
        assertEquals(Step.ABSENT, PeerLiveness.recordStep(detail(404, "no record"), sk, host, uin, homesOf))
        assertEquals(Step.ABSENT, PeerLiveness.recordStep(record(host to uin), sk, host, uin, homesOf))
        assertEquals(Step.ABSENT, PeerLiveness.recordStep(record("IS2.example.:443" to uin), sk, host, uin, homesOf))
    }

    @Test fun step_three_a_failed_or_unverified_read_is_unknown_never_deleted() {
        assertEquals(Step.UNKNOWN, PeerLiveness.recordStep(null, sk, host, uin, homesOf))
        assertEquals(Step.UNKNOWN, PeerLiveness.recordStep(detail(429, "rate_limited"), sk, host, uin, homesOf))
        assertEquals(Step.UNKNOWN, PeerLiveness.recordStep(Reply(503, null), sk, host, uin, homesOf))
        assertEquals(Step.UNKNOWN, PeerLiveness.recordStep(detail(502, "no record"), sk, host, uin, homesOf))
        assertEquals(Step.UNKNOWN, PeerLiveness.recordStep(detail(404, "Not Found"), sk, host, uin, homesOf))
        assertEquals(Step.UNKNOWN, PeerLiveness.recordStep(Reply(200, null), sk, host, uin, homesOf))
        assertEquals(Step.UNKNOWN, PeerLiveness.recordStep(detail(404, "no record"), "", host, uin, homesOf))
        // Not signed by the pinned key: the verifier says null.
        assertEquals(Step.UNKNOWN, PeerLiveness.recordStep(record("elsewhere.example" to uin), sk, host, uin) { null })
    }

    @Test fun gone_takes_all_three_answers_and_each_step_runs_only_after_the_one_before() {
        val asked = ArrayList<String>()
        fun run(c: Reply?, o: Reply?, r: Reply?) = PeerLiveness.classify(
            host, uin, ik, sk,
            card = { asked += "card"; c },
            owner = { asked += "owner"; o },
            record = { asked += "record"; r },
            verifiedHomes = homesOf,
        )
        assertEquals(Liveness.GONE, run(detail(404, "no such user"), detail(404, "no account for key"), detail(404, "no record")))
        assertEquals(listOf("card", "owner", "record"), asked)

        asked.clear()
        assertEquals(Liveness.ALIVE, run(card(ik, sk), null, null))
        assertEquals(listOf("card"), asked)

        // A card under another key is not ours to judge here, and asks nothing more.
        asked.clear()
        assertEquals(Liveness.UNKNOWN, run(card(key(5), key(6)), null, null))
        assertEquals(listOf("card"), asked)

        // Refused: island_closed says nothing about the person.
        asked.clear()
        assertEquals(Liveness.UNKNOWN, run(detail(403, "island_closed"), null, null))
        assertEquals(listOf("card"), asked)

        asked.clear()
        assertEquals(Liveness.MOVED, run(detail(404, "no such user"), Reply(200, obj("uin" to 12345)), null))
        assertEquals(listOf("card", "owner"), asked)

        assertEquals(Liveness.MOVED, run(detail(404, "no such user"), detail(404, "no account for key"), record("api.example" to 5)))
        assertEquals(Liveness.UNKNOWN, run(detail(404, "no such user"), detail(404, "no account for key"), Reply(503, null)))
        assertEquals(Liveness.UNKNOWN, run(detail(404, "no such user"), null, null))
    }

    @Test fun nothing_is_asked_without_both_pinned_keys() {
        var asked = false
        val v = PeerLiveness.classify(host, uin, ik, "", { asked = true; null }, { null }, { null }, homesOf)
        assertEquals(Liveness.UNKNOWN, v)
        assertFalse(asked)
    }

    @Test fun the_card_alone_can_only_say_alive() {
        assertEquals(Liveness.ALIVE, PeerLiveness.cardOnly(card(ik, sk), ik, sk))
        assertEquals(Liveness.UNKNOWN, PeerLiveness.cardOnly(card(key(5), sk), ik, sk))
        assertEquals(Liveness.UNKNOWN, PeerLiveness.cardOnly(detail(404, "no such user"), ik, sk))
    }

    @Test fun a_mark_holds_for_the_same_key_and_not_for_a_contact_added_again_since() {
        val m = Mark(sk = sk, gone = 1_000)
        assertTrue(PeerLiveness.holds(m, sk, 500))
        assertTrue(PeerLiveness.holds(m, sk, 1_000))
        assertFalse(PeerLiveness.holds(m, sk, 1_001))
        // A reissued number pinned again under its new key is somebody else.
        assertFalse(PeerLiveness.holds(m, key(9), 500))
        assertFalse(PeerLiveness.holds(m, null, 500))
    }

    @Test fun a_new_stamp_on_a_contact_added_again_starts_from_a_clean_mark() {
        val old = Mark(sk = sk, gone = 1_000, probedAt = 900, failProbeAt = 950)
        // Still about this row: kept as it is.
        assertEquals(old, PeerLiveness.base(old, sk, 500))
        // Added again after the mark: the old "gone" goes, the probe stamps
        // stay, so a GONE verdict now stamps a time that holds.
        val again = PeerLiveness.base(old, sk, 2_000)
        assertEquals(Mark(sk = sk, probedAt = 900, failProbeAt = 950), again)
        val marked = again.copy(gone = again.gone ?: 3_000)
        assertTrue(PeerLiveness.holds(marked, sk, 2_000))
        // Another key, or nothing yet: a fresh record.
        assertEquals(Mark(sk = key(9)), PeerLiveness.base(old, key(9), 500))
        assertEquals(Mark(sk = sk), PeerLiveness.base(null, sk, 500))
    }

    @Test fun gone_keys_are_the_marked_addresses_that_still_hold() {
        val marks = mapOf(
            "$uin@$host" to Mark(sk = sk, gone = 1_000),
            "5@a.example" to Mark(sk = sk, gone = 1_000),      // row removed since
            "6@b.example" to Mark(sk = sk, gone = 1_000),      // re-pinned under another key
            "7@c.example" to Mark(sk = sk, probedAt = 900),   // asked, not gone
            "42" to Mark(sk = "", gone = 1_000),               // our own island
        )
        val rows = mapOf("$uin@$host" to (sk to 10L), "6@b.example" to (key(9) to 10L), "7@c.example" to (sk to 10L))
        assertEquals(setOf("$uin@$host", "42"), PeerLiveness.goneKeys(marks) { rows[it] })
    }

    @Test fun a_probe_is_due_once_a_day_and_a_clock_that_went_back_does_not_stop_it() {
        val day = PeerLiveness.OPEN_EVERY_MS
        assertTrue(PeerLiveness.probeDue(null, 10_000, day))
        assertFalse(PeerLiveness.probeDue(10_000, 10_000 + day - 1, day))
        assertTrue(PeerLiveness.probeDue(10_000, 10_000 + day, day))
        assertTrue(PeerLiveness.probeDue(10_000, 9_000, day))
    }

    @Test fun only_mail_deposited_after_the_mark_lifts_it() {
        val mark = 1_759_000_000_000
        assertTrue(PeerLiveness.depositAfterMark(mark + 1, mark))
        assertTrue(PeerLiveness.depositAfterMark(mark, mark))
        assertFalse(PeerLiveness.depositAfterMark(mark - 1, mark))
        // No stamp: a live delivery.
        assertTrue(PeerLiveness.depositAfterMark(null, mark))
    }

    @Test fun marks_survive_a_round_trip_through_disk() {
        val marks = mapOf(
            "$uin@$host" to Mark(sk = sk, gone = 1, probedAt = 2, failProbeAt = 3),
            "42" to Mark(sk = "", gone = 4),
        )
        assertEquals(marks, PeerLiveness.decode(PeerLiveness.encode(marks)))
        assertEquals(emptyMap<String, Mark>(), PeerLiveness.decode(null))
        assertEquals(emptyMap<String, Mark>(), PeerLiveness.decode("<html>"))
    }

    @Test fun island_addresses_compare_by_one_spelling() {
        assertTrue(PeerLiveness.sameIslandHost("IS2.Example:443", "is2.example"))
        assertTrue(PeerLiveness.sameIslandHost("is2.example.", "is2.example"))
        assertFalse(PeerLiveness.sameIslandHost("is2.example:8443", "is2.example"))
    }

    @Test fun keys_compare_by_bytes_and_empty_is_never_a_match() {
        assertTrue(PeerLiveness.sameKey(sk, sk.trimEnd('=')))
        assertFalse(PeerLiveness.sameKey(sk, ik))
        assertFalse(PeerLiveness.sameKey("", ""))
        assertFalse(PeerLiveness.sameKey(null, sk))
    }
}
