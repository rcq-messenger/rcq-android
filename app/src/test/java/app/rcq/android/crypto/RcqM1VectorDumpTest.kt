package app.rcq.android.crypto

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/** TEMPORARY: writes an RCQM1 vector for the iOS and web readers to open. */
class RcqM1VectorDumpTest {

    @Test
    fun `dump a vector`() {
        val dir = File(System.getProperty("rcq.vector.dir") ?: "/tmp")
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val chunk = 64 * 1024
        // Three full chunks and a ragged tail, so the last record is short.
        val plain = ByteArray(chunk * 3 + 1234) { ((it * 31) xor (it shr 8)).toByte() }
        val out = File(dir, "rcqm1-vector.bin")
        out.outputStream().use { MediaStream.seal(ByteArrayInputStream(plain), it, key, plain.size.toLong(), chunk) }
        File(dir, "rcqm1-vector.key").writeText(key.joinToString("") { "%02x".format(it) })
        File(dir, "rcqm1-vector.plain").writeBytes(plain)
        println("wrote ${out.absolutePath} (${out.length()} bytes)")
    }
}
