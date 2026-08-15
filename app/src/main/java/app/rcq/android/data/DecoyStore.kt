package app.rcq.android.data

import android.content.Context
import app.rcq.android.model.ChatMessage
import app.rcq.android.model.Contact
import java.security.SecureRandom
import java.util.UUID

/**
 * The decoy (duress) history: its OWN SQLCipher file, opened under the decoy
 * slot's OWN key.
 *
 * Before this, the Android decoy PIN was a visibility filter over a real roster
 * account and its slot carried a copy of the REAL `dataKey` — so handing over
 * the duress PIN handed over the SQLCipher passphrase for every account on the
 * device. Now the decoy slot carries a key that opens exactly one file, this
 * one, and nothing else on the device is reachable with it.
 *
 * ⚠⚠ SEEDING REWRITES IDENTITY. An empty decoy is the tell the feature exists
 * to avoid, so the user picks real conversations to copy in. `peer_uin`,
 * `sender_uin` and the reaction map are NOT encrypted inside the row — they are
 * plain integer columns — so copying rows verbatim would put the real UINs of
 * real people into the duress store in the clear. Anyone who took the duress
 * PIN and imaged the phone would compare those numbers against the real store
 * and see the setup immediately. Every copied row therefore gets:
 *   - a fresh envelope id (the original is a server-issued uuid),
 *   - the peer's uin replaced by a synthetic one allocated here,
 *   - `sender_uin` replaced by the decoy identity for own messages and by the
 *     synthetic peer for theirs,
 *   - reaction keys remapped through the same table, unknown reactors dropped,
 *   - `media_id` / `media_key` dropped: those name blobs on the real account's
 *     island and are fetched with the real account's token.
 * Display NAMES are deliberately kept — they are the plausibility the feature
 * is for, and the user chose these conversations knowing they would be shown.
 */
object DecoyStore {

    /**
     * The decoy history file, as an account-shaped id so it is named
     * `rcq-messages-<uuid>.db` like every other store on disk. Fixed rather
     * than random: nothing outside the vault may record that a decoy exists,
     * and a per-install id would have to be persisted somewhere to be found
     * again. (iOS makes the same trade with a fixed decoy store filename.)
     */
    const val STORE_ID = "8f3c1a64-2d5b-4e07-9a18-c6b0d7e42f95"

    private val rng = SecureRandom()

    /** One conversation the user picked to seed the decoy with. */
    data class SeedThread(val realUin: Int, val displayName: String, val messages: List<ChatMessage>)

    /** A fresh 32-byte SQLCipher key for the decoy store. Never the real one. */
    fun newDataKey(): ByteArray = ByteArray(32).also { rng.nextBytes(it) }

    /** A plausible 9-digit UIN for the decoy identity (iOS parity). */
    fun randomUin(): Int = 100_000_000 + rng.nextInt(900_000_000)

    fun randomNickname(): String = "user-" + (1000 + rng.nextInt(9000))

    /**
     * Rebuild the decoy store from [threads] under [dataKey]. Always a full
     * rebuild: re-seeding is the user changing their mind about what the duress
     * view shows, and a partial one would leave the previous choice in place.
     * Returns false if the store could not be opened or written, in which case
     * the caller must not commit a decoy PIN.
     */
    fun seed(context: Context, dataKey: ByteArray, decoyUin: Int, threads: List<SeedThread>): Boolean {
        // A store left behind by an earlier decoy is sealed under a key we no
        // longer have, so it will not open. That is not a failure to report to
        // the user — it is rubbish in the way of a rebuild. Drop it and retry
        // once. (Nothing is lost: its only reader was a duress PIN that has
        // already been replaced.)
        val db = runCatching { MessageDb(context, STORE_ID, dataKey) }.getOrElse {
            destroy(context)
            runCatching { MessageDb(context, STORE_ID, dataKey) }.getOrElse { e ->
                android.util.Log.e("RCQpin", "decoy store would not open: ${e.message}")
                return false
            }
        }
        return try {
            db.wipeAll()
            // Synthetic peer numbers, allocated once per thread and never
            // derived from the real one (no arithmetic a forensic tool could
            // invert). Uniqueness matters: two threads collapsing onto one uin
            // would merge two people's conversations in the duress view.
            val taken = HashSet<Int>().apply { add(decoyUin) }
            var written = 0
            threads.forEach { thread ->
                var fake = randomUin()
                while (!taken.add(fake)) fake = randomUin()
                db.putDecoyContact(fake, thread.displayName)
                written += copyThread(db, thread, fake, decoyUin)
            }
            // ⚠⚠ COUNT THE ROWS, do not assume them. The roster is written per
            // thread and the messages per row, so a seed that writes contacts
            // and no messages is not a hypothetical failure mode — it is the
            // exact shape iOS shipped: a decoy with names in the chat list and
            // every conversation empty, which is a louder tell than no decoy at
            // all. Nothing here throws on a rejected insert (CONFLICT_IGNORE
            // returns -1), so a silent zero is the only way it would surface.
            // Refusing sends the caller down the "do not commit a decoy PIN"
            // path instead of sealing one over an empty view.
            if (written == 0) {
                android.util.Log.e("RCQpin", "decoy seeding wrote 0 messages from ${threads.size} thread(s)")
                return false
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("RCQpin", "decoy seeding failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            db.close()
        }
    }

    /** Copy one conversation into [db], rewriting every identifier. Returns the
     *  number of rows that actually landed, so [seed] can refuse a decoy whose
     *  contacts have no messages under them. */
    private fun copyThread(db: MessageDb, thread: SeedThread, fakePeer: Int, decoyUin: Int): Int {
        val rows = thread.messages.filter { it.groupId == null }.sortedBy { it.sentAt }
        // First pass: allocate the new envelope ids, so replies and albums
        // inside the copied thread can point at the copies rather than at ids
        // that only exist in the real store.
        val idMap = HashMap<String, String>(rows.size)
        rows.forEach { idMap[it.id] = UUID.randomUUID().toString() }
        val albumMap = HashMap<String, String>()
        var written = 0
        rows.forEach { msg ->
            val copy = msg.copy(
                id = idMap.getValue(msg.id),
                peerUin = fakePeer,
                senderUin = if (msg.fromMe) decoyUin else fakePeer,
                // A reaction map is {realUin: asset} in plain JSON. Remap what
                // we can name, drop the rest — a reaction from someone outside
                // this thread would otherwise carry their real number in.
                // Reactions the account owner left are dropped along with
                // everyone else's: losing an emoji is not a tell, a real UIN is.
                reactions = msg.reactions.mapNotNull { (uin, asset) ->
                    if (uin == thread.realUin) fakePeer to asset else null
                }.toMap(),
                // Server-side blob handles: they belong to the real account and
                // only its token can fetch them. A locally-stored poster frame
                // (thumbB64) stays, so a photo/video still renders.
                mediaId = null,
                mediaKey = null,
                replyToId = msg.replyToId?.let { idMap[it] },
                albumId = msg.albumId?.let { a -> albumMap.getOrPut(a) { UUID.randomUUID().toString() } },
                // ⚠ A copied disappearing message keeps its deadline, and the
                // reaper (Session.sweepExpiredMessages, a timer that outlives
                // the lock) runs against whichever db is bound — the DECOY one
                // in a duress session. Seeded rows would quietly evaporate and
                // leave a hole exactly where a hole cannot be explained.
                expiresAt = null,
            )
            if (db.insert(copy, honourTombstones = false)) written++
        }
        return written
    }

    /** The decoy roster, as the chat list wants it. Reads the store that is
     *  already open in the decoy session, so no second connection is made. */
    fun contacts(db: MessageDb): List<Contact> =
        db.decoyContacts().map { (uin, nick) ->
            // identityKey is empty on purpose: a decoy session never sends,
            // and fabricating a key would invite an encrypt path to use it.
            Contact(uin = uin, nickname = nick, identityKey = "", signingKey = null, status = "offline")
        }

    /** Delete the decoy store outright (decoy PIN removed, or a duress reset). */
    fun destroy(context: Context) {
        runCatching { MessageDb.wipeAccount(context, STORE_ID) }
    }

    /** True if a decoy store file exists at all. */
    fun exists(context: Context): Boolean = MessageDb.exists(context, STORE_ID)

    /**
     * Copy the decoy store into [targetAccountId]'s own database under
     * [targetKey], row for row. Used by the duress "remove PIN" path, which
     * has to leave the coercer with a working, PIN-less app.
     *
     * ⚠ A COPY, never a rekey: the source file is left exactly as it is and
     * deleted afterwards by the caller. Nothing here re-encrypts an existing
     * store in place.
     */
    fun exportTo(context: Context, dataKey: ByteArray, targetAccountId: String, targetKey: ByteArray): List<Contact> {
        val src = runCatching { MessageDb(context, STORE_ID, dataKey) }.getOrNull() ?: return emptyList()
        return try {
            val rows = src.all()
            val roster = contacts(src)
            val dst = MessageDb(context, targetAccountId, targetKey)
            try {
                rows.forEach { dst.insert(it, honourTombstones = false) }
            } finally {
                dst.close()
            }
            roster
        } catch (e: Exception) {
            android.util.Log.e("RCQpin", "decoy export failed: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        } finally {
            src.close()
        }
    }
}
