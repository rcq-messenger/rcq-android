package app.rcq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session

/** District chat for [bucket] — public, NOT end-to-end encrypted (pseudonymous
 *  via the Nearby display name). Joins on enter, leaves on dispose. */
@Composable
fun HoodChatScreen(session: Session, bucket: String, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val hood = session.hood
    val messages by hood.messages.collectAsState()
    val count by hood.bucketCount.collectAsState()
    val ownUin = session.uin
    var draft by remember { mutableStateOf("") }
    var showEmoji by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    DisposableEffect(bucket) {
        hood.joinChat(bucket)
        onDispose { hood.leaveChat() }
    }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Column(Modifier.fillMaxSize().background(c.bgPrimary).imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(c.bgSecondary.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = c.accent,
                modifier = Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onBack))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.hood_title), color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.hood_here, count), color = c.textSecondary, fontSize = 12.sp)
            }
        }
        // Unencrypted notice.
        Row(Modifier.fillMaxWidth().background(c.bgSecondary).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, tint = c.textSecondary, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.hood_unencrypted), color = c.textSecondary, fontSize = 11.sp)
        }

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages, key = { it.id }) { m ->
                val mine = ownUin != null && m.owner_uin == ownUin
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (mine) c.accent.copy(alpha = 0.14f) else c.bgSecondary).padding(10.dp),
                ) {
                    Text(m.nickname, color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    if (m.deleted) {
                        Text(stringResource(R.string.hood_deleted), color = c.textSecondary, fontSize = 15.sp)
                    } else {
                        EmoticonText(m.body, color = c.textPrimary, fontSize = 15.sp)
                    }
                    if (m.reactions.isNotEmpty()) {
                        Text(m.reactions.keys.joinToString(" "), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }

        if (showEmoji) EmoticonPanel(onPick = { draft = (draft + it).take(500) })
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Mood,
                stringResource(R.string.chat_emoticons),
                tint = if (showEmoji) c.accent else c.textSecondary,
                modifier = Modifier.size(28.dp).clip(CircleShape).clickable { showEmoji = !showEmoji },
            )
            Spacer(Modifier.width(8.dp))
            RcqField(
                value = draft, onValueChange = { draft = it.take(500) },
                placeholder = stringResource(R.string.hood_hint),
                modifier = Modifier.weight(1f), singleLine = false,
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Text),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.Send,
                stringResource(R.string.hood_send),
                tint = if (draft.isNotBlank()) c.accent else c.textSecondary,
                modifier = Modifier.size(28.dp).clip(CircleShape).clickable {
                    if (draft.isNotBlank()) { hood.sendMessage(draft); draft = "" }
                },
            )
        }
    }
}
