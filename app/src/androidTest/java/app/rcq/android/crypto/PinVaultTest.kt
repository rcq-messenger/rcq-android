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
