package app.rcq.android.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A SignalMessage on a session state we already hold opens whatever the pin
 * says now; a new state (a PreKeySignalMessage) still has to clear the pin or
 * the island's vouch, and an established receive never moves the pin.
 */
class IdentityTrustRuleTest {

    private val R = IdentityTrustRule

    @Test fun an_established_session_opens_after_our_send_moved_the_pin() {
        assertTrue(R.trusted(sending = false, established = true, pinnedMatches = false, admitted = false))
    }

    @Test fun a_new_session_needs_the_pin_or_the_islands_vouch() {
        assertTrue(R.trusted(sending = false, established = false, pinnedMatches = true, admitted = false))
        assertTrue(R.trusted(sending = false, established = false, pinnedMatches = false, admitted = true))
        assertFalse(R.trusted(sending = false, established = false, pinnedMatches = false, admitted = false))
        // A first identity: the caller checks it against the island first.
        assertTrue(R.trusted(sending = false, established = false, pinnedMatches = null, admitted = false))
        // Our own send builds from a bundle the island served.
        assertTrue(R.trusted(sending = true, established = false, pinnedMatches = false, admitted = false))
    }

    @Test fun an_established_receive_never_pulls_the_pin_back() {
        assertTrue(R.keepsPin(established = true, hasPin = true))
        // A first identity is still pinned, and a new session still saves.
        assertFalse(R.keepsPin(established = true, hasPin = false))
        assertFalse(R.keepsPin(established = false, hasPin = true))
    }
}
