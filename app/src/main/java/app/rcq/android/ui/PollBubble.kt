package app.rcq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.model.ChatMessage
import app.rcq.android.model.PollContent
import app.rcq.android.net.RcqApi
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A group-poll bubble. The question + options come from the encrypted
 *  envelope (persisted in [ChatMessage.body] as JSON); the live tallies are
 *  fetched fresh from /polls/{id} (never persisted). Tap an option to vote
 *  (toggles); the creator can close the poll. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PollBubble(session: Session, m: ChatMessage, onLongPress: () -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val content = remember(m.id, m.body) { PollContent.fromJson(m.body) }
    if (content == null) {
        Text(m.body, color = c.textPrimary, fontSize = 15.sp)
        return
    }

    var poll by remember(m.id) { mutableStateOf<RcqApi.PollOut?>(null) }
    LaunchedEffect(content.pollId) { poll = session.loadPoll(content.pollId) }

    val total = poll?.total_votes ?: 0
    val myVotes = poll?.my_votes ?: emptyList()
    val closed = poll?.closed_at != null
    val isCreator = poll != null && poll?.creator_uin == session.uin
    var showVoters by remember(m.id) { mutableStateOf(false) }

    Column(
        Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (m.fromMe) c.bubbleSelf else c.bubbleOther)
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(content.question, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        val mode = if (content.singleChoice) stringResource(R.string.poll_single_badge) else stringResource(R.string.poll_multi_badge)
        val sub = if (content.anonymous) "$mode · ${stringResource(R.string.poll_anon_badge)}" else mode
        Text(sub, color = c.textSecondary, fontSize = 11.sp)

        content.options.forEachIndexed { i, label ->
            val count = poll?.tallies?.firstOrNull { it.option_index == i }?.count ?: 0
            val frac = if (total > 0) count.toFloat() / total else 0f
            val mine = myVotes.contains(i)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.bgSecondary)
                    .clickable(enabled = !closed) {
                        scope.launch { session.votePoll(content.pollId, i)?.let { poll = it } }
                    },
            ) {
                // Result bar (behind the label), accent-tinted, width = vote share.
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0f, 1f))
                        .background(c.accent.copy(alpha = if (mine) 0.40f else 0.20f)),
                )
                Row(
                    Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (mine) {
                        Icon(Icons.Filled.Check, null, tint = c.accent, modifier = Modifier.width(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(label, color = c.textPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    val pct = if (total > 0) (frac * 100f).roundToInt() else 0
                    Text("$count · $pct%", color = c.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.poll_votes, total), color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            // Non-anonymous polls: surface who voted for what (backend returns
            // voter_uins for these; iOS already shows them).
            if (!content.anonymous && total > 0) {
                Text(
                    stringResource(R.string.poll_who_voted), color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { showVoters = true }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            when {
                closed -> Text(stringResource(R.string.poll_closed), color = c.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                isCreator -> Text(
                    stringResource(R.string.poll_close), color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                        scope.launch { session.closePoll(content.pollId)?.let { poll = it } }
                    }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }

    if (showVoters) {
        val g = m.groupId?.let { session.group(it) }
        fun nameOf(uin: Int): String = g?.members?.firstOrNull { it.uin == uin }?.nickname ?: "#$uin"
        AlertDialog(
            onDismissRequest = { showVoters = false },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(R.string.poll_voters_title), color = c.textPrimary) },
            text = {
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content.options.forEachIndexed { i, label ->
                        val voters = poll?.tallies?.firstOrNull { it.option_index == i }?.voter_uins ?: emptyList()
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("$label — ${voters.size}", color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            if (voters.isEmpty()) {
                                Text(stringResource(R.string.poll_no_voters), color = c.textSecondary, fontSize = 13.sp)
                            } else {
                                voters.forEach { uin -> Text(nameOf(uin), color = c.textSecondary, fontSize = 13.sp) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showVoters = false }) { Text(stringResource(R.string.common_close), color = c.accent) } },
        )
    }
}

/** Compose a new group poll: a question + 2–10 options + single/multi-choice
 *  and anonymous toggles. Calls [onCreate] with the trimmed, non-blank values. */
@Composable
internal fun PollComposerDialog(
    onDismiss: () -> Unit,
    onCreate: (question: String, options: List<String>, singleChoice: Boolean, anonymous: Boolean) -> Unit,
) {
    val c = RcqTheme.colors
    var question by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(listOf("", "")) }
    var single by remember { mutableStateOf(true) }
    var anon by remember { mutableStateOf(false) }

    val clean = options.map { it.trim() }.filter { it.isNotEmpty() }
    val valid = question.trim().isNotEmpty() && clean.size >= 2

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgSecondary,
        title = { Text(stringResource(R.string.poll_create), color = c.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = question, onValueChange = { question = it.take(280) },
                    placeholder = { Text(stringResource(R.string.poll_question), color = c.textSecondary) },
                    modifier = Modifier.fillMaxWidth(), singleLine = false,
                )
                options.forEachIndexed { i, opt ->
                    OutlinedTextField(
                        value = opt,
                        onValueChange = { v -> options = options.toMutableList().also { it[i] = v.take(120) } },
                        placeholder = { Text(stringResource(R.string.poll_option_hint, i + 1), color = c.textSecondary) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                }
                if (options.size < 10) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { options = options + "" }.padding(vertical = 4.dp),
                    ) {
                        Icon(Icons.Filled.Add, null, tint = c.accent, modifier = Modifier.width(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.poll_add_option), color = c.accent, fontSize = 14.sp)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { single = !single },
                ) {
                    Checkbox(checked = single, onCheckedChange = { single = it })
                    Text(stringResource(R.string.poll_single), color = c.textPrimary, fontSize = 14.sp)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { anon = !anon },
                ) {
                    Checkbox(checked = anon, onCheckedChange = { anon = it })
                    Text(stringResource(R.string.poll_anonymous), color = c.textPrimary, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onCreate(question.trim(), clean, single, anon) }) {
                Text(stringResource(R.string.poll_post), color = if (valid) c.accent else c.textSecondary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
    )
}
