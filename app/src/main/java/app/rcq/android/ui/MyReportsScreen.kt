package app.rcq.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.net.RcqApi

/**
 * The reports this account has filed, and whatever was written back.
 *
 * Filing a report used to be shouting into a well: the queue is admin-side and
 * nothing ever came back, so a person who spent an hour writing careful
 * feedback could not tell whether it had been read. The answer cannot arrive as
 * a chat message either, because chats are sealed on the sending device and the
 * server holds no keys, so it is fetched here on our own session
 * (GET /reports/mine) and rendered as what it is: a note from the operators,
 * not an encrypted message from a person.
 *
 * Since 16.08 it is a conversation and not a box with a lid: an operator asks
 * "which version?", and the answer belongs on the same ticket. Before that the
 * only way to say anything back was to file a SECOND report, which is why the
 * queue held the same issue three times over from one person.
 *
 * Since 23.08 a ticket also carries its NUMBER, and the number is the whole
 * point: the founder answers people by quoting it, so the reporter has to be
 * looking at the same integer he is. Alongside it, the three things a person
 * expects to be able to do with their own text and could not: copy it, fix the
 * typo in it while nobody has answered yet, and take a stale one off the list.
 */
@Composable
fun MyReportsScreen(session: Session, onBack: () -> Unit) {
    val c = RcqTheme.colors
    var items by remember { mutableStateOf<List<RcqApi.MyReport>?>(null) }
    var loading by remember { mutableStateOf(true) }
    // The server keeps an OPEN report about another user until a verdict, so
    // taking it off the list can legitimately be refused; say so instead of
    // failing silently.
    var refused by remember { mutableStateOf(false) }
    // Which ticket has its box open, and what is typed in it. One at a time:
    // this is a list of tickets, not a chat list.
    var replyTo by remember { mutableStateOf<Int?>(null) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    // Rewriting one's own question. Same one-at-a-time rule, and mutually
    // exclusive with writing back: they are two different things to do with
    // the same ticket and both boxes open in the same place.
    var editingId by remember { mutableStateOf<Int?>(null) }
    var editDraft by remember { mutableStateOf("") }
    var savingEdit by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<String?>(null) }
    // The ticket a "remove from list" is being confirmed for. It is a HIDE
    // server-side, so the sheet says that and nothing stronger.
    var confirmRemove by remember { mutableStateOf<RcqApi.MyReport?>(null) }
    val closedMsg = stringResource(R.string.myreports_closed)
    val failedMsg = stringResource(R.string.myreports_send_error)
    val editLockedMsg = stringResource(R.string.myreports_edit_locked)
    val editNotEditableMsg = stringResource(R.string.myreports_edit_not_editable)
    val editFailedMsg = stringResource(R.string.myreports_edit_error)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        items = session.loadMyReports()
        loading = false
    }

    confirmRemove?.let { target ->
        RcqAskSheet(
            onDismiss = { confirmRemove = null },
            title = stringResource(R.string.myreports_remove_title),
            body = stringResource(R.string.myreports_remove_body),
            actions = listOf(
                SheetAction(
                    label = stringResource(R.string.myreports_delete),
                    destructive = true,
                    onClick = {
                        confirmRemove = null
                        scope.launch {
                            if (session.deleteMyReport(target.id)) {
                                items = items?.filterNot { it.id == target.id }
                                if (replyTo == target.id) {
                                    replyTo = null
                                    draft = ""
                                    sendError = null
                                }
                                if (editingId == target.id) {
                                    editingId = null
                                    editDraft = ""
                                    editError = null
                                }
                            } else {
                                refused = true
                            }
                        }
                    },
                ),
            ),
        )
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.common_back),
                tint = c.accent,
                modifier = Modifier.size(26.dp).clickable(onClick = onBack),
            )
            Text(
                stringResource(R.string.myreports_title),
                color = c.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        if (refused) {
            Text(
                stringResource(R.string.myreports_delete_refused),
                color = c.statusBusy,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val list = items.orEmpty()
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                Text(
                    stringResource(R.string.myreports_empty),
                    color = c.textSecondary,
                    fontSize = 14.sp,
                )
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Box(Modifier.size(4.dp)) }
                itemsIndexed(list, key = { _, r -> r.id }) { index, r ->
                    ReportCard(
                        report = r,
                        composing = replyTo == r.id,
                        draft = draft,
                        sending = sending,
                        editing = editingId == r.id,
                        editDraft = editDraft,
                        savingEdit = savingEdit,
                        // Only on the card being written in: the same string
                        // under every ticket would read as all of them failing.
                        // Two slots, because the two boxes are in two different
                        // places on the card and an error a screen away from
                        // the field it is about is an error nobody connects.
                        error = sendError.takeIf { replyTo == r.id },
                        editError = editError.takeIf { editingId == r.id },
                        onDraft = { draft = it },
                        onStartReply = {
                            replyTo = r.id
                            draft = ""
                            sendError = null
                            editingId = null
                            editDraft = ""
                            editError = null
                            // The box opens at the BOTTOM of a card that may
                            // already be a screen tall, so without this the
                            // person taps "write back" and nothing visibly
                            // happens. +1 for the spacer item above the list.
                            scope.launch { listState.animateScrollToItem(index + 1) }
                        },
                        onCancelReply = {
                            replyTo = null
                            draft = ""
                            sendError = null
                        },
                        onSend = {
                            val text = draft.trim()
                            if (text.isNotEmpty() && !sending) scope.launch {
                                sending = true
                                sendError = null
                                when (val res = session.addToMyReport(r.id, text)) {
                                    is Session.ReportReply.Sent -> {
                                        // Straight into the list, so the turn
                                        // appears where it was typed instead of
                                        // after a refresh nobody triggers.
                                        items = items?.map {
                                            if (it.id == r.id) it.copy(thread = it.thread + res.turn) else it
                                        }
                                        draft = ""
                                        replyTo = null
                                    }
                                    Session.ReportReply.Closed -> sendError = closedMsg
                                    Session.ReportReply.Failed -> sendError = failedMsg
                                }
                                sending = false
                            }
                        },
                        onStartEdit = {
                            editingId = r.id
                            // Prefilled with what was sent: this is a fix for a
                            // typo or a missing version number, not a rewrite
                            // from a blank box.
                            editDraft = r.reason.orEmpty()
                            editError = null
                            replyTo = null
                            draft = ""
                            sendError = null
                            scope.launch { listState.animateScrollToItem(index + 1) }
                        },
                        onEditDraft = { editDraft = clampToCodePoints(it, RcqApi.REPORT_REASON_MAX) },
                        onCancelEdit = {
                            editingId = null
                            editDraft = ""
                            editError = null
                        },
                        onSaveEdit = {
                            val text = editDraft.trim()
                            if (text.isNotEmpty() && !savingEdit) scope.launch {
                                savingEdit = true
                                editError = null
                                when (val res = session.editMyReport(r.id, text)) {
                                    is Session.ReportEdit.Saved -> {
                                        // Replace the row with what the server
                                        // now holds, so `edited_at` and the
                                        // frozen-once-answered state come from
                                        // it rather than from a guess here.
                                        items = items?.map { if (it.id == r.id) res.report else it }
                                        editingId = null
                                        editDraft = ""
                                    }
                                    Session.ReportEdit.Locked -> {
                                        editError = editLockedMsg
                                        // The refusal means an answer landed
                                        // since this list was fetched, so pull
                                        // it in: the answer IS the explanation,
                                        // and without it the card still shows
                                        // "waiting" next to "already answered".
                                        // The box stays open, so nothing typed
                                        // is thrown away without being read.
                                        items = session.loadMyReports() ?: items
                                    }
                                    Session.ReportEdit.NotEditable -> editError = editNotEditableMsg
                                    Session.ReportEdit.Failed -> editError = editFailedMsg
                                }
                                savingEdit = false
                            }
                        },
                        onRemove = {
                            refused = false
                            confirmRemove = r
                        },
                    )
                }
                item { Box(Modifier.size(8.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReportCard(
    report: RcqApi.MyReport,
    composing: Boolean,
    draft: String,
    sending: Boolean,
    editing: Boolean,
    editDraft: String,
    savingEdit: Boolean,
    error: String?,
    editError: String?,
    onDraft: (String) -> Unit,
    onStartReply: () -> Unit,
    onCancelReply: () -> Unit,
    onSend: () -> Unit,
    onStartEdit: () -> Unit,
    onEditDraft: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val copiedMsg = stringResource(R.string.myreports_copied)
    val reason = report.reason
    // "Waiting" is about waiting for an ANSWER, so an answered report must stop
    // saying it even while it is still open on our side: the operator replies
    // first and picks a verdict later, by design. The reporter read the
    // unchanged label as us ignoring him (#417).
    val answered = !report.reply.isNullOrBlank() || report.thread.any { it.from_admin }
    // What the server will actually accept a PATCH for, decided here so a
    // pencil is never offered on a ticket that can only refuse it:
    //  * `number` is the capability probe. It and PATCH /reports/mine/{id}
    //    shipped in the same server release, so an island that sends no number
    //    has no edit endpoint either and must show no pencil at all (an old
    //    island has to keep working, not sprout a button that 404s);
    //  * open, and nobody has said anything back: once an operator has replied,
    //    the text he replied to is frozen or the thread reads as an answer to a
    //    question nobody asked;
    //  * never a crash dump. The [CRASH] marker is what keeps auto-submitted
    //    crashes out of the Hall of Fame tally, so it is not a thing a person
    //    may put into, or take out of, their own text.
    val canEdit = report.number != null &&
        report.status == "open" &&
        !answered &&
        !reason.orEmpty().contains(CRASH_MARKER)

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bgSecondary).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (answered && report.status == "open") stringResource(R.string.myreports_status_answered)
                else statusLabel(report.status),
                color = if (report.status == "open" && !answered) c.accent else c.textSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // The number the founder answers by. Null only on an island
                // that predates it, where there is nothing honest to show.
                report.number?.let {
                    Text(
                        stringResource(R.string.myreports_number, it),
                        color = c.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                formatReportDate(report.created_at)?.let {
                    Text(it, color = c.textSecondary, fontSize = 12.sp)
                }
            }
        }

        if (editing) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            RcqField(
                value = editDraft,
                onValueChange = onEditDraft,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = false,
                minLines = 3,
                enabled = !savingEdit,
                // Only near the ceiling. A counter over every draft is noise;
                // a field that silently stops accepting characters is worse.
                supportingText = editDraft.codePointCount(0, editDraft.length)
                    .takeIf { it > RcqApi.REPORT_REASON_MAX - 100 }
                    ?.let { "$it / ${RcqApi.REPORT_REASON_MAX}" },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardButton(
                    label = stringResource(R.string.common_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = onCancelEdit,
                )
                CardButton(
                    label = stringResource(R.string.common_save),
                    modifier = Modifier.weight(1f),
                    filled = true,
                    enabled = editDraft.isNotBlank() && !savingEdit,
                    onClick = onSaveEdit,
                )
            }
            // Right under the box it is about. "Already answered" and "cannot
            // be edited" are not "try again": each says what happened and each
            // ends the attempt.
            if (editError != null) {
                Text(editError, color = c.statusBusy, fontSize = 12.sp)
            }
        } else if (!reason.isNullOrBlank()) {
            // The copy is an ICON now, not only a long press. The long press
            // was asked for in a report ("предлагаю добавить функцию
            // скопировать в буфер обмена текст обращения аналогично как сделаны
            // новости") and shipped, but a gesture nothing on screen mentions
            // is a feature only the person who built it knows about. Both work.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    reason,
                    color = c.textPrimary,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f).combinedClickable(
                        onClick = {},
                        onLongClick = { copyToClipboard(context, reason, copiedMsg) },
                    ),
                )
                IconAction(
                    icon = Icons.Outlined.ContentCopy,
                    label = stringResource(R.string.common_copy),
                    tint = c.textSecondary,
                    onClick = { copyToClipboard(context, reason, copiedMsg) },
                )
            }
            if (!report.edited_at.isNullOrBlank()) {
                Text(stringResource(R.string.myreports_edited), color = c.textSecondary, fontSize = 11.sp)
            }
        }

        // The exchange, oldest first. `thread` is what a current island sends;
        // an older one sends only the single `reply`, and that is the fallback
        // below: the screen must not go blank against an island that has not
        // updated. The answer is the whole reason this screen exists, so it
        // gets its own block rather than a line of small print.
        if (report.thread.isNotEmpty()) {
            report.thread.forEach { turn ->
                TurnBlock(
                    label = stringResource(
                        if (turn.from_admin) R.string.myreports_answer else R.string.myreports_you,
                    ),
                    labelColor = if (turn.from_admin) c.accent else c.textSecondary,
                    body = turn.body.orEmpty(),
                    fromAdmin = turn.from_admin,
                )
            }
        } else if (!report.reply.isNullOrBlank()) {
            TurnBlock(
                label = stringResource(R.string.myreports_answer),
                labelColor = c.accent,
                body = report.reply,
                fromAdmin = true,
            )
        }

        // Writing back only makes sense while the ticket is open. A closed one
        // keeps its whole exchange readable.
        if (composing) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            RcqField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = stringResource(R.string.myreports_reply_placeholder),
                singleLine = false,
                minLines = 3,
                enabled = !sending,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardButton(
                    label = stringResource(R.string.common_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = onCancelReply,
                )
                CardButton(
                    label = stringResource(R.string.myreports_reply_send),
                    modifier = Modifier.weight(1f),
                    filled = true,
                    enabled = draft.isNotBlank() && !sending,
                    onClick = onSend,
                )
            }
        } else if (!editing) {
            // The card's own actions, out of the way of whatever is being
            // typed: while a box is open the only buttons are that box's.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (report.status == "open") {
                    CardButton(
                        label = stringResource(R.string.myreports_reply),
                        modifier = Modifier.weight(1f),
                        onClick = onStartReply,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (canEdit) {
                    IconAction(
                        icon = Icons.Outlined.Edit,
                        label = stringResource(R.string.myreports_edit),
                        tint = c.textSecondary,
                        onClick = onStartEdit,
                    )
                }
                IconAction(
                    icon = Icons.Outlined.Delete,
                    label = stringResource(R.string.myreports_delete),
                    tint = c.textSecondary,
                    onClick = onRemove,
                )
            }
        }

        // A closed ticket is not a failure, it is an answer, so it says so here
        // rather than as a generic "could not send".
        if (error != null) {
            Text(error, color = c.statusBusy, fontSize = 12.sp)
        }
    }
}

/** One turn of the exchange. The operator's side sits on the screen background
 *  and the reporter's on a wash of the text colour, so the two read apart at a
 *  glance without either growing a border (house rule: a fill or a border,
 *  never both). Copy is both an icon and a long press, same as the question
 *  above: the answer is the half people quote back at us. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TurnBlock(label: String, labelColor: Color, body: String, fromAdmin: Boolean) {
    val c = RcqTheme.colors
    val ctx = LocalContext.current
    val copied = stringResource(R.string.myreports_copied)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (fromAdmin) c.bgPrimary else c.textPrimary.copy(alpha = 0.06f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = labelColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            IconAction(
                icon = Icons.Outlined.ContentCopy,
                label = stringResource(R.string.common_copy),
                tint = c.textSecondary,
                onClick = { copyToClipboard(ctx, body, copied) },
            )
        }
        Text(
            body,
            color = c.textPrimary,
            fontSize = 14.sp,
            modifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = { copyToClipboard(ctx, body, copied) },
            ),
        )
    }
}

/** A small tappable glyph. The box is bigger than the glyph on purpose: an
 *  18dp icon is an 18dp target, and these sit next to each other. */
@Composable
private fun IconAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Icon(
        icon,
        label,
        tint = tint,
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
    )
}

private fun copyToClipboard(context: Context, text: String, toast: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("RCQ", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

/** ⚠ Clamp by CODE POINTS, not by `String.length`. The island validates with
 *  pydantic's max_length, which counts Python characters: one emoji is 1 there
 *  and 2 in a Kotlin String, so a length-based clamp lets a draft through that
 *  comes back 422 with nothing on screen explaining why. Same trap the pinned
 *  message fell into on all three clients. */
private fun clampToCodePoints(s: String, max: Int): String {
    if (s.length <= max) return s
    val points = s.codePointCount(0, s.length)
    if (points <= max) return s
    return s.substring(0, s.offsetByCodePoints(0, max))
}

/** The marker a client puts on an auto-submitted crash dump. `hof_stats` reads
 *  it to keep crashes out of a contributor's tally, which is exactly why the
 *  island refuses to let a person type it in or edit it out by hand. */
private const val CRASH_MARKER = "[CRASH]"

@Composable
private fun statusLabel(status: String?): String = stringResource(
    when (status) {
        "resolved" -> R.string.myreports_status_resolved
        "dismissed" -> R.string.myreports_status_dismissed
        "duplicate" -> R.string.myreports_status_duplicate
        else -> R.string.myreports_status_open
    },
)

/** A button sized for the inside of a card: the app's CapsuleButton is a
 *  full-width primary action and swallows a ticket card whole. */
@Composable
private fun CardButton(
    label: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = RcqTheme.colors
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (filled && enabled) c.accent else c.bgPrimary)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                filled && enabled -> Color.White
                enabled -> c.accent
                else -> c.textSecondary
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Best-effort ISO-8601 to a localized date; falls back to the date part. */
private fun formatReportDate(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val fmt = java.time.format.DateTimeFormatter
        .ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
        .withZone(java.time.ZoneId.systemDefault())
    return runCatching { fmt.format(java.time.OffsetDateTime.parse(iso).toInstant()) }
        .recoverCatching { fmt.format(java.time.Instant.parse(iso)) }
        .getOrDefault(iso.take(10))
}
