package app.rcq.android.net

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The `X-Deposit-Token` header a Stage 3 bundle fetch carries, checked on the
 * JVM. The island base64url-decodes the value (padding tolerated) and reads
 * the same `{epoch_id, prepared, sig}` JSON a sealed deposit puts in its
 * body; a padded or non-url-safe encoding would pass locally and be refused
 * by the verifier as a malformed token (403), which the client reads as a
 * rotated epoch and spends a mint on.
 */
class DepositTokenHeaderTest {

    private fun token(): JsonObject = JsonObject().apply {
        addProperty("epoch_id", "2026-08-23T00:00:00Z")
        // Standard base64 with the characters that differ from the url-safe
        // alphabet, and padding: the header must wrap them, not alter them.
        addProperty("prepared", "+/+/AAECAwQFBgcICQoLDA0ODw==")
        addProperty("sig", "//8AAQ==")
    }

    @Test fun headerRoundTripsTheToken() {
        val t = token()
        val value = DepositAuthStore.headerValue(t)
        val decoded = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
        assertEquals(t, JsonParser.parseString(decoded).asJsonObject)
    }

    @Test fun headerIsUrlSafeAndUnpadded() {
        val value = DepositAuthStore.headerValue(token())
        assertFalse("no padding", value.endsWith("="))
        assertTrue("url-safe alphabet only", value.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test fun reserveHandsBackAnUnspentToken() {
        val host = "island.test"
        val t = token()
        DepositAuthStore.giveBack(host, t)
        // Nothing is minted for a host that has a token in reserve: the
        // object reads the reserve before it ever touches the network, so
        // the client argument is never used here.
        val popped = DepositAuthStore.tokenFor(host, okhttp3.OkHttpClient())
        assertEquals(t, popped)
        DepositAuthStore.forget(host)
    }
}
