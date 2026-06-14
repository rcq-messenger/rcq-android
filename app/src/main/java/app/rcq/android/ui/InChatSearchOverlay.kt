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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-chat message search — the Android port of the iOS `InChatSearchOverlay`.
 * Scoped to the current thread's messages: text bubbles and media captions
 * only (system/control rows are skipped), sorted newest-first, capped at 100.
 * Tapping a hit closes the overlay and asks the host [ChatScreen] to scroll
 * to that message. Fully local — no network.
 */
@Composable
internal fun InChatSearchOverlay(
    messages: List<ChatMessage>,
    onClose: () -> Unit,
    onSelect: (ChatMessage) -> Unit,
) {
    val c = RcqTheme.colors
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val dayFmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    val norm = query.trim().lowercase()
    val hits = remember(norm, messages) {
        if (norm.isEmpty()) emptyList()
        else messages
            .filter { m ->
                when (m.kind) {
                    "text", "photo", "video" -> m.body.lowercase().contains(norm)
                    else -> false
                }
            }
            .sortedByDescending { it.sentAt }
            .take(100)
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text(stringResource(R.string.chat_search_hint), color = c.textSecondary) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = c.textSecondary) },
                singleLine = true,
            )
            Text(
                stringResource(R.string.common_cancel),
                color = c.accent,
                modifier = Modifier.clickable(onClick = onClose).padding(12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))

        when {
            norm.isEmpty() -> Centered(R.string.chat_search_idle, c.textSecondary)
            hits.isEmpty() -> Centered(R.string.search_no_match, c.textSecondary)
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(hits, key = { it.id }) { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(m) }.padding(horizontal = 8.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(iconFor(m.kind), null, tint = c.accent, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.body, color = c.textPrimary, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(dayFmt.format(Date(m.sentAt)), color = c.textMono, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

private fun iconFor(kind: String): ImageVector = when (kind) {
    "photo" -> Icons.Filled.Image
    "video" -> Icons.Filled.Movie
    else -> Icons.AutoMirrored.Filled.Chat
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Centered(textRes: Int, color: androidx.compose.ui.graphics.Color) {
    Column(
        Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Search, null, tint = color, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(textRes), color = color, fontSize = 13.sp, fontWeight = FontWeight.Normal)
    }
}
