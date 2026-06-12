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
    fun share(context: Context, bytes: ByteArray, fileName: String, mime: String) {
        runCatching {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val f = File(dir, fileName.replace('/', '_'))
            f.writeBytes(bytes)
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
    fun saveToGallery(context: Context, bytes: ByteArray, fileName: String, mime: String): Boolean = runCatching {
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
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            true
        } else {
            val pubDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            saveLegacy(context, bytes, fileName, mime, pubDir)
        }
    }.getOrDefault(false)

    /** Save a document / voice note into Downloads/RCQ. Returns true on success. */
    fun saveToDownloads(context: Context, bytes: ByteArray, fileName: String, mime: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/RCQ")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            true
        } else {
            saveLegacy(context, bytes, fileName, mime, Environment.DIRECTORY_DOWNLOADS)
        }
    }.getOrDefault(false)

    private fun saveLegacy(context: Context, bytes: ByteArray, fileName: String, mime: String, publicDir: String): Boolean {
        val dir = File(Environment.getExternalStoragePublicDirectory(publicDir), "RCQ").apply { mkdirs() }
        val f = File(dir, fileName)
        f.writeBytes(bytes)
        MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf(mime), null)
        return true
    }
}
