package app.rcq.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.launch

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
     *  iOS `cleanForwardCredit`.
     *
     *  ⚠ A number followed by "@island" is not a name either: it is how a
     *  person on another island with no name of their own is stored
     *  ([app.rcq.android.Session.addCrossIslandContactDetailed], the accept
     *  path), and would put their number AND island in every recipient's
     *  chat. A chosen name like "@alice" or "Alice@work" stays. */
    fun cleanCredit(raw: String?): String? {
        val name = Envelope.sanitizeFwdName(raw) ?: return null
        val bare = name.removePrefix("#")
        val number = if ('@' in bare) bare.substringBefore('@') else bare
        if (bare.isEmpty() || (number.isNotEmpty() && number.all { it.isDigit() })) return null
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

    enum class Refusal { FILES, LINKS, SLOWMODE }

    /** How many posts [items] make in a room: an album that travels whole is
     *  one (the forward sends it under one slowmode token, as the album picker
     *  does, #950), everything else one each. */
    fun posts(items: List<ChatMessage>): Int {
        val albums = items.filter { inAlbum(it) }
            .groupingBy { it.albumId!! }.eachCount().filterValues { it > 1 }.keys
        return items.count { m -> !inAlbum(m) || m.albumId !in albums } + albums.size
    }

    private fun inAlbum(m: ChatMessage): Boolean = m.albumId != null && (m.kind == "photo" || m.kind == "video")

    /** Why [target]'s rules turn this whole pick away, or null when they let
     *  it through. The room's content rules (#755) are the sender's to keep:
     *  the island cannot read what a sealed envelope carries. All or nothing:
     *  half a forward that silently left out the pictures is worse than a no.
     *  The owner and the moderators are exempt, as at the composer.
     *
     *  Slowmode is the island's to keep, and it would: the first post of the
     *  pick buys the slot and every one after it comes back 429, a column of
     *  red bubbles for what was one tap. So a pick of more than one post is
     *  turned away whole, before anything goes. The exemption is the same
     *  set the island uses (owner, admin, any granted cap). */
    fun refusal(target: RcqGroup?, ownUin: Int, items: List<ChatMessage>): Refusal? {
        if (target == null || target.roomExempt(ownUin)) return null
        if (!target.filesAllowed && items.any { it.kind in FILE_KINDS }) return Refusal.FILES
        if (!target.linksAllowed && items.any { HTTP_URL_GATE.containsMatchIn(it.body) }) return Refusal.LINKS
        if (target.slowmodeSec > 0 && posts(items) > 1) return Refusal.SLOWMODE
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
 *
 * ⚠ The pick belongs to the owner the picker was opened under ([UiOwner]).
 * The chat screen is keyed by owner and starts over on a switch, but a tap
 * that lands while the roster below is still being fetched must not hand one
 * account's messages to the next one's send.
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
    val owner = remember { UiOwner.now() }
    val scope = rememberCoroutineScope()
    // One pick at a time: the room check can wait on the island, and a second
    // tap meanwhile would forward the same messages twice.
    var busy by remember { mutableStateOf(false) }
    BackHandler { onCancel() }
    // ⚠ The picker is a layer over the chat, not a screen of its own, and a
    // tap on a part of it that takes no touches (the title, a gap between the
    // rows) would otherwise reach the chat underneath: its header's call
    // button, a bubble. This box is hit first and keeps them here; it
    // consumes nothing, so everything inside works as it does on the share
    // screen.
    Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        ChatPickerScreen(
            session = session,
            title = stringResource(R.string.chat_forward_title),
            summary = pluralStringResource(R.plurals.chat_forward_summary, items.size, items.size),
            groupFilter = { it.canPost(ownUin) },
            // Blocked at THEIR address, the rule the chat list and the card use:
            // the same number on another island is somebody else.
            contactFilter = { !session.isBlockedAddress(it.uin, it.host) },
            onCancel = onCancel,
            onPick = { picked ->
                if (busy) return@ChatPickerScreen
                busy = true
                scope.launch {
                    try {
                        if (forwardTo(session, picked, items, isSelf, ownUin, owner, wireName, aliases.values, someone, savedTitle, context)) onSent()
                    } finally {
                        busy = false
                    }
                }
            },
        )
    }
}

/** The pick itself, out of the composable. False when nothing went: the room
 *  turned the pick away (and said so), or the owner changed underneath. */
private suspend fun forwardTo(
    session: Session,
    picked: ChatTarget,
    items: List<ChatMessage>,
    isSelf: Boolean,
    ownUin: Int,
    owner: String,
    wireName: (ChatMessage) -> String,
    myAliases: Collection<String>,
    someone: String,
    savedTitle: String,
    context: android.content.Context,
): Boolean {
    if (UiOwner.now() != owner) return false
    val toGroup = (picked as? ChatTarget.Group)?.id
    val toPeer = (picked as? ChatTarget.Peer)?.uin
    // Read FRESH: the rules can have changed since the list was drawn.
    var room = toGroup?.let { gid -> session.groups.value.firstOrNull { it.id == gid } }
    var refused = ForwardRules.refusal(room, ownUin, items)
    // A moderator is exempt only by the roster, and the chat list is fetched
    // without one (#650): ask for it before saying no to somebody it exempts.
    if (refused != null && room != null && room.members.none { it.uin == ownUin }) {
        room = try {
            session.ensureRoster(room.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: room
        refused = ForwardRules.refusal(room, ownUin, items)
        if (UiOwner.now() != owner) return false
    }
    if (refused != null) {
        val res = when (refused) {
            ForwardRules.Refusal.FILES -> R.string.chat_files_off
            ForwardRules.Refusal.LINKS -> R.string.chat_links_off
            ForwardRules.Refusal.SLOWMODE -> R.string.chat_forward_slowmode
        }
        android.widget.Toast.makeText(context, context.getString(res), android.widget.Toast.LENGTH_SHORT).show()
        return false
    }
    val myNick = session.nickname.takeUnless { it == NO_NICK }
    val credits = items.associate { m ->
        // A note in Saved Messages is mine whichever side it was filed
        // on (notes from before #599 are `fromMe = false` on disk).
        val author = if (m.fromMe || isSelf) myNick else wireName(m)
        m.id to (ForwardRules.credit(m, author, myAliases) ?: someone)
    }
    session.forwardMessages(items, toPeer, toGroup, credits)
    val where = when {
        room != null -> room.name
        toPeer == ownUin -> savedTitle
        // This device's name for them (my alias included): the toast is
        // read here and goes nowhere.
        else -> session.contactName(toPeer ?: 0, session.contacts.value.firstOrNull { it.uin == toPeer }?.host)
    }
    android.widget.Toast.makeText(context, context.getString(R.string.chat_forwarded_to, where), android.widget.Toast.LENGTH_SHORT).show()
    return true
}
