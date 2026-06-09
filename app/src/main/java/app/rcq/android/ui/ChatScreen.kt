package app.rcq.android.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.annotation.SuppressLint
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.crypto.Reply
import app.rcq.android.media.VoiceRecorder
import app.rcq.android.model.ChatMessage
import app.rcq.android.model.DeliveryState
import app.rcq.android.model.UserStatus
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a chat thread is pointed at — a 1:1 peer or a group. */
sealed interface ChatTarget {
    data class Peer(val uin: Int) : ChatTarget
    data class Group(val id: Int) : ChatTarget
}

/** Process-lifetime composer drafts, keyed by thread ("p:<uin>" / "g:<id>").
 *  A typed-but-unsent message survives navigating to a profile or back to the
 *  chat list and returning (a fresh ChatScreen composition reads its draft
 *  back). Cleared on send. */
private object ChatDrafts {
    val byThread = mutableMapOf<String, String>()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatScreen(session: Session, target: ChatTarget, onBack: () -> Unit, onOpenGroupInfo: (Int) -> Unit = {}, onOpenPeerInfo: (Int) -> Unit = {}, onOpenGroup: (Int) -> Unit = {}) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val ownUin = session.uin ?: 0

    // Hide the soft keyboard when leaving the chat (any exit path disposes this
    // composable) so it doesn't linger over the chat list — reported: exiting a
    // chat with the keyboard up left it showing on the list.
    val exitKeyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) { onDispose { exitKeyboard?.hide() } }

    val isGroup = target is ChatTarget.Group
    val groupId = (target as? ChatTarget.Group)?.id
    val peer = (target as? ChatTarget.Peer)?.uin
    // A 1:1 thread pointed at your own UIN = "Saved messages" (notes to self).
    // Self-sends loop through the sealed path but dedup by envelope UUID, so a
    // note shows once as a `fromMe` bubble; typing / contact-info are pointless
    // against yourself, so they're suppressed below.
    val isSelf = !isGroup && peer != null && peer == ownUin

    val peerAll by session.messages.collectAsState()
    val groupAll by session.groupMessages.collectAsState()
    val contacts by session.contacts.collectAsState()
    val groups by session.groups.collectAsState()
    val typingFrom by session.typingFrom.collectAsState()

    val messages = if (isGroup) groupAll[groupId] ?: emptyList() else peerAll[peer] ?: emptyList()
    val peerContact = peer?.let { p -> contacts.firstOrNull { it.uin == p } }
    val group = groupId?.let { gid -> groups.firstOrNull { it.id == gid } }
    val canPost = group?.canPost(ownUin) ?: true
    // Resolve a `#<uin>` mention in a message body to a nick (group member or
    // contact), for clickable mentions in the bubble — like the pinned banner.
    val mentionNick = remember(contacts, group, isGroup) {
        { uin: Int ->
            (if (isGroup) group?.members?.firstOrNull { it.uin == uin }?.nickname else null)
                ?: contacts.firstOrNull { it.uin == uin }?.nickname
        }
    }
    val onMentionClick: (Int) -> Unit = { uin -> if (uin != ownUin) onOpenPeerInfo(uin) }
    // Resolve an `@nickname` to a group member's uin (case-insensitive), for
    // clickable @-mentions in the bubble + the composer autocomplete. Groups only.
    val mentionUin = remember(group, isGroup) {
        { nick: String ->
            if (isGroup) group?.members?.firstOrNull { it.nickname.equals(nick, ignoreCase = true) }?.uin else null
        }
    }
    val isTyping = !isGroup && typingFrom == peer

    // Draft survives leaving + re-entering the chat (tester #6): held per-thread
    // in a process-level map, not just transient composable state.
    val threadKey = if (isGroup) "g:$groupId" else "p:$peer"
    // NB: the composer draft lives INSIDE `Composer` now (not here), so typing
    // a character doesn't recompose the whole ChatScreen (header + message
    // LazyColumn). That recomposition-per-keystroke was the input-field lag.
    var actionMsg by remember { mutableStateOf<ChatMessage?>(null) }
    // Long-pressing a reaction chip opens a "who reacted" sheet for that message.
    var whoReactedMsg by remember { mutableStateOf<ChatMessage?>(null) }
    var editMsg by remember { mutableStateOf<ChatMessage?>(null) }
    var replyTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var attachMenu by remember { mutableStateOf(false) }
    var showPollComposer by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var chatMenu by remember { mutableStateOf(false) }
    // A picked photo/video waiting in the pre-send preview (tap to blur).
    var pendingSend by remember { mutableStateOf<PendingSend?>(null) }
    var showGroupPicker by remember { mutableStateOf(false) }
    // Decrypted bytes of a photo opened for fullscreen viewing (tester #10).
    var fullscreenImage by remember { mutableStateOf<ByteArray?>(null) }
    val listState = rememberLazyListState()

    // Per-conversation screen-secure mode (1:1 only) is NOTIFY-ONLY (iOS parity,
    // founder's choice): we no longer blank the chat with FLAG_SECURE. When a
    // screenshot is taken while a secure chat is open, MainActivity's screen-
    // capture detector (Android 14+) sends the peer a "took a screenshot" notice
    // via Session.onLocalScreenshot(). The global screen-security toggle still
    // hard-blocks all screenshots via FLAG_SECURE (applied in MainActivity).
    val secureThreads by app.rcq.android.data.LocalStores.secureThreads.collectAsState()
    val chatSecure = !isGroup && !isSelf && peer != null &&
        app.rcq.android.data.LocalStores.peerThread(peer) in secureThreads

    fun authorName(m: ChatMessage): String = when {
        m.fromMe -> "You"
        isGroup -> group?.memberName(m.senderUin ?: 0) ?: "${m.senderUin}"
        else -> session.contactName(peer ?: 0)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            // GIFs ship as RAW bytes (re-compressing to JPEG would kill the
            // animation); everything else downscales to JPEG as before.
            val data = withContext(Dispatchers.IO) { readImageForSend(context, uri) }
            // Hold it in the pre-send preview so the user can mark it a spoiler.
            if (data != null) pendingSend = PendingSend.Photo(data)
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val picked = withContext(Dispatchers.IO) { readPickedFile(context, uri) }
            if (picked != null) runCatching {
                if (isGroup) session.sendGroupFile(groupId!!, picked.bytes, picked.name, picked.mime)
                else session.sendFile(peer!!, picked.bytes, picked.name, picked.mime)
            }
        }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val v = withContext(Dispatchers.IO) { readPickedVideo(context, uri) }
            if (v != null) pendingSend = PendingSend.Video(v)
        }
    }

    // Multi-pick photos/videos → one media album (shared album id). A single
    // pick still sends fine (renders as a normal single, not a grid).
    val albumPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val albumId = if (uris.size > 1) java.util.UUID.randomUUID().toString().uppercase() else null
            for (uri in uris) {
                val mime = withContext(Dispatchers.IO) { context.contentResolver.getType(uri) } ?: ""
                runCatching {
                    if (mime.startsWith("video/")) {
                        val v = withContext(Dispatchers.IO) { readPickedVideo(context, uri) }
                        if (v != null) {
                            if (isGroup) session.sendGroupVideo(groupId!!, v.bytes, v.thumbB64, v.durationSec, null, albumId = albumId)
                            else session.sendVideo(peer!!, v.bytes, v.thumbB64, v.durationSec, null, albumId = albumId)
                        }
                    } else {
                        val data = withContext(Dispatchers.IO) { readImageForSend(context, uri) }
                        if (data != null) {
                            if (isGroup) session.sendGroupPhoto(groupId!!, data, null, albumId = albumId)
                            else session.sendPhoto(peer!!, data, null, albumId = albumId)
                        }
                    }
                }
            }
        }
    }

    // ── share location ───────────────────────────────────────────────
    fun doShareLocation() {
        scope.launch {
            val loc = withContext(Dispatchers.IO) { currentLocation(context) } ?: return@launch
            runCatching {
                if (isGroup) session.sendGroupLocation(groupId!!, loc.first, loc.second, null)
                else session.sendLocation(peer!!, loc.first, loc.second, null)
            }
        }
    }
    val locPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doShareLocation()
    }
    fun shareLocation() {
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (ok) doShareLocation() else locPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // ── calls ─────────────────────────────────────────────────────────
    var pendingCallMedia by remember { mutableStateOf<app.rcq.android.call.CallController.Media?>(null) }
    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val media = pendingCallMedia ?: return@rememberLauncherForActivityResult
        pendingCallMedia = null
        val audioOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camOk = media != app.rcq.android.call.CallController.Media.VIDEO ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (audioOk && camOk) peer?.let { session.calls.start(it, media) }
        else android.widget.Toast.makeText(context, context.getString(R.string.call_perm_needed), android.widget.Toast.LENGTH_LONG).show()
    }
    fun placeCall(media: app.rcq.android.call.CallController.Media) {
        val p = peer ?: return
        val needed = if (media == app.rcq.android.call.CallController.Media.VIDEO)
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        else arrayOf(Manifest.permission.RECORD_AUDIO)
        val missing = needed.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) session.calls.start(p, media)
        else { pendingCallMedia = media; callPermission.launch(missing.toTypedArray()) }
    }

    // ── voice recording ──────────────────────────────────────────────
    val recorder = remember { VoiceRecorder(context.cacheDir) }
    var recording by remember { mutableStateOf(false) }
    var recElapsed by remember { mutableStateOf(0) }
    LaunchedEffect(recording) {
        if (recording) { recElapsed = 0; while (true) { delay(1000); recElapsed++ } }
    }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    fun startRecording() { if (runCatching { recorder.start() }.isSuccess) recording = true }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
    }
    fun onMic() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun stopAndSendVoice() {
        recording = false
        val res = recorder.stop() ?: return
        scope.launch {
            runCatching {
                if (isGroup) session.sendGroupVoice(groupId!!, res.first, res.second)
                else session.sendVoice(peer!!, res.first, res.second)
            }
        }
    }
    fun cancelRecording() { recording = false; recorder.cancel() }

    // Mark this thread active+read while open; clear again on a new
    // message arriving here is handled in Session.bumpUnreadIfInbound.
    val thisThread = if (isGroup) app.rcq.android.data.LocalStores.groupThread(groupId!!) else app.rcq.android.data.LocalStores.peerThread(peer!!)
    DisposableEffect(target) {
        session.openThread(thisThread)
        if (!isGroup && !isSelf && peer != null) session.sendReadReceipts(peer)
        onDispose {
            session.closeThread()
            if (!isGroup && !isSelf && peer != null) session.sendTyping(peer, false)
        }
    }
    // A message can land while the chat is already open — re-clear so the
    // badge never lingers after the user has seen it.
    LaunchedEffect(messages.size) { app.rcq.android.data.LocalStores.clearUnread(thisThread) }

    // Snapshot the unread count at open (before openThread clears it) so we can
    // mark where reading left off — an "Unread messages" divider, Telegram-style.
    val initialUnread = remember(target) { app.rcq.android.data.LocalStores.unread.value[thisThread] ?: 0 }
    val firstUnreadIndex = remember(messages.size, initialUnread) {
        if (initialUnread in 1..messages.size) messages.size - initialUnread else -1
    }
    val rows = remember(messages, firstUnreadIndex) { buildChatRows(messages, firstUnreadIndex) }
    var didInitialScroll by remember(target) { mutableStateOf(false) }
    var highlightId by remember(target) { mutableStateOf<String?>(null) }

    // Initial position: at the first unread (or the bottom). INSTANT (no
    // animation) — the old animateScroll-on-every-size-change was the "mota к
    // последнему / eats resources" complaint (#1).
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty() && !didInitialScroll) {
            didInitialScroll = true
            val u = rows.indexOfFirst { it is ChatRow.Unread }
            listState.scrollToItem((if (u >= 0) u else rows.lastIndex).coerceAtLeast(0))
        }
    }
    // New message: stick to the bottom ONLY if the user is already near it
    // (don't yank them up while reading, #5); an own send always follows.
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (!didInitialScroll) return@LaunchedEffect
        val last = messages.lastOrNull() ?: return@LaunchedEffect
        val info = listState.layoutInfo
        val nearBottom = (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
        if (last.fromMe || nearBottom) listState.animateScrollToItem(rows.lastIndex.coerceAtLeast(0))
    }
    // Keep the latest message visible when the keyboard opens (report #29).
    KeyboardScrollEffect(listState, rows.size)

    // Jump to (and briefly flash) the message a reply quotes — iOS parity (#3).
    val onTapReply: (String) -> Unit = { rid ->
        val idx = rows.indexOfFirst { r ->
            (r is ChatRow.Single && r.m.id == rid) || (r is ChatRow.Album && r.items.any { it.id == rid })
        }
        if (idx >= 0) {
            scope.launch { listState.animateScrollToItem(idx) }
            highlightId = rid
            scope.launch { kotlinx.coroutines.delay(1400); if (highlightId == rid) highlightId = null }
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary).imePadding()) {
        // Header.
        Row(
            Modifier.fillMaxWidth().background(c.bgSecondary.copy(alpha = 0.6f))
                .clickable(enabled = !isSelf) { if (isGroup) groupId?.let(onOpenGroupInfo) else peer?.let(onOpenPeerInfo) }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = c.accent,
                modifier = Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onBack),
            )
            Spacer(Modifier.width(6.dp))
            if (isGroup) {
                GroupAvatar(group, session, 28.dp)
            } else if (isSelf) {
                Icon(Icons.Filled.Bookmark, null, tint = c.accent, modifier = Modifier.size(26.dp))
            } else {
                StatusIcon(peerContact?.presence ?: UserStatus.OFFLINE, size = 26.dp)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                val title = when {
                    isGroup -> group?.name ?: stringResource(R.string.chat_group)
                    isSelf -> stringResource(R.string.chat_saved_title)
                    else -> session.contactName(peer ?: 0)
                }
                Text(title, color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    isGroup -> {
                        val n = group?.members?.size ?: 0
                        pluralStringResource(R.plurals.members, n, n)
                    }
                    isSelf -> stringResource(R.string.chat_saved_subtitle)
                    isTyping -> stringResource(R.string.chat_typing)
                    peerContact == null -> "$peer"
                    peerContact.presence == UserStatus.OFFLINE && peerContact.lastSeen != null -> stringResource(R.string.last_seen_fmt, relativeLastSeen(peerContact.lastSeen, context))
                    else -> stringResource(peerContact.presence.labelRes).lowercase()
                }
                Text(sub, color = if (isTyping) c.accent else c.textSecondary, fontSize = 12.sp)
            }
            // Chat actions live in an overflow menu (iOS parity) instead of
            // loose header icons: calls (1:1, gated on the peer's call_policy)
            // + search. Own click consumes the tap so the header's open-info
            // click doesn't also fire.
            val canCall = !isGroup && !isSelf && peer != null && peerContact?.callable != false
            Box {
                Icon(
                    Icons.Filled.MoreVert, stringResource(R.string.chat_menu_cd), tint = c.accent,
                    modifier = Modifier.size(24.dp).clip(CircleShape).clickable { chatMenu = true },
                )
                DropdownMenu(expanded = chatMenu, onDismissRequest = { chatMenu = false }) {
                    if (canCall) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.call_voice_cd), color = c.textPrimary) },
                            leadingIcon = { Icon(Icons.Filled.Call, null, tint = c.accent) },
                            onClick = { chatMenu = false; placeCall(app.rcq.android.call.CallController.Media.AUDIO) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.call_video_cd), color = c.textPrimary) },
                            leadingIcon = { Icon(Icons.Filled.Videocam, null, tint = c.accent) },
                            onClick = { chatMenu = false; placeCall(app.rcq.android.call.CallController.Media.VIDEO) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_search_hint), color = c.textPrimary) },
                        leadingIcon = { Icon(Icons.Filled.Search, null, tint = c.accent) },
                        onClick = { chatMenu = false; showSearch = true },
                    )
                    if (!isGroup && !isSelf && peer != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (chatSecure) R.string.chat_secure_off else R.string.chat_secure_on), color = c.textPrimary) },
                            leadingIcon = { Icon(Icons.Filled.Shield, null, tint = if (chatSecure) c.accent else c.textSecondary) },
                            onClick = { chatMenu = false; session.setChatSecure(peer, !chatSecure) },
                        )
                    }
                }
            }
        }

        // Pinned banner (groups). Links are tappable, and a #<uin> that's a
        // CURRENT member renders as their clickable nick (tap → their profile);
        // a #<uin> NOT in the group stays plain digits so the announcement
        // can't point the group at an outsider.
        group?.pinnedText?.takeIf { it.isNotBlank() }?.let { pin ->
            val members = group?.members ?: emptyList()
            val annotated = remember(pin, members) { buildPinnedAnnotated(pin, members, c.accent) }
            // Group-invite links in the pin are stripped from the text (above)
            // and rendered as tappable join cards (iOS parity); tapping a card
            // opens the in-app join sheet instead of a browser.
            val pinGroupIds = remember(pin) { GroupLinkParser.parseAll(pin) }
            val hasPinText = annotated.text.isNotBlank()
            val uriHandler = LocalUriHandler.current
            // The banner is ALWAYS a single compact line, so it never reflows the
            // chat. The full text + the group join-cards open in an OVERLAY sheet
            // that floats over the messages — the message list never moves
            // (founder: expanding the pin inline shoved the top message up under
            // the pin icon). A pin can list many groups; they all live in the sheet.
            var showPinSheet by remember(pin, threadKey) { mutableStateOf(false) }
            val expandable = pinGroupIds.isNotEmpty() || pin.length > 48
            Column(Modifier.fillMaxWidth().background(c.bgSecondary).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PushPin, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                    Box(Modifier.weight(1f)) {
                        if (hasPinText) {
                            ClickableText(
                                text = annotated,
                                style = TextStyle(color = c.textSecondary, fontSize = 12.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                onClick = { offset ->
                                    // A mention of yourself isn't actionable (opening
                                    // your own profile would offer add/block/report
                                    // against yourself).
                                    annotated.getStringAnnotations("MENTION", offset, offset).firstOrNull()?.let {
                                        val mUin = it.item.toInt()
                                        if (mUin != ownUin) onOpenPeerInfo(mUin)
                                        return@ClickableText
                                    }
                                    annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                                        runCatching { uriHandler.openUri(it.item) }
                                        return@ClickableText
                                    }
                                    // Tap the text → open the pin sheet (overlay).
                                    if (expandable) showPinSheet = true
                                },
                            )
                        } else {
                            // Pin is only group links — label it with the count.
                            Text(
                                if (pinGroupIds.isEmpty()) stringResource(R.string.gi_pinned)
                                else "${stringResource(R.string.gi_pinned)} · ${pinGroupIds.size}",
                                color = c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable(enabled = expandable) { showPinSheet = true },
                            )
                        }
                    }
                    if (expandable) {
                        if (pinGroupIds.isNotEmpty()) {
                            Text("${pinGroupIds.size}", color = c.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        Icon(
                            Icons.Filled.ExpandMore,
                            stringResource(R.string.gi_pinned),
                            tint = c.textSecondary,
                            modifier = Modifier.size(20.dp).clip(CircleShape).clickable { showPinSheet = true },
                        )
                    }
                }
            }
            if (showPinSheet) {
                AlertDialog(
                    onDismissRequest = { showPinSheet = false },
                    confirmButton = {
                        TextButton(onClick = { showPinSheet = false }) {
                            Text(stringResource(R.string.common_close), color = c.accent)
                        }
                    },
                    icon = { Icon(Icons.Filled.PushPin, null, tint = c.accent) },
                    title = { Text(stringResource(R.string.gi_pinned), color = c.textPrimary) },
                    text = {
                        Column {
                            if (hasPinText) {
                                ClickableText(
                                    text = annotated,
                                    style = TextStyle(color = c.textPrimary, fontSize = 14.sp),
                                    onClick = { offset ->
                                        annotated.getStringAnnotations("MENTION", offset, offset).firstOrNull()?.let {
                                            val mUin = it.item.toInt()
                                            if (mUin != ownUin) { showPinSheet = false; onOpenPeerInfo(mUin) }
                                            return@ClickableText
                                        }
                                        annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { runCatching { uriHandler.openUri(it.item) } }
                                    },
                                )
                            }
                            pinGroupIds.forEach { gid ->
                                Spacer(Modifier.height(6.dp))
                                PinnedGroupChip(session, gid, onOpenGroup = { showPinSheet = false; onOpenGroup(it) })
                            }
                        }
                    },
                    containerColor = c.bgSecondary,
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(
                rows,
                key = { row ->
                    when (row) {
                        is ChatRow.Single -> row.m.id
                        is ChatRow.Album -> "alb-${row.items.first().id}"
                        is ChatRow.DateLabel -> "date-${row.key}"
                        ChatRow.Unread -> "unread-divider"
                    }
                },
            ) { row ->
                when (row) {
                    is ChatRow.DateLabel -> DateDividerRow(row.label)
                    ChatRow.Unread -> UnreadDividerRow()
                    is ChatRow.Single -> {
                        val m = row.m
                        if (m.kind == "call") {
                            CallHistoryRow(m)
                        } else if (m.kind == "system") {
                            SystemNoticeRow(m)
                        } else {
                            MessageBubble(
                                session, m,
                                senderName = if (isGroup && !m.fromMe) authorName(m) else null,
                                onRetry = { scope.launch { runCatching { session.resend(m) } } },
                                onLongPress = { actionMsg = m },
                                onOpenGroup = onOpenGroup,
                                onViewImage = { fullscreenImage = it },
                                mentionNick = mentionNick,
                                onMentionClick = onMentionClick,
                                mentionUin = mentionUin,
                                highlighted = m.id == highlightId,
                                onTapReply = onTapReply,
                                onSenderClick = if (isGroup && !m.fromMe) ({ m.senderUin?.let { if (it != ownUin) onOpenPeerInfo(it) } }) else null,
                                onShowReactors = { whoReactedMsg = it },
                            )
                        }
                    }
                    is ChatRow.Album -> AlbumBubble(
                        session, row.items,
                        senderName = if (isGroup && !row.items.first().fromMe) authorName(row.items.first()) else null,
                        onLongPress = { actionMsg = row.items.first() },
                        onSenderClick = if (isGroup && !row.items.first().fromMe) ({ row.items.first().senderUin?.let { if (it != ownUin) onOpenPeerInfo(it) } }) else null,
                    )
                }
            }
        }
            // Jump-to-latest: a floating down-arrow shown only when the user has
            // scrolled up from the newest message (iOS/Telegram parity). Tapping
            // animates back to the bottom.
            val showJumpDown by remember { derivedStateOf { listState.canScrollForward } }
            if (showJumpDown) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 10.dp)
                        .size(40.dp).clip(CircleShape).background(c.bgSecondary)
                        .border(1.dp, c.divider, CircleShape)
                        .clickable { scope.launch { listState.animateScrollToItem(rows.lastIndex.coerceAtLeast(0)) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = c.textPrimary, modifier = Modifier.size(26.dp))
                }
            }
        }

        replyTarget?.let { rt ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                Box(Modifier.width(3.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(authorName(rt), color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(previewOf(rt, context), color = c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Filled.Close, stringResource(R.string.chat_cancel_reply), tint = c.textSecondary, modifier = Modifier.clickable { replyTarget = null }.padding(8.dp).size(18.dp))
            }
        }

        if (!canPost) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.chat_owner_only), color = c.textSecondary, fontSize = 13.sp)
            }
        } else {
            Composer(
                threadKey = threadKey,
                isGroup = isGroup,
                members = group?.members ?: emptyList(),
                ownUin = ownUin,
                accentColor = c.accent,
                onAttach = { attachMenu = true },
                onTyping = { nonBlank ->
                    if (!isGroup && !isSelf && peer != null) session.sendTyping(peer, nonBlank)
                },
                onSend = { body ->
                    val reply = replyTarget?.let { Reply(it.id, previewOf(it, context), authorName(it)) }
                    replyTarget = null
                    if (!isGroup && !isSelf && peer != null) session.sendTyping(peer, false)
                    scope.launch {
                        runCatching {
                            if (isGroup) session.sendGroupText(groupId!!, body, reply)
                            else session.sendText(peer!!, body, reply)
                        }
                    }
                },
                recording = recording,
                recElapsed = recElapsed,
                onMic = { onMic() },
                onStopVoice = { stopAndSendVoice() },
                onCancelVoice = { cancelRecording() },
            )
        }
    }

    fullscreenImage?.let { bytes ->
        FullscreenImageViewer(bytes) { fullscreenImage = null }
    }

    whoReactedMsg?.let { m ->
        // Who reacted, grouped by reaction asset. Reactions = reactorUin -> asset.
        val byAsset = remember(m.reactions) {
            m.reactions.entries.groupBy { it.value }.entries.sortedByDescending { it.value.size }
        }
        AlertDialog(
            onDismissRequest = { whoReactedMsg = null },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(R.string.reactions_who_title), color = c.textPrimary) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    byAsset.forEach { (asset, entries) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        ) {
                            EmoticonGif(asset, Modifier.size(20.dp), animate = false)
                            Spacer(Modifier.width(8.dp))
                            Text("${entries.size}", color = c.textSecondary, fontSize = 12.sp)
                        }
                        entries.forEach { (uin, _) ->
                            val name = group?.memberName(uin) ?: session.contactName(uin)
                            Text(
                                name, color = c.textPrimary, fontSize = 14.sp,
                                modifier = Modifier.padding(start = 28.dp, top = 2.dp, bottom = 2.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { whoReactedMsg = null }) {
                    Text(stringResource(R.string.common_done), color = c.accent)
                }
            },
        )
    }

    actionMsg?.let { m ->
        // "Delete for everyone" is offered for your own message, OR (in a group)
        // when you're a moderator: the owner, or a member granted the `delete`
        // cap. Recipients re-check the same rule on receipt.
        val canDeleteAll = m.fromMe ||
            (group != null && group.members.firstOrNull { it.uin == ownUin }?.canDelete(group.ownerUin) == true)
        AlertDialog(
            onDismissRequest = { actionMsg = null },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(if (m.kind == "photo") R.string.chat_a_photo else R.string.chat_a_message), color = c.textPrimary) },
            text = {
                Column {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                    ) {
                        Emoticons.reactions.forEach { asset ->
                            Box(
                                modifier = Modifier.clip(CircleShape).clickable {
                                    scope.launch { runCatching { session.sendReaction(m, asset) } }
                                    actionMsg = null
                                }.padding(4.dp),
                            ) { EmoticonGif(asset, Modifier.size(32.dp), animate = false) }
                        }
                    }
                    MessageAction(stringResource(R.string.chat_reply)) { replyTarget = m; actionMsg = null }
                    if (m.kind == "text") MessageAction(stringResource(R.string.chat_copy)) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("message", m.body))
                        actionMsg = null
                    }
                    if (m.fromMe && m.kind == "text") MessageAction(stringResource(R.string.chat_edit)) { editMsg = m; actionMsg = null }
                    if (m.fromMe && m.state == DeliveryState.FAILED) MessageAction(stringResource(R.string.chat_retry)) {
                        scope.launch { runCatching { session.resend(m) } }; actionMsg = null
                    }
                    if (canDeleteAll) MessageAction(stringResource(R.string.chat_delete_all), danger = true) {
                        scope.launch { runCatching { session.sendDeleteForEveryone(m) } }; actionMsg = null
                    }
                    MessageAction(stringResource(R.string.chat_delete_me), danger = true) { session.deleteLocal(m); actionMsg = null }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionMsg = null }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
        )
    }

    editMsg?.let { m ->
        var editText by remember(m.id) { mutableStateOf(m.body) }
        AlertDialog(
            onDismissRequest = { editMsg = null },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(R.string.chat_edit_title), color = c.textPrimary) },
            text = {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bgPrimary).padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        textStyle = TextStyle(color = c.textPrimary, fontSize = 15.sp),
                        cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val newText = editText.trim()
                    val orig = m
                    editMsg = null
                    if (newText.isNotEmpty() && newText != orig.body) scope.launch { runCatching { session.sendEdit(orig, newText) } }
                }) { Text(stringResource(R.string.common_save), color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { editMsg = null }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
        )
    }

    if (attachMenu) {
        AlertDialog(
            onDismissRequest = { attachMenu = false },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(R.string.chat_attach), color = c.textPrimary) },
            text = {
                Column {
                    MessageAction(stringResource(R.string.chat_attach_photo)) { attachMenu = false; picker.launch("image/*") }
                    MessageAction(stringResource(R.string.chat_attach_video)) { attachMenu = false; videoPicker.launch("video/*") }
                    MessageAction(stringResource(R.string.chat_attach_album)) {
                        attachMenu = false
                        albumPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }
                    MessageAction(stringResource(R.string.chat_attach_file)) { attachMenu = false; filePicker.launch("*/*") }
                    MessageAction(stringResource(R.string.chat_attach_location)) { attachMenu = false; shareLocation() }
                    MessageAction(stringResource(R.string.chat_attach_group)) { attachMenu = false; showGroupPicker = true }
                    if (isGroup) MessageAction(stringResource(R.string.poll_create)) { attachMenu = false; showPollComposer = true }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { attachMenu = false }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
        )
    }

    // Pre-send preview: tap the media to mark it a spoiler, then Send.
    pendingSend?.let { ps ->
        MediaPreviewDialog(
            pending = ps,
            onCancel = { pendingSend = null },
            onSend = { spoiler ->
                pendingSend = null
                scope.launch {
                    runCatching {
                        when (ps) {
                            is PendingSend.Photo ->
                                if (isGroup) session.sendGroupPhoto(groupId!!, ps.bytes, null, spoiler)
                                else session.sendPhoto(peer!!, ps.bytes, null, spoiler)
                            is PendingSend.Video ->
                                if (isGroup) session.sendGroupVideo(groupId!!, ps.v.bytes, ps.v.thumbB64, ps.v.durationSec, null, spoiler)
                                else session.sendVideo(peer!!, ps.v.bytes, ps.v.thumbB64, ps.v.durationSec, null, spoiler)
                        }
                    }
                }
            },
        )
    }

    // Share a group invite into this chat: pick one of your groups, send its
    // canonical link as a text message (renders as a join card on both ends).
    if (showGroupPicker) {
        AlertDialog(
            onDismissRequest = { showGroupPicker = false },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(R.string.chat_attach_group), color = c.textPrimary) },
            text = {
                if (groups.isEmpty()) {
                    Text(stringResource(R.string.group_invite_none), color = c.textSecondary)
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        groups.forEach { g ->
                            MessageAction(g.name) {
                                showGroupPicker = false
                                val url = GroupLinkParser.canonicalUrl(g.id)
                                scope.launch {
                                    runCatching {
                                        if (isGroup) session.sendGroupText(groupId!!, url) else session.sendText(peer!!, url)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showGroupPicker = false }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
        )
    }

    if (showPollComposer && groupId != null) {
        PollComposerDialog(
            onDismiss = { showPollComposer = false },
            onCreate = { q, opts, single, anon ->
                showPollComposer = false
                scope.launch { runCatching { session.sendPoll(groupId, q, opts, single, anon) } }
            },
        )
    }

    // In-chat message search — stacks over the thread (it's a later child of
    // the host Box). Tapping a hit scrolls the list to that message.
    if (showSearch) {
        BackHandler { showSearch = false }
        InChatSearchOverlay(
            messages = messages,
            onClose = { showSearch = false },
            onSelect = { msg ->
                showSearch = false
                scope.launch {
                    val idx = messages.indexOfFirst { it.id == msg.id }
                    if (idx >= 0) listState.animateScrollToItem(idx)
                }
            },
        )
    }
}

@Composable
private fun Composer(
    threadKey: String,
    isGroup: Boolean,
    members: List<app.rcq.android.model.GroupMember>,
    ownUin: Int,
    accentColor: Color,
    onAttach: () -> Unit,
    onTyping: (Boolean) -> Unit,
    onSend: (String) -> Unit,
    recording: Boolean,
    recElapsed: Int,
    onMic: () -> Unit,
    onStopVoice: () -> Unit,
    onCancelVoice: () -> Unit,
) {
    val c = RcqTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    var showEmoji by remember { mutableStateOf(false) }
    // The draft lives HERE (not in ChatScreen) so a keystroke recomposes only
    // the composer, not the header + message list. Seeded from / persisted to
    // the process-lifetime ChatDrafts map, keyed by thread.
    var draft by remember(threadKey) { mutableStateOf(ChatDrafts.byThread[threadKey] ?: "") }
    val setDraft: (String) -> Unit = { v ->
        draft = v
        if (v.isBlank()) ChatDrafts.byThread.remove(threadKey) else ChatDrafts.byThread[threadKey] = v
        onTyping(v.isNotBlank())
    }
    // Hold-to-record: holding the mic records, releasing sends, sliding up past
    // the threshold cancels (WhatsApp/Telegram-style). The trailing button
    // stays mounted across the `recording` state so the pointer gesture isn't
    // torn out from under the finger mid-hold.
    var cancelArmed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        // @-mention autocomplete (groups): an "@partial" at the input tail pops a
        // member picker; tapping inserts "@nick ". iOS parity (activeMentionQuery).
        if (isGroup) {
            val q: Pair<Int, String>? = run {
                var i = draft.length
                while (i > 0) {
                    val ch = draft[i - 1]
                    if (ch == '@') {
                        val partial = draft.substring(i)
                        return@run if (partial.isNotEmpty()) (i - 1) to partial else null
                    }
                    if (ch.isWhitespace()) return@run null
                    i--
                }
                null
            }
            val candidates = q?.let { (_, partial) ->
                val p = partial.lowercase()
                members.filter { it.uin != ownUin && it.nickname.lowercase().contains(p) }.take(8)
            } ?: emptyList()
            if (q != null && candidates.isNotEmpty()) {
                val (mStart, mPartial) = q
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                        .heightIn(max = 220.dp).clip(RoundedCornerShape(12.dp)).background(c.bgSecondary),
                ) {
                    LazyColumn {
                        items(candidates, key = { it.uin }) { mbr ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    setDraft(
                                        draft.substring(0, mStart) + "@" + mbr.nickname + " " +
                                            draft.substring(mStart + 1 + mPartial.length),
                                    )
                                }.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(mbr.nickname, color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Text("#${mbr.uin}", color = c.textMono, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
        if (showEmoji && !recording) EmoticonPanel(onPick = { setDraft(draft + it) })
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (recording) {
                Row(
                    Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp))
                        .background(c.bgSecondary).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFE5484D)))
                    Text(formatDuration(recElapsed), color = c.textPrimary, fontSize = 15.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(if (cancelArmed) R.string.chat_voice_release_cancel else R.string.chat_voice_slide_cancel),
                        color = if (cancelArmed) Color(0xFFE5484D) else c.textSecondary, fontSize = 12.sp,
                    )
                }
            } else {
                Icon(
                    Icons.Filled.AddPhotoAlternate, stringResource(R.string.chat_attach), tint = c.textSecondary,
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onAttach).padding(8.dp),
                )
                Icon(
                    Icons.Filled.Mood, stringResource(R.string.chat_emoticons), tint = if (showEmoji) c.accent else c.textSecondary,
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable {
                        showEmoji = !showEmoji
                        if (showEmoji) keyboard?.hide()
                    }.padding(8.dp),
                )
                Box(
                    Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(c.bgSecondary).padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (draft.isEmpty()) Text(stringResource(R.string.chat_input_hint), color = c.textSecondary, fontSize = 15.sp)
                    // Plain Compose field. Previously a native EditText (so emoticon
                    // :codes: rendered as inline GIFs while typing), but that
                    // AndroidView↔IME interop froze the app — up to a 20s ANR ("RCQ
                    // isn't responding") — when tapping to type on some devices.
                    // Codes now show as ":code:" text in the composer and still
                    // render as GIFs once the message is sent/received.
                    BasicTextField(
                        value = draft,
                        onValueChange = setDraft,
                        textStyle = androidx.compose.ui.text.TextStyle(color = c.textPrimary, fontSize = 15.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) showEmoji = false },
                    )
                }
            }
            val canSend = draft.isNotBlank() && !recording
            val trailingBg = when {
                recording && cancelArmed -> Color(0xFFE5484D)
                canSend || recording -> c.accent
                else -> c.bgSecondary
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(trailingBg)
                    .then(
                        if (canSend) {
                            Modifier.clickable {
                                val body = draft.trim()
                                draft = ""
                                ChatDrafts.byThread.remove(threadKey)
                                onSend(body)
                            }
                        } else {
                            // Hold the mic to record; release sends, slide up cancels.
                            Modifier.pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val cancelPx = 80.dp.toPx()
                                    cancelArmed = false
                                    onMic()
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        if (!change.pressed) { change.consume(); break }
                                        cancelArmed = down.position.y - change.position.y > cancelPx
                                    }
                                    if (cancelArmed) onCancelVoice() else onStopVoice()
                                    cancelArmed = false
                                }
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (canSend) Icons.AutoMirrored.Filled.Send else Icons.Filled.Mic,
                    stringResource(if (canSend) R.string.chat_send else R.string.chat_record_voice),
                    tint = if (canSend || recording) Color.White else c.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** m:ss for a duration in seconds. */
private fun formatDuration(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

@Composable
private fun MessageAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (danger) Color(0xFFE5484D) else RcqTheme.colors.textPrimary,
        fontSize = 16.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
    )
}

private fun previewOf(m: ChatMessage, context: android.content.Context): String = when (m.kind) {
    "photo" -> context.getString(R.string.chat_prev_photo)
    "file" -> m.fileName ?: context.getString(R.string.chat_prev_file)
    "voice" -> context.getString(R.string.chat_prev_voice)
    "video" -> context.getString(R.string.chat_prev_video)
    "location" -> context.getString(R.string.chat_prev_location)
    "poll" -> app.rcq.android.model.PollContent.fromJson(m.body)?.question?.take(100)
        ?: context.getString(R.string.poll_create)
    else -> m.body.take(100)
}

/** Scrolls [listState] to the last item when the soft keyboard opens, so the
 *  latest message stays visible above the composer instead of being hidden as
 *  the chat area shrinks (report #29). Isolated in its own composable so reading
 *  the per-frame IME inset doesn't recompose the whole ChatScreen. */
@Composable
private fun KeyboardScrollEffect(
    listState: androidx.compose.foundation.lazy.LazyListState,
    itemCount: Int,
) {
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(imeVisible) {
        if (!imeVisible || itemCount == 0) return@LaunchedEffect
        // Keep the newest messages above the keyboard — but JUMP, don't animate:
        // an animated scroll ran concurrently with the imePadding() inset
        // animation, so every frame both squeezed AND scrolled the list,
        // composing/decoding rows on the way down. On weaker devices that stutter
        // read as "tapping the field freezes everything". And only follow down
        // when the latest messages are already on screen, so tapping the composer
        // while reading history no longer yanks you to the bottom.
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= itemCount - 2) listState.scrollToItem(itemCount - 1)
    }
}

/** Centered call-summary line (kind == "call"): a phone glyph + the localized
 *  "Voice call · 1:23" / "Missed call" text logged by [CallController]. */
@Composable
private fun CallHistoryRow(m: ChatMessage) {
    val c = RcqTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Call, null, tint = c.textSecondary, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(m.body, color = c.textSecondary, fontSize = 12.sp)
    }
}

/** Centered system notice (kind == "system"), e.g. "X took a screenshot". */
@Composable
private fun SystemNoticeRow(m: ChatMessage) {
    val c = RcqTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Shield, null, tint = c.textSecondary, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(m.body, color = c.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

/** Parse a chat text body that is exactly a group-invite URL into a group id.
 *  Matches iOS GroupLinkParser: `rcq://group/<id>` (in-app tap) or
 *  `https://rcq.app/g/<id>` (the shareable / paste form). */
internal object GroupLinkParser {
    fun parse(body: String): Int? {
        val t = body.trim()
        if (t.isEmpty() || t.contains(' ') || t.contains('\n')) return null
        val uri = runCatching { android.net.Uri.parse(t) }.getOrNull() ?: return null
        if (uri.scheme == "rcq" && uri.host == "group") {
            return uri.lastPathSegment?.toIntOrNull()?.takeIf { it > 0 }
        }
        if ((uri.scheme == "https" || uri.scheme == "http") && uri.host == "rcq.app") {
            val segs = uri.pathSegments
            if (segs.size >= 2 && segs[0] == "g") return segs[1].toIntOrNull()?.takeIf { it > 0 }
        }
        return null
    }

    fun canonicalUrl(id: Int): String = "https://rcq.app/g/$id"

    /** Every group-invite link in [text], in document order (iOS
     *  GroupLinkParser.parseAll parity) — used to render pin cards. Matches
     *  both the shareable https form and the rcq:// deep-link form. */
    fun parseAll(text: String): List<Int> {
        val re = Regex("(?:https?://rcq\\.app/g/|rcq://group/)(\\d+)", RegexOption.IGNORE_CASE)
        return re.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull()?.takeIf { id -> id > 0 } }.distinct().toList()
    }
}

/** A shared group-invite link rendered as a join card (iOS GroupLinkBubble
 *  parity): avatar + name + member count + closed badge; tap opens a join
 *  dialog, and joining jumps into the group chat. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupLinkBubble(session: Session, groupId: Int, onOpenGroup: (Int) -> Unit, onLongPress: () -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    var showJoin by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    val preview by produceState<app.rcq.android.net.RcqApi.GroupPreviewOut?>(initialValue = null, groupId) {
        value = session.previewGroup(groupId)
    }
    val p = preview
    // Minimal RcqGroup so the shared GroupAvatar can render the real avatar
    // (or fall back to the generic glyph for groups without one).
    val avatarGroup = remember(p) {
        p?.let {
            app.rcq.android.model.RcqGroup(
                id = it.id, name = it.name ?: "", ownerUin = it.owner_uin,
                isClosed = it.is_closed, avatarMediaId = it.avatar_media_id, avatarMediaKey = it.avatar_media_key,
            )
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .width(260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.bubbleOther)
            .combinedClickable(onClick = { if (p != null) showJoin = true }, onLongClick = onLongPress)
            .padding(10.dp),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            GroupAvatar(avatarGroup, session, 52.dp)
            if (p?.is_closed == true) {
                Box(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(3.dp)) {
                    Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(11.dp))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                p?.name ?: stringResource(R.string.group_invite_loading),
                color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (p != null) {
                Text(pluralStringResource(R.plurals.members, p.member_count, p.member_count), color = c.textSecondary, fontSize = 12.sp)
                Text(
                    stringResource(if (p.is_closed) R.string.group_invite_closed else R.string.group_invite_tap_join),
                    color = if (p.is_closed) Color(0xFFE5484D) else c.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                )
            } else {
                Text(stringResource(R.string.group_invite_link), color = c.textSecondary, fontSize = 12.sp)
            }
        }
    }
    if (showJoin && p != null) {
        AlertDialog(
            onDismissRequest = { if (!joining) showJoin = false },
            containerColor = c.bgSecondary,
            title = { Text(p.name ?: stringResource(R.string.group_invite_title), color = c.textPrimary) },
            text = { Text(pluralStringResource(R.plurals.members, p.member_count, p.member_count), color = c.textSecondary) },
            confirmButton = {
                TextButton(enabled = !joining, onClick = {
                    joining = true
                    scope.launch {
                        val g = session.joinGroup(groupId)
                        joining = false
                        showJoin = false
                        if (g != null) onOpenGroup(groupId)
                    }
                }) { Text(stringResource(R.string.group_invite_join), color = c.accent) }
            },
            dismissButton = { TextButton(enabled = !joining, onClick = { showJoin = false }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
        )
    }
}

/** Compact join card for a group-invite link inside a pinned announcement
 *  (iOS PinnedGroupChip parity): avatar + name + member count. Tap opens the
 *  in-app join sheet (NOT a browser); joining jumps into that group. */
@Composable
private fun PinnedGroupChip(session: Session, groupId: Int, onOpenGroup: (Int) -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    var showJoin by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    val preview by produceState<app.rcq.android.net.RcqApi.GroupPreviewOut?>(initialValue = null, groupId) {
        value = session.previewGroup(groupId)
    }
    val p = preview
    val avatarGroup = remember(p) {
        p?.let {
            app.rcq.android.model.RcqGroup(
                id = it.id, name = it.name ?: "", ownerUin = it.owner_uin,
                isClosed = it.is_closed, avatarMediaId = it.avatar_media_id, avatarMediaKey = it.avatar_media_key,
            )
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.bgPrimary)
            .clickable(enabled = p != null) { showJoin = true }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            GroupAvatar(avatarGroup, session, 30.dp)
            if (p?.is_closed == true) {
                Box(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(2.dp)) {
                    Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(9.dp))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                p?.name ?: stringResource(R.string.group_invite_loading),
                color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (p != null) Text(
                pluralStringResource(R.plurals.members, p.member_count, p.member_count),
                color = c.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            )
        }
    }
    if (showJoin && p != null) {
        AlertDialog(
            onDismissRequest = { if (!joining) showJoin = false },
            containerColor = c.bgSecondary,
            title = { Text(p.name ?: stringResource(R.string.group_invite_title), color = c.textPrimary) },
            text = { Text(pluralStringResource(R.plurals.members, p.member_count, p.member_count), color = c.textSecondary) },
            confirmButton = {
                TextButton(enabled = !joining, onClick = {
                    joining = true
                    scope.launch {
                        val g = session.joinGroup(groupId)
                        joining = false; showJoin = false
                        if (g != null) onOpenGroup(groupId)
                    }
                }) { Text(stringResource(R.string.group_invite_join), color = c.accent) }
            },
            dismissButton = { TextButton(enabled = !joining, onClick = { showJoin = false }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
        )
    }
}

/** A picked photo or video waiting in the pre-send preview. */
private sealed interface PendingSend {
    data class Photo(val bytes: ByteArray) : PendingSend
    data class Video(val v: PickedVideo) : PendingSend
}

/** Pre-send preview for a picked photo/video: tap the thumbnail to toggle a
 *  spoiler blur, then Send. [onSend] receives the chosen spoiler flag. */
@Composable
private fun MediaPreviewDialog(pending: PendingSend, onCancel: () -> Unit, onSend: (Boolean) -> Unit) {
    val c = RcqTheme.colors
    var spoiler by remember { mutableStateOf(false) }
    val isVideo = pending is PendingSend.Video
    val base = remember(pending) {
        when (pending) {
            is PendingSend.Photo -> runCatching { BitmapFactory.decodeByteArray(pending.bytes, 0, pending.bytes.size) }.getOrNull()
            is PendingSend.Video -> runCatching {
                val b = android.util.Base64.decode(pending.v.thumbB64, android.util.Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(b, 0, b.size)
            }.getOrNull()
        }
    }
    val shown = remember(base, spoiler) {
        base?.let { (if (spoiler) blurForSpoiler(it) else it).asImageBitmap() }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = c.bgSecondary,
        title = { Text(stringResource(R.string.chat_media_preview_title), color = c.textPrimary) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.bgPrimary)
                        .clickable { spoiler = !spoiler },
                    contentAlignment = Alignment.Center,
                ) {
                    if (shown != null) Image(bitmap = shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (isVideo && !spoiler) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                    }
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(6.dp),
                    ) {
                        Icon(if (spoiler) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(if (spoiler) R.string.chat_spoiler_on_hint else R.string.chat_spoiler_off_hint),
                    color = c.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSend(spoiler) }) { Text(stringResource(R.string.chat_send), color = c.accent) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
    )
}

/** A chat-list render unit: a normal single message, or a collapsed media
 *  album (2+ consecutive photo/video messages that shared an albumId at send). */
private sealed interface ChatRow {
    data class Single(val m: ChatMessage) : ChatRow
    data class Album(val id: String, val items: List<ChatMessage>) : ChatRow
    /** A day separator between messages of different calendar dates (iOS parity). */
    data class DateLabel(val label: String, val key: Long) : ChatRow
    /** The "unread messages" marker, placed before the first unread message. */
    object Unread : ChatRow
}

/** Pinned text -> AnnotatedString with tappable URLs + `#<uin>` member
 *  mentions. A `#<uin>` whose UIN is a CURRENT group member becomes that
 *  member's nickname, tagged "MENTION"=uin (tap -> their profile); a `#<uin>`
 *  not in the group stays inert plain digits, so the pin can't point the
 *  group at an outsider. URLs are tagged "URL"=url. */
private fun buildPinnedAnnotated(
    text: String,
    members: List<app.rcq.android.model.GroupMember>,
    accent: androidx.compose.ui.graphics.Color,
): AnnotatedString {
    val nickByUin = members.associate { it.uin to it.nickname }
    // Matches both `#<uin>` and `UIN <uin>` (the format used in real pins).
    val mentionRe = Regex("(?:#|UIN\\s+)(\\d{3,})", RegexOption.IGNORE_CASE)
    return buildAnnotatedString {
        var cursor = 0
        for (m in mentionRe.findAll(text)) {
            if (m.range.first > cursor) appendWithUrls(text.substring(cursor, m.range.first), accent)
            val uin = m.groupValues[1].toIntOrNull()
            val nick = uin?.let { nickByUin[it] }
            if (uin != null && nick != null) {
                pushStringAnnotation("MENTION", uin.toString())
                withStyle(SpanStyle(color = accent)) { append(nick) }
                pop()
            } else {
                append(m.value)  // inert "#digits"
            }
            cursor = m.range.last + 1
        }
        if (cursor < text.length) appendWithUrls(text.substring(cursor), accent)
    }
}

/** Append a plain segment, turning http(s) URLs into tappable "URL"-tagged
 *  spans. */
private fun AnnotatedString.Builder.appendWithUrls(segment: String, accent: androidx.compose.ui.graphics.Color) {
    val urlRe = Regex("https?://\\S+")
    var cursor = 0
    for (m in urlRe.findAll(segment)) {
        if (m.range.first > cursor) append(segment.substring(cursor, m.range.first))
        if (GroupLinkParser.parse(m.value) != null) {
            // A group-invite link is rendered as a tappable card under the pin
            // (iOS parity) and opens the in-app join sheet — strip the raw URL
            // from the text so it doesn't show AND doesn't open a browser.
        } else {
            pushStringAnnotation("URL", m.value)
            withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(m.value) }
            pop()
        }
        cursor = m.range.last + 1
    }
    if (cursor < segment.length) append(segment.substring(cursor))
}

/** Day bucket (year*1000 + day-of-year) for grouping messages into date sections. */
private fun dayKeyOf(ts: Long): Long {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = ts
    return c.get(java.util.Calendar.YEAR) * 1000L + c.get(java.util.Calendar.DAY_OF_YEAR)
}

/** Human date label for a divider (iOS DateDivider parity: "EEE, d MMM"). */
private fun dayLabelOf(ts: Long): String =
    java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale.getDefault()).format(java.util.Date(ts))

/** Build the rendered row list: album-collapse (iOS parity) + date dividers
 *  between calendar days + a single "unread messages" divider before the first
 *  unread message ([firstUnreadIndex] = message index, or -1 for none). */
private fun buildChatRows(msgs: List<ChatMessage>, firstUnreadIndex: Int): List<ChatRow> {
    val out = ArrayList<ChatRow>(msgs.size + 8)
    var lastDay = Long.MIN_VALUE
    var unreadDone = firstUnreadIndex < 0
    var i = 0
    while (i < msgs.size) {
        val m = msgs[i]
        val day = dayKeyOf(m.sentAt)
        if (day != lastDay) { out.add(ChatRow.DateLabel(dayLabelOf(m.sentAt), day)); lastDay = day }
        if (!unreadDone && i == firstUnreadIndex) { out.add(ChatRow.Unread); unreadDone = true }

        val alb = m.albumId
        if (alb != null && (m.kind == "photo" || m.kind == "video")) {
            var j = i
            val group = ArrayList<ChatMessage>()
            while (j < msgs.size) {
                // Don't let an album swallow the unread boundary or cross a day.
                if (!unreadDone && j == firstUnreadIndex && j != i) break
                val n = msgs[j]
                if (n.albumId == alb && (n.kind == "photo" || n.kind == "video") && n.fromMe == m.fromMe && n.senderUin == m.senderUin && dayKeyOf(n.sentAt) == day) {
                    group.add(n); j++
                } else break
            }
            if (group.size >= 2) { out.add(ChatRow.Album(alb, group)); i = j; continue }
        }
        out.add(ChatRow.Single(m)); i++
    }
    return out
}

/** A centered day separator between messages of different dates. */
@Composable
private fun DateDividerRow(label: String) {
    val c = RcqTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).height(1.dp).background(c.divider))
        Text(label, color = c.textSecondary, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(horizontal = 8.dp))
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).height(1.dp).background(c.divider))
    }
}

/** The "Unread messages" divider, accent-tinted so it reads as a marker. */
@Composable
private fun UnreadDividerRow() {
    val c = RcqTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).height(1.dp).background(c.accent.copy(alpha = 0.5f)))
        Text(stringResource(R.string.chat_unread_divider), color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).height(1.dp).background(c.accent.copy(alpha = 0.5f)))
    }
}

/** A collapsed media album: the tile grid + count pill, an optional caption,
 *  and a time/state footer. Long-press acts on the album's first message. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumBubble(session: Session, items: List<ChatMessage>, senderName: String?, onLongPress: () -> Unit, onSenderClick: (() -> Unit)? = null) {
    val c = RcqTheme.colors
    val first = items.first()
    val last = items.last()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (first.fromMe) Alignment.End else Alignment.Start) {
        if (senderName != null) {
            Text(
                senderName, color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 1.dp)
                    .then(if (onSenderClick != null) Modifier.clickable { onSenderClick() } else Modifier),
            )
        }
        AlbumGrid(session, items, onLongPress)
        items.firstOrNull { it.body.isNotEmpty() }?.let { cap ->
            EmoticonText(
                cap.body, color = c.textPrimary, fontSize = 14.sp,
                modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(10.dp)).background(if (first.fromMe) c.bubbleSelf else c.bubbleOther).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(formatTime(last.sentAt), color = c.textSecondary, fontSize = 10.sp)
            if (first.fromMe) DeliveryTicks(last.state)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumGrid(session: Session, items: List<ChatMessage>, onLongPress: () -> Unit) {
    val maxW = 240.dp
    val sp = 3.dp
    val half = (maxW - sp) / 2f
    val count = minOf(items.size, 4)
    Box {
        val gridMod = Modifier.clip(RoundedCornerShape(12.dp))
        when (count) {
            2 -> Row(gridMod, horizontalArrangement = Arrangement.spacedBy(sp)) {
                AlbumTile(session, items[0], half, maxW * 0.5f, onLongPress)
                AlbumTile(session, items[1], half, maxW * 0.5f, onLongPress)
            }
            3 -> Column(gridMod, verticalArrangement = Arrangement.spacedBy(sp)) {
                AlbumTile(session, items[0], maxW, maxW * 0.55f, onLongPress)
                Row(horizontalArrangement = Arrangement.spacedBy(sp)) {
                    AlbumTile(session, items[1], half, maxW * 0.385f, onLongPress)
                    AlbumTile(session, items[2], half, maxW * 0.385f, onLongPress)
                }
            }
            else -> Column(gridMod, verticalArrangement = Arrangement.spacedBy(sp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(sp)) {
                    AlbumTile(session, items[0], half, maxW * 0.5f, onLongPress)
                    AlbumTile(session, items[1], half, maxW * 0.5f, onLongPress)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(sp)) {
                    AlbumTile(session, items[2], half, maxW * 0.5f, onLongPress)
                    Box(contentAlignment = Alignment.Center) {
                        AlbumTile(session, items[3], half, maxW * 0.5f, onLongPress)
                        if (items.size > 4) {
                            Box(
                                Modifier.size(half, maxW * 0.5f).background(Color.Black.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center,
                            ) { Text("+${items.size - 4}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 1.dp),
        ) { Text("${items.size}", color = Color.White, fontSize = 11.sp) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTile(session: Session, m: ChatMessage, w: Dp, h: Dp, onLongPress: () -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isVideo = m.kind == "video"
    val photo by produceState<ByteArray?>(initialValue = null, m.id) {
        value = if (!isVideo && m.mediaId != null && m.mediaKey != null) session.fetchImage(m.mediaId, m.mediaKey) else null
    }
    val bmp = remember(photo, m.id) {
        if (isVideo) {
            m.thumbB64?.takeIf { it.isNotEmpty() }?.let { runCatching { val b = android.util.Base64.decode(it, android.util.Base64.NO_WRAP); BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() }
        } else {
            photo?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
        }
    }
    Box(
        Modifier.size(w, h).background(c.bgSecondary).combinedClickable(
            onClick = {
                val mid = m.mediaId; val key = m.mediaKey
                if (mid != null && key != null) scope.launch {
                    val bytes = session.fetchImage(mid, key)
                    if (bytes != null) {
                        if (isVideo) openFile(context, bytes, "video-${m.id}.mp4", "video/mp4")
                        else openFile(context, bytes, "photo-${m.id}.jpg", "image/jpeg")
                    }
                }
            },
            onLongClick = onLongPress,
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) Image(bitmap = bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else CircularProgressIndicator(color = c.accent, modifier = Modifier.size(16.dp))
        if (isVideo) Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(session: Session, m: ChatMessage, senderName: String?, onRetry: () -> Unit, onLongPress: () -> Unit, onOpenGroup: (Int) -> Unit = {}, onViewImage: (ByteArray) -> Unit = {}, mentionNick: ((Int) -> String?)? = null, onMentionClick: ((Int) -> Unit)? = null, mentionUin: ((String) -> Int?)? = null, highlighted: Boolean = false, onTapReply: ((String) -> Unit)? = null, onSenderClick: (() -> Unit)? = null, onShowReactors: (ChatMessage) -> Unit = {}) {
    val c = RcqTheme.colors
    val failed = m.state == DeliveryState.FAILED
    // Cap a bubble so a long message leaves a gap to the far edge — keeps the
    // L/R alignment (mine vs theirs) readable, not just the colour (tester #7).
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.78f).dp
    // A text body that is just a group-invite URL renders as a join card.
    val groupLinkId = if (m.kind == "text") GroupLinkParser.parse(m.body) else null
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) c.accent.copy(alpha = 0.24f) else Color.Transparent)
            .padding(vertical = 3.dp, horizontal = 2.dp),
        horizontalAlignment = if (m.fromMe) Alignment.End else Alignment.Start,
    ) {
        if (senderName != null) {
            Text(
                senderName, color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 1.dp)
                    .then(if (onSenderClick != null) Modifier.clickable { onSenderClick() } else Modifier),
            )
        }
        if (groupLinkId != null) {
            GroupLinkBubble(session, groupLinkId, onOpenGroup, onLongPress)
        } else if (m.kind == "photo") {
            PhotoBubble(session, m, onLongPress, onViewImage)
            if (m.body.isNotEmpty()) {
                EmoticonText(
                    m.body, color = c.textPrimary, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(10.dp)).background(if (m.fromMe) c.bubbleSelf else c.bubbleOther).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        } else if (m.kind == "poll") {
            PollBubble(session, m, onLongPress)
        } else if (m.kind == "file") {
            FileBubble(session, m, onLongPress)
        } else if (m.kind == "video") {
            VideoBubble(session, m, onLongPress)
            if (m.body.isNotEmpty()) {
                EmoticonText(
                    m.body, color = c.textPrimary, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(10.dp)).background(if (m.fromMe) c.bubbleSelf else c.bubbleOther).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        } else if (m.kind == "voice") {
            VoiceBubble(session, m, onLongPress)
        } else if (m.kind == "location") {
            LocationBubble(m, onLongPress)
        } else {
            Column(
                Modifier
                    .widthIn(max = maxW)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (m.fromMe) c.bubbleSelf else c.bubbleOther)
                    .combinedClickable(onClick = { if (failed) onRetry() }, onLongClick = onLongPress)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (m.replyToSnippet != null) {
                    val tappable = m.replyToId != null && onTapReply != null
                    Column(
                        Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(6.dp)).background(c.accent.copy(alpha = 0.14f))
                            .then(if (tappable) Modifier.clickable { onTapReply!!.invoke(m.replyToId!!) } else Modifier)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(m.replyToAuthor.orEmpty(), color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text(m.replyToSnippet, color = c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                EmoticonText(m.body, color = c.textPrimary, fontSize = 15.sp, lineHeight = 19.sp, mentionNick = mentionNick, onMentionClick = onMentionClick, mentionUin = mentionUin)
            }
        }
        if (m.reactions.isNotEmpty()) {
            val reactScope = rememberCoroutineScope()
            val me = session.uin
            // Group by asset -> count (reactions is reactorUin -> asset).
            val grouped = remember(m.reactions) {
                m.reactions.values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
            ) {
                grouped.forEach { (asset, count) ->
                    ReactionChip(
                        asset = asset,
                        count = count,
                        mine = me != null && m.reactions[me] == asset,
                        onClick = { reactScope.launch { runCatching { session.sendReaction(m, asset) } } },
                        onLongClick = { onShowReactors(m) },
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(formatTime(m.sentAt), color = c.textSecondary, fontSize = 10.sp)
            if (m.edited) Text(stringResource(R.string.chat_edited), color = c.textSecondary, fontSize = 10.sp)
            if (m.fromMe) {
                if (failed) Text(stringResource(R.string.chat_failed_retry), color = Color(0xFFE5484D), fontSize = 10.sp, modifier = Modifier.clickable(onClick = onRetry))
                else DeliveryTicks(m.state)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoBubble(session: Session, m: ChatMessage, onLongPress: () -> Unit, onView: (ByteArray) -> Unit = {}) {
    val c = RcqTheme.colors
    var revealed by remember(m.id) { mutableStateOf(false) }
    val hidden = m.spoiler && !revealed
    val bytes by produceState<ByteArray?>(initialValue = null, m.mediaId) {
        value = if (m.mediaId != null && m.mediaKey != null) session.fetchImage(m.mediaId, m.mediaKey) else null
    }
    val b = bytes
    Box(
        Modifier
            .size(220.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.bgSecondary)
            .combinedClickable(onClick = { if (hidden) revealed = true else b?.let(onView) }, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        when {
            b == null -> CircularProgressIndicator(color = c.accent, modifier = Modifier.size(22.dp))
            // Spoiler: render a heavily blurred copy until the viewer taps it.
            // Decoded + blurred off the main thread (the full-res decode used to
            // block the UI thread during composition).
            hidden -> {
                val blurred by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, b) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        runCatching { decodeSampled(b, 360)?.let { blurForSpoiler(it).asImageBitmap() } }.getOrNull()
                    }
                }
                blurred?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                SpoilerOverlay()
            }
            // Animated GIF (same "photo" media path iOS uses, gated by magic
            // bytes) — render it animated instead of a frozen first frame.
            b.isGif() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P ->
                AnimatedGif(b, Modifier.fillMaxSize())
            else -> {
                // Downsampled + decoded off the main thread — a full-res JPEG
                // decode here stalled the UI thread when the row scrolled in
                // (notably the keyboard-open auto-scroll → the "tap freezes" bug).
                val image = rememberSampledBitmap(b)
                if (image != null) Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else CircularProgressIndicator(color = c.accent, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** Centered "tap to view" chip drawn over a blurred spoiler thumbnail. */
@Composable
private fun SpoilerOverlay() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Icon(Icons.Filled.VisibilityOff, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.chat_spoiler_reveal), color = Color.White, fontSize = 12.sp)
    }
}

/** Heavy pixelate-blur for a spoiler: downscale to a few px then back up. Used
 *  instead of Modifier.blur, which is a no-op below API 31 and would otherwise
 *  leak the original image on older devices (minSdk is 26). */
private fun blurForSpoiler(src: Bitmap): Bitmap {
    val w = src.width.coerceAtLeast(1)
    val h = src.height.coerceAtLeast(1)
    val scale = 18f / maxOf(w, h).toFloat()
    val sw = (w * scale).toInt().coerceAtLeast(1)
    val sh = (h * scale).toInt().coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(src, sw, sh, true)
    // Upscale with filtering for a smooth smear; cap the size so big photos
    // don't allocate a huge bitmap just to be blurred.
    val outW = w.coerceAtMost(360)
    val outH = (outW * h / w).coerceAtLeast(1)
    return Bitmap.createScaledBitmap(small, outW, outH, true)
}

/** Fullscreen photo viewer (tester #10): tap anywhere or the X to close, pinch
 *  to zoom, drag while zoomed to pan. */
@Composable
private fun FullscreenImageViewer(bytes: ByteArray, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transform = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            offset = if (scale > 1f) offset + panChange else Offset.Zero
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            if (bytes.isGif() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                AnimatedGif(bytes, Modifier.fillMaxWidth())
            } else {
                // Decode off the main thread, bounded to 2560px (ample for the
                // 5x pinch-zoom) so opening a big photo never stalls the UI.
                val image = rememberSampledBitmap(bytes, maxPx = 2560)
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .transformable(transform)
                            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                    )
                }
            }
            Icon(
                Icons.Filled.Close,
                stringResource(R.string.common_close),
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(28.dp).clickable(onClick = onDismiss),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileBubble(session: Session, m: ChatMessage, onLongPress: () -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (m.fromMe) c.bubbleSelf else c.bubbleOther)
            .combinedClickable(
                onClick = {
                    val mid = m.mediaId; val key = m.mediaKey
                    if (mid != null && key != null) scope.launch {
                        val bytes = session.fetchImage(mid, key)
                        if (bytes != null) openFile(context, bytes, m.fileName ?: "file", m.fileMime ?: "application/octet-stream")
                    }
                },
                onLongClick = onLongPress,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(Icons.Filled.Description, null, tint = c.accent, modifier = Modifier.size(24.dp))
        Column {
            Text(m.fileName ?: "file", color = c.textPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatFileSize(m.fileSize ?: 0L), color = c.textSecondary, fontSize = 11.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceBubble(session: Session, m: ChatMessage, onLongPress: () -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf(false) }
    val player = remember { android.media.MediaPlayer() }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (m.fromMe) c.bubbleSelf else c.bubbleOther)
            .combinedClickable(
                onClick = {
                    if (playing) {
                        runCatching { player.pause() }
                        playing = false
                    } else {
                        val mid = m.mediaId; val key = m.mediaKey
                        if (mid != null && key != null) scope.launch {
                            val bytes = session.fetchImage(mid, key) ?: return@launch
                            runCatching {
                                val f = java.io.File(context.cacheDir, "voice-${m.id}.m4a")
                                if (!f.exists() || f.length() == 0L) f.writeBytes(bytes)
                                player.reset()
                                player.setDataSource(f.absolutePath)
                                player.setOnCompletionListener { playing = false }
                                player.prepare()
                                player.start()
                                playing = true
                            }
                        }
                    }
                },
                onLongClick = onLongPress,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(R.string.chat_play_voice), tint = c.accent, modifier = Modifier.size(26.dp),
        )
        Text(formatDuration(m.durationSec ?: 0), color = c.textPrimary, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoBubble(session: Session, m: ChatMessage, onLongPress: () -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revealed by remember(m.id) { mutableStateOf(false) }
    val hidden = m.spoiler && !revealed
    val thumbBmp = remember(m.id) {
        m.thumbB64?.takeIf { it.isNotEmpty() }?.let {
            runCatching {
                val b = android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(b, 0, b.size)
            }.getOrNull()
        }
    }
    val thumb = remember(thumbBmp, hidden) {
        thumbBmp?.let { (if (hidden) blurForSpoiler(it) else it).asImageBitmap() }
    }
    Box(
        Modifier
            .size(220.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.bgSecondary)
            .combinedClickable(
                onClick = {
                    if (hidden) { revealed = true; return@combinedClickable }
                    val mid = m.mediaId; val key = m.mediaKey
                    if (mid != null && key != null) scope.launch {
                        val bytes = session.fetchImage(mid, key)
                        if (bytes != null) openFile(context, bytes, "video-${m.id}.mp4", "video/mp4")
                    }
                },
                onLongClick = onLongPress,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) {
            Image(bitmap = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (hidden) {
            SpoilerOverlay()
        } else {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, stringResource(R.string.chat_play_video), tint = Color.White, modifier = Modifier.size(30.dp))
            }
            (m.durationSec ?: 0).takeIf { it > 0 }?.let {
                Text(
                    formatDuration(it), color = Color.White, fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocationBubble(m: ChatMessage, onLongPress: () -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val lat = m.lat ?: 0.0
    val lng = m.lng ?: 0.0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (m.fromMe) c.bubbleSelf else c.bubbleOther)
            .combinedClickable(
                onClick = {
                    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(RCQ)"))
                    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps?q=$lat,$lng"))
                    runCatching { context.startActivity(geo) }.recoverCatching { context.startActivity(web) }
                },
                onLongClick = onLongPress,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(Icons.Filled.LocationOn, null, tint = c.accent, modifier = Modifier.size(24.dp))
        Column {
            Text(if (m.body.isNotEmpty()) m.body else stringResource(R.string.chat_prev_location), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("%.5f, %.5f".format(lat, lng), color = c.textSecondary, fontSize = 11.sp)
        }
    }
}

/** Best-effort current location: last-known across providers, else one
 *  fresh fix (8s timeout). The caller checks the permission. */
@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): Pair<Double, Double>? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return null
    for (p in listOf(
        android.location.LocationManager.GPS_PROVIDER,
        android.location.LocationManager.NETWORK_PROVIDER,
        android.location.LocationManager.PASSIVE_PROVIDER,
    )) {
        runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { return it.latitude to it.longitude }
    }
    val provider = when {
        runCatching { lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) }.getOrDefault(false) -> android.location.LocationManager.GPS_PROVIDER
        runCatching { lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) -> android.location.LocationManager.NETWORK_PROVIDER
        else -> return null
    }
    return withTimeoutOrNull(8000) {
        suspendCancellableCoroutine { cont ->
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(loc: android.location.Location) {
                    lm.removeUpdates(this)
                    if (cont.isActive) cont.resume(loc.latitude to loc.longitude)
                }
                override fun onProviderDisabled(p: String) {}
                override fun onProviderEnabled(p: String) {}
                @Deprecated("legacy callback")
                override fun onStatusChanged(p: String?, status: Int, extras: android.os.Bundle?) {}
            }
            runCatching { lm.requestLocationUpdates(provider, 0L, 0f, listener, android.os.Looper.getMainLooper()) }
            cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
        }
    }
}

/** Read a picked file's bytes + display name + MIME from a content URI. */
private fun readPickedFile(context: Context, uri: Uri): PickedFile? = runCatching {
    val cr = context.contentResolver
    var name = "file"
    cr.query(uri, null, null, null, null)?.use { cur ->
        val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cur.moveToFirst()) cur.getString(idx)?.let { name = it }
    }
    val mime = cr.getType(uri) ?: "application/octet-stream"
    val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
    PickedFile(bytes, name, mime)
}.getOrNull()

private data class PickedFile(val bytes: ByteArray, val name: String, val mime: String)

private data class PickedVideo(val bytes: ByteArray, val thumbB64: String, val durationSec: Int)

/** Read a picked video: raw bytes + a base64 JPEG poster frame (so the
 *  bubble renders before the blob downloads) + duration in seconds. */
private fun readPickedVideo(context: Context, uri: Uri): PickedVideo? = runCatching {
    val cr = context.contentResolver
    val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
    val mmr = android.media.MediaMetadataRetriever()
    val (thumbB64, durSec) = try {
        mmr.setDataSource(context, uri)
        val durMs = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val b64 = mmr.getFrameAtTime(0)?.let { bm ->
            val maxSide = 320
            val longest = maxOf(bm.width, bm.height)
            val scaled = if (longest > maxSide) {
                val f = maxSide.toFloat() / longest
                Bitmap.createScaledBitmap(bm, (bm.width * f).toInt().coerceAtLeast(1), (bm.height * f).toInt().coerceAtLeast(1), true)
            } else bm
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        } ?: ""
        b64 to (durMs / 1000L).toInt()
    } finally {
        runCatching { mmr.release() }
    }
    PickedVideo(bytes, thumbB64, durSec)
}.getOrNull()

/** Write decrypted bytes to the cache and hand them to a viewer via a
 *  FileProvider URI (chooser fallback so the user can always save it). */
private fun openFile(context: Context, bytes: ByteArray, fileName: String, mime: String) {
    runCatching {
        val dir = java.io.File(context.cacheDir, "files").apply { mkdirs() }
        val f = java.io.File(dir, fileName.replace('/', '_'))
        f.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(view, fileName).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1000.0)
    else -> "$bytes B"
}

/** Bytes to upload for a picked image: a picked GIF ships RAW (preserving the
 *  animation, capped at 8MB; larger falls back to a static JPEG frame), every
 *  other image downscales to JPEG. The recipient's PhotoBubble animates a GIF
 *  via its magic bytes. */
private fun readImageForSend(context: Context, uri: Uri): ByteArray? {
    if (context.contentResolver.getType(uri) == "image/gif") {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (raw != null && raw.size <= 8 * 1024 * 1024) return raw
    }
    return compressImage(context, uri)
}

private fun compressImage(context: Context, uri: Uri): ByteArray? {
    val src = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
    val maxSide = 1200
    val longest = maxOf(src.width, src.height)
    val scaled = if (longest > maxSide) {
        val f = maxSide.toFloat() / longest
        Bitmap.createScaledBitmap(src, (src.width * f).toInt(), (src.height * f).toInt(), true)
    } else src
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
    return out.toByteArray()
}

/** Outbound delivery indicator built from SHAPE + FILL, not colour, so the
 *  states stay legible on poor screens (founder request — a thin tinted tick is
 *  hard to read): a clock while sending, ONE check once the server has it, TWO
 *  checks once it reached the device, and TWO WHITE checks on a filled accent
 *  pill once READ. The Signal-style fill for "read" reads unmistakably even when
 *  you can't tell the colour, and never collides with the grey delivered ticks. */
@Composable
private fun DeliveryTicks(state: DeliveryState) {
    val c = RcqTheme.colors
    when (state) {
        DeliveryState.SENDING ->
            Icon(Icons.Filled.Schedule, null, tint = c.textSecondary, modifier = Modifier.size(12.dp))
        DeliveryState.SENT ->
            Icon(Icons.Filled.Check, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
        DeliveryState.DELIVERED ->
            Icon(Icons.Filled.DoneAll, null, tint = c.textSecondary, modifier = Modifier.size(15.dp))
        DeliveryState.READ ->
            Box(
                Modifier.clip(RoundedCornerShape(percent = 50)).background(c.accent)
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.DoneAll, null, tint = Color.White, modifier = Modifier.size(12.dp)) }
        DeliveryState.FAILED ->
            Icon(Icons.Filled.Close, null, tint = Color(0xFFE5484D), modifier = Modifier.size(13.dp))
    }
}
