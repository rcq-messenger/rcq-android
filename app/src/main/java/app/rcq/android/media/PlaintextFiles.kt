package app.rcq.android.media

import android.content.Context
import java.io.File

/**
 * The decrypted copies of messages this app leaves in its cache directory, so
 * a wipe can take them with the database they came out of.
 *
 * ⚠ The history is SQLCipher and the media cache holds sealed bytes only, but
 * three paths write PLAINTEXT beside them and nothing ever cleared it: a voice
 * note is decrypted to `voice-<id>.m4a` to be played (the one being recorded is
 * `voice-out.m4a`), an audio file or an opened attachment goes to `files/`, and
 * whatever is handed to another app goes to `share/`. None of it is keyed by
 * account, so a burn, a removed account or a wipe PIN left the voices and the
 * files of the account it had just erased readable on disk. They are caches:
 * playing or opening the message again writes them back.
 */
object PlaintextFiles {

    /** Whether [name], in the cache subdirectory [dir] ("" for the cache root),
     *  is one of them. The update packages share `files/` and are not anybody's
     *  message: an update that is about to be installed stays. */
    internal fun isPlaintext(dir: String, name: String): Boolean = when (dir) {
        "" -> name.startsWith("voice-") && name.endsWith(".m4a")
        "files" -> !(name.endsWith(".apk") && (name.startsWith("rcq-update-") || name.startsWith("RCQ-")))
        "share" -> true
        else -> false
    }

    fun wipe(context: Context) {
        val root = context.applicationContext.cacheDir ?: return
        for (dir in listOf("", "files", "share")) {
            val d = if (dir.isEmpty()) root else File(root, dir)
            d.listFiles()?.forEach { f ->
                if (f.isFile && isPlaintext(dir, f.name)) runCatching { f.delete() }
            }
        }
    }
}
