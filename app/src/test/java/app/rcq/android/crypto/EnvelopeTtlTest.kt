package app.rcq.android.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Disappearing-message TTL survives the envelope wire round-trip for every
 *  content kind, and control kinds ignore a stray ttl. Guards the iOS↔Android
 *  interop that the Android receiver used to drop entirely. */
class EnvelopeTtlTest {

    private fun roundTrip(e: Envelope): Envelope =
        Envelope.fromJsonBytes(e.toJsonBytes())

    @Test fun text_ttl_survives() {
        val out = roundTrip(Envelope.Text(id = "A", text = "hi", ttl = 60))
        assertEquals(60, (out as Envelope.Text).ttl)
    }

    @Test fun text_without_ttl_is_null() {
        val out = roundTrip(Envelope.Text(id = "A", text = "hi"))
        assertNull((out as Envelope.Text).ttl)
    }

    @Test fun media_kinds_carry_ttl() {
        assertEquals(30, (roundTrip(Envelope.Photo("A", "m", "k", "cap", ttl = 30)) as Envelope.Photo).ttl)
        assertEquals(45, (roundTrip(Envelope.Video("A", "m", "k", "t", 1.0, "cap", ttl = 45)) as Envelope.Video).ttl)
        assertEquals(15, (roundTrip(Envelope.Voice("A", "m", "k", 2.0, ttl = 15)) as Envelope.Voice).ttl)
        assertEquals(90, (roundTrip(Envelope.File("A", "m", "k", "f.pdf", "application/pdf", 10, "cap", ttl = 90)) as Envelope.File).ttl)
        assertEquals(120, (roundTrip(Envelope.Location("A", 1.0, 2.0, "here", ttl = 120)) as Envelope.Location).ttl)
    }

    @Test fun ios_wire_shape_decodes() {
        // Exactly what the iOS CryptoService encoder emits (key "ttl").
        val json = """{"kind":"text","id":"A","text":"secret","ttl":60}"""
        val out = Envelope.fromJsonBytes(json.toByteArray())
        assertEquals(60, (out as Envelope.Text).ttl)
    }

    @Test fun control_kind_ignores_stray_ttl() {
        val json = """{"kind":"reaction","targetID":"X","asset":":ok:","ttl":60}"""
        val out = Envelope.fromJsonBytes(json.toByteArray())
        assert(out is Envelope.Reaction)
    }
}
