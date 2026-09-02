package app.rcq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.crypto.RecoveryPhrase
import kotlinx.coroutines.launch

/**
 * Restore an existing account on a fresh install from its 24-word recovery
 * phrase. Calls [Session.recoverAccount] which proves key ownership to the
 * server and rebinds onto the recovered UIN.
 */
@Composable
fun RestoreScreen(session: Session, onBack: () -> Unit, onRestored: (Int) -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phrase by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // Set when the phrase resolves to an identity this device already holds;
    // drives the dead-end dialog below. There is no `forceAdd` any more — see
    // the dialog for why a second local copy of one number cannot be allowed.
    var duplicateUin by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val wordCount = remember(phrase) { RecoveryPhrase.parse(phrase).size }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        Row(
            Modifier.fillMaxWidth().background(c.bgSecondary.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = c.accent,
                modifier = Modifier.size(26.dp).clip(CircleShape).clickable(enabled = !busy, onClick = onBack))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.restore_title), color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.restore_hint), color = c.textSecondary, fontSize = 14.sp)

            RcqField(
                value = phrase,
                onValueChange = { phrase = it; error = null },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                placeholder = stringResource(R.string.restore_phrase_ph),
                // 24 words wrap over several lines.
                singleLine = false,
                enabled = !busy,
            )
            Text(stringResource(R.string.restore_wordcount, wordCount), color = if (wordCount == 24) c.accent else c.textSecondary, fontSize = 12.sp)

            RcqField(
                value = server,
                onValueChange = { server = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(R.string.restore_server_ph),
                singleLine = true,
                enabled = !busy,
            )

            error?.let { Text(it, color = Color(0xFFE5484D), fontSize = 13.sp) }

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = c.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.restore_restoring), color = c.textSecondary, fontSize = 14.sp)
                }
            } else {
                CapsuleButton(stringResource(R.string.restore_button), enabled = wordCount == 24, modifier = Modifier.fillMaxWidth()) {
                    busy = true
                    error = null
                    scope.launch {
                        val words = RecoveryPhrase.parse(phrase)
                        // The island's `#fp` fragment is judged here, before
                        // anything is dialled (design §3), so its error is
                        // its own and not mistaken for a bad phrase below.
                        val entry = app.rcq.android.net.IslandTrust.adopt(server)
                        islandAddressError(context, entry)?.let {
                            error = it; busy = false; return@launch
                        }
                        val srv = (entry as? app.rcq.android.net.IslandTrust.Entry.Ok)?.hostPort
                        val res = runCatching {
                            session.recoverAccount(words, srv)
                        }
                        busy = false
                        res.onSuccess { onRestored(it) }
                            .onFailure { e ->
                                // Already on this device: ask instead of silently
                                // making a second, empty copy of the same number.
                                if (e is Session.AccountAlreadyHere) { duplicateUin = e.uin; return@launch }
                                error = when {
                                    e is IllegalArgumentException -> R.string.restore_err_invalid
                                    (e.message ?: "").contains("404") || (e.message ?: "").contains("identity_not_found") -> R.string.restore_err_notfound
                                    else -> R.string.restore_err_generic
                                }.let { id -> context.getString(id) }
                            }
                    }
                }
            }
        }
    }

    // The same number, already on this device. There is no "add anyway" here
    // any more, and the reason is not tidiness.
    //
    // A number has exactly ONE libsignal bundle on the server (it lives on the
    // user row; the devices table is for secondary devices, which only the web
    // client registers). Two local copies of one number therefore both act as
    // device 1 with their own local key state, the server keeps whichever
    // uploaded last, and a group's sender key can only be opened by that one.
    // The other copy never sees group messages from the first — permanently,
    // not until a reconnect. Report #421 was exactly this: two copies of #134
    // in one group, each missing the other's messages.
    //
    // The old dialog offered to add the copy anyway and warned only that it
    // would have "no message history", so the user agreed to lose history and
    // silently lost working group chat instead. Restoring a number you already
    // hold has no use case that this cost is worth: you already have it.
    duplicateUin?.let { dupUin ->
        RcqAskSheet(
            // Both ways out lead out. Dismissing the sheet used to just hide
            // the dialog and leave the phrase typed with Restore still armed,
            // so the only thing the next tap could do was show this same
            // question again.
            onDismiss = { duplicateUin = null; onBack() },
            title = stringResource(R.string.restore_title),
            body = stringResource(R.string.restore_already_here, dupUin),
            actions = listOf(
                SheetAction(stringResource(R.string.common_ok)) { duplicateUin = null; onBack() },
            ),
        )
    }
}
