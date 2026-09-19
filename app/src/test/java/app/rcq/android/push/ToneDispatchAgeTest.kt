package app.rcq.android.push

import app.rcq.android.media.SoundService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a notification tone that waited is still worth playing (#1028).
 *
 * The report had two halves and this is the second one. The first is that the
 * chime was lost with the screen off, because the tone moved into our own
 * process in 0.191 and nothing held the CPU for it, so the device suspended
 * between the dispatch and MediaPlayer.prepare(); that half is a wake lock and
 * cannot be tested off a device. The second half is what the reporter heard
 * NEXT: the missing chimes arriving all at once when the screen came back on,
 * which is the dispatch queue draining. A tone is a statement that something
 * just happened, so a stale one is dropped rather than played late.
 *
 * Pinned here because the window is a judgement call that a later reader will be
 * tempted to tighten, and tightening it silently takes the chime away from every
 * cold wake, which is exactly the bug this was fixing.
 */
class ToneDispatchAgeTest {

    /** elapsedRealtime is an uptime-since-boot clock, so any base will do; a big
     *  one on purpose, since the real values are days into a boot. */
    private val at = 987_654_321L

    @Test fun a_tone_that_runs_immediately_plays() {
        assertTrue(SoundService.toneStillWanted(at, at))
    }

    @Test fun an_ordinary_warm_dispatch_plays() {
        // The measured typical prepare() is 50-110ms, so this is the common case
        // and it must never be near the limit.
        assertTrue(SoundService.toneStillWanted(at, at + 110))
    }

    /** ⚠ The worst measured cold `prepare()` was 865ms, once in eight wakes. The
     *  window exists to drop a queue drain, not to punish a cold media stack. */
    @Test fun the_worst_measured_cold_start_still_plays() {
        assertTrue(SoundService.toneStillWanted(at, at + 865))
    }

    @Test fun the_boundary_itself_plays() {
        assertTrue(SoundService.toneStillWanted(at, at + SoundService.TONE_DISPATCH_MAX_AGE_MS))
    }

    @Test fun one_millisecond_past_the_boundary_is_dropped() {
        assertFalse(SoundService.toneStillWanted(at, at + SoundService.TONE_DISPATCH_MAX_AGE_MS + 1))
    }

    /** The shape of the complaint: the device stayed suspended for a while and
     *  then the screen came on. elapsedRealtime counts that time, which is the
     *  whole reason this question can be asked at all. */
    @Test fun a_queue_drained_at_screen_on_is_dropped() {
        assertFalse(SoundService.toneStillWanted(at, at + 30_000))
        assertFalse(SoundService.toneStillWanted(at, at + 4 * 60 * 60 * 1000L))
    }

    /** ⚠ A negative age PLAYS. elapsedRealtime is monotonic so it should not
     *  happen, and "the clock did something we do not understand" must not be a
     *  reason to swallow the alert for an arriving message. */
    @Test fun a_clock_that_went_backwards_plays_rather_than_swallows() {
        assertTrue(SoundService.toneStillWanted(at, at - 5_000))
    }

    /** The window has to sit above the throttle: a tone that lost its gap was
     *  never dispatched, so this may only ever cut off a dispatch the DEVICE
     *  delayed. If these two ever cross, the throttle's own tests keep passing
     *  and the chime quietly stops. */
    @Test fun the_window_is_wider_than_the_throttle_gap() {
        assertTrue(SoundService.TONE_DISPATCH_MAX_AGE_MS > 1_200L)
    }
}
