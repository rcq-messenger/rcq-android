package app.rcq.android.net

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.rcq.android.crypto.Envelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof of the in-chat bridge-sharing core (task #1): the relay token
 * parser, the relay<->wire-JSON round-trip, the `relay_share` envelope
 * round-trip, and the ContactRelayStore augmenting the transport pool with
 * dedup + remove. See RCQ/docs/bridge-sharing-design.md. The live "does it
 * actually route" is founder-tested; this nails the plumbing.
 */
@RunWith(AndroidJUnit4::class)
class ContactRelayShareTest {

    private val vless = SingBoxTransport.Relay(
        tag = "shared-test", proto = "vless", server = "203.0.113.7", port = 443,
        sni = "www.yandex.ru", uuid = "9c7174e7-2cb9-4d03-bffb-259bd534b65b",
        publicKey = "ord-QgtxD57vOVLMsXwGC6Qj7kaK4kb8Tq3MxImQch4",
        shortId = "5d88ef2912b4fa39", flow = "xtls-rprx-vision",
    )

    @Test
    fun tokenRoundTrips() {
        val token = ContactRelayStore.relayToToken(vless)
        assertTrue(token.startsWith("rcq-relay://vless"))
        val back = ContactRelayStore.relayFromToken(token)
        assertNotNull(back)
        assertEquals(vless.server, back!!.server)
        assertEquals(vless.port, back.port)
        assertEquals(vless.uuid, back.uuid)
        assertEquals(vless.publicKey, back.publicKey)
        assertEquals(vless.shortId, back.shortId)
        assertEquals("vless", back.proto)
    }

    @Test
    fun hysteria2TokenRoundTrips() {
        val hy2 = SingBoxTransport.Relay(
            tag = "shared-hy2", proto = "hysteria2", server = "198.51.100.4", port = 443,
            sni = "www.microsoft.com", password = "bvuvu74CVsiXdcJazcYphnO5", obfsPassword = "PaEHrZABTk36orhfFON7Jure",
        )
        val token = ContactRelayStore.relayToToken(hy2)
        assertTrue(token.startsWith("rcq-relay://hy2"))
        val back = ContactRelayStore.relayFromToken(token)
        assertNotNull(back)
        assertEquals("hysteria2", back!!.proto)
        assertEquals(hy2.password, back.password)
        assertEquals(hy2.obfsPassword, back.obfsPassword)
    }

    @Test
    fun badTokenRejected() {
        assertNull(ContactRelayStore.relayFromToken("https://example.com"))
        assertNull(ContactRelayStore.relayFromToken("rcq-relay://vless?s=1.2.3.4&p=443")) // no sni/uuid
        assertNull(ContactRelayStore.relayFromToken("not a url"))
    }

    @Test
    fun wireJsonRoundTrips() {
        val obj = ContactRelayStore.relayToJson(vless)
        val back = ContactRelayStore.relayFromJson(obj)
        assertNotNull(back)
        assertEquals(vless.server, back!!.server)
        assertEquals(vless.uuid, back.uuid)
        assertEquals(vless.publicKey, back.publicKey)
    }

    @Test
    fun relayShareEnvelopeRoundTrips() {
        val obj = ContactRelayStore.relayToJson(vless)
        val env = Envelope.relayShare(obj, note = "try this")
        val decoded = Envelope.fromJsonBytes(env.toJsonBytes())
        assertTrue(decoded is Envelope.RelayShare)
        val rs = decoded as Envelope.RelayShare
        assertEquals(env.id, rs.id)
        assertEquals("try this", rs.note)
        val relay = ContactRelayStore.relayFromJson(rs.relay)
        assertNotNull(relay)
        assertEquals(vless.server, relay!!.server)
        assertEquals(vless.uuid, relay.uuid)
    }

    @Test
    fun storeAddsToPoolWithDedupAndRemove() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ContactRelayStore.init(ctx)
        // Unique server so the test is independent of prior runs / real config.
        val r = vless.copy(server = "192.0.2.${(System.nanoTime() % 250).toInt() + 1}")

        val added = ContactRelayStore.add(r, fromUin = 692238923, fromName = "BannerBot")
        assertTrue(added)
        assertTrue(ContactRelayStore.has(r))
        // The transport pool surface includes it.
        assertTrue(ContactRelayStore.relays().any { it.server == r.server && it.port == r.port })
        // The assigned tag is the collision-proof shared- form, not the wire label.
        val entry = ContactRelayStore.list().first { it.relay.server == r.server }
        assertTrue(entry.relay.tag.startsWith("shared-"))

        // Re-adding the same proto:server:port is a no-op (dedup) — count stable.
        val before = ContactRelayStore.count()
        assertFalse(ContactRelayStore.add(r, fromUin = 1, fromName = "x"))
        assertEquals(before, ContactRelayStore.count())

        ContactRelayStore.remove(entry.relay.tag)
        assertFalse(ContactRelayStore.has(r))
    }
}
