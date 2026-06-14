package app.rcq.android.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.model.OutgoingRequest
import kotlinx.coroutines.launch

/**
 * "Sent requests" — contact requests WE sent that are still pending, or were
 * declined by the recipient. Declined ones surface here because there's no
 * push to tell the sender the outcome (sealed sender / no FCM). Each row lets
 * the user revoke a pending request or dismiss a declined one. Reached from
 * the Home overflow menu.
 */
@Composable
fun OutgoingRequestsScreen(session: Session, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val outgoing by session.outgoing.collectAsState()

    LaunchedEffect(Unit) { session.loadOutgoing() }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.common_back),
                tint = c.accent,
                modifier = Modifier.size(26.dp).clickable(onClick = onBack),
            )
            Text(
                stringResource(R.string.outgoing_title),
                color = c.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        if (outgoing.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(stringResource(R.string.outgoing_empty), color = c.textSecondary, fontSize = 14.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                item { Box(Modifier.size(4.dp)) }
                items(outgoing, key = { it.toUin }) { req ->
                    OutgoingRow(req) { scope.launch { session.cancelOutgoing(req.toUin) } }
                }
            }
        }
    }
}

@Composable
private fun OutgoingRow(req: OutgoingRequest, onCancel: () -> Unit) {
    val c = RcqTheme.colors
    val declined = req.state == "declined"
    Row(
        Modifier.fillMaxWidth().background(c.bgPrimary).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            if (declined) Icon(Icons.Filled.Block, null, tint = c.statusBusy, modifier = Modifier.size(22.dp))
            else Icon(Icons.Outlined.Schedule, null, tint = c.accent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(req.toNickname, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("#${req.toUin}", color = c.textMono, fontSize = 12.sp)
                Text(
                    stringResource(if (declined) R.string.outgoing_state_declined else R.string.outgoing_state_pending),
                    color = if (declined) c.statusBusy else c.textSecondary,
                    fontSize = 12.sp,
                )
            }
        }
        Text(
            stringResource(if (declined) R.string.outgoing_dismiss else R.string.outgoing_cancel),
            color = if (declined) c.textSecondary else c.statusBusy,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onCancel).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
