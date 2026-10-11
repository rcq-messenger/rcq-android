package app.rcq.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.model.ChatMessage
import app.rcq.android.model.RcqGroup

/**
 * Multi-select in a chat (report 1063): tick messages, then forward them or
 * delete them in one go. iOS and the web have had it; Android had neither.
 *
 * ⚠⚠ Everything lives HERE, and ChatScreen only holds a few hook lines.
 * ChatScreen() is at the edge of what ART's verifier accepts for one method
 * (see the kdoc on MessageLongPressOverlay: the release dex once failed to
 * verify and every chat crashed on open, while the compiler stayed green).
 * Each composable below is its own method and its own verification unit.
 */

/** The set arithmetic, pure so a test pins it. A message and a whole album
 *  are both a GROUP of ids that is ticked and unticked as one. */
internal object SelectionMath {

    /** [ids] with [group] flipped: all of it off when all of it was on,
     *  otherwise all of it on. Null when turning it on would pass [max]: the
     *  pick stays as it was rather than taking half an album. */
    fun toggle(ids: Set<String>, group: Collection<String>, max: Int): Set<String>? {
        if (group.isEmpty()) return ids
        if (group.all { it in ids }) return ids - group.toSet()
        val next = ids + group
        return if (next.size > max) null else next
    }

    /** [ids] minus whatever is no longer in the thread (deleted here or for
     *  everyone, expired), or [ids] itself when nothing went. */
    fun retain(ids: Set<String>, present: Set<String>): Set<String> =
        if (ids.all { it in present }) ids else ids.filterTo(HashSet()) { it in present }
}

/** The selection of one chat screen. Ids only: the messages themselves are
 *  read back from the live thread every time ([picked]), so a message that
 *  is deleted or expires while ticked simply drops out of the pick. Empty is
 *  "not selecting": unticking the last one leaves the mode, as on iOS. */
@Stable
internal class ChatSelection {
    var ids: Set<String> by mutableStateOf(emptySet())
        private set

    val active: Boolean get() = ids.isNotEmpty()

    /** The pick the "Forward to…" screen is open for, or null. Also set by
     *  the single-message Forward, with no selection behind it. */
    var forwarding: List<ChatMessage>? by mutableStateOf(null)

    /** The delete question is up. */
    var confirmDelete: Boolean by mutableStateOf(false)

    /** Start selecting with [seed] ticked: one message, or a whole album. */
    fun enter(seed: Collection<String>) {
        ids = seed.take(ForwardRules.SELECTION_MAX).toSet()
    }

    /** Flip [group] as one. False when it would pass the cap; nothing
     *  changes then and the caller says why. */
    fun toggle(group: Collection<String>): Boolean {
        val next = SelectionMath.toggle(ids, group, ForwardRules.SELECTION_MAX) ?: return false
        ids = next
        if (next.isEmpty()) confirmDelete = false
        return true
    }

    fun clear() {
        ids = emptySet()
        confirmDelete = false
    }

    fun retain(present: Set<String>) {
        val next = SelectionMath.retain(ids, present)
        if (next !== ids) {
            ids = next
            if (next.isEmpty()) confirmDelete = false
        }
    }

    /** The ticked messages, oldest first, out of the live [messages]. */
    fun picked(messages: List<ChatMessage>): List<ChatMessage> {
        val set = ids
        if (set.isEmpty()) return emptyList()
        return messages.filter { it.id in set }.sortedBy { it.sentAt }
    }
}

/**
 * One row of the list, tickable while [selection] is active. [ids] is the
 * message, or every message of an album: an album is ticked as a whole.
 *
 * ⚠ The SAME tree whether or not selection is on, with the tick and the cover
 * added beside the content: swapping the tree would rebuild every bubble on
 * the way in and out, and lose what they hold (a revealed spoiler, a picture
 * already decoded).
 *
 * While selecting, a transparent cover lies over the bubble and takes the
 * taps and the long presses itself. It is the topmost sibling, so nothing
 * under it sees them: no viewer, no player, no link, no spoiler reveal, no
 * swipe-to-quote. A drag still scrolls the list, which is an ancestor.
 */
@Composable
internal fun SelectableRow(selection: ChatSelection, ids: List<String>, content: @Composable () -> Unit) {
    val c = RcqTheme.colors
    val active = selection.active
    val selected = active && ids.isNotEmpty() && ids.all { it in selection.ids }
    val context = LocalContext.current
    Box(
        Modifier.fillMaxWidth()
            .then(if (selected) Modifier.clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = 0.12f)) else Modifier),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (active) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) c.accent else c.textSecondary,
                    modifier = Modifier.padding(start = 2.dp, end = 4.dp).size(22.dp),
                )
            }
            Box(Modifier.weight(1f)) { content() }
        }
        // Over the tick as well as the bubble: the circle is the obvious
        // thing to tap, and on its own it takes no touches.
        if (active) {
            Box(
                Modifier.matchParentSize().pointerInput(selection, ids) {
                    val flip = {
                        if (!selection.toggle(ids)) {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.chat_selection_too_many, ForwardRules.SELECTION_MAX),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    detectTapGestures(onTap = { flip() }, onLongPress = { flip() })
                },
            )
        }
    }
}

/**
 * The bar that stands where the composer was while selecting: Cancel, how
 * many, Forward and Delete. Forward is there only when EVERY ticked message
 * can go (iOS and web do the same): a forward that quietly skipped some of
 * them would arrive as something other than what was ticked. Also drawn in a
 * read-only room, which has no composer: forwarding out of a channel is the
 * point there.
 */
@Composable
internal fun ChatSelectionBar(session: Session, selection: ChatSelection, messages: List<ChatMessage>) {
    val c = RcqTheme.colors
    val picked = remember(messages, selection.ids) { selection.picked(messages) }
    val canForward = remember(picked) {
        picked.isNotEmpty() && picked.all { ForwardRules.forwardable(it, session::mediaOnDevice, ::isInviteText) }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.common_cancel),
            color = c.accent,
            fontSize = 15.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { selection.clear() }.padding(horizontal = 10.dp, vertical = 8.dp),
        )
        Text(
            stringResource(R.string.chat_selection_count, picked.size),
            color = c.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (canForward) {
            Icon(
                Icons.AutoMirrored.Filled.Forward,
                contentDescription = stringResource(R.string.chat_forward),
                tint = c.accent,
                modifier = Modifier.clip(CircleShape).clickable { selection.forwarding = picked }.padding(10.dp).size(24.dp),
            )
        }
        Icon(
            Icons.Filled.Delete,
            contentDescription = stringResource(R.string.chat_delete),
            tint = Color(0xFFE5484D),
            modifier = Modifier.clip(CircleShape).clickable(enabled = picked.isNotEmpty()) { selection.confirmDelete = true }.padding(10.dp).size(24.dp),
        )
    }
}

/**
 * Everything selection puts over the chat: Back leaves the mode, the delete
 * question, and the "Forward to…" screen. Drawn as a late child of the chat's
 * root Box, so the picker covers the whole chat and its Back answers first.
 *
 * Delete asks the single-message menu's question for the whole pick. "For
 * everyone" is offered only when it would be true of EVERY ticked message:
 * all mine, or a room I moderate. Saved Messages has one Delete, which
 * reaches my other devices like the single one does. A row of mine still
 * on its way out is left out of a retraction, which could overtake it (see
 * Session.deleteManyForEveryone); "for me" takes it like the single one does,
 * since nobody else is told anything.
 */
@Composable
internal fun ChatSelectionLayer(
    session: Session,
    selection: ChatSelection,
    messages: List<ChatMessage>,
    isSelf: Boolean,
    group: RcqGroup?,
    ownUin: Int,
    wireName: (ChatMessage) -> String,
) {
    // What left the thread leaves the pick; the last one gone ends the mode.
    LaunchedEffect(messages, selection.ids) {
        if (selection.active) selection.retain(messages.mapTo(HashSet()) { it.id })
    }
    BackHandler(enabled = selection.active) { selection.clear() }
    if (selection.confirmDelete && selection.active) {
        val picked = selection.picked(messages)
        val moderator = group != null && group.moderator(ownUin)
        val everyone = !isSelf && picked.isNotEmpty() && picked.all { ForwardRules.canRetract(it, isSelf, moderator) }
        val deleteLabel = stringResource(R.string.chat_delete)
        val allLabel = stringResource(R.string.chat_delete_all)
        val meLabel = stringResource(R.string.chat_delete_me)
        RcqAskSheet(
            onDismiss = { selection.confirmDelete = false },
            title = stringResource(R.string.chat_selection_delete_title),
            actions = buildList {
                if (isSelf) {
                    add(SheetAction(deleteLabel, destructive = true) {
                        session.deleteManyForEveryone(picked)
                        selection.clear()
                    })
                } else {
                    if (everyone) {
                        add(SheetAction(allLabel, destructive = true) {
                            session.deleteManyForEveryone(picked)
                            selection.clear()
                        })
                    }
                    add(SheetAction(meLabel, destructive = true) {
                        picked.forEach { session.deleteLocal(it) }
                        selection.clear()
                    })
                }
            },
        )
    }
    selection.forwarding?.let { items ->
        ForwardPickerLayer(
            session = session,
            items = items,
            isSelf = isSelf,
            ownUin = ownUin,
            wireName = wireName,
            onCancel = { selection.forwarding = null },
            onSent = {
                selection.forwarding = null
                selection.clear()
            },
        )
    }
}
