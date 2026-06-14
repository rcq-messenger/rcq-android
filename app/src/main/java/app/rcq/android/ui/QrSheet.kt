package app.rcq.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Generate a black-on-white QR bitmap for [content]. */
private fun qrBitmap(content: String, size: Int = 512): Bitmap? = runCatching {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val off = y * w
        for (x in 0 until w) {
            pixels[off + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
}.getOrNull()

/** "My code" sheet — a QR of the federated contact link (spec §5: island +
 *  advisory key ride query params; flagship degrades to the legacy bare uin)
 *  plus the UIN. Matches the iOS QRSheet. */
@Composable
fun QrDialog(uin: Int, qrPayload: String, shareLink: String, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val bmp = remember(qrPayload) { qrBitmap(qrPayload) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done), color = c.accent) } },
        title = { Text(stringResource(R.string.qr_title), color = c.textPrimary) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.qr_title),
                        filterQuality = FilterQuality.None,
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(10.dp),
                    )
                }
                Text(
                    "$uin",
                    color = c.textPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                // Copy the UIN, or share a Telegram-style https link anyone can tap.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("UIN", "$uin"))
                        Toast.makeText(context, context.getString(R.string.common_uin_copied), Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Filled.ContentCopy, null, tint = c.accent, modifier = Modifier.size(16.dp))
                        Text(" " + stringResource(R.string.qr_copy_uin), color = c.accent, fontSize = 13.sp)
                    }
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.qr_share_text, "$uin", shareLink))
                        }
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.qr_share)))
                    }) {
                        Icon(Icons.Filled.Share, null, tint = c.accent, modifier = Modifier.size(16.dp))
                        Text(" " + stringResource(R.string.qr_share), color = c.accent, fontSize = 13.sp)
                    }
                }
                Text(
                    stringResource(R.string.qr_hint),
                    color = c.textSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        },
    )
}
