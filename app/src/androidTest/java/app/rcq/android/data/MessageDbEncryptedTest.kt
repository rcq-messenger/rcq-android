package app.rcq.android.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.rcq.android.model.ChatMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for the SQLCipher-encrypted message DB: write/read under a
 * key, and rekey (PIN set/change) preserves rows while changing the key.
 */
@RunWith(AndroidJUnit4::class)
class MessageDbEncryptedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val acctId = "test-pin-msgdb"

    @After
    fun cleanup() = MessageDb.wipeAccount(ctx, acctId)

    private fun msg(id: String, body: String) =
        ChatMessage(id = id, peerUin = 42, fromMe = true, body = body, sentAt = 1000L)

    private fun keyOf(seed: Int) = ByteArray(32) { (it + seed).toByte() }

    @Test
    fun writeReadUnderKey() {
        MessageDb.wipeAccount(ctx, acctId)
        val db = MessageDb(ctx, acctId, keyOf(0))
        assertTrue(db.insert(msg("m1", "hello")))
        assertTrue(db.insert(msg("m2", "world")))
        assertEquals(2, db.all().size)
        assertEquals("hello", db.all().first { it.id == "m1" }.body)
        db.close()
        // Reopening under the same key reads it all back.
        val db2 = MessageDb(ctx, acctId, keyOf(0))
        assertEquals(2, db2.all().size)
        db2.close()
    }

    @Test
    fun plaintextMigrationPreservesRows() {
        val migId = "test-pin-migrate"
        SecureStore.wipeAccount(ctx, migId) // clear the "migrated" marker
        MessageDb.wipeAccount(ctx, migId)
        // Seed a PLAINTEXT db (the pre-SQLCipher format) with the framework SQLite.
        val file = ctx.getDatabasePath("rcq-messages-$migId.db")
        file.parentFile?.mkdirs()
        val plain = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        plain.execSQL(
            "CREATE TABLE messages (id TEXT PRIMARY KEY, peer_uin INTEGER NOT NULL, from_me INTEGER NOT NULL, " +
                "body TEXT NOT NULL, sent_at INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'DELIVERED', " +
                "kind TEXT NOT NULL DEFAULT 'text', media_id TEXT, media_key TEXT, reply_snippet TEXT, " +
                "reply_author TEXT, group_id INTEGER, sender_uin INTEGER, reactions TEXT, " +
                "edited INTEGER NOT NULL DEFAULT 0, file_name TEXT, file_mime TEXT, file_size INTEGER, " +
                "duration_sec INTEGER, thumb_b64 TEXT, lat REAL, lng REAL)"
        )
        plain.execSQL("INSERT INTO messages (id, peer_uin, from_me, body, sent_at) VALUES ('m1', 42, 1, 'plainhello', 1000)")
        plain.execSQL("INSERT INTO messages (id, peer_uin, from_me, body, sent_at) VALUES ('m2', 42, 0, 'plainworld', 1001)")
        // ⚠ The schema above is v11 (it ends at lat/lng, which is what v11
        // added), so it must be STAMPED v11. Stamping it MessageDb.VERSION told
        // the opener there was nothing to upgrade, and the first read then
        // asked for `spoiler` (a v12 column) of a table that has no such
        // thing. The test had been failing on that since v12, on a line that
        // looks like bookkeeping. Left as the version it really is, so the
        // whole upgrade chain runs, which is what a plaintext database of that
        // age would actually go through.
        plain.version = 11
        plain.close()
        try {
            // Migrate plaintext -> SQLCipher-encrypted under the device key.
            val deviceKey = keyOf(3)
            assertTrue(MessageDb.migrateToEncrypted(ctx, migId, deviceKey))
            // The encrypted DB opens under that key and every row survived.
            val db = MessageDb(ctx, migId, deviceKey)
            val all = db.all()
            assertEquals(2, all.size)
            assertEquals("plainhello", all.first { it.id == "m1" }.body)
            db.close()
            // And it's genuinely encrypted now: opening with no key fails.
            var plaintextOpenFailed = false
            try {
                MessageDb(ctx, migId, ByteArray(32)).all()
            } catch (e: Exception) {
                plaintextOpenFailed = true
            }
            assertTrue("migrated DB must be encrypted", plaintextOpenFailed)
        } finally {
            MessageDb.wipeAccount(ctx, migId)
            SecureStore.wipeAccount(ctx, migId)
        }
    }

    /**
     * v18 -> v19: the call id on the history row (#678/#686). The column and
     * its partial index are added by ALTER on a live store, so this seeds a
     * real v18 database and opens it through [MessageDb], which is the only
     * place the upgrade runs. A DDL that SQLCipher refuses here would refuse on
     * every phone in the field, and the store would not open at all.
     */
    @Test
    fun callIdColumnMigratesAndDedupes() {
        val migId = "test-callid-migrate"
        SecureStore.wipeAccount(ctx, migId)
        MessageDb.wipeAccount(ctx, migId)
        val file = ctx.getDatabasePath("rcq-messages-$migId.db")
        file.parentFile?.mkdirs()
        val plain = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        // The schema exactly as v18 left it: everything through expires_at, no
        // call_id. Seeded plaintext and then run through the encrypting
        // migration, so the upgrade happens inside SQLCipher like a real one.
        plain.execSQL(
            "CREATE TABLE messages (id TEXT PRIMARY KEY, peer_uin INTEGER NOT NULL, from_me INTEGER NOT NULL, " +
                "body TEXT NOT NULL, sent_at INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'DELIVERED', " +
                "kind TEXT NOT NULL DEFAULT 'text', media_id TEXT, media_key TEXT, reply_snippet TEXT, " +
                "reply_author TEXT, group_id INTEGER, sender_uin INTEGER, reactions TEXT, " +
                "edited INTEGER NOT NULL DEFAULT 0, file_name TEXT, file_mime TEXT, file_size INTEGER, " +
                "duration_sec INTEGER, thumb_b64 TEXT, lat REAL, lng REAL, " +
                "spoiler INTEGER NOT NULL DEFAULT 0, album_id TEXT, reply_to_id TEXT, expires_at INTEGER)"
        )
        // v16 and v17 respectively; a store stamped v18 has them both.
        plain.execSQL("CREATE TABLE deleted_ids (id TEXT PRIMARY KEY, at INTEGER NOT NULL)")
        plain.execSQL("CREATE TABLE decoy_contacts (uin INTEGER PRIMARY KEY, nickname TEXT NOT NULL)")
        plain.execSQL(
            "INSERT INTO messages (id, peer_uin, from_me, body, sent_at, kind) " +
                "VALUES ('old-call', 42, 0, 'missed call', 1000, 'call')"
        )
        plain.version = 18
        plain.close()
        try {
            val key = keyOf(11)
            assertTrue(MessageDb.migrateToEncrypted(ctx, migId, key))
            val db = MessageDb(ctx, migId, key)
            // The pre-upgrade call row survives and simply has no id to match.
            assertEquals(1, db.all().size)
            assertNull(db.all().single().callId)
            assertFalse("an empty id must never match", db.hasCallId(""))
            // A row written after the upgrade carries its id, and that is what
            // the callee's dedupe reads.
            assertTrue(
                db.insert(
                    ChatMessage(
                        id = "new-call", peerUin = 42, fromMe = false, body = "missed call",
                        sentAt = 2000L, kind = "call", callId = "CID-1",
                    )
                )
            )
            assertTrue(db.hasCallId("CID-1"))
            assertFalse(db.hasCallId("CID-2"))
            assertEquals("CID-1", db.all().first { it.id == "new-call" }.callId)
            db.close()
            // And it is on disk, not just in this connection.
            val db2 = MessageDb(ctx, migId, key)
            assertTrue(db2.hasCallId("CID-1"))
            db2.close()
        } finally {
            MessageDb.wipeAccount(ctx, migId)
            SecureStore.wipeAccount(ctx, migId)
        }
    }

    @Test
    fun rekeyChangesKeyKeepsRows() {
        MessageDb.wipeAccount(ctx, acctId)
        val db = MessageDb(ctx, acctId, keyOf(0))
        db.insert(msg("m1", "secret"))
        db.rekey(keyOf(7))
        db.close()
        // The NEW key opens and reads the row.
        val db2 = MessageDb(ctx, acctId, keyOf(7))
        assertEquals("secret", db2.all().single().body)
        db2.close()
        // The OLD key can no longer open the rekeyed DB.
        var failed = false
        try {
            MessageDb(ctx, acctId, keyOf(0)).all()
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("old key must not open the rekeyed DB", failed)
    }
}
