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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.data.Account
import app.rcq.android.data.AccountManager
import app.rcq.android.data.SecureStore
import app.rcq.android.net.RcqApi

/**
 * Local-only management of the account roster — the Android port of the iOS
 * `ManageAccountsSheet`. Lists every account on the device and lets the user
 * delete the ones they no longer want. The active account can't be deleted
 * here (switch to another first via the Home switcher); this keeps the data
 * flow simple — a delete only ever touches a non-mounted account's stores.
 *
 * "Delete" is local: it wipes this device's per-account SecureStore /
 * MessageDb / VisitStore / LocalStores entries. The server-side UIN stays
 * alive; to fully burn it, switch to the account and use Burn account in
 * Privacy & Network.
 */
@Composable
internal fun ManageAccountsScreen(session: Session, onBack: () -> Unit, onAddBySeed: () -> Unit = {}) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    // Decoy-aware: only the decoy account shows while decoy mode is active.
    val accounts by AccountManager.visibleAccounts.collectAsState(initial = AccountManager.visibleNow())
    val activeId by AccountManager.activeId.collectAsState()
    var pendingDelete by remember { mutableStateOf<Account?>(null) }

    val sorted = accounts.sortedBy { it.createdAt }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = c.accent, modifier = Modifier.size(26.dp).clickable(onClick = onBack))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.home_menu_manage_accounts), color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item(key = "intro") {
                Text(
                    stringResource(R.string.manage_accounts_intro),
                    color = c.textSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            // Add an EXISTING account (one you already own elsewhere) to this
            // device by typing its recovery phrase — registers it as a further
            // local account and switches to it. Hidden at the account limit.
            if (!AccountManager.isAtLimit) {
                item(key = "add_by_phrase") {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp)
                            .clip(RoundedCornerShape(12.dp)).background(c.bgSecondary)
                            .clickable(onClick = onAddBySeed).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Filled.Add, null, tint = c.accent, modifier = Modifier.size(22.dp))
                        Text(
                            stringResource(R.string.manage_accounts_add_by_phrase),
                            color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            items(sorted, key = { it.id }) { account ->
                val isActive = account.id == activeId
                val nick = SecureStore.peekNickname(context, account.id) ?: "—"
                val uin = SecureStore.peekUin(context, account.id)
                val host = account.serverHost ?: RcqApi.DEFAULT_HOST
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(12.dp)).background(c.bgSecondary).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(nick, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            if (isActive) {
                                Text(
                                    stringResource(R.string.manage_accounts_active).uppercase(),
                                    color = c.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Text(host, color = c.textSecondary, fontSize = 12.sp)
                        uin?.let { Text("#$it", color = c.textMono, fontSize = 12.sp) }
                    }
                    if (isActive) {
                        Icon(Icons.Filled.Check, null, tint = c.accent, modifier = Modifier.size(20.dp))
                    } else {
                        TextButton(onClick = { pendingDelete = account }) {
                            Text(stringResource(R.string.manage_accounts_delete), color = Color(0xFFE5484D))
                        }
                    }
                }
            }
            item(key = "tail") { Spacer(Modifier.height(24.dp)) }
        }
    }

    pendingDelete?.let { account ->
        val nick = SecureStore.peekNickname(context, account.id) ?: (account.serverHost ?: RcqApi.DEFAULT_HOST)
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(R.string.manage_accounts_delete_title, nick), color = c.textPrimary) },
            text = { Text(stringResource(R.string.manage_accounts_delete_body), color = c.textSecondary) },
            confirmButton = {
                TextButton(onClick = { session.deleteAccountLocal(account.id); pendingDelete = null }) {
                    Text(stringResource(R.string.manage_accounts_delete), color = Color(0xFFE5484D))
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
        )
    }
}
