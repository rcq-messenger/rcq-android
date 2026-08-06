package app.rcq.android.backup

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The `.rcqbak` container: a stream of named entries, encrypted end to end
 * with a key derived from the account's 24-word recovery phrase.
 *
 * Why a container of our own rather than an encrypted database dump: the three
 * clients keep their history in three different stores (SQLCipher on Android,
 * its own store on iOS, IndexedDB in the browser), so a dump taken on a phone
 * would simply not open anywhere else, and "took it on Android, restored it on
 * an iPhone" is the entire point of having this. Entries are therefore neutral:
 * a manifest, one message per line, the device-local settings, and the media
 * blobs in the clear inside the encrypted stream.
 *
 * Why the recovery phrase and not a separate password: anyone holding the
 * phrase already owns the account, so a second secret would be one more thing
 * to lose without protecting anything. It does mean the honest warning belongs
 * in the UI: this file plus a leaked phrase is the whole history.
 *
 * ⚠ Private keys are deliberately NOT in here. Identity travels by phrase; a
 * file that carried both the history and the keys would be a single object that
 * hands over the entire account, and people mail these to themselves.
 *
 * Layout, all little-endian:
 *
 *   "RCQBAK1\n"
 *   <4-byte header length><header JSON>      // plaintext: version, uin, salt…
 *   repeat: <4-byte chunk length><12-byte nonce><ciphertext+tag>
 *   <4-byte 0xFFFFFFFF>                      // end marker
 *
 * Each chunk is authenticated with the header bytes AND its own index as
 * additional data, so a truncated or reordered file fails to open instead of
 * silently restoring half a history.
 */
object BackupFormat {

    const val MAGIC = "RCQBAK1\n"
    const val VERSION = 1

    /** 400k rounds, same as the panic-PIN vault: this runs once per export and
     *  once per restore, so the cost is invisible to the user and expensive for
     *  anyone brute-forcing a stolen file. */
    private const val PBKDF2_ROUNDS = 400_000
    private const val CHUNK = 1 shl 20
    private const val END_MARKER = -1

    class BackupError(message: String) : Exception(message)

    fun deriveKey(phrase: String, salt: ByteArray): ByteArray {
        // Normalised so "Word  Word" and "word word" derive the same key: the
        // phrase is typed by hand on the restoring device.
        val norm = phrase.trim().lowercase().split(Regex("\\s+")).joinToString(" ")
        val spec = PBEKeySpec(norm.toCharArray(), salt, PBKDF2_ROUNDS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun len(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun readInt(input: InputStream): Int {
        val b = ByteArray(4)
        var off = 0
        while (off < 4) {
            val n = input.read(b, off, 4 - off)
            if (n < 0) throw EOFException()
            off += n
        }
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun aad(header: ByteArray, index: Long): ByteArray =
        header + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(index).array()

    /** Streaming writer. Entries are written one after another; nothing is ever
     *  held whole in memory, which matters because the media of a busy account
     *  runs to gigabytes. */
    class Writer(private val out: OutputStream, phrase: String, uin: Int, createdAt: Long) {
        private val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        private val key: SecretKeySpec
        private val header: ByteArray
        private var chunkIndex = 0L
        private val buf = ByteArray(CHUNK)
        private var used = 0

        init {
            key = SecretKeySpec(deriveKey(phrase, salt), "AES")
            header = buildString {
                append("{\"version\":").append(VERSION)
                append(",\"uin\":").append(uin)
                append(",\"created_at\":").append(createdAt)
                append(",\"kdf\":\"pbkdf2-sha256\",\"rounds\":").append(PBKDF2_ROUNDS)
                append(",\"salt\":\"").append(android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)).append('"')
                append(",\"cipher\":\"aes-256-gcm\"}")
            }.toByteArray()
            out.write(MAGIC.toByteArray())
            out.write(len(header.size))
            out.write(header)
        }

        private fun flushChunk() {
            if (used == 0) return
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            c.updateAAD(aad(header, chunkIndex))
            val ct = c.doFinal(buf, 0, used)
            out.write(len(nonce.size + ct.size))
            out.write(nonce)
            out.write(ct)
            chunkIndex++
            used = 0
        }

        private fun raw(b: ByteArray, off: Int, n: Int) {
            var o = off
            var left = n
            while (left > 0) {
                val take = minOf(left, CHUNK - used)
                System.arraycopy(b, o, buf, used, take)
                used += take
                o += take
                left -= take
                if (used == CHUNK) flushChunk()
            }
        }

        /** One entry: a JSON line naming it and giving its byte length, then the
         *  bytes. Deliberately dumb so the other two clients can read it without
         *  a library. */
        fun entry(name: String, bytes: ByteArray) {
            val head = "{\"name\":\"$name\",\"size\":${bytes.size}}\n".toByteArray()
            raw(head, 0, head.size)
            raw(bytes, 0, bytes.size)
        }

        /** Same, for something too big to hold in memory. */
        fun entryStream(name: String, size: Long, source: InputStream) {
            val head = "{\"name\":\"$name\",\"size\":$size}\n".toByteArray()
            raw(head, 0, head.size)
            val tmp = ByteArray(64 * 1024)
            var left = size
            while (left > 0) {
                val n = source.read(tmp, 0, minOf(tmp.size.toLong(), left).toInt())
                if (n <= 0) throw BackupError("attachment ended early")
                raw(tmp, 0, n)
                left -= n
            }
        }

        fun finish() {
            flushChunk()
            out.write(len(END_MARKER))
            out.flush()
        }
    }

    /** Streaming reader. Calls [onEntry] with the name and the decrypted bytes
     *  of each entry, in the order they were written. */
    class Reader(private val input: InputStream, private val phrase: String) {
        lateinit var header: ByteArray
            private set
        var uin: Int = 0
            private set
        var version: Int = 0
            private set

        private lateinit var key: SecretKeySpec
        private var chunkIndex = 0L
        private var pending = ByteArray(0)
        private var pendingOff = 0
        private var done = false

        fun open() {
            val magic = ByteArray(MAGIC.length)
            var off = 0
            while (off < magic.size) {
                val n = input.read(magic, off, magic.size - off)
                if (n < 0) throw BackupError("not an RCQ backup")
                off += n
            }
            if (String(magic) != MAGIC) throw BackupError("not an RCQ backup")
            val hlen = readInt(input)
            if (hlen !in 1..8192) throw BackupError("backup header looks wrong")
            header = ByteArray(hlen)
            off = 0
            while (off < hlen) {
                val n = input.read(header, off, hlen - off)
                if (n < 0) throw BackupError("backup header is truncated")
                off += n
            }
            val json = com.google.gson.JsonParser.parseString(String(header)).asJsonObject
            version = json.get("version").asInt
            if (version > VERSION) throw BackupError("this backup was made by a newer version")
            uin = json.get("uin").asInt
            val salt = android.util.Base64.decode(json.get("salt").asString, android.util.Base64.NO_WRAP)
            key = SecretKeySpec(deriveKey(phrase, salt), "AES")
        }

        private fun fill(): Boolean {
            if (done) return false
            val n = readInt(input)
            if (n == END_MARKER) { done = true; return false }
            if (n <= 12 || n > CHUNK + 64) throw BackupError("backup is damaged")
            val body = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(body, off, n - off)
                if (r < 0) throw BackupError("backup is truncated")
                off += r
            }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, body, 0, 12))
            c.updateAAD(aad(header, chunkIndex))
            pending = try {
                c.doFinal(body, 12, n - 12)
            } catch (e: Exception) {
                // The only realistic causes are a wrong phrase and a tampered
                // file, and the user can only act on the first.
                throw BackupError("wrong recovery phrase, or the file is damaged")
            }
            pendingOff = 0
            chunkIndex++
            return true
        }

        private fun read(dest: ByteArray, size: Int): Boolean {
            var off = 0
            while (off < size) {
                if (pendingOff >= pending.size && !fill()) return false
                val take = minOf(size - off, pending.size - pendingOff)
                System.arraycopy(pending, pendingOff, dest, off, take)
                pendingOff += take
                off += take
            }
            return true
        }

        private fun readLine(): String? {
            val sb = StringBuilder()
            val one = ByteArray(1)
            while (true) {
                if (!read(one, 1)) return if (sb.isEmpty()) null else sb.toString()
                if (one[0] == '\n'.code.toByte()) return sb.toString()
                sb.append(one[0].toInt().toChar())
                if (sb.length > 4096) throw BackupError("backup is damaged")
            }
        }

        fun forEachEntry(onEntry: (name: String, bytes: ByteArray) -> Unit) {
            while (true) {
                val line = readLine() ?: return
                if (line.isBlank()) return
                val obj = com.google.gson.JsonParser.parseString(line).asJsonObject
                val name = obj.get("name").asString
                val size = obj.get("size").asLong
                if (size < 0 || size > 512L * 1024 * 1024) throw BackupError("backup is damaged")
                val bytes = ByteArray(size.toInt())
                if (size > 0 && !read(bytes, size.toInt())) throw BackupError("backup is truncated")
                onEntry(name, bytes)
            }
        }
    }
}
