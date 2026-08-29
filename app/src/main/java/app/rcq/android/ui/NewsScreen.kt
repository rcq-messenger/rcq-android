package app.rcq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.net.RcqApi

/**
 * Read-only admin-posted news feed (GET /news), iOS NewsSheet parity. Phase 1
 * renders the text posts (author + date + body) with a media indicator;
 * rendering the attachment images is a follow-up.
 */
@Composable
fun NewsScreen(session: Session, onBack: () -> Unit) {
    val c = RcqTheme.colors
    var feed by remember { mutableStateOf<RcqApi.NewsFeed?>(null) }
    var loading by remember { mutableStateOf(true) }

    // Keyed on the news_posted tick (A4), not Unit: a post published while
    // this screen is open re-fetches in place. Re-marking seen keeps the home
    // dot dark for what the reader is literally looking at (markNewsSeen's
    // monotonic guard makes the repeat harmless).
    val newsTick by session.newsFeedChanged.collectAsState()
    LaunchedEffect(newsTick) {
        feed = session.loadNews()
        // Seen the moment the screen shows them, which is what clears the red
        // dot on the home menu.
        feed?.let { session.markNewsSeen(it.latest_id) }
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
                stringResource(R.string.news_title),
                color = c.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        val items = feed?.items.orEmpty()
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(stringResource(R.string.news_empty), color = c.textSecondary, fontSize = 14.sp)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Box(Modifier.size(4.dp)) }
                items(items) { post -> NewsCard(post) }
                item { Box(Modifier.size(8.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewsCard(post: RcqApi.NewsPost) {
    val c = RcqTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    var copied by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(copied) {
        if (copied) { kotlinx.coroutines.delay(1600); copied = false }
    }
    val body = post.body
    // Remembered, not rebuilt per recomposition: it is also the key of the
    // pointer-input handler below, and a fresh lambda every frame would restart
    // the gesture detector under the finger.
    val copyPost: () -> Unit = remember(body, context) {
        {
            if (!body.isNullOrBlank()) {
                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("RCQ news", body))
                copied = true
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bgSecondary)
            // A post is often a list of changes somebody wants to quote back at
            // us, or paste to a friend, and there was no way to get the text
            // out of it at all ("желательно сделать возможность копировать в
            // буфер текст выбранной новости. Долгим зажатием наверно").
            .combinedClickable(onClick = {}, onLongClick = copyPost)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(post.author_label ?: "RCQ", color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            formatNewsDate(post.published_at)?.let { Text(it, color = c.textSecondary, fontSize = 12.sp) }
        }
        if (!body.isNullOrBlank()) {
            // A post announcing a release or a page is mostly there to hand
            // over an address, and until now that address was dead grey text
            // the reader had to retype. [linkedText] does the finding (and the
            // trimming of the full stop the sentence ends on); the tap resolves
            // through the app-wide uri handler, which is InAppBrowser, so a
            // link opens in a Custom Tab over the app exactly like one tapped
            // in a chat, and an rcq.app invite still routes back into the app.
            val linked = remember(body, c.accent) { linkedText(body, c.accent) }
            Text(
                linked,
                color = c.textPrimary,
                fontSize = 15.sp,
                // Compose gives the whole gesture inside a link to the link, so
                // without this the copy above would stop working on precisely
                // the posts worth copying.
                modifier = Modifier.longPressOverLinks(copyPost),
            )
        }
        if (post.attachments.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.AttachFile, null, tint = c.textSecondary, modifier = Modifier.size(15.dp))
                Text(stringResource(R.string.news_has_media), color = c.textSecondary, fontSize = 12.sp)
            }
        }
        if (copied) {
            Text(stringResource(R.string.news_copied), color = c.accent, fontSize = 12.sp)
        }
    }
}

/** Best-effort ISO-8601 -> localized date/time; falls back to the date part. */
private fun formatNewsDate(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val fmt = java.time.format.DateTimeFormatter
        .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
        .withZone(java.time.ZoneId.systemDefault())
    return runCatching { fmt.format(java.time.OffsetDateTime.parse(iso).toInstant()) }
        .recoverCatching { fmt.format(java.time.Instant.parse(iso)) }
        .getOrDefault(iso.take(10))
}
