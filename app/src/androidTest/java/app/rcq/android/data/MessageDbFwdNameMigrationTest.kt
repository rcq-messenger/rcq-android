package app.rcq.android.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.rcq.android.model.ChatMessage
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device twin of the JVM `MessageDbSchemaTest` (report 1063): a real v20
 * SQLCipher database opens as v21, keeps its rows, reads them back with no
 * forward label, and stores and reads a label from then on.
 */
@RunWith(AndroidJUnit4::class)
class MessageDbFwdNameMigrationTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val acctId = "test-fwdname-migrate"
    private val key = ByteArray(32) { (it + 7).toByte() }

    @After
    fun cleanup() = MessageDb.wipeAccount(ctx, acctId)

    @Test
    fun v20_upgrades_and_keeps_its_rows() {
        MessageDb.wipeAccount(ctx, acctId)
        // A v20 database as 0.212 left it: the fresh schema minus fwd_name.
        val file = ctx.getDatabasePath("rcq-messages-$acctId.db")
        file.parentFile?.mkdirs()
        System.loadLibrary("sqlcipher")
        val old = SQLiteDatabase.openOrCreateDatabase(file, MessageDb.passphrase(key), null, null)
        old.execSQL(MessageDbSchema.CREATE_MESSAGES.replace(",\n  fwd_name   TEXT", ""))
        MessageDbSchema.createStatements().drop(1).forEach { old.execSQL(it) }
        old.execSQL("INSERT INTO messages (id, peer_uin, from_me, body, sent_at) VALUES ('m1', 42, 0, 'before', 1000)")
        old.version = 20
        old.close()

        val db = MessageDb(ctx, acctId, key)
        val before = db.all().single { it.id == "m1" }
        assertEquals("before", before.body)
        assertNull(before.fwdName)
        assertTrue(db.insert(ChatMessage(id = "m2", peerUin = 42, fromMe = true, body = "after", sentAt = 2000L, fwdName = "Alice")))
        db.close()

        val again = MessageDb(ctx, acctId, key)
        assertEquals("Alice", again.all().single { it.id == "m2" }.fwdName)
        again.close()
    }
}
