package app.rcq.android.net

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that the client can pull broker bridges from the LIVE prod
 * broker and that whatever comes back lands in the transport pool in the right
 * shape. Hits api.rcq.app/broker/bridges, so it is a real end-to-end check of
 * the broker client integration. See RCQ/docs/relay-broker-design.md.
 *
 * ⚠ It deliberately does NOT require the pool to be non-empty, and does not
 * name a particular relay. It used to assert that the Moscow relay
 * (45.151.101.221) was in the response, which turned this into a monitor of
 * prod's community pool rather than a test of the client: that relay was
 * retired in June, the pool has been empty since, and the test had been red
 * ever since while nothing was wrong with the code under test. An empty pool is
 * a legitimate state — every community relay can be dead at once, which is
 * exactly when the bundled list matters instead.
 */
@RunWith(AndroidJUnit4::class)
class BrokerRelayStoreTest {

    @Test
    fun fetchesBrokerBridgesFromProd() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        BrokerRelayStore.init(ctx)
        ContactRelayStore.init(ctx)
        BrokerRelayStore.refresh() // blocking GET against the live prod broker

        // Every relay that DID come back has to be usable: retagged so it cannot
        // collide with a signed-config tag, and carrying the fields its protocol
        // needs to build an outbound.
        for (r in BrokerRelayStore.relays()) {
            assertTrue("broker relay must be retagged, got ${r.tag}", r.tag.startsWith("broker-"))
            assertTrue("unknown proto ${r.proto}", r.proto == "vless" || r.proto == "hysteria2")
            assertTrue("empty server in $r", r.server.isNotBlank())
            assertTrue("bad port in $r", r.port in 1..65535)
            if (r.proto == "vless") {
                assertTrue("vless without uuid: $r", !r.uuid.isNullOrEmpty())
                assertTrue("vless without reality key: $r", !r.publicKey.isNullOrEmpty())
            } else {
                assertTrue("hysteria2 without password: $r", !r.password.isNullOrEmpty())
            }
        }
    }
}
