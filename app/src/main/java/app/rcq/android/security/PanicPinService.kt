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

    /** Set on a LEGACY DECOY submit: the roster account the caller should
     *  switch to + hide the rest behind. Only ever non-null for a vault written
     *  before the decoy got its own store. Consumed by [Session] after [submit]. */
    @Volatile
    var pendingDecoyAccountId: String? = null
        private set

    /** Set on a WIPE submit: did that slot ask for the server-side account to
     *  be erased too? Read from the WIPE SLOT PAYLOAD and nowhere else — prefs
     *  can be flipped by anyone holding an unlocked phone. Absent on an old
     *  slot, which decodes as null and therefore FALSE. */
    @Volatile
    private var pendingWipeServer: Boolean = false

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
        pendingWipeServer = false
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
                // The decoy slot carries its OWN dataKey, which opens exactly
                // one file — the decoy store — and nothing else on the device.
                // (A LEGACY slot, written before this change, still carries the
                // real key and a roster account id; [decoyIsLegacy] tells the
                // caller which of the two it is holding.)
                //
                // We do NOT clear `locked` here — the caller
                // (Session.applyDecoyUnlock) raises the decoy view FIRST, then
                // calls completeUnlock, so the real accounts never flash on
                // screen. Real PIN entry later exits decoy mode.
                dataKey = PinVault.dataKeyBytes(unlock.payload)
                decoyPayload = unlock.payload
                decoySlotKey = unlock.slotKey
                pendingDecoyAccountId = unlock.payload.decoyAccountId
                SubmitResult.DECOY
            }
            PinVault.MODE_WIPE -> {
                pendingWipeServer = unlock.payload.wipeServer == true
                SubmitResult.WIPE
            }
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
        // ⚠⚠ R1, enforced here and not only in the UI (iOS has always had this
        // guard in setRealPIN; Android relied on the Settings screen's
        // `configured` flag, which is a remembered snapshot).
        //
        // [PinVault.createWithRealPin] regenerates the pepper and mints a NEW
        // dataKey. Run against an existing vault it would leave every account's
        // SQLCipher file encrypted under a key that no longer exists anywhere,
        // and the caller would then "rekey" them FROM the device key — which is
        // not the key they are under — so every open fails. That is the whole
        // history of every account with a PIN, gone and unrecoverable.
        if (PinVault.isConfigured(context)) return null
        val unlock = PinVault.createWithRealPin(context, pin)
        realPayload = unlock.payload
        realSlotKey = unlock.slotKey
        dataKey = PinVault.dataKeyBytes(unlock.payload)
        _locked.value = false
        return dataKey
    }

    /** Is a wipe PIN configured? (Only meaningful while unlocked-real.) */
    fun hasWipePin(): Boolean = realPayload?.layout?.wipeSlot != null

    /** Does the configured wipe PIN also erase the account server-side?
     *  DISPLAY only, read from the real slot's layout mirror — the wipe itself
     *  reads the wipe slot's own copy (see [consumeWipeServer]). */
    fun wipeErasesServer(): Boolean = realPayload?.layout?.wipeServer == true

    /** Take + clear the "also erase server-side" flag carried by the wipe slot
     *  that was just entered. Defaults to FALSE: an absent flag in an old slot
     *  decodes as null, and the UI has only ever promised "on this device". */
    fun consumeWipeServer(): Boolean {
        val v = pendingWipeServer
        pendingWipeServer = false
        return v
    }

    /** Add a wipe PIN: entering it at the lock screen erases everything (the
     *  caller acts on [SubmitResult.WIPE]). Seals a WIPE slot under [pin] and
     *  records it in the real slot's layout. Requires an unlocked real session;
     *  [pin] must differ from the real/decoy PINs and a slot must be free.
     *
     *  [eraseServer] rides in the WIPE SLOT payload, so it cannot be flipped
     *  without that PIN. Default off, matching what every locale's copy says. */
    fun setWipePin(context: Context, pin: String, eraseServer: Boolean = false): Boolean {
        if (pin.length < PinVault.MIN_PIN_LENGTH) return false
        if (BiometricVault.isEnabled(context)) return false // mutually exclusive
        val real = realPayload ?: return false
        val key = realSlotKey ?: return false
        val layout = real.layout ?: return false
        val wipePayload = PinVault.SlotPayload(
            mode = PinVault.MODE_WIPE,
            // Only write the flag when it is ON: a null costs no bytes, and the
            // slot budget is fixed.
            wipeServer = if (eraseServer) true else null,
        )
        // Check the REAL slot's new size before anything is written: the layout
        // grows by the mirror below, and a payload that no longer fits must
        // leave the vault exactly as it was rather than half-updated.
        val previousWipeServer = layout.wipeServer
        layout.wipeServer = if (eraseServer) true else null
        if (!PinVault.payloadFits(real)) { layout.wipeServer = previousWipeServer; return false }
        val slot = PinVault.addSlot(context, pin, wipePayload, layout)
        if (slot == null) { layout.wipeServer = previousWipeServer; return false }
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
        layout.wipeServer = null
        PinVault.writeSlot(context, layout.realSlot, real, key)
        return true
    }

    /** Is a decoy PIN configured? */
    fun hasDecoyPin(): Boolean = realPayload?.layout?.decoySlot != null

    /** LEGACY decoy still in place: the configured decoy names a roster account
     *  and its slot carries the REAL dataKey. These vaults keep working exactly
     *  as before until the user passes the one-time migration screen — a decoy
     *  PIN that stops working under coercion is worse than an old one. */
    fun decoyNeedsMigration(): Boolean {
        val layout = realPayload?.layout ?: return false
        return layout.decoySlot != null && layout.decoyDataKeyB64 == null
    }

    /** The account a LEGACY decoy reveals (null once migrated). */
    fun decoyAccountId(): String? = realPayload?.layout?.decoyAccountId

    /** The decoy's own key + synthetic identity, as recorded in the real slot.
     *  Only an unlocked REAL session can read this. */
    data class DecoyIdentity(val dataKey: ByteArray, val uin: Int, val nickname: String)

    /** Mint (or re-use) the decoy's own key + identity for a rebuild. Reusing
     *  them when they already exist keeps the same duress identity across a
     *  re-pick of conversations, which is what a returning coercer would
     *  expect to see. Requires an unlocked real session with a writable real
     *  slot — biometrics deliberately do not hold [realSlotKey]. */
    fun prepareDecoyIdentity(context: Context): DecoyIdentity? {
        if (BiometricVault.isEnabled(context)) return null // mutually exclusive
        val layout = realPayload?.layout ?: return null
        if (realSlotKey == null) return null
        val key = layout.decoyDataKeyB64
            ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
            ?: app.rcq.android.data.DecoyStore.newDataKey()
        return DecoyIdentity(
            dataKey = key,
            uin = layout.decoyUin ?: app.rcq.android.data.DecoyStore.randomUin(),
            nickname = layout.decoyNickname ?: app.rcq.android.data.DecoyStore.randomNickname(),
        )
    }

    /**
     * Seal the decoy slot under [pin] and record [id] in the real slot's
     * layout. The decoy slot carries [id]'s OWN dataKey — never the real one —
     * so the duress PIN opens the decoy store and nothing else.
     *
     * Call only after the decoy store has actually been seeded: a decoy PIN
     * that opens an empty view is the tell the feature exists to avoid.
     */
    fun commitDecoyPin(context: Context, pin: String, id: DecoyIdentity): Boolean {
        if (pin.length < PinVault.MIN_PIN_LENGTH) return false
        if (BiometricVault.isEnabled(context)) return false
        val real = realPayload ?: return false
        val key = realSlotKey ?: return false
        val layout = real.layout ?: return false
        val payload = PinVault.SlotPayload(
            mode = PinVault.MODE_DECOY,
            dataKeyB64 = android.util.Base64.encodeToString(id.dataKey, android.util.Base64.NO_WRAP),
            decoyUin = id.uin,
            decoyNickname = id.nickname,
        )
        // Re-point the EXISTING decoy slot when there is one (the old duress
        // PIN dies with the overwrite), otherwise take a free index.
        val index = layout.decoySlot ?: PinVault.freeSlotIndex(layout) ?: return false
        // Snapshot the layout so a refusal anywhere below leaves the vault
        // byte-identical rather than half-migrated.
        val prev = layout.copy()
        layout.decoySlot = index
        layout.decoyAccountId = null // the decoy is no longer a roster account
        layout.decoyDataKeyB64 = android.util.Base64.encodeToString(id.dataKey, android.util.Base64.NO_WRAP)
        layout.decoyUin = id.uin
        layout.decoyNickname = id.nickname
        if (!PinVault.payloadFits(real) || !PinVault.payloadFits(payload)) { restore(layout, prev); return false }
        // Nothing is written until this returns true.
        if (!PinVault.writeSlotWithPin(context, index, payload, pin)) { restore(layout, prev); return false }
        PinVault.writeSlot(context, layout.realSlot, real, key)
        return true
    }

    private fun restore(layout: PinVault.Layout, from: PinVault.Layout) {
        layout.decoySlot = from.decoySlot
        layout.decoyAccountId = from.decoyAccountId
        layout.decoyDataKeyB64 = from.decoyDataKeyB64
        layout.decoyUin = from.decoyUin
        layout.decoyNickname = from.decoyNickname
    }

    fun removeDecoyPin(context: Context): Boolean {
        val real = realPayload ?: return false
        val key = realSlotKey ?: return false
        val layout = real.layout ?: return false
        val slot = layout.decoySlot ?: return true
        PinVault.writeSlot(context, slot, null, null)
        layout.decoySlot = null
        layout.decoyAccountId = null
        layout.decoyDataKeyB64 = null
        layout.decoyUin = null
        layout.decoyNickname = null
        PinVault.writeSlot(context, layout.realSlot, real, key)
        return true
    }

    /** Take + clear the pending LEGACY decoy account id (after a DECOY submit).
     *  Null for a migrated decoy, which has no roster account at all. */
    fun consumeDecoyAccountId(): String? {
        val id = pendingDecoyAccountId
        pendingDecoyAccountId = null
        return id
    }

    /** True while the CURRENT decoy session came from a legacy slot (roster
     *  account + the real dataKey) rather than its own store. */
    val decoyIsLegacy: Boolean get() = decoyPayload?.decoyAccountId != null

    /** The synthetic identity the current decoy session presents. */
    fun decoySessionUin(): Int? = decoyPayload?.decoyUin
    fun decoySessionNickname(): String? = decoyPayload?.decoyNickname

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

    /** True while the real slot can be re-sealed, i.e. the real PIN was typed
     *  this session. A biometric unlock deliberately never holds the real slot
     *  key, which is why the decoy migration screen cannot be reached from one. */
    val canWriteRealSlot: Boolean get() = realSlotKey != null

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
        pendingWipeServer = false
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
        pendingWipeServer = false
        _locked.value = true
    }
}
