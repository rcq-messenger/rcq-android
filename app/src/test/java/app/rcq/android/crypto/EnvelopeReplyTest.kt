package app.rcq.android.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1048: a quote rides on every content kind, not on text alone. Picking a
 *  message to answer and then attaching a picture used to send the picture
 *  without the quote, and a quote iOS or the web put on a photo was dropped on
 *  the way in. */
class EnvelopeReplyTest {

    private val q = Reply("ORIG-1", "the words", "alice")

    private fun roundTrip(e: Envelope): Envelope =
        Envelope.fromJsonBytes(e.toJsonBytes())

    @Test fun every_content_kind_carries_the_quote() {
        assertEquals(q, (roundTrip(Envelope.Text("A", "hi", replyTo = q)) as Envelope.Text).replyTo)
        assertEquals(q, (roundTrip(Envelope.Photo("A", "m", "k", null, replyTo = q)) as Envelope.Photo).replyTo)
        assertEquals(q, (roundTrip(Envelope.Video("A", "m", "k", "t", 1.0, null, replyTo = q)) as Envelope.Video).replyTo)
        assertEquals(q, (roundTrip(Envelope.Voice("A", "m", "k", 2.0, replyTo = q)) as Envelope.Voice).replyTo)
        assertEquals(q, (roundTrip(Envelope.File("A", "m", "k", "f.pdf", "application/pdf", 10, null, replyTo = q)) as Envelope.File).replyTo)
        assertEquals(q, (roundTrip(Envelope.Location("A", 1.0, 2.0, null, replyTo = q)) as Envelope.Location).replyTo)
    }

    @Test fun no_quote_means_no_key() {
        val json = String(Envelope.Photo("A", "m", "k", null).toJsonBytes())
        assertFalse(json.contains("\"reply\""))
        assertNull((roundTrip(Envelope.Photo("A", "m", "k", null)) as Envelope.Photo).replyTo)
    }

    /** Exactly the shape iOS `CryptoService.swift` emits for a photo that
     *  answers something (CodingKey `replyTo = "reply"`). */
    @Test fun ios_photo_reply_decodes() {
        val json = """{"kind":"photo","id":"A","mediaID":"m","mediaKey":"k","reply":{"id":"ORIG-1","snippet":"the words","authorName":"alice"}}"""
        val out = Envelope.fromJsonBytes(json.toByteArray()) as Envelope.Photo
        assertEquals(q, out.replyTo)
    }

    /** A malformed quote costs the quote, never the photo it came with. */
    @Test fun malformed_quote_keeps_the_photo() {
        val json = """{"kind":"photo","id":"A","mediaID":"m","mediaKey":"k","reply":"nope"}"""
        val out = Envelope.fromJsonBytes(json.toByteArray())
        assertTrue(out is Envelope.Photo)
        assertNull((out as Envelope.Photo).replyTo)
        val json2 = """{"kind":"voice","id":"A","mediaID":"m","mediaKey":"k","durationSec":2,"reply":{"id":{"x":1},"snippet":"s","authorName":"a"}}"""
        val out2 = Envelope.fromJsonBytes(json2.toByteArray()) as Envelope.Voice
        assertEquals("", out2.replyTo?.id)
        assertEquals("s", out2.replyTo?.snippet)
    }
}
