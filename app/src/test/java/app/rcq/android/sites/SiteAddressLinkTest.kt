package app.rcq.android.sites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule that turns a URL into a site: the host, and nothing else, decides
 * (founder, 02.09, after `https://e2ee.rcq` opened the system browser). Host
 * JVM (`./gradlew testDebugUnitTest`), no emulator.
 */
class SiteAddressLinkTest {

    @Test
    fun schemeIsStrippedAndPathBecomesPage() {
        assertEquals(SiteLink("e2ee.rcq", null), SiteAddress.linkOf("https://e2ee.rcq"))
        assertEquals(SiteLink("e2ee.rcq", null), SiteAddress.linkOf("http://e2ee.rcq/"))
        assertEquals(SiteLink("e2ee.rcq", "en.html"), SiteAddress.linkOf("https://e2ee.rcq/en.html"))
        assertEquals(SiteLink("e2ee.rcq", "en.html"), SiteAddress.linkOf("rcq://e2ee.rcq/en.html?x=1#top"))
        // Only the FIRST segment is a page; the rest is not ours to guess at.
        assertEquals(SiteLink("blog.is2.rcq", "docs"), SiteAddress.linkOf("https://blog.is2.rcq/docs/a.html"))
    }

    @Test
    fun bareAddressesWithAPathAreLinksToo() {
        assertEquals(SiteLink("e2ee.rcq", null), SiteAddress.linkOf("e2ee.rcq"))
        assertEquals(SiteLink("e2ee.rcq", "zh.html"), SiteAddress.linkOf("e2ee.rcq/zh.html"))
        assertEquals(SiteLink("home.flagship.rcq", null), SiteAddress.linkOf("HOME.Flagship.RCQ"))
    }

    @Test
    fun sentencePunctuationAfterAPastedLinkIsNotPartOfIt() {
        assertEquals(SiteLink("e2ee.rcq", "en.html"), SiteAddress.linkOf("https://e2ee.rcq/en.html."))
        assertEquals(SiteLink("e2ee.rcq", null), SiteAddress.linkOf(" https://e2ee.rcq, "))
    }

    @Test
    fun theWebStaysTheWeb() {
        assertNull(SiteAddress.linkOf("https://blog.rcq.app/x"))
        assertNull(SiteAddress.linkOf("https://rcq.app/free"))
        assertNull(SiteAddress.linkOf("https://e2ee.rcq:443/"))
        assertNull(SiteAddress.linkOf("https://user@e2ee.rcq/"))
    }

    @Test
    fun whatIsNotAnAddressIsNotADoor() {
        assertNull(SiteAddress.linkOf("javascript:alert(1)"))
        assertNull(SiteAddress.linkOf("data:text/html,<script>"))
        assertNull(SiteAddress.linkOf("mailto:x@e2ee.rcq"))
        assertNull(SiteAddress.linkOf("//e2ee.rcq/x"))
        assertNull(SiteAddress.linkOf("/e2ee.rcq"))
        assertNull(SiteAddress.linkOf("#top"))
        assertNull(SiteAddress.linkOf("../../etc/passwd"))
        assertNull(SiteAddress.linkOf("https://"))
        assertNull(SiteAddress.linkOf(""))
        // Three labels: not an address to the parser, so not one here either.
        assertNull(SiteAddress.linkOf("https://a.b.c.rcq/"))
    }

    @Test
    fun aSiteKnownByIdentityShowsAsItWouldBeShared() {
        assertEquals("home.rcq", SiteAddress.of("home", "api.rcq.app", "api.rcq.app").display)
        assertEquals("home.flagship.rcq", SiteAddress.of("home", "api.rcq.app", "is2.rcq.app").display)
        assertEquals("blog.is2.rcq", SiteAddress.of("blog", "is2.rcq.app", "api.rcq.app").display)
        assertEquals("blog.mine.rcq", SiteAddress.of("blog", "mine.rcq.app", "api.rcq.app").display)
        assertEquals("blog.localhost:8443.rcq", SiteAddress.of("blog", "localhost:8443", "api.rcq.app").display)
        // The pin key is the identity, whatever the display says.
        assertEquals("home@api.rcq.app", SiteAddress.of("home", "api.rcq.app", "is2.rcq.app").pinKey)
    }

    @Test
    fun theDisplayOfAnOwnIslandSiteParsesBackToTheSameSite() {
        val own = "is2.rcq.app"
        for (a in listOf(
            SiteAddress.of("blog", own, own),
            SiteAddress.of("blog", "api.rcq.app", own),
            SiteAddress.of("blog", "mine.rcq.app", own),
        )) {
            assertEquals(a.pinKey, SiteAddress.parse(a.display, own)?.pinKey)
        }
    }
}
