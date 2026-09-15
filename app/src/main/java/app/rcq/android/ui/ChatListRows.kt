package app.rcq.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.data.LocalStores
import app.rcq.android.model.Contact
import app.rcq.android.model.RcqGroup
import app.rcq.android.model.UserStatus

/**
 * The rows of the chat list: Saved Messages, a group, a person.
 *
 * Moved out of [HomeScreen] unchanged so the share picker (report #987: "the
 * chat list should look like my roster, so I can find where to send quickly")
 * draws exactly the rows the home screen draws, instead of a look-alike that
 * drifts the first time one of them gains a mark. The long press is optional
 * for that reason: the picker has nothing to preview and passes none.
 */

/** Saved Messages in the chat list. Same shape as a contact row so it does not
 *  read as a special banner, with a bookmark instead of an avatar. */
@Composable
internal fun SavedRow(count: Int, unread: Int, onClick: () -> Unit) {
    val c = RcqTheme.colors
    Row(
        // A fill of its own, because it is a list row and every other list row
        // has one. Identical to the screen's background without a wallpaper;
        // with one it takes the same veil as its neighbours instead of leaving
        // its two lines of text standing on the picture (founder item 18b).
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(c.bgPrimary.copy(alpha = LocalHomeVeil.current))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.bgSecondary), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Bookmark, null, tint = c.accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.home_menu_saved), color = c.textPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                pluralStringResource(R.plurals.saved_notes, count, count),
                color = c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (unread > 0) UnreadBadge(unread)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GroupRow(group: RcqGroup, ownUin: Int, session: Session, unread: Int, onClick: () -> Unit, onLongPress: (() -> Unit)? = null) {
    val c = RcqTheme.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    // NOT `by`: the animated value is read inside the graphicsLayer block
    // below, in the draw phase, so a press animates without recomposing the row.
    val scale = animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    // Observe the mute set so toggling mute reflects on the row immediately
    // (was a one-shot read → the bell only appeared after leaving + re-entering).
    val mutedSet by LocalStores.muted.collectAsState()
    val muted = LocalStores.groupThread(group.id) in mutedSet
    val reactSet by LocalStores.reactionInbox.collectAsState()
    val mentionSet by LocalStores.mentionInbox.collectAsState()
    val thread = LocalStores.groupThread(group.id)
    val hasReaction = thread in reactSet
    val hasMention = thread in mentionSet
    Row(
        Modifier.fillMaxWidth()
            // graphicsLayer, not Modifier.scale: `scale` is read here in the
            // modifier chain, so every frame of the press animation invalidated
            // this row's COMPOSITION. Read inside the layer block instead and
            // the animation costs a redraw, which is what it is.
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLongPress)
            .background(c.bgPrimary.copy(alpha = LocalHomeVeil.current))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            // Animate the group's GIF avatar here too (founder: it animated in
            // the chat but not on the home list). Safe: the chat list is a
            // LazyColumn, so only the handful of on-screen group rows compose,
            // and SafeAnimatedGif memoizes its decoder per instance — far lighter
            // than the emoticon-dense-message churn that caused the old OOM.
            GroupAvatar(group, session, 28.dp, animated = true)
            UnreadBadge(unread, Modifier.align(Alignment.TopEnd))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(group.name, color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BadgeMark(group.badge)
                if (group.ownerUin == ownUin) Icon(Icons.Filled.Star, "Owner", tint = c.accent, modifier = Modifier.size(12.dp))
                if (muted) Icon(Icons.Filled.NotificationsOff, null, tint = c.textSecondary, modifier = Modifier.size(11.dp))
            }
            Text(
                memberCountLabel(group.memberCount) + (group.host?.let { " · $it" } ?: ""),
                color = c.textSecondary, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (hasMention || hasReaction) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (hasMention) Icon(Icons.Filled.AlternateEmail, stringResource(R.string.home_mention_indicator), tint = c.accent, modifier = Modifier.size(14.dp))
                if (hasReaction) Icon(Icons.Filled.Favorite, stringResource(R.string.home_reaction_indicator), tint = Color(0xFFE5484D), modifier = Modifier.size(14.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ContactRowItem(contact: Contact, unread: Int, session: Session, onClick: () -> Unit, onLongPress: (() -> Unit)? = null) {
    val aliases by LocalStores.aliases.collectAsState()
    // My own name for this person wins over the nickname they chose. Device-only
    // (see LocalStores.aliases) — a rename says more about the relationship than
    // the contact row does, and the island has no business holding it.
    val shownName = aliases[LocalStores.aliasKey(contact.uin, contact.host)] ?: contact.nickname
    val c = RcqTheme.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    // NOT `by`: the animated value is read inside the graphicsLayer block
    // below, in the draw phase, so a press animates without recomposing the row.
    val scale = animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    val mutedSet by LocalStores.muted.collectAsState()
    val muted = LocalStores.peerThread(contact.uin) in mutedSet
    val reactSet by LocalStores.reactionInbox.collectAsState()
    val mentionSet by LocalStores.mentionInbox.collectAsState()
    val thread = LocalStores.peerThread(contact.uin)
    val hasReaction = thread in reactSet
    val hasMention = thread in mentionSet

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // See [GroupRow]: read the press animation in the draw phase, not
            // in composition.
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLongPress)
            .background(c.bgPrimary.copy(alpha = LocalHomeVeil.current))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            // A picture when the contact has one, the status flower otherwise.
            // Cross-island rows used to keep the glyph unconditionally, because
            // the blob did not cross islands. §5e crosses it: the peer DEPOSITS
            // their encrypted picture into our island and hands us the key in a
            // sealed envelope, so there is a real picture to draw and it is
            // fetched from our own island like any other. Presence still does
            // not cross — that is what `crossIsland` keeps marking.
            PersonAvatar(
                contact.avatarMediaId, contact.avatarMediaKey,
                contact.presence, session, 28.dp, crossIsland = contact.host != null,
            )
            UnreadBadge(unread, Modifier.align(Alignment.TopEnd))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    shownName,
                    color = if (contact.presence == UserStatus.OFFLINE) c.textSecondary else c.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BadgeMark(contact.badge)
                GenderIcon(contact.gender)
                if (contact.blocked) Icon(Icons.Outlined.Block, null, tint = c.statusBusy, modifier = Modifier.size(11.dp))
                if (muted) Icon(Icons.Filled.NotificationsOff, null, tint = c.textSecondary, modifier = Modifier.size(11.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${contact.uin}", color = c.textMono, fontSize = 12.sp)
                val ctx = LocalContext.current
                // ⚠ Order matters, and it used to be the other way round: a
                // status message won outright, so an OFFLINE contact who had
                // one never showed when they were last around. Measured on
                // prod 31.08: of 1498 contact rows genuinely offline, 455
                // (30%) carry a status message, so for nearly a third of
                // people the last seen was invisible everywhere - most
                // visibly in Favourites and user sections, where there is no
                // Online/Offline heading to read it off instead. A status
                // message is text somebody left behind; when they are not
                // here, WHEN they were here is the more useful half, so it
                // goes first and the message keeps whatever room is left.
                val seen = if (contact.presence == UserStatus.OFFLINE && contact.lastSeen != null) {
                    lastSeenPhrase(contact.lastSeen, contact.gender, ctx)
                } else null
                val msg = contact.statusMessage?.takeIf { it.isNotEmpty() }
                // Both worth saying, room for one: they take turns (founder).
                if (contact.host == null && seen != null && msg != null) {
                    Text("·", color = c.textSecondary, fontSize = 12.sp)
                    AltText(seen, msg, c.textSecondary, 12.sp)
                    return@Row
                }
                val sub = when {
                    // §5c: a cross-island peer shows its island (presence/last_seen
                    // don't cross islands), then any status message.
                    contact.host != null -> contact.host + (msg?.let { " · $it" } ?: "")
                    seen != null -> seen
                    else -> msg
                }
                if (sub != null) {
                    Text(
                        "· $sub",
                        color = c.textSecondary,
                        fontSize = 12.sp,
                        // Italic marked "this is their own words". Now that a
                        // last seen can share the line, italics would be a lie
                        // about half of it, so it is kept only when the line is
                        // nothing BUT their words.
                        fontStyle = if (seen == null && msg != null) FontStyle.Italic else FontStyle.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (hasMention || hasReaction) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (hasMention) Icon(Icons.Filled.AlternateEmail, stringResource(R.string.home_mention_indicator), tint = c.accent, modifier = Modifier.size(14.dp))
                if (hasReaction) Icon(Icons.Filled.Favorite, stringResource(R.string.home_reaction_indicator), tint = Color(0xFFE5484D), modifier = Modifier.size(14.dp))
            }
        }
    }
}
