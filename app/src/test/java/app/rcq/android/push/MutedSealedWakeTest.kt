package app.rcq.android.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Report 1072, cause 1: a muted 1:1 chat rang whenever its message came by
 * push, because the sealed wake cannot say who wrote. The alert now waits for
 * the session to open the envelope, but only where waiting cannot cost a
 * message its sound: every condition below is a case where it could.
 */
class MutedSealedWakeTest {

    private fun holds(
        sealedDirect: Boolean = true,
        quiet: Boolean = false,
        accountMutesSomeone: Boolean = true,
        drainWillOpen: Boolean = true,
        processStaysUp: Boolean = true,
    ) = Push.holdsForDrain(sealedDirect, quiet, accountMutesSomeone, drainWillOpen, processStaysUp)

    @Test fun aSealedWakeWaitsForTheSessionThatWillOpenIt() {
        assertTrue(holds())
    }

    @Test fun nobodyPaysTheWaitWithoutAMutedChat() {
        assertFalse(holds(accountMutesSomeone = false))
    }

    @Test fun anOpenedWakeOrAGroupWasDecidedAlready() {
        assertFalse(holds(sealedDirect = false))
    }

    @Test fun withNoSessionToOpenItTheWakeRingsAsBefore() {
        // A cold process, a PIN lock, or a wake for a sibling account.
        assertFalse(holds(drainWillOpen = false))
    }

    @Test fun aLockedOrDuressPhoneNeverWaits() {
        assertFalse(holds(quiet = true))
    }

    @Test fun aProcessTheSystemMayFreezeNeverHoldsAnAlert() {
        // Another distributor app handed the wake over: once it returns, this
        // process is cached and can be frozen before any deadline fires.
        assertFalse(holds(processStaysUp = false))
    }
}
