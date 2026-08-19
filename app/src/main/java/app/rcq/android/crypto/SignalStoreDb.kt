package app.rcq.android.crypto

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite backing for the libsignal protocol stores (v=2 forward secrecy),
 * scoped per [Account.id] like [MessageDb]. Mirrors the iOS SignalProtocolDB
 * schema so the mental model matches across platforms; the stored records are
 * libsignal-serialized blobs (the serialization is protocol-stable, so the
 * exact table layout is a local detail).
 *
 * Tables: local_identity (our keypair + regId), local_device (which libsignal
 * device of the account THIS install is), prekeys / signed_prekeys /
 * kyber_prekeys (id -> serialized record), sessions / identities /
 * device_outer_keys (address "uin:device" -> blob). Group sender-keys are
 * deferred (1:1 v=2 first).
 */
class SignalStoreDb(context: Context, accountId: String) :
    SQLiteOpenHelper(context.applicationContext, dbName(accountId), null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE local_identity (id INTEGER PRIMARY KEY, uin INTEGER NOT NULL, identity_keypair BLOB NOT NULL, registration_id INTEGER NOT NULL)")
        // pool_device: which of OUR device pools the public half went to.
        // NULL means the primary pool, the only one that existed before this
        // install could be a secondary.
        db.execSQL("CREATE TABLE prekeys (prekey_id INTEGER PRIMARY KEY, record BLOB NOT NULL, pool_device INTEGER)")
        db.execSQL("CREATE TABLE signed_prekeys (signed_prekey_id INTEGER PRIMARY KEY, record BLOB NOT NULL)")
        db.execSQL("CREATE TABLE kyber_prekeys (kyber_prekey_id INTEGER PRIMARY KEY, record BLOB NOT NULL)")
        db.execSQL("CREATE TABLE sessions (address TEXT PRIMARY KEY, record BLOB NOT NULL)")
        db.execSQL("CREATE TABLE identities (address TEXT PRIMARY KEY, identity_key BLOB NOT NULL)")
        // Addresses whose pinned identity key was REPLACED (re-register / new
        // device / possible MITM) and not yet re-verified by the user. Drives
        // the "safety number changed" warning. Presence = changed + unacked.
        db.execSQL("CREATE TABLE identity_changes (address TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE local_device (id INTEGER PRIMARY KEY, device_id INTEGER NOT NULL)")
        // Outer (sealed-sender) key of a PEER's secondary device — the one an
        // envelope for that install is ECIES-sealed to. Device 1 is not kept
        // here: its outer key is the account identity key the contact row
        // already carries.
        db.execSQL("CREATE TABLE device_outer_keys (address TEXT PRIMARY KEY, outer_key BLOB NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS identity_changes (address TEXT PRIMARY KEY)")
        }
        if (oldVersion < 3) {
            // Deliberately left EMPTY rather than seeded with 1: an install
            // that upgrades has to ask the server which device it is, and a
            // seeded row would answer "the primary" for every one of them.
            db.execSQL("CREATE TABLE IF NOT EXISTS local_device (id INTEGER PRIMARY KEY, device_id INTEGER NOT NULL)")
        }
        if (oldVersion < 4) {
            db.execSQL("CREATE TABLE IF NOT EXISTS device_outer_keys (address TEXT PRIMARY KEY, outer_key BLOB NOT NULL)")
            // Everything already in the table was published to the primary
            // pool, which is what a NULL says.
            db.execSQL("ALTER TABLE prekeys ADD COLUMN pool_device INTEGER")
        }
    }

    // ── local identity ───────────────────────────────────────────────
    /** (uin, serialized IdentityKeyPair, registrationId) or null if unset. */
    fun loadLocalIdentity(): Triple<Int, ByteArray, Int>? {
        readableDatabase.rawQuery(
            "SELECT uin, identity_keypair, registration_id FROM local_identity WHERE id = 1", null,
        ).use { c ->
            if (!c.moveToFirst()) return null
            return Triple(c.getInt(0), c.getBlob(1), c.getInt(2))
        }
    }

    fun storeLocalIdentity(uin: Int, identityKeyPair: ByteArray, registrationId: Int) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO local_identity (id, uin, identity_keypair, registration_id) VALUES (1, ?, ?, ?)",
            arrayOf<Any>(uin, identityKeyPair, registrationId),
        )
    }

    // ── local device id ──────────────────────────────────────────────
    /** Which libsignal device of the account this install is, or null while
     *  that is still unresolved (server not asked yet). */
    fun loadDeviceId(): Int? =
        readableDatabase.rawQuery("SELECT device_id FROM local_device WHERE id = 1", null).use {
            if (it.moveToFirst()) it.getInt(0) else null
        }

    fun storeDeviceId(deviceId: Int) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO local_device (id, device_id) VALUES (1, ?)",
            arrayOf<Any>(deviceId),
        )
    }

    // ── int-keyed record tables (prekeys / signed_prekeys / kyber_prekeys) ──
    fun recordByInt(table: String, idCol: String, id: Int): ByteArray? =
        readableDatabase.rawQuery("SELECT record FROM $table WHERE $idCol = ?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) it.getBlob(0) else null
        }

    fun putRecordByInt(table: String, idCol: String, id: Int, record: ByteArray) {
        val v = ContentValues().apply { put(idCol, id); put("record", record) }
        writableDatabase.insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun containsInt(table: String, idCol: String, id: Int): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM $table WHERE $idCol = ? LIMIT 1", arrayOf(id.toString())).use { it.moveToFirst() }

    fun deleteByInt(table: String, idCol: String, id: Int) {
        writableDatabase.delete(table, "$idCol = ?", arrayOf(id.toString()))
    }

    fun allRecords(table: String): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        readableDatabase.rawQuery("SELECT record FROM $table", null).use { c ->
            while (c.moveToNext()) out.add(c.getBlob(0))
        }
        return out
    }

    // ── one-time prekeys, per published pool ─────────────────────────
    /** Unspent one-time prekeys whose public half went to [poolDevice]'s pool.
     *  Untagged rows are the primary's: they were uploaded when the primary
     *  pool was the only one. */
    fun countPreKeysInPool(poolDevice: Int): Int {
        // Interpolated, not bound: a bound argument arrives as TEXT and SQLite
        // compares it to the untagged-row test by type, never by value.
        // [poolDevice] is an Int, so there is nothing to inject.
        val match =
            if (poolDevice == SealedSender.PRIMARY_DEVICE_ID) "pool_device IS NULL OR pool_device = $poolDevice"
            else "pool_device = $poolDevice"
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM prekeys WHERE $match", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun putPreKey(id: Int, record: ByteArray, poolDevice: Int?) {
        val v = ContentValues().apply {
            put("prekey_id", id); put("record", record)
            if (poolDevice == null) putNull("pool_device") else put("pool_device", poolDevice)
        }
        writableDatabase.insertWithOnConflict("prekeys", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Record which pool [ids] were published to, once the server has said
     *  which device this install is. */
    fun setPreKeyPool(ids: List<Int>, poolDevice: Int) {
        if (ids.isEmpty()) return
        writableDatabase.execSQL(
            "UPDATE prekeys SET pool_device = ? WHERE prekey_id IN (${ids.joinToString(",")})",
            arrayOf<Any>(poolDevice),
        )
    }

    // ── text-keyed tables (sessions / identities), key = "uin:device" ──
    fun blobByAddress(table: String, col: String, address: String): ByteArray? =
        readableDatabase.rawQuery("SELECT $col FROM $table WHERE address = ?", arrayOf(address)).use {
            if (it.moveToFirst()) it.getBlob(0) else null
        }

    fun putBlobByAddress(table: String, col: String, address: String, blob: ByteArray) {
        val v = ContentValues().apply { put("address", address); put(col, blob) }
        writableDatabase.insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun containsAddress(table: String, address: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM $table WHERE address = ? LIMIT 1", arrayOf(address)).use { it.moveToFirst() }

    fun deleteByAddress(table: String, address: String) {
        writableDatabase.delete(table, "address = ?", arrayOf(address))
    }

    fun deleteByAddressPrefix(table: String, prefix: String) {
        writableDatabase.delete(table, "address LIKE ?", arrayOf("$prefix%"))
    }

    /** Addresses in [table] starting with "[name]:" (for getSubDeviceSessions). */
    fun addressesWithName(table: String, name: String): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT address FROM $table WHERE address LIKE ?", arrayOf("$name:%")).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun allAddresses(table: String): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT address FROM $table", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    // ── identity-change flags (safety-number "changed" warning) ────────
    fun markIdentityChanged(address: String) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO identity_changes (address) VALUES (?)", arrayOf<Any>(address))
    }

    fun isIdentityChanged(address: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM identity_changes WHERE address = ? LIMIT 1", arrayOf(address)).use { it.moveToFirst() }

    fun clearIdentityChanged(address: String) {
        writableDatabase.delete("identity_changes", "address = ?", arrayOf(address))
    }

    /** Drop all libsignal state (re-bootstrap on UIN drift / server wipe). */
    fun clear() {
        writableDatabase.apply {
            for (t in listOf("local_identity", "local_device", "prekeys", "signed_prekeys", "kyber_prekeys", "sessions", "identities", "identity_changes", "device_outer_keys")) {
                execSQL("DELETE FROM $t")
            }
        }
    }

    companion object {
        const val VERSION = 4
        private fun dbName(accountId: String) = "signal-stores-$accountId.db"

        /** Drop an account's libsignal store file (burn / local delete). */
        fun wipeAccount(context: Context, accountId: String) {
            context.applicationContext.deleteDatabase(dbName(accountId))
        }
    }
}
