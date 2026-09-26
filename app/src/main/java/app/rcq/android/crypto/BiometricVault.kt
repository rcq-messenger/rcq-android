package app.rcq.android.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Biometric unlock store — Android port of the iOS `BiometricUnlock`. Wraps the
 * panic-PIN real-slot payload (the [PinVault.SlotPayload] carrying the dataKey)
 * behind a biometric-gated AndroidKeyStore key so the app can unlock with a
 * fingerprint/face instead of the PIN.
 *
 * The Keystore key requires user authentication for every use ([Cipher] must be
 * presented to a `BiometricPrompt` via a `CryptoObject`), so the encrypted blob
 * is useless without a live biometric match — the equivalent of iOS's
 * `.biometryCurrentSet` Keychain item. The key is also invalidated whenever the
 * device's biometric enrollment changes, so re-enrolling a fingerprint forces a
 * re-enable (and a fresh PIN entry to recover the payload).
 *
 * The ciphertext + IV live in a plain prefs file — they're already
 * authenticated-encrypted under the Keystore key, so they need no further
 * at-rest layer.
 */
object BiometricVault {
    private const val KEY_ALIAS = "rcq_pin_biometric"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val PREFS = "rcq_pin_bio"
    private const val K_BLOB = "blob"
    private const val K_IV = "iv"

    /** Hardware + enrollment present for a strong biometric. */
    fun isHardwareAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG,
        ) == BiometricManager.BIOMETRIC_SUCCESS

    /** A biometric unlock blob has been stored (the user enabled it). */
    fun isEnabled(context: Context): Boolean =
        prefs(context).contains(K_BLOB) && keyExists()

    // ── enable: BiometricPrompt authorises [encryptCipher], then [persist] ──

    /** A fresh ENCRYPT cipher bound to a newly (re)generated biometric key. The
     *  caller presents it to BiometricPrompt; on success calls [persist]. */
    fun encryptCipher(): Cipher {
        deleteKey()
        val key = generateKey()
        return Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** After the prompt authorises [cipher], seal [payload] and store it. */
    fun persist(context: Context, cipher: Cipher, payload: ByteArray) {
        val ct = cipher.doFinal(payload)
        prefs(context).edit()
            .putString(K_BLOB, Base64.encodeToString(ct, Base64.NO_WRAP))
            .putString(K_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    // ── unlock: BiometricPrompt authorises [decryptCipher], then [open] ──

    /** What [decryptCipher] found. */
    sealed interface Decrypt {
        class Ready(val cipher: Cipher) : Decrypt
        /** Nothing to unlock with any more: no stored blob or no key, or the
         *  key was invalidated because the phone's biometrics changed. The
         *  stale blob is gone too, so [isEnabled] now reads false. */
        object Gone : Decrypt
        /** The keystore refused for another reason. The blob is kept: this
         *  may pass (a keystore that is not up yet after a boot). */
        class Broken(val error: Exception) : Decrypt
    }

    /** A DECRYPT cipher initialised with the stored IV, or why there is none.
     *
     *  ⚠ Each outcome is told apart (#1049). All three used to be one `null`,
     *  and the lock screen treated it as "nothing happened": the fingerprint
     *  button stayed, a tap on it did nothing at all, not even a word, and
     *  the sensor never lit. */
    fun decryptCipher(context: Context): Decrypt {
        val ivB64 = prefs(context).getString(K_IV, null) ?: return Decrypt.Gone
        val key = loadKey() ?: run { disable(context); return Decrypt.Gone }
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        return try {
            Decrypt.Ready(
                Cipher.getInstance(TRANSFORM).apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                },
            )
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Re-enrollment wiped the key — drop the stale blob so isEnabled()
            // reads false and the lock screen offers the PIN only.
            disable(context)
            Decrypt.Gone
        } catch (e: Exception) {
            Decrypt.Broken(e)
        }
    }

    /** After the prompt authorises [cipher], decrypt the stored blob. */
    fun open(context: Context, cipher: Cipher): ByteArray? {
        val ctB64 = prefs(context).getString(K_BLOB, null) ?: return null
        return runCatching { cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)) }.getOrNull()
    }

    /** Forget the biometric unlock (disable, PIN removed, or duress wipe). */
    fun disable(context: Context) {
        deleteKey()
        prefs(context).edit().remove(K_BLOB).remove(K_IV).apply()
    }

    // ── keystore plumbing ─────────────────────────────────────────────
    private fun generateKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // Drop the key (and the stored blob becomes useless) when the
            // device's biometric set changes — mirrors iOS .biometryCurrentSet.
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        0, // 0s validity = authenticate for every single use
                        KeyProperties.AUTH_BIOMETRIC_STRONG,
                    )
                }
                // Pre-R: setUserAuthenticationRequired(true) alone already means
                // every use needs a CryptoObject-backed auth.
            }
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun keyExists(): Boolean =
        runCatching { keyStore().containsAlias(KEY_ALIAS) }.getOrDefault(false)

    private fun loadKey(): SecretKey? =
        runCatching { keyStore().getKey(KEY_ALIAS, null) as? SecretKey }.getOrNull()

    private fun deleteKey() {
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
