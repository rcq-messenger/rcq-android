package app.rcq.android.data

/**
 * The message store's schema and its upgrade path, as plain SQL.
 *
 * Out of [MessageDb] so the two can be checked against each other on the JVM:
 * MessageDb's companion loads SQLCipher's native library the moment it is
 * touched, which a unit test cannot do. Nothing in here opens a database;
 * [MessageDb] runs these statements, in this order, exactly as it always ran
 * them inline.
 */
internal object MessageDbSchema {

    const val VERSION = 21

    /** Every column of `messages`, in table order, which is also the order
     *  [MessageDb.all] reads them back by index. A column added to the table
     *  and forgotten here is a column nothing ever reads back, so the test
     *  pins this list to [CREATE_MESSAGES] and to the upgrade path. */
    val COLUMNS: List<String> = listOf(
        "id", "peer_uin", "from_me", "body", "sent_at", "state", "kind", "media_id", "media_key",
        "reply_snippet", "reply_author", "group_id", "sender_uin", "reactions", "edited",
        "file_name", "file_mime", "file_size", "duration_sec", "thumb_b64", "lat", "lng",
        "spoiler", "album_id", "reply_to_id", "expires_at", "call_id", "fwd_name",
    )

    /** The one query that loads a whole history. */
    val SELECT_ALL: String = "SELECT ${COLUMNS.joinToString(", ")} FROM messages ORDER BY sent_at ASC"

    val CREATE_MESSAGES: String =
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
          expires_at INTEGER,
          call_id    TEXT,
          fwd_name   TEXT
        )
        """.trimIndent()

    /** Last-known group member names (#982), keyed like the cache in
     *  memory: island host (lowercase) + local group id + uin. */
    const val MEMBER_NAMES_DDL =
        "CREATE TABLE IF NOT EXISTS member_names (host TEXT NOT NULL, group_id INTEGER NOT NULL, " +
            "uin INTEGER NOT NULL, nickname TEXT NOT NULL, PRIMARY KEY (host, group_id, uin))"

    /** Partial on purpose: `call_id` is NULL on everything that is not a
     *  call summary, which is all but a handful of rows, and SQLite would
     *  otherwise index every one of them. A `call_id = ?` probe implies
     *  `call_id IS NOT NULL`, so the planner still uses it. */
    const val CALL_ID_INDEX_DDL =
        "CREATE INDEX IF NOT EXISTS idx_messages_call ON messages(call_id) WHERE call_id IS NOT NULL"

    /** The decoy store's own contact list (synthetic uins). Created on
     *  every db so the schema is uniform; empty everywhere but the decoy. */
    const val DECOY_CONTACTS_DDL =
        "CREATE TABLE IF NOT EXISTS decoy_contacts (uin INTEGER PRIMARY KEY, nickname TEXT NOT NULL)"

    /** Envelopes deleted on this device. Kept forever on purpose: a copy can
     *  come back from the offline queue at any later launch, and one row per
     *  deleted message is nothing next to the message itself. */
    const val DELETED_IDS_DDL =
        "CREATE TABLE IF NOT EXISTS deleted_ids (id TEXT PRIMARY KEY, at INTEGER NOT NULL)"

    /** A fresh database, at [VERSION]. */
    fun createStatements(): List<String> = listOf(
        CREATE_MESSAGES,
        "CREATE INDEX idx_messages_peer ON messages(peer_uin, sent_at)",
        "CREATE INDEX idx_messages_group ON messages(group_id, sent_at)",
        CALL_ID_INDEX_DDL,
        DELETED_IDS_DDL,
        DECOY_CONTACTS_DDL,
        MEMBER_NAMES_DDL,
    )

    /** Everything a database stamped [oldVersion] needs to reach [VERSION],
     *  oldest step first. Each step is a plain ALTER or CREATE, so a new
     *  column on an old table gets NULL (or its DEFAULT) and nothing is
     *  rewritten. */
    fun upgradeStatements(oldVersion: Int): List<String> = buildList {
        if (oldVersion < 2) add("ALTER TABLE messages ADD COLUMN state TEXT NOT NULL DEFAULT 'DELIVERED'")
        if (oldVersion < 3) {
            add("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'text'")
            add("ALTER TABLE messages ADD COLUMN media_id TEXT")
            add("ALTER TABLE messages ADD COLUMN media_key TEXT")
        }
        if (oldVersion < 4) {
            add("ALTER TABLE messages ADD COLUMN reply_snippet TEXT")
            add("ALTER TABLE messages ADD COLUMN reply_author TEXT")
        }
        if (oldVersion < 5) {
            add("ALTER TABLE messages ADD COLUMN group_id INTEGER")
            add("ALTER TABLE messages ADD COLUMN sender_uin INTEGER")
            add("CREATE INDEX IF NOT EXISTS idx_messages_group ON messages(group_id, sent_at)")
        }
        if (oldVersion < 6) add("ALTER TABLE messages ADD COLUMN reactions TEXT")
        if (oldVersion < 7) add("ALTER TABLE messages ADD COLUMN edited INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 8) {
            add("ALTER TABLE messages ADD COLUMN file_name TEXT")
            add("ALTER TABLE messages ADD COLUMN file_mime TEXT")
            add("ALTER TABLE messages ADD COLUMN file_size INTEGER")
        }
        if (oldVersion < 9) add("ALTER TABLE messages ADD COLUMN duration_sec INTEGER")
        if (oldVersion < 10) add("ALTER TABLE messages ADD COLUMN thumb_b64 TEXT")
        if (oldVersion < 11) {
            add("ALTER TABLE messages ADD COLUMN lat REAL")
            add("ALTER TABLE messages ADD COLUMN lng REAL")
        }
        if (oldVersion < 12) add("ALTER TABLE messages ADD COLUMN spoiler INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 13) add("ALTER TABLE messages ADD COLUMN album_id TEXT")
        if (oldVersion < 14) add("ALTER TABLE messages ADD COLUMN reply_to_id TEXT")
        if (oldVersion < 15) add("ALTER TABLE messages ADD COLUMN expires_at INTEGER")
        if (oldVersion < 16) add(DELETED_IDS_DDL)
        if (oldVersion < 17) add(DECOY_CONTACTS_DDL)
        // 18: an inbound row's `state` becomes the read-receipt ledger (READ
        // once our receipt for it has left; see Session.sendReadReceipts).
        // Everything already here was acked under the old rule, which sent
        // a receipt for the whole thread on every open, so it starts READ:
        // otherwise the first open of a long chat would re-ack its entire
        // history in one envelope.
        if (oldVersion < 18) add("UPDATE messages SET state = 'READ' WHERE from_me = 0")
        // 19: the call id on the history row (#678/#686). Existing call rows
        // stay NULL and can never be filled in, because the id lived only in
        // the CallController's per-call state and was gone the moment the call
        // ended. An old row therefore never matches a dedupe probe, which is
        // the safe direction: at worst one already-known call from before the
        // upgrade is filed a second time, where guessing would have hidden a
        // real one.
        if (oldVersion < 19) {
            add("ALTER TABLE messages ADD COLUMN call_id TEXT")
            add(CALL_ID_INDEX_DDL)
        }
        // 20: last-known group member names (#982). Starts empty and fills on
        // the next roster fetch; a member who left before the upgrade stays a
        // number unless a quote in the chat still names them.
        if (oldVersion < 20) add(MEMBER_NAMES_DDL)
        // 21: "forwarded from" (report 1063). Every row from before it was
        // not a forward as far as this client knew: the label iOS sent was
        // dropped at parse time and is not recoverable, so NULL is the truth.
        if (oldVersion < 21) add("ALTER TABLE messages ADD COLUMN fwd_name TEXT")
    }
}
