package app.rcq.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.model.GroupMember
import app.rcq.android.model.orderedRoster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.layout.imePadding
import androidx.lifecycle.repeatOnLifecycle

/** A tap-row for a sheet that brings its own body: [RcqAskSheet] lays out its
 *  actions and appends a cancel by itself, [RcqSheet] leaves both to the caller.
 *  Same shape and weights as the rows inside [RcqAskSheet]. */
@Composable
private fun SheetActionRow(label: String, dimmed: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = RcqTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (dimmed || !enabled) c.textSecondary else c.accent,
            fontSize = 16.sp,
            fontWeight = if (dimmed) FontWeight.Normal else FontWeight.Medium,
        )
    }
}

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
    // The chat list is fetched without rosters, so this screen — the one place
    // that actually shows the members — asks for it on arrival. No-op when it
    // is already here or when the group lives on another island.
    androidx.compose.runtime.LaunchedEffect(groupId) { session.ensureRoster(groupId) }

    // ⚠ A roster carries PRESENCE, and presence is a snapshot of the moment it
    // was fetched. A screen left open, or reopened from the cache, put whoever
    // came online since at the bottom under "everyone else" and kept whoever
    // left near the top (#859, and the founder the same day). So it is asked
    // again whenever this screen comes back to the front, and on a timer for
    // rooms small enough that a roster is cheap - the flagship's two thousand
    // members are hundreds of kilobytes and that one is left alone.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.LaunchedEffect(groupId, lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            session.ensureRoster(groupId, refresh = true)
            val small = (session.group(groupId)?.members?.size ?: 0) <= 200
            while (small) {
                kotlinx.coroutines.delay(45_000)
                session.ensureRoster(groupId, refresh = true)
            }
        }
    }
    val ownUin = session.uin ?: 0
    val isOwner = group?.ownerUin == ownUin
    var confirmDestructive by remember { mutableStateOf(false) }
    var showAddMember by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showPin by remember { mutableStateOf(false) }
    // Member the owner/moderator is about to remove (drives the confirm dialog).
    var memberToRemove by remember { mutableStateOf<GroupMember?>(null) }
    // Member the owner is about to hand the whole group to (founder item 23),
    // and, once the island has confirmed it, the member it went to. The second
    // one drives the follow-up offer, so it is kept apart from [group.ownerUin]
    // on purpose: "you can leave now" only makes sense to the person who just
    // handed the room over, not to everyone who opens this screen afterwards.
    var transferTarget by remember { mutableStateOf<GroupMember?>(null) }
    var handedTo by remember { mutableStateOf<GroupMember?>(null) }
    // Roster fold + search (parity with iOS): big groups show a preview, expand
    // to all, and can be searched + collapsed without scrolling to the bottom.
    var showAllMembers by remember { mutableStateOf(false) }
    var memberSearch by remember { mutableStateOf("") }
    // Copy-link transient feedback (label flips to "Link copied" for ~1.6s).
    var linkCopied by remember { mutableStateOf(false) }

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
    /** May I edit the room's name, description, picture and pin?
     *
     *  ⚠⚠ This client never asked. `members` was honoured one line above, but
     *  `info` was read NOWHERE: every edit here was gated on ownership alone,
     *  so an owner could grant the right, the island would have accepted every
     *  edit the grantee made (groups.py `_member_can(g, me, "info")` guards
     *  exactly these branches), and the grantee's own app kept the controls
     *  hidden. Granting it did visibly nothing, which is #836. The web has
     *  `canEditInfo` and iOS has `canEditChrome`; iOS's comment records that
     *  it once had this same bug and was fixed. Android was the last one left.
     *
     *  ⚠ Deliberately NOT extended to deleting the room or handing out
     *  permissions: those stay with the owner, matching the island. */
    val canEditInfo = isOwner || (ownMember?.permissions?.contains("info") == true)

    // The owner, then the moderators (the wide set: `admin` or any granted
    // cap, the same people the composer exempts from the room rules), then
    // whoever is around, then everyone else; by name inside a tier (founder,
    // 02.09: the same order on every client). The rule and its traps live in
    // `orderedRoster`, a plain function with a JVM test; this screen only
    // memoises it.
    //
    // ⚠ Keyed on `group.ownerUin` as well as the roster: the compact
    // `group_membership_changed` a big room gets carries the owner alone, so a
    // handover arrives here as a changed number over a roster that has not
    // moved (see `rosterTier`).
    val sortedMembers = remember(group.members, group.ownerUin) { orderedRoster(group.members, group.ownerUin) }
    val previewLimit = 8
    val q = memberSearch.trim().lowercase()
    val searching = q.isNotEmpty()
    val filtered = if (searching) sortedMembers.filter { it.nickname.lowercase().contains(q) || it.uin.toString().contains(q) } else sortedMembers
    val visibleMembers = if (searching || showAllMembers || filtered.size <= previewLimit) filtered else filtered.take(previewLimit)
    val hiddenCount = if (searching) 0 else (sortedMembers.size - visibleMembers.size).coerceAtLeast(0)
    val bigGroup = sortedMembers.size > previewLimit

    // ONE top-level LazyColumn for the whole screen (#650). Third layout here;
    // the arc, so the next rework does not walk the same circle: (1) the roster
    // was a weight(1f) LazyColumn below the header, and the tall owner-settings
    // block starved it to ~0px, hiding the roster and the delete button from
    // the owner; (2) the fix made the whole screen a plain verticalScroll
    // Column, which cannot be starved but composes EVERY member row eagerly,
    // so "show all" or a member search on a 2000-member group built 2000+ rows
    // in one frame (#650's open delay); (3) now the whole screen IS the lazy
    // list: header/settings blocks are one item each, member rows are
    // items(key = uin), so only on-screen rows compose and the header still
    // scrolls away with the list. With exactly one vertical scroller nothing
    // can starve, and there is no nested vertical scrolling (a Compose crash)
    // to dodge.
    // ⚠ `imePadding`, or the keyboard raised by the member search sits on top
    // of the results it is filtering: with a long roster the last rows were
    // under it, and the only way to read them was to dismiss the keyboard,
    // which is the opposite of searching (#860). The padding is on the list, so
    // the rows move up with it and the whole roster stays reachable.
    LazyColumn(Modifier.fillMaxSize().imePadding().background(c.bgPrimary)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = c.accent, modifier = Modifier.size(26.dp).clickable(onClick = onBack))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.gi_title), color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (canEditInfo) {
                    Icon(Icons.Filled.Edit, stringResource(R.string.gi_rename), tint = c.accent, modifier = Modifier.size(22.dp).clickable { showRename = true })
                }
            }
        }

        item {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    Box(Modifier.then(if (canEditInfo) Modifier.clickable { avatarPicker.launch("image/*") } else Modifier)) {
                        GroupAvatar(group, session, 72.dp, glyphSize = 40.dp, animated = true)
                    }
                    if (canEditInfo) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(c.accent).clickable { avatarPicker.launch("image/*") }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.CameraAlt, stringResource(R.string.gi_change_avatar), tint = Color.White, modifier = Modifier.size(15.dp))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(group.name, color = c.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    BadgeMark(group.badge, size = 17.dp)
                }
                // Founder item 27: the group's own screen was the last place
                // still printing the raw count, so a 12480-member room read
                // "12.5K members" in the chat list and "12480 members" here two
                // taps later. [memberCountLabel] (CountFormat.kt) is the one
                // formatter every surface shares.
                Text(memberCountLabel(group.memberCount), color = c.textSecondary, fontSize = 13.sp)
                // #581: whether the group is closed was legible only to the owner,
                // as the toggle further down, and to whoever happened to look at an
                // invite card. Same padlock and the same "Closed group" the invite
                // card carries, so the two surfaces do not teach two vocabularies
                // for one setting. Stated in both directions on purpose — a missing
                // line reads as "unknown", not as "open".
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        if (group.isClosed) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        null,
                        tint = c.textSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        stringResource(if (group.isClosed) R.string.gi_closed else R.string.gi_open),
                        color = c.textSecondary,
                        fontSize = 13.sp,
                    )
                }
                group.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = c.textSecondary, fontSize = 13.sp)
                }
            }
        }

        // Pin display for EVERYONE: the same rich, bounded viewer as the chat
        // banner. A single compact line (so a pin listing many groups can't blow
        // up the field); tap opens the scrollable sheet with clickable mentions/
        // URLs + group join-cards. Owners ALSO get the editable row in Settings.
        group.pinnedText?.takeIf { it.isNotBlank() }?.let { pin ->
            item {
                PinnedAnnouncement(
                    session = session,
                    pin = pin,
                    members = group.members,
                    ownUin = ownUin,
                    groupHost = session.groupHost(group.id),
                    onOpenPeerInfo = onOpenPeerInfo,
                    onOpenGroup = onOpenGroup,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(c.bgSecondary).padding(12.dp),
                    textColor = c.textPrimary,
                    iconTint = c.accent,
                )
            }
        }

        // Notifications (#11) — every member: All / Mentions only / None.
        item {
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

        if (canEditInfo) {
            item {
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
                    // Room policies (#755 — the desktop had these, this client
                    // could neither see nor set them). The island enforces
                    // slowmode server-side; links/files are honoured by
                    // clients on render and compose.
                    GroupToggleRow(stringResource(R.string.gi_links), stringResource(R.string.gi_links_desc), group.linksAllowed) { v -> scope.launch { runCatching { session.patchGroup(groupId, linksAllowed = v) } } }
                    // Covers photos, videos and documents; voice messages stay
                    // (founder, 02.09). The hint says so, because the person
                    // switching it has to know what they are switching.
                    GroupToggleRow(stringResource(R.string.gi_files), stringResource(R.string.gi_files_desc), group.filesAllowed) { v -> scope.launch { runCatching { session.patchGroup(groupId, filesAllowed = v) } } }
                    // Voluntary catalog (stage 6): listing publishes the name
                    // and description so island search can match the room.
                    GroupToggleRow(stringResource(R.string.gi_catalog), stringResource(R.string.gi_catalog_desc), group.inCatalog) { v -> scope.launch { runCatching { session.patchGroup(groupId, inCatalog = v) } } }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bgSecondary).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.gi_slowmode), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.gi_slowmode_desc), color = c.textSecondary, fontSize = 12.sp)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(percent = 50)).background(c.bgPrimary).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            // The island's fixed steps (_SLOWMODE_STEPS): off, 10s, 30s, 1m, 5m, 1h.
                            listOf(0 to stringResource(R.string.gi_slow_off), 10 to "10s", 30 to "30s", 60 to "1m", 300 to "5m", 3600 to "1h").forEach { (sec, label) ->
                                val sel = group.slowmodeSec == sec
                                Box(
                                    Modifier.weight(1f).clip(RoundedCornerShape(percent = 50)).background(if (sel) c.accent else Color.Transparent)
                                        .clickable { if (!sel) scope.launch { runCatching { session.patchGroup(groupId, slowmodeSec = sec) } } }.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text(label, color = if (sel) Color.White else c.textSecondary, fontSize = 12.sp) }
                            }
                        }
                    }
                    // Anti-spam age floor (#803): accounts younger than the
                    // step read but cannot post; the island enforces it.
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bgSecondary).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.gi_age_gate), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.gi_age_gate_desc), color = c.textSecondary, fontSize = 12.sp)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(percent = 50)).background(c.bgPrimary).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            // The island's fixed steps (_AGE_GATE_STEPS): off, 1h, 6h, 24h, 3d, 7d, 30d.
                            listOf(0 to stringResource(R.string.gi_slow_off), 1 to "1h", 6 to "6h", 24 to "24h", 72 to "3d", 168 to "7d", 720 to "30d").forEach { (h, label) ->
                                val sel = group.minAccountAgeHours == h
                                Box(
                                    Modifier.weight(1f).clip(RoundedCornerShape(percent = 50)).background(if (sel) c.accent else Color.Transparent)
                                        .clickable { if (!sel) scope.launch { runCatching { session.patchGroup(groupId, minAccountAgeHours = h) } } }.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text(label, color = if (sel) Color.White else c.textSecondary, fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.gi_members), color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                // Any member can add to an OPEN group (you could equally share the
                // link; the server already allows it, gated only by the invitee's
                // own invite policy + the owner's block list). A CLOSED group locks
                // adds to the owner / members-moderator.
                if (!group.isClosed || canManageMembers) {
                    Row(Modifier.clip(RoundedCornerShape(percent = 50)).clickable { showAddMember = true }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.PersonAdd, null, tint = c.accent, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.home_bar_add), color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Owner hid the roster: non-owners see a notice instead of the member
        // list (iOS parity — GroupInfoView does the same `membersHidden && !owner`
        // gate). The member COUNT above still shows; only the per-member rows are
        // withheld. Client-side hide for now (the server still sends the list);
        // server redaction is the deeper privacy follow-up.
        if (group.membersHidden && !isOwner) {
            item {
                Text(
                    stringResource(R.string.gi_members_hidden),
                    color = c.textSecondary, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        } else {
            // Search field — only on a group big enough to warrant it.
            if (bigGroup) {
                item {
                    RcqField(
                        value = memberSearch,
                        onValueChange = { memberSearch = it },
                        placeholder = stringResource(R.string.gi_member_search),
                        leadingIcon = { Icon(Icons.Filled.Search, null, tint = c.textSecondary, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (memberSearch.isNotEmpty()) Icon(Icons.Filled.Close, stringResource(R.string.common_close), tint = c.textSecondary, modifier = Modifier.size(18.dp).clickable { memberSearch = "" })
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            // Collapse control at the TOP (no need to scroll to the bottom to fold).
            if (showAllMembers && !searching && bigGroup) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { showAllMembers = false }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Filled.ExpandLess, null, tint = c.accent, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.gi_members_collapse), color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (searching && filtered.isEmpty()) {
                item {
                    Text(stringResource(R.string.gi_members_no_matches), color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
            items(visibleMembers, key = { it.uin }) { m ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = m.uin != ownUin) { onOpenPeerInfo(m.uin) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Our own row reflects the locally-known status (the
                        // server folds self→offline for "other viewers").
                        // A member's picture is part of the roster, gated by
                        // membership; without one this is the plain flower, so
                        // the screen is unchanged for everyone who never set
                        // one. Presence stays on it as the badge — this is a
                        // list of people, which is exactly where it means
                        // something.
                        PersonAvatar(
                            id = m.avatarMediaId,
                            key = m.avatarMediaKey,
                            status = if (m.uin == ownUin) ownStatus else m.presence,
                            session = session,
                            size = 26.dp,
                            // §5c: a FOREIGN group's roster, and every blob in
                            // it, lives on THAT island. Without the host this
                            // asked our own island for a media id it has never
                            // heard of, so every member's picture 404'd and the
                            // whole roster fell back to the status flower.
                            host = session.groupHost(group.id),
                        )
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(m.nickname + if (m.uin == ownUin) stringResource(R.string.gi_you) else "", color = c.textPrimary, fontSize = 15.sp)
                                BadgeMark(m.badge)
                            }
                            Text("${m.uin}", color = c.textMono, fontSize = 12.sp)
                        }
                        if (m.uin == group.ownerUin) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Icon(Icons.Filled.Star, null, tint = c.accent, modifier = Modifier.size(12.dp))
                                Text(stringResource(R.string.gi_owner), color = c.textSecondary, fontSize = 11.sp)
                            }
                        } else if (m.permissions.isNotEmpty()) {
                            Text(stringResource(R.string.gi_moderator), color = c.accent, fontSize = 11.sp)
                        }
                        // Owner/«members»-moderator can remove anyone but the
                        // owner and themselves (long-tap-free explicit control).
                        if (canManageMembers && m.uin != ownUin && m.uin != group.ownerUin) {
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
                    if (isOwner && m.uin != ownUin && m.uin != group.ownerUin) {
                        val toggle: (String) -> Unit = { perm ->
                            val next = if (perm in m.permissions) m.permissions - perm else m.permissions + perm
                            scope.launch { runCatching { session.setMemberPermissions(group.id, m.uin, next) } }
                        }
                        Row(Modifier.padding(start = 36.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PermChip(stringResource(R.string.gi_perm_delete), "delete" in m.permissions) { toggle("delete") }
                            PermChip(stringResource(R.string.gi_perm_members), "members" in m.permissions) { toggle("members") }
                            PermChip(stringResource(R.string.gi_perm_info), "info" in m.permissions) { toggle("info") }
                        }
                        // Handing the room over sits under the three chips
                        // rather than among them, and is red: those are rights
                        // the owner lends out and can take back, this is the
                        // owner seat itself and it does not come back.
                        //
                        // Own-island only (group.host == null). A member is a
                        // bare number that means something only against this
                        // island's users, so there is no wire form for "the new
                        // owner lives on island B" and the island refuses one
                        // it cannot resolve.
                        if (group.host == null) {
                            Text(
                                stringResource(R.string.gi_transfer),
                                color = Color(0xFFE5484D),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .padding(start = 32.dp, top = 6.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { transferTarget = m }
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            if (hiddenCount > 0 && !showAllMembers) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { showAllMembers = true }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Filled.ExpandMore, null, tint = c.accent, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.gi_members_show_all, hiddenCount), color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Copy group link — EVERY member (parity with iOS Manage section). The
        // link always carries the host (§5c) so it works from any island; paste
        // it into a chat or a pinned announcement to surface a tappable join card.
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp)).background(c.bgSecondary)
                    .clickable {
                        val (rid, host) = session.groupShareRef(groupId)
                        val link = GroupLinkParser.canonicalUrl(rid, host)
                        (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("group link", link))
                        linkCopied = true
                        scope.launch { delay(1600); linkCopied = false }
                    }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(if (linkCopied) Icons.Filled.Check else Icons.Filled.Link, null, tint = c.accent, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(if (linkCopied) R.string.gi_link_copied else R.string.gi_copy_link),
                    color = if (linkCopied) c.accent else c.textPrimary, fontSize = 15.sp,
                )
            }
        }

        item {
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
    }

    if (confirmDestructive) {
        RcqAskSheet(
            onDismiss = { confirmDestructive = false },
            title = stringResource(if (isOwner) R.string.gi_delete_q else R.string.gi_leave_q),
            body = stringResource(if (isOwner) R.string.gi_delete_body else R.string.gi_leave_body),
            actions = listOf(
                SheetAction(
                    label = stringResource(if (isOwner) R.string.common_delete else R.string.gi_leave_cta),
                    destructive = true,
                ) {
                    confirmDestructive = false
                    scope.launch {
                        runCatching { if (isOwner) session.deleteGroup(groupId) else session.leaveGroup(groupId) }
                        onLeft()
                    }
                },
            ),
        )
    }

    // Irreversible from this side, so it is named and spelled out before it is
    // sent: who gets the room, and what we are left holding.
    transferTarget?.let { target ->
        RcqAskSheet(
            onDismiss = { transferTarget = null },
            title = stringResource(R.string.gi_transfer_q),
            body = stringResource(R.string.gi_transfer_body, target.nickname),
            actions = listOf(
                SheetAction(
                    label = stringResource(R.string.gi_transfer_cta),
                    destructive = true,
                ) {
                    transferTarget = null
                    scope.launch {
                        // The island answers with the whole group and Session
                        // upserts it, so `isOwner` and everything gated on it
                        // flip on the next frame: the edit pencil, the owner
                        // settings block, the kick buttons, the capability
                        // chips, and "delete group" turning back into "leave".
                        val err = session.transferGroupOwner(groupId, target.uin)
                        if (err != null) {
                            android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                        } else {
                            handedTo = target
                        }
                    }
                },
            ),
        )
    }

    // The second half of a handover, offered where it leads: handing the room
    // over and then walking out is the migration the founder described, and by
    // now the button at the bottom of the screen says "leave group" instead of
    // "delete group" because the island has already answered.
    handedTo?.let { newOwner ->
        RcqAskSheet(
            onDismiss = { handedTo = null },
            title = stringResource(R.string.gi_transfer_done_title),
            body = stringResource(R.string.gi_transfer_done, newOwner.nickname),
            actions = listOf(
                SheetAction(
                    label = stringResource(R.string.gi_leave_cta),
                    destructive = true,
                ) {
                    handedTo = null
                    scope.launch {
                        // ALWAYS the self-removal, never the owner's delete.
                        // This button sits where the same person's "delete the
                        // group for everyone" sat seconds earlier, and routing
                        // it through the shared confirm would put the room one
                        // stale `isOwner` read away from being destroyed
                        // instead of left.
                        runCatching { session.leaveGroup(groupId) }
                        onLeft()
                    }
                },
            ),
            cancelLabel = stringResource(R.string.gi_transfer_stay),
        )
    }

    memberToRemove?.let { target ->
        RcqAskSheet(
            onDismiss = { memberToRemove = null },
            title = stringResource(R.string.gi_remove_member),
            body = stringResource(R.string.gi_remove_member_q, target.nickname),
            actions = listOf(
                SheetAction(
                    label = stringResource(R.string.gi_remove_member_cta),
                    destructive = true,
                ) {
                    val uin = target.uin
                    memberToRemove = null
                    scope.launch { runCatching { session.removeGroupMember(group.id, uin) } }
                },
            ),
        )
    }

    if (showAddMember) {
        val candidates = contacts.filter { ct -> group.members.none { it.uin == ct.uin } }
        RcqSheet(onDismiss = { showAddMember = false }, title = stringResource(R.string.gi_add_member)) {
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
                                    Text(ct.host, color = c.textSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
            SheetGap()
            SheetActionRow(stringResource(R.string.common_close), dimmed = true) { showAddMember = false }
        }
    }

    if (showRename) {
        var name by remember { mutableStateOf(group.name) }
        var desc by remember { mutableStateOf(group.description ?: "") }
        RcqSheet(onDismiss = { showRename = false }, title = stringResource(R.string.gi_edit)) {
            RcqField(value = name, onValueChange = { name = it }, placeholder = stringResource(R.string.gi_name), singleLine = true, modifier = Modifier.fillMaxWidth())
            SheetGap(8)
            // RcqField is single-line by default; the description never was.
            RcqField(value = desc, onValueChange = { desc = it }, placeholder = stringResource(R.string.gi_description), singleLine = false, modifier = Modifier.fillMaxWidth())
            SheetGap()
            SheetActionRow(stringResource(R.string.common_save), enabled = name.isNotBlank()) {
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
            }
            SheetActionRow(stringResource(R.string.common_cancel), dimmed = true) { showRename = false }
        }
    }

    if (showPin) {
        var pinText by remember { mutableStateOf(group.pinnedText ?: "") }
        RcqSheet(onDismiss = { showPin = false }, title = stringResource(R.string.gi_pinned)) {
            // RcqField is single-line by default; a pin is a paragraph.
            RcqField(
                value = pinText, onValueChange = { pinText = it },
                placeholder = stringResource(R.string.gi_pin_placeholder),
                singleLine = false,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            SheetGap()
            SheetActionRow(stringResource(R.string.common_save)) {
                val t = pinText.trim()
                showPin = false
                scope.launch { runCatching { session.patchGroup(groupId, pinnedText = t) } }
            }
            SheetActionRow(stringResource(R.string.common_cancel), dimmed = true) { showPin = false }
        }
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
 *  JPEG; everything else is downscaled + JPEG-compressed.
 *
 *  [maxSide] and [quality] default to the avatar numbers this has always used.
 *  ⚠ They are parameters because an avatar is not the only thing picked
 *  through here: a chat wallpaper goes through the same helper and is then
 *  drawn EDGE TO EDGE with a cropping content scale, so 640 px was blown up
 *  three or four times on an ordinary phone screen and the picture arrived
 *  smeared (#725). Pass what the destination actually paints. */
internal fun compressImageFor(
    context: android.content.Context,
    uri: android.net.Uri,
    maxSide: Int = 640,
    quality: Int = 85,
): ByteArray? {
    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    // "GIF8" magic — keep an animated GIF raw (capped) so it animates.
    val isGif = raw.size >= 4 && raw[0] == 0x47.toByte() && raw[1] == 0x49.toByte() &&
        raw[2] == 0x46.toByte() && raw[3] == 0x38.toByte()
    if (isGif && raw.size <= 2 * 1024 * 1024) return raw
    // A GIF too big to keep raw is flattened to a static JPEG via the PURE-JAVA
    // decoder — the native GIF decoder SIGSEGVs on some OEM ROMs.
    // Same orientation rule as the chat photo path (#527): a selfie set as an
    // avatar was rotated for exactly the same reason.
    val src = (if (isGif) gifFirstFrame(raw) else decodeUpright(raw)) ?: return null
    val longest = maxOf(src.width, src.height)
    val scaled = if (longest > maxSide) {
        val f = maxSide.toFloat() / longest
        android.graphics.Bitmap.createScaledBitmap(src, (src.width * f).toInt(), (src.height * f).toInt(), true)
    } else src
    val out = java.io.ByteArrayOutputStream()
    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}
