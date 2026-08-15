package app.rcq.android.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for the panic-PIN vault. Uses the real EncryptedSharedPreferences
 * (for the pepper) + the real PBKDF2; cleans the vault up after each test.
 */
@RunWith(AndroidJUnit4::class)
class PinVaultTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanup() = PinVault.destroy(ctx)

    /**
     * ⚠⚠ R2 guard. The slot payload is a FIXED-SIZE box and the vault format
     * may never change to make room, so every field added to [PinVault.Layout]
     * or [PinVault.SlotPayload] has to be checked against the budget at its
     * maximum length. If this ever fails, the fix is a shorter field — not a
     * bigger PAYLOAD_LEN, which would make readVault return null on every
     * existing vault and read as "no PIN set" while the history stayed
     * unreadable ciphertext.
     */
    @Test
    fun maximalPayloadsStillSeal() {
        val key32 = android.util.Base64.encodeToString(ByteArray(32) { 0xAB.toByte() }, android.util.Base64.NO_WRAP)
        // Worst-case REAL slot as the app can actually write it: every slot in
        // use, the decoy's own key + identity recorded, the wipe mirror on, and
        // NO legacy account id — commitDecoyPin nulls that before it measures,
        // precisely because the two cannot coexist (see below). 263 + 2 = 265
        // of 320.
        val real = PinVault.SlotPayload(
            mode = PinVault.MODE_REAL,
            dataKeyB64 = key32,
            layout = PinVault.Layout(
                realSlot = 2,
                decoySlot = 1,
                wipeSlot = 0,
                decoyAccountId = null,
                decoyDataKeyB64 = key32,
                decoyUin = 999_999_999,
                decoyNickname = "user-9999",
                wipeServer = true,
            ),
        )
        assertTrue("real slot payload no longer fits", PinVault.payloadFits(real))

        // ⚠⚠ And the shape that does NOT fit, asserted on purpose. A 36-char
        // legacy `decoyAccountId` alongside `decoyDataKeyB64` is 321 of 320 —
        // Gson HTML-escapes the single base64 '=' on each 32-byte key as
        // "=", five bytes apiece, which is what eats the margin. Nothing
        // writes that shape today because commitDecoyPin clears the legacy id
        // first; if that ever stops being true, every existing decoy user would
        // silently fail to migrate rather than crash, so pin it here.
        val withLegacyId = real.copy(
            layout = real.layout!!.copy(decoyAccountId = "0f3b1c22-9a4d-4e77-8b10-5c6d7e8f9012"),
        )
        assertFalse(
            "legacy decoyAccountId now fits alongside decoyDataKeyB64 — re-measure the budget",
            PinVault.payloadFits(withLegacyId),
        )

        val decoy = PinVault.SlotPayload(
            mode = PinVault.MODE_DECOY,
            dataKeyB64 = key32,
            decoyUin = 999_999_999,
            decoyNickname = "user-9999",
        )
        assertTrue("decoy slot payload no longer fits", PinVault.payloadFits(decoy))

        val wipe = PinVault.SlotPayload(mode = PinVault.MODE_WIPE, wipeServer = true)
        assertTrue("wipe slot payload no longer fits", PinVault.payloadFits(wipe))

        // And it actually seals + reopens at that size, not just fits on paper.
        PinVault.createWithRealPin(ctx, "1234")
        val u = PinVault.unlock(ctx, "1234")!!
        PinVault.writeSlot(ctx, u.payload.layout!!.realSlot, real, u.slotKey)
        val back = PinVault.unlock(ctx, "1234")
        assertNotNull(back)
        assertEquals(999_999_999, back!!.payload.layout?.decoyUin)
        assertEquals(true, back.payload.layout?.wipeServer)
    }

    /** An old slot has no [wipeServer] key at all; it must decode as absent,
     *  which the wipe path treats as FALSE. */
    @Test
    fun absentWipeServerFlagDecodesAsFalse() {
        PinVault.createWithRealPin(ctx, "1234")
        val u = PinVault.unlock(ctx, "1234")!!
        val slot = PinVault.addSlot(ctx, "4321", PinVault.SlotPayload(mode = PinVault.MODE_WIPE), u.payload.layout!!)
        assertNotNull(slot)
        val w = PinVault.unlock(ctx, "4321")
        assertNotNull(w)
        assertEquals(PinVault.MODE_WIPE, w!!.payload.mode)
        assertNull(w.payload.wipeServer)
        // Same shape as a slot written by the SHIPPED build: Gson omits nulls,
        // so this JSON has no `wipeServer`, no `decoyDataKeyB64` and no
        // `decoyUin` key at all. Reading it back under the NEW classes must
        // yield nulls rather than a decode failure — a failure here would make
        // readVault look like "no PIN set" while the history stayed ciphertext.
        val realBack = PinVault.unlock(ctx, "1234")!!
        assertNull(realBack.payload.layout?.decoyDataKeyB64)
        assertNull(realBack.payload.layout?.wipeServer)
        assertNull(realBack.payload.layout?.decoyUin)
        assertNull(realBack.payload.decoyNickname)
    }

    /** The decoy slot must NOT carry the real dataKey any more. */
    @Test
    fun decoySlotCarriesItsOwnKey() {
        val created = PinVault.createWithRealPin(ctx, "1234")
        val realKey = PinVault.dataKeyBytes(created.payload)!!
        val decoyKey = ByteArray(32) { 0x11 }
        val payload = PinVault.SlotPayload(
            mode = PinVault.MODE_DECOY,
            dataKeyB64 = android.util.Base64.encodeToString(decoyKey, android.util.Base64.NO_WRAP),
            decoyUin = 123_456_789,
            decoyNickname = "user-1234",
        )
        val idx = PinVault.freeSlotIndex(created.payload.layout!!)!!
        assertTrue(PinVault.writeSlotWithPin(ctx, idx, payload, "4321"))
        val d = PinVault.unlock(ctx, "4321")!!
        assertArrayEquals(decoyKey, PinVault.dataKeyBytes(d.payload))
        assertFalse(realKey.contentEquals(PinVault.dataKeyBytes(d.payload)))
        // …and the real slot still opens with the real key, untouched.
        assertArrayEquals(realKey, PinVault.dataKeyBytes(PinVault.unlock(ctx, "1234")!!.payload))
    }

    @Test
    fun createUnlockRoundTrip() {
        val unlock = PinVault.createWithRealPin(ctx, "1234")
        assertEquals(PinVault.MODE_REAL, unlock.payload.mode)
        val dataKey = PinVault.dataKeyBytes(unlock.payload)
        assertNotNull(dataKey)
        assertEquals(32, dataKey!!.size)
        assertTrue(PinVault.isConfigured(ctx))

        // Right PIN unlocks and yields the same dataKey.
        val u2 = PinVault.unlock(ctx, "1234")
        assertNotNull(u2)
        assertArrayEquals(dataKey, PinVault.dataKeyBytes(u2!!.payload))

        // Wrong PIN opens no slot.
        assertNull(PinVault.unlock(ctx, "9999"))
    }

    @Test
    fun changePinKeepsDataKey() {
        val unlock = PinVault.createWithRealPin(ctx, "1234")
        val dataKey = PinVault.dataKeyBytes(unlock.payload)!!
        val realSlot = unlock.payload.layout!!.realSlot
        PinVault.reSealSlot(ctx, realSlot, unlock.payload, "5678")
        // Old PIN no longer opens; new PIN opens with the SAME dataKey
        // (so the message DB never needs re-encrypting on a PIN change).
        assertNull(PinVault.unlock(ctx, "1234"))
        val u = PinVault.unlock(ctx, "5678")
        assertNotNull(u)
        assertArrayEquals(dataKey, PinVault.dataKeyBytes(u!!.payload))
    }

    @Test
    fun reSealDecoyUnderNewPinKeepsRealSlotIntact() {
        // Real vault + a decoy slot (same dataKey), mirroring setDecoyPin.
        val unlock = PinVault.createWithRealPin(ctx, "1111")
        val dataKey = PinVault.dataKeyBytes(unlock.payload)!!
        val layout = unlock.payload.layout!!
        val decoyPayload = PinVault.SlotPayload(
            mode = PinVault.MODE_DECOY,
            dataKeyB64 = android.util.Base64.encodeToString(dataKey, android.util.Base64.NO_WRAP),
            decoyAccountId = "acc-decoy",
        )
        val decoySlot = PinVault.addSlot(ctx, "2222", decoyPayload, layout)
        assertNotNull(decoySlot)

        // Change the DECOY pin from within a duress session: re-seal whichever
        // slot the old decoy key opens, under a fresh pin.
        val oldDecoyKey = PinVault.unlock(ctx, "2222")!!.slotKey
        val newKey = PinVault.reSealUnderNewPin(ctx, oldDecoyKey, decoyPayload, "3333")
        assertNotNull(newKey)

        // Old decoy pin dead; new decoy pin opens the DECOY slot.
        assertNull(PinVault.unlock(ctx, "2222"))
        val d = PinVault.unlock(ctx, "3333")
        assertNotNull(d)
        assertEquals(PinVault.MODE_DECOY, d!!.payload.mode)
        // The REAL slot is untouched: real pin still opens the real slot.
        val r = PinVault.unlock(ctx, "1111")
        assertNotNull(r)
        assertEquals(PinVault.MODE_REAL, r!!.payload.mode)
    }

    @Test
    fun reSealDecoyRejectsCollisionWithRealPin() {
        // A decoy pin change that would collide with the REAL pin must fail,
        // so it can never accidentally make the decoy slot openable by (or
        // shadow) the real slot.
        val unlock = PinVault.createWithRealPin(ctx, "1111")
        val dataKey = PinVault.dataKeyBytes(unlock.payload)!!
        val layout = unlock.payload.layout!!
        val decoyPayload = PinVault.SlotPayload(
            mode = PinVault.MODE_DECOY,
            dataKeyB64 = android.util.Base64.encodeToString(dataKey, android.util.Base64.NO_WRAP),
            decoyAccountId = "acc-decoy",
        )
        PinVault.addSlot(ctx, "2222", decoyPayload, layout)
        val oldDecoyKey = PinVault.unlock(ctx, "2222")!!.slotKey
        // Re-seal the decoy under the REAL pin → rejected.
        assertNull(PinVault.reSealUnderNewPin(ctx, oldDecoyKey, decoyPayload, "1111"))
        // Both pins still work as before (nothing was mutated).
        assertEquals(PinVault.MODE_REAL, PinVault.unlock(ctx, "1111")!!.payload.mode)
        assertEquals(PinVault.MODE_DECOY, PinVault.unlock(ctx, "2222")!!.payload.mode)
    }

    @Test
    fun destroyClearsVault() {
        PinVault.createWithRealPin(ctx, "1234")
        assertTrue(PinVault.isConfigured(ctx))
        PinVault.destroy(ctx)
        assertFalse(PinVault.isConfigured(ctx))
        assertNull(PinVault.unlock(ctx, "1234"))
    }

    @Test
    fun lockoutEscalates() {
        assertEquals(0L, PinVault.lockoutMillis(4))
        assertEquals(30_000L, PinVault.lockoutMillis(5))
        assertEquals(60_000L, PinVault.lockoutMillis(6))
        assertEquals(300_000L, PinVault.lockoutMillis(7))
        assertEquals(3_600_000L, PinVault.lockoutMillis(20))
    }
}
