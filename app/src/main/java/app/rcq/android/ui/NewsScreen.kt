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
import androidx.compose.ui.text.style.TextOverflow
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

    // The island this feed comes from, drawn the way every other screen draws
    // an island: off the card the last `/server/info` wrote, so the header is
    // complete on the first frame and no island is asked anything to render
    // it. GET /news is the session's own island and nobody else's, which is
    // why the host is the session's rather than anything a post carries.
    val context = androidx.compose.ui.platform.LocalContext.current
    remember { app.rcq.android.data.IslandCards.warm(context) }
    val islandCards by app.rcq.android.data.IslandCards.cards.collectAsState()
    val host = session.currentServer
    val card = islandCards[host.lowercase()]
    val author = NewsAuthor(host, card?.name?.takeIf { it.isNotBlank() } ?: host, card?.logoVersion)

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
                items(items) { post -> NewsCard(post, author) }
                item { Box(Modifier.size(8.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewsCard(post: RcqApi.NewsPost, author: NewsAuthor) {
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NewsAuthorLine(post.author_label, author, Modifier.weight(1f))
            formatNewsDate(post.published_at)?.let {
                Text(it, color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }
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

/** The island a feed belongs to, resolved once per screen rather than once per
 *  post: every post on the feed has the same author. [name] already falls back
 *  to the host, which is all anybody knows about an island that has never
 *  answered `/server/info`. */
private data class NewsAuthor(val host: String, val name: String, val logoVersion: String?)

/** Labels an island sends for a post its operator signed with nothing. The
 *  stock server said "RCQ Team" for every post until 02.09, and this screen
 *  drew "RCQ" for a missing label; neither names an island, so neither earns
 *  a suffix. Matched without case: a fork writing "RCQ team" means the same
 *  default. */
private val STOCK_AUTHOR_LABELS = listOf("RCQ Team", "RCQ")

/** The island's label, or null when it would only repeat what the island's
 *  face already says. Split out of the composable so the rule has a JVM test. */
internal fun newsAuthorSuffix(label: String?, islandName: String): String? =
    label?.trim()?.takeIf { l ->
        l.isNotEmpty() &&
            STOCK_AUTHOR_LABELS.none { it.equals(l, ignoreCase = true) } &&
            !l.equals(islandName, ignoreCase = true)
    }

/**
 * Who a post is from: the island, drawn as an island.
 *
 * The line used to print whatever label the island sent, and the island sent
 * "RCQ Team" unless told otherwise, so a self-hoster's own announcements came
 * out signed by a team that never wrote them. The author of a feed is the
 * island serving it, and it gets the island's logo (or lettered tile) and the
 * name its operator typed, the same face the switcher and Settings draw for it
 * (founder, 02.09: the island's logo and name on every client, not RCQ Team).
 *
 * The island's label survives as a dim suffix only when it adds something: an
 * operator signing posts as "Support" keeps that, while a stock label, or one
 * that merely repeats the island's name, would print the author twice.
 */
@Composable
private fun NewsAuthorLine(label: String?, author: NewsAuthor, modifier: Modifier = Modifier) {
    val c = RcqTheme.colors
    val suffix = newsAuthorSuffix(label, author.name)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IslandAvatar(author.host, author.logoVersion, author.name, size = 16.dp)
        // The name is measured before the suffix and ellipsised on its own: a
        // long island name shortens the suffix, never the other way round.
        Text(
            author.name, color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (suffix != null) {
            Text(
                suffix, color = c.textSecondary, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
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
