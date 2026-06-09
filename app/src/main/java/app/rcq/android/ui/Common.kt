package app.rcq.android.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Male
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.Session
import app.rcq.android.model.RcqGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun formatTime(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

/** Coarse "last seen" buckets — minutes / hours / days, else a date.
 *  Mirrors iOS ContactRow.relativeLastSeen. Localized via [context]. */
internal fun relativeLastSeen(ts: Long, context: android.content.Context): String {
    val secs = ((System.currentTimeMillis() - ts) / 1000).toInt()
    if (secs < 60) return context.getString(app.rcq.android.R.string.last_seen_just_now)
    val mins = secs / 60
    if (mins < 60) return context.getString(app.rcq.android.R.string.last_seen_min, mins)
    val hours = mins / 60
    if (hours < 24) return context.getString(app.rcq.android.R.string.last_seen_hour, hours)
    val days = hours / 24
    if (days < 7) return context.getString(app.rcq.android.R.string.last_seen_day, days)
    return SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts))
}

/** Chevron that points right when collapsed, down when expanded. */
@Composable
internal fun CollapseChevron(collapsed: Boolean) {
    val rotation by animateFloatAsState(if (collapsed) 0f else 90f, label = "chevron")
    Icon(
        Icons.Filled.ChevronRight,
        contentDescription = null,
        tint = RcqTheme.colors.textSecondary,
        modifier = Modifier.size(16.dp).rotate(rotation),
    )
}

/** ICQ-style collapsible section header: chevron · TITLE · (count) · trailing. */
@Composable
internal fun SectionHeader(
    title: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val c = RcqTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(0.dp))
            .background(c.bgSecondary.copy(alpha = 0.7f))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CollapseChevron(collapsed)
        Spacer(Modifier.size(6.dp))
        Text(title.uppercase(), color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(4.dp))
        Text("($count)", color = c.textSecondary, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/**
 * Round group avatar. Loads + decrypts the custom avatar blob via the
 * session media cache; falls back to the generic groups glyph on an
 * accent disc (mirrors iOS GroupAvatarView). Reused by the home row,
 * preview overlay, chat header, and group-info header.
 */
@Composable
internal fun GroupAvatar(group: RcqGroup?, session: Session, size: Dp, glyphSize: Dp = size * 0.55f) {
    GroupAvatarMedia(group?.avatarMediaId, group?.avatarMediaKey, session, size, glyphSize)
}

/** [GroupAvatar] by raw media id/key — for places that only have a group
 *  PREVIEW (e.g. the Add-window search results) rather than a full roster. */
@Composable
internal fun GroupAvatarMedia(id: String?, key: String?, session: Session, size: Dp, glyphSize: Dp = size * 0.55f) {
    val c = RcqTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val bytes by produceState<ByteArray?>(initialValue = null, id, key) {
        value = if (!id.isNullOrEmpty() && !key.isNullOrEmpty()) {
            // Native-crash breadcrumb (#1): a launch crash "when the beta chat
            // loads" is suspected around group-avatar decode — mark the stage.
            app.rcq.android.CrashReporter.crumb(ctx, "group_avatar")
            session.fetchImage(id, key)
        } else null
    }
    Box(Modifier.size(size).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
        val b = bytes
        when {
            // Animated GIF avatar (kept raw on upload) — render it animated.
            b != null && b.isGif() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P ->
                AnimatedGif(b, Modifier.fillMaxSize())
            else -> {
                val image = remember(b) { b?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
                if (image != null) {
                    Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Filled.Groups, null, tint = Color.White, modifier = Modifier.size(glyphSize))
                }
            }
        }
    }
}

/** Red unread-count capsule, anchored top-end over an avatar (iOS-style).
 *  Renders nothing when [count] is 0. */
@Composable
internal fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val c = RcqTheme.colors
    // 15dp min SQUARE keeps a single digit a true circle (dropping it made
    // a lone digit a tall oval — the box hugged the narrow-but-tall glyph);
    // 9sp text keeps it smaller than the old 16dp/10sp badge. Extra width
    // only kicks in for 2+ digits, growing it into a pill.
    Box(
        modifier
            .defaultMinSize(minWidth = 15.dp, minHeight = 15.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(c.statusBusy)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 9.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
        )
    }
}

@Composable
internal fun GenderIcon(gender: String?) {
    when (gender?.lowercase()) {
        "m", "male" -> Icon(Icons.Filled.Male, null, tint = Color(0xFF4A90D9), modifier = Modifier.size(12.dp))
        "f", "female" -> Icon(Icons.Filled.Female, null, tint = Color(0xFFD96BA6), modifier = Modifier.size(12.dp))
        else -> Unit
    }
}

@Composable
internal fun CapsuleButton(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = RcqTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (enabled) c.accent else c.bgSecondary)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 40.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}
