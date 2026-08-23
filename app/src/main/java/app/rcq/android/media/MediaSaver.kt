package app.rcq.android.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Share + save-to-device for decrypted media bytes. iOS has had this for a
 * while; this brings Android to parity (report #6 — couldn't share/download a
 * photo/video). Share works on every API via the existing FileProvider; save
 * uses scoped MediaStore on API 29+ (no permission) and the legacy public dirs
 * on API 26-28 (needs WRITE_EXTERNAL_STORAGE, requested by the caller).
 */
object MediaSaver {

    /** Hand decrypted bytes to the system share sheet (ACTION_SEND). No storage
     *  permission needed — the file lives in our cache, exposed via FileProvider. */
    fun share(context: Context, bytes: ByteArray, fileName: String, mime: String) =
        share(context, { out -> out.write(bytes); true }, fileName, mime)

    /** [share] for media too big to hold: [write] streams the plaintext into
     *  the file we are about to hand out and answers false if it could not
     *  produce all of it (a chunk that failed authentication, a read error).
     *  A refusal deletes the half-written file instead of sharing it. A clip
     *  that stops in the middle looks to the recipient like a clip we sent,
     *  and it is not one. */
    fun share(context: Context, write: (java.io.OutputStream) -> Boolean, fileName: String, mime: String) {
        runCatching {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val f = File(dir, fileName.replace('/', '_'))
            val ok = java.io.FileOutputStream(f).use { out -> write(out) }
            if (!ok) { f.delete(); return }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, fileName).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    /** True only on API < 29, where saving to the public gallery/downloads needs
     *  the WRITE_EXTERNAL_STORAGE runtime permission. API 29+ uses scoped storage. */
    val needsLegacyWritePermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Save an image or video into the device gallery (Pictures/RCQ or
     *  Movies/RCQ). Returns true on success. */
    fun saveToGallery(context: Context, bytes: ByteArray, fileName: String, mime: String): Boolean =
        saveToGallery(context, { out -> out.write(bytes); true }, fileName, mime)

    /** [saveToGallery] for media too big to hold. [write] streams the plaintext
     *  into the pending MediaStore row and answers false if it could not
     *  produce all of it; a false deletes the row rather than publishing a
     *  truncated file into the person's gallery.
     *
     *  ⚠ This is where whole-file integrity is paid for on a chunk-sealed
     *  container: the writer walks every chunk to the end, so by the time the
     *  row is published every byte has been verified. Playback is the only
     *  place that starts using a chunk before the last one is checked. */
    fun saveToGallery(context: Context, write: (java.io.OutputStream) -> Boolean, fileName: String, mime: String): Boolean = runCatching {
        val isVideo = mime.startsWith("video")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                             else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val dirName = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dirName/RCQ")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(collection, values) ?: return false
            val ok = context.contentResolver.openOutputStream(uri)?.use { write(it) } ?: false
            if (!ok) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                return false
            }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            true
        } else {
            val pubDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            saveLegacy(context, write, fileName, mime, pubDir)
        }
    }.getOrDefault(false)

    /** Save a document / voice note into Downloads/RCQ. Returns true on success.
     *
     *  ⚠ Two attempts, and the second one is not paranoia. MediaProvider vets
     *  the pair (display name, mime) and REFUSES some of them outright: an
     *  `.apk` offered as `application/vnd.android.package-archive` throws
     *  IllegalArgumentException on insert, so "Save" on a received APK did
     *  nothing at all and said nothing either (#590, with a video of the tap).
     *  The bytes are the user's, they asked for them, and the mime is a label
     *  the sender chose. So on a refusal we write the same bytes under the same
     *  name as a plain stream, which MediaProvider accepts.
     */
    fun saveToDownloads(context: Context, bytes: ByteArray, fileName: String, mime: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return runCatching {
                saveLegacy(context, bytes, fileName, mime, Environment.DIRECTORY_DOWNLOADS)
            }.getOrDefault(false)
        }
        if (insertIntoDownloads(context, bytes, fileName, mime)) return true
        if (mime != OCTET && insertIntoDownloads(context, bytes, fileName, OCTET)) return true
        return false
    }

    private const val OCTET = "application/octet-stream"

    private fun insertIntoDownloads(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        mime: String,
    ): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/RCQ")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: return@runCatching false
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)

    private fun saveLegacy(context: Context, bytes: ByteArray, fileName: String, mime: String, publicDir: String): Boolean =
        saveLegacy(context, { out -> out.write(bytes); true }, fileName, mime, publicDir)

    private fun saveLegacy(context: Context, write: (java.io.OutputStream) -> Boolean, fileName: String, mime: String, publicDir: String): Boolean {
        val dir = File(Environment.getExternalStoragePublicDirectory(publicDir), "RCQ").apply { mkdirs() }
        val f = File(dir, fileName)
        val ok = java.io.FileOutputStream(f).use { out -> write(out) }
        if (!ok) { f.delete(); return false }
        MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf(mime), null)
        return true
    }
}
