package app.rcq.android.data

import android.content.ContentValues
import android.content.Context
import android.util.Log
import app.rcq.android.model.ChatMessage
import app.rcq.android.model.DeliveryState
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject
import java.io.File

/** Reactions persist as a JSON object {"<uin>":"<asset>"}. Old rows (pre-0.18)
 *  stored a ``-joined asset list with no reactor — those carried no UIN,
 *  so on read they're dropped (a cosmetic one-time loss; the next reaction
 *  repopulates). */
private fun reactionsToJson(r: Map<Int, String>): String =
    JSONObject().apply { r.forEach { (uin, asset) -> put(uin.toString(), asset) } }.toString()

private fun reactionsFromJson(s: String?): Map<Int, String> {
    if (s.isNullOrEmpty() || s.first() != '{') return emptyMap()
    return runCatching {
        val o = JSONObject(s)
        buildMap { o.keys().forEach { k -> k.toIntOrNull()?.let { put(it, o.getString(k)) } } }
    }.getOrDefault(emptyMap())
}

/**
 * Local message store, encrypted at rest with SQLCipher. The backend drains
 * messages on delivery (rcq-spec 6.3.1), so chat history only survives if the
 * client keeps it. Hand-rolled SQLite (like iOS) rather than Room.
 *
 * The whole DB is encrypted under a [dataKey] passphrase: a per-device key when
 * no PIN is set (always-on at-rest encryption, auto-unlocked with the device),
 * or the PIN-vault key when a panic PIN is configured (history then only
 * decrypts after PIN entry). Changing the PIN is a single [rekey]
 * (`PRAGMA rekey` under the hood), never a per-row re-encryption.
 *
 * The primary key is the envelope UUID and inserts are INSERT OR IGNORE, which
 * de-dups a message arriving over both the WebSocket and the queue drain.
 * Multi-account: the file is named per [Account.id] (`rcq-messages-<id>.db`).
 */
class MessageDb(context: Context, accountId: String, dataKey: ByteArray) {

    private val db: SQLiteDatabase

    init {
        val file = context.applicationContext.getDatabasePath(dbName(accountId))
        file.parentFile?.mkdirs()
        db = SQLiteDatabase.openOrCreateDatabase(file, passphrase(dataKey), null, null)
        when (val v = db.version) {
            0 -> { createSchema(db); db.version = VERSION }
            in 1 until VERSION -> { upgrade(db, v); db.version = VERSION }
        }
    }

    /** Re-encrypt the entire DB to [newKey] (PIN set / change / remove). */
    fun rekey(newKey: ByteArray) = db.changePassword(passphrase(newKey))

    /** Close the underlying connection (called when the session rebinds/locks). */
    fun close() { runCatching { db.close() } }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
              id        TEXT PRIMARY KEY,
              peer_uin  INTEGER NOT NULL,
              from_me   INTEGER NOT NULL,
              body      TEXT NOT NULL,
              sent_at   INTEGER NOT NULL,
              state     TEXT NOT NULL DEFAULT 'DELIVERED',
              kind      TEXT NOT NULL DEFAULT 'text',
              media_id  TEXT,
              media_key TEXT,
              reply_snippet TEXT,
              reply_author  TEXT,
              group_id   INTEGER,
              sender_uin INTEGER,
              reactions  TEXT,
              edited     INTEGER NOT NULL DEFAULT 0,
              file_name  TEXT,
              file_mime  TEXT,
              file_size  INTEGER,
              duration_sec INTEGER,
              thumb_b64  TEXT,
              lat        REAL,
              lng        REAL,
              spoiler    INTEGER NOT NULL DEFAULT 0,
              album_id   TEXT,
              reply_to_id TEXT,
              expires_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_peer ON messages(peer_uin, sent_at)")
        db.execSQL("CREATE INDEX idx_messages_group ON messages(group_id, sent_at)")
        db.execSQL(DELETED_IDS_DDL)
        db.execSQL(DECOY_CONTACTS_DDL)
    }

    private fun upgrade(db: SQLiteDatabase, oldVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE messages ADD COLUMN state TEXT NOT NULL DEFAULT 'DELIVERED'")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'text'")
            db.execSQL("ALTER TABLE messages ADD COLUMN media_id TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN media_key TEXT")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reply_snippet TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN reply_author TEXT")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE messages ADD COLUMN group_id INTEGER")
            db.execSQL("ALTER TABLE messages ADD COLUMN sender_uin INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_group ON messages(group_id, sent_at)")
        }
        if (oldVersion < 6) db.execSQL("ALTER TABLE messages ADD COLUMN reactions TEXT")
        if (oldVersion < 7) db.execSQL("ALTER TABLE messages ADD COLUMN edited INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE messages ADD COLUMN file_name TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN file_mime TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN file_size INTEGER")
        }
        if (oldVersion < 9) db.execSQL("ALTER TABLE messages ADD COLUMN duration_sec INTEGER")
        if (oldVersion < 10) db.execSQL("ALTER TABLE messages ADD COLUMN thumb_b64 TEXT")
        if (oldVersion < 11) {
            db.execSQL("ALTER TABLE messages ADD COLUMN lat REAL")
            db.execSQL("ALTER TABLE messages ADD COLUMN lng REAL")
        }
        if (oldVersion < 12) db.execSQL("ALTER TABLE messages ADD COLUMN spoiler INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 13) db.execSQL("ALTER TABLE messages ADD COLUMN album_id TEXT")
        if (oldVersion < 14) db.execSQL("ALTER TABLE messages ADD COLUMN reply_to_id TEXT")
        if (oldVersion < 15) db.execSQL("ALTER TABLE messages ADD COLUMN expires_at INTEGER")
        if (oldVersion < 16) db.execSQL(DELETED_IDS_DDL)
        if (oldVersion < 17) db.execSQL(DECOY_CONTACTS_DDL)
    }

    // ── decoy roster (only ever populated in the DECOY store) ────────────
    //
    // The duress view needs a contact list, and it must not be the real one:
    // the peers in the decoy store carry SYNTHETIC uins, so the real roster
    // would not match a single thread. Kept inside this SQLCipher file — under
    // the decoy slot's own key — rather than in prefs, which are readable
    // without any PIN. On a real account's db the table exists and stays empty.

    fun putDecoyContact(uin: Int, nickname: String) {
        db.execSQL("INSERT OR REPLACE INTO decoy_contacts (uin, nickname) VALUES (?, ?)", arrayOf<Any>(uin, nickname))
    }

    fun decoyContacts(): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        db.rawQuery("SELECT uin, nickname FROM decoy_contacts ORDER BY rowid ASC", null).use { c ->
            while (c.moveToNext()) out.add(c.getInt(0) to c.getString(1))
        }
        return out
    }

    fun clearDecoyContacts() { db.execSQL("DELETE FROM decoy_contacts") }

    /** Drop every row this store holds (messages, tombstones, decoy roster).
     *  Used when re-seeding the decoy store, which is always a full rebuild. */
    fun wipeAll() {
        db.execSQL("DELETE FROM messages")
        db.execSQL("DELETE FROM deleted_ids")
        db.execSQL("DELETE FROM decoy_contacts")
    }

    /** Delete every message whose disappearing-message TTL has elapsed and
     *  return their ids so the caller can drop them from the in-memory flows.
     *  Cheap (indexed by the WHERE on a nullable column is fine at our row
     *  counts) and idempotent. */
    fun deleteExpired(nowMs: Long): List<String> {
        val ids = ArrayList<String>()
        db.rawQuery("SELECT id FROM messages WHERE expires_at IS NOT NULL AND expires_at <= ?", arrayOf(nowMs.toString())).use { c ->
            while (c.moveToNext()) ids.add(c.getString(0))
        }
        if (ids.isNotEmpty()) db.delete("messages", "expires_at IS NOT NULL AND expires_at <= ?", arrayOf(nowMs.toString()))
        return ids
    }

    /** Insert; returns true if it was new (false if the UUID already existed,
     *  or if this message was deleted here before — see [delete]).
     *
     *  [honourTombstones] exists for exactly one caller: restoring a backup.
     *  A restore is the user asking for their history back, so a message they
     *  once deleted and later chose to restore has to come back — the guard is
     *  there to stop the offline queue resurrecting things behind their back,
     *  not to overrule them. That path clears the tombstone instead. */
    fun insert(msg: ChatMessage, honourTombstones: Boolean = true): Boolean {
        if (honourTombstones && isDeleted(msg.id)) return false
        if (!honourTombstones) db.delete("deleted_ids", "id = ?", arrayOf(msg.id))
        val values = ContentValues().apply {
            put("id", msg.id)
            put("peer_uin", msg.peerUin)
            put("from_me", if (msg.fromMe) 1 else 0)
            put("body", msg.body)
            put("sent_at", msg.sentAt)
            put("state", msg.state.name)
            put("kind", msg.kind)
            put("media_id", msg.mediaId)
            put("media_key", msg.mediaKey)
            put("reply_snippet", msg.replyToSnippet)
            put("reply_author", msg.replyToAuthor)
            put("group_id", msg.groupId)
            put("sender_uin", msg.senderUin)
            put("reactions", reactionsToJson(msg.reactions))
            put("edited", if (msg.edited) 1 else 0)
            put("file_name", msg.fileName)
            put("file_mime", msg.fileMime)
            put("file_size", msg.fileSize)
            put("duration_sec", msg.durationSec)
            put("thumb_b64", msg.thumbB64)
            put("lat", msg.lat)
            put("lng", msg.lng)
            put("spoiler", if (msg.spoiler) 1 else 0)
            put("album_id", msg.albumId)
            put("reply_to_id", msg.replyToId)
            put("expires_at", msg.expiresAt)
        }
        val rowId = db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return rowId != -1L
    }

    fun updateState(id: String, state: DeliveryState) {
        db.update("messages", ContentValues().apply { put("state", state.name) }, "id = ?", arrayOf(id))
    }

    fun updateReactions(id: String, reactions: Map<Int, String>) {
        db.update("messages", ContentValues().apply { put("reactions", reactionsToJson(reactions)) }, "id = ?", arrayOf(id))
    }

    /** Replace a message's body and mark it edited. */
    fun updateBody(id: String, body: String) {
        db.update("messages", ContentValues().apply { put("body", body); put("edited", 1) }, "id = ?", arrayOf(id))
    }

    fun wipe() {
        db.execSQL("DELETE FROM messages")
    }

    /** Delete one message AND remember that we did.
     *
     *  The tombstone is the point. Dedup on the way in was `INSERT OR IGNORE`
     *  on the envelope uuid, which only works while the row still exists — so
     *  deleting a message removed the very thing that was keeping its copies
     *  out. The offline queue still held one, and the next launch ingested it
     *  through the RECEIVE path: the note you deleted came back as an unread
     *  incoming message, carrying its original text because the edit envelope
     *  had been applied to a row that no longer existed. That is report #415,
     *  all three of its symptoms from one cause.
     *
     *  Bulk clears (`clearPeerThread` / `clearGroupThread` / `deleteExpired`)
     *  deliberately do NOT come through here: they erase whole conversations of
     *  already-delivered history, where there is nothing left in the queue to
     *  resurrect, and tombstoning them would grow this table by the size of the
     *  history it just removed. */
    fun delete(id: String) {
        db.delete("messages", "id = ?", arrayOf(id))
        db.execSQL("INSERT OR IGNORE INTO deleted_ids (id, at) VALUES (?, ?)", arrayOf<Any>(id, System.currentTimeMillis()))
    }

    /** Every tombstone, for the backup archive.
     *
     *  ⚠ These belong in a backup as much as the messages do. Without them a
     *  restore starts with a clean slate, and anything the user had deleted
     *  that is still sitting in the island's offline queue walks straight back
     *  in — reported as "удаление сообщений работает фиктивно". The rows are
     *  ids and timestamps, no content. */
    fun allDeletedIds(): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        db.rawQuery("SELECT id, at FROM deleted_ids", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getLong(1))
        }
        return out
    }

    /** Re-arm a tombstone carried in from a backup. */
    fun markDeleted(id: String, at: Long) {
        db.execSQL("INSERT OR IGNORE INTO deleted_ids (id, at) VALUES (?, ?)", arrayOf<Any>(id, at))
    }

    /** True if this envelope was deleted here before. */
    fun isDeleted(id: String): Boolean =
        db.rawQuery("SELECT 1 FROM deleted_ids WHERE id = ? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }

    /** Erase one 1:1 conversation. `group_id IS NULL` matters: a peer's uin
     *  also appears as `peer_uin` on their messages inside a group, and
     *  clearing a private chat must not take group history with it. */
    fun deletePeerThread(uin: Int) {
        db.delete("messages", "peer_uin = ? AND group_id IS NULL", arrayOf(uin.toString()))
    }

    /** Erase one group conversation. */
    fun deleteGroupThread(groupId: Int) {
        db.delete("messages", "group_id = ?", arrayOf(groupId.toString()))
    }

    fun all(): List<ChatMessage> {
        val out = ArrayList<ChatMessage>()
        db.rawQuery(
            "SELECT id, peer_uin, from_me, body, sent_at, state, kind, media_id, media_key, reply_snippet, reply_author, group_id, sender_uin, reactions, edited, file_name, file_mime, file_size, duration_sec, thumb_b64, lat, lng, spoiler, album_id, reply_to_id, expires_at FROM messages ORDER BY sent_at ASC", null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ChatMessage(
                        id = c.getString(0),
                        peerUin = c.getInt(1),
                        fromMe = c.getInt(2) == 1,
                        body = c.getString(3),
                        sentAt = c.getLong(4),
                        state = runCatching { DeliveryState.valueOf(c.getString(5)) }.getOrDefault(DeliveryState.DELIVERED),
                        kind = c.getString(6) ?: "text",
                        mediaId = c.getString(7),
                        mediaKey = c.getString(8),
                        replyToSnippet = c.getString(9),
                        replyToAuthor = c.getString(10),
                        groupId = if (c.isNull(11)) null else c.getInt(11),
                        senderUin = if (c.isNull(12)) null else c.getInt(12),
                        reactions = reactionsFromJson(c.getString(13)),
                        edited = c.getInt(14) == 1,
                        fileName = c.getString(15),
                        fileMime = c.getString(16),
                        fileSize = if (c.isNull(17)) null else c.getLong(17),
                        durationSec = if (c.isNull(18)) null else c.getInt(18),
                        thumbB64 = c.getString(19),
                        lat = if (c.isNull(20)) null else c.getDouble(20),
                        lng = if (c.isNull(21)) null else c.getDouble(21),
                        spoiler = c.getInt(22) == 1,
                        albumId = c.getString(23),
                        replyToId = c.getString(24),
                        expiresAt = if (c.isNull(25)) null else c.getLong(25),
                    )
                )
            }
        }
        return out
    }

    companion object {
        // SQLCipher's native lib must be loaded before any SQLiteDatabase use.
        // Runs once when the class is first touched (constructor or migration).
        init { System.loadLibrary("sqlcipher") }

        const val VERSION = 17

        /** The decoy store's own contact list (synthetic uins). Created on
         *  every db so the schema is uniform; empty everywhere but the decoy. */
        private const val DECOY_CONTACTS_DDL =
            "CREATE TABLE IF NOT EXISTS decoy_contacts (uin INTEGER PRIMARY KEY, nickname TEXT NOT NULL)"

        /** Envelopes deleted on this device. Kept forever on purpose: a copy can
         *  come back from the offline queue at any later launch, and one row per
         *  deleted message is nothing next to the message itself. */
        private const val DELETED_IDS_DDL =
            "CREATE TABLE IF NOT EXISTS deleted_ids (id TEXT PRIMARY KEY, at INTEGER NOT NULL)"
        private const val LEGACY_NAME = "rcq-messages.db"
        private val SIDECARS = listOf("", "-wal", "-shm", "-journal")

        private fun dbName(accountId: String) = "rcq-messages-$accountId.db"

        /** The SQLCipher passphrase for a 32-byte key: its lowercase hex. */
        fun passphrase(key: ByteArray): String = key.joinToString("") { "%02x".format(it) }

        private fun countRows(db: SQLiteDatabase): Int =
            db.rawQuery("SELECT count(*) FROM messages", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

        /**
         * One-time migration of an existing PLAINTEXT message DB to the
         * SQLCipher-encrypted format under [dataKey]. Idempotent (a marker in
         * [SecureStore] makes it run once per account). Data-loss-safe: exports
         * via `sqlcipher_export`, verifies the encrypted copy opens AND has the
         * same row count, and only then swaps the file. On any failure the
         * plaintext DB is left untouched and false is returned.
         */
        fun migrateToEncrypted(context: Context, accountId: String, dataKey: ByteArray): Boolean {
            val ctx = context.applicationContext
            if (SecureStore.isMsgDbMigrated(ctx, accountId)) return true
            val plain = ctx.getDatabasePath(dbName(accountId))
            if (!plain.exists()) {
                SecureStore.setMsgDbMigrated(ctx, accountId)
                return true
            }
            val tmp = File(plain.path + ".enc")
            SIDECARS.forEach { File(tmp.path + it).delete() }
            tmp.delete()
            return try {
                val pass = passphrase(dataKey)
                // Open plaintext (no key) and read the source version + count.
                val src = SQLiteDatabase.openOrCreateDatabase(plain, null as SQLiteDatabase.CursorFactory?)
                val srcVersion = src.version
                val srcCount = countRows(src)
                src.rawExecSQL("ATTACH DATABASE '${tmp.path}' AS enc KEY '$pass'")
                src.rawExecSQL("SELECT sqlcipher_export('enc')")
                src.rawExecSQL("DETACH DATABASE enc")
                src.close()
                // Verify the encrypted copy independently.
                val enc = SQLiteDatabase.openOrCreateDatabase(tmp, pass, null, null)
                enc.version = srcVersion
                val encCount = countRows(enc)
                enc.close()
                if (encCount != srcCount) {
                    Log.e("RCQpin", "msgdb migration row mismatch ($encCount != $srcCount); keeping plaintext")
                    tmp.delete()
                    return false
                }
                // Swap: drop plaintext (+ sidecars), promote the encrypted file.
                SIDECARS.forEach { File(plain.path + it).delete() }
                if (!tmp.renameTo(plain)) { plain.writeBytes(tmp.readBytes()); tmp.delete() }
                SecureStore.setMsgDbMigrated(ctx, accountId)
                Log.i("RCQpin", "msgdb migrated to encrypted for $accountId ($srcCount rows)")
                true
            } catch (e: Exception) {
                Log.e("RCQpin", "msgdb migration failed: ${e.javaClass.simpleName}: ${e.message}")
                runCatching { tmp.delete() }
                false
            }
        }

        /** Rename the legacy single-account db (+ sidecars) under [accountId]. */
        fun migrateLegacyToAccount(context: Context, accountId: String) {
            val ctx = context.applicationContext
            val legacy = ctx.getDatabasePath(LEGACY_NAME)
            if (!legacy.exists()) return
            val target = ctx.getDatabasePath(dbName(accountId))
            SIDECARS.forEach { suffix ->
                val src = File(legacy.path + suffix)
                if (src.exists()) src.renameTo(File(target.path + suffix))
            }
        }

        /** Does this account's db file exist on disk? */
        fun exists(context: Context, accountId: String): Boolean =
            context.applicationContext.getDatabasePath(dbName(accountId)).exists()

        /** Delete an account's db file (local account delete / burn). */
        fun wipeAccount(context: Context, accountId: String) {
            val ctx = context.applicationContext
            val f = ctx.getDatabasePath(dbName(accountId))
            SIDECARS.forEach { File(f.path + it).delete() }
        }

        const val REACTION_DELIM = ""
    }
}
