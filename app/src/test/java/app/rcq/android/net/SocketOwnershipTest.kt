package app.rcq.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard behind every socket callback in Session: a callback from a
 * socket the Session no longer holds is dropped, and the check reads the
 * field LIVE, at callback time.
 *
 * ⚠ The failure this pins down is the self-hosted tester's switch-back bug:
 * the socket for island A was replaced by one for island B while A's socket
 * was still connected, and A's onState kept writing "connected" for a Session
 * that had moved on. Identity, not equality: two sockets to the same host are
 * still two sockets.
 */
class SocketOwnershipTest {

    private class FakeSocket(val host: String)

    @Test fun callbackFromTheHeldSocketRuns() {
        val a = FakeSocket("island-a")
        var held: FakeSocket? = a
        val owned = SocketOwnership<FakeSocket> { held }
        assertTrue(owned.stillOwns(a))
        assertEquals("ran", owned.ifOwned(a) { "ran" })
    }

    @Test fun callbackFromAReplacedSocketIsDropped() {
        val a = FakeSocket("island-a")
        val b = FakeSocket("island-b")
        var held: FakeSocket? = a
        val owned = SocketOwnership<FakeSocket> { held }
        // The Session rebuilt its socket for another island; A is still alive
        // and still calling back.
        held = b
        assertFalse(owned.stillOwns(a))
        assertNull(owned.ifOwned(a) { "must not run" })
        assertTrue(owned.stillOwns(b))
    }

    @Test fun readsTheFieldAtCallbackTimeNotAtRegistration() {
        val a = FakeSocket("island-a")
        val b = FakeSocket("island-b")
        var held: FakeSocket? = a
        val owned = SocketOwnership<FakeSocket> { held }
        // Registered while A was held, delivered after the swap and after
        // the swap back: the answer follows the field, not history.
        held = b
        assertFalse(owned.stillOwns(a))
        held = a
        assertTrue(owned.stillOwns(a))
        assertFalse(owned.stillOwns(b))
    }

    @Test fun identityNotEquality() {
        val a1 = FakeSocket("same-host")
        val a2 = FakeSocket("same-host")
        val owned = SocketOwnership<FakeSocket> { a2 }
        assertFalse(owned.stillOwns(a1))
        assertTrue(owned.stillOwns(a2))
    }

    @Test fun nothingHeldOwnsNothing() {
        val a = FakeSocket("island-a")
        val owned = SocketOwnership<FakeSocket> { null }
        assertFalse(owned.stillOwns(a))
        var ran = false
        owned.ifOwned(a) { ran = true }
        assertFalse(ran)
    }
}
