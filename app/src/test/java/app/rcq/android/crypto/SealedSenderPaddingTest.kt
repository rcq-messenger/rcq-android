package app.rcq.android.crypto

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 2 size-padding + message class: the sender-side wire contract, pinned.
 *
 * The seal/open round-trip itself rides `android.util.Base64`, which the plain
 * JVM unit runtime does not provide, so it is exercised on device (and against
 * web + iOS) rather than here. What this test can pin without Base64 is the part
 * that actually has to be right: that `padInner` lands the inner plaintext
 * EXACTLY on a size bucket, and that padding is TRANSPARENT: a receiver that
 * reads the inner by named keys (every shipped decoder does) sees the original
 * fields untouched and an extra `_pad` it ignores. That transparency is the one
 * property the hard constraint depends on: a padded message must open on a
 * client that has never heard of padding.
 *
 * The bucket ladder, `_pad` key, filler byte and class mapping mirror web
 * crypto.ts exactly, so a text padded on Android lands on the same rung as the
 * same text padded on the web.
 */
class SealedSenderPaddingTest {

    @Test
    fun bucketLadderMatchesWeb() {
        // Fixed rungs.
        assertEquals(256, SealedSender.bucketFor(1))
        assertEquals(256, SealedSender.bucketFor(256))
        assertEquals(1024, SealedSender.bucketFor(257))
        assertEquals(1024, SealedSender.bucketFor(1024))
        assertEquals(4096, SealedSender.bucketFor(1025))
        assertEquals(16384, SealedSender.bucketFor(4097))
        assertEquals(65536, SealedSender.bucketFor(16385))
        assertEquals(65536, SealedSender.bucketFor(65536))
        // Past the last fixed rung: multiples of 65536 (ceil).
        assertEquals(131072, SealedSender.bucketFor(65537))
        assertEquals(131072, SealedSender.bucketFor(131072))
        assertEquals(196608, SealedSender.bucketFor(131073))
    }

    /** Build a v=1-shaped inner with base64-like values (standard base64 chars
     *  are never JSON-escaped, exactly as the real spub/sig/env are). */
    private fun v1Inner(envB64: String): JsonObject = JsonObject().apply {
        addProperty("from", 555123)
        addProperty("from_host", "api.rcq.app")
        addProperty("spub", "A".repeat(44))
        addProperty("sig", "B".repeat(88))
        addProperty("env", envB64)
    }

    private fun v2Inner(msgB64: String): JsonObject = JsonObject().apply {
        addProperty("from", 555123)
        addProperty("kind", "signal")
        addProperty("msg", msgB64)
    }

    private fun compactLen(o: JsonObject): Int = o.toString().toByteArray(Charsets.UTF_8).size

    @Test
    fun padInnerLandsExactlyOnBucketAndStaysTransparent() {
        // A spread of message sizes that fall on different rungs.
        val envs = listOf(
            "Zg==",                 // tiny
            "Q".repeat(76),         // ~ a short text
            "Q".repeat(700),        // mid
            "Q".repeat(3000),       // larger
            "Q".repeat(40000),      // media caption / big body
        )
        for (env in envs) {
            val unpaddedLen = compactLen(v1Inner(env))
            val expectedBucket = SealedSender.bucketFor(unpaddedLen + 10) // PAD_OVERHEAD

            val inner = v1Inner(env)
            val padded = SealedSender.padInner(inner)

            // Byte-exact: the sealed inner is the size of its bucket, so every
            // message on a rung produces the identical inner (and blob) length.
            assertEquals(
                "padded inner must equal its size bucket",
                expectedBucket,
                padded.size,
            )

            // Transparent: an old decoder parses named keys and ignores `_pad`.
            val parsed = JsonParser.parseString(String(padded, Charsets.UTF_8)).asJsonObject
            assertEquals(555123, parsed.get("from").asInt)
            assertEquals("api.rcq.app", parsed.get("from_host").asString)
            assertEquals("A".repeat(44), parsed.get("spub").asString)
            assertEquals("B".repeat(88), parsed.get("sig").asString)
            assertEquals(env, parsed.get("env").asString)

            // The filler is only ASCII 'A' under the `_pad` key; dropping it
            // yields exactly the original inner, so it adds meaning to nothing.
            val pad = parsed.get(SealedSender.PAD_KEY).asString
            assertTrue("pad must be all 'A'", pad.all { it == 'A' })
            parsed.remove(SealedSender.PAD_KEY)
            assertEquals(unpaddedLen, compactLen(parsed))
        }
    }

    @Test
    fun v2InnerPadsToSmallestBucketToo() {
        // A tiny v=2 signal inner can reach the 256 rung a v=1 inner never can.
        val inner = v2Inner("Zg==")
        val unpaddedLen = compactLen(v2Inner("Zg=="))
        val padded = SealedSender.padInner(inner)
        assertEquals(SealedSender.bucketFor(unpaddedLen + 10), padded.size)
        assertTrue(unpaddedLen + 10 <= 256)
        assertEquals(256, padded.size)

        val parsed = JsonParser.parseString(String(padded, Charsets.UTF_8)).asJsonObject
        assertEquals("signal", parsed.get("kind").asString)
        assertEquals("Zg==", parsed.get("msg").asString)
    }

    @Test
    fun shouldPadCoversContentKindsOnly() {
        for (k in listOf("text", "photo", "video", "file", "location", "edit", "poll", "carbon")) {
            assertTrue("$k should pad", SealedSender.shouldPad(k))
        }
        for (k in listOf("read", "delivered", "reaction", "contactreq", "profile", "call", "homerec", "skdm", "sknack", "visit")) {
            assertFalse("$k should not pad", SealedSender.shouldPad(k))
        }
        assertFalse(SealedSender.shouldPad(null))
        assertFalse(SealedSender.shouldPad("nonsense"))
    }

    @Test
    fun envelopeKindOfReadsKindAndToleratesGarbage() {
        assertEquals("text", SealedSender.envelopeKindOf("""{"kind":"text","id":"x","text":"hi"}""".toByteArray()))
        assertNull(SealedSender.envelopeKindOf("""{"id":"x"}""".toByteArray()))
        assertNull(SealedSender.envelopeKindOf("not json at all".toByteArray()))
        // A numeric `kind` coerces to its string form ("123") rather than
        // throwing; harmless, because a garbage kind is never a pad kind.
        assertFalse(SealedSender.shouldPad(SealedSender.envelopeKindOf("""{"kind":123}""".toByteArray())))
    }

    @Test
    fun messageClassMirrorsClsFor() {
        // Ephemeral (0): dropped when the app is closed, no push, short TTL.
        for (t in listOf("typing", "read", "visit", "presence", "nudge", "bounce")) {
            assertEquals("$t is ephemeral", 0, SealedSender.messageClass(t))
        }
        // Critical (2): sender-key control that must survive.
        for (t in listOf("skdm", "sknack")) {
            assertEquals("$t is critical", 2, SealedSender.messageClass(t))
        }
        // Content (1): everything else, including unknown / future types.
        for (t in listOf("message", "reaction", "edit", "delete", "carbon", "homerec", "gmsg", "call", "future_type")) {
            assertEquals("$t is content", 1, SealedSender.messageClass(t))
        }
    }
}
