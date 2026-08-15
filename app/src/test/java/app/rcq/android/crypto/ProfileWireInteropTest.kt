package app.rcq.android.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5e cross-island profile refresh — the wire contract, pinned.
 *
 * The three clients only interoperate if their encoders agree byte for byte on
 * field names, on `ts` being epoch SECONDS, and on the omitted-vs-null rule for
 * the avatar pair. The expected strings below were produced by RUNNING the web
 * encoder (`envelopeToObject` in web-chat/src/lib/crypto.ts) and the iOS one
 * (the `Envelope` enum in iOS/RCQ/RCQ/Services/CryptoService.swift), not by
 * reading them, and the decode cases feed this client the other two clients'
 * actual output.
 *
 * The all-or-nothing rule for the picture is the one worth a test of its own:
 * an id without its key names a blob nobody can open, and since the envelope is
 * a SNAPSHOT, the other two clients read a missing pair as "I have no picture"
 * and CLEAR the one they hold. Emitting half a pair would silently delete our
 * face on web and iOS.
 */
class ProfileWireInteropTest {

    private val ID = "6E9F2C1A-4B3D-4E5F-8A7B-0C1D2E3F4A5B"
    private val TS = 1755277200L
    private val AID = "3f1c0a9b2d4e4f7a8b6c5d4e3f2a1b0c"
    private val AKEY = "8Jm2Yb0Zq3lTt5rXk9vN1cQ7wE4hR6uP0aS2dF8gJ1k="

    private fun enc(id: String?, key: String?): String =
        String(
            Envelope.ProfileUpdate(id = ID, ts = TS, nickname = "nick2", avatarMediaId = id, avatarMediaKey = key)
                .toJsonBytes(),
            Charsets.UTF_8,
        )

    private fun profile(json: String): Envelope.ProfileUpdate {
        val e = Envelope.fromJsonBytes(json.toByteArray(Charsets.UTF_8))
        assertTrue("expected a ProfileUpdate, got ${e::class.simpleName}", e is Envelope.ProfileUpdate)
        return e as Envelope.ProfileUpdate
    }

    @Test
    fun encodesTheSameBytesAsWebAndIos() {
        assertEquals(
            """{"kind":"profile","id":"$ID","ts":$TS,"nickname":"nick2","avatar_media_id":"$AID","avatar_media_key":"$AKEY"}""",
            enc(AID, AKEY),
        )
        // Optionals are OMITTED, never emitted as null — the convention §5f's
        // `note` settled on in all three clients.
        assertEquals("""{"kind":"profile","id":"$ID","ts":$TS,"nickname":"nick2"}""", enc(null, null))
    }

    @Test
    fun halfAPictureIsNeverPutOnTheWire() {
        val none = """{"kind":"profile","id":"$ID","ts":$TS,"nickname":"nick2"}"""
        assertEquals(none, enc(AID, null))
        assertEquals(none, enc(null, AKEY))
        // The factory the app actually calls collapses it too.
        val viaFactory = Envelope.profileUpdate("nick2", AID, null)
        assertNull(viaFactory.avatarMediaId)
        assertNull(viaFactory.avatarMediaKey)
    }

    @Test
    fun decodesWhatWebAndIosSend() {
        // Web: key order kind,id,ts,nickname,avatar_media_id,avatar_media_key.
        val fromWeb = profile("""{"kind":"profile","id":"$ID","ts":$TS,"nickname":"nick2","avatar_media_id":"$AID","avatar_media_key":"$AKEY"}""")
        assertEquals(ID, fromWeb.id)
        assertEquals(TS, fromWeb.ts)          // epoch SECONDS, not millis
        assertEquals("nick2", fromWeb.nickname)
        assertEquals(AID, fromWeb.avatarMediaId)
        assertEquals(AKEY, fromWeb.avatarMediaKey)

        // iOS: same fields, different key order (JSONEncoder does not sort).
        val fromIos = profile("""{"avatar_media_id":"$AID","avatar_media_key":"$AKEY","id":"$ID","kind":"profile","nickname":"nick2","ts":$TS}""")
        assertEquals(fromWeb, fromIos)
    }

    @Test
    fun toleratesBothOmittedAndExplicitNull() {
        val omitted = profile("""{"kind":"profile","id":"$ID","ts":$TS,"nickname":"nick2"}""")
        val explicit = profile("""{"kind":"profile","id":"$ID","ts":$TS,"nickname":"nick2","avatar_media_id":null,"avatar_media_key":null}""")
        assertNull(omitted.avatarMediaId)
        assertNull(omitted.avatarMediaKey)
        assertEquals(omitted, explicit)
    }

    @Test
    fun roundTrips() {
        val sent = Envelope.profileUpdate("nick2", AID, AKEY)
        val back = profile(String(sent.toJsonBytes(), Charsets.UTF_8))
        assertEquals(sent, back)
    }

    @Test
    fun hostileFieldTypesDoNotThrow() {
        // The queue drain catches per BATCH, not per row: one envelope that
        // throws in the decoder would abort every drain, forever. A peer can
        // put anything in these fields, so nothing may assume a string.
        val e = profile("""{"kind":"profile","id":"$ID","ts":$TS,"nickname":{"x":1},"avatar_media_id":["a"],"avatar_media_key":{}}""")
        assertEquals("", e.nickname)
        assertNull(e.avatarMediaId)
        assertNull(e.avatarMediaKey)
    }
}
