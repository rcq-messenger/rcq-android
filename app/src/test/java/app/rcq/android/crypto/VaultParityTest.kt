package app.rcq.android.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64 as JBase64

/** The vault's derivation and sealed layout must be byte-identical to the web
 *  client's (`web-chat/src/lib/vault.ts`), or a contact list sealed on the
 *  desktop is unreadable on the phone and vice versa. The vector below was
 *  produced by the web implementation (identity_priv = 32 bytes of 0x01, slot
 *  name "contacts", version 7, nonce = 12 bytes of 0x07). If this ever fails,
 *  one side changed its derivation, and the slot is lost for the other. */
class VaultParityTest {
    private val ik = ByteArray(32) { 1 }
    private val slot = "78146d092b6e9be01f68451ea3dc0394"
    private val keyHex = "d7138589825968476d19a67ec265cf8962f93991e639002c31d6a841778a48a1"
    private val webBlob = "AQcHBwcHBwcHBwcHB7s8m3pcaUj8NByj0VVEpROZ4qW519dgA+zNDKqAqjDQfHP67JFiV5X5J8N1gw8MPSRiJuabp/x8Ky4qibIwJ/w9pYC4KADDbwPYs/53xO2AlXmbMG4lxZNKYwMHHoA6UqPQpdjWAyJk91X+fDXKA6foOMywFKXlrxQiE8qVNOgfmVLPanosS5SrFK85cthE1Vzh0gtQovujG1h6sbqf2bRE5zyGx/SkcbL5yZeX7lro9up2NkbzFKprxchpnlXcH/lpo+wlbiaibR8IDKgciNXcGzN+PS2mQMrCHW/yubyVbmRSg3UnYf6dzb5sX10kHTmwwyhpExj6rHRY/QXe0t3nGTGkl/2o9kcXYL1/+GNynpRmTZ+5DacLjSCjJ+pWmXavUvx7h/7Pw/B7rc/dUBy2Exy83KczrhcmWNVKS33cFkXbcSDO8SktVBLn3tjVGoaanRZnmxyrbj9WVtQr1y14cHLl/GimRlOLkvlPz5sYURLBN56p72LUrz5HUmDGZQFRObdA0nOKG2bdxfQobafNMJsg+2GIOX1qNMAdJwXkTFPYDTacUDRc4uRC3kRYjki89V6R2U0RlfrnrkAUH+oi354/uKMjb+dVgTNUsMIDlDqHUPJQgGoiIgB/zOxI0pJXqbBM8Vd8G42+Y7jvGURdT8C+0Xie6EgD9I7G7rV9N1/3NiLEbV4oizlQIbZJzA=="
    private val plaintext = "{\"v\":1,\"c\":{\"1\":{\"a\":1,\"u\":1,\"n\":\"n1\"}},\"g\":{}}"

    @Test fun slot_and_key_match_web() {
        assertEquals(slot, Vault.slotId(ik, Vault.CONTACTS))
        assertEquals(keyHex, Vault.slotKey(ik, slot).joinToString("") { "%02x".format(it) })
    }

    @Test fun opens_a_blob_the_web_sealed() {
        val opened = Vault.open(ik, slot, 7, JBase64.getDecoder().decode(webBlob))
        assertEquals(plaintext, String(opened, Charsets.UTF_8))
    }

    @Test fun seals_exactly_what_the_web_seals() {
        val blob = Vault.seal(ik, slot, 7, plaintext.toByteArray(), ByteArray(12) { 7 })
        assertArrayEquals(JBase64.getDecoder().decode(webBlob), blob)
    }

    @Test fun refuses_the_wrong_version_slot_or_identity() {
        val blob = JBase64.getDecoder().decode(webBlob)
        assertThrows(Vault.BadSeal::class.java) { Vault.open(ik, slot, 8, blob) }
        assertThrows(Vault.BadSeal::class.java) { Vault.open(ik, Vault.slotId(ik, "other"), 7, blob) }
        assertThrows(Vault.BadSeal::class.java) { Vault.open(ByteArray(32) { 2 }, slot, 7, blob) }
    }

    @Test fun size_class_not_size() {
        val a = Vault.seal(ik, slot, 1, ByteArray(10))
        val b = Vault.seal(ik, slot, 1, ByteArray(400))
        assertEquals(a.size, b.size)
        assertEquals(400, Vault.open(ik, slot, 1, b).size)
        assertEquals(0, Vault.open(ik, slot, 1, Vault.seal(ik, slot, 1, ByteArray(0))).size)
    }
}
