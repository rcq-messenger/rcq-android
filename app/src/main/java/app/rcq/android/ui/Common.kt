package app.rcq.android.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Key
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
import androidx.compose.ui.res.stringResource
import app.rcq.android.R
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.Session
import app.rcq.android.model.RcqGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The FAQ entry that explains what a relay is.
 *
 *  Every place that names "RCQ relays" links here. The founder's call: the user
 *  is supposed to MEET the word "relay" rather than have it hidden behind a
 *  euphemism, which only works if there is somewhere to go and find out what it
 *  means. */
internal const val RELAYS_FAQ_URL = "https://rcq.app/faq#relays"

/** "What is a relay?", the link that goes with [RELAYS_FAQ_URL]. Plain accent
 *  text so it reads as a link without turning a settings row into a button. */
@Composable
internal fun RelayLearnMore(modifier: Modifier = Modifier) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Text(
        stringResource(R.string.relay_what_is),
        color = RcqTheme.colors.accent,
        fontSize = 12.sp,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { runCatching { uriHandler.openUri(RELAYS_FAQ_URL) } }
            .padding(vertical = 4.dp),
    )
}

internal fun formatTime(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

/** "Last seen" in WORDS, not numbers (founder, 31.08).
 *
 *  ⚠ "was here 47 minutes ago" is an activity pattern: read it a few times a
 *  day and you know when someone wakes up, commutes and sleeps. Nobody needs
 *  that to decide whether to write - "recently" answers the same question.
 *  The island already floors what it serves to the hour (A7), so the minutes
 *  were never real anyway; printing them dressed a floored hour up as
 *  precision it does not have. Same buckets on every client.
 */
internal fun relativeLastSeen(ts: Long, context: android.content.Context): String {
    val now = System.currentTimeMillis()
    if (now - ts < 3_600_000L) return context.getString(app.rcq.android.R.string.last_seen_recently)
    // Calendar days, not 24-hour blocks: "yesterday" has to mean yesterday to
    // a person, not "between 24 and 48 hours ago".
    val midnight = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val day = 86_400_000L
    return when {
        ts >= midnight -> context.getString(app.rcq.android.R.string.last_seen_today)
        ts >= midnight - day -> context.getString(app.rcq.android.R.string.last_seen_yesterday)
        ts >= midnight - 6 * day -> context.getString(app.rcq.android.R.string.last_seen_this_week)
        ts >= midnight - 29 * day -> context.getString(app.rcq.android.R.string.last_seen_this_month)
        else -> context.getString(app.rcq.android.R.string.last_seen_long_ago)
    }
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

/** ICQ-style collapsible section header: chevron · TITLE · (count) · trailing.
 *
 *  @param onLongPress opens the section menu (founder item 1 of 23.08). Null on
 *  a header that has no menu: the request rows, and any section whose PIN has
 *  not been answered. See the note on [SectionMenuSheet].
 *  @param locked draws the key glyph and, with [count] hidden by the caller,
 *  is the whole of what a gated section shows. A member count or an unread
 *  badge here is a leak of exactly what the user asked to hide.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SectionHeader(
    title: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    showCount: Boolean = true,
    locked: Boolean = false,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val c = RcqTheme.colors
    // 0.7 is this header's own look against the theme background and stays that
    // on a screen with no wallpaper ([LocalHomeVeil] is 1f there).
    //
    // ⚠ Over a home wallpaper it takes the LIST's veil when the list is more
    // opaque than that. The rows around it went translucent by a number chosen
    // for contrast (0.9 when the wallpaper fights the theme), and a header left
    // at a flat 0.7 would be the one strip on the screen where the picture
    // shows through harder than anywhere else, with the section title, the
    // dimmest text on the row, sitting on it.
    val listVeil = LocalHomeVeil.current
    val veil = if (listVeil < 1f) maxOf(0.7f, listVeil) else 0.7f
    // Л2.12: with a home wallpaper the header stands on a frosted slice of it
    // rather than on the plain veil. The wash on top then drops to the iOS
    // translucent-surface tint (0.58): the frost underneath restores the
    // ground the heavier veil alone used to provide. A wallpaper that FIGHTS
    // the theme keeps the 0.9 wash - frost softens the picture but does not
    // buy back text contrast, so the AA arithmetic on the veil constant still
    // rules. Null / inactive slices (no wallpaper, or a screen that never
    // provides them) keep the exact fill this header has always had.
    val slices = LocalWallpaperSlices.current
    val fill = if (slices?.active == true) {
        val wash = if (listVeil >= 0.9f) 0.9f else 0.58f
        Modifier.wallpaperSlice(slices, veil = c.bgSecondary.copy(alpha = wash))
    } else {
        Modifier.background(c.bgSecondary.copy(alpha = veil))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(0.dp))
            .then(fill)
            .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CollapseChevron(collapsed)
        Spacer(Modifier.size(6.dp))
        // #593: with the system font turned up the title ran past the right
        // edge and took `(count)` — and the trailing unread badge behind it —
        // off screen with it. The title is the part that gives way: it gets
        // whatever the row has left over and ellipsizes there, while the count
        // and the trailing slot are measured first and always keep their width.
        // The text still grows with the font setting; only its ceiling changed.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.uppercase(),
                color = c.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // fill = false so a short title keeps the count next to it
                // instead of parking it at the far end of the row.
                modifier = Modifier.weight(1f, fill = false),
            )
            if (locked) {
                Spacer(Modifier.size(4.dp))
                Icon(
                    Icons.Filled.Key,
                    contentDescription = null,
                    tint = c.textSecondary,
                    modifier = Modifier.size(13.dp),
                )
            }
            if (showCount) {
                Spacer(Modifier.size(4.dp))
                Text("($count)", color = c.textSecondary, fontSize = 11.sp, maxLines = 1)
            }
        }
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
 *  ⚠ Deliberately NOT used where strangers meet: Random, Nearby and an
 *  unaccepted contact request all keep the glyph. A picture is for people
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
    /** Draw the person WITHOUT their presence. One screen asks for this: a
     *  call, where the flower answers "are they around" to somebody who is
     *  listening to them breathe. Off, the picture stands alone; with no
     *  picture this draws nothing at all, so the caller supplies its own
     *  fallback (the call screen already has its lettered disc). */
    showStatus: Boolean = true,
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
        if (!showStatus) return
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
        // ⚠ No disc under it. The badge used to sit on a `bgPrimary` circle,
        // which is white in the light theme and near-black in the dark one, and
        // over a wallpaper or a photograph that disc is the thing the eye
        // catches rather than the status on it (founder, 24.08). The glyph is
        // a solid coloured shape with its own outline: it reads on a picture
        // without a plate under it, the same way it does on iOS.
        if (showStatus) Box(
            Modifier.align(Alignment.BottomStart)
                .offset(x = -(badge / 4), y = badge / 4)
                .size(badge)
                .clip(CircleShape)
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
    ) {
        // ⚠ White on the disabled fill is white on light grey. The button used
        // to live only on the accent, so nobody saw it; on a sheet a disabled
        // Send is a common state and it has to stay readable.
        Text(
            label,
            color = if (enabled) Color.White else c.textSecondary,
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * The one dialog for removing a contact.
 *
 * There used to be two, and each was missing what the other had: the home-screen
 * one asked what to do with the messages but had no way out, while the profile
 * one had Cancel and silently kept the history without asking (reported with
 * screenshots on 0.98). Same action, two answers to the same question, depending
 * on where you started.
 *
 * Three stacked choices rather than a confirm/dismiss pair, because "delete the
 * messages too" and "keep them" are different outcomes, not a confirmation:
 * neither should be the one you hit by reflex, and backing out has to be
 * possible from both. As a sheet the two outcomes are rows and the way out is
 * the cancel row the sheet appends.
 */
@Composable
internal fun RemoveContactDialog(
    nickname: String,
    onDismiss: () -> Unit,
    onRemove: (alsoDeleteMessages: Boolean) -> Unit,
) {
    RcqAskSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.home_remove_title, nickname),
        body = stringResource(R.string.home_remove_body),
        actions = listOf(
            SheetAction(stringResource(R.string.home_remove_with_chat), destructive = true) { onRemove(true) },
            SheetAction(stringResource(R.string.home_remove_keep_chat)) { onRemove(false) },
        ),
    )
}

/**
 * An island's face: its operator's logo, or the lettered tile when it has none.
 *
 * Rounded square, not a circle: a person is a circle and a group is a circle,
 * and an island is neither. Same shape iOS draws (`IslandAvatarView`) and the
 * desktop draws (`web-chat/src/components/IslandAvatar.tsx`), and the same tint
 * from the same hash, so an island without a logo looks like the same island on
 * all four clients.
 *
 * ⚠⚠ FALLS BACK IN FOUR DIRECTIONS AND NEVER SHOWS A BROKEN IMAGE:
 *   * an island with no logo -> [logoVersion] is empty -> the tile;
 *   * an island too old to know the field -> `logo_version` is absent, which
 *     Gson fills with its default "" -> the tile;
 *   * an island that has not answered yet, or at all -> no version -> the
 *     tile, drawn on the FIRST frame and replaced in place if a logo lands;
 *   * bytes that arrive but do not decode -> [rememberSampledBitmap] answers
 *     null -> the tile.
 * There is no state in which this draws an empty box. The tile is drawn
 * underneath and the picture covers it, which is the same trick [AccountAvatar]
 * uses so a missing blob looks exactly like it did before pictures existed.
 */
@Composable
internal fun IslandAvatar(
    /** The island. Every screen that lists more than one account passes the
     *  ROW's own host, never the active one: an account living on another
     *  island keeps its face there. */
    host: String?,
    /** `logo_version` from that island's `/server/info`. Empty or null for an
     *  island with no logo, or one we have not asked yet. */
    logoVersion: String?,
    /** What the island calls itself, for the letter on the tile. Falls back to
     *  the host, which is all anybody honestly knows about an island that has
     *  never answered. */
    name: String? = null,
    size: Dp = 28.dp,
    modifier: Modifier = Modifier,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Seeded from the memory cache rather than from null, the same reason
    // PersonAvatar is: starting at null redraws the tile on every appearance
    // and swaps the picture in a frame later.
    val bytes by produceState<ByteArray?>(
        initialValue = app.rcq.android.data.IslandLogos.cached(host, logoVersion),
        host, logoVersion,
    ) {
        value = app.rcq.android.data.IslandLogos.load(ctx, host, logoVersion)
    }
    // Still frames only: an animated island logo is served as its first frame
    // here, which is what the phones already do for an animated account avatar
    // in a list. A 28dp tile is not where an animation is worth a decoder.
    val image = rememberSampledBitmap(bytes?.takeIf { it.isJpegOrPng() }, maxPx = 256)
        ?: rememberGifFirstFrame(bytes)
    val shape = RoundedCornerShape(size * 0.28f)
    // ⚠ The tint is the TILE, not a mat under the picture. A logo is a PNG with
    // an alpha channel (that is the whole point of refusing to flatten one, see
    // the admin console), so painting the tile behind it showed the island's
    // hash colour THROUGH the mark: the founder's flower came out on a green
    // square (24.08). With a picture there is nothing to tint.
    Box(
        Modifier.size(size).clip(shape)
            .then(if (image == null) Modifier.background(islandTint(host)) else Modifier)
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                islandInitial(name, host),
                color = Color.White,
                fontSize = (size.value * 0.46f).sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            )
        }
    }
}

/**
 * The tile's colour, derived from the host.
 *
 * ⚠ FNV-1a over the host, never [String.hashCode]. iOS spells out why for its
 * own copy: Swift seeds its hashing per process, so an island changed colour on
 * every launch. Kotlin's hashCode is stable, so ours would not do that, but a
 * DIFFERENT hash is a different colour from the phone next to it for the same
 * island, which is the same bug seen from one device over.
 */
private fun islandTint(host: String?): Color {
    var hash = 2166136261u
    for (byte in (host ?: "").lowercase().toByteArray()) {
        hash = (hash xor byte.toUByte().toUInt()) * 16777619u
    }
    // Off full saturation so the tile reads as chrome rather than as an alert.
    return Color.hsv((hash % 360u).toFloat(), 0.46f, 0.62f)
}

/** First LETTER, not first character: a name that opens with an emoji or a
 *  bracket would otherwise draw a tile with punctuation on it. */
private fun islandInitial(name: String?, host: String?): String {
    val source = (name?.takeIf { it.isNotBlank() } ?: host.orEmpty()).trim()
    val ch = source.firstOrNull { it.isLetter() || it.isDigit() } ?: return "#"
    return ch.uppercase()
}
