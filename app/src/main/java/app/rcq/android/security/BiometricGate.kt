package app.rcq.android.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.rcq.android.crypto.BiometricVault

/**
 * Drives the [BiometricPrompt] UI for the two panic-PIN biometric flows. Lives
 * apart from [BiometricVault] (the crypto) because the prompt must be shown from
 * a [FragmentActivity] on the main thread; everything here is UI glue.
 */
object BiometricGate {

    /** Show the prompt to ENABLE biometric unlock: a success authorises a fresh
     *  encrypt cipher that seals [payload] (the real-slot blob). [onResult] gets
     *  true on success, false on cancel/error. Runs on the main thread. */
    fun enable(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negative: String,
        payload: ByteArray,
        onResult: (Boolean) -> Unit,
    ) {
        val cipher = try {
            BiometricVault.encryptCipher()
        } catch (e: Exception) {
            onResult(false); return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (c == null) { onResult(false); return }
                    val ok = runCatching {
                        BiometricVault.persist(activity, c, payload); true
                    }.getOrDefault(false)
                    onResult(ok)
                }

                override fun onAuthenticationError(code: Int, msg: CharSequence) = onResult(false)
                // A single non-match isn't terminal; the prompt keeps retrying.
                override fun onAuthenticationFailed() {}
            },
        )
        prompt.authenticate(promptInfo(title, subtitle, negative), BiometricPrompt.CryptoObject(cipher))
    }

    /** How an unlock prompt ended, or why it never started. */
    sealed interface Outcome {
        /** A real match: the sealed real-slot blob, decrypted. */
        class Unlocked(val blob: ByteArray) : Outcome
        /** Closed without a match and without a fault: "Use PIN", back, or the
         *  system took it down (the app left the screen). [byPerson] when the
         *  person closed it themselves. */
        class Dismissed(val byPerson: Boolean) : Outcome
        /** The prompt could not run, or stopped on an error: no sensor free,
         *  too many tries, nothing enrolled. [message] is the system's own
         *  sentence when it gave one. */
        class Failed(val message: String?) : Outcome
        /** The phone's biometrics changed and the key went with them:
         *  biometric unlock is off now and has to be turned on again. */
        object KeyReset : Outcome
        /** Not asked: the activity is not in front. See [unlock]. */
        object NotReady : Outcome
    }

    /** Show the prompt to UNLOCK with biometrics; [onResult] is called exactly
     *  once, on the main thread, with how it ended.
     *
     *  ⚠⚠ Never silently (#1049: "the sensor does not light up, there is
     *  nowhere to put a finger"). Every way this used to end without a word
     *  now comes back as an [Outcome] the caller can act on:
     *  - androidx.biometric 1.1.0 drops a request made after the activity's
     *    state was saved (it logs "Called after onSaveInstanceState()" and
     *    returns, no callback at all), and the lock screen, waiting for that
     *    callback, kept its fingerprint button disabled for good. Asked while
     *    the activity is not resumed, this answers [Outcome.NotReady] instead
     *    of asking; the caller asks again when it is.
     *  - A key invalidated by a change of enrolled fingerprints, and any other
     *    keystore refusal, were the same `null` as a cancel. Now
     *    [Outcome.KeyReset] and [Outcome.Failed].
     *  - Hardware that cannot serve a strong biometric right now (busy, locked
     *    out, nothing enrolled) is asked first, and said. */
    fun unlock(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negative: String,
        onResult: (Outcome) -> Unit,
    ) {
        var done = false
        val once: (Outcome) -> Unit = { o -> if (!done) { done = true; onResult(o) } }
        if (activity.supportFragmentManager.isStateSaved ||
            !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        ) {
            once(Outcome.NotReady); return
        }
        val can = BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        // Every fingerprint removed: the key needed a strong biometric and is
        // dead with them, so this is the "turn it back on" case, not a sensor
        // that is merely busy (review of 9295bd6: it said "unavailable right
        // now" for ever).
        if (can == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            BiometricVault.disable(activity)
            once(Outcome.KeyReset); return
        }
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            android.util.Log.w("RCQbio", "strong biometric unavailable: $can")
            once(Outcome.Failed(null)); return
        }
        val cipher = when (val d = BiometricVault.decryptCipher(activity)) {
            is BiometricVault.Decrypt.Ready -> d.cipher
            BiometricVault.Decrypt.Gone -> { once(Outcome.KeyReset); return }
            is BiometricVault.Decrypt.Broken -> {
                android.util.Log.w("RCQbio", "decrypt cipher refused", d.error)
                once(Outcome.Failed(null)); return
            }
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val blob = result.cryptoObject?.cipher?.let { BiometricVault.open(activity, it) }
                    once(if (blob != null) Outcome.Unlocked(blob) else Outcome.Failed(null))
                }

                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    android.util.Log.i("RCQbio", "prompt ended: $code")
                    once(outcomeOfError(code, msg))
                }

                // A single non-match isn't terminal; the prompt keeps retrying.
                override fun onAuthenticationFailed() {}
            },
        )
        prompt.authenticate(promptInfo(title, subtitle, negative), BiometricPrompt.CryptoObject(cipher))
    }

    /** What an [BiometricPrompt.AuthenticationCallback.onAuthenticationError]
     *  means for the person: closing it themselves ("Use PIN", back) is a
     *  choice and says nothing; the system taking it down (the app left the
     *  screen) says nothing either; anything else (locked out, no sensor
     *  free, nothing enrolled, a vendor error) is a failure worth a sentence,
     *  in the system's own words when there are any. */
    internal fun outcomeOfError(code: Int, msg: CharSequence?): Outcome = when (code) {
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_USER_CANCELED -> Outcome.Dismissed(byPerson = true)
        BiometricPrompt.ERROR_CANCELED -> Outcome.Dismissed(byPerson = false)
        else -> Outcome.Failed(msg?.toString()?.takeIf { it.isNotBlank() })
    }

    private fun promptInfo(title: String, subtitle: String, negative: String) =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negative)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
}
