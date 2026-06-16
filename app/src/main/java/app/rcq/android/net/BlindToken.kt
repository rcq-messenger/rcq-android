package app.rcq.android.net

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.RSAPublicKeySpec

/**
 * F3 deposit-auth client crypto — RFC 9474 RSA blind signatures (RSABSSA-SHA384-
 * PSS, randomized), the client half of `rcq-server-ref app/core/deposit_auth.py`.
 *
 * A deposit token lets the recipient island rate-limit our sealed deposit WITHOUT
 * learning who we are: we blind a random nonce, the island blind-signs it (we pay
 * proof-of-work), we unblind to a standard RSA-PSS signature and later spend it.
 * The unblinded token is plain RSA-PSS, so it interoperates with the Python issuer
 * + Swift client by spec (verified cross-runtime in BlindTokenTest).
 *
 * Framework-free (pure `java.math` + `java.security`) so it runs in a JVM unit
 * test; base64 + HTTP live in [DepositAuthStore]. Mirrors the Python byte-for-byte:
 * SHA-384 / MGF1-SHA-384 / salt 48, em_bits = modBits-1, randomized 32-byte prefix.
 */
object BlindToken {
    private const val SALT_LEN = 48
    private const val RANDOM_PREFIX_LEN = 32
    private val rng = SecureRandom()

    private fun sha384(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-384").digest(b)
    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    /** I2OSP: x as exactly [len] big-endian unsigned bytes. */
    fun i2osp(x: BigInteger, len: Int): ByteArray {
        val b = x.toByteArray()                       // minimal two's-complement, big-endian
        if (b.size == len) return b
        if (b.size == len + 1 && b[0].toInt() == 0) return b.copyOfRange(1, b.size)  // strip sign byte
        val out = ByteArray(len)
        val src = if (b.size > len) b.copyOfRange(b.size - len, b.size) else b
        System.arraycopy(src, 0, out, len - src.size, src.size)
        return out
    }

    /** OS2IP: bytes as a non-negative integer. */
    fun os2ip(b: ByteArray): BigInteger = BigInteger(1, b)

    private fun mgf1(seed: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var counter = 0
        var off = 0
        while (off < length) {
            val md = MessageDigest.getInstance("SHA-384")
            md.update(seed)
            md.update(i2osp(BigInteger.valueOf(counter.toLong()), 4))
            val block = md.digest()
            val n = minOf(block.size, length - off)
            System.arraycopy(block, 0, out, off, n)
            off += n
            counter++
        }
        return out
    }

    /** EMSA-PSS-ENCODE (RFC 8017 §9.1.1), SHA-384 / MGF1-SHA-384 / sLen=48. */
    private fun emsaPssEncode(msg: ByteArray, emBits: Int): ByteArray {
        val hLen = 48
        val emLen = (emBits + 7) / 8
        val mHash = sha384(msg)
        require(emLen >= hLen + SALT_LEN + 2) { "encoding error" }
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        val mPrime = ByteArray(8) + mHash + salt
        val h = sha384(mPrime)
        val db = ByteArray(emLen - SALT_LEN - hLen - 2) + byteArrayOf(0x01) + salt
        val dbMask = mgf1(h, emLen - hLen - 1)
        val maskedDb = ByteArray(db.size) { (db[it].toInt() xor dbMask[it].toInt()).toByte() }
        val clear = 8 * emLen - emBits
        if (clear > 0) maskedDb[0] = (maskedDb[0].toInt() and (0xFF ushr clear)).toByte()
        return maskedDb + h + byteArrayOf(0xBC.toByte())
    }

    /** A fresh randomized prepared message: 32 random bytes ‖ a 32-byte nonce. */
    fun prepare(): ByteArray = ByteArray(RANDOM_PREFIX_LEN + 32).also { rng.nextBytes(it) }

    /** Blinding result. [blinded] goes to the issuer; [blindInv] + [prepared] stay local. */
    class Blinded(val blinded: ByteArray, val blindInv: BigInteger, val prepared: ByteArray)

    /** CLIENT: blind a prepared message. blinded = m * r^e mod n. */
    fun blind(n: BigInteger, e: BigInteger, prepared: ByteArray): Blinded {
        val modLen = (n.bitLength() + 7) / 8
        val m = os2ip(emsaPssEncode(prepared, n.bitLength() - 1))
        require(m < n) { "encoded message not < modulus" }
        var r: BigInteger
        var rInv: BigInteger
        while (true) {
            r = BigInteger(n.bitLength(), rng).mod(n)
            if (r < BigInteger.ONE || r.gcd(n) != BigInteger.ONE) continue
            rInv = r.modInverse(n)
            break
        }
        val x = m.multiply(r.modPow(e, n)).mod(n)
        return Blinded(i2osp(x, modLen), rInv, prepared)
    }

    /** CLIENT: unblind the issuer's blind signature -> a standard RSA-PSS signature. */
    fun finalize(blindSig: ByteArray, blindInv: BigInteger, n: BigInteger): ByteArray {
        val modLen = (n.bitLength() + 7) / 8
        val s = os2ip(blindSig).multiply(blindInv).mod(n)
        return i2osp(s, modLen)
    }

    /** Verify a token is a valid RSA-PSS signature over [prepared] (the interop check). */
    fun verify(n: BigInteger, e: BigInteger, prepared: ByteArray, sig: ByteArray): Boolean = try {
        val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
        val v = Signature.getInstance("RSASSA-PSS")
        v.setParameter(PSSParameterSpec("SHA-384", "MGF1", MGF1ParameterSpec.SHA384, SALT_LEN, 1))
        v.initVerify(pub)
        v.update(prepared)
        v.verify(sig)
    } catch (t: Throwable) {
        false
    }

    // ── proof-of-work (SHA-256 hashcash) ──────────────────────────────────────
    private fun leadingZeroBits(d: ByteArray): Int {
        var bits = 0
        for (b in d) {
            val v = b.toInt() and 0xFF
            if (v == 0) {
                bits += 8
                continue
            }
            bits += 8 - (32 - Integer.numberOfLeadingZeros(v))   // 8 - bit_length(v)
            break
        }
        return bits
    }

    fun verifyPow(challenge: String, nonce: String, difficultyBits: Int): Boolean =
        leadingZeroBits(sha256("$challenge:$nonce".toByteArray(Charsets.UTF_8))) >= difficultyBits

    /** Solve the hashcash PoW bound to [challenge] (= "{epoch_id}:{blinded_b64}"). */
    fun solvePow(challenge: String, difficultyBits: Int): String {
        var c = 0L
        while (true) {
            val nonce = c.toString()
            if (verifyPow(challenge, nonce, difficultyBits)) return nonce
            c++
        }
    }
}
