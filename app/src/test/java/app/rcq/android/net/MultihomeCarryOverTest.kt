package app.rcq.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Report 1064: a backup removed on this install has to really leave the
 *  signed home record, while one added on another device is still carried
 *  over; and a backup copy is forgotten only on an explicit "no such key". */
class MultihomeCarryOverTest {

    private fun h(host: String, uin: Int) = RcqFederation.Home(host, uin)

    @Test
    fun `a host removed here is not carried back from the published record`() {
        val published = listOf(h("api.rcq.app", 134), h("is2.rcq.app", 969335757), h("other.example", 5))
        val mine = listOf(h("api.rcq.app", 134))
        val carried = Multihome.carriedOverHomes(published, mine, setOf("IS2.rcq.app"), "api.rcq.app") { false }
        // is2 was removed on this install; other.example came from another device.
        assertEquals(listOf(h("other.example", 5)), carried)
    }

    @Test
    fun `without a removal everything unknown here is carried over, fronts and our island are not`() {
        val published = listOf(h("api.rcq.app", 134), h("cdn.rcq.app", 134), h("is2.rcq.app", 7))
        val mine = listOf(h("api.rcq.app", 134))
        val carried = Multihome.carriedOverHomes(published, mine, emptySet(), "api.rcq.app") { it == "cdn.rcq.app" }
        assertEquals(listOf(h("is2.rcq.app", 7)), carried)
    }

    @Test
    fun `only identity_not_found means the copy is gone`() {
        assertTrue(Multihome.copyGone("HTTP 404: {\"detail\":{\"code\":\"identity_not_found\"}}"))
        assertFalse(Multihome.copyGone("HTTP 404: {\"detail\":{\"code\":\"identity_rotated\",\"uin\":5}}"))
        assertFalse(Multihome.copyGone("HTTP 404: {\"detail\":{\"code\":\"identity_ambiguous\"}}"))
        assertFalse(Multihome.copyGone("HTTP 404: Not Found"))
        assertFalse(Multihome.copyGone("HTTP 401: {\"detail\":{\"code\":\"identity_not_found\"}}"))
        assertFalse(Multihome.copyGone(null))
    }
}
