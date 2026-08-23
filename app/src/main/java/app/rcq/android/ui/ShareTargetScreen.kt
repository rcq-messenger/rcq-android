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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.ShareIntake
import app.rcq.android.data.LocalStores

/**
 * "Send to…" — the chat picker another app's share sheet lands on.
 *
 * RCQ used to be absent from the system share sheet altogether, so sending a
 * picture from any other app (or forwarding one between two RCQ chats) meant
 * saving it to storage first and re-attaching it with the paperclip — the
 * round trip described in report #443. [ShareIntake] captures the ACTION_SEND
 * payload, this screen chooses where it goes, and the chat itself does the
 * actual send through the same paths the paperclip uses.
 *
 * Text rides along as a composer draft rather than being sent on its own, so a
 * shared link can still be introduced with a sentence before it goes.
 */
@Composable
fun ShareTargetScreen(
    session: Session,
    req: ShareIntake.Req,
    onPick: (ChatTarget) -> Unit,
    onCancel: () -> Unit,
) {
    val c = RcqTheme.colors
    val contacts by session.contacts.collectAsState()
    val groups by session.groups.collectAsState()
    val ownUin = session.uin
    var query by remember { mutableStateOf("") }

    // Blocked contacts are not a place anything can be sent to, so they stay
    // out of the list rather than failing after the tap.
    val people = remember(contacts, query) {
        contacts.filter { !it.blocked }.filter {
            query.isBlank() || it.nickname.contains(query, true) ||
                (LocalStores.aliasFor(it.uin, it.host)?.contains(query, true) == true) ||
                it.uin.toString().contains(query)
        }
    }
    val rooms = remember(groups, query) {
        groups.filter { query.isBlank() || it.name.contains(query, true) }
    }
    val savedMatches = query.isBlank() ||
        stringResource(R.string.chat_saved_title).contains(query, true)

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.common_cancel),
                tint = c.accent,
                modifier = Modifier.size(26.dp).clickable(onClick = onCancel),
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    stringResource(R.string.share_to_title),
                    color = c.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(payloadSummary(req), color = c.textSecondary, fontSize = 12.sp, maxLines = 1)
            }
        }

        RcqField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            placeholder = stringResource(R.string.share_to_search),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))

        if (people.isEmpty() && rooms.isEmpty() && !savedMatches) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(stringResource(R.string.share_to_empty), color = c.textSecondary, fontSize = 14.sp)
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            // Notes to yourself is the one target that always exists, and it is
            // the natural place to park a link you want off the other device.
            if (ownUin != null && savedMatches) {
                item(key = "saved") {
                    ShareRow(
                        title = stringResource(R.string.chat_saved_title),
                        subtitle = stringResource(R.string.chat_saved_subtitle),
                        onClick = { onPick(ChatTarget.Peer(ownUin)) },
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(c.accent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Bookmark, null,
                                tint = c.bgPrimary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
            items(people, key = { "p${it.uin}" }) { ct ->
                ShareRow(
                    title = session.contactName(ct.uin),
                    subtitle = "#${ct.uin}",
                    onClick = { onPick(ChatTarget.Peer(ct.uin)) },
                ) {
                    // No host: a person's picture is on OUR island either way —
                    // §5e deposits a cross-island contact's blob here.
                    PersonAvatar(ct.avatarMediaId, ct.avatarMediaKey, ct.presence, session, 36.dp)
                }
            }
            items(rooms, key = { "g${it.id}" }) { g ->
                ShareRow(
                    title = g.name,
                    // Founder item 27: same shared formatter as the chat list
                    // and the group screen, so one room never reads two ways.
                    subtitle = memberCountLabel(g.memberCount),
                    onClick = { onPick(ChatTarget.Group(g.id)) },
                ) {
                    GroupAvatar(g, session, 36.dp)
                }
            }
        }
    }
}

@Composable
private fun ShareRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    avatar: @Composable () -> Unit,
) {
    val c = RcqTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        avatar()
        Column {
            Text(title, color = c.textPrimary, fontSize = 15.sp, maxLines = 1)
            Text(subtitle, color = c.textSecondary, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** One line naming what is about to be sent, so the picker isn't a bare list
 *  of names with no clue what tapping one would do. */
@Composable
private fun payloadSummary(req: ShareIntake.Req): String =
    if (req.uris.isNotEmpty()) pluralStringResource(R.plurals.share_to_files, req.uris.size, req.uris.size)
    else req.text.orEmpty()
