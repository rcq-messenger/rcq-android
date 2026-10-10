package app.rcq.android.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Report 1063: "forwarded from" rides under `fwdName` on every content kind,
 *  both ways, and an iPhone's label is no longer dropped on the way in. */
class EnvelopeForwardTest {

    private fun roundTrip(e: Envelope): Envelope = Envelope.fromJsonBytes(e.toJsonBytes())

    private fun fwdOf(e: Envelope): String? = when (e) {
        is Envelope.Text -> e.fwdName
        is Envelope.Photo -> e.fwdName
        is Envelope.Video -> e.fwdName
        is Envelope.Voice -> e.fwdName
        is Envelope.File -> e.fwdName
        is Envelope.Location -> e.fwdName
        else -> error("not a content kind: $e")
    }

    private val all: List<(String?) -> Envelope> = listOf(
        { f -> Envelope.Text("A", "hi", fwdName = f) },
        { f -> Envelope.Photo("A", "m", "k", null, fwdName = f) },
        { f -> Envelope.Video("A", "m", "k", "t", 1.0, null, fwdName = f) },
        { f -> Envelope.Voice("A", "m", "k", 2.0, fwdName = f) },
        { f -> Envelope.File("A", "m", "k", "f.pdf", "application/pdf", 10, null, fwdName = f) },
        { f -> Envelope.Location("A", 1.0, 2.0, null, fwdName = f) },
    )

    @Test fun every_content_kind_carries_the_label() {
        all.forEach { make ->
            val out = roundTrip(make("Alice"))
            assertEquals("Alice", fwdOf(out))
        }
    }

    @Test fun no_label_means_no_key_and_reads_back_null() {
        all.forEach { make ->
            val e = make(null)
            assertFalse(String(e.toJsonBytes()).contains("fwdName"))
            assertNull(fwdOf(roundTrip(e)))
            // A blank one is not a label either.
            assertFalse(String(make("  ").toJsonBytes()).contains("fwdName"))
        }
    }

    @Test fun the_label_does_not_disturb_the_rest() {
        val e = Envelope.Photo("A", "m", "k", "cap", spoiler = true, albumId = "AL", ttl = 60, ts = 1_700_000_000L, replyTo = null, fwdName = "Bob")
        val out = roundTrip(e) as Envelope.Photo
        assertEquals(e, out)
    }

    /** Exactly the shape iOS `CryptoService.swift` emits for a forwarded
     *  photo (CodingKey `forwardedFromName = "fwdName"`). */
    @Test fun ios_forward_decodes() {
        val json = """{"kind":"photo","id":"A","mediaID":"m","mediaKey":"k","fwdName":"Алиса","spoiler":true}"""
        val out = Envelope.fromJsonBytes(json.toByteArray()) as Envelope.Photo
        assertEquals("Алиса", out.fwdName)
        assertTrue(out.spoiler)
        val text = Envelope.fromJsonBytes("""{"kind":"text","id":"B","text":"x","fwdName":"Bob"}""".toByteArray()) as Envelope.Text
        assertEquals("Bob", text.fwdName)
    }

    /** A carbon to my other devices wraps the content envelope whole, so the
     *  label reaches them with it. */
    @Test fun a_carbon_keeps_the_label() {
        val inner = Envelope.Voice("A", "m", "k", 3.0, fwdName = "Carol")
        val out = roundTrip(Envelope.Carbon(to = 42, gid = null, env = inner)) as Envelope.Carbon
        assertEquals("Carol", (out.env as Envelope.Voice).fwdName)
    }

    @Test fun factories_take_the_label() {
        assertEquals("D", Envelope.text("x", fwdName = "D").fwdName)
        assertEquals("D", Envelope.photo("m", "k", null, fwdName = "D").fwdName)
        assertEquals("D", Envelope.video("m", "k", "t", 1.0, null, fwdName = "D").fwdName)
        assertEquals("D", Envelope.voice("m", "k", 1.0, fwdName = "D").fwdName)
        assertEquals("D", Envelope.file("m", "k", "n", "x/y", 1, null, fwdName = "D").fwdName)
        assertEquals("D", Envelope.location(1.0, 2.0, null, fwdName = "D").fwdName)
        assertNull(Envelope.text("x").fwdName)
    }

    /** It is a stranger's string drawn as one line over a bubble. */
    @Test fun a_hostile_label_is_cleaned_or_dropped() {
        fun parse(v: String) = (Envelope.fromJsonBytes("""{"kind":"text","id":"A","text":"x","fwdName":$v}""".toByteArray()) as Envelope.Text).fwdName
        // Line breaks and a bidi override become spaces and are trimmed.
        assertEquals("Eve  Mallory", parse("\"Eve\\n\\u202EMallory\\r\""))
        // Not a string: no label, and the message still arrives.
        assertNull(parse("{\"x\":1}"))
        assertNull(parse("[\"a\"]"))
        assertNull(parse("null"))
        assertNull(parse("\" \\t \""))
        // Cut to 64 code points without splitting a surrogate pair.
        val long = "😀".repeat(70)
        val cut = Envelope.sanitizeFwdName(long)!!
        assertEquals(Envelope.FWD_NAME_MAX, cut.codePointCount(0, cut.length))
        assertEquals(long.substring(0, cut.length), cut)
        // A number the sender chose for a name is theirs to show.
        assertEquals("12345", Envelope.sanitizeFwdName("12345"))
    }
}
