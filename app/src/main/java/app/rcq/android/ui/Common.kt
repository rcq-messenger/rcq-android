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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.collectAsState
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
            .padding(horizontal = 10.dp, vertical = 3.dp),
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
internal fun GroupAvatar(group: RcqGroup?, session: Session, size: Dp, glyphSize: Dp = size * 0.55f, animated: Boolean = false) {
    GroupAvatarMedia(group?.avatarMediaId, group?.avatarMediaKey, session, size, glyphSize, host = group?.host, animated = animated)
}

/** [GroupAvatar] by raw media id/key — for places that only have a group
 *  PREVIEW (e.g. the Add-window search results) rather than a full roster.
 *  [animated]=true plays a GIF avatar (only used where ONE avatar is on screen,
 *  e.g. the chat header — list rows stay static first-frame to bound memory). */
@Composable
internal fun GroupAvatarMedia(id: String?, key: String?, session: Session, size: Dp, glyphSize: Dp = size * 0.55f, host: String? = null, animated: Boolean = false) {
    val c = RcqTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Seeded from the memory cache rather than from null: see
    // Session.cachedImage. Starting at null redrew the status glyph on every
    // appearance and swapped the picture in a frame later.
    val bytes by produceState<ByteArray?>(initialValue = session.cachedImage(id), id, key) {
        value = if (!id.isNullOrEmpty() && !key.isNullOrEmpty()) {
            // Native-crash breadcrumb (#1): a launch crash "when the beta chat
            // loads" is suspected around group-avatar decode — mark the stage.
            app.rcq.android.CrashReporter.crumb(ctx, "group_avatar")
            // §5c: a foreign group's avatar blob lives on ITS island.
            session.fetchImage(id, key, host)
        } else null
    }
    // JPEG/PNG decode via the fast, well-hardened native path; a GIF avatar (the
    // beta group's is a GIF) decodes its first frame via the PURE-JAVA decoder
    // (SafeGif.kt). The platform Skia GIF decoder SIGSEGV/SIGABRT'd here even on
    // a static first-frame BitmapFactory decode on realme/ColorOS — a native
    // crash we can't catch (the v0.31 diagnostic pinned it to "group_avatar",
    // v0.32/0.33 STILL crashed). Now GIF avatars actually render instead of
    // showing the glyph, and no path touches the crashing native decoder. Any
    // other/odd format still falls back to the group glyph. Both decodes are
    // off the main thread.
    val nativeImage = rememberSampledBitmap(bytes?.takeIf { it.isJpegOrPng() }, maxPx = 384)
    val gifImage = rememberGifFirstFrame(bytes)
    val image = nativeImage ?: gifImage
    // The caller asks for animation; the person's setting decides. Gated here
    // rather than at the seven call sites so a new one cannot forget it.
    val mayAnimate by app.rcq.android.data.LocalStores.animateAvatars.collectAsState()
    val animatableGif = bytes?.takeIf { animated && mayAnimate && it.isGif() }
    Box(Modifier.size(size).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
        when {
            // Animated GIF avatar (chat header only) — pure-Java decoder, safe
            // on every ROM; one instance so no list-wide churn.
            animatableGif != null -> SafeAnimatedGif(animatableGif, Modifier.fillMaxSize())
            image != null -> Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> Icon(Icons.Filled.Groups, null, tint = Color.White, modifier = Modifier.size(glyphSize))
        }
    }
}

/** A person's profile picture, or their status glyph when there is none.
 *
 *  Same decode path as [GroupAvatarMedia] (native for JPEG/PNG, the pure-Java
 *  decoder for GIF, because the platform GIF decoder crashes natively on some
 *  ROMs) — only the fallback differs: a person without a picture keeps the
 *  status flower everyone already knows, so nothing regresses for the people
 *  who never set one.
 *
 *  ⚠ Deliberately NOT used where strangers meet: Random, Nearby, the hood, and
 *  an unaccepted contact request all keep the glyph. A picture is for people
 *  you already have a relationship with, and an incoming request is otherwise
 *  a way to push an image onto someone's screen.
 */
@Composable
internal fun PersonAvatar(
    id: String?,
    key: String?,
    status: app.rcq.android.model.UserStatus,
    session: Session,
    size: Dp,
    host: String? = null,
    animated: Boolean = false,
    crossIsland: Boolean = false,
    onStatusClick: (() -> Unit)? = null,
) {
    val c = RcqTheme.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Seeded from the memory cache rather than from null: see
    // Session.cachedImage. Starting at null redrew the status glyph on every
    // appearance and swapped the picture in a frame later.
    val bytes by produceState<ByteArray?>(initialValue = session.cachedImage(id), id, key) {
        value = if (!id.isNullOrEmpty() && !key.isNullOrEmpty()) {
            app.rcq.android.CrashReporter.crumb(ctx, "person_avatar")
            session.fetchImage(id, key, host)
        } else null
    }
    val nativeImage = rememberSampledBitmap(bytes?.takeIf { it.isJpegOrPng() }, maxPx = 384)
    val gifImage = rememberGifFirstFrame(bytes)
    val image = nativeImage ?: gifImage
    // The caller asks for animation; the person's setting decides. Gated here
    // rather than at the seven call sites so a new one cannot forget it.
    val mayAnimate by app.rcq.android.data.LocalStores.animateAvatars.collectAsState()
    val animatableGif = bytes?.takeIf { animated && mayAnimate && it.isGif() }
    // No picture: nothing changes at all for the people who never set one.
    if (image == null && animatableGif == null) {
        StatusIcon(
            status,
            size = size,
            crossIsland = crossIsland,
            modifier = if (onStatusClick != null) Modifier.clip(CircleShape).clickable(onClick = onStatusClick) else Modifier,
        )
        return
    }
    // A picture does NOT replace the status: presence is still the thing this
    // app is built around, so the flower moves to a badge on the lower-left
    // corner and keeps working, including the tap that opens the status menu.
    // Big enough to read at 26dp in a list, small enough not to swallow a
    // 80dp contact card. Capped at both ends rather than a flat fraction.
    val badge = (size * 0.36f).coerceIn(12.dp, 26.dp)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().clip(CircleShape)) {
            if (animatableGif != null) SafeAnimatedGif(animatableGif, Modifier.fillMaxSize())
            else Image(bitmap = image!!, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // The badge sits ON the picture's lower-left edge and sticks out past
        // it, the way the site draws it in the Hall of Fame. Keeping it fully
        // inside the square put it in the corner the round image never reaches,
        // so it read as clipped and half-swallowed by the avatar.
        Box(
            Modifier.align(Alignment.BottomStart)
                .offset(x = -(badge / 4), y = badge / 4)
                .size(badge)
                .clip(CircleShape)
                .background(c.bgPrimary)
                .then(if (onStatusClick != null) Modifier.clickable(onClick = onStatusClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            StatusIcon(status, size = badge * 0.82f, crossIsland = crossIsland)
        }
    }
}

/** The sender's picture beside their nick on a group message — and NOTHING at
 *  all when they have not set one, so that row stays exactly the plain nick it
 *  has always been.
 *
 *  Deliberately not [PersonAvatar]: that one falls back to the status flower
 *  and keeps presence as a badge, which is right in a list of people and wrong
 *  here. Presence on a bubble would be the sender's status NOW, sitting next to
 *  something they said hours ago, and a coloured dot on every message is noise
 *  where the list needs it to be signal.
 */
@Composable
internal fun SenderAvatar(id: String?, key: String?, session: Session, size: Dp) {
    if (id.isNullOrEmpty() || key.isNullOrEmpty()) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Seeded from the memory cache, like PersonAvatar: bubbles recycle
    // constantly while scrolling and starting at null flickers the row.
    val bytes by produceState<ByteArray?>(initialValue = session.cachedImage(id), id, key) {
        app.rcq.android.CrashReporter.crumb(ctx, "sender_avatar")
        value = session.fetchImage(id, key, null)
    }
    // A still frame even for a GIF: an animation per message would be its own
    // kind of noise, and the picture here is 16dp of identification.
    val image = rememberSampledBitmap(bytes?.takeIf { it.isJpegOrPng() }, maxPx = 96)
        ?: rememberGifFirstFrame(bytes)
    if (image == null) return
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(CircleShape),
    )
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
