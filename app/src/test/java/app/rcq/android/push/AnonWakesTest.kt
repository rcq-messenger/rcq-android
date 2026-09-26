package app.rcq.android.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The anonymous "New message" slot and the drain that explains it (#1047).
 *
 * A wake we cannot open (every v=2 envelope) goes into one shared notification.
 * When the drain later opens the same ciphertext and finds a control envelope
 * (a caller's sealed `call_end`, a receipt), the notification was never about
 * a message; it comes down only if nothing else is keeping the slot up.
 */
class AnonWakesTest {

    @Before fun reset() = Push.AnonWakes.cleared()

    @Test fun the_only_wake_explained_takes_the_slot_down() {
        Push.AnonWakes.posted("ENV-END")
        assertTrue(Push.AnonWakes.explained("ENV-END"))
    }

    @Test fun another_unexplained_wake_keeps_it_up() {
        Push.AnonWakes.posted("ENV-END")
        Push.AnonWakes.posted("ENV-REAL-MESSAGE")
        assertFalse(Push.AnonWakes.explained("ENV-END"))
    }

    @Test fun a_wake_with_no_envelope_is_never_taken_down_from_here() {
        Push.AnonWakes.posted(null)
        Push.AnonWakes.posted("ENV-END")
        assertFalse(Push.AnonWakes.explained("ENV-END"))
    }

    @Test fun a_silent_row_that_was_never_posted_changes_nothing() {
        Push.AnonWakes.posted("ENV-REAL-MESSAGE")
        assertFalse(Push.AnonWakes.explained("ENV-OTHER"))
    }

    /** The other order: the socket drained the row before the wake arrived. */
    @Test fun a_wake_that_lands_after_the_drain_is_known_silent() {
        // Its own ciphertext: what the drain found silent is remembered across
        // a cleared slot on purpose, so the other tests' rows are in there.
        assertFalse(Push.AnonWakes.knownSilent("ENV-LATE-END"))
        Push.AnonWakes.explained("ENV-LATE-END")
        assertTrue(Push.AnonWakes.knownSilent("ENV-LATE-END"))
        assertFalse(Push.AnonWakes.knownSilent("ENV-LATE-MESSAGE"))
    }

    @Test fun clearing_the_slot_forgets_what_was_in_it() {
        Push.AnonWakes.posted(null)
        Push.AnonWakes.cleared()
        Push.AnonWakes.posted("ENV-END")
        assertTrue(Push.AnonWakes.explained("ENV-END"))
    }
}
