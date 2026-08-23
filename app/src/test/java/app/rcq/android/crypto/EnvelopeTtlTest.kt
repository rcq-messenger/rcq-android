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

    // ── the send anchor (`ts`) ───────────────────────────────────────

    @Test fun ts_survives_for_every_content_kind() {
        assertEquals(1_700_000_000L, (roundTrip(Envelope.Text("A", "hi", null, 60, 1_700_000_000L)) as Envelope.Text).ts)
        assertEquals(1_700_000_001L, (roundTrip(Envelope.Photo("A", "m", "k", "cap", ttl = 30, ts = 1_700_000_001L)) as Envelope.Photo).ts)
        assertEquals(1_700_000_002L, (roundTrip(Envelope.Video("A", "m", "k", "t", 1.0, "cap", ttl = 45, ts = 1_700_000_002L)) as Envelope.Video).ts)
        assertEquals(1_700_000_003L, (roundTrip(Envelope.Voice("A", "m", "k", 2.0, ttl = 15, ts = 1_700_000_003L)) as Envelope.Voice).ts)
        assertEquals(1_700_000_004L, (roundTrip(Envelope.File("A", "m", "k", "f.pdf", "application/pdf", 10, "cap", ttl = 90, ts = 1_700_000_004L)) as Envelope.File).ts)
        assertEquals(1_700_000_005L, (roundTrip(Envelope.Location("A", 1.0, 2.0, "here", ttl = 120, ts = 1_700_000_005L)) as Envelope.Location).ts)
    }

    /** A timestamp on every message would be a new piece of metadata inside
     *  the envelope for nobody's benefit. Same rule the web encoder follows. */
    @Test fun ts_is_never_emitted_without_a_ttl() {
        val json = String(Envelope.Text("A", "hi", null, ttl = null, ts = 1_700_000_000L).toJsonBytes())
        assert(!json.contains("\"ts\"")) { json }
    }

    @Test fun factory_stamps_ts_only_when_a_timer_is_on() {
        val off = Envelope.text("hi")
        assertNull(off.ttl); assertNull(off.ts)
        val zero = Envelope.text("hi", ttl = 0)
        assertNull(zero.ttl); assertNull(zero.ts)
        val on = Envelope.text("hi", ttl = 60)
        assertEquals(60, on.ttl)
        val now = System.currentTimeMillis() / 1000
        assert(on.ts != null && on.ts!! in (now - 5)..(now + 5)) { "ts=${on.ts} now=$now" }
    }

    /** An older peer sends a bare `ttl`; the reader must survive that and fall
     *  back rather than treat a missing anchor as zero. */
    @Test fun wire_without_ts_decodes_to_null() {
        val out = Envelope.fromJsonBytes("""{"kind":"text","id":"A","text":"hi","ttl":60}""".toByteArray())
        assertNull((out as Envelope.Text).ts)
    }

    @Test fun malformed_ts_does_not_throw() {
        val obj = Envelope.fromJsonBytes("""{"kind":"text","id":"A","text":"hi","ttl":60,"ts":{"a":1}}""".toByteArray())
        assertNull((obj as Envelope.Text).ts)
        val arr = Envelope.fromJsonBytes("""{"kind":"text","id":"A","text":"hi","ttl":60,"ts":[1]}""".toByteArray())
        assertNull((arr as Envelope.Text).ts)
        val str = Envelope.fromJsonBytes("""{"kind":"text","id":"A","text":"hi","ttl":60,"ts":"nope"}""".toByteArray())
        assertNull((str as Envelope.Text).ts)
    }

    // ── the rails on an attacker-controlled anchor ───────────────────

    private val now = 1_700_000_000_000L   // epoch ms

    @Test fun a_plausible_ts_is_believed() {
        // Sent an hour ago, drained now.
        assertEquals(now - 3_600_000, Envelope.anchorFromTs((now - 3_600_000) / 1000, now))
    }

    @Test fun a_future_ts_is_refused() {
        // Past the skew allowance: believing it would extend the message's life
        // beyond what its own sender promised.
        assertNull(Envelope.anchorFromTs((now + 120_000) / 1000, now))
        // ...but ordinary skew inside the allowance still counts.
        assertEquals(now + 30_000, Envelope.anchorFromTs((now + 30_000) / 1000, now))
    }

    @Test fun a_pre_epoch_or_ancient_ts_is_refused() {
        assertNull(Envelope.anchorFromTs(0L, now))
        assertNull(Envelope.anchorFromTs(-1L, now))
        assertNull(Envelope.anchorFromTs(null, now))
        assertNull(Envelope.anchorFromTs(1L, now))
        assertNull(Envelope.anchorFromTs((now - 400L * 24 * 3600 * 1000) / 1000, now))
    }

    /** A client that sends MILLISECONDS where seconds belong must not be read
     *  as a plausible second count. Multiplied first, it lands centuries out
     *  and is refused. */
    @Test fun milliseconds_in_the_seconds_field_are_refused() {
        assertNull(Envelope.anchorFromTs(now, now))
    }

    @Test fun overflow_is_refused() {
        assertNull(Envelope.anchorFromTs(Long.MAX_VALUE, now))
    }

    @Test fun the_deposit_stamp_is_railed_the_same_way() {
        assertEquals(now - 60_000, Envelope.saneAnchorMs(now - 60_000, now))
        assertNull(Envelope.saneAnchorMs(now + 120_000, now))
        assertNull(Envelope.saneAnchorMs(0L, now))
        assertNull(Envelope.saneAnchorMs(null, now))
    }
}
