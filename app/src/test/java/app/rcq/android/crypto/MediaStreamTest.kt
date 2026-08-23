package app.rcq.android.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom

/**
 * The RCQM1 container: round trip, random access, and every way the structure
 * can be attacked.
 *
 * The point of these is the last group. A chunked AEAD buys constant memory by
 * giving up ONE tag over the whole file, and it only stays honest if the
 * structure (order, count, length, the end) is authenticated too. Each
 * tamper case below is a thing a monolithic seal made impossible for free and
 * this container has to earn.
 */
class MediaStreamTest {

    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun body(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    private fun sealTo(plain: ByteArray, chunkSize: Int = MediaStream.CHUNK_SIZE): File {
        val f = File.createTempFile("rcqm1", ".bin").apply { deleteOnExit() }
        f.outputStream().use { out ->
            MediaStream.seal(ByteArrayInputStream(plain), out, key, plain.size.toLong(), chunkSize)
        }
        return f
    }

    private fun readAll(f: File): ByteArray? {
        val out = ByteArrayOutputStream()
        return if (MediaStream.streamTo(f, key, out)) out.toByteArray() else null
    }

    // ── round trip ──────────────────────────────────────────────────────

    @Test
    fun `round trips across every chunk boundary`() {
        val chunk = 64 * 1024
        // Empty, one byte, one byte under a chunk, exactly a chunk, one over,
        // several whole chunks, and a ragged tail.
        for (n in listOf(0, 1, chunk - 1, chunk, chunk + 1, chunk * 3, chunk * 3 + 17)) {
            val plain = body(n)
            val f = sealTo(plain, chunk)
            assertEquals("declared length for $n", MediaStream.blobLength(n.toLong(), chunk), f.length())
            assertArrayEquals("round trip of $n bytes", plain, readAll(f))
            f.delete()
        }
    }

    @Test
    fun `container is recognised and a monolithic blob is not`() {
        val f = sealTo(body(4096), 64 * 1024)
        assertTrue(MediaStream.looksChunked(f))
        val legacy = File.createTempFile("legacy", ".bin").apply { deleteOnExit() }
        legacy.writeBytes(MediaCrypto.seal(body(4096), key))
        assertFalse(MediaStream.looksChunked(legacy))
    }

    @Test
    fun `random access matches the plaintext at any offset`() {
        val chunk = 64 * 1024
        val plain = body(chunk * 3 + 1234)
        val f = sealTo(plain, chunk)
        MediaStream.Reader(f, key).use { r ->
            assertEquals(plain.size.toLong(), r.plainLen)
            // Reads that start inside a chunk, span a boundary, span three
            // chunks, and run off the end.
            for ((pos, len) in listOf(
                0L to 10, 7L to 100, (chunk - 5).toLong() to 20, (chunk * 2 - 3).toLong() to chunk + 6,
                (plain.size - 5).toLong() to 50,
            )) {
                val buf = ByteArray(len)
                var got = 0
                while (got < len) {
                    val n = r.readAt(pos + got, buf, got, len - got)
                    if (n < 0) break
                    got += n
                }
                val want = plain.copyOfRange(pos.toInt(), minOf(plain.size, (pos + len).toInt()))
                assertArrayEquals("read $len at $pos", want, buf.copyOf(got))
            }
            // Past the end is end of stream, not an exception: the platform
            // decoder probes there while it hunts for the moov atom.
            assertEquals(-1, r.readAt(plain.size.toLong(), ByteArray(16), 0, 16))
            assertEquals(-1, r.readAt(plain.size + 4096L, ByteArray(16), 0, 16))
        }
    }

    @Test
    fun `a wrong key opens nothing`() {
        val f = sealTo(body(200_000), 64 * 1024)
        val other = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val out = ByteArrayOutputStream()
        assertFalse(MediaStream.streamTo(f, other, out))
    }

    @Test
    fun `a source shorter than it claimed is a failure, not a truncated blob`() {
        val f = File.createTempFile("short", ".bin").apply { deleteOnExit() }
        var threw = false
        try {
            f.outputStream().use { out ->
                // Claim 100 KB, supply 10 KB.
                MediaStream.seal(ByteArrayInputStream(body(10_000)), out, key, 100_000L, 64 * 1024)
            }
        } catch (e: java.io.IOException) {
            threw = true
        }
        assertTrue("a short source must fail loudly", threw)
    }

    @Test
    fun `a source longer than it claimed is a failure too`() {
        val f = File.createTempFile("long", ".bin").apply { deleteOnExit() }
        var threw = false
        try {
            f.outputStream().use { out ->
                MediaStream.seal(ByteArrayInputStream(body(100_000)), out, key, 10_000L, 64 * 1024)
            }
        } catch (e: java.io.IOException) {
            threw = true
        }
        assertTrue("a long source must fail loudly", threw)
    }

    // ── structure, which is the whole argument ──────────────────────────

    @Test
    fun `a flipped bit anywhere fails`() {
        val chunk = 64 * 1024
        val f = sealTo(body(chunk * 2 + 99), chunk)
        val bytes = f.readBytes()
        // First chunk's ciphertext, its tag, and the last chunk.
        for (at in listOf(MediaStream.HEADER_LEN + 5, MediaStream.HEADER_LEN + chunk + 3, bytes.size - 20)) {
            val hurt = bytes.copyOf()
            hurt[at] = (hurt[at].toInt() xor 0x01).toByte()
            val g = File.createTempFile("hurt", ".bin").apply { deleteOnExit() }
            g.writeBytes(hurt)
            assertFalse("a flipped bit at $at must fail", MediaStream.streamTo(g, key, ByteArrayOutputStream()))
            g.delete()
        }
    }

    @Test
    fun `two chunks swapped fail`() {
        val chunk = 64 * 1024
        val f = sealTo(body(chunk * 3), chunk)
        val bytes = f.readBytes()
        val recSize = chunk + 16
        val swapped = bytes.copyOf()
        val a = MediaStream.HEADER_LEN
        val b = MediaStream.HEADER_LEN + recSize
        System.arraycopy(bytes, b, swapped, a, recSize)
        System.arraycopy(bytes, a, swapped, b, recSize)
        val g = File.createTempFile("swap", ".bin").apply { deleteOnExit() }
        g.writeBytes(swapped)
        // Every chunk is sealed against its own index, so a chunk that moved is
        // a chunk whose AAD no longer matches. This is the property a single
        // whole-file tag gave away for nothing and a chunked one has to buy.
        assertFalse(MediaStream.streamTo(g, key, ByteArrayOutputStream()))
    }

    @Test
    fun `a duplicated chunk fails`() {
        val chunk = 64 * 1024
        val f = sealTo(body(chunk * 3), chunk)
        val bytes = f.readBytes()
        val recSize = chunk + 16
        val dup = bytes.copyOf()
        System.arraycopy(bytes, MediaStream.HEADER_LEN, dup, MediaStream.HEADER_LEN + recSize, recSize)
        val g = File.createTempFile("dup", ".bin").apply { deleteOnExit() }
        g.writeBytes(dup)
        assertFalse(MediaStream.streamTo(g, key, ByteArrayOutputStream()))
    }

    @Test
    fun `a truncated container fails rather than playing short`() {
        val chunk = 64 * 1024
        val f = sealTo(body(chunk * 3), chunk)
        val bytes = f.readBytes()
        val g = File.createTempFile("cut", ".bin").apply { deleteOnExit() }
        // Cut one whole record off the end. The header still says three chunks
        // and the header is authenticated, so this is not a shorter video, it
        // is a broken one.
        g.writeBytes(bytes.copyOf(bytes.size - (chunk + 16)))
        assertFalse(MediaStream.streamTo(g, key, ByteArrayOutputStream()))
    }

    @Test
    fun `an edited header fails`() {
        val chunk = 64 * 1024
        val f = sealTo(body(chunk * 2 + 10), chunk)
        val bytes = f.readBytes()
        // Claim one fewer chunk. The length arithmetic no longer describes the
        // file, and even if it did, the header is in every chunk's AAD.
        val lied = bytes.copyOf()
        lied[13] = (lied[13] - 1).toByte()
        val g = File.createTempFile("lie", ".bin").apply { deleteOnExit() }
        g.writeBytes(lied)
        assertFalse(MediaStream.streamTo(g, key, ByteArrayOutputStream()))
    }

    @Test
    fun `chunks of one blob cannot be spliced into another`() {
        val chunk = 64 * 1024
        val a = sealTo(body(chunk * 2), chunk)
        val b = sealTo(body(chunk * 2), chunk)
        val recSize = chunk + 16
        val mixed = a.readBytes().copyOf()
        System.arraycopy(b.readBytes(), MediaStream.HEADER_LEN, mixed, MediaStream.HEADER_LEN, recSize)
        val g = File.createTempFile("mix", ".bin").apply { deleteOnExit() }
        g.writeBytes(mixed)
        // Two blobs sealed under the SAME key here, which is the hardest case:
        // in production every blob draws its own key, so this cannot even be
        // attempted. It still fails, because the nonce prefix in the header is
        // per blob and the header is authenticated.
        assertFalse(MediaStream.streamTo(g, key, ByteArrayOutputStream()))
    }

    @Test
    fun `encoded length is exact, which is what lets an upload declare it`() {
        val chunk = 64 * 1024
        for (n in listOf(0L, 1L, chunk.toLong(), chunk * 5L + 1)) {
            val f = sealTo(body(n.toInt()), chunk)
            assertEquals(MediaStream.blobLength(n, chunk), f.length())
            f.delete()
        }
    }
}
