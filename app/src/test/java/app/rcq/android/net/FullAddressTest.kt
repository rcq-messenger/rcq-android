package app.rcq.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What lands in the clipboard when a person copies a number (#1025).
 *
 * The rule is one line long and the reason it matters is not: a bare "134"
 * names a different person on every island, so a number that leaves the app
 * has to carry the island it belongs to. The display form hides
 * `@api.rcq.app`; the copied form never does.
 */
class FullAddressTest {

    @Test
    fun `the flagship suffix is spelled out, unlike the display form`() {
        assertEquals("134@api.rcq.app", RcqFederation.fullAddress(134, "api.rcq.app"))
        // ...and the display form still hides it, or this test is about nothing.
        assertEquals("134", RcqFederation.formatAddress(RcqFederation.Address(134, "api.rcq.app")))
    }

    @Test
    fun `a foreign island is carried as it is`() {
        assertEquals(
            "911@146-190-232-70.sslip.io",
            RcqFederation.fullAddress(911, "146-190-232-70.sslip.io"),
        )
        assertEquals("7@is2.rcq.app", RcqFederation.fullAddress(7, "is2.rcq.app"))
    }

    @Test
    fun `the host is normalised, because a pasted address is compared as text`() {
        assertEquals("134@api.rcq.app", RcqFederation.fullAddress(134, "API.RCQ.APP"))
        assertEquals("134@api.rcq.app", RcqFederation.fullAddress(134, "  api.rcq.app  "))
    }

    @Test
    fun `everything it produces parses back to the same address`() {
        // The add sheet is the other end of this string. A copied address that
        // the parser rejects would be worse than the bare number it replaced.
        for (host in listOf("api.rcq.app", "is2.rcq.app", "146-190-232-70.sslip.io", "localhost:8443")) {
            val copied = RcqFederation.fullAddress(134, host)
            val back = RcqFederation.parseAddress(copied)
            assertEquals(134, back.uin)
            assertEquals(host.lowercase(), back.host)
            assertTrue("$copied must name its island", copied.contains('@'))
        }
    }

    @Test
    fun `a port survives, since a self-hosted island may run on one`() {
        assertEquals("134@localhost:8443", RcqFederation.fullAddress(134, "localhost:8443"))
    }
}
