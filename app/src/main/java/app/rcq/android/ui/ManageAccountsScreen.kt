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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.data.Account
import app.rcq.android.data.AccountCards
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
internal fun ManageAccountsScreen(
    session: Session,
    onBack: () -> Unit,
    onAddBySeed: () -> Unit = {},
    onSwitchAccount: (String) -> Unit = {},
) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    // Decoy-aware: only the decoy account shows while decoy mode is active.
    val accounts by AccountManager.visibleAccounts.collectAsState(initial = AccountManager.visibleNow())
    val activeId by AccountManager.activeId.collectAsState()
    var pendingDelete by remember { mutableStateOf<Account?>(null) }
    // Each account's cached face + name (founder item 7), the same cache the
    // home switcher reads. Warmed once, then a map lookup per row: no island is
    // asked anything to draw this screen, and it is complete on the first frame
    // after a cold start.
    remember { AccountCards.warm(context) }
    val cards by AccountCards.cards.collectAsState()
    // And the islands those accounts live on, from the same kind of cache, so
    // a row names its island properly without asking it anything.
    remember { app.rcq.android.data.IslandCards.warm(context) }
    val islands by app.rcq.android.data.IslandCards.cards.collectAsState()

    // Roster order, not creation order: the list is now reorderable (the
    // switcher renders the same order), and sorting by createdAt here would
    // have quietly undone every move.
    val sorted = accounts

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
                val card = cards[account.id]
                // remember, not a bare call: these are two SharedPreferences
                // reads and they sat in the body of a list row, so they ran
                // again on every recomposition of the screen for every account
                // on it. Keyed on the account and on the cache, which is what
                // changes when a name does.
                val nick = remember(account.id, card) {
                    SecureStore.peekNickname(context, account.id) ?: card?.nickname ?: "—"
                }
                val uin = remember(account.id, card) {
                    SecureStore.peekUin(context, account.id) ?: card?.uin
                }
                val host = account.serverHost ?: card?.host ?: RcqApi.DEFAULT_HOST
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(12.dp)).background(c.bgSecondary).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The account's own picture, cached (founder item 7). The
                    // rows carried a name, a host and a number and no face at
                    // all, which is the one thing that tells two of your own
                    // identities apart at a glance.
                    AccountAvatar(
                        card?.avatarMediaId, card?.avatarMediaKey, host,
                        active = isActive, session = session, size = 36.dp,
                    )
                    Spacer(Modifier.width(12.dp))
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
                        // The island, drawn as an island: its own logo (or
                        // its lettered tile) and the name its operator typed,
                        // falling back to the bare host for one that has never
                        // answered. This line was the hostname on its own.
                        val island = islands[host.lowercase()]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            IslandAvatar(host, island?.logoVersion, island?.name, size = 14.dp)
                            Text(
                                island?.name?.takeIf { it.isNotBlank() } ?: host,
                                color = c.textSecondary, fontSize = 12.sp,
                            )
                        }
                        uin?.let { Text("#$it", color = c.textMono, fontSize = 12.sp) }
                    }
                    val index = sorted.indexOfFirst { it.id == account.id }
                    // The active row used to end in a tick. It sat in the column
                    // where every other row has an action, did nothing when
                    // tapped, and duplicated the ACTIVE label two lines to the
                    // left. The other rows now get the action it looked like it
                    // was: switch to this account without leaving the screen.
                    if (isActive) {
                        TextButton(onClick = { pendingDelete = account }) {
                            Text(stringResource(R.string.manage_accounts_delete), color = Color(0xFFE5484D))
                        }
                    } else {
                        TextButton(onClick = { onSwitchAccount(account.id) }) {
                            Text(stringResource(R.string.manage_accounts_switch), color = c.accent)
                        }
                        TextButton(onClick = { pendingDelete = account }) {
                            Text(stringResource(R.string.manage_accounts_delete), color = Color(0xFFE5484D))
                        }
                    }
                    // Reorder: with several identities, creation order is rarely
                    // the order you want to see them in (user request).
                    //
                    // These sat BETWEEN the name and the actions, so Switch and
                    // Delete started at a different x on every row depending on
                    // how many arrows that row had — one on the first and last
                    // account, two in the middle. Ending the row with them
                    // lines the text actions up (tester, 0.95).
                    //
                    // 22dp icons with no padding gave two 22dp targets touching
                    // each other, which is under the 48dp minimum and reads as
                    // one control (#409). Same stacked order, room to hit them.
                    Spacer(Modifier.width(6.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp, stringResource(R.string.manage_accounts_move_up),
                            tint = if (index > 0) c.textSecondary else c.divider,
                            modifier = Modifier.clip(CircleShape)
                                .clickable(enabled = index > 0) { AccountManager.move(account.id, up = true) }
                                .padding(6.dp).size(22.dp),
                        )
                        Icon(
                            Icons.Filled.KeyboardArrowDown, stringResource(R.string.manage_accounts_move_down),
                            tint = if (index < sorted.lastIndex) c.textSecondary else c.divider,
                            modifier = Modifier.clip(CircleShape)
                                .clickable(enabled = index < sorted.lastIndex) { AccountManager.move(account.id, up = false) }
                                .padding(6.dp).size(22.dp),
                        )
                    }
                }
            }
            item(key = "tail") { Spacer(Modifier.height(24.dp)) }
        }
    }

    pendingDelete?.let { account ->
        val nick = SecureStore.peekNickname(context, account.id)
            ?: cards[account.id]?.nickname
            ?: (account.serverHost ?: RcqApi.DEFAULT_HOST)
        RcqAskSheet(
            onDismiss = { pendingDelete = null },
            title = stringResource(R.string.manage_accounts_delete_title, nick),
            body = stringResource(R.string.manage_accounts_delete_body),
            actions = listOf(
                SheetAction(
                    label = stringResource(R.string.manage_accounts_delete),
                    destructive = true,
                    onClick = {
                        session.deleteAccountLocal(account.id)
                        // The face goes with the account. A cached picture and
                        // nickname left behind after a LOCAL delete is exactly
                        // the leftover the delete was asked to remove.
                        AccountCards.forget(context, account.id)
                        pendingDelete = null
                    },
                ),
            ),
        )
    }
}
