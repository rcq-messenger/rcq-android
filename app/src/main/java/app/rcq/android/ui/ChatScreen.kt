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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import app.rcq.android.data.LocalStores
import app.rcq.android.net.CrossIslandStore
import app.rcq.android.net.ContactRelayStore
import app.rcq.android.crypto.Reply
import app.rcq.android.media.MediaSaver
import app.rcq.android.media.VoiceRecorder
import app.rcq.android.model.ChatMessage
import app.rcq.android.model.DeliveryState
import app.rcq.android.model.GroupMember
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
    var showAllMedia by remember { mutableStateOf(false) }
    var chatMenu by remember { mutableStateOf(false) }
    // A picked photo/video waiting in the pre-send preview (tap to blur).
    var pendingSend by remember { mutableStateOf<PendingSend?>(null) }
    var showGroupPicker by remember { mutableStateOf(false) }
    var showRelayPicker by remember { mutableStateOf(false) }
    // Decrypted bytes of a photo opened for fullscreen viewing (tester #10).
    var fullscreenImage by remember { mutableStateOf<ByteArray?>(null) }
    val listState = rememberLazyListState()

    // Share / save media to device (report #6 — Android couldn't share/download
    // a photo/video; iOS already could). Save uses scoped MediaStore on API 29+
    // (no permission); on API ≤ 28 it needs WRITE_EXTERNAL_STORAGE, which we
    // request on demand and then run the deferred save.
    val savedToast = stringResource(R.string.media_saved)
    val saveFailToast = stringResource(R.string.media_save_failed)
    var pendingSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val storagePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingSave?.invoke()
        else android.widget.Toast.makeText(context, saveFailToast, android.widget.Toast.LENGTH_SHORT).show()
        pendingSave = null
    }
    fun runSave(action: () -> Unit) {
        if (!MediaSaver.needsLegacyWritePermission ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingSave = action
            storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    /** Fetch a message's decrypted media bytes, then share or save. [toGallery]
     *  routes images/video to the gallery and everything else to Downloads. */
    fun mediaBytes(m: ChatMessage, then: (ByteArray) -> Unit) {
        val mid = m.mediaId; val key = m.mediaKey ?: return
        if (mid == null) return
        scope.launch {
            val bytes = session.fetchImage(mid, key, m.groupId?.let { session.groupHost(it) }) ?: return@launch
            then(bytes)
        }
    }
    fun mediaNameMime(m: ChatMessage, bytes: ByteArray): Pair<String, String> = when (m.kind) {
        "photo" -> if (bytes.isGif()) "RCQ_${m.id}.gif" to "image/gif" else "RCQ_${m.id}.jpg" to "image/jpeg"
        "video" -> "RCQ_${m.id}.mp4" to "video/mp4"
        "voice" -> "RCQ_voice_${m.id}.m4a" to "audio/mp4"
        else -> (m.fileName ?: "RCQ_${m.id}") to (m.fileMime ?: "application/octet-stream")
    }
    fun shareMessageMedia(m: ChatMessage) = mediaBytes(m) { bytes ->
        val (name, mime) = mediaNameMime(m, bytes)
        MediaSaver.share(context, bytes, name, mime)
    }
    fun saveMessageMedia(m: ChatMessage) = mediaBytes(m) { bytes ->
        val (name, mime) = mediaNameMime(m, bytes)
        runSave {
            val isGallery = m.kind == "photo" || m.kind == "video"
            val ok = if (isGallery) MediaSaver.saveToGallery(context, bytes, name, mime)
                     else MediaSaver.saveToDownloads(context, bytes, name, mime)
            val where = if (m.kind == "video") "Movies/RCQ" else if (isGallery) "Pictures/RCQ" else "Downloads/RCQ"
            val msg = if (ok) context.getString(R.string.media_saved_to, where) else saveFailToast
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Per-conversation screen-secure mode (1:1 only) is NOTIFY-ONLY (iOS parity,
    // founder's choice): we no longer blank the chat with FLAG_SECURE. When a
    // screenshot is taken while a secure chat is open, MainActivity's screen-
    // capture detector (Android 14+) sends the peer a "took a screenshot" notice
    // via Session.onLocalScreenshot(). The global screen-security toggle still
    // hard-blocks all screenshots via FLAG_SECURE (applied in MainActivity).
    val secureThreads by app.rcq.android.data.LocalStores.secureThreads.collectAsState()
    val chatSecure = !isGroup && !isSelf && peer != null &&
        app.rcq.android.data.LocalStores.peerThread(peer) in secureThreads
    // The user's chosen quick reactions (≤6); defaults to the historical six
    // until customised in the emoji picker. Drives the long-press reaction row.
    val reactionSet by LocalStores.reactionEmojis.collectAsState()

    val youLabel = stringResource(R.string.chat_you)
    fun authorName(m: ChatMessage): String = when {
        m.fromMe -> youLabel
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

    // OpenDocument (ACTION_OPEN_DOCUMENT / SAF DocumentsUI) shows EVERY file
    // type incl. APKs/docs; the old GetContent (ACTION_GET_CONTENT) is
    // media-skewed on modern Android and hid arbitrary files, so picking a
    // file "did nothing". Matches iOS UIDocumentPicker([.item]).
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
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

    // Reaction-jump on open: if someone reacted to one of my messages while I was
    // away, scroll to + flash the FIRST (lowest row index) reacted message once
    // the open-scroll has settled, then consume the queue so it doesn't re-flash
    // on reopen. (The home-heart for this thread is already cleared in openThread;
    // we clear the message-id queue here, AFTER the jump consumes it.) Snapshot
    // the ids at open so a reaction landing while we're already inside the chat
    // doesn't yank the view (that path keeps the live reaction chip visible).
    val reactedAtOpen = remember(target) {
        app.rcq.android.data.LocalStores.reactedMsgIds.value[thisThread] ?: emptySet()
    }
    var didReactionJump by remember(target) { mutableStateOf(false) }
    LaunchedEffect(rows.size, didInitialScroll) {
        if (didReactionJump || !didInitialScroll || reactedAtOpen.isEmpty()) return@LaunchedEffect
        didReactionJump = true
        val idx = rows.indexOfFirst { r ->
            (r is ChatRow.Single && r.m.id in reactedAtOpen) ||
                (r is ChatRow.Album && r.items.any { it.id in reactedAtOpen })
        }
        if (idx >= 0) {
            val rid = when (val r = rows[idx]) {
                is ChatRow.Single -> r.m.id
                is ChatRow.Album -> r.items.first { it.id in reactedAtOpen }.id
                else -> null
            }
            listState.animateScrollToItem(idx)
            if (rid != null) {
                highlightId = rid
                scope.launch { kotlinx.coroutines.delay(1400); if (highlightId == rid) highlightId = null }
            }
        }
        app.rcq.android.data.LocalStores.clearReactedMsgs(thisThread)
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

    // Mention-jump (Telegram-style @-FAB): ordered ids of messages in THIS open
    // thread that @mention me and aren't mine. Group-only by nature — a 1:1 body
    // can't @mention you as a third party (the gate is the same as the home-row
    // mention inbox). Tapping the @-FAB steps through these in order.
    val mentionIds = remember(messages, isGroup) {
        if (!isGroup) emptyList()
        else messages.filter { !it.fromMe && session.bodyMentionsMe(it.body) }.map { it.id }
    }
    var mentionCursor by remember(target) { mutableStateOf(0) }
    // The @-FAB steps through mentions and HIDES once the cursor passes the
    // last one (NO wrap) — tapping the final mention dismisses the FAB instead
    // of restarting the count back to the total. A newly arriving mention grows
    // the list past the cursor and brings the FAB back for that one.
    val mentionsLeft = (mentionIds.size - mentionCursor).coerceAtLeast(0)

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
                GroupAvatar(group, session, 28.dp, animated = true)
            } else if (isSelf) {
                Icon(Icons.Filled.Bookmark, null, tint = c.accent, modifier = Modifier.size(26.dp))
            } else {
                val isCrossIsland = peerContact?.host != null ||
                    (peerContact == null && CrossIslandStore.findByUin(peer ?: 0) != null)
                StatusIcon(peerContact?.presence ?: UserStatus.OFFLINE, size = 26.dp, crossIsland = isCrossIsland)
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
                    peerContact == null -> CrossIslandStore.findByUin(peer ?: 0)?.host ?: "$peer"
                    // Cross-island peer: show their island, not a fake "offline"
                    // (presence isn't tracked across islands).
                    peerContact.host != null -> peerContact.host
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
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_menu_all_media), color = c.textPrimary) },
                        leadingIcon = { Icon(Icons.Filled.Image, null, tint = c.accent) },
                        onClick = { chatMenu = false; showAllMedia = true },
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

        // Pinned banner (groups). Single compact line that never reflows the
        // chat; tap opens the full scrollable sheet with clickable mentions/URLs
        // + group join-cards. Shared with GroupInfoScreen (same rich viewer).
        group?.pinnedText?.takeIf { it.isNotBlank() }?.let { pin ->
            PinnedAnnouncement(
                session = session,
                pin = pin,
                members = group?.members ?: emptyList(),
                ownUin = ownUin,
                groupHost = group?.id?.let { session.groupHost(it) },
                onOpenPeerInfo = onOpenPeerInfo,
                onOpenGroup = onOpenGroup,
                modifier = Modifier.fillMaxWidth().background(c.bgSecondary).padding(horizontal = 12.dp, vertical = 6.dp),
                textColor = c.textSecondary,
                iconTint = c.textSecondary,
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
        ChatBackground()  // global chat wallpaper (behind the messages); no-op when default
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
                    ChatRow.Unread -> UnreadDividerRow(initialUnread)
                    is ChatRow.Single -> {
                        val m = row.m
                        if (m.kind == "call") {
                            CallHistoryRow(m)
                        } else if (m.kind == "system") {
                            SystemNoticeRow(m)
                        } else {
                            MessageBubble(
                                session, m,
                                senderName = if (isGroup && !m.fromMe && row.showSender) authorName(m) else null,
                                replyAuthorOverride = if (row.replyMine) youLabel else null,
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
                        senderName = if (isGroup && !row.items.first().fromMe && row.showSender) authorName(row.items.first()) else null,
                        onLongPress = { actionMsg = row.items.first() },
                        onSenderClick = if (isGroup && !row.items.first().fromMe) ({ row.items.first().senderUin?.let { if (it != ownUin) onOpenPeerInfo(it) } }) else null,
                    )
                }
            }
        }
            // Jump-to-latest: a floating down-arrow shown only when the user has
            // scrolled up from the newest message (iOS/Telegram parity). Tapping
            // animates back to the bottom.
            // Show the jump-down arrow only once the newest message has scrolled
            // fully off-screen — not the instant the list can scroll down by a
            // pixel (#19: "буквально один миллиметр — и она тут как тут").
            val showJumpDown by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    lastVisible < info.totalItemsCount - 1
                }
            }
            // #15: badge on the arrow counting unread messages BELOW the fold.
            // We track a high-water mark — the deepest row the user has actually
            // scrolled into view — and count messages below THAT, not below the
            // current viewport. So the count only decreases as you scroll down,
            // never re-counts rows you already passed, and never grows when you
            // scroll back up. Only genuinely-new messages arriving below the
            // mark push it up again. (Matches the iOS unreadBelow behavior; fixes
            // the count re-counting after reaching bottom / inflating on scroll-up.)
            var deepestSeen by remember(threadKey) { mutableStateOf(-1) }
            LaunchedEffect(threadKey) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                    .collect { last -> if (last > deepestSeen) deepestSeen = last }
            }
            val belowCount by remember(rows) {
                derivedStateOf {
                    if (deepestSeen < 0) return@derivedStateOf 0
                    val from = deepestSeen + 1
                    if (from > rows.lastIndex) 0
                    else (from..rows.lastIndex).count { rows[it] is ChatRow.Single || rows[it] is ChatRow.Album }
                }
            }
            if (showJumpDown) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 10.dp),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Box(
                        Modifier.padding(top = 6.dp, end = 0.dp)
                            .size(40.dp).clip(CircleShape).background(c.bgSecondary)
                            .border(1.dp, c.divider, CircleShape)
                            .clickable { scope.launch { listState.animateScrollToItem(rows.lastIndex.coerceAtLeast(0)) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, null, tint = c.textPrimary, modifier = Modifier.size(26.dp))
                    }
                    UnreadBadge(belowCount, Modifier.align(Alignment.TopEnd))
                }
            }
            // Mention-jump FAB (@): a second circular button directly ABOVE the
            // jump-down FAB, shown whenever the open group thread has messages
            // that @mention me — INDEPENDENT of scroll position (Telegram-style).
            // Each tap scrolls to + flashes the next @-mention, stepping in order.
            if (mentionsLeft > 0 && mentionCursor < mentionIds.size) {
                Box(
                    // Stack above the jump-down FAB: its 40dp circle + 6dp badge
                    // gap sits at bottom=10dp, so clear ~64dp to leave an ~8dp gap.
                    Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 64.dp),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Box(
                        Modifier.padding(top = 6.dp, end = 0.dp)
                            .size(40.dp).clip(CircleShape).background(c.bgSecondary)
                            .border(1.dp, c.divider, CircleShape)
                            .clickable {
                                val rid = mentionIds[mentionCursor]
                                val idx = rows.indexOfFirst { r ->
                                    (r is ChatRow.Single && r.m.id == rid) ||
                                        (r is ChatRow.Album && r.items.any { it.id == rid })
                                }
                                if (idx >= 0) {
                                    scope.launch { listState.animateScrollToItem(idx) }
                                    highlightId = rid
                                    scope.launch { kotlinx.coroutines.delay(1400); if (highlightId == rid) highlightId = null }
                                }
                                // Advance WITHOUT wrapping: stepping past the last
                                // mention takes the cursor to size, which hides the
                                // FAB (badge 0) instead of resetting to the total.
                                mentionCursor += 1
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.AlternateEmail, null, tint = c.textPrimary, modifier = Modifier.size(24.dp))
                    }
                    // Remaining un-stepped @-mentions, like the jump-down badge.
                    UnreadBadge(mentionsLeft, Modifier.align(Alignment.TopEnd))
                }
            }
        }

        replyTarget?.let { rt ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                Box(Modifier.width(3.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(authorName(rt), color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(previewOf(rt, context), color = c.textSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Filled.Close, stringResource(R.string.chat_cancel_reply), tint = c.textSecondary, modifier = Modifier.clickable { replyTarget = null }.padding(8.dp).size(18.dp))
            }
        }

        if (!canPost) {
            // Read-only notice on a subtle plate (parity with the iOS material
            // backdrop) so it reads as a deliberate bar, not stray text.
            Box(Modifier.fillMaxWidth().background(c.bgSecondary).padding(horizontal = 16.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Campaign, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.chat_owner_only), color = c.textSecondary, fontSize = 13.sp)
                }
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
                    // Carry the REAL author nick in the quote (never the literal
                    // "You") so other people see the nick; the viewer's own
                    // client localizes "You" via replyMine at render time.
                    val reply = replyTarget?.let { Reply(it.id, previewOf(it, context), if (it.fromMe) session.nickname else authorName(it)) }
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
        FullscreenImageViewer(
            bytes,
            onShare = {
                val (name, mime) = if (it.isGif()) "RCQ_${System.currentTimeMillis()}.gif" to "image/gif"
                                   else "RCQ_${System.currentTimeMillis()}.jpg" to "image/jpeg"
                MediaSaver.share(context, it, name, mime)
            },
            onSave = {
                val (name, mime) = if (it.isGif()) "RCQ_${System.currentTimeMillis()}.gif" to "image/gif"
                                   else "RCQ_${System.currentTimeMillis()}.jpg" to "image/jpeg"
                runSave {
                    val ok = MediaSaver.saveToGallery(context, it, name, mime)
                    val msg = if (ok) context.getString(R.string.media_saved_to, "Pictures/RCQ") else saveFailToast
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { fullscreenImage = null },
        )
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
                            // 30dp (was 20): the smiley is the subject of the
                            // group, 20dp read as tiny (founder report).
                            EmoticonGif(asset, Modifier.size(30.dp), animate = false)
                            Spacer(Modifier.width(8.dp))
                            Text("${entries.size}", color = c.textSecondary, fontSize = 14.sp)
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
                        reactionSet.forEach { asset ->
                            Box(
                                modifier = Modifier.clip(CircleShape).clickable {
                                    scope.launch { runCatching { session.sendReaction(m, asset) } }
                                    actionMsg = null
                                }.padding(4.dp),
                            ) { EmoticonGif(asset, Modifier.size(32.dp), animate = false) }
                        }
                    }
                    MessageAction(stringResource(R.string.chat_reply)) { replyTarget = m; actionMsg = null }
                    if (m.kind == "photo" || m.kind == "video" || m.kind == "file" || m.kind == "voice") {
                        MessageAction(stringResource(R.string.media_share)) { shareMessageMedia(m); actionMsg = null }
                        MessageAction(stringResource(R.string.media_save)) { saveMessageMedia(m); actionMsg = null }
                    }
                    // Pin from chat (owner / info-moderator): copies this message's
                    // text into the single group pin slot, replacing whatever was
                    // there (a chat pin or the settings-entered text — one slot).
                    if (group != null && group.members.firstOrNull { it.uin == ownUin }?.canManageInfo(group.ownerUin) == true) {
                        val pinFallback = stringResource(R.string.chat_pinned_media)
                        MessageAction(stringResource(R.string.chat_pin)) {
                            val text = m.body.ifBlank { pinFallback }
                            // Optimistic + instant: replace the displayed pin now,
                            // then PATCH (the response reconciles it).
                            session.applyPinnedTextLocally(group.id, text)
                            scope.launch { runCatching { session.patchGroup(group.id, pinnedText = text) } }
                            actionMsg = null
                        }
                    }
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
                    MessageAction(stringResource(R.string.chat_attach_file)) { attachMenu = false; filePicker.launch(arrayOf("*/*")) }
                    MessageAction(stringResource(R.string.chat_attach_location)) { attachMenu = false; shareLocation() }
                    MessageAction(stringResource(R.string.chat_attach_group)) { attachMenu = false; showGroupPicker = true }
                    if (isGroup) MessageAction(stringResource(R.string.poll_create)) { attachMenu = false; showPollComposer = true }
                    if (!isGroup) MessageAction(stringResource(R.string.relay_share_attach)) { attachMenu = false; showRelayPicker = true }
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
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    showGroupPicker = false
                                    val (shareId, shareHost) = session.groupShareRef(g.id)
                                    val url = GroupLinkParser.canonicalUrl(shareId, shareHost)
                                    scope.launch {
                                        runCatching {
                                            if (isGroup) session.sendGroupText(groupId!!, url) else session.sendText(peer!!, url)
                                        }
                                    }
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                GroupAvatar(g, session, 28.dp, animated = true)
                                Text(g.name, color = c.textPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showGroupPicker = false }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
        )
    }

    // In-chat bridge sharing: pick a relay from your pool to hand the peer so
    // they can route through it when their own relays are blocked.
    if (showRelayPicker) {
        val pool = remember { session.shareableRelays() }
        AlertDialog(
            onDismissRequest = { showRelayPicker = false },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(R.string.relay_share_pick_title), color = c.textPrimary) },
            text = {
                Column {
                    Text(stringResource(R.string.relay_share_pick_body), color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        pool.forEach { r ->
                            MessageAction("${r.proto.uppercase()} · ${r.server}:${r.port}") {
                                showRelayPicker = false
                                peer?.let { p -> scope.launch { runCatching { session.shareRelay(p, r) } } }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showRelayPicker = false }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
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
    if (showAllMedia) {
        BackHandler { showAllMedia = false }
        AllMediaOverlay(
            session = session,
            messages = messages,
            onClose = { showAllMedia = false },
            onOpenPhoto = { m -> mediaBytes(m) { fullscreenImage = it } },
            onOpenVideo = { m -> mediaBytes(m) { openFile(context, it, "video-${m.id}.mp4", "video/mp4") } },
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
    // Don't let the keyboard auto-reappear after the app is backgrounded and
    // resumed (reading a chat, switch apps, come back → IME used to pop up).
    // On ON_STOP we drop the composer's focus + hide the IME, so resume has
    // no focused field to restore the keyboard for. The draft is untouched.
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                focusManager.clearFocus(force = true)
                keyboard?.hide()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
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
                                Text("#${mbr.uin}", color = c.textMono, fontSize = 12.sp)
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
                    Icons.Filled.AttachFile, stringResource(R.string.chat_attach), tint = c.textSecondary,
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
    "relay" -> context.getString(R.string.relay_share_title)
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
    // Read the per-frame IME inset (this composable is isolated so only it
    // recomposes each frame, not the whole ChatScreen).
    val imeBottom = WindowInsets.ime.getBottom(density)
    val imeVisible = imeBottom > 0
    // Capture, at the moment the keyboard STARTS opening (before imePadding has
    // shrunk the list), whether the newest messages were on screen — so tapping
    // the composer while reading history doesn't yank you to the bottom.
    val followToBottom = remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible && itemCount > 0) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            followToBottom.value = lastVisible >= itemCount - 2
        } else {
            followToBottom.value = false
        }
    }
    // The earlier one-shot scroll fired BEFORE the inset finished animating, so
    // the list re-grew its scroll range as it shrank and the last message slid
    // back under the keyboard. Instead re-pin to the bottom on every inset frame
    // while it animates open (JUMP, never animate — an animated scroll racing the
    // inset animation stuttered on weak devices, report #29/#21).
    LaunchedEffect(imeBottom) {
        if (imeVisible && followToBottom.value && itemCount > 0) {
            listState.scrollToItem(itemCount - 1)
        }
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
    /** A parsed invite: server-side group id + the island it lives on
     *  (§5c — null host = "my own island", the legacy bare-id form). */
    data class GroupRef(val id: Int, val host: String?)

    private fun refOf(seg: String): GroupRef? {
        val at = seg.indexOf('@')
        val id = (if (at >= 0) seg.substring(0, at) else seg).toIntOrNull()?.takeIf { it > 0 } ?: return null
        val host = if (at >= 0) seg.substring(at + 1).lowercase().takeIf { it.isNotEmpty() } else null
        return GroupRef(id, host)
    }

    fun parse(body: String): GroupRef? {
        val t = body.trim()
        if (t.isEmpty() || t.contains(' ') || t.contains('\n')) return null
        val uri = runCatching { android.net.Uri.parse(t) }.getOrNull() ?: return null
        if (uri.scheme == "rcq" && uri.host == "group") {
            return uri.lastPathSegment?.let(::refOf)
        }
        if ((uri.scheme == "https" || uri.scheme == "http") && uri.host == "rcq.app") {
            val segs = uri.pathSegments
            if (segs.size >= 2 && segs[0] == "g") return refOf(segs[1])
        }
        return null
    }

    /** New shares always carry the host so the link works from ANY island. */
    fun canonicalUrl(id: Int, host: String): String = "https://rcq.app/g/$id@$host"

    /** Every group-invite link in [text], in document order (iOS
     *  GroupLinkParser.parseAll parity) — used to render pin cards. Matches
     *  both the shareable https form and the rcq:// deep-link form. */
    fun parseAll(text: String): List<GroupRef> {
        val re = Regex("(?:https?://rcq\\.app/g/|rcq://group/)(\\d+(?:@[a-z0-9.-]+)?)", RegexOption.IGNORE_CASE)
        return re.findAll(text).mapNotNull { refOf(it.groupValues[1]) }.distinct().toList()
    }
}

/** A shared group-invite link rendered as a join card (iOS GroupLinkBubble
 *  parity): avatar + name + member count + closed badge; tap opens a join
 *  dialog, and joining jumps into the group chat. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupLinkBubble(session: Session, ref: GroupLinkParser.GroupRef, onOpenGroup: (Int) -> Unit, onLongPress: () -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    var showJoin by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    val groupId = ref.id
    // §5c: a link can carry the group's island. Privacy rule — an island we
    // never visited is NOT touched for the preview (minimal card; the guest
    // registration happens only on the explicit Join tap).
    val foreignHost = ref.host?.takeIf { it != session.currentServer }
    val preview by produceState<app.rcq.android.net.RcqApi.GroupPreviewOut?>(initialValue = null, ref) {
        value = if (foreignHost != null) session.previewForeignGroup(foreignHost, groupId)
        else session.previewGroup(groupId)
    }
    val p = preview
    // Already a member? Resolve the LOCAL group id via a pure reverse lookup
    // (refByAlias never allocates an alias) so tapping a group you're already
    // in OPENS it instead of re-asking you to join (founder report).
    val groups by session.groups.collectAsState()
    val joinedLocalId = remember(groups, foreignHost, groupId) {
        if (foreignHost != null) groups.firstOrNull { g ->
            app.rcq.android.net.VisitedIslandsStore.refByAlias(g.id)
                ?.let { it.host.equals(foreignHost, ignoreCase = true) && it.remoteId == groupId } == true
        }?.id
        else groups.firstOrNull { it.id == groupId }?.id
    }
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
            .combinedClickable(onClick = {
                val open = joinedLocalId
                if (open != null) onOpenGroup(open)
                else if (p != null || foreignHost != null) showJoin = true
            }, onLongClick = onLongPress)
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
                p?.name ?: stringResource(if (foreignHost != null) R.string.group_invite_island else R.string.group_invite_loading),
                color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (foreignHost != null) {
                Text(foreignHost, color = c.textMono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (p != null) {
                Text(pluralStringResource(R.plurals.members, p.member_count, p.member_count), color = c.textSecondary, fontSize = 12.sp)
                Text(
                    stringResource(
                        if (joinedLocalId != null) R.string.group_invite_tap_open
                        else if (p.is_closed) R.string.group_invite_closed
                        else R.string.group_invite_tap_join,
                    ),
                    color = if (p.is_closed && joinedLocalId == null) Color(0xFFE5484D) else c.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                )
            } else if (foreignHost != null) {
                Text(stringResource(if (joinedLocalId != null) R.string.group_invite_tap_open else R.string.group_invite_tap_join), color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            } else {
                Text(stringResource(R.string.group_invite_link), color = c.textSecondary, fontSize = 12.sp)
            }
        }
    }
    if (showJoin && (p != null || foreignHost != null)) {
        AlertDialog(
            onDismissRequest = { if (!joining) showJoin = false },
            containerColor = c.bgSecondary,
            title = { Text(p?.name ?: stringResource(if (foreignHost != null) R.string.group_invite_island else R.string.group_invite_title), color = c.textPrimary) },
            text = {
                if (p != null) Text(pluralStringResource(R.plurals.members, p.member_count, p.member_count), color = c.textSecondary)
                else Text(stringResource(R.string.group_invite_island_hint, foreignHost ?: ""), color = c.textSecondary)
            },
            confirmButton = {
                TextButton(enabled = !joining, onClick = {
                    joining = true
                    scope.launch {
                        if (foreignHost != null) {
                            val alias = session.joinForeignGroup(foreignHost, groupId)
                            joining = false
                            showJoin = false
                            if (alias != null) onOpenGroup(alias)
                        } else {
                            val g = session.joinGroup(groupId)
                            joining = false
                            showJoin = false
                            if (g != null) onOpenGroup(groupId)
                        }
                    }
                }) { Text(stringResource(R.string.group_invite_join), color = c.accent) }
            },
            dismissButton = { TextButton(enabled = !joining, onClick = { showJoin = false }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
        )
    }
}

/** Rich pinned-announcement preview: a single compact line (never reflows its
 *  container) whose text has clickable @mentions + URLs, with an expand affordance
 *  when there's more. Tapping opens a scrollable sheet showing the full pin text
 *  plus group-invite join CARDS (not bare links). Shared by the chat banner and
 *  GroupInfoScreen so both render the pin identically. [modifier] styles the
 *  outer row (the chat uses an edge-to-edge bar, group info a rounded card). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PinnedAnnouncement(
    session: Session,
    pin: String,
    members: List<GroupMember>,
    ownUin: Int,
    groupHost: String? = null,
    onOpenPeerInfo: (Int) -> Unit,
    onOpenGroup: (Int) -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color,
    iconTint: Color,
) {
    val c = RcqTheme.colors
    val annotated = remember(pin, members) { buildPinnedAnnotated(pin, members, c.accent) }
    // Inject the viewing group's host into BARE group links (`/g/<id>` with no
    // @host) so a pinned link to a sibling group on the SAME foreign island
    // resolves cross-island instead of blank-fetching from our own island.
    val pinGroupIds = remember(pin, groupHost) { GroupLinkParser.parseAll(pin).map { it.copy(host = it.host ?: groupHost) } }
    val hasPinText = annotated.text.isNotBlank()
    val uriHandler = LocalUriHandler.current
    var showPinSheet by remember(pin) { mutableStateOf(false) }
    val expandable = pinGroupIds.isNotEmpty() || pin.length > 48
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.PushPin, null, tint = iconTint, modifier = Modifier.size(14.dp))
        Box(Modifier.weight(1f)) {
            if (hasPinText) {
                ClickableText(
                    text = annotated,
                    style = TextStyle(color = textColor, fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    onClick = { offset ->
                        annotated.getStringAnnotations("MENTION", offset, offset).firstOrNull()?.let {
                            val mUin = it.item.toInt()
                            if (mUin != ownUin) onOpenPeerInfo(mUin)
                            return@ClickableText
                        }
                        annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                            runCatching { uriHandler.openUri(it.item) }
                            return@ClickableText
                        }
                        if (expandable) showPinSheet = true
                    },
                )
            } else {
                Text(
                    if (pinGroupIds.isEmpty()) stringResource(R.string.gi_pinned)
                    else "${stringResource(R.string.gi_pinned)} · ${pinGroupIds.size}",
                    color = textColor, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = expandable) { showPinSheet = true },
                )
            }
        }
        if (expandable) {
            if (pinGroupIds.isNotEmpty()) {
                Text("${pinGroupIds.size}", color = textColor, fontSize = 11.sp)
            }
            Icon(
                Icons.Filled.ExpandMore,
                stringResource(R.string.gi_pinned),
                tint = textColor,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { showPinSheet = true },
            )
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
                Column(Modifier.verticalScroll(rememberScrollState())) {
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
                    pinGroupIds.forEach { gref ->
                        Spacer(Modifier.height(6.dp))
                        PinnedGroupChip(session, gref, onOpenGroup = { showPinSheet = false; onOpenGroup(it) })
                    }
                }
            },
            containerColor = c.bgSecondary,
        )
    }
}

/** Compact join card for a group-invite link inside a pinned announcement
 *  (iOS PinnedGroupChip parity): avatar + name + member count. Tap opens the
 *  in-app join sheet (NOT a browser); joining jumps into that group. */
@Composable
internal fun PinnedGroupChip(session: Session, ref: GroupLinkParser.GroupRef, onOpenGroup: (Int) -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    var showJoin by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    val groupId = ref.id
    val foreignHost = ref.host?.takeIf { it != session.currentServer }
    val preview by produceState<app.rcq.android.net.RcqApi.GroupPreviewOut?>(initialValue = null, ref) {
        value = if (foreignHost != null) session.previewForeignGroup(foreignHost, groupId)
        else session.previewGroup(groupId)
    }
    val p = preview
    // Same membership check as GroupLinkBubble: open instead of re-join.
    val groups by session.groups.collectAsState()
    val joinedLocalId = remember(groups, foreignHost, groupId) {
        if (foreignHost != null) groups.firstOrNull { g ->
            app.rcq.android.net.VisitedIslandsStore.refByAlias(g.id)
                ?.let { it.host.equals(foreignHost, ignoreCase = true) && it.remoteId == groupId } == true
        }?.id
        else groups.firstOrNull { it.id == groupId }?.id
    }
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
            .clickable(enabled = p != null || foreignHost != null || joinedLocalId != null) {
                val open = joinedLocalId
                if (open != null) onOpenGroup(open) else showJoin = true
            }
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
                p?.name ?: stringResource(if (foreignHost != null) R.string.group_invite_island else R.string.group_invite_loading),
                color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (foreignHost != null) Text(foreignHost, color = c.textMono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p != null) Text(
                pluralStringResource(R.plurals.members, p.member_count, p.member_count),
                color = c.textSecondary, fontSize = 11.sp,
            )
        }
    }
    if (showJoin && (p != null || foreignHost != null)) {
        AlertDialog(
            onDismissRequest = { if (!joining) showJoin = false },
            containerColor = c.bgSecondary,
            title = { Text(p?.name ?: stringResource(if (foreignHost != null) R.string.group_invite_island else R.string.group_invite_title), color = c.textPrimary) },
            text = {
                if (p != null) Text(pluralStringResource(R.plurals.members, p.member_count, p.member_count), color = c.textSecondary)
                else Text(stringResource(R.string.group_invite_island_hint, foreignHost ?: ""), color = c.textSecondary)
            },
            confirmButton = {
                TextButton(enabled = !joining, onClick = {
                    joining = true
                    scope.launch {
                        if (foreignHost != null) {
                            val alias = session.joinForeignGroup(foreignHost, groupId)
                            joining = false; showJoin = false
                            if (alias != null) onOpenGroup(alias)
                        } else {
                            val g = session.joinGroup(groupId)
                            joining = false; showJoin = false
                            if (g != null) onOpenGroup(groupId)
                        }
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
            is PendingSend.Photo -> runCatching { val pb = pending.bytes; if (pb.isGif()) gifFirstFrame(pb) else BitmapFactory.decodeByteArray(pb, 0, pb.size) }.getOrNull()
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
    // showSender: first message of a consecutive run from the same sender in a
    // group (WA/TG style — the name appears once, not on every bubble). A date
    // or unread divider resets the run.
    // replyMine: this message quotes one of MY OWN messages, so the quote shows
    // "You" to ME — but the wire carries the real nick, so OTHERS see the nick.
    data class Single(val m: ChatMessage, val showSender: Boolean = true, val replyMine: Boolean = false) : ChatRow
    data class Album(val id: String, val items: List<ChatMessage>, val showSender: Boolean = true) : ChatRow
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
internal fun buildPinnedAnnotated(
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
    // id -> fromMe, so a reply quoting one of MY messages can render "You" for
    // me while the wire still carries the real nick for everyone else.
    val mineById = HashMap<String, Boolean>(msgs.size)
    for (mm in msgs) mineById[mm.id] = mm.fromMe
    var lastDay = Long.MIN_VALUE
    var unreadDone = firstUnreadIndex < 0
    // Track the previous content row's sender so a run of messages from the same
    // person shows the name only once (reset by any divider below).
    var runSender: Int? = Int.MIN_VALUE  // sentinel: first row always shows
    var i = 0
    while (i < msgs.size) {
        val m = msgs[i]
        val day = dayKeyOf(m.sentAt)
        if (day != lastDay) { out.add(ChatRow.DateLabel(dayLabelOf(m.sentAt), day)); lastDay = day; runSender = Int.MIN_VALUE }
        if (!unreadDone && i == firstUnreadIndex) { out.add(ChatRow.Unread); unreadDone = true; runSender = Int.MIN_VALUE }
        val showSender = m.senderUin != runSender
        runSender = m.senderUin

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
            if (group.size >= 2) { out.add(ChatRow.Album(alb, group, showSender)); i = j; continue }
        }
        val replyMine = m.replyToId?.let { mineById[it] } ?: false
        out.add(ChatRow.Single(m, showSender, replyMine)); i++
    }
    return out
}

/** A centered day separator between messages of different dates. */
@Composable
private fun DateDividerRow(label: String) {
    val c = RcqTheme.colors
    val onWallpaper = LocalStores.chatBackground.collectAsState().value.isNotEmpty()
    if (onWallpaper) {
        // The flanking lines + gray label wash out on a gradient wallpaper, so
        // show a centered Telegram-style contrast pill instead.
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                label, color = c.textPrimary, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.bgSecondary.copy(alpha = 0.85f)).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).height(1.dp).background(c.divider))
        Text(label, color = c.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).height(1.dp).background(c.divider))
    }
}

/** The "Unread messages" divider, accent-tinted so it reads as a marker. */
@Composable
private fun UnreadDividerRow(count: Int = 0) {
    val c = RcqTheme.colors
    val label = stringResource(R.string.chat_unread_divider) + if (count > 0) " ($count)" else ""
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).height(1.dp).background(c.accent.copy(alpha = 0.5f)))
        Text(label, color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
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
    // Match MessageBubble: on a chat wallpaper the time/ticks footer washes out
    // on the gradient, so give it the same contrast pill. No-op on default chat.
    val onWallpaper = LocalStores.chatBackground.collectAsState().value.isNotEmpty()
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
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                .then(if (onWallpaper) Modifier.clip(RoundedCornerShape(8.dp)).background(c.bgSecondary.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 1.dp) else Modifier),
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
        value = if (!isVideo && m.mediaId != null && m.mediaKey != null) session.fetchImage(m.mediaId, m.mediaKey, m.groupId?.let { session.groupHost(it) }) else null
    }
    val bmp = remember(photo, m.id) {
        if (isVideo) {
            m.thumbB64?.takeIf { it.isNotEmpty() }?.let { runCatching { val b = android.util.Base64.decode(it, android.util.Base64.NO_WRAP); BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() }
        } else {
            // GIF album tiles via the pure-Java first frame (native GIF decoder
            // SIGSEGVs on some OEM ROMs); JPEG/PNG via the native decoder.
            photo?.let { runCatching { (if (it.isGif()) gifFirstFrame(it) else BitmapFactory.decodeByteArray(it, 0, it.size))?.asImageBitmap() }.getOrNull() }
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
private fun MessageBubble(session: Session, m: ChatMessage, senderName: String?, onRetry: () -> Unit, onLongPress: () -> Unit, onOpenGroup: (Int) -> Unit = {}, onViewImage: (ByteArray) -> Unit = {}, mentionNick: ((Int) -> String?)? = null, onMentionClick: ((Int) -> Unit)? = null, mentionUin: ((String) -> Int?)? = null, highlighted: Boolean = false, onTapReply: ((String) -> Unit)? = null, onSenderClick: (() -> Unit)? = null, onShowReactors: (ChatMessage) -> Unit = {}, replyAuthorOverride: String? = null) {
    val c = RcqTheme.colors
    val failed = m.state == DeliveryState.FAILED
    // When a chat wallpaper is set, the time/ticks row sits on the wallpaper
    // (not on a bubble), so the gray text washes out on a gradient — give it a
    // bubble-like pill for contrast. No-op on the default (no-wallpaper) chat.
    val onWallpaper = LocalStores.chatBackground.collectAsState().value.isNotEmpty()
    // Cap a bubble so a long message leaves a gap to the far edge — keeps the
    // L/R alignment (mine vs theirs) readable, not just the colour (tester #7).
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.78f).dp
    // A text body that is just a group-invite URL renders as a join card.
    val groupLinkId = if (m.kind == "text") GroupLinkParser.parse(m.body) else null
    // Telegram-style: a plain text bubble carries the time/ticks INSIDE itself
    // (readable on the bubble bg, no wallpaper washout, no separate row below).
    // Media/voice/file/poll/location/relay keep the row BELOW the bubble with the
    // wallpaper contrast pill (those bubbles have no good inline slot).
    val isPlainText = groupLinkId == null &&
        m.kind !in listOf("photo", "poll", "file", "video", "voice", "location", "relay")
    val meta: @Composable () -> Unit = {
        Text(formatTime(m.sentAt), color = c.textSecondary, fontSize = 10.sp)
        if (m.edited) Text(stringResource(R.string.chat_edited), color = c.textSecondary, fontSize = 10.sp)
        if (m.fromMe) {
            if (failed) Text(stringResource(R.string.chat_failed_retry), color = Color(0xFFE5484D), fontSize = 10.sp, modifier = Modifier.clickable(onClick = onRetry))
            else DeliveryTicks(m.state)
        }
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) c.accent.copy(alpha = 0.24f) else Color.Transparent)
            .padding(vertical = 3.dp, horizontal = 2.dp),
        horizontalAlignment = if (m.fromMe) Alignment.End else Alignment.Start,
    ) {
        // Media/voice/file/poll/location/relay keep the sender name ABOVE the
        // bubble; a plain text bubble renders it INSIDE, at the top (Telegram).
        if (senderName != null && !isPlainText) {
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
        } else if (m.kind == "relay") {
            RelayBubble(m, onLongPress)
        } else {
            Column(
                Modifier
                    .widthIn(max = maxW)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (m.fromMe) c.bubbleSelf else c.bubbleOther)
                    .combinedClickable(onClick = { if (failed) onRetry() }, onLongClick = onLongPress)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                // Sender name as the first line INSIDE the bubble (Telegram-style).
                if (senderName != null) {
                    Text(
                        senderName, color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 2.dp)
                            .then(if (onSenderClick != null) Modifier.clickable { onSenderClick() } else Modifier),
                    )
                }
                if (m.replyToSnippet != null) {
                    val tappable = m.replyToId != null && onTapReply != null
                    Column(
                        Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(6.dp)).background(c.accent.copy(alpha = 0.14f))
                            .then(if (tappable) Modifier.clickable { onTapReply!!.invoke(m.replyToId!!) } else Modifier)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(replyAuthorOverride ?: m.replyToAuthor.orEmpty(), color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text(m.replyToSnippet, color = c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // #2: collapse a very long body to ~14 lines with "Показать
                // полностью" (Telegram-style). Only a long CANDIDATE collapses,
                // and the button shows ONLY when the text actually overflowed
                // (hasVisualOverflow) — so a message that fits in <14 lines never
                // gets a pointless toggle.
                var bodyExpanded by remember(m.id) { mutableStateOf(false) }
                var bodyOverflow by remember(m.id) { mutableStateOf(false) }
                val candidate = m.body.length > 280
                val collapsed = candidate && !bodyExpanded
                // Truncated if the layout reports overflow (handles a long
                // no-newline paragraph) OR the body alone has > 14 hard lines.
                val manyLines = m.body.count { it == '\n' } >= 14
                EmoticonText(
                    m.body, color = c.textPrimary, fontSize = 15.sp, lineHeight = 19.sp,
                    mentionNick = mentionNick, onMentionClick = onMentionClick, mentionUin = mentionUin,
                    maxLines = if (collapsed) 14 else Int.MAX_VALUE,
                    onTextLayout = { if (collapsed) bodyOverflow = it.hasVisualOverflow },
                )
                if (collapsed && (bodyOverflow || manyLines)) {
                    Text(
                        stringResource(R.string.chat_show_more),
                        color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 2.dp).clickable { bodyExpanded = true },
                    )
                }
                // Telegram-style: time/ticks inside the bubble, bottom-right.
                Row(
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) { meta() }
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
        // Non-text bubbles (media/voice/file/poll/location/relay) keep the meta
        // BELOW the bubble, with the wallpaper contrast pill. Plain text bubbles
        // render it inline (above), so skip the below-row for them.
        if (!isPlainText) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .then(if (onWallpaper) Modifier.clip(RoundedCornerShape(8.dp)).background(c.bgSecondary.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 1.dp) else Modifier),
            ) { meta() }
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
        value = if (m.mediaId != null && m.mediaKey != null) session.fetchImage(m.mediaId, m.mediaKey, m.groupId?.let { session.groupHost(it) }) else null
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
            // bytes) — rendered animated via the pure-Java decoder (SafeGif.kt),
            // which works on all API levels and never hits the crashing native
            // GIF decoder on realme/ColorOS.
            b.isGif() ->
                SafeAnimatedGif(b, Modifier.fillMaxSize())
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
private fun FullscreenImageViewer(
    bytes: ByteArray,
    onShare: (ByteArray) -> Unit = {},
    onSave: (ByteArray) -> Unit = {},
    onDismiss: () -> Unit,
) {
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
            if (bytes.isGif()) {
                SafeAnimatedGif(bytes, Modifier.fillMaxWidth())
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            ) {
                Icon(
                    Icons.Filled.Download,
                    stringResource(R.string.media_save),
                    tint = Color.White,
                    modifier = Modifier.size(28.dp).clickable { onSave(bytes) },
                )
                Icon(
                    Icons.Filled.Share,
                    stringResource(R.string.media_share),
                    tint = Color.White,
                    modifier = Modifier.size(28.dp).clickable { onShare(bytes) },
                )
            }
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
                        val bytes = session.fetchImage(mid, key, m.groupId?.let { session.groupHost(it) })
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
                            val bytes = session.fetchImage(mid, key, m.groupId?.let { session.groupHost(it) }) ?: return@launch
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
                        val bytes = session.fetchImage(mid, key, m.groupId?.let { session.groupHost(it) })
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

/** In-chat bridge sharing: a relay a contact handed you (or you sent). Incoming
 *  shows an Add button → [ContactRelayStore.add] (augments the transport pool);
 *  outgoing/added/invalid show a status line. See RCQ/docs/bridge-sharing-design.md. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RelayBubble(m: ChatMessage, onLongPress: () -> Unit) {
    val c = RcqTheme.colors
    val relay = remember(m.id) {
        ContactRelayStore.parseJsonElement(m.body)?.let { ContactRelayStore.relayFromJson(it) }
    }
    var added by remember(m.id) { mutableStateOf(relay?.let { ContactRelayStore.has(it) } ?: false) }
    Column(
        Modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (m.fromMe) c.bubbleSelf else c.bubbleOther)
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Shield, null, tint = c.accent, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.relay_share_title), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (relay != null) {
                    Text(
                        "${relay.proto.uppercase()} · ${relay.server}:${relay.port}",
                        color = c.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        when {
            relay == null ->
                Text(stringResource(R.string.relay_share_invalid), color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            m.fromMe ->
                Text(stringResource(R.string.relay_share_sent_note), color = c.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            added ->
                Text(stringResource(R.string.relay_share_added), color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            else -> Box(
                Modifier
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.accent)
                    .clickable { added = ContactRelayStore.add(relay, m.peerUin, null) || true }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.relay_share_add), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
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
/** "Show all media" gallery: a 3-column grid of every photo/video in the
 *  thread (newest first), built from the in-memory message list (iOS parity).
 *  Photo tap opens the fullscreen viewer; video tap opens the external player. */
@Composable
private fun AllMediaOverlay(
    session: Session,
    messages: List<ChatMessage>,
    onClose: () -> Unit,
    onOpenPhoto: (ChatMessage) -> Unit,
    onOpenVideo: (ChatMessage) -> Unit,
) {
    val c = RcqTheme.colors
    val media = remember(messages) {
        messages.filter { it.kind == "photo" || it.kind == "video" }.asReversed()
    }
    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.Close, contentDescription = null, tint = c.textPrimary,
                modifier = Modifier.clickable(onClick = onClose).padding(4.dp),
            )
            Text(stringResource(R.string.chat_all_media_title), color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        if (media.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.chat_all_media_empty), color = c.textSecondary, fontSize = 14.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                gridItems(media, key = { it.id }) { m ->
                    MediaTile(session, m) { if (m.kind == "video") onOpenVideo(m) else onOpenPhoto(m) }
                }
            }
        }
    }
}

@Composable
private fun MediaTile(session: Session, m: ChatMessage, onClick: () -> Unit) {
    val c = RcqTheme.colors
    val isVideo = m.kind == "video"
    val bmp = if (isVideo) {
        // Video poster straight from the stored base64 thumbnail (no fetch).
        remember(m.id) {
            m.thumbB64?.takeIf { it.isNotEmpty() }?.let {
                runCatching {
                    val b = android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
                }.getOrNull()
            }
        }
    } else {
        val bytes by produceState<ByteArray?>(null, m.id) {
            val mid = m.mediaId
            val key = m.mediaKey
            value = if (mid != null && key != null) {
                runCatching { session.fetchImage(mid, key, m.groupId?.let { session.groupHost(it) }) }.getOrNull()
            } else {
                null
            }
        }
        remember(bytes) {
            bytes?.let {
                runCatching {
                    (if (it.isGif()) gifFirstFrame(it) else decodeSampled(it, 360))?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    Box(
        Modifier.aspectRatio(1f).background(c.bgSecondary).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (isVideo) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(34.dp))
        }
    }
}

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
    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    // A picked GIF: keep it raw (size-capped) so it animates; a large one is
    // flattened to a static JPEG via the PURE-JAVA decoder. Either way we never
    // hit the native GIF decoder, which SIGSEGVs on some OEM ROMs. Mirrors
    // compressImageFor (the avatar path).
    if (raw.isGif()) {
        if (raw.size <= 2 * 1024 * 1024) return raw
        val frame = gifFirstFrame(raw) ?: return null
        return ByteArrayOutputStream().also { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }
    val src = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
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
