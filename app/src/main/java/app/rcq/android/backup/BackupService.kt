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

    /** Result of a restore, for a plain sentence at the end of it. */
    data class RestoreResult(val added: Int, val skipped: Int, val media: Int)

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
     */
    data class Record(
        val id: String,
        val peer: Int?,          // 1:1 thread, null for a group message
        val group: Int?,         // group thread, null for 1:1
        val from_me: Boolean,
        val sender: Int?,        // group message author
        val sent_at: Long,
        val kind: String,
        val body: String,
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
        val reactions: Map<String, String> = emptyMap(),
        val expires_at: Long? = null,
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
    )

    private fun Record.toMessage() = ChatMessage(
        id = id,
        peerUin = peer ?: 0,
        fromMe = from_me,
        body = body,
        sentAt = sent_at,
        // Anything restored is history: it either arrived or it was sent long
        // ago, so it is never left looking like it is still on its way.
        state = DeliveryState.DELIVERED,
        kind = kind,
        mediaId = media_id,
        mediaKey = media_key,
        replyToSnippet = reply_to_snippet,
        replyToAuthor = reply_to_author,
        replyToId = reply_to_id,
        groupId = group,
        senderUin = sender,
        reactions = reactions.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap(),
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
    )

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

        val local = JsonObject().apply {
            add("aliases", gson.toJsonTree(LocalStores.aliases.value))
            add("favorites", gson.toJsonTree(LocalStores.favorites.value))
            add("muted", gson.toJsonTree(LocalStores.muted.value))
            add("archived", gson.toJsonTree(LocalStores.archived.value))
        }
        writer.entry("local.json", local.toString().toByteArray())

        if (includeMedia) {
            // Attachments are fetched and decrypted one at a time, so a
            // multi-gigabyte account never has to fit in memory. A blob that
            // has already aged off the island is skipped rather than failing
            // the whole export: some history is better than none.
            val withMedia = messages.filter { !it.mediaId.isNullOrEmpty() && !it.mediaKey.isNullOrEmpty() }
            withMedia.forEachIndexed { i, m ->
                onProgress(Progress("media", i + 1, withMedia.size))
                val bytes = runCatching { session.fetchImage(m.mediaId!!, m.mediaKey!!) }.getOrNull()
                if (bytes != null) writer.entry("media/${m.mediaId}", bytes)
            }
        }
        writer.finish()
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
        var media = 0
        reader.forEachEntry { name, bytes ->
            when {
                name == "messages.ndjson" -> {
                    val lines = String(bytes).split('\n').filter { it.isNotBlank() }
                    lines.forEachIndexed { i, line ->
                        if (i % 200 == 0) onProgress(Progress("messages", i, lines.size))
                        val msg = runCatching { gson.fromJson(line, Record::class.java).toMessage() }.getOrNull()
                        if (msg != null) {
                            if (session.insertRestoredMessage(msg)) added++ else skipped++
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
                }
                name.startsWith("media/") -> {
                    session.cacheRestoredMedia(name.removePrefix("media/"), bytes)
                    media++
                }
            }
        }
        RestoreResult(added = added, skipped = skipped, media = media)
    }
}
