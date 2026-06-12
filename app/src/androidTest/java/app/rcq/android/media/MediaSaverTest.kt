package app.rcq.android.media

import android.content.ContentUris
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that MediaSaver actually writes to the gallery + Downloads
 * via scoped MediaStore (report #6). Runs on API 29+ emulators with no runtime
 * permission. A 1x1 JPEG is enough to validate the ContentResolver insert ->
 * openOutputStream -> IS_PENDING clear path.
 */
@RunWith(AndroidJUnit4::class)
class MediaSaverTest {

    // Minimal valid 1x1 JPEG.
    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46,
        0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        0xFF.toByte(), 0xD9.toByte(),
    )

    @Test
    fun savesImageToGallery() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "RCQ_test_${System.nanoTime()}.jpg"
        val ok = MediaSaver.saveToGallery(ctx, jpeg, name, "image/jpeg")
        assertTrue("saveToGallery should report success", ok)
        // Confirm the row is queryable in the images collection.
        val cursor = ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(name),
            null,
        )
        val found = (cursor?.count ?: 0) > 0
        cursor?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                // Clean up the test row.
                ctx.contentResolver.delete(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), null, null,
                )
            }
        }
        assertTrue("saved image must be queryable in MediaStore", found)
    }

    @Test
    fun savesFileToDownloads() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "RCQ_test_${System.nanoTime()}.bin"
        val ok = MediaSaver.saveToDownloads(ctx, byteArrayOf(1, 2, 3, 4, 5), name, "application/octet-stream")
        assertTrue("saveToDownloads should report success", ok)
        val cursor = ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(name),
            null,
        )
        val found = (cursor?.count ?: 0) > 0
        cursor?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                ctx.contentResolver.delete(
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null,
                )
            }
        }
        assertTrue("saved file must be queryable in MediaStore Downloads", found)
    }
}
