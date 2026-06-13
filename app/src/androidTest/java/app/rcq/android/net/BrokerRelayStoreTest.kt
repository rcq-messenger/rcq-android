package app.rcq.android.net

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that the client pulls broker bridges from the LIVE prod broker
 * and they land in the transport pool. Hits api.rcq.app/broker/bridges (the
 * Moscow relay is registered + enabled there), so this is a real end-to-end
 * check of the broker client integration. See RCQ/docs/relay-broker-design.md.
 */
@RunWith(AndroidJUnit4::class)
class BrokerRelayStoreTest {

    @Test
    fun fetchesBrokerBridgesFromProd() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        BrokerRelayStore.init(ctx)
        ContactRelayStore.init(ctx)
        BrokerRelayStore.refresh()   // blocking GET against the live prod broker
        val relays = BrokerRelayStore.relays()
        // The prod broker has the Moscow relay (45.151.101.221) enabled.
        assertTrue("expected a broker relay, got $relays", relays.isNotEmpty())
        val moscow = relays.firstOrNull { it.server == "45.151.101.221" }
        assertTrue("expected the Moscow relay in the broker set, got ${relays.map { it.server }}", moscow != null)
        // Retagged collision-proof + descriptor parsed (vless reality fields present).
        assertTrue(moscow!!.tag.startsWith("broker-"))
        assertTrue(moscow.proto == "vless" && !moscow.uuid.isNullOrEmpty() && !moscow.publicKey.isNullOrEmpty())
    }
}
