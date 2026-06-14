package app.rcq.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.rcq.android.data.LocalStores
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.model.GroupMember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A small toggle chip for one moderator capability (owner taps to grant/revoke). */
@Composable
private fun PermChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = RcqTheme.colors
    Text(
        label,
        color = if (on) c.bgPrimary else c.textSecondary,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) c.accent else c.bgSecondary)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
internal fun GroupInfoScreen(session: Session, groupId: Int, onBack: () -> Unit, onLeft: () -> Unit, onOpenPeerInfo: (Int) -> Unit, onOpenGroup: (Int) -> Unit = {}) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val groups by session.groups.collectAsState()
    val contacts by session.contacts.collectAsState()
    // Our own live status — the roster reports each member's presence via the
    // server's "for other viewers" fold, which shows US as offline; render our
    // own row from the locally-known status instead (home-header parity).
    val ownStatus by session.status.collectAsState()
    val group = groups.firstOrNull { it.id == groupId }
    val ownUin = session.uin ?: 0
    val isOwner = group?.ownerUin == ownUin
    var confirmDestructive by remember { mutableStateOf(false) }
    var showAddMember by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showPin by remember { mutableStateOf(false) }
    // Member the owner/moderator is about to remove (drives the confirm dialog).
    var memberToRemove by remember { mutableStateOf<GroupMember?>(null) }
    // Roster fold + search (parity with iOS): big groups show a preview, expand
    // to all, and can be searched + collapsed without scrolling to the bottom.
    var showAllMembers by remember { mutableStateOf(false) }
    var memberSearch by remember { mutableStateOf("") }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val jpeg = withContext(Dispatchers.IO) { compressImageFor(context, uri) }
            if (jpeg != null) runCatching { session.setGroupAvatar(groupId, jpeg) }
        }
    }

    if (group == null) {
        // Group vanished (left/deleted) — bounce back.
        Box(Modifier.fillMaxSize().background(c.bgPrimary))
        return
    }

    // Who may remove members: the owner (implicitly) or a moderator the owner
    // granted the "members" cap. Mirrors the backend `_member_can(.,'members')`.
    val ownMember = group.members.firstOrNull { it.uin == ownUin }
    val canManageMembers = isOwner || (ownMember?.permissions?.contains("members") == true)

    Column(Modifier.fillMaxSize().background(c.bgPrimary).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = c.accent, modifier = Modifier.size(26.dp).clickable(onClick = onBack))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.gi_title), color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (isOwner) {
                Icon(Icons.Filled.Edit, stringResource(R.string.gi_rename), tint = c.accent, modifier = Modifier.size(22.dp).clickable { showRename = true })
            }
        }

        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(Modifier.then(if (isOwner) Modifier.clickable { avatarPicker.launch("image/*") } else Modifier)) {
                    GroupAvatar(group, session, 72.dp, glyphSize = 40.dp, animated = true)
                }
                if (isOwner) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(c.accent).clickable { avatarPicker.launch("image/*") }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.CameraAlt, stringResource(R.string.gi_change_avatar), tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                }
            }
            Text(group.name, color = c.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(pluralStringResource(R.plurals.members, group.members.size, group.members.size), color = c.textSecondary, fontSize = 13.sp)
            group.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = c.textSecondary, fontSize = 13.sp)
            }
        }

        // Pin display for EVERYONE: the same rich, bounded viewer as the chat
        // banner. A single compact line (so a pin listing many groups can't blow
        // up the field); tap opens the scrollable sheet with clickable mentions/
        // URLs + group join-cards. Owners ALSO get the editable row in Settings.
        group.pinnedText?.takeIf { it.isNotBlank() }?.let { pin ->
            PinnedAnnouncement(
                session = session,
                pin = pin,
                members = group.members,
                ownUin = ownUin,
                onOpenPeerInfo = onOpenPeerInfo,
                onOpenGroup = onOpenGroup,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(c.bgSecondary).padding(12.dp),
                textColor = c.textPrimary,
                iconTint = c.accent,
            )
        }

        // Notifications (#11) — every member: All / Mentions only / None.
        run {
            val mutedSet by LocalStores.muted.collectAsState()
            val mentionsSet by LocalStores.mentionsOnly.collectAsState()
            val thread = LocalStores.groupThread(groupId)
            val mode = when {
                thread in mutedSet -> LocalStores.NotifyMode.NONE
                thread in mentionsSet -> LocalStores.NotifyMode.MENTIONS
                else -> LocalStores.NotifyMode.ALL
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.gi_notifications), color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(percent = 50)).background(c.bgSecondary).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf(
                        LocalStores.NotifyMode.ALL to stringResource(R.string.gi_notify_all),
                        LocalStores.NotifyMode.MENTIONS to stringResource(R.string.gi_notify_mentions),
                        LocalStores.NotifyMode.NONE to stringResource(R.string.gi_notify_none),
                    ).forEach { (m, label) ->
                        val sel = mode == m
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(percent = 50)).background(if (sel) c.accent else Color.Transparent)
                                .clickable { if (!sel) LocalStores.setNotifyMode(thread, m) }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = if (sel) Color.White else c.textSecondary, fontSize = 13.sp) }
                    }
                }
            }
        }

        if (isOwner) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.gi_settings), color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                // Pinned message — opens an editor dialog.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bgSecondary).clickable { showPin = true }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.PushPin, null, tint = c.accent, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.gi_pinned), color = c.textPrimary, fontSize = 15.sp)
                        Text(group.pinnedText?.takeIf { it.isNotBlank() } ?: stringResource(R.string.gi_none), color = c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Filled.Edit, null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
                }
                // Who can post.
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bgSecondary).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.gi_who_post), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(percent = 50)).background(c.bgPrimary).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf("all" to stringResource(R.string.vis_everyone), "owner_only" to stringResource(R.string.gi_post_owner)).forEach { (key, label) ->
                            val sel = group.postPolicy == key
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(percent = 50)).background(if (sel) c.accent else Color.Transparent)
                                    .clickable { if (!sel) scope.launch { runCatching { session.patchGroup(groupId, postPolicy = key) } } }.padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(label, color = if (sel) Color.White else c.textSecondary, fontSize = 13.sp) }
                        }
                    }
                }
                GroupToggleRow(stringResource(R.string.gi_closed), stringResource(R.string.gi_closed_desc), group.isClosed) { v -> scope.launch { runCatching { session.patchGroup(groupId, isClosed = v) } } }
                GroupToggleRow(stringResource(R.string.gi_hide), stringResource(R.string.gi_hide_desc), group.membersHidden) { v -> scope.launch { runCatching { session.patchGroup(groupId, membersHidden = v) } } }
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.gi_members), color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (isOwner) {
                Row(Modifier.clip(RoundedCornerShape(percent = 50)).clickable { showAddMember = true }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.PersonAdd, null, tint = c.accent, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.home_bar_add), color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Owner first, then admins, then everyone else (stable within a rank).
        val sortedMembers = remember(group.members) {
            group.members.sortedBy { when (it.role) { "owner" -> 0; "admin" -> 1; else -> 2 } }
        }
        val previewLimit = 8
        val q = memberSearch.trim().lowercase()
        val searching = q.isNotEmpty()
        val filtered = if (searching) sortedMembers.filter { it.nickname.lowercase().contains(q) || it.uin.toString().contains(q) } else sortedMembers
        val visibleMembers = if (searching || showAllMembers || filtered.size <= previewLimit) filtered else filtered.take(previewLimit)
        val hiddenCount = if (searching) 0 else (sortedMembers.size - visibleMembers.size).coerceAtLeast(0)
        val bigGroup = sortedMembers.size > previewLimit
        // Plain Column (not a weight(1f) LazyColumn): the whole screen is now
        // verticalScroll-able, and a weight(1f) lazy list inside that got
        // starved to ~0px by the tall owner-settings block + per-member perm
        // chips, hiding the roster + delete button from the owner.
        Column(Modifier.fillMaxWidth()) {
            // Search field — only on a group big enough to warrant it.
            if (bigGroup) {
                OutlinedTextField(
                    value = memberSearch,
                    onValueChange = { memberSearch = it },
                    placeholder = { Text(stringResource(R.string.gi_member_search), color = c.textSecondary) },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = c.textSecondary, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (memberSearch.isNotEmpty()) Icon(Icons.Filled.Close, stringResource(R.string.common_close), tint = c.textSecondary, modifier = Modifier.size(18.dp).clickable { memberSearch = "" })
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            // Collapse control at the TOP (no need to scroll to the bottom to fold).
            if (showAllMembers && !searching && bigGroup) {
                Row(
                    Modifier.fillMaxWidth().clickable { showAllMembers = false }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.ExpandLess, null, tint = c.accent, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.gi_members_collapse), color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (searching && filtered.isEmpty()) {
                Text(stringResource(R.string.gi_members_no_matches), color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            visibleMembers.forEach { m ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = m.uin != ownUin) { onOpenPeerInfo(m.uin) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Our own row reflects the locally-known status (the
                        // server folds self→offline for "other viewers").
                        StatusIcon(if (m.uin == ownUin) ownStatus else m.presence, size = 26.dp)
                        Column(Modifier.weight(1f)) {
                            Text(m.nickname + if (m.uin == ownUin) stringResource(R.string.gi_you) else "", color = c.textPrimary, fontSize = 15.sp)
                            Text("#${m.uin}", color = c.textMono, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        if (m.role == "owner") {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Icon(Icons.Filled.Star, null, tint = c.accent, modifier = Modifier.size(12.dp))
                                Text(stringResource(R.string.gi_owner), color = c.textSecondary, fontSize = 11.sp)
                            }
                        } else if (m.permissions.isNotEmpty()) {
                            Text(stringResource(R.string.gi_moderator), color = c.accent, fontSize = 11.sp)
                        }
                        // Owner/«members»-moderator can remove anyone but the
                        // owner and themselves (long-tap-free explicit control).
                        if (canManageMembers && m.uin != ownUin && m.role != "owner") {
                            Icon(
                                Icons.Filled.PersonRemove,
                                stringResource(R.string.gi_remove_member),
                                tint = Color(0xFFE5484D),
                                modifier = Modifier.size(22.dp).clickable { memberToRemove = m },
                            )
                        }
                    }
                    // Owner picks which rights this member gets. Tapping a chip
                    // grants/revokes that cap (POST /permissions). Owner has all
                    // implicitly, so no chips on the owner row or for yourself.
                    if (isOwner && m.uin != ownUin && m.role != "owner") {
                        val toggle: (String) -> Unit = { perm ->
                            val next = if (perm in m.permissions) m.permissions - perm else m.permissions + perm
                            scope.launch { runCatching { session.setMemberPermissions(group.id, m.uin, next) } }
                        }
                        Row(Modifier.padding(start = 36.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PermChip(stringResource(R.string.gi_perm_delete), "delete" in m.permissions) { toggle("delete") }
                            PermChip(stringResource(R.string.gi_perm_members), "members" in m.permissions) { toggle("members") }
                            PermChip(stringResource(R.string.gi_perm_info), "info" in m.permissions) { toggle("info") }
                        }
                    }
                }
            }
            if (hiddenCount > 0 && !showAllMembers) {
                Row(
                    Modifier.fillMaxWidth().clickable { showAllMembers = true }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.ExpandMore, null, tint = c.accent, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.gi_members_show_all, hiddenCount), color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Box(
            Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x14E5484D)).clickable { confirmDestructive = true }.padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (isOwner) Icons.Filled.Delete else Icons.AutoMirrored.Filled.ExitToApp, null, tint = Color(0xFFE5484D), modifier = Modifier.size(18.dp))
                Text(stringResource(if (isOwner) R.string.gi_delete else R.string.gi_leave), color = Color(0xFFE5484D), fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (confirmDestructive) {
        AlertDialog(
            onDismissRequest = { confirmDestructive = false },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(if (isOwner) R.string.gi_delete_q else R.string.gi_leave_q), color = c.textPrimary) },
            text = { Text(stringResource(if (isOwner) R.string.gi_delete_body else R.string.gi_leave_body), color = c.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDestructive = false
                    scope.launch {
                        runCatching { if (isOwner) session.deleteGroup(groupId) else session.leaveGroup(groupId) }
                        onLeft()
                    }
                }) { Text(stringResource(if (isOwner) R.string.common_delete else R.string.gi_leave_cta), color = Color(0xFFE5484D)) }
            },
            dismissButton = { TextButton(onClick = { confirmDestructive = false }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
        )
    }

    memberToRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { memberToRemove = null },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(R.string.gi_remove_member), color = c.textPrimary) },
            text = { Text(stringResource(R.string.gi_remove_member_q, target.nickname), color = c.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    val uin = target.uin
                    memberToRemove = null
                    scope.launch { runCatching { session.removeGroupMember(group.id, uin) } }
                }) { Text(stringResource(R.string.gi_remove_member_cta), color = Color(0xFFE5484D)) }
            },
            dismissButton = { TextButton(onClick = { memberToRemove = null }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
        )
    }

    if (showAddMember) {
        val candidates = contacts.filter { ct -> group.members.none { it.uin == ct.uin } }
        AlertDialog(
            onDismissRequest = { showAddMember = false },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(R.string.gi_add_member), color = c.textPrimary) },
            text = {
                if (candidates.isEmpty()) {
                    Text(stringResource(R.string.gi_all_in), color = c.textSecondary)
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(candidates, key = { it.uin }) { ct ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    scope.launch {
                                        if (ct.host != null) {
                                            // §5c: a contact on another island can't be added by their
                                            // foreign uin (the group's island has no such account).
                                            // Resolve/register them on the group's island + invite by link.
                                            val ci = app.rcq.android.net.CrossIslandStore.get(ct.uin, ct.host)
                                            val err = if (ci != null) session.addCrossIslandGroupMember(groupId, ci) else "no card"
                                            if (err != null) android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                                        } else {
                                            val err = session.addGroupMember(groupId, ct.uin)
                                            if (err != null) android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                    showAddMember = false
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                StatusIcon(ct.presence, size = 24.dp, crossIsland = ct.host != null)
                                Column {
                                    Text(ct.nickname, color = c.textPrimary, fontSize = 15.sp)
                                    if (ct.host != null) {
                                        Text(ct.host, color = c.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAddMember = false }) { Text(stringResource(R.string.common_close), color = c.textSecondary) } },
        )
    }

    if (showRename) {
        var name by remember { mutableStateOf(group.name) }
        var desc by remember { mutableStateOf(group.description ?: "") }
        AlertDialog(
            onDismissRequest = { showRename = false },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(R.string.gi_edit), color = c.textPrimary) },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.gi_name), color = c.textSecondary) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text(stringResource(R.string.gi_description), color = c.textSecondary) }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val n = name.trim(); val d = desc.trim()
                    showRename = false
                    scope.launch {
                        runCatching {
                            session.patchGroup(
                                groupId,
                                name = if (n != group.name) n else null,
                                description = if (d != (group.description ?: "")) d else null,
                            )
                        }
                    }
                }) { Text(stringResource(R.string.common_save), color = if (name.isNotBlank()) c.accent else c.textSecondary) }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
        )
    }

    if (showPin) {
        var pinText by remember { mutableStateOf(group.pinnedText ?: "") }
        AlertDialog(
            onDismissRequest = { showPin = false },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(R.string.gi_pinned), color = c.textPrimary) },
            text = {
                OutlinedTextField(
                    value = pinText, onValueChange = { pinText = it },
                    placeholder = { Text(stringResource(R.string.gi_pin_placeholder), color = c.textSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = pinText.trim()
                    showPin = false
                    scope.launch { runCatching { session.patchGroup(groupId, pinnedText = t) } }
                }) { Text(stringResource(R.string.common_save), color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { showPin = false }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
        )
    }
}

@Composable
private fun GroupToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = RcqTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bgSecondary).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.textPrimary, fontSize = 15.sp)
            Text(subtitle, color = c.textSecondary, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
    }
}

/** Shared downscale+compress for picked images (avatars). A small animated GIF
 *  is kept as-is so it still animates instead of being flattened to a static
 *  JPEG; everything else is downscaled + JPEG-compressed. */
internal fun compressImageFor(context: android.content.Context, uri: android.net.Uri): ByteArray? {
    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    // "GIF8" magic — keep an animated GIF raw (capped) so it animates.
    val isGif = raw.size >= 4 && raw[0] == 0x47.toByte() && raw[1] == 0x49.toByte() &&
        raw[2] == 0x46.toByte() && raw[3] == 0x38.toByte()
    if (isGif && raw.size <= 2 * 1024 * 1024) return raw
    // A GIF too big to keep raw is flattened to a static JPEG via the PURE-JAVA
    // decoder — the native GIF decoder SIGSEGVs on some OEM ROMs.
    val src = (if (isGif) gifFirstFrame(raw) else android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)) ?: return null
    val maxSide = 640
    val longest = maxOf(src.width, src.height)
    val scaled = if (longest > maxSide) {
        val f = maxSide.toFloat() / longest
        android.graphics.Bitmap.createScaledBitmap(src, (src.width * f).toInt(), (src.height * f).toInt(), true)
    } else src
    val out = java.io.ByteArrayOutputStream()
    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
    return out.toByteArray()
}
