package app.rcq.android.crypto

import com.google.gson.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 7 of the 2026-09-15 guest spec: a group frame never carries a 1:1
 * conversation. One case per kind, because a kind that slips through here is a
 * member (a guest included) addressing one person through the room in a way
 * the island cannot see.
 */
class GroupFrameKindDropTest {

    @Test
    fun contactRequestIsDropped() =
        assertTrue(GroupFrameRule.oneToOneOnly(Envelope.ContactRequest("id", "request", 1L, "Anna")))

    @Test
    fun ciAckIsDropped() =
        assertTrue(GroupFrameRule.oneToOneOnly(Envelope.CiAck(1, "b.example", "accept")))

    @Test
    fun pkeyAskIsDropped() = assertTrue(GroupFrameRule.oneToOneOnly(Envelope.PKeyAsk))

    @Test
    fun pkeyIsDropped() = assertTrue(GroupFrameRule.oneToOneOnly(Envelope.PKey("k")))

    @Test
    fun visitIsDropped() = assertTrue(GroupFrameRule.oneToOneOnly(Envelope.Visit(1.0)))

    @Test
    fun callIsDropped() =
        assertTrue(GroupFrameRule.oneToOneOnly(Envelope.CallSignal("id", "offer", "cid", 1L, emptyMap())))

    @Test
    fun profileRefreshIsDropped() =
        assertTrue(GroupFrameRule.oneToOneOnly(Envelope.ProfileUpdate("id", 1L, "Anna")))

    @Test
    fun carbonIsDropped() =
        assertTrue(GroupFrameRule.oneToOneOnly(Envelope.Carbon(5, null, Envelope.Text("id", "hi"))))

    @Test
    fun readMarkAndHomeRecordAreDropped() {
        assertTrue(GroupFrameRule.oneToOneOnly(Envelope.ReadMark(1L)))
        assertTrue(GroupFrameRule.oneToOneOnly(Envelope.HomeRecord(JsonObject())))
    }

    @Test
    fun roomKeysAndScreenTogglesAreDropped() {
        assertTrue(GroupFrameRule.oneToOneOnly(Envelope.GsKey(1, 1L, "k")))
        assertTrue(GroupFrameRule.oneToOneOnly(Envelope.GsKnack(1)))
        assertTrue(GroupFrameRule.oneToOneOnly(Envelope.SecureScreen(true)))
        assertTrue(GroupFrameRule.oneToOneOnly(Envelope.ScreenshotTaken("id")))
    }

    @Test
    fun roomContentStillRenders() {
        val content = listOf(
            Envelope.Text("id", "hi"),
            Envelope.Photo("id", "m", "k", null),
            Envelope.Location("id", 1.0, 2.0, null),
            Envelope.Poll("id", 1, "q", listOf("a", "b"), singleChoice = true, anonymous = false),
            Envelope.Reaction("x", "a"),
            Envelope.Delete("x"),
            Envelope.Edit("x", "y"),
            Envelope.ReadReceipt(listOf("x")),
            Envelope.DeliveredReceipt(listOf("x")),
            Envelope.RelayShare("id", JsonObject()),
            Envelope.Skdm(1, "kid", 0, 0, "ck"),
            Envelope.Sknack(1, "kid"),
            Envelope.Unknown("future"),
        )
        for (env in content) assertFalse("$env", GroupFrameRule.oneToOneOnly(env))
    }

    /**
     * Decision E3: the drop list is checked against the WIRE, not against the
     * spec's prose. Every kind in it is decoded from the name this client's
     * encoder writes, and the decoder has to make something of it: a name that
     * is not on the wire decodes to [Envelope.Unknown], drops nothing, and
     * reads like a rule that is doing work.
     */
    @Test
    fun everyDroppedKindIsOneTheDecoderCanProduce() {
        for ((kind, json) in ONE_TO_ONE_FRAMES) {
            val env = Envelope.fromJsonBytes(json.toByteArray(Charsets.UTF_8))
            assertFalse("$kind decoded to Unknown: not a kind on this wire", env is Envelope.Unknown)
            assertTrue("$kind is not dropped inside a group frame", GroupFrameRule.oneToOneOnly(env))
        }
    }

    /** The screenshot notice travels as "shot" (Envelope.toJsonBytes, and iOS
     *  names it the same). "screenshot" is the word the spec's prose uses, and
     *  a list built from the prose would drop nothing at all. */
    @Test
    fun theScreenshotNoticeIsDroppedUnderItsWireName() {
        val prose = Envelope.fromJsonBytes("""{"kind":"screenshot","id":"x"}""".toByteArray(Charsets.UTF_8))
        assertTrue(prose is Envelope.Unknown)
        assertFalse(GroupFrameRule.oneToOneOnly(prose))
        val wire = Envelope.fromJsonBytes("""{"kind":"shot","id":"x"}""".toByteArray(Charsets.UTF_8))
        assertTrue(wire is Envelope.ScreenshotTaken)
        assertTrue(GroupFrameRule.oneToOneOnly(wire))
    }

    /** The other half of the same rule: what a room is FOR still arrives after
     *  a round trip through the wire names. */
    @Test
    fun roomKindsSurviveTheDropList() {
        for (json in ROOM_FRAMES) {
            val env = Envelope.fromJsonBytes(json.toByteArray(Charsets.UTF_8))
            assertFalse("$json decoded to Unknown", env is Envelope.Unknown)
            assertFalse("$json was dropped", GroupFrameRule.oneToOneOnly(env))
        }
    }

    private companion object {
        /** The fourteen 1:1 kinds, spelled as [Envelope.toJsonBytes] writes
         *  them. The same set on iOS (`GroupFrameRule.oneToOneOnlyKinds`). */
        val ONE_TO_ONE_FRAMES = listOf(
            "contactreq" to """{"kind":"contactreq","id":"x","act":"request","ts":1,"nickname":"Anna"}""",
            "ciack" to """{"kind":"ciack","uin":5,"host":"b.example","act":"accept"}""",
            "pkey" to """{"kind":"pkey","key":"k"}""",
            "pkeyask" to """{"kind":"pkeyask"}""",
            "profile" to """{"kind":"profile","id":"x","ts":1,"nickname":"Anna"}""",
            "visit" to """{"kind":"visit","at":1.0}""",
            "call" to """{"kind":"call","id":"x","sig":"offer","cid":"c","ts":1}""",
            "carbon" to """{"kind":"carbon","to":5,"env":{"kind":"text","id":"y","text":"hi"}}""",
            "readmark" to """{"kind":"readmark","at":1}""",
            "homerec" to """{"kind":"homerec","rec":{"uin":5}}""",
            "gskey" to """{"kind":"gskey","gid":1,"ver":1,"key":"k"}""",
            "gsknack" to """{"kind":"gsknack","gid":1}""",
            "secscreen" to """{"kind":"secscreen","on":true}""",
            "shot" to """{"kind":"shot","id":"x"}""",
        )

        /** What a room carries, including the sender-key control frames that
         *  DO ride group-sealed and must never join the list above. */
        val ROOM_FRAMES = listOf(
            """{"kind":"text","id":"x","text":"hi"}""",
            """{"kind":"photo","id":"x","mediaID":"m","mediaKey":"k"}""",
            """{"kind":"reaction","targetID":"x","asset":"a"}""",
            """{"kind":"delete","targetID":"x"}""",
            """{"kind":"edit","targetID":"x","text":"y"}""",
            """{"kind":"read","targetIDs":["x"]}""",
            """{"kind":"delivered","targetIDs":["x"]}""",
            """{"kind":"location","id":"x","lat":1.0,"lng":2.0}""",
            """{"kind":"poll","id":"x","poll":1,"q":"q","opts":["a","b"]}""",
            """{"kind":"skdm","gid":1,"kid":"k","e":0,"i":0,"ck":"c"}""",
            """{"kind":"sknack","gid":1,"kid":"k"}""",
            """{"kind":"relay_share","id":"x","relay":{"h":"r"}}""",
        )
    }
}
