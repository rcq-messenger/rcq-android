package app.rcq.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `uin@island` in a message is a person, not a site (#1053).
 */
class ProfileLinksTest {

    private fun one(text: String): ProfileLinks.Hit? = ProfileLinks.find(text).singleOrNull()

    @Test
    fun `the reported address is a profile on is2`() {
        val text = "833111503@is2.rcq.app"
        val hit = one(text)!!
        assertEquals(833111503, hit.uin)
        assertEquals("is2.rcq.app", hit.host)
        assertEquals(text.indices, hit.range)
    }

    @Test
    fun `the flagship form and a self-hosted island with a port`() {
        assertEquals(ProfileLinks.Hit(0..14, 134, "api.rcq.app"), one("134@api.rcq.app"))
        val t = "мой 911@146-190-232-70.sslip.io:8443 пиши"
        val hit = one(t)!!
        assertEquals("911@146-190-232-70.sslip.io:8443", t.substring(hit.range))
        assertEquals("146-190-232-70.sslip.io:8443", hit.host)
    }

    @Test
    fun `the host is lowercased the way the add sheet parses it`() {
        assertEquals("is2.rcq.app", one("7@IS2.RCQ.APP")!!.host)
    }

    @Test
    fun `a sentence's full stop and brackets are not part of it`() {
        val t = "Добавь меня: 833111503@is2.rcq.app."
        assertEquals("833111503@is2.rcq.app", t.substring(one(t)!!.range))
        val b = "(833111503@is2.rcq.app)"
        assertEquals("833111503@is2.rcq.app", b.substring(one(b)!!.range))
    }

    @Test
    fun `emails are left alone`() {
        assertTrue(ProfileLinks.find("name@example.com").isEmpty())
        assertTrue(ProfileLinks.find("john.134@example.com").isEmpty())
        assertTrue(ProfileLinks.find("a134@example.com").isEmpty())
        assertTrue(ProfileLinks.find("x_134@example.com").isEmpty())
        assertTrue(ProfileLinks.find("mailto:134@example.com").isEmpty())
    }

    @Test
    fun `not a number that cannot be a uin, and not a host without a dot`() {
        // Eleven digits: a phone number, and the tail of it is not a uin either.
        assertTrue(ProfileLinks.find("79161234567@is2.rcq.app").isEmpty())
        assertTrue(ProfileLinks.find("9999999999@is2.rcq.app").isEmpty())
        assertTrue(ProfileLinks.find("0@is2.rcq.app").isEmpty())
        assertTrue(ProfileLinks.find("134@localhost").isEmpty())
        assertTrue(ProfileLinks.find("134@").isEmpty())
        assertTrue(ProfileLinks.find("134@@is2.rcq.app").isEmpty())
    }

    @Test
    fun `an address inside a url belongs to the url`() {
        val t = "https://x.org/?a=134@is2.rcq.app"
        val url = t.indices
        assertTrue(ProfileLinks.find(t, listOf(url)).isEmpty())
    }

    @Test
    fun `several in one message, in order`() {
        val hits = ProfileLinks.find("134@api.rcq.app и 7@is2.rcq.app")
        assertEquals(listOf(134, 7), hits.map { it.uin })
        assertEquals(listOf("api.rcq.app", "is2.rcq.app"), hits.map { it.host })
    }

    @Test
    fun `the gate is cheap and exact enough`() {
        assertTrue(ProfileLinks.mayContain("hi@x 134@is2.rcq.app"))
        assertEquals(false, ProfileLinks.mayContain("name@example.com"))
        assertEquals(false, ProfileLinks.mayContain("no at sign 134"))
    }

    @Test
    fun `an island's host is not a site, a site still is`() {
        // The `.rcq` pass no longer takes `is2.rcq` out of `is2.rcq.app`...
        assertTrue(SiteLinks.find("is2.rcq.app").isEmpty())
        assertTrue(SiteLinks.find("see api.rcq.app/x").isEmpty())
        // ...and every real address form still links.
        assertEquals(listOf(0..7), SiteLinks.find("blog.rcq"))
        assertEquals(listOf(4..15), SiteLinks.find("see blog.is2.rcq."))
        assertEquals(listOf(4..11), SiteLinks.find("see blog.rcq, ok"))
        assertEquals(listOf(1..8), SiteLinks.find("(e2ee.rcq)"))
    }
}
