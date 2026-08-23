package app.rcq.android.crypto

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chunked media container ("RCQM1"), the large-file twin of [MediaCrypto].
 *
 * ## Why this exists
 *
 * [MediaCrypto] seals a whole file under ONE AES-256-GCM tag. That is a fine
 * shape for a photo and an impossible one for a film, because GCM puts its tag
 * at the END: a provider may not hand out a single byte of plaintext before it
 * has read the last byte and checked the tag. Conscrypt (the JCE provider on
 * every Android we ship to) therefore buffers the entire ciphertext internally
 * and produces the entire plaintext in `doFinal`. That is not a bug to route
 * around; it is what "authenticated" means for a monolithic seal. But it puts
 * a hard floor under the memory a download costs:
 *
 *     blob (N) + copyOfRange in MediaCrypto.open (N) + provider buffer (N)
 *   + plaintext out (N)                                       ≈ 4 × N
 *
 * With `largeHeap="true"` that ceiling lands somewhere around a 60-100 MB clip,
 * i.e. half a minute of 1080p phone footage, and the `OutOfMemoryError` was
 * swallowed by `runCatching` into a silent `null` (report #691, item 3: "long
 * videos do not download": no error, just nothing).
 *
 * ## The container
 *
 * The plaintext is cut into fixed-size chunks and each chunk gets its own
 * AES-256-GCM seal under the SAME per-blob key. Encrypting and decrypting then
 * cost one chunk of memory each, whatever the file weighs.
 *
 * ```
 *   offset  0 : magic       "RCQM1"                     5
 *   offset  5 : version     0x01                        1
 *   offset  6 : chunkSize   uint32 BE, plaintext bytes  4
 *   offset 10 : chunkCount  uint32 BE                   4
 *   offset 14 : plainLen    uint64 BE                   8
 *   offset 22 : noncePrefix random                      8
 *                                                      -- 30 bytes
 *   then chunkCount records, record i =
 *       ciphertext(len_i) || tag(16)
 *   len_i = chunkSize, except the last = plainLen - chunkSize*(chunkCount-1)
 * ```
 *
 * * nonce for chunk i = `noncePrefix || uint32BE(i)`. The prefix is fresh
 *   random per blob and the KEY is fresh random per blob, so no nonce is ever
 *   reused under a key even if two blobs draw the same prefix.
 * * AAD for chunk i = `header(30) || uint32BE(i)`.
 *
 * ## Whole-file integrity, not just per-chunk integrity
 *
 * Every structural fact lives in the header, and the header is in the AAD of
 * every single chunk. So a tag failure is the answer to all of:
 *
 * * a chunk moved, duplicated, or swapped in from elsewhere: index mismatch;
 * * a chunk dropped: the reader walks indices in order and the next tag is
 *   bound to the index it expected;
 * * the file truncated: `chunkCount` and `plainLen` are authenticated, and the
 *   reader fails when the bytes run out before the count does;
 * * the file extended, or `chunkSize`/`plainLen` edited: every tag breaks.
 *
 * The one property a chunked AEAD cannot have, and this one does not claim, is
 * that the LAST chunk is verified before the FIRST is used. A player that
 * starts on chunk 0 has verified chunk 0 and nothing after it. That is the
 * deliberate trade (the same one Tink's StreamingAEAD and age make), and it is
 * confined to playback. [streamTo], which is save, share and export, verifies every chunk
 * to the end, and its callers only publish the output file once it returns
 * true.
 *
 * ## Compatibility
 *
 * Nothing on the wire above the blob changes: the envelope still carries a
 * media id and a base64 key, and the server still stores opaque bytes. The
 * container is chosen by SIZE at send time and detected by MAGIC at open time,
 * so an ordinary photo, voice note or short clip keeps the byte-for-byte
 * [MediaCrypto] layout every shipped client already reads. Only files past
 * `Session.streamThresholdBytes`, which is comfortably past the point where
 * the monolithic path OOMs on the sender's own phone, use this.
 */
object MediaStream {

    /** Plaintext bytes per chunk. 16 B of tag per MiB is 0.0015% overhead, and
     *  1 MiB is small enough that a decrypt is imperceptible during a seek. */
    const val CHUNK_SIZE = 1 shl 20

    private val MAGIC = byteArrayOf('R'.code.toByte(), 'C'.code.toByte(), 'Q'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte())
    private const val VERSION: Byte = 1
    const val HEADER_LEN = 30
    private const val TAG_LEN = 16
    private const val NONCE_PREFIX_LEN = 8

    /** Sanity bounds on a header read off the network. A blob claiming a 2 GB
     *  chunk size must not make us allocate one. */
    private const val MIN_CHUNK = 64 * 1024
    private const val MAX_CHUNK = 16 * 1024 * 1024

    /** Absolute ceiling on a declared plaintext length. Two orders of magnitude
     *  past any island's blob cap, and the point is only that the arithmetic
     *  below runs on a number somebody could have meant. */
    private const val MAX_PLAIN = 64L * 1024 * 1024 * 1024

    /** True if these leading bytes are an RCQM1 container rather than a
     *  [MediaCrypto] blob (which starts with a raw 12-byte GCM nonce and can
     *  therefore collide with this magic only by 1 in 2^40 chance per blob, and
     *  and a nonce is not attacker-chosen, it is ours). */
    fun looksChunked(head: ByteArray): Boolean {
        if (head.size < MAGIC.size + 1) return false
        for (i in MAGIC.indices) if (head[i] != MAGIC[i]) return false
        return head[MAGIC.size] == VERSION
    }

    fun looksChunked(file: File): Boolean = runCatching {
        val head = ByteArray(HEADER_LEN)
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < HEADER_LEN) return false
            raf.readFully(head)
        }
        looksChunked(head)
    }.getOrDefault(false)

    fun chunkCountFor(plainLen: Long, chunkSize: Int = CHUNK_SIZE): Int {
        if (plainLen <= 0L) return 1
        val n = (plainLen + chunkSize - 1) / chunkSize
        require(n <= Int.MAX_VALUE) { "media too large for one container" }
        return n.toInt()
    }

    /** Exact encoded length, so an upload can declare a real Content-Length
     *  instead of falling back to chunked transfer-encoding. */
    fun blobLength(plainLen: Long, chunkSize: Int = CHUNK_SIZE): Long =
        HEADER_LEN + plainLen + TAG_LEN.toLong() * chunkCountFor(plainLen, chunkSize)

    private fun putU32(dst: ByteArray, at: Int, v: Int) {
        dst[at] = (v ushr 24).toByte()
        dst[at + 1] = (v ushr 16).toByte()
        dst[at + 2] = (v ushr 8).toByte()
        dst[at + 3] = v.toByte()
    }

    private fun u32(src: ByteArray, at: Int): Int =
        ((src[at].toInt() and 0xff) shl 24) or
            ((src[at + 1].toInt() and 0xff) shl 16) or
            ((src[at + 2].toInt() and 0xff) shl 8) or
            (src[at + 3].toInt() and 0xff)

    private fun putU64(dst: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) dst[at + i] = (v ushr (56 - 8 * i)).toByte()
    }

    private fun u64(src: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (src[at + i].toLong() and 0xff)
        return v
    }

    private fun buildHeader(plainLen: Long, chunkSize: Int, noncePrefix: ByteArray): ByteArray {
        val h = ByteArray(HEADER_LEN)
        System.arraycopy(MAGIC, 0, h, 0, MAGIC.size)
        h[5] = VERSION
        putU32(h, 6, chunkSize)
        putU32(h, 10, chunkCountFor(plainLen, chunkSize))
        putU64(h, 14, plainLen)
        System.arraycopy(noncePrefix, 0, h, 22, NONCE_PREFIX_LEN)
        return h
    }

    private fun nonceFor(header: ByteArray, index: Int): ByteArray {
        val n = ByteArray(12)
        System.arraycopy(header, 22, n, 0, NONCE_PREFIX_LEN)
        putU32(n, NONCE_PREFIX_LEN, index)
        return n
    }

    private fun aadFor(header: ByteArray, index: Int): ByteArray {
        val a = ByteArray(HEADER_LEN + 4)
        System.arraycopy(header, 0, a, 0, HEADER_LEN)
        putU32(a, HEADER_LEN, index)
        return a
    }

    private fun cipherFor(mode: Int, key: ByteArray, header: ByteArray, index: Int): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, nonceFor(header, index)))
            updateAAD(aadFor(header, index))
        }

    // ── encrypt ─────────────────────────────────────────────────────────

    /**
     * Seal exactly [plainLen] bytes from [input] into [out] as an RCQM1
     * container. Never holds more than one chunk of plaintext plus one of
     * ciphertext, so a 2 GB film costs the same memory as a 2 MB one.
     *
     * [plainLen] MUST be the true length: the encoded size is declared to the
     * server as Content-Length before a byte moves, so a stream that runs
     * short or long is a hard failure here rather than a truncated blob the
     * receiver discovers days later.
     */
    @Throws(IOException::class)
    fun seal(
        input: InputStream,
        out: OutputStream,
        key: ByteArray,
        plainLen: Long,
        chunkSize: Int = CHUNK_SIZE,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ) {
        require(chunkSize in MIN_CHUNK..MAX_CHUNK) { "bad chunk size" }
        val noncePrefix = ByteArray(NONCE_PREFIX_LEN).also { SecureRandom().nextBytes(it) }
        val header = buildHeader(plainLen, chunkSize, noncePrefix)
        val total = blobLength(plainLen, chunkSize)
        out.write(header)
        var written = HEADER_LEN.toLong()
        onProgress?.invoke(written, total)

        val count = chunkCountFor(plainLen, chunkSize)
        val plain = ByteArray(chunkSize)
        var consumed = 0L
        for (i in 0 until count) {
            val want = minOf(chunkSize.toLong(), plainLen - consumed).toInt().coerceAtLeast(0)
            var got = 0
            while (got < want) {
                val n = input.read(plain, got, want - got)
                if (n < 0) throw IOException("media source ended at $consumed of $plainLen bytes")
                got += n
            }
            consumed += got
            val sealed = cipherFor(Cipher.ENCRYPT_MODE, key, header, i).doFinal(plain, 0, got)
            out.write(sealed)
            written += sealed.size
            onProgress?.invoke(written, total)
        }
        // A source that still has bytes lied about its length, and the blob we
        // just wrote is a truncation of the user's video. Say so here rather
        // than let it look like a successful send.
        if (input.read() >= 0) throw IOException("media source longer than the declared $plainLen bytes")
        out.flush()
    }

    // ── decrypt ─────────────────────────────────────────────────────────

    /**
     * Random-access reader over an RCQM1 file. Decrypts one chunk at a time and
     * keeps a handful around so a seek that lands back inside a chunk we
     * already opened does not pay for it twice.
     *
     * Thread-safe: MediaPlayer calls into it from its own decoder threads.
     */
    class Reader(file: File, private val key: ByteArray) : Closeable {
        private val raf = RandomAccessFile(file, "r")
        private val header = ByteArray(HEADER_LEN)
        val chunkSize: Int
        val chunkCount: Int
        val plainLen: Long

        /** A chunk failed to authenticate while this reader was being read.
         *
         *  ⚠⚠ It exists because [readAt] CANNOT report the failure the way it
         *  happened. Its contract is a byte count, the framework reads a short
         *  count followed by -1 as the end of the stream, and an exception
         *  thrown across the JNI boundary is caught there and turned into the
         *  same -1. So a tampered container plays and stops early, the player
         *  fires "completed", and the person concludes they were sent a short
         *  clip: an integrity failure presented as content. Whoever drives the
         *  player has to ask this before believing a completion. */
        @Volatile
        var integrityFailed: Boolean = false
            private set

        /** Up to four decrypted chunks, least-recently-used dropped first. An
         *  mp4 player bounces between the moov atom and the mdat it indexes, so
         *  a cache of one would decrypt the same megabyte over and over. */
        private val cache = object : LinkedHashMap<Int, ByteArray>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>): Boolean = size > 4
        }

        init {
            if (raf.length() < HEADER_LEN) {
                raf.close()
                throw IOException("not an RCQM1 blob (too short)")
            }
            raf.readFully(header)
            if (!looksChunked(header)) {
                raf.close()
                throw IOException("not an RCQM1 blob (bad magic)")
            }
            chunkSize = u32(header, 6)
            chunkCount = u32(header, 10)
            plainLen = u64(header, 14)
            // Everything below is authenticated by the per-chunk tags, but a
            // header is read BEFORE any tag has been checked, so it is checked
            // for sanity first: an absurd chunkSize is an allocation, and an
            // absurd chunkCount is a loop.
            // ⚠ Order matters: the length bound comes before any arithmetic on
            // it. `||` short-circuits, so a header claiming 2^63 bytes is
            // rejected here rather than inside chunkCountFor.
            val bad = chunkSize !in MIN_CHUNK..MAX_CHUNK ||
                plainLen < 0L ||
                plainLen > MAX_PLAIN ||
                chunkCount <= 0 ||
                chunkCount != chunkCountFor(plainLen, chunkSize) ||
                raf.length() != blobLength(plainLen, chunkSize)
            if (bad) {
                raf.close()
                throw IOException("RCQM1 header does not describe this file")
            }
        }

        private fun plainLenOf(index: Int): Int =
            minOf(chunkSize.toLong(), plainLen - index.toLong() * chunkSize).toInt().coerceAtLeast(0)

        /** Decrypt and VERIFY chunk [index]. Throws if the tag does not check
         *  out, which for this container also means "reordered, truncated, or
         *  edited"; see the class doc. */
        @Synchronized
        @Throws(IOException::class)
        fun chunk(index: Int): ByteArray {
            if (index < 0 || index >= chunkCount) throw IOException("chunk $index out of range")
            cache[index]?.let { return it }
            val len = plainLenOf(index) + TAG_LEN
            val buf = ByteArray(len)
            raf.seek(HEADER_LEN.toLong() + index.toLong() * (chunkSize + TAG_LEN))
            raf.readFully(buf)
            val plain = try {
                cipherFor(Cipher.DECRYPT_MODE, key, header, index).doFinal(buf)
            } catch (e: Exception) {
                throw IOException("chunk $index failed authentication", e)
            }
            cache[index] = plain
            return plain
        }

        /** Plaintext bytes at [position], the shape [android.media.MediaDataSource]
         *  asks for. Returns -1 at (or past) the end. */
        @Synchronized
        fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0 || position >= plainLen || size <= 0) return -1
            var done = 0
            var pos = position
            while (done < size && pos < plainLen) {
                val index = (pos / chunkSize).toInt()
                val within = (pos % chunkSize).toInt()
                val chunk = try {
                    chunk(index)
                } catch (e: IOException) {
                    // A failed tag mid-playback: stop the stream rather than
                    // feed the decoder bytes nobody vouched for. Everything
                    // already copied came out of a chunk that DID verify, so
                    // handing back `done` is honest; the next read fails at the
                    // same chunk and the player sees the end.
                    //
                    // ⚠ And the flag, which is the part that keeps this honest.
                    // Ending the stream is all this method is able to say, and
                    // "the video ended" is exactly what a truncated video looks
                    // like to the person watching. [integrityFailed] is how the
                    // viewer tells the two apart.
                    integrityFailed = true
                    android.util.Log.w("RCQmedia", "chunk $index failed authentication", e)
                    return if (done > 0) done else -1
                }
                val n = minOf(size - done, chunk.size - within)
                if (n <= 0) break
                System.arraycopy(chunk, within, buffer, offset + done, n)
                done += n
                pos += n
            }
            return if (done > 0) done else -1
        }

        @Synchronized
        override fun close() {
            cache.clear()
            runCatching { raf.close() }
        }
    }

    /**
     * Decrypt the whole container to [out], verifying EVERY chunk. Returns
     * false without finishing if any tag fails.
     *
     * This is the path for save-to-gallery, share and export, and it is why
     * those can still promise whole-file integrity: the caller writes into a
     * pending/temporary sink and only publishes it when this returns true.
     */
    fun streamTo(
        file: File,
        key: ByteArray,
        out: OutputStream,
        onProgress: ((done: Long, total: Long) -> Unit)? = null,
    ): Boolean = runCatching {
        Reader(file, key).use { r ->
            var done = 0L
            for (i in 0 until r.chunkCount) {
                val plain = r.chunk(i)
                out.write(plain)
                done += plain.size
                onProgress?.invoke(done, r.plainLen)
            }
            out.flush()
            done == r.plainLen
        }
    }.getOrDefault(false)

    /** An [android.media.MediaDataSource] that decrypts on demand, so playback
     *  never materialises the film: not in RAM, and not as a plaintext file a
     *  FileProvider would have to hand to another process.
     *
     *  Carries [integrityFailed] out to whoever drives the player, because the
     *  MediaDataSource contract has no way to say it: see [Reader.integrityFailed]. */
    class ChunkedDataSource internal constructor(private val reader: Reader) : android.media.MediaDataSource() {
        val integrityFailed: Boolean get() = reader.integrityFailed

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
            reader.readAt(position, buffer, offset, size)

        override fun getSize(): Long = reader.plainLen

        override fun close() = reader.close()
    }

    fun dataSource(file: File, key: ByteArray): ChunkedDataSource = ChunkedDataSource(Reader(file, key))
}
