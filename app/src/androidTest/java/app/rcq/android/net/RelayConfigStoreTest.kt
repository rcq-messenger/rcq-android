package app.rcq.android.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the Ed25519 + canonical-JSON relay-config verification end-to-end
 * against the REAL signed payload (v9) the iOS client + Python signer produce.
 * If the Android canonicalization were off by a single byte, the signature
 * would not verify — so a green test proves byte-exact agreement with the
 * signer (the whole risk of the port). Runs on-device (BouncyCastle Ed25519).
 */
@RunWith(AndroidJUnit4::class)
class RelayConfigStoreTest {

    // The live, genuinely-signed v9 payload (pretty-printed; verifyAndParse
    // re-canonicalizes before verifying, so whitespace is irrelevant).
    private val signed = """{
  "version": 9,
  "issued_at": "2026-05-26T18:52:30Z",
  "relays": [
    {
      "tag": "relay-do-fra-yandex-hy2",
      "proto": "hysteria2",
      "server": "165.22.90.214",
      "port": 443,
      "sni": "www.yandex.ru",
      "password": "JN0qzA4LJfhHPKKN3QHj4eN8",
      "obfs_password": "jXfGkLToOkTihpeJzDiNf8Bb",
      "priority": 0
    },
    {
      "tag": "relay-do-fra-yandex",
      "proto": "vless",
      "server": "165.22.90.214",
      "port": 443,
      "uuid": "2081b3c4-faaa-4cce-a0ab-607197b28237",
      "sni": "www.yandex.ru",
      "public_key": "n33TZTLNrc6X7jTGrKWex_sk8aIQ6Qqz-eC8lqYMii8",
      "short_id": "aa5d483441e59ac7",
      "priority": 1,
      "flow": "xtls-rprx-vision"
    },
    {
      "tag": "relay-oracle-il-hy2",
      "proto": "hysteria2",
      "server": "129.159.143.135",
      "port": 443,
      "sni": "www.microsoft.com",
      "password": "bvuvu74CVsiXdcJazcYphnO5",
      "obfs_password": "PaEHrZABTk36orhfFON7Jure",
      "priority": 2
    },
    {
      "tag": "relay-oracle-il",
      "proto": "vless",
      "server": "129.159.143.135",
      "port": 443,
      "uuid": "ff005e0c-175e-4475-a166-eeac88f514e2",
      "sni": "www.microsoft.com",
      "public_key": "_Hhc-2pjkvR914mddMdmuoOVaT74vWR8Gby7KmJp9F8",
      "short_id": "318567678ac9878e",
      "priority": 3,
      "flow": "xtls-rprx-vision"
    },
    {
      "tag": "relay-gcp-hy2",
      "proto": "hysteria2",
      "server": "35.238.53.96",
      "port": 443,
      "sni": "www.apple.com",
      "password": "QaY3uT8EmfZxfON65jaT5bSu",
      "obfs_password": "fLpJ2c211xjnZcP9VNcNpbZP",
      "priority": 4
    },
    {
      "tag": "relay-gcp",
      "proto": "vless",
      "server": "35.238.53.96",
      "port": 443,
      "uuid": "8e3b35d3-18a6-406d-9ac6-c5558a806663",
      "sni": "www.apple.com",
      "public_key": "mQZ8CJeMWyf7oYGWJG8oOI52or2kx4yTthl6AGZkSTw",
      "short_id": "b5b8979af1f27aab",
      "priority": 5,
      "flow": "xtls-rprx-vision"
    },
    {
      "tag": "relay-aws-sg-hy2",
      "proto": "hysteria2",
      "server": "47.129.249.170",
      "port": 443,
      "sni": "www.amazon.com",
      "password": "IjO9NlfvuXuP8w4tZNXHZwGL",
      "obfs_password": "yBlwN4J7IMzQi3VCMo0oKZHh",
      "priority": 6
    },
    {
      "tag": "relay-aws-sg",
      "proto": "vless",
      "server": "47.129.249.170",
      "port": 443,
      "uuid": "2b0a3318-7bfc-4ff2-83ae-2f322cb91ef8",
      "sni": "www.amazon.com",
      "public_key": "xxasGveo2BtMx4doxftb-AJcvIXL-9LpymZcV9tIRxo",
      "short_id": "533142a04b016a00",
      "priority": 7,
      "flow": "xtls-rprx-vision"
    }
  ],
  "sig": "Z0PWyeZaAFNFIt8QHchOGFv+lMnxN6ElE5ZuZGKNs47CWGVNQmY+7GPaAv0F4yftoTpbX8OwMmfCANFldwnXCg=="
}
"""

    @Test
    fun genuinePayloadVerifiesAndParses() {
        val relays = RelayConfigStore.verifyAndParse(signed)
        assertNotNull("genuine signature must verify", relays)
        assertEquals(8, relays!!.size)
        // Sorted by priority: the priority-0 hysteria2 relay comes first.
        assertEquals("relay-do-fra-yandex-hy2", relays.first().tag)
        assertTrue(relays.any { it.tag == "relay-gcp" && it.proto == "vless" })
        // A vless relay carries its Reality params; a hy2 relay its obfs.
        val gcp = relays.first { it.tag == "relay-gcp" }
        assertEquals("8e3b35d3-18a6-406d-9ac6-c5558a806663", gcp.uuid)
        assertNotNull(gcp.publicKey)
        val hy2 = relays.first { it.proto == "hysteria2" }
        assertNotNull(hy2.password)
        assertNotNull(hy2.obfsPassword)
    }

    @Test
    fun tamperedPayloadRejected() {
        // Flip a relay's server IP — body no longer matches the signature.
        val tampered = signed.replace("35.238.53.96", "35.238.53.97")
        assertTrue("test setup: replacement must change the text", tampered != signed)
        assertNull(RelayConfigStore.verifyAndParse(tampered))
    }

    @Test
    fun missingSignatureRejected() {
        // Drop the sig field: chop from the comma before "sig" to the end,
        // then re-close the object. No signature → rejected.
        val cut = signed.lastIndexOf(",")
        val noSig = signed.substring(0, cut) + "\n}"
        assertNull(RelayConfigStore.verifyAndParse(noSig))
    }

    // ── key rotation ────────────────────────────────────────────────────
    //
    // The payloads below are the live v131 config (trimmed to two relays)
    // re-signed with keys other than the one in use. They are what proves the
    // client can be handed a payload signed by a DIFFERENT key without an app
    // release — the property that makes rotating a leaked key possible at all,
    // and the one that is silently absent until something actually verifies it.

    private val successorSigned = """{
  "version": 131,
  "issued_at": "2026-08-05T01:07:49Z",
  "relays": [
    {
      "tag": "relay-do-fra-spaces-hy2",
      "proto": "hysteria2",
      "server": "165.22.90.214",
      "port": 443,
      "sni": "fra1.digitaloceanspaces.com",
      "password": "JN0qzA4LJfhHPKKN3QHj4eN8",
      "obfs_password": "jXfGkLToOkTihpeJzDiNf8Bb",
      "priority": 0
    },
    {
      "tag": "relay-do-fra-spaces",
      "proto": "vless",
      "server": "165.22.90.214",
      "port": 443,
      "sni": "fra1.digitaloceanspaces.com",
      "uuid": "2081b3c4-faaa-4cce-a0ab-607197b28237",
      "public_key": "n33TZTLNrc6X7jTGrKWex_sk8aIQ6Qqz-eC8lqYMii8",
      "short_id": "aa5d483441e59ac7",
      "flow": "xtls-rprx-vision",
      "priority": 1
    }
  ],
  "onion": {
    "enabled": true
  },
  "sig": "yogGP3rjyMAAg8K1qao153pPSJFChsfk/3nCD574tiLFfxOg8bjnjQaYW0xwDqMDaDEhOqk9Tt4G+BmUX6kWAw=="
}"""

    private val strangerSigned = """{
  "version": 131,
  "issued_at": "2026-08-05T01:07:49Z",
  "relays": [
    {
      "tag": "relay-do-fra-spaces-hy2",
      "proto": "hysteria2",
      "server": "165.22.90.214",
      "port": 443,
      "sni": "fra1.digitaloceanspaces.com",
      "password": "JN0qzA4LJfhHPKKN3QHj4eN8",
      "obfs_password": "jXfGkLToOkTihpeJzDiNf8Bb",
      "priority": 0
    },
    {
      "tag": "relay-do-fra-spaces",
      "proto": "vless",
      "server": "165.22.90.214",
      "port": 443,
      "sni": "fra1.digitaloceanspaces.com",
      "uuid": "2081b3c4-faaa-4cce-a0ab-607197b28237",
      "public_key": "n33TZTLNrc6X7jTGrKWex_sk8aIQ6Qqz-eC8lqYMii8",
      "short_id": "aa5d483441e59ac7",
      "flow": "xtls-rprx-vision",
      "priority": 1
    }
  ],
  "onion": {
    "enabled": true
  },
  "sig": "cb05b4Rut1+p1aaFlN6+USTrsO3DQ8UdkMpWMFxU/KDENMMOjgNB0pcRmtGUj+MtQPqpJi0+Z14RXaUvEVj8Dw=="
}"""

    @Test
    fun payloadSignedByTheSuccessorKeyIsAccepted() {
        // Never signed anything in production; ships so that switching to it is
        // a signing decision rather than a release.
        val relays = RelayConfigStore.verifyAndParse(successorSigned)
        assertNotNull("successor key must verify — without this, rotation is impossible", relays)
        assertEquals(2, relays!!.size)
    }

    @Test
    fun payloadSignedByAnUnknownKeyIsRejected() {
        // Accepting a SET must not mean accepting anyone: this key was generated
        // for the test and appears in no build.
        assertNull(RelayConfigStore.verifyAndParse(strangerSigned))
    }
}
