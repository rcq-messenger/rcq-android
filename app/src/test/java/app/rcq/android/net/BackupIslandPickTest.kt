package app.rcq.android.net

import app.rcq.android.net.BackupIslandPick.Info
import app.rcq.android.net.BackupIslandPick.Pass
import app.rcq.android.net.BackupIslandPick.Probe
import app.rcq.android.net.BackupIslandPick.Sentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Report #988: the backup toggle picked the flagship, which answers /health but
 * sells entry, and printed the 403 body. The info bodies below are the shape
 * both live islands answered on 15.09. The rule is the one shared with web and
 * iOS, see [BackupIslandPick].
 */
class BackupIslandPickTest {

    private fun info(caps: String?) =
        if (caps == null) "{\"name\":\"x\"}" else "{\"name\":\"x\",\"capabilities\":$caps}"

    private val flagship = info(
        "{\"registration_policy\":\"paid\",\"closed_island\":false," +
            "\"entry_price_cents\":1500,\"entry_url\":\"https://rcq.app/residency\"}",
    )
    private val is2 = info("{\"registration_policy\":\"open\",\"closed_island\":false,\"entry_price_cents\":0,\"entry_url\":\"\"}")

    private fun door(code: String, status: Int = 403) = "HTTP $status: {\"detail\":{\"code\":\"$code\"}}"

    private val openInfo: Info = Info.Read(is2)
    private val shutInfo: Info = Info.Read(flagship)
    private val unreadable: Info = Info.Unreadable

    /** Records every network action a pass takes, per host, in order. */
    private class Log {
        val calls = ArrayList<String>()
        fun health(host: String, ok: Boolean = true): Boolean {
            calls.add("health $host")
            return ok
        }
        fun info(host: String, info: Info): Info {
            calls.add("info $host")
            return info
        }
        fun register(host: String, fail: String? = null): String {
            calls.add("register $host")
            if (fail != null) throw IOException(fail)
            return "registered@$host"
        }
        fun recover(host: String, has: Boolean): String? {
            calls.add("recover $host")
            return if (has) "adopted@$host" else null
        }
    }

    // ---- R2: the door ----

    @Test
    fun openOrMissingPolicyIsOpen() {
        assertEquals(Probe.OPEN, BackupIslandPick.doorOf(is2))
        assertEquals(Probe.OPEN, BackupIslandPick.doorOf(info(null)))
        assertEquals(Probe.OPEN, BackupIslandPick.doorOf(info("{}")))
        assertEquals(Probe.OPEN, BackupIslandPick.doorOf(info("{\"registration_policy\":\"open\"}")))
        assertEquals(Probe.OPEN, BackupIslandPick.doorOf(info("{\"closed_island\":false}")))
    }

    @Test
    fun aJsonNullIsMalformedSoShut() {
        // D1: only a field that is not there at all is missing.
        assertEquals(Probe.SHUT, BackupIslandPick.doorOf(info("null")))
        assertEquals(Probe.SHUT, BackupIslandPick.doorOf(info("{\"registration_policy\":null}")))
        assertEquals(Probe.SHUT, BackupIslandPick.doorOf(info("{\"closed_island\":null}")))
        assertEquals(Probe.SHUT, BackupIslandPick.doorOf(info("{\"registration_policy\":\"open\",\"closed_island\":null}")))
        assertEquals(Probe.SHUT, BackupIslandPick.doorOf(info("{\"registration_policy\":null,\"closed_island\":false}")))
        assertEquals(Probe.SHUT, BackupIslandPick.doorOf(info("{\"registration_policy\":null,\"closed_island\":null}")))
    }

    @Test
    fun paidIsShut() {
        assertEquals(Probe.SHUT, BackupIslandPick.doorOf(flagship))
        assertEquals(Probe.SHUT, BackupIslandPick.doorOf(info("{\"registration_policy\":\"paid\"}")))
    }

    @Test
    fun inviteAndAnyOtherPolicyIsShut() {
        for (p in listOf("invite", "something_new", "", "OPEN", " open ")) {
            assertEquals(p, Probe.SHUT, BackupIslandPick.doorOf(info("{\"registration_policy\":\"$p\"}")))
        }
    }

    @Test
    fun closedIslandIsShut() {
        assertEquals(Probe.SHUT, BackupIslandPick.doorOf(info("{\"closed_island\":true}")))
        assertEquals(Probe.SHUT, BackupIslandPick.doorOf(info("{\"registration_policy\":\"open\",\"closed_island\":true}")))
    }

    @Test
    fun aMalformedFieldIsShut() {
        val malformed = listOf(
            "{\"registration_policy\":1}",
            "{\"registration_policy\":true}",
            "{\"registration_policy\":[\"open\"]}",
            "{\"registration_policy\":{\"v\":\"open\"}}",
            "{\"closed_island\":\"false\"}",
            "{\"closed_island\":0}",
            "{\"closed_island\":[false]}",
            "{\"registration_policy\":\"open\",\"closed_island\":\"no\"}",
            "\"open\"",
            "[]",
            "false",
            "0",
        )
        for (caps in malformed) assertEquals(caps, Probe.SHUT, BackupIslandPick.doorOf(info(caps)))
    }

    @Test
    fun thePriceIsIgnored() {
        // An island may sell residency while registration stays open.
        assertEquals(Probe.OPEN, BackupIslandPick.doorOf(info("{\"registration_policy\":\"open\",\"entry_price_cents\":1500}")))
        assertEquals(Probe.OPEN, BackupIslandPick.doorOf(info("{\"entry_price_cents\":\"garbage\"}")))
        assertEquals(Probe.OPEN, BackupIslandPick.doorOf(info("{\"entry_price_cents\":null}")))
        assertEquals(Probe.SHUT, BackupIslandPick.doorOf(info("{\"registration_policy\":\"paid\",\"entry_price_cents\":0}")))
    }

    // ---- R1 / D5: silence, the body and the parser ----

    @Test
    fun unreadableOrUnparseableInfoIsSilent() {
        for (body in listOf(null, "", "  ", "<html>blocked</html>", "[]", "\"open\"", "{\"capabilities\":", "null")) {
            assertEquals("$body", Probe.SILENT, BackupIslandPick.doorOf(body))
        }
        val a = "a.example:443"
        // Any non-2xx is unreadable, and so is a 2xx whose body was rejected.
        for (status in listOf(400, 403, 404, 429, 500, 502)) {
            assertSame("$status", unreadable, BackupIslandPick.classifyInfo(a, a, status, is2))
        }
        assertSame(unreadable, BackupIslandPick.classifyInfo(a, a, 200, null))
        assertEquals(Probe.SILENT, BackupIslandPick.probe(true) { unreadable })
        assertEquals(Probe.SILENT, BackupIslandPick.probe(true) { Info.Read("<html>captive</html>") })
    }

    @Test
    fun onlyStrictJsonIsRead() {
        // Everything Gson's lenient parser would have turned into an open door.
        val lenient = listOf(
            "{'name':'x','capabilities':{'registration_policy':'open'}}",
            "{name:\"x\",capabilities:{registration_policy:open}}",
            "{\"capabilities\":{\"registration_policy\":\"open\"}} trailing",
            "{\"capabilities\":{\"registration_policy\":\"open\"}}{}",
            "{\"capabilities\":{\"registration_policy\":\"open\",}}",
            "{\"capabilities\":{\"registration_policy\":\"open\"},}",
            "{\"capabilities\"={\"registration_policy\"=\"open\"}}",
            "{\"capabilities\":{\"registration_policy\":\"open\"};\"a\":1}",
            "/* c */{\"capabilities\":{}}",
            "{\"capabilities\":{}} // c",
            "{\"a\":NaN}",
            "{\"a\":01}",
            "{\"a\":1.}",
            "{\"a\":.5}",
            "{\"a\":+1}",
            "{\"a\":TRUE}",
            "{\"a\":Null}",
            "{\"a\":\"\\'\"}",
            "{\"a\":\"\\x41\"}",
            "{\"a\":\"\\u00g1\"}",
            "{\"a\":\"tab\there\"}",
            "\uFEFF{\"capabilities\":{}}",
            "[".repeat(40) + "]".repeat(40),
        )
        for (body in lenient) {
            assertNull(body, BackupIslandPick.parseStrictObject(body))
            assertEquals(body, Probe.SILENT, BackupIslandPick.doorOf(body))
        }
        val strict = listOf(
            " \n{\"a\":[1,-0.5e+3,2E-2,true,false,null,{}],\"b\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u00E9\"}\r\n",
            "{}",
            "{\"x\":\"ok\",\"capabilities\":{\"registration_policy\":\"open\"}}",
        )
        for (body in strict) assertNotNull(body, BackupIslandPick.parseStrictObject(body))
        assertEquals(Probe.OPEN, BackupIslandPick.doorOf(strict[2]))
    }

    @Test
    fun aBodyOverTheCapIsRejectedNotCut() {
        val cap = BackupIslandPick.INFO_BODY_CAP.toInt()
        // Exactly the cap is accepted: is2's answer, padded with whitespace.
        val atCap = is2 + " ".repeat(cap - is2.length)
        assertEquals(cap, atCap.toByteArray().size)
        val read = BackupIslandPick.readInfoBody(ByteArrayInputStream(atCap.toByteArray()))
        assertEquals(atCap, read)
        assertEquals(Probe.OPEN, BackupIslandPick.doorOf(read))
        // One byte more is rejected whole, even though its first 64 KB read open.
        val over = atCap + " "
        assertNull(BackupIslandPick.readInfoBody(ByteArrayInputStream(over.toByteArray())))
        // A declared length over the cap is rejected before anything is read.
        val untouched = object : java.io.InputStream() {
            override fun read(): Int { fail("read a body declared too long"); return -1 }
        }
        assertNull(BackupIslandPick.readInfoBody(untouched, declaredLength = BackupIslandPick.INFO_BODY_CAP + 1))
        // A declared length that lies low does not get past the cap either.
        assertNull(BackupIslandPick.readInfoBody(ByteArrayInputStream(over.toByteArray()), declaredLength = 10))
        // An unknown length is fine.
        assertEquals(is2, BackupIslandPick.readInfoBody(ByteArrayInputStream(is2.toByteArray()), declaredLength = -1))
    }

    @Test
    fun aBodyThatIsNotUtf8IsRejected() {
        val bad = listOf(
            byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D),           // broken 2-byte sequence
            byteArrayOf(0x7B, 0xFF.toByte(), 0x7D),                  // never valid in UTF-8
            byteArrayOf(0x7B, 0xED.toByte(), 0xA0.toByte(), 0x80.toByte(), 0x7D), // encoded surrogate
            byteArrayOf(0x7B, 0xE2.toByte(), 0x82.toByte()),         // cut in the middle of a char
        )
        for (b in bad) {
            assertNull(b.joinToString(), BackupIslandPick.decodeUtf8Strict(b))
            assertNull(b.joinToString(), BackupIslandPick.readInfoBody(ByteArrayInputStream(b)))
        }
        val good = "{\"name\":\"Остров\"}".toByteArray(Charsets.UTF_8)
        assertEquals("{\"name\":\"Остров\"}", BackupIslandPick.readInfoBody(ByteArrayInputStream(good)))
    }

    @Test
    fun aRedirectIsSilent() {
        val a = "a.example:443"
        // A 30x is silence, even when it points back at the same host, and even
        // when it carries a body that reads as open.
        for (status in listOf(301, 302, 303, 307, 308)) {
            assertSame("$status", unreadable, BackupIslandPick.classifyInfo(a, a, status, is2))
        }
        // An answer that belongs to another host (or another port) is not A's.
        assertSame(unreadable, BackupIslandPick.classifyInfo(a, "b.example:443", 200, is2))
        assertSame(unreadable, BackupIslandPick.classifyInfo(a, "a.example:8443", 200, is2))
        assertSame(unreadable, BackupIslandPick.classifyInfo(a, "", 200, is2))
        // The same host is compared the way DNS compares it.
        assertEquals(Info.Read(is2), BackupIslandPick.classifyInfo(a, "A.Example:443", 200, is2))
        // A redirected /health is a failed /health: silent, info never asked.
        assertEquals(Probe.SILENT, BackupIslandPick.probe(false) { fail("info asked after a failed /health"); openInfo })
        assertEquals(Probe.OPEN, BackupIslandPick.probe(true) { openInfo })
    }

    @Test
    fun theProbeDeadlinesAreTheSharedNumbers() {
        // D6: an overall deadline per request, 6 s direct, 15 s over relays.
        assertEquals(6L, BackupIslandPick.probeDeadlineSeconds(relayed = false))
        assertEquals(15L, BackupIslandPick.probeDeadlineSeconds(relayed = true))
    }

    // ---- D3: one at a time, in order ----

    @Test
    fun candidatesAreProbedOneAtATimeAndInfoOnlyAfterHealth() {
        val log = Log()
        val pass = BackupIslandPick.runPass(
            listOf("down.example", "shut.example", "open.example", "never.example"),
            answers = { log.health(it, ok = it != "down.example") },
            infoOf = { log.info(it, if (it == "shut.example") shutInfo else openInfo) },
            register = { log.register(it) },
            recover = { log.recover(it, has = false) },
        )
        assertEquals(Pass.Added("open.example", "registered@open.example"), pass)
        assertEquals(
            listOf(
                "health down.example",
                "health shut.example", "info shut.example", "recover shut.example",
                "health open.example", "info open.example", "register open.example",
            ),
            log.calls,
        )
    }

    @Test
    fun aSilentHostIsNeitherRegisteredNorRecoveredOn() {
        val log = Log()
        val pass = BackupIslandPick.runPass(
            listOf("down.example", "redirect.example", "is2.rcq.app"),
            answers = { it != "down.example" },
            infoOf = { if (it == "redirect.example") unreadable else openInfo },
            register = { log.register(it) },
            recover = { log.recover(it, has = true) },
        )
        assertEquals(Pass.Added("is2.rcq.app", "registered@is2.rcq.app"), pass)
        assertEquals(listOf("register is2.rcq.app"), log.calls)
    }

    @Test
    fun candidatesKeepCatalogueOrderAndEveryExclusion() {
        val cat = listOf("a.example", "own.example", "added.example", "front.example", "b.example", "a.example")
        assertEquals(
            listOf("a.example", "b.example"),
            BackupIslandPick.candidates(cat, "own.example", setOf("added.example")) { it == "front.example" },
        )
    }

    // ---- R3: the action ----

    @Test
    fun report988NeverRegisterOnAShutIsland() {
        val cands = BackupIslandPick.candidates(listOf("api.rcq.app", "is2.rcq.app"), "is2.rcq.app", emptySet()) { false }
        assertEquals(listOf("api.rcq.app"), cands)
        // No copy there: recover only, then nothing.
        val log = Log()
        val pass = BackupIslandPick.runPass(cands, { true }, { shutInfo }, { log.register(it) }, { log.recover(it, has = false) })
        assertSame(Pass.NoneOpen, pass)
        assertEquals(listOf("recover api.rcq.app"), log.calls)
    }

    @Test
    fun aShutIslandThatHoldsACopyIsAdopted() {
        val log = Log()
        val pass = BackupIslandPick.runPass(
            listOf("api.rcq.app", "is2.rcq.app"), { true }, { if (it == "api.rcq.app") shutInfo else openInfo },
            { log.register(it) }, { log.recover(it, has = true) },
        )
        assertEquals(Pass.Added("api.rcq.app", "adopted@api.rcq.app"), pass)
        assertEquals(listOf("recover api.rcq.app"), log.calls)
    }

    @Test
    fun aShutIslandWithoutACopyMovesOnToTheNextOpenOne() {
        val log = Log()
        val pass = BackupIslandPick.runPass(
            listOf("api.rcq.app", "is2.rcq.app", "c.example"), { true }, { if (it == "api.rcq.app") shutInfo else openInfo },
            { log.register(it) }, { log.recover(it, has = false) },
        )
        assertEquals(Pass.Added("is2.rcq.app", "registered@is2.rcq.app"), pass)
        assertEquals(listOf("recover api.rcq.app", "register is2.rcq.app"), log.calls)
    }

    @Test
    fun aDoorRefusalMovesOnWithoutASecondRecover() {
        // D4: [register] is the manual add, which already recovered first on
        // this host. A refusal means no copy here and a shut door: move on.
        for (code in listOf("entry_required", "invite_required", "invite_invalid")) {
            val log = Log()
            val pass = BackupIslandPick.runPass(
                listOf("liar.example", "is2.rcq.app"), { true }, { openInfo },
                { log.register(it, fail = if (it == "liar.example") door(code) else null) },
                { log.recover(it, has = true) },
            )
            assertEquals(code, Pass.Added("is2.rcq.app", "registered@is2.rcq.app"), pass)
            assertEquals(code, listOf("register liar.example", "register is2.rcq.app"), log.calls)
        }
    }

    @Test
    fun onlyTheExactDoorCodesOfA403AreADoor() {
        assertEquals(BackupIslandPick.Door.ENTRY, BackupIslandPick.doorRefusal(door("entry_required")))
        assertEquals(BackupIslandPick.Door.INVITE, BackupIslandPick.doorRefusal(door("invite_required")))
        assertEquals(BackupIslandPick.Door.INVITE, BackupIslandPick.doorRefusal(door("invite_invalid")))
        assertEquals(
            BackupIslandPick.Door.ENTRY,
            BackupIslandPick.doorRefusal("HTTP 403: {\"detail\":{\"code\":\"entry_required\",\"entry_url\":\"https://x\"}}"),
        )
        val notADoor = listOf(
            door("key_proof_required"),
            door("entry_required", status = 404),
            door("entry_required", status = 401),
            door("entry_required_v2"),
            // D9: a bare-string detail is not a door.
            "HTTP 403: {\"detail\":\"entry_required\"}",
            // D9: no status is not a door.
            "{\"detail\":{\"code\":\"entry_required\"}}",
            "403: {\"detail\":{\"code\":\"entry_required\"}}",
            "HTTP 4030: {\"detail\":{\"code\":\"entry_required\"}}",
            "HTTP 403 {\"detail\":{\"code\":\"entry_required\"}}",
            // A code that is not a string, or a detail that is not strict JSON.
            "HTTP 403: {\"detail\":{\"code\":[\"entry_required\"]}}",
            "HTTP 403: {\"detail\":{\"code\":null}}",
            "HTTP 403: {detail:{code:entry_required}}",
            "HTTP 403: {'detail':{'code':'entry_required'}}",
            "HTTP 403: {\"detail\":{\"code\":\"entry_required\"}} and more",
            "HTTP 403: entry_required",
            "HTTP 403: {\"detail\":{\"code\":\"entry_requ",
            "HTTP 403",
            "timeout",
            "",
            null,
        )
        for (m in notADoor) assertNull("$m", BackupIslandPick.doorRefusal(m))
        // A failure that is not a door moves on just the same, no recover.
        val log = Log()
        val pass = BackupIslandPick.runPass(
            listOf("broken.example", "is2.rcq.app"), { true }, { openInfo },
            { log.register(it, fail = if (it == "broken.example") "HTTP 500: {\"detail\":\"boom\"}" else null) },
            { log.recover(it, has = true) },
        )
        assertEquals(Pass.Added("is2.rcq.app", "registered@is2.rcq.app"), pass)
        assertEquals(listOf("register broken.example", "register is2.rcq.app"), log.calls)
    }

    @Test
    fun everyHostIsTriedAtMostOnce() {
        val log = Log()
        val pass = BackupIslandPick.runPass(
            listOf("a.example", "b.example", "a.example", "b.example"), { true },
            { if (it == "a.example") openInfo else shutInfo },
            { log.register(it, fail = door("entry_required")) },
            { log.recover(it, has = false) },
        )
        assertSame(Pass.NoneOpen, pass)
        assertEquals(listOf("register a.example", "recover b.example"), log.calls)
    }

    @Test
    fun aFailingRecoverMovesOnAndCancellationIsNeverSwallowed() {
        val pass = BackupIslandPick.runPass<String>(
            listOf("a.example", "b.example"), { true }, { if (it == "a.example") shutInfo else openInfo },
            { "registered@$it" }, { throw IOException("HTTP 401: {\"detail\":\"no\"}") },
        )
        assertEquals(Pass.Added("b.example", "registered@b.example"), pass)
        val cancel = java.util.concurrent.CancellationException("left the screen")
        try {
            BackupIslandPick.runPass<String>(listOf("a.example"), { true }, { openInfo }, { throw cancel }, { "adopted" })
            fail("cancellation was swallowed")
        } catch (e: java.util.concurrent.CancellationException) {
            assertSame(cancel, e)
        }
        try {
            BackupIslandPick.runPass<String>(listOf("a.example"), { true }, { shutInfo }, { "registered" }, { throw cancel })
            fail("cancellation was swallowed")
        } catch (e: java.util.concurrent.CancellationException) {
            assertSame(cancel, e)
        }
    }

    // ---- R4 / D2: the relay pass ----

    @Test
    fun allSilentRunsTheRelayPassOnce() {
        val silent = BackupIslandPick.runPass(listOf("a.example", "b.example"), { false }, { openInfo }, { it }, { it })
        assertSame(Pass.Silent, silent)
        val unread = BackupIslandPick.runPass(listOf("a.example", "b.example"), { true }, { unreadable }, { it }, { it })
        assertSame(Pass.Silent, unread)

        // Silent, relays up, the second pass finds an island.
        var passes = 0
        var engaged = 0
        val home = BackupIslandPick.attempt(
            pass = { passes++; if (passes == 1) Pass.Silent else Pass.Added("is2.rcq.app", "home") },
            engageRelays = { engaged++; true },
        )
        assertEquals("home", home)
        assertEquals(2, passes)
        assertEquals(1, engaged)

        // Silent both times: nothing reachable.
        passes = 0
        assertEquals(BackupIslandPick.NO_ISLAND, thrown { BackupIslandPick.attempt<String>({ passes++; Pass.Silent }, { true }) })
        assertEquals(2, passes)

        // Silent and the relays would not come up: one pass only.
        passes = 0
        assertEquals(BackupIslandPick.NO_ISLAND, thrown { BackupIslandPick.attempt<String>({ passes++; Pass.Silent }, { false }) })
        assertEquals(1, passes)

        // Silent, then the relayed pass heard a shut door.
        passes = 0
        assertEquals(
            BackupIslandPick.NO_OPEN_ISLAND,
            thrown { BackupIslandPick.attempt<String>({ passes++; if (passes == 1) Pass.Silent else Pass.NoneOpen }, { true }) },
        )
    }

    @Test
    fun anUnverifiedCatalogueIsSilenceAndRunsTheRelayPass() {
        // D2: no catalogue (unreachable or failed verification) is all-SILENT.
        val none = BackupIslandPick.catalogPass<String>(
            null, "is2.rcq.app", emptySet(), { false },
            { fail("probed without a catalogue"); true }, { openInfo }, { it }, { it },
        )
        assertSame(Pass.Silent, none)
        var passes = 0
        var engaged = 0
        val home = BackupIslandPick.attempt(
            pass = {
                passes++
                BackupIslandPick.catalogPass<String>(
                    if (passes == 1) null else listOf("api.rcq.app"), "is2.rcq.app", emptySet(), { false },
                    { true }, { openInfo }, { "registered@$it" }, { null },
                )
            },
            engageRelays = { engaged++; true },
        )
        assertEquals("registered@api.rcq.app", home)
        assertEquals(1, engaged)
    }

    @Test
    fun aVerifiedCatalogueWithNobodyToAskIsNoOpenIslandWithoutRelays() {
        // D2: the catalogue verified, but every island in it is excluded.
        val empty = BackupIslandPick.catalogPass<String>(
            listOf("is2.rcq.app", "added.example", "front.example"), "is2.rcq.app", setOf("added.example"),
            { it == "front.example" },
            { fail("probed an excluded host"); true }, { openInfo }, { it }, { it },
        )
        assertSame(Pass.NoneOpen, empty)
        assertSame(Pass.NoneOpen, BackupIslandPick.catalogPass<String>(emptyList(), "x", emptySet(), { false }, { true }, { openInfo }, { it }, { it }))
        assertSame(Pass.NoneOpen, BackupIslandPick.runPass(emptyList<String>(), { true }, { openInfo }, { it }, { it }))
        val msg = thrown { BackupIslandPick.attempt<String>({ empty }, { fail("relays for an empty candidate list"); true }) }
        assertEquals(BackupIslandPick.NO_OPEN_ISLAND, msg)
        assertEquals(Sentence.NO_OPEN_ISLAND, BackupIslandPick.sentenceOf(msg))
    }

    @Test
    fun anAnswerNeverRunsTheRelayPass() {
        var engaged = false
        val shutOnly = BackupIslandPick.runPass(
            listOf("a.example", "b.example"), { it == "a.example" }, { shutInfo }, { fail("registered on shut"); it }, { null },
        )
        assertSame(Pass.NoneOpen, shutOnly)
        assertEquals(
            BackupIslandPick.NO_OPEN_ISLAND,
            thrown { BackupIslandPick.attempt<String>({ shutOnly }, { engaged = true; true }) },
        )
        assertTrue(!engaged)
    }

    // ---- R5: the words ----

    @Test
    fun answeredButNoneGivesTheNoOpenIslandSentence() {
        val refusedEverywhere = BackupIslandPick.runPass<String>(
            listOf("a.example", "b.example"), { true }, { openInfo },
            { throw IOException(door("invite_required")) }, { null },
        )
        val msg = thrown { BackupIslandPick.attempt<String>({ refusedEverywhere }, { fail("relays for an answer"); true }) }
        assertEquals(Sentence.NO_OPEN_ISLAND, BackupIslandPick.sentenceOf(msg))
        assertEquals(Sentence.NO_ISLAND, BackupIslandPick.sentenceOf(BackupIslandPick.NO_ISLAND))
    }

    @Test
    fun theScreenGetsASentenceNeverACodeOrABody() {
        assertEquals(Sentence.ENTRY, BackupIslandPick.sentenceOf(door("entry_required")))
        assertEquals(Sentence.INVITE, BackupIslandPick.sentenceOf(door("invite_required")))
        assertEquals(Sentence.INVITE, BackupIslandPick.sentenceOf(door("invite_invalid")))
        assertEquals(Sentence.SWITCH_NOT_DONE, BackupIslandPick.sentenceOf("island_said:HTTP 403"))
        assertEquals(Sentence.SWITCH_NOT_DONE, BackupIslandPick.sentenceOf("island_said:HTTP 500"))
        assertEquals(Sentence.ALREADY_ADDED, BackupIslandPick.sentenceOf("already_added"))
        assertEquals(Sentence.NO_ROUTE, BackupIslandPick.sentenceOf("no_route"))
        // Statuses, bodies, exception strings: the generic sentence alone.
        for (m in listOf(
            "HTTP 401: {\"detail\":\"bad\"}",
            door("key_proof_required"),
            "HTTP 403: {\"detail\":\"entry_required\"}",
            "HTTP 404: {\"detail\":\"Not Found\"}",
            "HTTP 500: <html>oops</html>",
            "Unable to resolve host \"x.example\": No address associated with hostname",
            "timeout",
            "not_backup",
            "",
            null,
        )) {
            assertEquals("$m", Sentence.GENERIC, BackupIslandPick.sentenceOf(m))
        }
    }

    @Test
    fun everyFailedSwitchSaysNothingChangedExceptADoor() {
        for (m in listOf(
            "unreachable",
            "no_route",
            "no_account_here",
            "island_said:HTTP 401",
            "island_said:HTTP 500",
            "empty/unparseable response",
            "primary_island",
            "not_backup",
            "no session",
            "HTTP 403: {\"detail\":\"entry_required\"}",
            door("key_proof_required"),
            IllegalStateException("boom").message,
            RuntimeException().message,
            "",
            null,
        )) {
            assertEquals("$m", Sentence.SWITCH_NOT_DONE, BackupIslandPick.promoteSentenceOf(m))
        }
        assertEquals(Sentence.ENTRY, BackupIslandPick.promoteSentenceOf(door("entry_required")))
        assertEquals(Sentence.INVITE, BackupIslandPick.promoteSentenceOf(door("invite_required")))
        assertEquals(Sentence.INVITE, BackupIslandPick.promoteSentenceOf(door("invite_invalid")))
        // The toggle and the manual add keep their own sentences.
        assertEquals(Sentence.NO_ROUTE, BackupIslandPick.sentenceOf("no_route"))
        assertEquals(Sentence.UNREACHABLE, BackupIslandPick.sentenceOf("unreachable"))
        assertEquals(Sentence.NO_ACCOUNT_HERE, BackupIslandPick.sentenceOf("no_account_here"))
    }

    @Test
    fun theLogLabelNeverCarriesAnIslandBody() {
        assertEquals("HTTP 403", BackupIslandPick.causeLabel(door("entry_required"), "IOException"))
        assertEquals("HTTP 500", BackupIslandPick.causeLabel("HTTP 500: <html>oops</html>", "IOException"))
        assertEquals("IOException", BackupIslandPick.causeLabel("{\"detail\":{\"code\":\"x\"}}", "IOException"))
        assertEquals("IOException", BackupIslandPick.causeLabel(null, "IOException"))
        assertEquals("timeout", BackupIslandPick.causeLabel("timeout", "SocketTimeoutException"))
    }

    private inline fun thrown(block: () -> Unit): String? {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        fail("nothing thrown")
        return null
    }
}
