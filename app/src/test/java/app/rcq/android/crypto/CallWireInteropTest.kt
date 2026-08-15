package app.rcq.android.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5d cross-island call signalling — the inner wire contract, pinned.
 *
 * The three clients only interoperate if their encoders agree on the field
 * names, on `ts` being epoch SECONDS, on `data` always being present, and on
 * every value inside `data` being a STRING. A number or a boolean in there is a
 * hard decode error on iOS (`[String: String]`), which drops the signal in
 * silence — and a dropped signal is a call that rings and never connects.
 *
 * The expected strings below were produced by RUNNING the other two encoders,
 * not by reading them: the web one by bundling `envelopeToObject`
 * (web-chat/src/lib/crypto.ts) through esbuild and executing it in node, the
 * iOS one by compiling the `Envelope` enum out of
 * iOS/RCQ/RCQ/Services/CryptoService.swift with swiftc and running
 * `JSONEncoder().encode(...)` on it — the same call `CryptoService.encrypt`
 * makes. The decode cases feed this client those clients' actual output.
 *
 * Key ORDER is deliberately not asserted against iOS: Foundation's JSONEncoder
 * emits a keyed container in hash order, so iOS bytes differ run to run. It
 * does not matter — each side signs the bytes it emits and the receiver parses
 * JSON, which is order-independent. What must match is names, types and
 * presence, which is what this test pins.
 */
class CallWireInteropTest {

    private val ID = "6E9F2C1A-4B3D-4E5F-8A7B-0C1D2E3F4A5B"
    private val CID = "0AF3B1C2-9D8E-4F70-B123-456789ABCDEF"
    private val TS = 1755200000L

    private fun enc(sig: String, data: Map<String, String>): String =
        String(Envelope.CallSignal(id = ID, sig = sig, cid = CID, ts = TS, data = data).toJsonBytes(), Charsets.UTF_8)

    private fun call(json: String): Envelope.CallSignal {
        val e = Envelope.fromJsonBytes(json.toByteArray(Charsets.UTF_8))
        assertTrue("expected a CallSignal, got ${e::class.simpleName}", e is Envelope.CallSignal)
        return e as Envelope.CallSignal
    }

    @Test
    fun encodesTheSameBytesAsWeb() {
        assertEquals(
            """{"kind":"call","id":"$ID","sig":"call_offer","cid":"$CID","ts":$TS,"data":{"media":"audio","sdp":"sdp-here"}}""",
            enc("call_offer", linkedMapOf("media" to "audio", "sdp" to "sdp-here")),
        )
        // The BATCHED ICE shape: one envelope, `candidates` a JSON-array STRING.
        assertEquals(
            """{"kind":"call","id":"$ID","sig":"call_ice","cid":"$CID","ts":$TS,"data":{"candidates":"[\"cand-a\",\"cand-b\"]"}}""",
            enc("call_ice", mapOf("candidates" to """["cand-a","cand-b"]""")),
        )
        // `data` is always PRESENT, even when empty — iOS emits `"data":{}` and
        // web emits `"data":{}`; omitting it would be different signed bytes for
        // no gain.
        assertEquals(
            """{"kind":"call","id":"$ID","sig":"call_end","cid":"$CID","ts":$TS,"data":{}}""",
            enc("call_end", emptyMap()),
        )
    }

    @Test
    fun timestampIsEpochSeconds() {
        val env = Envelope.callSignal("call_offer", CID, mapOf("media" to "audio"))
        val now = System.currentTimeMillis() / 1000
        assertTrue("ts=${env.ts} is not epoch seconds near $now", env.ts in (now - 5)..(now + 5))
        // And the factory's id is an UPPERCASE UUID: iOS decodes `id` as
        // `UUID.self` and web emits `.toUpperCase()`.
        assertEquals(env.id.uppercase(), env.id)
        assertEquals(36, env.id.length)
    }

    @Test
    fun decodesWhatWebAndIosSend() {
        val fromWeb = call(
            """{"kind":"call","id":"$ID","sig":"call_offer","cid":"$CID","ts":$TS,"data":{"media":"audio","sdp":"sdp-here"}}"""
        )
        assertEquals(ID, fromWeb.id)
        assertEquals("call_offer", fromWeb.sig)
        assertEquals(CID, fromWeb.cid)
        assertEquals(TS, fromWeb.ts)          // epoch SECONDS, not millis
        assertEquals(mapOf("media" to "audio", "sdp" to "sdp-here"), fromWeb.data)

        // iOS: identical fields, hash key order (JSONEncoder does not sort).
        val fromIos = call(
            """{"cid":"$CID","ts":$TS,"data":{"media":"audio","sdp":"sdp-here"},"kind":"call","id":"$ID","sig":"call_offer"}"""
        )
        assertEquals(fromWeb, fromIos)
    }

    @Test
    fun hostileFieldTypesDoNotThrow() {
        // The queue drain catches per BATCH, not per row: one envelope that
        // throws in the decoder would abort every drain, forever. A peer can put
        // anything in these fields, so nothing may assume a string.
        val e = call("""{"kind":"call","id":{"x":1},"sig":{"x":1},"cid":["a"],"ts":"nope","data":{"media":{"deep":1},"sdp":"ok"}}""")
        assertEquals("", e.sig)
        assertEquals("", e.cid)
        assertEquals(0L, e.ts)
        assertEquals(mapOf("sdp" to "ok"), e.data)   // the object-valued key is dropped, not stringified
        // `data` itself may be any JSON: `getAsJsonObject` CASTS, so an array
        // there used to throw ClassCastException before any filter ran.
        assertTrue(call("""{"kind":"call","id":"$ID","sig":"call_end","cid":"c","ts":1,"data":["x"]}""").data.isEmpty())
        assertTrue(call("""{"kind":"call","id":"$ID","sig":"call_end","cid":"c","ts":1}""").data.isEmpty())
    }

    @Test
    fun roundTrips() {
        val sent = Envelope.callSignal("call_answer", CID, mapOf("sdp" to "answer"))
        assertEquals(sent, call(String(sent.toJsonBytes(), Charsets.UTF_8)))
    }
}
