package app.rcq.android.backup

import app.rcq.android.Session
import app.rcq.android.data.LocalStores
import app.rcq.android.model.ChatMessage
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
        messages.forEach { sb.append(gson.toJson(it)).append('\n') }
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
                        val msg = runCatching { gson.fromJson(line, ChatMessage::class.java) }.getOrNull()
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
