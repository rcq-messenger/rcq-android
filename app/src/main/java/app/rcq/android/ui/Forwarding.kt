package app.rcq.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.crypto.Envelope
import app.rcq.android.data.LocalStores
import app.rcq.android.model.ChatMessage
import app.rcq.android.model.DeliveryState
import app.rcq.android.model.RcqGroup

/**
 * What multi-select and forwarding may touch (report 1063). Pure, so the rules
 * are pinned by a test rather than by whichever screen happens to ask.
 */
internal object ForwardRules {

    /** The most messages one selection holds, the web's SELECTION_MAX. It
     *  keeps a "delete for everyone" of the whole pick inside what the
     *  island's rate limiter lets through, and a forward inside one sitting. */
    const val SELECTION_MAX = 50

    private val FORWARD_KINDS = setOf("text", "photo", "video", "voice", "file", "location")
    private val MEDIA_KINDS = setOf("photo", "video", "voice", "file")

    /** What a room with files switched off refuses (#755): the same kinds
     *  the attach sheet greys out there. A voice note is not one of them. */
    private val FILE_KINDS = setOf("photo", "video", "file")

    /** May [m] be ticked at all. A call record and a system notice are not
     *  messages anybody wrote; there is nothing to forward or retract. */
    fun selectable(m: ChatMessage): Boolean = m.kind != "call" && m.kind != "system"

    /**
     * May [m] be forwarded.
     *
     * ⚠ Never a disappearing message: its sender was promised it goes, and a
     * forward is a fresh, permanent copy of it in somebody else's chat. Never
     * my own row that has not left yet (or failed to): a forward of something
     * nobody received yet. Never media whose bytes are not here in full
     * ([onDevice]): a forward re-sends the bytes, and must not quietly become
     * a download. Never a room invite (web parity: an invite is a key to a
     * room, and the person forwarding it may not be the one who should hand
     * it out). Polls, relay cards, calls and notices are not content.
     */
    fun forwardable(m: ChatMessage, onDevice: (String?) -> Boolean, isInvite: (String) -> Boolean): Boolean {
        if (m.kind !in FORWARD_KINDS) return false
        if (m.expiresAt != null) return false
        if (m.fromMe && (m.state == DeliveryState.SENDING || m.state == DeliveryState.FAILED)) return false
        return when (m.kind) {
            "text" -> m.body.isNotBlank() && !isInvite(m.body)
            "location" -> m.lat != null && m.lng != null
            in MEDIA_KINDS -> m.mediaId != null && m.mediaKey != null && onDevice(m.mediaId)
            else -> false
        }
    }

    /** May [m] be retracted for everyone: the single-message menu's rule, word
     *  for word. Mine, or anybody's where I moderate the room. Saved Messages
     *  has no "everyone" (you ARE everyone there), and its one delete already
     *  reaches every device. */
    fun canRetract(m: ChatMessage, isSelf: Boolean, moderator: Boolean): Boolean =
        !isSelf && (m.fromMe || moderator)

    /** A credit fit for the wire: what [Envelope.sanitizeFwdName] keeps, and
     *  null when that is nothing or only a number (with or without "#"), which
     *  is how a person with no name reads everywhere in this app. Same rule as
     *  iOS `cleanForwardCredit`. */
    fun cleanCredit(raw: String?): String? {
        val name = Envelope.sanitizeFwdName(raw) ?: return null
        val bare = name.removePrefix("#")
        if (bare.isEmpty() || bare.all { it.isDigit() }) return null
        return name
    }

    /**
     * The "forwarded from" label [m] goes out with, or null for the neutral
     * word the caller puts there instead.
     *
     * ⚠⚠ It rides INSIDE the envelope to every recipient, so it is the name
     * the author chose for themselves ([authorWireName], the same name a quote
     * carries), never my alias for them and never their number. A forward of
     * a forward keeps the ORIGINAL author's credit, as iOS does; one I sent
     * myself from an older iPhone build may carry my alias for somebody, so a
     * credit on my own row that matches one of [myAliases] is not passed on.
     */
    fun credit(m: ChatMessage, authorWireName: String?, myAliases: Collection<String>): String? {
        cleanCredit(m.fwdName)?.let { earlier ->
            if (m.fromMe && earlier in myAliases) return null
            return earlier
        }
        return cleanCredit(authorWireName)
    }

    enum class Refusal { FILES, LINKS }

    /** Why [target]'s rules turn this whole pick away, or null when they let
     *  it through. The room's content rules (#755) are the sender's to keep:
     *  the island cannot read what a sealed envelope carries. All or nothing:
     *  half a forward that silently left out the pictures is worse than a no.
     *  The owner and the moderators are exempt, as at the composer. */
    fun refusal(target: RcqGroup?, ownUin: Int, items: List<ChatMessage>): Refusal? {
        if (target == null || target.roomExempt(ownUin)) return null
        if (!target.filesAllowed && items.any { it.kind in FILE_KINDS }) return Refusal.FILES
        if (!target.linksAllowed && items.any { HTTP_URL_GATE.containsMatchIn(it.body) }) return Refusal.LINKS
        return null
    }
}

/** True when [body] is a room invite, the card [GroupLinkBubble] draws. */
internal fun isInviteText(body: String): Boolean = GroupLinkParser.parse(body) != null

/** "Forwarded from X" over a bubble (report 1063). One line, small and grey:
 *  it says where the words came from without competing with them. [pill] puts
 *  it on the same contrast plate the time under a media bubble gets on a
 *  chat wallpaper. */
@Composable
internal fun ForwardedLabel(name: String, modifier: Modifier = Modifier, pill: Boolean = false) {
    val c = RcqTheme.colors
    Row(
        modifier.then(
            if (pill) Modifier.clip(RoundedCornerShape(8.dp)).background(c.bgSecondary.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 1.dp)
            else Modifier,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.Forward, null, tint = c.textSecondary, modifier = Modifier.size(12.dp))
        Text(
            stringResource(R.string.chat_forwarded_from, name),
            color = c.textSecondary,
            fontSize = 11.sp,
            fontStyle = FontStyle.Italic,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** What [Session.nickname] reads when the account has no nickname at all. */
private const val NO_NICK = "—"

/**
 * "Forward to…" over the whole chat: the home roster as the share sheet draws
 * it ([ChatPickerScreen]), minus the rooms this person cannot post in and the
 * people they blocked. Picking a chat checks its room rules, hands the pick to
 * [Session.forwardMessages] and comes back to THIS chat, as iOS and the web
 * do: the progress strip and a failure toast say the rest.
 *
 * [wireName] is the chat's own "name for the wire" ([Session.memberWireName] /
 * [Session.contactWireName]): never my alias.
 */
@Composable
internal fun ForwardPickerLayer(
    session: Session,
    items: List<ChatMessage>,
    isSelf: Boolean,
    ownUin: Int,
    wireName: (ChatMessage) -> String,
    onCancel: () -> Unit,
    onSent: () -> Unit,
) {
    val context = LocalContext.current
    val someone = stringResource(R.string.chat_forward_someone)
    val savedTitle = stringResource(R.string.chat_saved_title)
    val aliases by LocalStores.aliases.collectAsState()
    BackHandler { onCancel() }
    ChatPickerScreen(
        session = session,
        title = stringResource(R.string.chat_forward_title),
        summary = pluralStringResource(R.plurals.chat_forward_summary, items.size, items.size),
        groupFilter = { it.canPost(ownUin) },
        contactFilter = { !LocalStores.isBlocked(it.uin) },
        onCancel = onCancel,
        onPick = { picked ->
            val toGroup = (picked as? ChatTarget.Group)?.id
            val toPeer = (picked as? ChatTarget.Peer)?.uin
            // Read FRESH: the rules can have changed since the list was drawn.
            val room = toGroup?.let { gid -> session.groups.value.firstOrNull { it.id == gid } }
            val refused = ForwardRules.refusal(room, ownUin, items)
            if (refused != null) {
                val res = if (refused == ForwardRules.Refusal.FILES) R.string.chat_files_off else R.string.chat_links_off
                android.widget.Toast.makeText(context, context.getString(res), android.widget.Toast.LENGTH_SHORT).show()
                return@ChatPickerScreen
            }
            val myNick = session.nickname.takeUnless { it == NO_NICK }
            val credits = items.associate { m ->
                // A note in Saved Messages is mine whichever side it was filed
                // on (notes from before #599 are `fromMe = false` on disk).
                val author = if (m.fromMe || isSelf) myNick else wireName(m)
                m.id to (ForwardRules.credit(m, author, aliases.values) ?: someone)
            }
            session.forwardMessages(items, toPeer, toGroup, credits)
            val where = when {
                room != null -> room.name
                toPeer == ownUin -> savedTitle
                else -> session.contactName(toPeer ?: 0)
            }
            android.widget.Toast.makeText(context, context.getString(R.string.chat_forwarded_to, where), android.widget.Toast.LENGTH_SHORT).show()
            onSent()
        },
    )
}
