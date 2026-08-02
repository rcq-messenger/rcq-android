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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
 */
@Composable
fun MyReportsScreen(session: Session, onBack: () -> Unit) {
    val c = RcqTheme.colors
    var items by remember { mutableStateOf<List<RcqApi.MyReport>?>(null) }
    var loading by remember { mutableStateOf(true) }
    // The server keeps an OPEN report about another user until a verdict, so a
    // delete can legitimately be refused; say so instead of failing silently.
    var refused by remember { mutableStateOf(false) }
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Box(Modifier.size(4.dp)) }
                items(list, key = { it.id }) { r ->
                    ReportCard(r, onDelete = {
                        scope.launch {
                            if (session.deleteMyReport(r.id)) {
                                items = items?.filterNot { it.id == r.id }
                            } else {
                                refused = true
                            }
                        }
                    })
                }
                item { Box(Modifier.size(8.dp)) }
            }
        }
    }
}

@Composable
private fun ReportCard(report: RcqApi.MyReport, onDelete: () -> Unit) {
    val c = RcqTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bgSecondary).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                statusLabel(report.status),
                color = if (report.status == "open") c.accent else c.textSecondary,
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

        if (!report.reason.isNullOrBlank()) {
            Text(report.reason, color = c.textPrimary, fontSize = 15.sp)
        }

        // The answer is the whole reason this screen exists, so it gets its own
        // block rather than a line of small print under the report.
        if (!report.reply.isNullOrBlank()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bgPrimary).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.myreports_answer),
                    color = c.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(report.reply, color = c.textPrimary, fontSize = 14.sp)
            }
        }
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
