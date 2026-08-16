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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.Delete
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
 */
@Composable
fun MyReportsScreen(session: Session, onBack: () -> Unit) {
    val c = RcqTheme.colors
    var items by remember { mutableStateOf<List<RcqApi.MyReport>?>(null) }
    var loading by remember { mutableStateOf(true) }
    // The server keeps an OPEN report about another user until a verdict, so a
    // delete can legitimately be refused; say so instead of failing silently.
    var refused by remember { mutableStateOf(false) }
    // Which ticket has its box open, and what is typed in it. One at a time:
    // this is a list of tickets, not a chat list.
    var replyTo by remember { mutableStateOf<Int?>(null) }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val closedMsg = stringResource(R.string.myreports_closed)
    val failedMsg = stringResource(R.string.myreports_send_error)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        items = session.loadMyReports()
        loading = false
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
                        // Only on the card being written in: the same string
                        // under every ticket would read as all of them failing.
                        error = sendError.takeIf { replyTo == r.id },
                        onDraft = { draft = it },
                        onStartReply = {
                            replyTo = r.id
                            draft = ""
                            sendError = null
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
                        onDelete = {
                            scope.launch {
                                if (session.deleteMyReport(r.id)) {
                                    items = items?.filterNot { it.id == r.id }
                                    if (replyTo == r.id) {
                                        replyTo = null
                                        draft = ""
                                        sendError = null
                                    }
                                } else {
                                    refused = true
                                }
                            }
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
    error: String?,
    onDraft: (String) -> Unit,
    onStartReply: () -> Unit,
    onCancelReply: () -> Unit,
    onSend: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = RcqTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bgSecondary).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // "Waiting" is about waiting for an ANSWER, so an answered report
            // must stop saying it even while it is still open on our side: the
            // operator replies first and picks a verdict later, by design. The
            // reporter read the unchanged label as us ignoring him (#417).
            val answered = !report.reply.isNullOrBlank() || report.thread.any { it.from_admin }
            Text(
                if (answered && report.status == "open") stringResource(R.string.myreports_status_answered)
                else statusLabel(report.status),
                color = if (report.status == "open" && !answered) c.accent else c.textSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                formatReportDate(report.created_at)?.let {
                    Text(it, color = c.textSecondary, fontSize = 12.sp)
                }
                Icon(
                    Icons.Outlined.Delete,
                    stringResource(R.string.myreports_delete),
                    tint = c.textSecondary,
                    modifier = Modifier.size(18.dp).clickable(onClick = onDelete),
                )
            }
        }

        // Long-press copies the text. Asked for in a report ("предлагаю добавить
        // функцию скопировать в буфер обмена текст обращения аналогично как
        // сделаны новости"): people re-send their own wording when following up,
        // and re-typing it off a screen is the kind of small tax that stops
        // somebody bothering.
        if (!report.reason.isNullOrBlank()) {
            val context = LocalContext.current
            val copied = stringResource(R.string.myreports_copied)
            Text(
                report.reason,
                color = c.textPrimary,
                fontSize = 15.sp,
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("RCQ", report.reason))
                        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    },
                ),
            )
        }

        // The exchange, oldest first. `thread` is what a current island sends;
        // an older one sends only the single `reply`, and that is the fallback
        // below — the screen must not go blank against an island that has not
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
        if (report.status == "open") {
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
            } else {
                CardButton(
                    label = stringResource(R.string.myreports_reply),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onStartReply,
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
 *  never both). Long-press copies, same as the report text above. */
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
        Text(label, color = labelColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
            body,
            color = c.textPrimary,
            fontSize = 14.sp,
            modifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("RCQ", body))
                    Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show()
                },
            ),
        )
    }
}

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

@Composable
private fun statusLabel(status: String?): String = stringResource(
    when (status) {
        "resolved" -> R.string.myreports_status_resolved
        "dismissed" -> R.string.myreports_status_dismissed
        "duplicate" -> R.string.myreports_status_duplicate
        else -> R.string.myreports_status_open
    },
)

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
