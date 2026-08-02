package app.rcq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import app.rcq.android.R
import app.rcq.android.RandomState
import app.rcq.android.Session
import app.rcq.android.model.ChatMessage
import app.rcq.android.model.DeliveryState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Stranger roulette. Drives the [Session.random] state machine: an intro →
 *  searching spinner → a time-boxed anonymous chat (countdown + skip + leave) →
 *  an ended card. Chat traffic rides the normal sealed path; the conversation
 *  is held in [Session.randomMessages] (ephemeral, never persisted). */
@Composable
internal fun RandomScreen(session: Session, onBack: () -> Unit, onEditProfile: () -> Unit = {}) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val state by session.random.collectAsState()

    // Leaving the screen ends queueing / the pair so the peer isn't stranded.
    // The 18+ gate used to live only on the server: you pressed Start, waited,
    // and got an error card back. The age is in your own profile, so the
    // check belongs here, before the button (tester report).
    var ownAge by remember { mutableStateOf<Int?>(null) }
    var ageLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        ownAge = (session.cachedProfile() ?: session.loadProfile())?.age
        ageLoaded = true
    }

    fun exit() {
        scope.launch { session.leaveRandom() }
        onBack()
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary).imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = c.accent,
                modifier = Modifier.size(26.dp).clip(CircleShape).clickable { exit() },
            )
            Text(
                stringResource(R.string.random_title), color = c.textPrimary, fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp),
            )
        }

        when (val s = state) {
            is RandomState.Matched -> MatchedChat(session, s)
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (s) {
                    is RandomState.Searching -> Searching(onCancel = { scope.launch { session.leaveRandom() } })
                    is RandomState.Ended -> Ended(reason = s.reason, onAgain = { scope.launch { session.startRandom() } }, onClose = { session.dismissRandom() })
                    is RandomState.Error -> ErrorCard(code = s.code, onClose = { session.dismissRandom() })
                    else -> Idle(
                        age = ownAge,
                        ageKnown = ageLoaded,
                        onStart = { scope.launch { session.startRandom() } },
                        onEditProfile = onEditProfile,
                    )
                }
            }
        }
    }
}

@Composable
private fun Idle(age: Int?, ageKnown: Boolean, onStart: () -> Unit, onEditProfile: () -> Unit) {
    val c = RcqTheme.colors
    // Same two refusals the server would give, decided before the tap.
    val blocked = when {
        !ageKnown -> null                       // still loading: don't accuse anyone yet
        age == null -> R.string.random_err_age_required
        age < 18 -> R.string.random_err_under_18
        else -> null
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        Icon(Icons.Filled.Casino, null, tint = c.accent, modifier = Modifier.size(64.dp))
        Text(stringResource(R.string.random_intro_title), color = c.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(stringResource(R.string.random_intro_body), color = c.textSecondary, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        blocked?.let {
            Text(stringResource(it), color = c.statusBusy, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            // Telling someone to go set their age and leaving them to find the
            // screen is half a message. iOS opens the editor from here and comes
            // back; do the same.
            if (age == null) {
                Text(
                    stringResource(R.string.random_set_age),
                    color = c.accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onEditProfile),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        CapsuleButton(
            stringResource(R.string.random_start),
            enabled = ageKnown && blocked == null,
            onClick = onStart,
        )
    }
}

@Composable
private fun Searching(onCancel: () -> Unit) {
    val c = RcqTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(color = c.accent)
        Text(stringResource(R.string.random_searching), color = c.textSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.common_cancel), color = c.accent, fontSize = 15.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onCancel).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Ended(reason: String, onAgain: () -> Unit, onClose: () -> Unit) {
    val c = RcqTheme.colors
    val reasonText = when (reason) {
        "expired" -> stringResource(R.string.random_end_expired)
        "peer_skipped" -> stringResource(R.string.random_end_skipped)
        "peer_disconnected" -> stringResource(R.string.random_end_disconnected)
        else -> stringResource(R.string.random_end_left)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        Text(stringResource(R.string.random_ended), color = c.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(reasonText, color = c.textSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        CapsuleButton(stringResource(R.string.random_again), onClick = onAgain)
        Text(
            stringResource(R.string.common_close), color = c.textSecondary, fontSize = 15.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ErrorCard(code: String, onClose: () -> Unit) {
    val c = RcqTheme.colors
    val msg = when (code) {
        "age_required" -> stringResource(R.string.random_err_age_required)
        "under_18" -> stringResource(R.string.random_err_under_18)
        "limit" -> stringResource(R.string.random_err_limit)
        else -> stringResource(R.string.random_err_other)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        Text(stringResource(R.string.random_cant_start), color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(msg, color = c.textSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        CapsuleButton(stringResource(R.string.common_ok), onClick = onClose)
    }
}

@Composable
private fun MatchedChat(session: Session, matched: RandomState.Matched) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val messages by session.randomMessages.collectAsState()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var added by remember(matched.peerUin) { mutableStateOf(false) }

    // Live countdown to expiry.
    var remaining by remember(matched.expiresAtMs) { mutableStateOf(matched.expiresAtMs - System.currentTimeMillis()) }
    LaunchedEffect(matched.expiresAtMs) {
        while (true) {
            remaining = matched.expiresAtMs - System.currentTimeMillis()
            if (remaining <= 0) break
            delay(1000)
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        // Peer header + countdown.
        Row(
            Modifier.fillMaxWidth().background(c.bgSecondary.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                Text(matched.peerNickname.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(matched.peerNickname, color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.random_stranger), color = c.textSecondary, fontSize = 12.sp)
            }
            // Add the stranger as a contact (a random peer is NOT a contact by default).
            Icon(
                if (added) Icons.Filled.Check else Icons.Filled.PersonAdd,
                stringResource(R.string.random_add_contact),
                tint = if (added) c.textSecondary else c.accent,
                modifier = Modifier.size(24.dp).clip(CircleShape).clickable(enabled = !added) {
                    added = true
                    scope.launch {
                        runCatching { session.addContact(matched.peerUin) }
                        android.widget.Toast.makeText(context, context.getString(R.string.random_add_sent), android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
            )
            Spacer(Modifier.width(12.dp))
            Text(formatRemaining(remaining), color = if (remaining < 60_000) Color(0xFFE5484D) else c.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(messages, key = { it.id }) { m -> RandomBubble(m) }
        }

        // Composer + skip.
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.SkipNext, stringResource(R.string.random_skip), tint = c.accent,
                modifier = Modifier.size(30.dp).clip(CircleShape).clickable { scope.launch { session.skipRandom() } },
            )
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(c.bgSecondary).padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (draft.isEmpty()) Text(stringResource(R.string.chat_input_hint), color = c.textSecondary, fontSize = 15.sp)
                BasicTextField(
                    value = draft, onValueChange = { draft = it },
                    textStyle = TextStyle(color = c.textPrimary, fontSize = 15.sp),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(6.dp))
            val canSend = draft.isNotBlank()
            Icon(
                Icons.AutoMirrored.Filled.Send, stringResource(R.string.chat_send),
                tint = if (canSend) c.accent else c.textSecondary,
                modifier = Modifier.size(30.dp).clip(CircleShape).clickable(enabled = canSend) {
                    val body = draft.trim(); draft = ""
                    scope.launch { session.sendRandomText(body) }
                },
            )
        }
    }
}

@Composable
private fun RandomBubble(m: ChatMessage) {
    val c = RcqTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.clip(RoundedCornerShape(14.dp))
                .background(if (m.fromMe) c.bubbleSelf else c.bubbleOther)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                m.body,
                color = if (m.fromMe && m.state == DeliveryState.FAILED) Color(0xFFE5484D) else c.textPrimary,
                fontSize = 15.sp,
            )
        }
    }
}

/** mm:ss from a millisecond remainder (clamped at 0). */
private fun formatRemaining(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
