package app.rcq.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import app.rcq.android.Session
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

/** Generate a black-on-white QR bitmap for [content].
 *
 *  `internal` since 0.166: the UIN checkout draws a payment code with it too,
 *  behind a "show QR" tap rather than always on screen (the person is usually
 *  paying from the same phone, and a code they cannot scan is a picture in the
 *  way of the address they need to copy). One generator, one set of hints. */
internal fun qrBitmap(content: String, size: Int = 512): Bitmap? = runCatching {
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
fun QrDialog(
    uin: Int,
    qrPayload: String,
    shareLink: String,
    session: Session,
    onDismiss: () -> Unit,
) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val bmp = remember(qrPayload) { qrBitmap(qrPayload) }
    val ownAvatar by session.ownAvatar.collectAsState()
    val ownStatus by session.status.collectAsState()
    RcqSheet(onDismiss = onDismiss, title = stringResource(R.string.qr_title)) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (bmp != null) {
                // Your face in the middle of your own code (iOS parity).
                // The code is held up to a stranger, so the middle of it is
                // worth the person holding it. PersonAvatar draws the plain
                // status flower when no picture is set, which is what the
                // phones showed here before — nothing changes for anyone
                // who has not set one.
                //
                // ⚠ 40dp over 220dp is ~3.3% of the code's AREA. Error
                // correction is level M, which tolerates about 15%, so the
                // badge must not grow much past this.
                Box(contentAlignment = Alignment.Center) {
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
                    // A white ring so the picture reads as ON the code
                    // rather than punched into it, and so the scanner sees
                    // a clean edge.
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        PersonAvatar(
                            id = ownAvatar?.first,
                            key = ownAvatar?.second,
                            status = ownStatus,
                            session = session,
                            size = 40.dp,
                        )
                    }
                }
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
        SheetGap(16)
        Text(
            stringResource(R.string.common_done),
            color = c.accent, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onDismiss).padding(vertical = 14.dp),
        )
    }
}
