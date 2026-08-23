package app.rcq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import app.rcq.android.R
import app.rcq.android.model.ChatMessage
import app.rcq.android.model.PollContent

/** A poll from an OLD peer, rendered as a retired card.
 *
 *  Polls were removed (founder decision, item 14a): the ballots were never
 *  end-to-end encrypted. The island stored `voter_uin` and `option_index` in
 *  the clear for every vote INCLUDING the ones cast in an "anonymous" poll,
 *  and `polls.creator_uin` sat next to the envelope UUID, which named the
 *  author of that one sealed message. The backend now answers 410
 *  `feature_removed` and advertises `polls:false` in `/server/info`.
 *
 *  ★★ A removed feature has to ANSWER, not vanish. Old builds are still out
 *  there and they still send `kind = "poll"`, so this row must keep drawing:
 *  it shows the question (which came out of the ENCRYPTED envelope and is
 *  already on this device) plus one line saying the ballot is retired. What it
 *  must not do is touch the network - no `getPoll`, no `vote`, no `close` -
 *  because every one of those is a 410 now, and the old bubble would have sat
 *  there with an empty tally forever.
 *
 *  Long-press still opens the message menu, so an old poll can be replied to,
 *  quoted or deleted exactly like any other message. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PollBubble(m: ChatMessage, onLongPress: () -> Unit) {
    val c = RcqTheme.colors
    // A body that will not parse still draws the card, just without a
    // question. It is NEVER printed raw: the body of a poll row is the
    // serialized ballot, and dumping that JSON at the reader would be worse
    // than saying nothing.
    val content = remember(m.id, m.body) { PollContent.fromJson(m.body) }
    Column(
        Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (m.fromMe) c.bubbleSelf else c.bubbleOther)
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.BarChart, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.poll_removed_title),
                color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        content?.question?.takeIf { it.isNotBlank() }?.let { question ->
            Text(question, color = c.textPrimary, fontSize = 15.sp)
        }
        // The options are worth keeping on screen: they are the only record of
        // what was actually being asked, and they cost nothing (they are in the
        // envelope). The tallies are NOT shown - they only ever lived on the
        // island, and that is exactly the part being retired.
        content?.options?.forEach { label ->
            Text("· $label", color = c.textSecondary, fontSize = 14.sp)
        }
        Text(stringResource(R.string.poll_removed_body), color = c.textSecondary, fontSize = 11.sp)
    }
}
