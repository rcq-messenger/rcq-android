package app.rcq.android.crypto

import android.content.Context
import com.google.gson.Gson
import app.rcq.android.data.SecureStore
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN vault — Android port of the iOS `PINVault`. A single `pin-vault.bin`
 * file holds a fixed salt and exactly [SLOT_COUNT] equal-size slots; each slot
 * is AES-GCM-sealed under a key derived from a PIN. Any PIN derives one key and
 * tries to open every slot; the one that decrypts (valid GCM tag) is the match.
 * Unused slots are random bytes indistinguishable from sealed ones, so the file
 * never reveals how many PINs exist or which mode each is — that's what makes
 * decoy / wipe PINs deniable.
 *
 * The slot's payload carries a random 32-byte `dataKey` (the SQLCipher
 * passphrase for the message DB) and, on the real slot, a `layout` recording
 * which slot is real/decoy/wipe. Changing the PIN re-seals the slot but keeps
 * the same dataKey, so it never re-encrypts the message DB.
 *
 * This vault is LOCAL only (never crosses to another device), so the KDF need
 * not match iOS byte-for-byte; the pepper (a random key in the Keystore-wrapped
 * prefs) is folded into the PBKDF2 salt so the file alone can't be brute-forced.
 */
object PinVault {
    const val MODE_REAL = 1
    const val MODE_DECOY = 2
    const val MODE_WIPE = 3
    const val MIN_PIN_LENGTH = 4

    private const val VERSION = 1
    private const val SALT_LEN = 16
    private const val PAYLOAD_LEN = 320
    // MediaCrypto layout: nonce(12) || ciphertext(PAYLOAD_LEN) || tag(16).
    private const val SLOT_LEN = 12 + PAYLOAD_LEN + 16
    private const val SLOT_COUNT = 3
    private const val KDF_ROUNDS = 400_000
    private const val FILE_NAME = "pin-vault.bin"

    private val gson = Gson()
    private val rng = SecureRandom()

    data class Layout(
        var realSlot: Int,
        var decoySlot: Int? = null,
        var wipeSlot: Int? = null,
        var decoyAccountId: String? = null,
    )

    data class SlotPayload(
        val mode: Int,
        val dataKeyB64: String? = null,
        var layout: Layout? = null,
        // On a DECOY slot: which local account the decoy PIN reveals (the real
        // accounts are hidden while it's active).
        val decoyAccountId: String? = null,
    )

    /** Result of a successful unlock: the matched payload + the derived key
     *  (so the caller can re-seal the same slot when changing settings). */
    data class Unlock(val payload: SlotPayload, val slotKey: ByteArray)

    fun isConfigured(context: Context): Boolean = readVault(context) != null

    // ── KDF ──────────────────────────────────────────────────────────
    private fun deriveKey(context: Context, pin: String, salt: ByteArray): ByteArray {
        val pepper = SecureStore.pinPepper(context)
        val spec = PBEKeySpec(pin.toCharArray(), salt + pepper, KDF_ROUNDS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    // ── slot crypto ──────────────────────────────────────────────────
    private fun sealSlot(payload: SlotPayload, key: ByteArray): ByteArray {
        val json = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        require(json.size + 2 <= PAYLOAD_LEN) { "pin payload too large" }
        val plain = ByteArray(PAYLOAD_LEN)
        plain[0] = (json.size ushr 8).toByte()
        plain[1] = (json.size and 0xFF).toByte()
        System.arraycopy(json, 0, plain, 2, json.size)
        // Fill the remainder with random padding so every slot is identical size.
        val pad = ByteArray(PAYLOAD_LEN - 2 - json.size).also { rng.nextBytes(it) }
        System.arraycopy(pad, 0, plain, 2 + json.size, pad.size)
        return MediaCrypto.seal(plain, key) // nonce||ct||tag = SLOT_LEN
    }

    private fun openSlot(slot: ByteArray, key: ByteArray): SlotPayload? {
        if (slot.size != SLOT_LEN) return null
        val plain = try {
            MediaCrypto.open(slot, key)
        } catch (e: Exception) {
            return null // wrong key: GCM tag mismatch
        }
        if (plain.size < 2) return null
        val len = ((plain[0].toInt() and 0xFF) shl 8) or (plain[1].toInt() and 0xFF)
        if (len <= 0 || 2 + len > plain.size) return null
        return try {
            gson.fromJson(String(plain, 2, len, Charsets.UTF_8), SlotPayload::class.java)
        } catch (e: Exception) {
            null
        }
    }

    // ── file read / write ────────────────────────────────────────────
    private data class VaultFile(val salt: ByteArray, val slots: List<ByteArray>)

    private fun vaultFile(context: Context) = File(context.noBackupFilesDir, FILE_NAME)

    private fun readVault(context: Context): VaultFile? {
        val raw = runCatching { vaultFile(context).readBytes() }.getOrNull() ?: return null
        val expected = 1 + SALT_LEN + SLOT_COUNT * SLOT_LEN
        if (raw.size != expected || raw[0].toInt() != VERSION) return null
        var off = 1
        val salt = raw.copyOfRange(off, off + SALT_LEN); off += SALT_LEN
        val slots = ArrayList<ByteArray>(SLOT_COUNT)
        repeat(SLOT_COUNT) { slots.add(raw.copyOfRange(off, off + SLOT_LEN)); off += SLOT_LEN }
        return VaultFile(salt, slots)
    }

    private fun writeVault(context: Context, v: VaultFile) {
        val out = ByteArray(1 + SALT_LEN + SLOT_COUNT * SLOT_LEN)
        out[0] = VERSION.toByte()
        var off = 1
        System.arraycopy(v.salt, 0, out, off, SALT_LEN); off += SALT_LEN
        v.slots.forEach { System.arraycopy(it, 0, out, off, SLOT_LEN); off += SLOT_LEN }
        val f = vaultFile(context)
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(f)) { f.writeBytes(out); tmp.delete() } // atomic-ish replace
    }

    private fun randomSlot() = ByteArray(SLOT_LEN).also { rng.nextBytes(it) }

    // ── high-level operations ────────────────────────────────────────

    /** Create a fresh vault whose real slot holds a new random dataKey. The
     *  caller (PanicPinService) then re-encrypts the message DB to that key. */
    fun createWithRealPin(context: Context, pin: String): Unlock {
        // Fresh pepper so an old one can't be reused against the new vault.
        SecureStore.clearPinSecrets(context)
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        val realSlot = rng.nextInt(SLOT_COUNT)
        val dataKey = ByteArray(32).also { rng.nextBytes(it) }
        val payload = SlotPayload(
            mode = MODE_REAL,
            dataKeyB64 = android.util.Base64.encodeToString(dataKey, android.util.Base64.NO_WRAP),
            layout = Layout(realSlot = realSlot),
        )
        val key = deriveKey(context, pin, salt)
        val slots = (0 until SLOT_COUNT).map { randomSlot() }.toMutableList()
        slots[realSlot] = sealSlot(payload, key)
        writeVault(context, VaultFile(salt, slots))
        clearAttempts(context)
        return Unlock(payload, key)
    }

    /** Try [pin] against every slot; returns the matched payload + key or null. */
    fun unlock(context: Context, pin: String): Unlock? {
        val vault = readVault(context) ?: return null
        val key = deriveKey(context, pin, vault.salt)
        for (slot in vault.slots) {
            val payload = openSlot(slot, key)
            if (payload != null) return Unlock(payload, key)
        }
        return null
    }

    /** Re-seal [index] with [payload] under [key], or clear it (random) when
     *  [payload] is null. Used to change a PIN or add/remove decoy/wipe slots. */
    fun writeSlot(context: Context, index: Int, payload: SlotPayload?, key: ByteArray?) {
        val vault = readVault(context) ?: error("no vault")
        require(index in 0 until SLOT_COUNT)
        val slots = vault.slots.toMutableList()
        slots[index] = if (payload != null && key != null) sealSlot(payload, key) else randomSlot()
        writeVault(context, VaultFile(vault.salt, slots))
    }

    /** Re-seal [index] under a key derived from [pin] (change-PIN; the payload
     *  and its dataKey are unchanged, so the message DB is not re-encrypted). */
    fun reSealSlot(context: Context, index: Int, payload: SlotPayload, pin: String) {
        val vault = readVault(context) ?: error("no vault")
        writeSlot(context, index, payload, deriveKey(context, pin, vault.salt))
    }

    /** Re-seal whichever slot [oldKey] currently opens under a key derived from
     *  [newPin], keeping [payload] (dataKey unchanged → DB not re-encrypted).
     *  Used to change the DECOY PIN from within a duress session WITHOUT the
     *  real slot key: the caller holds only the decoy slot's key. Rejects a
     *  [newPin] that would open a DIFFERENT existing slot (a collision could
     *  make the new decoy PIN accidentally match the real slot). Returns the
     *  new derived key on success, or null (slot not found / collision). */
    fun reSealUnderNewPin(context: Context, oldKey: ByteArray, payload: SlotPayload, newPin: String): ByteArray? {
        val vault = readVault(context) ?: return null
        val idx = vault.slots.indexOfFirst { openSlot(it, oldKey) != null }
        if (idx < 0) return null
        val newKey = deriveKey(context, newPin, vault.salt)
        vault.slots.forEachIndexed { i, slot -> if (i != idx && openSlot(slot, newKey) != null) return null }
        val slots = vault.slots.toMutableList()
        slots[idx] = sealSlot(payload, newKey)
        writeVault(context, VaultFile(vault.salt, slots))
        return newKey
    }

    fun freeSlotIndex(layout: Layout): Int? {
        val used = listOfNotNull(layout.realSlot, layout.decoySlot, layout.wipeSlot).toSet()
        return (0 until SLOT_COUNT).firstOrNull { it !in used }
    }

    /** Seal [payload] into the first free slot (per [layout]) under a key
     *  derived from [pin]; returns the slot index, or null if [pin] already
     *  opens a slot (would collide with an existing PIN) or no slot is free.
     *  Used to add a decoy / wipe PIN to an unlocked real vault. */
    fun addSlot(context: Context, pin: String, payload: SlotPayload, layout: Layout): Int? {
        val vault = readVault(context) ?: return null
        val key = deriveKey(context, pin, vault.salt)
        if (vault.slots.any { openSlot(it, key) != null }) return null // pin already in use
        val slot = freeSlotIndex(layout) ?: return null
        val slots = vault.slots.toMutableList()
        slots[slot] = sealSlot(payload, key)
        writeVault(context, VaultFile(vault.salt, slots))
        return slot
    }

    fun dataKeyBytes(payload: SlotPayload): ByteArray? =
        payload.dataKeyB64?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }

    fun destroy(context: Context) {
        runCatching { vaultFile(context).delete() }
        SecureStore.clearPinSecrets(context)
        clearAttempts(context)
    }

    // ── brute-force throttle ─────────────────────────────────────────
    data class AttemptState(val failedCount: Int = 0, val lockoutUntil: Long? = null)

    /** Escalating lockout (ms) mirroring iOS: <5 free, then 30s/60s/5m/15m/1h. */
    fun lockoutMillis(failedCount: Int): Long = when {
        failedCount < 5 -> 0
        failedCount == 5 -> 30_000
        failedCount == 6 -> 60_000
        failedCount == 7 -> 300_000
        failedCount == 8 -> 900_000
        else -> 3_600_000
    }

    fun loadAttempts(context: Context): AttemptState =
        SecureStore.loadPinAttempts(context)
            ?.let { runCatching { gson.fromJson(it, AttemptState::class.java) }.getOrNull() }
            ?: AttemptState()

    fun saveAttempts(context: Context, s: AttemptState) =
        SecureStore.savePinAttempts(context, gson.toJson(s))

    fun clearAttempts(context: Context) = SecureStore.clearPinAttempts(context)
}
