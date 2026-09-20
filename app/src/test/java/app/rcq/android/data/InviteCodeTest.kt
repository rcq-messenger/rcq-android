package app.rcq.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a field that asks for an access code has to accept (#1034).
 *
 * The case that cost us somebody: the invite sheet copies a LINK, the join
 * field asks for a CODE, and the island answers a refusal that names three
 * wrong reasons. Everything here is a string somebody really might paste.
 */
class InviteCodeTest {

    private val code = "eEqhpf9aPc8xelRn8XSzFw1N"

    @Test
    fun theLinkTheSheetCopiesGivesUpItsCode() {
        // Verbatim from report #1034.
        assertEquals(code, InviteCode.of("rcq://server/api.rcq.app?invite=$code"))
    }

    @Test
    fun aBareCodeIsItself() {
        assertEquals(code, InviteCode.of(code))
        assertEquals(code, InviteCode.of("  $code  "))
    }

    @Test
    fun aWebLinkWorksToo() {
        assertEquals(code, InviteCode.of("https://rcq.app/join?invite=$code"))
        assertEquals(code, InviteCode.of("http://rcq.app/join?invite=$code"))
    }

    @Test
    fun anythingAfterTheCodeIsNotPartOfIt() {
        assertEquals(code, InviteCode.of("rcq://server/api.rcq.app?invite=$code&ref=x"))
        assertEquals(code, InviteCode.of("rcq://server/api.rcq.app?invite=$code#top"))
        assertEquals(code, InviteCode.of("$code and then some words"))
    }

    @Test
    fun theHostBeforeTheCodeIsNotPartOfItEither() {
        // The whole point: the island was being handed the link as the code.
        assertFalse(InviteCode.of("rcq://server/api.rcq.app?invite=$code")!!.contains("api.rcq.app"))
    }

    @Test
    fun punctuationFromASentenceIsDropped() {
        assertEquals(code, InviteCode.of("$code."))
        assertEquals(code, InviteCode.of("(rcq://server/api.rcq.app?invite=$code)"))
        assertEquals(code, InviteCode.of("\"$code\""))
    }

    @Test
    fun aPercentEncodedCodeComesBackReadable() {
        assertEquals("a+b/c", InviteCode.of("rcq://server/h?invite=a%2Bb%2Fc"))
    }

    @Test
    fun nothingUsableIsNull() {
        assertNull(InviteCode.of(null))
        assertNull(InviteCode.of(""))
        assertNull(InviteCode.of("   "))
        assertNull(InviteCode.of("rcq://server/api.rcq.app?invite="))
    }

    @Test
    fun aQueryWithOtherParametersFirstStillFindsIt() {
        assertEquals(code, InviteCode.of("rcq://server/api.rcq.app?ref=x&invite=$code"))
    }

    @Test
    fun theCaseOfTheParameterDoesNotMatter() {
        assertEquals(code, InviteCode.of("rcq://server/api.rcq.app?INVITE=$code"))
    }

    @Test
    fun aScreenCanTellALinkFromACode() {
        assertTrue(InviteCode.looksLikeLink("rcq://server/api.rcq.app?invite=$code"))
        assertTrue(InviteCode.looksLikeLink("https://rcq.app/join?invite=$code"))
        assertFalse(InviteCode.looksLikeLink(code))
        assertFalse(InviteCode.looksLikeLink(null))
    }
}
