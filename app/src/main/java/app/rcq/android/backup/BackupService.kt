package app.rcq.android.backup

import app.rcq.android.Session
import app.rcq.android.data.LocalStores
import app.rcq.android.model.ChatMessage
import app.rcq.android.model.DeliveryState
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Export and restore a `.rcqbak` archive.
 *
 * The archive is a plain container (see [BackupFormat]) so the same file opens
 * on the desktop and on an iPhone. What goes in:
 *
 *  * `manifest.json`     — what this file is and how much is in it
 *  * `messages.ndjson`   — one message per line
 *  * `local.json`        — the settings that exist NOWHERE else: the names I
 *                          gave contacts, favourites, mutes, archived threads.
 *                          The contact graph itself is not here, the island
 *                          hands that back on sign-in.
 *  * `media/<id>`        — the decrypted bytes of an attachment, when the user
 *                          asked for attachments to be included
 *
 * Restore only ever ADDS: a message already present by id is skipped, and
 * nothing local is deleted or overwritten. An old archive can therefore never
 * eat newer history, which is the failure that makes people distrust backups.
 */
object BackupService {

    data class Progress(val stage: String, val done: Int, val total: Int)

    /** Result of a restore, for a plain sentence at the end of it.
     *
     *  [unreadable] is counted separately and on purpose: a line this build
     *  cannot turn into a message is neither added nor already here, and
     *  folding it into either number is how a restore reports success while
     *  quietly handing back a shorter history.
     *
     *  [deletedHere] is the same argument applied to deletions. A message the
     *  person deleted on this device is not restored, by design — but counting
     *  it as "already here" tells them it is in the chat, and it is not. */
    data class RestoreResult(
        val added: Int,
        val skipped: Int,
        val deletedHere: Int,
        val media: Int,
        val unreadable: Int,
    )

    /** What an export actually managed to put in the file.
     *
     *  [mediaMissed] exists because attachments are fetched from the island at
     *  export time: a blob that has aged off, or a phone with no connection,
     *  leaves the file short while the manifest still says it carries media.
     *  The person is told the number instead of finding out years later. */
    data class ExportResult(val messages: Int, val media: Int, val mediaMissed: Int)

    class Refused(message: String) : Exception(message)

    private val gson = Gson()

    /**
     * The neutral message record that actually travels in the archive.
     *
     * Deliberately NOT this client's own model: Android keeps a `ChatMessage`,
     * the browser keeps its own rows and iOS keeps a third shape, so writing
     * any one of them into the file would make "took it on Android, restored it
     * on an iPhone" a lie. Every client maps to and from THIS, and adding a
     * field here is additive — an older client ignores what it does not know.
     *
     * ⚠ Every field is nullable with a default, and that is load-bearing rather
     * than tidy. A writer that has nothing to put in a field leaves the key out
     * altogether — the browser does exactly that with `reactions` — and Gson
     * builds this class through Unsafe whenever a parameter lacks a default,
     * which skips Kotlin's defaults and leaves the field genuinely null behind
     * a non-null type. A web-written archive therefore threw on the first
     * message and lost all of them, silently, because the throw happened inside
     * a runCatching. Absent must read as empty here, not as a crash.
     */
    data class Record(
        val id: String? = null,
        val peer: Int? = null,   // 1:1 thread, null for a group message
        val group: Int? = null,  // group thread, null for 1:1
        val from_me: Boolean = false,
        val sender: Int? = null, // group message author
        val sent_at: Long = 0L,
        val kind: String? = null,
        val body: String? = null,
        val media_id: String? = null,
        val media_key: String? = null,
        val file_name: String? = null,
        val file_mime: String? = null,
        val file_size: Long? = null,
        val duration_sec: Int? = null,
        val thumb_b64: String? = null,
        val lat: Double? = null,
        val lng: Double? = null,
        val spoiler: Boolean = false,
        val album_id: String? = null,
        val edited: Boolean = false,
        val reply_to_id: String? = null,
        val reply_to_author: String? = null,
        val reply_to_snippet: String? = null,
        val reactions: Map<String, String>? = null,
        val expires_at: Long? = null,
        /** kind == "call": which call this row records, so a restored history
         *  can still tell an already-known call from a new one (#678/#686).
         *  Additive and optional; an archive written by an older build or by
         *  another client simply has no such field and reads as null. */
        val call_id: String? = null,
    )

    private fun ChatMessage.toRecord() = Record(
        id = id,
        peer = if (groupId == null) peerUin else null,
        group = groupId,
        from_me = fromMe,
        sender = senderUin,
        sent_at = sentAt,
        kind = kind,
        body = body,
        media_id = mediaId,
        media_key = mediaKey,
        file_name = fileName,
        file_mime = fileMime,
        file_size = fileSize,
        duration_sec = durationSec,
        thumb_b64 = thumbB64,
        lat = lat,
        lng = lng,
        spoiler = spoiler,
        album_id = albumId,
        edited = edited,
        reply_to_id = replyToId,
        reply_to_author = replyToAuthor,
        reply_to_snippet = replyToSnippet,
        reactions = reactions.mapKeys { it.key.toString() },
        expires_at = expiresAt,
        call_id = callId,
    )

    /** Back into this client's shape, or null when the line cannot be a message
     *  here at all. Null rather than an exception: one unreadable record must
     *  cost that record and nothing more. */
    private fun Record.toMessageOrNull(): ChatMessage? {
        val msgId = id?.takeIf { it.isNotBlank() } ?: return null
        // Exactly one of the two is set; a record naming neither thread has
        // nowhere to land and would otherwise appear as a chat with #0.
        if (peer == null && group == null) return null
        return ChatMessage(
            id = msgId,
            peerUin = peer ?: 0,
            fromMe = from_me,
            body = body.orEmpty(),
            sentAt = sent_at,
            // Anything restored is history: it either arrived or it was sent
            // long ago, so it is never left looking like it is still on its way.
            state = DeliveryState.DELIVERED,
            kind = kind ?: "text",
            mediaId = media_id,
            mediaKey = media_key,
            replyToSnippet = reply_to_snippet,
            replyToAuthor = reply_to_author,
            replyToId = reply_to_id,
            groupId = group,
            senderUin = sender,
            reactions = reactions.orEmpty().mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap(),
            edited = edited,
            fileName = file_name,
            fileMime = file_mime,
            fileSize = file_size,
            durationSec = duration_sec,
            thumbB64 = thumb_b64,
            lat = lat,
            lng = lng,
            spoiler = spoiler,
            albumId = album_id,
            expiresAt = expires_at,
            callId = call_id,
        )
    }

    suspend fun export(
        session: Session,
        out: OutputStream,
        includeMedia: Boolean,
        onProgress: (Progress) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val phrase = session.recoveryPhrase()?.joinToString(" ")
            ?: throw Refused("no recovery phrase on this device")
        val uin = session.uin ?: throw Refused("not signed in")
        val messages = session.allMessagesForBackup()
        val writer = BackupFormat.Writer(out, phrase, uin, System.currentTimeMillis())

        val manifest = JsonObject().apply {
            addProperty("app", "rcq-android")
            addProperty("app_version", app.rcq.android.BuildConfig.VERSION_NAME)
            addProperty("uin", uin)
            addProperty("messages", messages.size)
            addProperty("includes_media", includeMedia)
        }
        writer.entry("manifest.json", manifest.toString().toByteArray())

        val sb = StringBuilder()
        messages.forEach { sb.append(gson.toJson(it.toRecord())).append('\n') }
        writer.entry("messages.ndjson", sb.toString().toByteArray())

        // Tombstones travel with the history. Without them a restore begins with
        // no record of what was deleted, and anything still queued on the island
        // returns on the next drain — the "delete does not really delete"
        // report. Ids and timestamps only, no content.
        val tombstones = session.deletedIdsForBackup()
        if (tombstones.isNotEmpty()) {
            val td = StringBuilder()
            tombstones.forEach { (id, at) ->
                td.append(JsonObject().apply {
                    addProperty("id", id)
                    addProperty("at", at)
                }.toString()).append('\n')
            }
            writer.entry("deleted.ndjson", td.toString().toByteArray())
        }

        val local = JsonObject().apply {
            add("aliases", gson.toJsonTree(LocalStores.aliases.value))
            add("favorites", gson.toJsonTree(LocalStores.favorites.value))
            add("muted", gson.toJsonTree(LocalStores.muted.value))
            add("mentions_only", gson.toJsonTree(LocalStores.mentionsOnly.value))
            add("archived", gson.toJsonTree(LocalStores.archived.value))
        }
        writer.entry("local.json", local.toString().toByteArray())

        var saved = 0
        var missed = 0
        if (includeMedia) {
            // Attachments are fetched and decrypted one at a time, so a
            // multi-gigabyte account never has to fit in memory. A blob that
            // has already aged off the island is skipped rather than failing
            // the whole export: some history is better than none, and the
            // count of what was missed is handed back rather than swallowed.
            // distinctBy the blob, not the message: one video forwarded into six
            // chats is six rows pointing at one media id, and without this it
            // was fetched, decrypted and written into the file six times.
            val withMedia = messages
                .filter { !it.mediaId.isNullOrEmpty() && !it.mediaKey.isNullOrEmpty() }
                .distinctBy { it.mediaId }
            withMedia.forEachIndexed { i, m ->
                onProgress(Progress("media", i + 1, withMedia.size))
                val bytes = runCatching { session.fetchImage(m.mediaId!!, m.mediaKey!!) }.getOrNull()
                if (bytes != null) {
                    writer.entry("media/${m.mediaId}", bytes)
                    saved++
                } else {
                    missed++
                }
            }
        }
        writer.finish()
        ExportResult(messages = messages.size, media = saved, mediaMissed = missed)
    }

    suspend fun restore(
        session: Session,
        input: InputStream,
        phrase: String,
        onProgress: (Progress) -> Unit = {},
    ): RestoreResult = withContext(Dispatchers.IO) {
        val reader = BackupFormat.Reader(input, phrase)
        reader.open()
        val me = session.uin ?: throw Refused("not signed in")
        // Refused on purpose: a history that belongs to another number would
        // appear in this account's chat list as if it were its own, and the
        // person reading it is not the person it was written to.
        if (reader.uin != me) {
            throw Refused("this archive belongs to #${reader.uin}, and you are signed in as #$me")
        }

        var added = 0
        var skipped = 0
        var deletedHere = 0
        var media = 0
        var unreadable = 0
        // media/<id> entries carry the decrypted bytes but not the key that
        // seals them on disk; the key rides in the records, which the writer
        // always puts in the file before the blobs.
        val mediaKeys = HashMap<String, String>()
        reader.forEachEntry { name, bytes ->
            when {
                name == "messages.ndjson" -> {
                    val lines = String(bytes).split('\n').filter { it.isNotBlank() }
                    lines.forEachIndexed { i, line ->
                        if (i % 200 == 0) onProgress(Progress("messages", i, lines.size))
                        val msg = runCatching { gson.fromJson(line, Record::class.java).toMessageOrNull() }
                            .getOrNull()
                            // An archive written before disappearing messages
                            // were excluded can still carry one. Its timer does
                            // not pause because it sat in a file, so anything
                            // already past its moment stays gone.
                            ?.takeIf { it.expiresAt == null || it.expiresAt!! > System.currentTimeMillis() }
                        when {
                            msg == null -> unreadable++
                            else -> when (session.insertRestoredMessage(msg)) {
                                Session.RestoreInsert.ADDED -> added++
                                Session.RestoreInsert.ALREADY_HERE -> skipped++
                                Session.RestoreInsert.DELETED_HERE -> deletedHere++
                            }
                        }
                        if (msg != null && !msg.mediaId.isNullOrEmpty() && !msg.mediaKey.isNullOrEmpty()) {
                            mediaKeys[msg.mediaId!!] = msg.mediaKey!!
                        }
                    }
                }
                name == "deleted.ndjson" -> {
                    // Re-arm every tombstone the archive carries. The writer
                    // emits this after messages.ndjson, so entries restored
                    // earlier in this same pass are re-deleted here rather than
                    // being kept — same end state, one extra step.
                    String(bytes).split('\n').filter { it.isNotBlank() }.forEach { line ->
                        runCatching {
                            val o = JsonParser.parseString(line).asJsonObject
                            val id = o.get("id").asString
                            val at = o.get("at")?.asLong ?: System.currentTimeMillis()
                            session.restoreDeletedId(id, at)
                            session.deleteRestoredMessage(id)
                        }
                    }
                }
                name == "local.json" -> {
                    val obj = JsonParser.parseString(String(bytes)).asJsonObject
                    obj.getAsJsonObject("aliases")?.entrySet()?.forEach { (k, v) ->
                        k.toIntOrNull()?.let { uin ->
                            // Never overwrite a name chosen on THIS device: the
                            // restore adds, it does not correct.
                            if (LocalStores.aliasFor(uin) == null) LocalStores.setAlias(uin, v.asString)
                        }
                    }
                    // These three went into every archive from the first version
                    // and were read back by nothing, so a restore quietly lost
                    // every pinned, muted and archived thread. Additive like the
                    // aliases: a thread already in the state stays as it is.
                    fun threads(key: String): List<String> =
                        obj.getAsJsonArray(key)?.mapNotNull { runCatching { it.asString }.getOrNull() } ?: emptyList()
                    threads("favorites").forEach { if (!LocalStores.isFavorite(it)) LocalStores.toggleFavorite(it) }
                    threads("archived").forEach { if (!LocalStores.isArchived(it)) LocalStores.toggleArchive(it) }
                    // Mute is one of three notify modes rather than a flag of its
                    // own, so it is set through the mode: the sets behind it have
                    // to stay mutually exclusive.
                    threads("muted").forEach {
                        if (LocalStores.notifyMode(it) == LocalStores.NotifyMode.ALL) {
                            LocalStores.setNotifyMode(it, LocalStores.NotifyMode.NONE)
                        }
                    }
                    threads("mentions_only").forEach {
                        if (LocalStores.notifyMode(it) == LocalStores.NotifyMode.ALL) {
                            LocalStores.setNotifyMode(it, LocalStores.NotifyMode.MENTIONS)
                        }
                    }
                }
                name.startsWith("media/") -> {
                    val mediaId = name.removePrefix("media/")
                    session.cacheRestoredMedia(mediaId, bytes, mediaKeys[mediaId])
                    media++
                }
            }
        }
        // The screens read Session's flows, not the database, and those are
        // seeded once at launch — so without this the whole restore is invisible
        // until the app is next started. Deliberately after every entry has been
        // applied, tombstones included, so what appears is the end state rather
        // than a version of the history that exists for one frame.
        session.reloadHistoryFromDb()
        RestoreResult(
            added = added,
            skipped = skipped,
            deletedHere = deletedHere,
            media = media,
            unreadable = unreadable,
        )
    }
}
