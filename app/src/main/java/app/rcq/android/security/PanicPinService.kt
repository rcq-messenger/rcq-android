package app.rcq.android.security

import android.content.Context
import app.rcq.android.crypto.BiometricVault
import app.rcq.android.crypto.PinVault
import app.rcq.android.data.SecureStore
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Panic-PIN lock state + dataKey custody (Phase 1: real PIN only). Android port
 * of the real-mode slice of iOS `PanicPINService`. Holds the in-memory unlocked
 * `dataKey` (the SQLCipher passphrase source for every account's message DB)
 * and the app lock state. The DB rekeying on set/change/remove is orchestrated
 * by [app.rcq.android.Session]; this object owns the vault + state only.
 *
 * Decoy / wipe / biometric are deferred to later phases — but [submit] already
 * routes a wipe-slot match to [SubmitResult.WIPE], so adding those modes later
 * won't reshuffle the vault.
 */
object PanicPinService {
    enum class SubmitResult { REAL, DECOY, WIPE, WRONG, LOCKED_OUT }

    /** Set on a DECOY submit: the account the caller should switch to + hide
     *  the rest behind. Consumed by [Session] right after [submit]. */
    @Volatile
    var pendingDecoyAccountId: String? = null
        private set

    @Volatile
    var dataKey: ByteArray? = null
        private set

    /** True when a PIN is configured and the app has not been unlocked this
     *  foreground session. The UI gates on this to show the lock screen. */
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    val isLocked: Boolean get() = _locked.value

    private var realPayload: PinVault.SlotPayload? = null
    // The real slot's derived key, kept while unlocked so we can re-seal the
    // real slot (e.g. to record a new decoy/wipe slot in its layout) without
    // re-deriving from the PIN.
    @Volatile
    private var realSlotKey: ByteArray? = null

    // Decoy session custody: while unlocked into the decoy (duress) view we
    // hold the decoy slot's payload + derived key so a "change PIN" from that
    // view re-seals the DECOY slot (never the real one). Both null in a real
    // session; cleared on lock. We never keep the real slot key in a decoy
    // session, so a coercer's UI actions can't reach the hidden accounts' slot.
    @Volatile
    private var decoyPayload: PinVault.SlotPayload? = null
    @Volatile
    private var decoySlotKey: ByteArray? = null

    private val gson = Gson()

    fun isConfigured(context: Context): Boolean = PinVault.isConfigured(context)

    /** Call once at process start: start locked iff a PIN is configured. */
    fun initLockState(context: Context) {
        _locked.value = PinVault.isConfigured(context)
        dataKey = null
        realPayload = null
        realSlotKey = null
        decoyPayload = null
        decoySlotKey = null
        pendingDecoyAccountId = null
    }

    /** Epoch-ms the lockout lasts until, or null if not locked out. */
    fun lockedOutUntil(context: Context): Long? =
        PinVault.loadAttempts(context).lockoutUntil?.takeIf { it > System.currentTimeMillis() }

    /** Try [pin] at the lock screen. On [SubmitResult.REAL] the dataKey is set
     *  and [locked] clears; the caller then (re)binds the message DB. */
    fun submit(context: Context, pin: String): SubmitResult {
        if (lockedOutUntil(context) != null) return SubmitResult.LOCKED_OUT
        val unlock = PinVault.unlock(context, pin)
        if (unlock == null) {
            val n = PinVault.loadAttempts(context).failedCount + 1
            val lo = PinVault.lockoutMillis(n)
            PinVault.saveAttempts(
                context,
                PinVault.AttemptState(n, if (lo > 0) System.currentTimeMillis() + lo else null),
            )
            return SubmitResult.WRONG
        }
        PinVault.clearAttempts(context)
        return when (unlock.payload.mode) {
            PinVault.MODE_REAL -> {
                realPayload = unlock.payload
                realSlotKey = unlock.slotKey
                dataKey = PinVault.dataKeyBytes(unlock.payload)
                _locked.value = false
                SubmitResult.REAL
            }
            PinVault.MODE_DECOY -> {
                // Unlock into the decoy account, hiding the real ones. The
                // decoy slot carries the SAME dataKey (so the encrypted DBs
                // open). We do NOT clear `locked` here — the caller
                // (Session.applyDecoyUnlock) switches + filters the roster
                // FIRST, then calls completeUnlock, so the real accounts never
                // flash on screen. Real PIN entry later exits decoy mode.
                dataKey = PinVault.dataKeyBytes(unlock.payload)
                decoyPayload = unlock.payload
                decoySlotKey = unlock.slotKey
                pendingDecoyAccountId = unlock.payload.decoyAccountId
                SubmitResult.DECOY
            }
            PinVault.MODE_WIPE -> SubmitResult.WIPE
            else -> SubmitResult.WRONG
        }
    }

    /** Verify [pin] opens the REAL slot, with NO unlock side effects (no dataKey
     *  swap, no decoy/wipe routing, no attempt-state change). Used to re-gate a
     *  sensitive surface (the recovery phrase) while the app is already unlocked.
     *  A decoy/wipe PIN returns false here — only the real PIN reveals the real
     *  account's phrase. */
    fun verifyRealPin(context: Context, pin: String): Boolean {
        val unlock = PinVault.unlock(context, pin) ?: return false
        return unlock.payload.mode == PinVault.MODE_REAL
    }

    /** Create the real PIN (no PIN currently). Returns the new vault dataKey,
     *  or null if [pin] is too short. The caller rekeys the message DBs from
     *  the device key to this one. */
    fun setRealPin(context: Context, pin: String): ByteArray? {
        if (pin.length < PinVault.MIN_PIN_LENGTH) return null
        val unlock = PinVault.createWithRealPin(context, pin)
        realPayload = unlock.payload
        realSlotKey = unlock.slotKey
        dataKey = PinVault.dataKeyBytes(unlock.payload)
        _locked.value = false
        return dataKey
    }

    /** Is a wipe PIN configured? (Only meaningful while unlocked-real.) */
    fun hasWipePin(): Boolean = realPayload?.layout?.wipeSlot != null

    /** Add a wipe PIN: entering it at the lock screen erases everything (the
     *  caller acts on [SubmitResult.WIPE]). Seals a WIPE slot under [pin] and
     *  records it in the real slot's layout. Requires an unlocked real session;
     *  [pin] must differ from the real/decoy PINs and a slot must be free. */
    fun setWipePin(context: Context, pin: String): Boolean {
        if (pin.length < PinVault.MIN_PIN_LENGTH) return false
        if (BiometricVault.isEnabled(context)) return false // mutually exclusive
        val real = realPayload ?: return false
        val key = realSlotKey ?: return false
        val layout = real.layout ?: return false
        val slot = PinVault.addSlot(context, pin, PinVault.SlotPayload(mode = PinVault.MODE_WIPE), layout) ?: return false
        layout.wipeSlot = slot
        PinVault.writeSlot(context, layout.realSlot, real, key) // re-seal real with the updated layout
        return true
    }

    /** Remove the wipe PIN (clears its slot back to random, drops it from the
     *  real layout). */
    fun removeWipePin(context: Context): Boolean {
        val real = realPayload ?: return false
        val key = realSlotKey ?: return false
        val layout = real.layout ?: return false
        val slot = layout.wipeSlot ?: return true
        PinVault.writeSlot(context, slot, null, null)
        layout.wipeSlot = null
        PinVault.writeSlot(context, layout.realSlot, real, key)
        return true
    }

    /** Is a decoy PIN configured, and which account does it reveal? */
    fun hasDecoyPin(): Boolean = realPayload?.layout?.decoySlot != null
    fun decoyAccountId(): String? = realPayload?.layout?.decoyAccountId

    /** Add a decoy PIN that unlocks into [decoyAccountId], hiding the other
     *  (real) accounts. The decoy slot carries the SAME dataKey as the real
     *  one (so the encrypted DBs open), plus the decoy account id. Requires an
     *  unlocked real session with a free slot. */
    fun setDecoyPin(context: Context, pin: String, decoyAccountId: String): Boolean {
        if (pin.length < PinVault.MIN_PIN_LENGTH) return false
        if (BiometricVault.isEnabled(context)) return false // mutually exclusive
        val real = realPayload ?: return false
        val key = realSlotKey ?: return false
        val dk = dataKey ?: return false
        val layout = real.layout ?: return false
        val payload = PinVault.SlotPayload(
            mode = PinVault.MODE_DECOY,
            dataKeyB64 = android.util.Base64.encodeToString(dk, android.util.Base64.NO_WRAP),
            decoyAccountId = decoyAccountId,
        )
        val slot = PinVault.addSlot(context, pin, payload, layout) ?: return false
        layout.decoySlot = slot
        layout.decoyAccountId = decoyAccountId
        PinVault.writeSlot(context, layout.realSlot, real, key)
        return true
    }

    fun removeDecoyPin(context: Context): Boolean {
        val real = realPayload ?: return false
        val key = realSlotKey ?: return false
        val layout = real.layout ?: return false
        val slot = layout.decoySlot ?: return true
        PinVault.writeSlot(context, slot, null, null)
        layout.decoySlot = null
        layout.decoyAccountId = null
        PinVault.writeSlot(context, layout.realSlot, real, key)
        return true
    }

    /** Take + clear the pending decoy account id (after a DECOY submit). */
    fun consumeDecoyAccountId(): String? {
        val id = pendingDecoyAccountId
        pendingDecoyAccountId = null
        return id
    }

    /** Clear the lock — called by [Session.applyDecoyUnlock] only AFTER it has
     *  switched to the decoy account + filtered the roster, so the UI never
     *  flashes the real accounts. */
    fun completeUnlock() { _locked.value = false }

    // ── biometric unlock (phase 4) ───────────────────────────────────

    /** Can biometric unlock be enabled right now? Hardware present and no
     *  duress PIN configured (biometric reveals the real account, so it's
     *  mutually exclusive with the decoy/wipe duress modes — same as iOS). */
    fun canEnableBiometric(context: Context): Boolean =
        BiometricVault.isHardwareAvailable(context) && !hasDecoyPin() && !hasWipePin()

    fun biometricEnabled(context: Context): Boolean = BiometricVault.isEnabled(context)

    /** Pure hardware/enrollment check (ignores duress-PIN exclusivity), so the
     *  Settings UI can show a biometric row even while a duress PIN is set. */
    fun biometricHardwareAvailable(context: Context): Boolean =
        BiometricVault.isHardwareAvailable(context)

    /** The real-slot payload (dataKey + layout) as a JSON blob, for sealing
     *  behind the biometric Keystore key. Only meaningful while unlocked-real. */
    fun realPayloadBlob(): ByteArray? =
        realPayload?.let { gson.toJson(it).toByteArray(Charsets.UTF_8) }

    fun disableBiometric(context: Context) = BiometricVault.disable(context)

    /** Unlock from a biometric-decrypted real-slot [blob] (no PIN entered).
     *  Mirrors iOS `unlockWithBiometrics`: restores the dataKey + real session
     *  and clears the lock. [realSlotKey] stays null — adding a decoy/wipe PIN
     *  needs a PIN entry, but those are blocked while biometric is on anyway. */
    fun applyBiometricUnlock(context: Context, blob: ByteArray): Boolean {
        val payload = runCatching {
            gson.fromJson(String(blob, Charsets.UTF_8), PinVault.SlotPayload::class.java)
        }.getOrNull() ?: return false
        if (payload.mode != PinVault.MODE_REAL) return false
        val key = PinVault.dataKeyBytes(payload) ?: return false
        realPayload = payload
        realSlotKey = null
        dataKey = key
        PinVault.clearAttempts(context)
        _locked.value = false
        return true
    }

    /** Re-seal the real slot under [newPin]; the dataKey (and the DB) are
     *  unchanged. Requires an unlocked real session. */
    fun changeRealPin(context: Context, newPin: String): Boolean {
        if (newPin.length < PinVault.MIN_PIN_LENGTH) return false
        val payload = realPayload ?: return false
        val realSlot = payload.layout?.realSlot ?: return false
        return runCatching { PinVault.reSealSlot(context, realSlot, payload, newPin) }.isSuccess
    }

    /** True while unlocked into the decoy (duress) view. */
    val inDecoySession: Boolean get() = decoySlotKey != null

    /** Change the PIN from within a decoy session: re-seal the DECOY slot under
     *  [newPin]. The real slot is untouched, so the hidden accounts stay hidden
     *  and their PIN unchanged — a coercer who was given the decoy PIN can
     *  change "their" PIN without it ever exposing (or hinting at) the real
     *  accounts. Rejects a [newPin] that collides with another slot. */
    fun changeDecoyPin(context: Context, newPin: String): Boolean {
        if (newPin.length < PinVault.MIN_PIN_LENGTH) return false
        val payload = decoyPayload ?: return false
        val oldKey = decoySlotKey ?: return false
        val newKey = PinVault.reSealUnderNewPin(context, oldKey, payload, newPin) ?: return false
        decoySlotKey = newKey
        return true
    }

    /** Remove the PIN entirely. Returns the device key the message DBs revert
     *  to (the caller rekeys them back). Also drops any biometric unlock — its
     *  sealed blob is meaningless once the vault is gone. */
    fun removePin(context: Context): ByteArray {
        PinVault.destroy(context)
        BiometricVault.disable(context)
        dataKey = null
        realPayload = null
        realSlotKey = null
        decoyPayload = null
        decoySlotKey = null
        pendingDecoyAccountId = null
        _locked.value = false
        return SecureStore.deviceKey(context)
    }

    /** Re-lock when the app goes to background (only if a PIN is configured). */
    fun lock(context: Context) {
        if (!PinVault.isConfigured(context)) return
        dataKey = null
        realPayload = null
        realSlotKey = null
        decoyPayload = null
        decoySlotKey = null
        pendingDecoyAccountId = null
        _locked.value = true
    }
}
