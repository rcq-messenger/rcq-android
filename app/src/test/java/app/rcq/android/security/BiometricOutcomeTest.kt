package app.rcq.android.security

import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [BiometricGate.outcomeOfError] (#1049): only the person's own "no" and the
 *  system taking the prompt down pass without a word; every other ending of a
 *  prompt is a failure the lock screen says out loud. */
class BiometricOutcomeTest {
    @Test fun usePinIsThePersonsChoice() {
        val o = BiometricGate.outcomeOfError(BiometricPrompt.ERROR_NEGATIVE_BUTTON, "Use PIN")
        assertTrue(o is BiometricGate.Outcome.Dismissed && o.byPerson)
    }

    @Test fun backIsThePersonsChoice() {
        val o = BiometricGate.outcomeOfError(BiometricPrompt.ERROR_USER_CANCELED, "Cancelled")
        assertTrue(o is BiometricGate.Outcome.Dismissed && o.byPerson)
    }

    @Test fun systemCancelIsNotThePersons() {
        val o = BiometricGate.outcomeOfError(BiometricPrompt.ERROR_CANCELED, "Canceled")
        assertTrue(o is BiometricGate.Outcome.Dismissed && !o.byPerson)
    }

    @Test fun lockoutIsSaidInTheSystemsWords() {
        val o = BiometricGate.outcomeOfError(BiometricPrompt.ERROR_LOCKOUT, "Too many attempts. Try again later.")
        assertEquals("Too many attempts. Try again later.", (o as BiometricGate.Outcome.Failed).message)
    }

    @Test fun hardwareUnavailableIsAFailure() {
        val o = BiometricGate.outcomeOfError(BiometricPrompt.ERROR_HW_UNAVAILABLE, "")
        assertTrue(o is BiometricGate.Outcome.Failed)
        assertNull((o as BiometricGate.Outcome.Failed).message)
    }

    @Test fun vendorErrorIsAFailure() {
        assertTrue(BiometricGate.outcomeOfError(BiometricPrompt.ERROR_VENDOR, "Sensor busy") is BiometricGate.Outcome.Failed)
    }
}
