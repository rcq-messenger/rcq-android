package app.rcq.android.net

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The DNS-TXT delivery channel, end to end: the query bytes, the reassembly of a
 * record's character-strings, and a seed coming out the far side that the normal
 * verifier accepts.
 *
 * The two real fixtures are genuine answers from Cloudflare's resolver to
 * queries built exactly the way [DnsTxt.buildQuery] builds them, so a change
 * that breaks the wire format fails here rather than in the field, on the one
 * network where this channel is the last one left.
 */
@RunWith(AndroidJUnit4::class)
class DnsTxtTest {

    private fun b64(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)

    /** A response carrying our seed, split across five character-strings the way
     *  a 1177-character record necessarily is. */
    private val seedResponse = "AACBgAABAAEAAAAAA2NmZwdleGFtcGxlA25ldAAAEAABwAwAEAABAAABLASe/3JjcTE6ZXlKcGMzTjFaV1JmWVhRaU9pSXlNREkyTFRBNExUQTFWREF5T2pJM09qUTJXaUlzSW5KbGJHRjVjeUk2VzNzaWIySm1jMTl3WVhOemQyOXlaQ0k2SW1wWVprZHJURlJ2VDJ0VWFXaHdaVXA2UkdsT1pqaENZaUlzSW5CaGMzTjNiM0prSWpvaVNrNHdjWHBCTkV4S1ptaElVRXRMVGpOUlNHbzBaVTQ0SWl3aWNHOXlkQ0k2TkRRekxDSndjbWx2Y21sMGVTSTZNQ3dpY0hKdmRHOGlPaUpvZVhOMFpYSnBZVElpTENKelpYSjJaWElpT2lJeE5qVXVNav9JdU9UQXVNakUwSWl3aWMyNXBJam9pWm5KaE1TNWthV2RwZEdGc2IyTmxZVzV6Y0dGalpYTXVZMjl0SWl3aWRHRm5Jam9pY21Wc1lYa3RaRzh0Wm5KaExYTndZV05sY3kxb2VUSWlmU3g3SW1ac2IzY2lPaUo0ZEd4ekxYSndjbmd0ZG1semFXOXVJaXdpY0c5eWRDSTZORFF6TENKd2NtbHZjbWwwZVNJNk1Td2ljSEp2ZEc4aU9pSjJiR1Z6Y3lJc0luQjFZbXhwWTE5clpYa2lPaUp1TXpOVVdsUk1UbkpqTmxnM2FsUkhja3RYWlhoZmMyczRZVWxSTmxGeGX/aTFsUXpoc2NWbE5hV2s0SWl3aWMyVnlkbVZ5SWpvaU1UWTFMakl5TGprd0xqSXhOQ0lzSW5Ob2IzSjBYMmxrSWpvaVlXRTFaRFE0TXpRME1XVTFPV0ZqTnlJc0luTnVhU0k2SW1aeVlURXVaR2xuYVhSaGJHOWpaV0Z1YzNCaFkyVnpMbU52YlNJc0luUmhaeUk2SW5KbGJHRjVMV1J2TFdaeVlTMXpjR0ZqWlhNaUxDSjFkV2xrSWpvaU1qQTRNV0l6WXpRdFptRmhZUzAwWTJObExXRXdZV0l0TmpBM01UazNZakk0TWpNM0luMHNleUp2WW1aelgzQmhjM04z/2IzSmtJam9pVUdGRlNISmFRVUpVYXpNMmIzSm9aa1pQVGpkS2RYSmxJaXdpY0dGemMzZHZjbVFpT2lKaWRuVjJkVGMwUTFaemFWaGtZMHBoZW1OWmNHaHVUelVpTENKd2IzSjBJam8wTkRNc0luQnlhVzl5YVhSNUlqb3lMQ0p3Y205MGJ5STZJbWg1YzNSbGNtbGhNaUlzSW5ObGNuWmxjaUk2SWpFeU9TNHhOVGt1TVRRekxqRXpOU0lzSW5OdWFTSTZJbmQzZHk1dGFXTnliM052Wm5RdVkyOXRJaXdpZEdGbklqb2ljbVZzWVhrdGIzSmhZMnhsTFdsc0xXaJ01TWlKOVhTd2ljMmxuSWpvaVZXUm1aazgzY1cxTFRUa3JhVGhoTWxkNWVXYzNjRXhzTTIwM1dYTk1WMGhPU0VsbU5tWnNhbkJTTVZSWFJqZEtSMDR3YmpOSFdDOXdSeko1YWs1Q2RESm9iVUZCVGl0Q1ExQk1URU5YY25kSldtTlJRMmM5UFNJc0luWmxjbk5wYjI0aU9qRXpNWDA9"

    /** A real answer for rcq.app — one TXT record, none of it ours. */
    private val rcqAppResponse = "AACBgAABAAEAAAAAA3JjcQNhcHAAABAAAcAMABAAAQAAASwAKyp2PXNwZjEgaW5jbHVkZTpfc3BmLm14LmNsb3VkZmxhcmUubmV0IH5hbGw="

    /** A real answer for google.com — fifteen TXT records, none of it ours.
     *  This is the case that catches a parser which returns the first record it
     *  finds instead of the one it was looking for. */
    private val googleResponse = "AACBgAABAA8AAAAABmdvb2dsZQNjb20AABAAAcAMABAAAQAAAEUARURnb29nbGUtc2l0ZS12ZXJpZmljYXRpb249NGliRlVnQi13WExRX1M3dnNYVm9tU1RWYW11T1hCaVZBenBSNUlaODdEMMAMABAAAQAAAEUAKyphcHBsZS1kb21haW4tdmVyaWZpY2F0aW9uPTMwYWZJQmN2U3VEVjJQTFjADAAQAAEAAABFAEFAZ2xvYmFsc2lnbi1zbWltZS1kdj1DRFlYK1hGSFV3MndtbDYvR2I4KzU5QnNIMzFLelVyNmMxbDJCUHZxS1g4PcAMABAAAQAAAEUAPj1vbmV0cnVzdC1kb21haW4tdmVyaWZpY2F0aW9uPTBkNDc3ZmU2MDgwNzRlNmY5YzEyYmNhNzgyNjAzNWNjwAwAEAABAAAARQAJCFoyOXZaMnhswAwAEAABAAAARQAuLWRvY3VzaWduPTA1OTU4NDg4LTQ3NTItNGVmMi05NWViLWFhN2JhOGEzYmQwZcAMABAAAQAAAEUAQUB3b3JrLWFjY291bnRzLWRvbWFpbi12ZXJpZmljYXRpb249VGNqNkpqSU1aT3cyS3NTRXcyTnQyckxhZTg5dE42wAwAEAABAAAARQBFRGdvb2dsZS1zaXRlLXZlcmlmaWNhdGlvbj13RDhON2kxSlROVGtleko0OXN3dldXNDhmOF85eHZlUkVWNG9CLTBIZjVvwAwAEAABAAAARQBFRGdvb2dsZS1zaXRlLXZlcmlmaWNhdGlvbj1UVjktREJlNFI4MFg0djBNNFVfYmRfSjljcE9KTTBuaWtmdDBqQWdqbXNRwAwAEAABAAAARQA8O2ZhY2Vib29rLWRvbWFpbi12ZXJpZmljYXRpb249MjJybTU1MWN1NGswYWIwYnhzdzUzNnRsZHM0aDk1wAwAEAABAAAARQAuLWRvY3VzaWduPTFiMGE2NzU0LTQ5YjEtNGRiNS04NTQwLWQyYzEyNjY0YjI4OcAMABAAAQAAAEUAPj1vbmV0cnVzdC1kb21haW4tdmVyaWZpY2F0aW9uPTZkNjg1ZjFkNDFhOTQ2OTZhZDdlZjc3MWY2ODk5M2UwwAwAEAABAAAARQAsK01TPUU0QTY4QjlBQjJCQjk2NzBCQ0UxNTQxMkY2MjkxNjE2NEMwQjIwQkLADAAQAAEAAABFAF5dY2lzY28tY2ktZG9tYWluLXZlcmlmaWNhdGlvbj00N2MzOGJjOGM0Yjc0YjcyMzNlOTA1MzIyMGMxYmJlNzZiY2MxY2QzM2M3YWNmN2FjZDM2Y2Q2YTUzMzIwMDRiwAwAEAABAAAARQAkI3Y9c3BmMSBpbmNsdWRlOl9zcGYuZ29vZ2xlLmNvbSB+YWxs"

    /** The exact query a live resolver answered. */
    private val liveQuery = "AAABAAABAAAAAAAAA3JjcQNhcHAAABAAAQ=="

    @Test
    fun buildsTheQueryAResolverActuallyAnswered() {
        val built = DnsTxt.buildQuery("rcq.app")
        assertNotNull(built)
        assertArrayEquals(b64(liveQuery), built)
    }

    @Test
    fun rejectsNamesThatCannotBeEncoded() {
        assertNull(DnsTxt.buildQuery("a..b"))
        assertNull(DnsTxt.buildQuery("x".repeat(64) + ".example.com"))
    }

    @Test
    fun reassemblesASeedSplitAcrossCharacterStrings() {
        val value = DnsTxt.parseTxt(b64(seedResponse))
        assertNotNull("the seed record must be found", value)
        val json = String(b64(value!!), Charsets.UTF_8)
        // The whole point: what comes off DNS goes through the SAME verifier as
        // anything fetched over HTTPS, with no special case for its origin.
        val relays = RelayConfigStore.verifyAndParse(json)
        assertNotNull("a seed off DNS must verify like any other payload", relays)
        assertEquals(3, relays!!.size)
    }

    @Test
    fun ignoresTxtRecordsThatAreNotOurs() {
        assertNull(DnsTxt.parseTxt(b64(rcqAppResponse)))
        assertNull(DnsTxt.parseTxt(b64(googleResponse)))
    }

    @Test
    fun survivesGarbageFromAResolverItDoesNotControl() {
        assertNull(DnsTxt.parseTxt(ByteArray(0)))
        assertNull(DnsTxt.parseTxt(ByteArray(11)))
        // Header claims an answer the message does not contain.
        val truncated = b64(seedResponse).copyOfRange(0, 30)
        assertNull(DnsTxt.parseTxt(truncated))
    }
}
