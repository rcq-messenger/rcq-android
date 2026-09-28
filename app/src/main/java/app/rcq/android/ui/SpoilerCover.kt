package app.rcq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp

/**
 * A spoiler as a tile shows it (#1052 review): the album grid in the chat, the
 * chat's All media wall and the group card's wall drew the picture plain, so a
 * photo its sender hid behind a spoiler was on screen before anybody tapped
 * anything. The single bubbles have always covered one; these are the same
 * cover at tile size. Opening a covered tile lands on a covered page in the
 * pager, and a tap there uncovers it.
 */
internal fun coveredIfSpoiler(image: ImageBitmap?, spoiler: Boolean): ImageBitmap? =
    if (!spoiler || image == null) image
    else runCatching { blurForSpoiler(image.asAndroidBitmap()).asImageBitmap() }.getOrNull()

/** The small mark a covered tile carries, so it reads as hidden rather than blurry. */
@Composable
internal fun SpoilerTileMark(modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(6.dp)) {
        Icon(Icons.Filled.VisibilityOff, null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}
