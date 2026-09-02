package app.rcq.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule of island-fingerprint-design.md §1, the address syntax of §3 and
 * the fingerprint forms of §2, checked on the JVM over an in-memory map.
 *
 * ⚠ The typed branch comes before `caValid`, and the `ca` record is written
 * on the platform's SUCCESS: both are what the review of the first text
 * added (§12), and both are the kind of thing a refactor loses without
 * noticing, so each has a test of its own.
 */
class IslandTrustTest {

    private val fpA = "79bca4443228e8de6e4e8271642cfdc8ae6db9d7b46af1101fa97103c7ad21d8"
    private val fpB = "0000000000000000000000000000000000000000000000000000000000000001"

    private fun decide(
        records: MutableMap<String, IslandTrust.Record>,
        fp: String,
        caValid: Boolean,
        caOnly: Boolean = false,
        host: String = "island.example",
        port: Int = 8443,
    ) = IslandTrust.decide(host, port, fp, caValid, caOnly, records, now = 1000L)

    // ── §2 fingerprint ───────────────────────────────────────────────────

    @Test fun parsesOpensslColonFormAnyCase() {
        val colon = "79:BC:A4:44:32:28:E8:DE:6E:4E:82:71:64:2C:FD:C8:AE:6D:B9:D7:B4:6A:F1:10:1F:A9:71:03:C7:AD:21:D8"
        assertEquals(fpA, IslandTrust.parseFingerprint(colon))
        assertEquals(fpA, IslandTrust.parseFingerprint(fpA.uppercase()))
        assertEquals(fpA, IslandTrust.parseFingerprint("  ${fpA.chunked(4).joinToString(" ")} \n"))
    }

    @Test fun rejectsAnythingThatIsNot64Hex() {
        assertNull(IslandTrust.parseFingerprint(""))
        assertNull(IslandTrust.parseFingerprint("zz"))
        assertNull(IslandTrust.parseFingerprint(fpA.dropLast(1)))
        assertNull(IslandTrust.parseFingerprint(fpA + "0"))
        assertNull(IslandTrust.parseFingerprint("g" + fpA.drop(1)))
        // A group invite's key pasted into the wrong field.
        assertNull(IslandTrust.parseFingerprint("k=abcdef"))
    }

    @Test fun displayIsFourGroupsToALine() {
        val d = IslandTrust.displayFingerprint(fpA)
        val lines = d.split("\n")
        assertEquals(4, lines.size)
        assertEquals("79bc a444 3228 e8de", lines[0])
        assertEquals("c7ad 21d8", lines[3].takeLast(9))
        assertEquals(fpA, d.replace(" ", "").replace("\n", ""))
    }

    // ── §3 address ───────────────────────────────────────────────────────

    @Test fun splitsHostPortAndFragment() {
        val a = IslandTrust.splitAddress("island.example:8443#$fpA")!!
        assertEquals("island.example", a.host)
        assertEquals(8443, a.port)
        assertEquals(fpA, a.fp)
        assertEquals("island.example:8443", a.hostPort)
        assertEquals("island.example:8443", a.key)
    }

    @Test fun bareHostKeysOnTheDefaultPort() {
        val a = IslandTrust.splitAddress("203.0.113.5#$fpA")!!
        assertEquals("203.0.113.5", a.host)
        assertNull(a.port)
        assertEquals("203.0.113.5", a.hostPort)
        assertEquals("203.0.113.5:443", a.key)
    }

    @Test fun pastedUrlKeepsWorkingAndKeepsItsFragment() {
        val a = IslandTrust.splitAddress("https://203.0.113.5/#$fpA")!!
        assertEquals("203.0.113.5", a.host)
        assertEquals(fpA, a.fp)
        val b = IslandTrust.splitAddress("HTTPS://Island.Example:8443/some/path?q=1#${fpA.uppercase()}")!!
        assertEquals("island.example", b.host)
        assertEquals(8443, b.port)
        assertEquals(fpA, b.fp)
    }

    @Test fun opensslFormInTheFragment() {
        val colon = "79:BC:A4:44:32:28:E8:DE:6E:4E:82:71:64:2C:FD:C8:AE:6D:B9:D7:B4:6A:F1:10:1F:A9:71:03:C7:AD:21:D8"
        val a = IslandTrust.splitAddress("island.example:8443#$colon")!!
        assertEquals(fpA, a.fp)
    }

    /** ⚠ The store key has to be the name the socket dials. OkHttp turns
     *  `остров.рф` into `xn--…` before the trust manager ever sees it, so a
     *  key written in Unicode is a key no handshake looks up: the typed pin
     *  was bypassed and the connection took a first-use pin instead. */
    @Test fun anInternationalisedHostIsKeyedInPunycode() {
        val a = IslandTrust.splitAddress("https://остров.рф:8443/#$fpA")!!
        assertEquals("xn--b1axaheg.xn--p1ai", a.host)
        assertEquals(8443, a.port)
        assertEquals("xn--b1axaheg.xn--p1ai:8443", a.key)
        assertEquals(fpA, a.fp)
        // Both ways in agree: what the form pins and what the handshake asks
        // for are the same string.
        assertEquals(a.key, IslandTrust.key("остров.рф", 8443))
        assertEquals(a.key, IslandTrust.key("xn--b1axaheg.xn--p1ai", 8443))
        assertEquals("xn--b1axaheg.xn--p1ai:443", IslandTrust.keyOf("ОСТРОВ.РФ"))
        // An ASCII host is untouched by the extra step.
        assertEquals("island.example:8443", IslandTrust.splitAddress("island.example:8443")!!.key)
    }

    @Test fun ipv6KeepsItsBrackets() {
        val a = IslandTrust.splitAddress("[::1]:8443#$fpA")!!
        assertEquals("[::1]", a.host)
        assertEquals(8443, a.port)
        assertEquals("[::1]:8443", a.key)
        val b = IslandTrust.splitAddress("[2001:db8::5]")!!
        assertEquals("[2001:db8::5]", b.host)
        assertEquals("[2001:db8::5]:443", b.key)
        // OkHttp hands the verifier the host without brackets; the key puts
        // them back so both sides of the store agree.
        assertEquals("[::1]:8443", IslandTrust.key("::1", 8443))
        assertEquals("::1" to 8443, IslandTrust.hostAndPort("[::1]:8443"))
    }

    @Test fun aFragmentThatIsNotAFingerprintIsKeptSoTheFormCanRefuseIt() {
        val a = IslandTrust.splitAddress("island.example#zz")!!
        assertEquals("zz", a.fragment)
        assertNull(a.fp)
        val b = IslandTrust.splitAddress("island.example#")!!
        assertEquals("", b.fragment)
        assertNull(b.fp)
        assertNull(IslandTrust.splitAddress("island.example")!!.fragment)
    }

    @Test fun nothingOrNonsenseIsNull() {
        assertNull(IslandTrust.splitAddress(""))
        assertNull(IslandTrust.splitAddress("   "))
        assertNull(IslandTrust.splitAddress("https://"))
        assertNull(IslandTrust.splitAddress("host:notaport"))
        assertNull(IslandTrust.splitAddress("host:70000"))
        assertNull(IslandTrust.splitAddress("::1"))
    }

    @Test fun caOnlyHosts() {
        assertTrue(IslandTrust.isCaOnly("api.rcq.app", front = "cdn.rcq.app"))
        assertTrue(IslandTrust.isCaOnly("API.RCQ.APP", front = "cdn.rcq.app"))
        assertTrue(IslandTrust.isCaOnly("cdn.rcq.app", front = "cdn.rcq.app"))
        assertTrue(IslandTrust.isCaOnly("front.example", front = "front.example"))
        assertTrue(IslandTrust.isCaOnly("rcq.app", front = null))
        assertTrue(IslandTrust.isCaOnly("push.rcq.app", front = null))
        assertFalse(IslandTrust.isCaOnly("is2.example.org", front = "cdn.rcq.app"))
        assertFalse(IslandTrust.isCaOnly("rcq.app.example", front = null))
    }

    // ── §1 decide ────────────────────────────────────────────────────────

    @Test fun caOnlyHostAcceptsOnlyTheAuthority() {
        val r = HashMap<String, IslandTrust.Record>()
        assertTrue(decide(r, fpA, caValid = true, caOnly = true) is IslandTrust.Decision.Accept)
        val d = decide(r, fpA, caValid = false, caOnly = true)
        assertTrue(d is IslandTrust.Decision.Refuse && d.reason == IslandTrust.Reason.CA_ONLY)
        // Never pinned, typed or not.
        assertTrue(r.isEmpty())
    }

    @Test fun caValidWritesTheCaRecord() {
        val r = HashMap<String, IslandTrust.Record>()
        assertTrue(decide(r, fpA, caValid = true) is IslandTrust.Decision.Accept)
        val rec = r["island.example:8443"]!!
        assertEquals(IslandTrust.Mode.CA, rec.mode)
        assertNull(rec.fp)
        assertEquals(1000L, rec.since)
    }

    @Test fun caValidOverwritesATofuOrAcceptedPin() {
        for (src in listOf(IslandTrust.Source.TOFU, IslandTrust.Source.ACCEPTED)) {
            val r = hashMapOf("island.example:8443" to IslandTrust.Record(IslandTrust.Mode.PINNED, fpA, src, 5L))
            assertTrue(decide(r, fpB, caValid = true) is IslandTrust.Decision.Accept)
            assertEquals(IslandTrust.Mode.CA, r["island.example:8443"]!!.mode)
        }
    }

    @Test fun firstUsePinsAndSaysSo() {
        val r = HashMap<String, IslandTrust.Record>()
        val d = decide(r, fpA, caValid = false)
        assertTrue(d is IslandTrust.Decision.AcceptFirstUse && d.fp == fpA)
        val rec = r["island.example:8443"]!!
        assertEquals(IslandTrust.Mode.PINNED, rec.mode)
        assertEquals(fpA, rec.fp)
        assertEquals(IslandTrust.Source.TOFU, rec.source)
        assertFalse(rec.noticed)
        // The second time is plain acceptance, no notice.
        assertTrue(decide(r, fpA, caValid = false) is IslandTrust.Decision.Accept)
    }

    @Test fun aKnownIslandCannotBeDowngradedSilently() {
        val r = hashMapOf("island.example:8443" to IslandTrust.Record(IslandTrust.Mode.CA, since = 5L))
        val d = decide(r, fpA, caValid = false)
        assertTrue(d is IslandTrust.Decision.Refuse)
        d as IslandTrust.Decision.Refuse
        assertEquals(IslandTrust.Reason.CHANGED, d.reason)
        assertNull(d.old)   // "a certificate authority"
        assertEquals(fpA, d.new)
        assertFalse(d.ca)
        assertFalse(d.typed)
        // Nothing was written over the ca record.
        assertEquals(IslandTrust.Mode.CA, r["island.example:8443"]!!.mode)
    }

    @Test fun aPinnedIslandRefusesAnotherLeaf() {
        val r = hashMapOf("island.example:8443" to IslandTrust.Record(IslandTrust.Mode.PINNED, fpA, IslandTrust.Source.TOFU, 5L))
        assertTrue(decide(r, fpA, caValid = false) is IslandTrust.Decision.Accept)
        val d = decide(r, fpB, caValid = false)
        assertTrue(d is IslandTrust.Decision.Refuse)
        d as IslandTrust.Decision.Refuse
        assertEquals(fpA, d.old)
        assertEquals(fpB, d.new)
        assertFalse(d.typed)
        assertEquals(fpA, r["island.example:8443"]!!.fp)
    }

    @Test fun typedPinWinsOverTheAuthority() {
        val r = hashMapOf("island.example:8443" to IslandTrust.Record(IslandTrust.Mode.PINNED, fpA, IslandTrust.Source.TYPED, 5L))
        // The typed leaf, with or without a CA behind it: accepted, and the
        // record stays typed (no ca write).
        assertTrue(decide(r, fpA, caValid = false) is IslandTrust.Decision.Accept)
        assertTrue(decide(r, fpA, caValid = true) is IslandTrust.Decision.Accept)
        assertEquals(IslandTrust.Source.TYPED, r["island.example:8443"]!!.source)
        // A CA-valid chain that hashes to something else is a CHANGE: the
        // banner carries ca=true so accepting it records ca, and typed=true
        // so it says the person entered the value themselves.
        val d = decide(r, fpB, caValid = true)
        assertTrue(d is IslandTrust.Decision.Refuse)
        d as IslandTrust.Decision.Refuse
        assertEquals(IslandTrust.Reason.CHANGED, d.reason)
        assertEquals(fpA, d.old)
        assertEquals(fpB, d.new)
        assertTrue(d.ca)
        assertTrue(d.typed)
        assertEquals(fpA, r["island.example:8443"]!!.fp)
        // And a private certificate that is not the typed one: same refusal, ca=false.
        val e = decide(r, fpB, caValid = false) as IslandTrust.Decision.Refuse
        assertFalse(e.ca)
        assertTrue(e.typed)
    }

    @Test fun keysAreLowercaseAndPerPort() {
        val r = HashMap<String, IslandTrust.Record>()
        decide(r, fpA, caValid = false, host = "Island.Example", port = 8443)
        assertTrue(r.containsKey("island.example:8443"))
        // Another port is another island.
        val d = decide(r, fpB, caValid = false, host = "island.example", port = 443)
        assertTrue(d is IslandTrust.Decision.AcceptFirstUse)
        assertEquals(2, r.size)
    }

    @Test fun sha256OfDerIsTheOpensslFingerprint() {
        // openssl x509 -fingerprint -sha256 over the DER is plain SHA-256 of
        // the bytes; check the encoding of the digest against a known vector.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            IslandTrust.sha256Hex(ByteArray(0)),
        )
    }
}
