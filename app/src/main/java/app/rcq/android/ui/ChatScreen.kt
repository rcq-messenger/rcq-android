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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
// Direction arrows for the call log. Auto-mirrored on purpose: "outgoing" is
// the direction you read in, so it has to flip with the layout.
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Message
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
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
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
import app.rcq.android.media.AudioPlayer
import app.rcq.android.media.MediaSaver
import app.rcq.android.media.VoiceRecorder
import app.rcq.android.model.ChatMessage
import app.rcq.android.model.DeliveryState
import app.rcq.android.model.GroupMember
import app.rcq.android.model.UserStatus
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.offset

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
    /** Which message the draft is answering, keyed the same way.
     *
     *  ⚠ The reply lives as long as the text it belongs to, and it did not:
     *  `replyTarget` was composition state, so leaving the chat and coming
     *  back gave you your sentence back without the quote it was an answer to
     *  — and sending it then replied to nothing. Reported 2026-08-16.
     *
     *  Stores the message id, not the message: the row is re-read from the
     *  thread on the way back in, and a quote that has since been deleted
     *  simply does not come back. */
    val replyByThread = mutableMapOf<String, String>()
}

/** Put shared text into a thread's composer without sending it — the landing
 *  spot for the text half of a system share (a link, a quote). Appends rather
 *  than replaces: the picker can be reached with something already typed here,
 *  and silently eating it would be worse than an extra line break.
 *
 *  Written BEFORE the chat is opened, because the composer reads its draft once
 *  at first composition; a later write would not show until the next visit. */
internal fun seedDraft(target: ChatTarget, text: String) {
    val key = when (target) {
        is ChatTarget.Peer -> "p:${target.uin}"
        is ChatTarget.Group -> "g:${target.id}"
    }
    val existing = ChatDrafts.byThread[key].orEmpty()
    ChatDrafts.byThread[key] = if (existing.isBlank()) text else "$existing\n$text"
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
    // The chat list is fetched without rosters, and this screen needs one for
    // more than sending: an author's name, an @mention, and a moderator's own
    // delete/pin rights all come out of the roster, and without it the screen
    // shows bare uins where names belong. Fetched once on arrival; a no-op when
    // it is already here or when the group lives on another island.
    LaunchedEffect(groupId) { groupId?.let { session.ensureRoster(it) } }
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
    // Roster longest-match @mention resolver: given the body + the index of an
    // '@', return (uin, matched-nick-length) for the LONGEST member nick that
    // follows (case-insensitive), so nicks with spaces/colons (e.g. "JO f3 JO",
    // ".:example") render as clickable links — the old exact-nick + word-char
    // regex couldn't match them. Groups only.
    val mentionMatch: ((String, Int) -> Pair<Int, Int>?)? = remember(group, isGroup) {
        val members = group?.members?.takeIf { isGroup } ?: return@remember null
        fun match(text: String, at: Int): Pair<Int, Int>? {
            val after = text.substring(at + 1)
            val lower = after.lowercase()
            val mem = members
                .filter { it.nickname.isNotEmpty() && lower.startsWith(it.nickname.lowercase()) }
                .maxByOrNull { it.nickname.length } ?: return null
            val len = mem.nickname.length
            val tail = after.getOrNull(len)
            // Boundary so "@bob" doesn't match inside "@bobsled".
            return if (tail == null || !tail.isLetterOrDigit()) mem.uin to len else null
        }
        ::match
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
    var replyTarget by remember(threadKey) { mutableStateOf<ChatMessage?>(null) }
    // Bring the parked reply back with the draft it belongs to. Runs on the
    // thread's messages because the list arrives asynchronously — on the first
    // composition it can still be empty.
    // ⚠ Once per visit, not once per message list. This effect re-runs on every
    // change to `messages`, and SENDING changes them — so after sending a reply
    // it ran again, found the note it was about to be cleared of, and put the
    // quote straight back on the composer: "после отправки сообщения с
    // цитированием эта цитата остаётся для следующего сообщения" (#582). The
    // flag makes restoring what it says it is: bringing a parked reply back
    // when you walk into the thread.
    var replyRestored by remember(threadKey) { mutableStateOf(false) }
    LaunchedEffect(threadKey, messages) {
        if (replyRestored) return@LaunchedEffect
        val parked = ChatDrafts.replyByThread[threadKey] ?: return@LaunchedEffect
        if (replyTarget != null) return@LaunchedEffect
        replyTarget = messages.firstOrNull { it.id == parked }
        if (replyTarget != null) {
            replyRestored = true
        } else if (messages.isNotEmpty()) {
            // The message is gone (deleted, or the thread has not loaded that
            // far back). Drop the note rather than leaving it to resurface.
            ChatDrafts.replyByThread.remove(threadKey)
            replyRestored = true
        }
    }
    // One place that records it, so no path can set the chip without parking
    // it — and clearing the chip clears the note.
    LaunchedEffect(threadKey, replyTarget?.id) {
        val id = replyTarget?.id
        if (id == null) ChatDrafts.replyByThread.remove(threadKey)
        else ChatDrafts.replyByThread[threadKey] = id
    }
    // Choosing "reply" (swipe or the message menu) has to bring the keyboard up
    // together with the reply chip. It used to only set `replyTarget`, so the
    // chip appeared over a composer nobody was focused on and you had to tap
    // the input field before you could type the answer.
    val composerFocus = remember { FocusRequester() }
    // A counter, not a boolean: replying twice in a row must fire the effect
    // twice, and the second reply would not change a boolean that is already
    // true. Bumped by `startReply` below.
    var replyFocusTick by remember { mutableStateOf(0) }
    val startReply: (ChatMessage) -> Unit = { m ->
        replyTarget = m
        replyFocusTick++
    }
    LaunchedEffect(replyFocusTick) {
        if (replyFocusTick > 0) {
            // The message-action sheet is still on screen on the frame "Reply"
            // is tapped, and focus cannot land on the composer while the sheet
            // owns the window. Wait for the composition that removes it (and
            // the one that adds the reply chip) before asking.
            withFrameNanos {}
            withFrameNanos {}
            // Read-only channels render no composer at all, so the requester
            // has nothing to focus — that is a no-op, not a crash.
            runCatching { composerFocus.requestFocus() }
            exitKeyboard?.show()
        }
    }
    var attachMenu by remember { mutableStateOf(false) }
    var showPollComposer by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showAllMedia by remember { mutableStateOf(false) }
    var chatMenu by remember { mutableStateOf(false) }
    var confirmClearThread by remember { mutableStateOf(false) }
    // A picked photo/video waiting in the pre-send preview (tap to blur).
    var pendingSend by remember { mutableStateOf<PendingSend?>(null) }
    val mediaSending by session.mediaSending.collectAsState()
    val mediaProgress by session.mediaProgress.collectAsState()
    val mediaFailed by session.mediaSendFailed.collectAsState()
    var showGroupPicker by remember { mutableStateOf(false) }
    var showRelayPicker by remember { mutableStateOf(false) }
    // Decrypted bytes of a photo opened for fullscreen viewing (tester #10).
    var fullscreenImage by remember { mutableStateOf<ByteArray?>(null) }
    /** An album opened full screen: every picture of the batch and where to
     *  start. The grid only ever draws four, and before this the other six of a
     *  batch of ten could not be looked at from anywhere (#691/#675/#689). */
    var albumViewer by remember { mutableStateOf<Pair<List<ChatMessage>, Int>?>(null) }
    // ...and of a video. Same shape on purpose: the player reads the DECRYPTED
    // BYTES and never a URL, so a received clip is watched here rather than
    // being written out as plaintext for whatever player happens to be
    // installed to open (see VideoViewer.kt).
    var fullscreenVideo by remember { mutableStateOf<ByteArray?>(null) }

    // ---- Rows + unread anchor, computed BEFORE the list state exists. ----
    // The list used to be created blank (index 0), compose a frame or two at
    // the OLDEST message, and only then jump to the unread divider or the
    // bottom — the first thing anyone saw on opening a chat was the top of its
    // history sliding away (smoothness audit item 2). None of this depends on
    // the list state, so it moved above it: the state is born already pointing
    // at the right row and there is nothing to jump over.
    val thisThread = if (isGroup) app.rcq.android.data.LocalStores.groupThread(groupId!!) else app.rcq.android.data.LocalStores.peerThread(peer!!)
    // Snapshot the unread count at open (before openThread clears it) so we can
    // mark where reading left off — an "Unread messages" divider, Telegram-style.
    val initialUnread = remember(target) { app.rcq.android.data.LocalStores.unread.value[thisThread] ?: 0 }
    // Pin the divider to the message reading stopped at, by id. Deriving it as
    // `size - unread` on every size change slid the marker DOWN as new messages
    // arrived, so it always sat N-from-the-end instead of staying where the
    // user left off.
    // Count back over INBOUND messages only. Your own cannot be unread, and
    // counting raw positions put the divider inside the unread block whenever
    // you had sent anything after them: reported as "непрочитанных 5, три
    // видно, четвёртое вылезло, пятое дальше".
    val unreadAnchorId = remember(target, messages.isNotEmpty()) {
        if (initialUnread < 1) null
        else {
            var left = initialUnread
            var id: String? = null
            for (i in messages.indices.reversed()) {
                if (messages[i].fromMe) continue
                left--
                if (left == 0) { id = messages[i].id; break }
            }
            id
        }
    }
    val firstUnreadIndex = remember(messages, unreadAnchorId) {
        unreadAnchorId?.let { id -> messages.indexOfFirst { it.id == id } } ?: -1
    }
    val rows = remember(messages, firstUnreadIndex) { buildChatRows(messages, firstUnreadIndex) }
    // Where reading stopped last time (founder batch item 13a, iOS parity):
    // (rows-from-end, first-visible pixel offset), or null when the thread was
    // last left at the bottom. The unread divider WINS over it; from-the-END
    // so rows growing above the anchor leave it pointing at the same message.
    val savedPos = remember(target) { app.rcq.android.data.LocalStores.chatPosition(thisThread) }
    // Only the FIRST composition's rows matter here: with history already in
    // memory the state starts at the divider/saved-position/bottom directly.
    // An initially empty thread keeps index 0 and the LaunchedEffect below
    // does the jump once rows exist, same behaviour as before, minus the
    // visible hop.
    val initialListPos = remember(target) {
        val u = rows.indexOfFirst { it is ChatRow.Unread }
        when {
            rows.isEmpty() -> 0 to 0
            u >= 0 -> u to 0
            savedPos != null -> (rows.lastIndex - savedPos.first).coerceIn(0, rows.lastIndex) to savedPos.second
            else -> rows.lastIndex to 0
        }
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialListPos.first,
        initialFirstVisibleItemScrollOffset = initialListPos.second,
    )

    // Share / save media to device (report #6 — Android couldn't share/download
    // a photo/video; iOS already could). Save uses scoped MediaStore on API 29+
    // (no permission); on API ≤ 28 it needs WRITE_EXTERNAL_STORAGE, which we
    // request on demand and then run the deferred save.
    val savedToast = stringResource(R.string.media_saved)
    val saveFailToast = stringResource(R.string.media_save_failed)
    val fetchingToast = stringResource(R.string.media_fetching)
    val fetchFailToast = stringResource(R.string.media_fetch_failed)
    val shareFailToast = stringResource(R.string.share_to_unreadable)
    val mediaFailToast = stringResource(R.string.chat_media_send_failed)
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
    /// Fetch + decrypt the attachment behind a message, then hand the bytes on.
    ///
    /// ⚠ Both failure paths used to be silent. A file that is not in the cache
    /// has to come off the island first, which for the APKs people actually
    /// send takes long enough to read as nothing happening, and a fetch that
    /// failed outright returned without a word. Report #590 is exactly that:
    /// video of a finger tapping Save on a received APK, and no reaction of any
    /// kind. So: say that it started, and say if it did not finish.
    fun mediaBytes(m: ChatMessage, then: (ByteArray) -> Unit) {
        val mid = m.mediaId; val key = m.mediaKey ?: return
        if (mid == null) return
        android.widget.Toast.makeText(context, fetchingToast, android.widget.Toast.LENGTH_SHORT).show()
        scope.launch {
            val bytes = session.fetchImage(mid, key, m.groupId?.let { session.groupHost(it) })
            if (bytes == null) {
                android.widget.Toast.makeText(context, fetchFailToast, android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
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
    // Which way a row is dragged to quote it (#526).
    val swipeSide by LocalStores.swipeReplySide.collectAsState()

    val youLabel = stringResource(R.string.chat_you)
    fun authorName(m: ChatMessage): String = when {
        m.fromMe -> youLabel
        isGroup -> group?.memberName(m.senderUin ?: 0) ?: "${m.senderUin}"
        else -> session.contactName(peer ?: 0)
    }
    // The sender's roster row, for the picture that goes beside their nick.
    // Null for my own messages and for anyone who is not in the roster yet;
    // a member without a picture simply keeps the plain nick.
    fun authorMember(m: ChatMessage): app.rcq.android.model.GroupMember? =
        if (isGroup && !m.fromMe) group?.members?.firstOrNull { it.uin == m.senderUin } else null

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
        // ⚠ NOT on this screen's scope, and NOT a bare runCatching. A batch used
        // to upload on the composable's scope with every failure swallowed: no
        // "Sending N files" strip above the composer, so the chat looked as if
        // nothing had been sent until the first upload finished (#691); leaving
        // the chat cancelled the rest mid-flight; and a failed file was simply
        // absent, with nothing said. Same hole #473 closed for a single picture.
        if (uris.isNotEmpty()) session.sendMediaDetached("album", uris.size) { oneDone ->
            val albumId = if (uris.size > 1) java.util.UUID.randomUUID().toString().uppercase() else null
            var failed = 0
            for (uri in uris) {
                try {
                    val mime = withContext(Dispatchers.IO) { context.contentResolver.getType(uri) } ?: ""
                    if (mime.startsWith("video/")) {
                        val v = withContext(Dispatchers.IO) { readPickedVideo(context, uri) }
                        if (v == null) { failed += 1; continue }
                        if (isGroup) session.sendGroupVideo(groupId!!, v.bytes, v.thumbB64, v.durationSec, null, albumId = albumId)
                        else session.sendVideo(peer!!, v.bytes, v.thumbB64, v.durationSec, null, albumId = albumId)
                    } else {
                        val data = withContext(Dispatchers.IO) { readImageForSend(context, uri) }
                        if (data == null) { failed += 1; continue }
                        if (isGroup) session.sendGroupPhoto(groupId!!, data, null, albumId = albumId)
                        else session.sendPhoto(peer!!, data, null, albumId = albumId)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Throwable, not Exception: decoding a huge picture can
                    // throw OutOfMemoryError, and the old runCatching swallowed
                    // it; one bad file must not take the whole app down.
                    android.util.Log.w("RCQmedia", "album item failed: $uri", e)
                    failed += 1
                } finally {
                    oneDone()
                }
            }
            // One failure toast for the batch, raised by the wrapper.
            if (failed > 0) throw IllegalStateException("$failed of ${uris.size} album items failed")
        }
    }

    // Files handed over by another app's share sheet, after the "Send to…"
    // picker chose this thread. Deliberately the same treatment a paperclip
    // pick gets: one picture or video stops in the pre-send preview (so it can
    // still be captioned or hidden behind a spoiler), several go as an album,
    // and anything else goes as a file. ⚠ Runs once and clears the handoff —
    // the URI permissions are scoped to the intent that brought us here.
    //
    // ⚠ Collected rather than keyed on `collectAsState`: clearing the handoff is
    // the first thing this does, and with the cleared value as the effect's KEY
    // that write restarts the effect and kills the very work it just started.
    // The file then never arrives, silently, and only sometimes — it is a race
    // against the first suspension point.
    LaunchedEffect(Unit) {
        app.rcq.android.ShareIntake.deliver.collect { req ->
            req ?: return@collect
            app.rcq.android.ShareIntake.deliver.value = null
            val uris = req.uris
            if (uris.isEmpty()) return@collect
            val mimes = uris.map { withContext(Dispatchers.IO) { context.contentResolver.getType(it) } ?: "" }
            val allMedia = mimes.all { it.startsWith("image/") || it.startsWith("video/") }
            // A share that arrives unreadable (the sending app revoked the grant, or
            // never held one) otherwise looks exactly like a share that worked: the
            // chat just opens, empty. Name it in the log and say so on screen.
            android.util.Log.i("RCQshare", "delivering ${uris.size} into $threadKey — ${mimes.joinToString()}")
            if (uris.size == 1 && allMedia) {
                val uri = uris[0]
                val one = if (mimes[0].startsWith("video/")) {
                    withContext(Dispatchers.IO) {
                        runCatching { readPickedVideo(context, uri) }
                            .onFailure { android.util.Log.w("RCQshare", "video read threw", it) }.getOrNull()
                    }?.let { PendingSend.Video(it) }
                } else {
                    withContext(Dispatchers.IO) {
                        runCatching { readImageForSend(context, uri) }
                            .onFailure { android.util.Log.w("RCQshare", "image read threw", it) }.getOrNull()
                    }?.let { PendingSend.Photo(it) }
                }
                if (one != null) pendingSend = one
                else {
                    android.util.Log.w("RCQshare", "could not read the shared item: $uri")
                    android.widget.Toast.makeText(context, shareFailToast, android.widget.Toast.LENGTH_LONG).show()
                }
                return@collect
            }
            val albumId = if (uris.size > 1 && allMedia) java.util.UUID.randomUUID().toString().uppercase() else null
            // Detached from this screen for the same reasons as the paperclip
            // batch above: the strip, surviving the user leaving, and a word
            // when something fails (#691, #473).
            session.sendMediaDetached("share", uris.size) { oneDone ->
                var failed = 0
                for ((i, uri) in uris.withIndex()) {
                    val mime = mimes[i]
                    try {
                        when {
                            mime.startsWith("video/") -> {
                                val v = withContext(Dispatchers.IO) { readPickedVideo(context, uri) }
                                if (v == null) { failed += 1; continue }
                                if (isGroup) session.sendGroupVideo(groupId!!, v.bytes, v.thumbB64, v.durationSec, null, albumId = albumId)
                                else session.sendVideo(peer!!, v.bytes, v.thumbB64, v.durationSec, null, albumId = albumId)
                            }
                            mime.startsWith("image/") -> {
                                val data = withContext(Dispatchers.IO) { readImageForSend(context, uri) }
                                if (data == null) { failed += 1; continue }
                                if (isGroup) session.sendGroupPhoto(groupId!!, data, null, albumId = albumId)
                                else session.sendPhoto(peer!!, data, null, albumId = albumId)
                            }
                            else -> {
                                val f = withContext(Dispatchers.IO) { readPickedFile(context, uri) }
                                if (f == null) { failed += 1; continue }
                                if (isGroup) session.sendGroupFile(groupId!!, f.bytes, f.name, f.mime)
                                else session.sendFile(peer!!, f.bytes, f.name, f.mime)
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        android.util.Log.w("RCQshare", "shared item failed: $uri", e)
                        failed += 1
                    } finally {
                        oneDone()
                    }
                }
                if (failed > 0) throw IllegalStateException("$failed of ${uris.size} shared items failed")
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
    // (`thisThread` itself is declared up with the rows block.)
    // Latest snapshot for the mention-seen mark on exit (onDispose otherwise
    // captures the messages value from when the effect was first set up).
    val msgsForMentionSeen by androidx.compose.runtime.rememberUpdatedState(messages)
    DisposableEffect(target) {
        session.openThread(thisThread)
        if (!isGroup && !isSelf && peer != null) session.sendReadReceipts(peer)
        onDispose {
            // Mark the loaded @mentions as seen so re-entering this chat doesn't
            // resurface the @-jump FAB for mentions already viewed (iOS parity).
            if (isGroup && groupId != null) {
                msgsForMentionSeen
                    .filter { !it.fromMe && session.bodyMentionsMe(it.body) }
                    .maxOfOrNull { it.sentAt }
                    ?.let { app.rcq.android.data.LocalStores.markMentionSeen(groupId, it) }
            }
            session.closeThread()
            if (!isGroup && !isSelf && peer != null) session.sendTyping(peer, false)
        }
    }
    // A message can land while the chat is already open — re-clear so the
    // badge never lingers after the user has seen it. The system notification
    // goes with it: reading the message IS acting on the wake, whether or not
    // the notification is what brought you here.
    LaunchedEffect(messages.size) {
        app.rcq.android.data.LocalStores.clearUnread(thisThread)
        app.rcq.android.push.Push.clearThreadNotification(
            context,
            if (isGroup) groupId else null,
            if (isGroup) null else peer,
        )
    }

    // (initialUnread / unreadAnchorId / firstUnreadIndex / rows are declared up
    // with the list state, which is born pointing at the unread divider.)
    var didInitialScroll by remember(target) { mutableStateOf(false) }
    // 13a: the position saver below stays disarmed until the open jumps have
    // settled. Armed too early it records the restore scroll as if the user
    // made it, and its at-the-bottom sentinel fires on the unmeasured first
    // frames and clears the very position the open is about to restore.
    var positionSaveArmed by remember(target) { mutableStateOf(false) }
    var highlightId by remember(target) { mutableStateOf<String?>(null) }
    // #1 reply-jump return: the scroll position the user was at when they tapped
    // a reply quote. While set, the jump-down arrow takes them BACK here (where
    // they jumped FROM) instead of to the latest message — Telegram-style.
    var replyReturnIndex by remember(target) { mutableStateOf<Int?>(null) }
    var replyReturnOffset by remember(target) { mutableStateOf(0) }

    // Initial position: at the first unread (or the bottom). INSTANT (no
    // animation) — the old animateScroll-on-every-size-change was the "mota к
    // последнему / eats resources" complaint (#1).
    LaunchedEffect(rows.size) {
        if (rows.isEmpty()) return@LaunchedEffect
        if (didInitialScroll) {
            // A rows change cancelled the run below mid-settle; the jump is
            // done, so make sure the saver still gets armed.
            positionSaveArmed = true
            return@LaunchedEffect
        }
        didInitialScroll = true
        val u = rows.indexOfFirst { it is ChatRow.Unread }
        if (u >= 0) {
            listState.scrollToItem(u)
            positionSaveArmed = true
            return@LaunchedEffect
        }
        // 13a: reopen where reading stopped. Bottom anchoring is not needed
        // here: the anchor is the FIRST visible row + offset, and scrollToItem
        // pins an item top, which holds through the header/composer settling.
        if (savedPos != null) {
            listState.scrollToItem((rows.lastIndex - savedPos.first).coerceIn(0, rows.lastIndex), savedPos.second)
            positionSaveArmed = true
            return@LaunchedEffect
        }
        listState.scrollToItem(rows.lastIndex.coerceAtLeast(0))
        // `scrollToItem` anchors the list to an ITEM, and that anchor survives
        // the screen settling around it. The pinned banner, the header and the
        // composer all take their final height a frame or two after this runs,
        // the viewport shrinks under the list, and the list keeps the anchor
        // instead of staying at the end — so the newest message opens already
        // half-hidden behind the composer. (Reported on a group whose last
        // message follows a date divider, which is simply tall enough to make
        // the missing strip obvious.) Push to the real end for a few frames
        // while the layout is still moving; a drag outranks us and takes the
        // scroll away, which ends this on its own.
        repeat(6) {
            withFrameNanos {}
            // Any distance past the end of the content; the scroll clamps
            // itself, so this is "as far down as it goes" without having to
            // measure how far that is.
            listState.scrollBy(1_000_000f)
        }
        positionSaveArmed = true
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
    //
    // The tail that was ALREADY there when the chat opened must not move the
    // view. This effect also runs on first composition, and back then it did
    // two things wrong at once: an empty `visibleItemsInfo` (layout has not run
    // yet on the frame the chat opens) made `?: 0` compare against
    // `totalItemsCount - 3` = -3, so "near the bottom" was true on a list that
    // had not been measured; and once measured, landing on the unread divider
    // with only a few unread messages ALSO reads as near-the-bottom. Either way
    // it immediately animated past the divider to the last message, which is
    // the "opens and scrolls straight to the end" report. So: adopt the tail
    // silently the first time, and only react to a genuinely newer message.
    var stickyAnchorId by remember(target) { mutableStateOf<String?>(null) }
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (!didInitialScroll) return@LaunchedEffect
        val last = messages.lastOrNull() ?: return@LaunchedEffect
        val previous = stickyAnchorId
        stickyAnchorId = last.id
        if (previous == null || previous == last.id) return@LaunchedEffect
        val info = listState.layoutInfo
        // No measured items = no basis to claim the user is at the bottom.
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        val nearBottom = lastVisible >= info.totalItemsCount - 3
        if (last.fromMe || nearBottom) listState.animateScrollToItem(rows.lastIndex.coerceAtLeast(0))
    }
    // 13a: persist where reading stopped. The resting scroll position, written
    // debounced to prefs (per thread, per account) as rows-from-end + offset;
    // resting at the bottom clears the entry instead, so a chat read to the
    // end opens at the newest message again. The armed read lives INSIDE
    // snapshotFlow, so the settled position is evaluated once right when
    // saving arms even if the user never scrolls after that.
    // ⚠ Keyed on [target] alone, never on the armed flag or anything this
    // effect writes: an effect keyed on state it changes itself restarts and
    // cancels its own work mid-flight (the LaunchedEffect key race).
    LaunchedEffect(target) {
        snapshotFlow {
            if (!positionSaveArmed) return@snapshotFlow null
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@snapshotFlow null
            Triple(
                info.totalItemsCount - 1 - listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                lastVisible >= info.totalItemsCount - 1,
            )
        }.collectLatest { pos ->
            val (fromEnd, offset, atBottom) = pos ?: return@collectLatest
            delay(300)
            if (atBottom) app.rcq.android.data.LocalStores.clearChatPosition(thisThread)
            else app.rcq.android.data.LocalStores.saveChatPosition(thisThread, fromEnd, offset)
        }
    }
    // Keep the latest message visible when the keyboard opens (report #29).
    KeyboardScrollEffect(listState, rows.size)

    // Jump to (and briefly flash) the message a reply quotes — iOS parity (#3).
    //
    // ⚠ Reads `rows` through rememberUpdatedState and is itself remembered: a
    // lambda that closed over `rows` directly was a NEW object on every list
    // change, travelled into every visible MessageBubble as a parameter, and
    // defeated strong skipping — one receipt or reaction repainted every bubble
    // on screen. The handler only needs the rows that exist at TAP time.
    val rowsForJump by rememberUpdatedState(rows)
    val onTapReply: (String) -> Unit = remember(target) {
        { rid ->
            val idx = rowsForJump.indexOfFirst { r ->
                (r is ChatRow.Single && r.m.id == rid) || (r is ChatRow.Album && r.items.any { it.id == rid })
            }
            if (idx >= 0) {
                // Remember where we jumped FROM so the down-arrow returns here, not to
                // the bottom of the chat (report — "стрелка кидает в конец").
                replyReturnIndex = listState.firstVisibleItemIndex
                replyReturnOffset = listState.firstVisibleItemScrollOffset
                scope.launch { listState.animateScrollToItem(idx) }
                highlightId = rid
                scope.launch { kotlinx.coroutines.delay(1400); if (highlightId == rid) highlightId = null }
            }
        }
    }

    // Mention-jump (Telegram-style @-FAB): ordered ids of messages in THIS open
    // thread that @mention me and aren't mine. Group-only by nature — a 1:1 body
    // can't @mention you as a third party (the gate is the same as the home-row
    // mention inbox). Tapping the @-FAB steps through these in order.
    val mentionIds = remember(messages, isGroup) {
        if (!isGroup) emptyList()
        else {
            // Only mentions NEWER than the per-group seen cut-off — reopening a
            // chat must not resurface the @-FAB for mentions already viewed.
            val seenAt = app.rcq.android.data.LocalStores.mentionSeenAt(groupId!!)
            messages.filter { !it.fromMe && it.sentAt > seenAt && session.bodyMentionsMe(it.body) }.map { it.id }
        }
    }
    var mentionCursor by remember(target) { mutableStateOf(0) }
    // The @-FAB steps through mentions and HIDES once the cursor passes the
    // last one (NO wrap) — tapping the final mention dismisses the FAB instead
    // of restarting the count back to the total. A newly arriving mention grows
    // the list past the cursor and brings the FAB back for that one.
    val mentionsLeft = (mentionIds.size - mentionCursor).coerceAtLeast(0)

    // "This number no longer exists": set the first time a send to this peer
    // failed AND their island answered a clean 404 (Session.notePeerLivenessAfterFailure).
    // Cleared the moment anything arrives from them again, so a wrong guess
    // cannot stick.
    val gonePeers by LocalStores.gonePeers.collectAsState()
    val peerGone = !isGroup && peer != null && peer in gonePeers
    LaunchedEffect(messages.size) {
        if (peerGone && messages.lastOrNull()?.fromMe == false) LocalStores.setGone(peer!!, false)
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary).imePadding()) {
        // Header. Deliberately NOT wallpaper-aware: the wallpaper Box starts
        // BELOW this row (see ChatBackground further down), so the header
        // always stands on the theme background and the theme's colours are
        // simply right. A blend-with-the-wallpaper variant was tried for #648
        // and reverted — on the luminance threshold (Cream) it flipped the
        // title dark on a dark bar, the exact defect it meant to fix.
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
                // Animated here and only here: one avatar on screen, so a
                // moving GIF costs nothing, while a list of them would.
                PersonAvatar(
                    peerContact?.avatarMediaId?.takeIf { !isCrossIsland }, peerContact?.avatarMediaKey,
                    peerContact?.presence ?: UserStatus.OFFLINE, session, 26.dp,
                    animated = true, crossIsland = isCrossIsland,
                )
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
                        // The count, not the roster's size: the roster arrives a
                        // moment later than the header does.
                        val n = group?.memberCount ?: 0
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
            // Calling somebody was two taps and a menu you had to know about.
            // The web client has always had the phone and the camera straight
            // in the header, and a call is the one chat action worth a
            // permanent target — so they come out of the overflow menu and sit
            // next to it. Same gate as the menu items had, unchanged: 1:1 only,
            // never the Saved-messages thread, and the PEER's call_policy (do
            // THEY accept calls from us) rather than our own setting.
            val canCall = !isGroup && !isSelf && peer != null && peerContact?.callable != false
            if (canCall) {
                Icon(
                    Icons.Filled.Call, stringResource(R.string.call_voice_cd), tint = c.accent,
                    modifier = Modifier.size(24.dp).clip(CircleShape)
                        .clickable { placeCall(app.rcq.android.call.CallController.Media.AUDIO) },
                )
                Spacer(Modifier.width(14.dp))
                Icon(
                    Icons.Filled.Videocam, stringResource(R.string.call_video_cd), tint = c.accent,
                    modifier = Modifier.size(24.dp).clip(CircleShape)
                        .clickable { placeCall(app.rcq.android.call.CallController.Media.VIDEO) },
                )
                Spacer(Modifier.width(14.dp))
            }
            // Everything else stays in the overflow menu. Own click consumes
            // the tap so the header's open-info click doesn't also fire.
            Box {
                Icon(
                    Icons.Filled.MoreVert, stringResource(R.string.chat_menu_cd), tint = c.accent,
                    modifier = Modifier.size(24.dp).clip(CircleShape).clickable { chatMenu = true },
                )
                DropdownMenu(expanded = chatMenu, onDismissRequest = { chatMenu = false }) {
                    // No call entries here any more — they are the two icons to
                    // the left. Leaving both would give one action two homes.
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
                            // A camera-with-a-cross, not a shield: this mode
                            // BLOCKS nothing, it only tells the peer when a
                            // screenshot is taken (the blocking one is Settings
                            // -> "Запрет скриншотов"). The shield promised
                            // protection the feature does not give (#700).
                            leadingIcon = { Icon(Icons.Filled.NoPhotography, null, tint = if (chatSecure) c.accent else c.textSecondary) },
                            onClick = { chatMenu = false; session.setChatSecure(peer, !chatSecure) },
                        )
                    }
                    // Erase this conversation without hunting for the contact on
                    // the home screen and long-pressing it (vss: "нет функции
                    // удалить переписку в текущем диалоге").
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_clear_chat), color = Color(0xFFE5484D)) },
                        leadingIcon = { Icon(Icons.Filled.DeleteSweep, null, tint = Color(0xFFE5484D)) },
                        onClick = { chatMenu = false; confirmClearThread = true },
                    )
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

        if (peerGone) {
            Text(
                stringResource(R.string.chat_peer_gone),
                color = c.statusBusy,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(c.bgSecondary).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        // Writing to somebody on ANOTHER island for the first time: their app
        // files it as a conversation REQUEST, not a chat, and nothing comes
        // back until they accept it. The sender has no way to see that — the
        // tick only says the island took the envelope — so a perfectly working
        // send looks like silence, and the obvious conclusion is "cross-island
        // messaging is broken" (vss reported exactly that). Say it once, while
        // the thread has nothing incoming in it yet.
        val awaitingAcceptance = !isGroup && !isSelf && peer != null &&
            (peerContact?.host != null || CrossIslandStore.findByUin(peer) != null) &&
            rows.any { it is ChatRow.Single && it.m.fromMe } &&
            rows.none { it is ChatRow.Single && !it.m.fromMe }
        if (awaitingAcceptance) {
            Text(
                stringResource(R.string.chat_cross_island_pending),
                color = c.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(c.bgSecondary).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
        ChatBackground()  // global chat wallpaper (behind the messages); no-op when default
        // A brand-new thread had nothing in it at all here — just wallpaper —
        // while iOS has always said what the screen is and what to do with it.
        // A first-time reader could not tell an empty conversation from one
        // that failed to load.
        if (rows.isEmpty()) {
            // Nothing but wallpaper under this, so it reads off the wallpaper
            // rather than off the theme — a light theme with the "Midnight"
            // wallpaper printed it black on near-black (#554, same defect as
            // the home header).
            val ec = chatChrome()
            Column(
                Modifier.fillMaxSize().padding(top = 96.dp, start = 32.dp, end = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Message,
                    contentDescription = null,
                    tint = ec.divider,
                    modifier = Modifier.size(38.dp),
                )
                Text(
                    stringResource(R.string.chat_empty_title),
                    color = ec.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.chat_empty_body),
                    color = ec.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center,
                )
            }
        }
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
                            val senderPic = if (row.showSender) authorMember(m) else null
                            SwipeToReply(side = swipeSide, onReply = { startReply(m) }) {
                            MessageBubble(
                                session, m,
                                senderName = if (isGroup && !m.fromMe && row.showSender) authorName(m) else null,
                                senderAvatarId = senderPic?.avatarMediaId,
                                senderAvatarKey = senderPic?.avatarMediaKey,
                                replyAuthorOverride = if (row.replyMine) youLabel else null,
                                onRetry = { scope.launch { runCatching { session.resend(m) } } },
                                onLongPress = { actionMsg = m },
                                onOpenGroup = onOpenGroup,
                                onViewImage = { fullscreenImage = it },
                                onViewVideo = { fullscreenVideo = it },
                                mentionNick = mentionNick,
                                onMentionClick = onMentionClick,
                                mentionMatch = mentionMatch,
                                highlighted = m.id == highlightId,
                                onTapReply = onTapReply,
                                onSenderClick = if (isGroup && !m.fromMe) ({ m.senderUin?.let { if (it != ownUin) onOpenPeerInfo(it) } }) else null,
                                onShowReactors = { whoReactedMsg = it },
                            )
                            }
                        }
                    }
                    is ChatRow.Album -> AlbumBubble(
                        session, row.items,
                        senderName = if (isGroup && !row.items.first().fromMe && row.showSender) authorName(row.items.first()) else null,
                        senderAvatarId = if (row.showSender) authorMember(row.items.first())?.avatarMediaId else null,
                        senderAvatarKey = if (row.showSender) authorMember(row.items.first())?.avatarMediaKey else null,
                        onLongPress = { actionMsg = row.items.first() },
                        onSenderClick = if (isGroup && !row.items.first().fromMe) ({ row.items.first().senderUin?.let { if (it != ownUin) onOpenPeerInfo(it) } }) else null,
                        onViewImage = { fullscreenImage = it },
                        onViewVideo = { fullscreenVideo = it },
                        onOpenAlbum = { idx -> albumViewer = row.items to idx },
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
            // A reply-jump return target only makes sense while scrolled up; once
            // the user reaches the bottom on their own, drop it so the arrow goes
            // back to its plain scroll-to-latest role.
            LaunchedEffect(showJumpDown) { if (!showJumpDown) replyReturnIndex = null }
            // #15: badge on the arrow counting unread messages BELOW the fold.
            // We track a high-water mark — the deepest row the user has actually
            // READ — and count messages below THAT, not below the current
            // viewport. So the count only decreases as you scroll down, never
            // re-counts rows you already passed, and never grows when you
            // scroll back up. Only genuinely-new messages arriving below the
            // mark push it up again. (Matches the iOS unreadBelow behavior; fixes
            // the count re-counting after reaching bottom / inflating on scroll-up.)
            //
            // ★ Read means the whole row is on screen, not one pixel of it.
            // `visibleItemsInfo` lists every row that INTERSECTS the viewport, so
            // `lastOrNull()` is whichever row's top edge has just crossed the
            // bottom of the screen. On a long message that meant the badge dropped
            // while not one line of the text had appeared — "текст даже не начал
            // виднеться, а сообщение уже считается прочитанным".
            //
            // ⚠ `afterContentPadding` is not optional: the list carries 8.dp of
            // bottom padding, and without subtracting it the very last message can
            // never be fully visible, leaving the badge stuck at one forever.
            var deepestSeen by remember(threadKey) { mutableStateOf(-1) }
            LaunchedEffect(threadKey) {
                snapshotFlow {
                    val info = listState.layoutInfo
                    val floor = info.viewportEndOffset - info.afterContentPadding
                    // Reaching the end of the list IS reading it to the end, and
                    // it has to be said separately: a message taller than the
                    // screen is never "fully visible", so a chat whose last
                    // message is a long one would sit at the very bottom with
                    // the mark stuck one row short of it (#676).
                    if (!listState.canScrollForward && info.totalItemsCount > 0) {
                        info.totalItemsCount - 1
                    } else {
                        info.visibleItemsInfo.lastOrNull { it.offset + it.size <= floor }?.index ?: -1
                    }
                }
                    // A row taller than the screen is never fully visible, so this
                    // yields -1 and the mark simply waits where it was until the
                    // bottom edge arrives, which is the asked-for behaviour.
                    .collect { last -> if (last > deepestSeen) deepestSeen = last }
            }
            val belowCount by remember(rows) {
                derivedStateOf {
                    if (deepestSeen < 0) return@derivedStateOf 0
                    val from = deepestSeen + 1
                    if (from > rows.lastIndex) 0
                    // Own messages are never unread, so they must not raise the
                    // red badge: sending while scrolled up made it flash "1" for
                    // the message you had just written yourself.
                    else (from..rows.lastIndex).count { i ->
                        when (val r = rows[i]) {
                            is ChatRow.Single -> !r.m.fromMe
                            is ChatRow.Album -> r.items.none { it.fromMe }
                            else -> false
                        }
                    }
                }
            }
            // ⚠ The arrow's rule and the badge's rule disagreed, and the badge
            // lost. The arrow appears once the newest row's TOP edge crosses the
            // bottom of the screen (#19 wanted it that loose), while a message
            // counts as read only when its whole height has been on screen. On a
            // long last message the arrow vanished, taking the badge with it,
            // while the reader had not seen a line of the text (#676). So the
            // count now keeps the arrow up on its own; with everything read
            // `belowCount` is 0 and #19's rule is untouched.
            if (showJumpDown || belowCount > 0) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 10.dp),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Box(
                        Modifier.padding(top = 6.dp, end = 0.dp)
                            .size(40.dp).clip(CircleShape).background(c.bgSecondary)
                            .border(1.dp, c.divider, CircleShape)
                            .clickable {
                                scope.launch {
                                    val ret = replyReturnIndex
                                    if (ret != null) {
                                        // Return to where a reply-quote jump came FROM.
                                        listState.animateScrollToItem(ret.coerceIn(0, rows.lastIndex.coerceAtLeast(0)), replyReturnOffset)
                                        replyReturnIndex = null
                                    } else {
                                        // ⚠ To the true END, not to the top of the
                                        // last row. On a last message taller than
                                        // the screen those are different places,
                                        // and stopping at its top left the badge
                                        // standing (the row is still not fully
                                        // seen) with a button that then did
                                        // nothing when pressed again.
                                        listState.animateScrollToItem(rows.lastIndex.coerceAtLeast(0))
                                        repeat(3) { withFrameNanos {} ; listState.scrollBy(1_000_000f) }
                                    }
                                }
                            },
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
            // A photo produces no row until its upload finishes, so without this
            // the screen is blank for the whole upload and the user cannot tell
            // a slow send from one that never started (#473). Sits directly above
            // the composer, where the message they just sent would appear.
            if (mediaSending > 0) {
                Row(
                    Modifier.fillMaxWidth().background(c.bgSecondary)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Determinate as soon as the socket tells us anything: a
                    // spinner alone cannot distinguish a slow upload from one
                    // that never started (#537, asked for from Shanghai).
                    val p = mediaProgress
                    if (p == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = c.accent,
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { p },
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = c.accent,
                        )
                    }
                    Text(
                        pluralStringResource(R.plurals.chat_media_sending, mediaSending, mediaSending),
                        color = c.textSecondary, fontSize = 13.sp,
                    )
                    if (p != null) {
                        Text(
                            "${(p * 100).toInt()}%",
                            color = c.textSecondary, fontSize = 13.sp,
                        )
                    }
                }
            }
            Composer(
                threadKey = threadKey,
                focusRequester = composerFocus,
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
                    // Both, here and now. Clearing only the state left the
                    // parked note on disk for the moment it took the effect
                    // above to notice — long enough for it to be read back.
                    ChatDrafts.replyByThread.remove(threadKey)
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

    albumViewer?.let { (items, idx) ->
        // Name and type follow the page: a clip is an .mp4 in Movies/RCQ, a
        // picture a .jpg (or .gif) in Pictures/RCQ, like the single viewers.
        fun nameAndMime(m: ChatMessage, b: ByteArray): Pair<String, String> = when {
            m.kind == "video" -> "RCQ_${System.currentTimeMillis()}.mp4" to "video/mp4"
            b.isGif() -> "RCQ_${System.currentTimeMillis()}.gif" to "image/gif"
            else -> "RCQ_${System.currentTimeMillis()}.jpg" to "image/jpeg"
        }
        AlbumPagerViewer(
            session, items, idx,
            onShare = { m, b ->
                val (name, mime) = nameAndMime(m, b)
                MediaSaver.share(context, b, name, mime)
            },
            onSave = { m, b ->
                val (name, mime) = nameAndMime(m, b)
                runSave {
                    val ok = MediaSaver.saveToGallery(context, b, name, mime)
                    val dir = if (m.kind == "video") "Movies/RCQ" else "Pictures/RCQ"
                    val msg = if (ok) context.getString(R.string.media_saved_to, dir) else saveFailToast
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onPlayVideo = { fullscreenVideo = it },
            onDismiss = { albumViewer = null },
        )
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

    fullscreenVideo?.let { bytes ->
        FullscreenVideoViewer(
            bytes,
            onShare = { MediaSaver.share(context, it, "RCQ_${System.currentTimeMillis()}.mp4", "video/mp4") },
            onSave = {
                runSave {
                    val ok = MediaSaver.saveToGallery(context, it, "RCQ_${System.currentTimeMillis()}.mp4", "video/mp4")
                    val msg = if (ok) context.getString(R.string.media_saved_to, "Movies/RCQ") else saveFailToast
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            // A codec this device cannot decode is still worth watching
            // somewhere: fall back to the old behaviour rather than to a black
            // rectangle. This is the ONLY path that writes a decrypted clip out.
            onUnsupported = { openFile(context, it, "video-${System.currentTimeMillis()}.mp4", "video/mp4") },
            onDismiss = { fullscreenVideo = null },
        )
    }

    whoReactedMsg?.let { m ->
        // Who reacted, grouped by reaction asset. Reactions = reactorUin -> asset.
        val byAsset = remember(m.reactions) {
            m.reactions.entries.groupBy { it.value }.entries.sortedByDescending { it.value.size }
        }
        RcqSheet(
            onDismiss = { whoReactedMsg = null },
            title = stringResource(R.string.reactions_who_title),
        ) {
            // Bounded: the dialog used to cap this list for us, a sheet does not,
            // and a long list would push the Done row off the bottom.
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
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
                        // The picture, not just the name. Every other list of
                        // people in the app carries one, and this is the list
                        // where "who was that" is the entire question. The
                        // member row is gated by membership, exactly like the
                        // nickname beside it, so no new exposure.
                        val member = group?.members?.firstOrNull { it.uin == uin }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 28.dp, top = 3.dp, bottom = 3.dp),
                        ) {
                            PersonAvatar(
                                member?.avatarMediaId ?: peerContact?.avatarMediaId?.takeIf { uin == peer },
                                member?.avatarMediaKey ?: peerContact?.avatarMediaKey?.takeIf { uin == peer },
                                member?.presence ?: UserStatus.OFFLINE,
                                session,
                                22.dp,
                                host = group?.host,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(name, color = c.textPrimary, fontSize = 14.sp)
                        }
                    }
                }
            }
            SheetTextRow(stringResource(R.string.common_done)) { whoReactedMsg = null }
        }
    }

    actionMsg?.let { m ->
        // "Delete for everyone" is offered for your own message, OR (in a group)
        // when you're a moderator: the owner, an admin, or a member granted the
        // `delete` cap (founder batch 21.08, item 3; web precedent: Chat.tsx
        // canModerate). Recipients re-check the same rule on receipt.
        // Saved (notes to self) has no "everyone" — you ARE everyone there, and
        // offering both "delete for everyone" and "delete for me" on your own
        // note only asks the user to decide something meaningless. Reported by
        // vss: "У кого «всех»? Надо один пункт просто Удалить."
        val canDeleteAll = !isSelf && (m.fromMe || (group != null && group.moderator(ownUin)))
        // A sheet, not a centre dialog: this is the most-used menu in the app and
        // it belongs under the thumb, next to the message it acts on (iOS has
        // had it as a sheet from the start; on Android everything was a centred
        // AlertDialog, which reads as a system warning rather than a menu).
        ModalBottomSheet(
            onDismissRequest = { actionMsg = null },
            containerColor = c.bgSecondary,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(
                    stringResource(if (m.kind == "photo") R.string.chat_a_photo else R.string.chat_a_message),
                    color = c.textSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
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
                            ) { AnimatedEmoticon(asset, Modifier.size(32.dp)) }
                        }
                    }
                    MessageAction(stringResource(R.string.chat_reply)) { startReply(m); actionMsg = null }
                    if (m.kind == "photo" || m.kind == "video" || m.kind == "file" || m.kind == "voice") {
                        MessageAction(stringResource(R.string.media_share)) { shareMessageMedia(m); actionMsg = null }
                        MessageAction(stringResource(R.string.media_save)) { saveMessageMedia(m); actionMsg = null }
                    }
                    // Pin from chat (owner / info-moderator): copies this message's
                    // text into the single group pin slot, replacing whatever was
                    // there (a chat pin or the settings-entered text — one slot).
                    if (group != null && group.members.firstOrNull { it.uin == ownUin }?.canManageInfo(group.ownerUin) == true) {
                        val pinFallback = stringResource(R.string.chat_pinned_media)
                        val pinFailToast = stringResource(R.string.chat_pin_failed)
                        MessageAction(stringResource(R.string.chat_pin)) {
                            // ⚠ The island's slot is 500 chars (GroupPatchIn.pinned_text) and a
                            // longer body is refused BEFORE the row is written. Unclamped, the
                            // optimistic swap below showed the new pin until the next refresh put
                            // the old one back: a pin that looked like it worked and did nothing.
                            val body = m.body.ifBlank { pinFallback }
                            val text = if (body.length > 500) body.take(499) + "…" else body
                            val previous = group.pinnedText
                            // Optimistic + instant: replace the displayed pin now,
                            // then PATCH (the response reconciles it).
                            session.applyPinnedTextLocally(group.id, text)
                            scope.launch {
                                runCatching { session.patchGroup(group.id, pinnedText = text) }
                                    .onFailure {
                                        session.applyPinnedTextLocally(group.id, previous ?: "")
                                        android.widget.Toast.makeText(context, pinFailToast, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                            }
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
                    MessageAction(
                        stringResource(if (isSelf) R.string.chat_delete else R.string.chat_delete_me),
                        danger = true,
                    ) {
                        // ⚠ In Saved Messages this is the ONLY delete on offer
                        // (`canDeleteAll` is false above — you ARE everyone
                        // there), and it used to be a purely local erase. But a
                        // note has not been local since #469: it ships to your
                        // own number so every device of the account gets it. So
                        // "Удалить" removed the note here and left it standing
                        // on the web and on the other phone, with no reload or
                        // restart able to catch up — report #601. The single
                        // menu item stays; what it means now matches what the
                        // thread actually is, and the retraction rides the same
                        // envelope every other delete does (to my own number,
                        // where the other devices pick it up).
                        if (isSelf) scope.launch { runCatching { session.sendDeleteForEveryone(m) } }
                        else session.deleteLocal(m)
                        actionMsg = null
                    }
                }
            }
        }
    }

    editMsg?.let { m ->
        // TextFieldValue, not String: with a plain String the caret starts at
        // position 0, so opening a long message for editing showed its FIRST
        // line and left the rest below the fold with nothing pointing there.
        // Reported as "текст переходит на новую строку под этим окном,
        // приходится самому прокручивать вверх". Selecting the end makes the
        // field scroll to the caret on its own, which is also where you want to
        // type.
        var editValue by remember(m.id) {
            mutableStateOf(TextFieldValue(m.body, selection = TextRange(m.body.length)))
        }
        // A sheet, not a centre dialog: the old AlertDialog was its OWN sub-window
        // and did NOT inherit the activity's adjustResize, so for a long message the
        // keyboard covered the field and the user typed blind (it needed
        // decorFitsSystemWindows=false + imePadding to work around that). The sheet
        // comes up with the keyboard, and the bounded+scrolling field still keeps the
        // caret visible instead of growing tall.
        RcqSheet(onDismiss = { editMsg = null }, title = stringResource(R.string.chat_edit_title)) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bgPrimary).padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                // ⚠⚠ `maxLines`, NOT `heightIn(max).verticalScroll()`. The two look
                // identical — an 8-line box you can scroll — and only one of them
                // follows the caret.
                //
                // An external `verticalScroll` measures its child with an INFINITE
                // height, so the field lays itself out at its full height, never
                // clips, and its own caret-following scroll has nothing to do. The
                // outer scroller, for its part, knows about a text field but not
                // about a caret inside one, so it stays where it is. Result: the box
                // shows the first eight lines of the message for ever and you type
                // line nine blind — founder, "не видно что печатаешь, когда длинное
                // сообщение".
                //
                // `maxLines` bounds the field from the inside (Compose turns it into
                // a height constraint on the text itself), which switches on the
                // field's built-in scroll — the one that exists precisely to keep the
                // caret in view. Same visible box, and now it follows you down.
                // This is what the chat composer has always done; the edit field was
                // the one place that grew its own scroller instead.
                BasicTextField(
                    value = editValue,
                    onValueChange = { editValue = it },
                    textStyle = TextStyle(color = c.textPrimary, fontSize = 15.sp),
                    cursorBrush = SolidColor(c.accent),
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SheetGap()
            SheetTextRow(stringResource(R.string.common_save)) {
                val newText = editValue.text.trim()
                val orig = m
                // Was the reader sitting at the newest message before the sheet
                // opened? An edit rewrites the bubble in place (same id), so the
                // stick-to-bottom effect, keyed on the last id, never fires, and
                // the keyboard's shrink then regrow around a changed bubble
                // height leaves the list resting just above the end with the
                // newest row off-screen and the jump-to-latest chevron showing
                // (#698: "a keyboard icon stays in the corner"). If they were at
                // the end, put them back there once the layout settles.
                val wasAtEnd = !listState.canScrollForward
                editMsg = null
                if (newText.isNotEmpty() && newText != orig.body) {
                    scope.launch { runCatching { session.sendEdit(orig, newText) } }
                }
                if (wasAtEnd) scope.launch {
                    repeat(6) { withFrameNanos {} ; listState.scrollBy(1_000_000f) }
                }
            }
            SheetTextRow(stringResource(R.string.common_cancel), dimmed = true) { editMsg = null }
        }
    }

    if (attachMenu) {
        ModalBottomSheet(
            onDismissRequest = { attachMenu = false },
            containerColor = c.bgSecondary,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(
                    stringResource(R.string.chat_attach),
                    color = c.textSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
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
                    MessageAction(stringResource(R.string.relay_share_attach)) { attachMenu = false; showRelayPicker = true }
                }
            }
        }
    }

    // Pre-send preview: tap the media to mark it a spoiler, then Send.
    // A failed upload used to be indistinguishable from a successful one:
    // the dialog closed and nothing happened. Say it out loud, once.
    LaunchedEffect(mediaFailed) {
        if (mediaFailed > 0) {
            android.widget.Toast.makeText(context, mediaFailToast, android.widget.Toast.LENGTH_LONG).show()
            session.clearMediaSendFailed()
        }
    }

    pendingSend?.let { ps ->
        MediaPreviewDialog(
            pending = ps,
            onCancel = { pendingSend = null },
            onSend = { spoiler, caption ->
                pendingSend = null
                // NOT on this screen's scope: leaving the chat used to cancel the
                // upload and lose the picture without a word (#473).
                session.sendMediaDetached(if (ps is PendingSend.Video) "video" else "photo") {
                    when (ps) {
                        is PendingSend.Photo ->
                            if (isGroup) session.sendGroupPhoto(groupId!!, ps.bytes, caption, spoiler)
                            else session.sendPhoto(peer!!, ps.bytes, caption, spoiler)
                        is PendingSend.Video ->
                            if (isGroup) session.sendGroupVideo(groupId!!, ps.v.bytes, ps.v.thumbB64, ps.v.durationSec, caption, spoiler)
                            else session.sendVideo(peer!!, ps.v.bytes, ps.v.thumbB64, ps.v.durationSec, caption, spoiler)
                    }
                }
            },
        )
    }

    // Share a group invite into this chat: pick one of your groups, send its
    // canonical link as a text message (renders as a join card on both ends).
    if (showGroupPicker) {
        RcqSheet(
            onDismiss = { showGroupPicker = false },
            title = stringResource(R.string.chat_attach_group),
        ) {
            if (groups.isEmpty()) {
                Text(stringResource(R.string.group_invite_none), color = c.textSecondary)
            } else {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
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
            SheetTextRow(stringResource(R.string.common_cancel), dimmed = true) { showGroupPicker = false }
        }
    }

    // In-chat bridge sharing: pick a relay from your pool to hand the peer so
    // they can route through it when their own relays are blocked.
    if (showRelayPicker) {
        val pool = remember { session.shareableRelays() }
        RcqSheet(
            onDismiss = { showRelayPicker = false },
            title = stringResource(R.string.relay_share_pick_title),
        ) {
            Text(stringResource(R.string.relay_share_pick_body), color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            if (groupId != null) Text(stringResource(R.string.relay_share_group_warn), color = c.statusBusy, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                pool.forEach { r ->
                    MessageAction("${r.proto.uppercase()} · ${r.server}:${r.port}") {
                        showRelayPicker = false
                        val gid = groupId
                        val p = peer
                        scope.launch { runCatching {
                            if (gid != null) session.shareRelayToGroup(gid, r) else p?.let { session.shareRelay(it, r) }
                        } }
                    }
                }
            }
            SheetTextRow(stringResource(R.string.common_cancel), dimmed = true) { showRelayPicker = false }
        }
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
            onOpenVideo = { m -> mediaBytes(m) { fullscreenVideo = it } },
        )
    }
    if (confirmClearThread) {
        val threadName = when {
            isGroup -> group?.name ?: stringResource(R.string.chat_group)
            isSelf -> stringResource(R.string.chat_saved_title)
            else -> session.contactName(peer ?: 0)
        }
        // Three cases, not two. Saved messages are a thread with yourself,
        // and it used to fall through to the 1:1 copy — which promised the
        // messages would stay with "your contact" and warned about someone
        // who is not there (#413).
        RcqAskSheet(
            onDismiss = { confirmClearThread = false },
            title = stringResource(if (isSelf) R.string.home_clear_chat_self else R.string.home_clear_chat),
            body = if (isSelf) stringResource(R.string.home_clear_chat_body_self)
            else stringResource(
                if (isGroup) R.string.home_clear_chat_body_group else R.string.home_clear_chat_body,
                threadName,
            ),
            actions = listOf(
                SheetAction(stringResource(R.string.home_clear_chat_confirm), destructive = true) {
                    confirmClearThread = false
                    when (target) {
                        is ChatTarget.Peer -> session.clearPeerThread(target.uin)
                        is ChatTarget.Group -> session.clearGroupThread(target.id)
                    }
                    // Nothing left to show here.
                    onBack()
                },
            ),
        )
    }
}

@Composable
private fun Composer(
    threadKey: String,
    // Owned by ChatScreen so choosing "reply" can put the caret in here and
    // raise the keyboard without the user tapping the field first.
    focusRequester: FocusRequester,
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
    // TextFieldValue, not String: the caret position has to be readable, or
    // anything inserted from outside the keyboard lands at the end of the draft
    // instead of where the user is typing (reported on 0.100 for the emoticon
    // panel). Everything downstream still reads the plain text through `draft`.
    var field by remember(threadKey) {
        mutableStateOf((ChatDrafts.byThread[threadKey] ?: "").let { TextFieldValue(it, TextRange(it.length)) })
    }
    val draft = field.text
    val persistDraft: (String) -> Unit = { v ->
        if (v.isBlank()) ChatDrafts.byThread.remove(threadKey) else ChatDrafts.byThread[threadKey] = v
        onTyping(v.isNotBlank())
    }
    // Replace the whole draft and park the caret at its end. Used where the new
    // text IS the whole draft (mention autocomplete, clearing after send).
    val setDraft: (String) -> Unit = { v ->
        field = TextFieldValue(v, TextRange(v.length))
        persistDraft(v)
    }
    // Insert AT the caret, replacing any selection, and leave the caret after
    // what was inserted so a second pick continues where the first left off.
    val insertAtCaret: (String) -> Unit = { s ->
        val text = field.text
        val start = minOf(field.selection.start, field.selection.end).coerceIn(0, text.length)
        val end = maxOf(field.selection.start, field.selection.end).coerceIn(start, text.length)
        val next = text.substring(0, start) + s + text.substring(end)
        field = TextFieldValue(next, TextRange(start + s.length))
        persistDraft(next)
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
        if (showEmoji && !recording) EmoticonPanel(onPick = insertAtCaret)
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
                        value = field,
                        onValueChange = { v ->
                            val changed = v.text != field.text
                            field = v
                            if (changed) persistDraft(v.text)
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(color = c.textPrimary, fontSize = 15.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                            .onFocusChanged { if (it.isFocused) showEmoji = false },
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
                                field = TextFieldValue("")
                                ChatDrafts.byThread.remove(threadKey)
                                // The emoticon panel stayed up after sending, so
                                // the next message was composed against a keyboard
                                // that wasn't there (reported on 0.100).
                                showEmoji = false
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

/** RcqAskSheet grows its own Cancel; a custom-body [RcqSheet] does not, so the
 *  sheets in this file carry their confirm/cancel rows by hand. `dimmed` is the
 *  weight a Cancel had as a dialog button. */
@Composable
private fun SheetTextRow(label: String, dimmed: Boolean = false, onClick: () -> Unit) {
    val c = RcqTheme.colors
    Text(
        label,
        color = if (dimmed) c.textSecondary else c.accent,
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(vertical = 14.dp),
    )
}

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

/** Centered call-summary line (kind == "call"): a direction arrow, the
 *  localized "Voice call · 1:23" / "Missed call" text logged by
 *  [CallController], and when it started.
 *
 *  The glyph used to be a plain handset for every case, so the log could not
 *  say who had called whom — the reporter had to open the call to find out.
 *  An arrow in / arrow out is the universal idiom for exactly that and costs
 *  no width. The time sits beside the duration because "1:53" alone does not
 *  say when. */
@Composable
private fun CallHistoryRow(m: ChatMessage) {
    val c = RcqTheme.colors
    // On a wallpaper the bare grey line washes out (#648) — same contrast
    // pill the date and unread dividers ride; nothing changes without one.
    val onWallpaper = LocalStores.chatBackground.collectAsState().value.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (onWallpaper) Modifier.clip(RoundedCornerShape(10.dp)).background(c.bgSecondary.copy(alpha = 0.85f)).padding(horizontal = 10.dp, vertical = 3.dp) else Modifier,
        ) {
            Icon(
                if (m.fromMe) Icons.AutoMirrored.Filled.CallMade
                else Icons.AutoMirrored.Filled.CallReceived,
                null,
                tint = c.textSecondary,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "${m.body} · ${formatTime(m.sentAt)}",
                color = c.textSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

/** Centered system notice (kind == "system"), e.g. "X took a screenshot". */
@Composable
private fun SystemNoticeRow(m: ChatMessage) {
    val c = RcqTheme.colors
    // Same pill-on-wallpaper rule as the call row above (#648).
    val onWallpaper = LocalStores.chatBackground.collectAsState().value.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (onWallpaper) Modifier.clip(RoundedCornerShape(10.dp)).background(c.bgSecondary.copy(alpha = 0.85f)).padding(horizontal = 10.dp, vertical = 3.dp) else Modifier,
        ) {
            Icon(Icons.Filled.Shield, null, tint = c.textSecondary, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(m.body, color = c.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
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
        RcqAskSheet(
            onDismiss = { if (!joining) showJoin = false },
            title = p?.name ?: stringResource(if (foreignHost != null) R.string.group_invite_island else R.string.group_invite_title),
            body = if (p != null) pluralStringResource(R.plurals.members, p.member_count, p.member_count)
            else stringResource(R.string.group_invite_island_hint, foreignHost ?: ""),
            actions = listOf(
                // Greyed and inert while a join is in flight — the row stands in for
                // a button that was `enabled = !joining`.
                SheetAction(stringResource(R.string.group_invite_join), dimmed = joining) {
                    if (!joining) {
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
                    }
                },
            ),
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
        RcqSheet(onDismiss = { showPinSheet = false }, title = stringResource(R.string.gi_pinned)) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
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
            SheetTextRow(stringResource(R.string.common_close)) { showPinSheet = false }
        }
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
        RcqAskSheet(
            onDismiss = { if (!joining) showJoin = false },
            title = p?.name ?: stringResource(if (foreignHost != null) R.string.group_invite_island else R.string.group_invite_title),
            body = if (p != null) pluralStringResource(R.plurals.members, p.member_count, p.member_count)
            else stringResource(R.string.group_invite_island_hint, foreignHost ?: ""),
            actions = listOf(
                // Same as GroupLinkBubble: dimmed and inert while the join runs.
                SheetAction(stringResource(R.string.group_invite_join), dimmed = joining) {
                    if (!joining) {
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
                    }
                },
            ),
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
private fun MediaPreviewDialog(pending: PendingSend, onCancel: () -> Unit, onSend: (Boolean, String?) -> Unit) {
    val c = RcqTheme.colors
    var spoiler by remember { mutableStateOf(false) }
    // The wire has carried a caption on photo/video envelopes from the start,
    // the store keeps it and the bubble draws it — there was simply nowhere to
    // type one, so every call site passed null and nobody could send a picture
    // with a line under it.
    var caption by remember { mutableStateOf("") }
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
    RcqSheet(onDismiss = onCancel, title = stringResource(R.string.chat_media_preview_title)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
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
            RcqField(
                value = caption,
                onValueChange = { if (it.length <= 1000) caption = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(R.string.chat_caption_hint),
                singleLine = false,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (spoiler) R.string.chat_spoiler_on_hint else R.string.chat_spoiler_off_hint),
                color = c.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center,
            )
        }
        SheetGap()
        SheetTextRow(stringResource(R.string.chat_send)) { onSend(spoiler, caption.trim().takeIf { it.isNotEmpty() }) }
        SheetTextRow(stringResource(R.string.common_cancel), dimmed = true, onClick = onCancel)
    }
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
    val onWallpaper = LocalStores.chatBackground.collectAsState().value.isNotEmpty()
    if (onWallpaper) {
        // Same treatment as the date divider right above: bare accent text and
        // hairlines wash out on a wallpaper (#648 — a light wallpaper made the
        // marker unreadable), so the label rides a contrast pill instead.
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                label, color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.bgSecondary.copy(alpha = 0.85f)).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        return
    }
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
private fun AlbumBubble(session: Session, items: List<ChatMessage>, senderName: String?, senderAvatarId: String? = null, senderAvatarKey: String? = null, onLongPress: () -> Unit, onSenderClick: (() -> Unit)? = null, onViewImage: (ByteArray) -> Unit = {}, onViewVideo: (ByteArray) -> Unit = {}, onOpenAlbum: (Int) -> Unit = {}) {
    val c = RcqTheme.colors
    val first = items.first()
    val last = items.last()
    // Match MessageBubble: on a chat wallpaper the time/ticks footer washes out
    // on the gradient, so give it the same contrast pill. No-op on default chat.
    val onWallpaper = LocalStores.chatBackground.collectAsState().value.isNotEmpty()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (first.fromMe) Alignment.End else Alignment.Start) {
        if (senderName != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(start = 4.dp, bottom = 1.dp)
                    .then(if (onSenderClick != null) Modifier.clickable { onSenderClick() } else Modifier),
            ) {
                SenderAvatar(senderAvatarId, senderAvatarKey, session, 15.dp)
                Text(senderName, color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        AlbumGrid(session, items, onLongPress, onViewImage, onViewVideo, onOpenAlbum)
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
private fun AlbumGrid(
    session: Session,
    items: List<ChatMessage>,
    onLongPress: () -> Unit,
    onViewImage: (ByteArray) -> Unit = {},
    onViewVideo: (ByteArray) -> Unit = {},
    onOpenAlbum: (Int) -> Unit = {},
) {
    val maxW = 240.dp
    val sp = 3.dp
    val half = (maxW - sp) / 2f
    val count = minOf(items.size, 4)
    Box {
        val gridMod = Modifier.clip(RoundedCornerShape(12.dp))
        when (count) {
            2 -> Row(gridMod, horizontalArrangement = Arrangement.spacedBy(sp)) {
                AlbumTile(session, items[0], half, maxW * 0.5f, onLongPress, onViewImage, onViewVideo, onOpenAlbum = { onOpenAlbum(0) })
                AlbumTile(session, items[1], half, maxW * 0.5f, onLongPress, onViewImage, onViewVideo, onOpenAlbum = { onOpenAlbum(1) })
            }
            3 -> Column(gridMod, verticalArrangement = Arrangement.spacedBy(sp)) {
                AlbumTile(session, items[0], maxW, maxW * 0.55f, onLongPress, onViewImage, onViewVideo, onOpenAlbum = { onOpenAlbum(0) })
                Row(horizontalArrangement = Arrangement.spacedBy(sp)) {
                    AlbumTile(session, items[1], half, maxW * 0.385f, onLongPress, onViewImage, onViewVideo, onOpenAlbum = { onOpenAlbum(1) })
                    AlbumTile(session, items[2], half, maxW * 0.385f, onLongPress, onViewImage, onViewVideo, onOpenAlbum = { onOpenAlbum(2) })
                }
            }
            else -> Column(gridMod, verticalArrangement = Arrangement.spacedBy(sp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(sp)) {
                    AlbumTile(session, items[0], half, maxW * 0.5f, onLongPress, onViewImage, onViewVideo, onOpenAlbum = { onOpenAlbum(0) })
                    AlbumTile(session, items[1], half, maxW * 0.5f, onLongPress, onViewImage, onViewVideo, onOpenAlbum = { onOpenAlbum(1) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(sp)) {
                    AlbumTile(session, items[2], half, maxW * 0.5f, onLongPress, onViewImage, onViewVideo, onOpenAlbum = { onOpenAlbum(2) })
                    Box(contentAlignment = Alignment.Center) {
                        AlbumTile(session, items[3], half, maxW * 0.5f, onLongPress, onViewImage, onViewVideo, onOpenAlbum = { onOpenAlbum(3) })
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
private fun AlbumTile(
    session: Session,
    m: ChatMessage,
    w: Dp,
    h: Dp,
    onLongPress: () -> Unit,
    onViewImage: (ByteArray) -> Unit = {},
    onViewVideo: (ByteArray) -> Unit = {},
    /** Open the whole album at this tile, so everything in it can be reached
     *  by swiping. Null for the callers that have no album around them. */
    onOpenAlbum: (() -> Unit)? = null,
) {
    val c = RcqTheme.colors
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
                // In an album, a tap opens the ALBUM at this picture. Opening
                // the single tapped file was why only what the grid happened to
                // show could be looked at: a batch of ten had four reachable
                // and six that existed nowhere in the interface (#691, #675,
                // #689). A clip opens the album at its page as well: the
                // pager shows its poster and hands it to the player on tap.
                if (onOpenAlbum != null) { onOpenAlbum(); return@combinedClickable }
                val mid = m.mediaId; val key = m.mediaKey
                if (mid != null && key != null) scope.launch {
                    // An album tile used to hand BOTH kinds to an external app,
                    // so the same photo opened in-app from a single bubble and
                    // in the gallery app from an album. Both stay in-app now.
                    val bytes = session.fetchImage(mid, key, m.groupId?.let { session.groupHost(it) })
                    if (bytes != null) {
                        if (isVideo) onViewVideo(bytes) else onViewImage(bytes)
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

/// Swipe-to-reply. Drag a message past the threshold and RELEASE, and it
/// becomes the reply target.
///
/// Reported by a user: "нажал на сообщение влево видешь, оно выделяется чтоб
/// ответить, так ускоряет процесс, а то сейчас надо держать на него чтоб
/// выбрать ответить". Long-press → Reply still works and is still the only way
/// to reach the other actions; this is the shortcut for the one action people
/// use constantly.
///
/// ⚠ It used to commit the moment the finger crossed the threshold, which made
/// the gesture impossible to take back: you were quoting before you had decided
/// to (#526). Now crossing the threshold only ARMS it — with the haptic where
/// the finger feels the catch, and a second one if you drag back out of it —
/// and the release is what commits. Drag back before lifting and nothing
/// happens. The row springs back either way; the reply composer opening is the
/// feedback that it worked, not a message left sitting off-centre.
///
/// [side] is the user's choice, because there is no right answer: Telegram
/// pulls left, WhatsApp and Signal pull right.
@Composable
private fun SwipeToReply(
    side: LocalStores.SwipeReplySide,
    onReply: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val density = LocalDensity.current
    val threshold = with(density) { 56.dp.toPx() }
    val maxDrag = threshold * 1.4f
    // -1 pulls the row left, +1 right. Everything below is written in terms of
    // travel TOWARDS the chosen side, so there is one code path, not two.
    val dir = if (side == LocalStores.SwipeReplySide.LEFT) -1f else 1f
    // The pointer node outlives a recomposition, so it must not capture the
    // first `onReply` it ever saw (the target message changes per row).
    val reply by rememberUpdatedState(onReply)
    // The finger's own position. `offsetX` is an Animatable written from a
    // launched coroutine, so it lags within a frame — fine to RENDER from,
    // wrong to make decisions from.
    var raw by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }

    Box(
        Modifier.pointerInput(dir) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    // The one commit point: past the threshold AT RELEASE.
                    if (armed) reply()
                    armed = false
                    raw = 0f
                    scope.launch { offsetX.animateTo(0f) }
                },
                onDragCancel = {
                    armed = false
                    raw = 0f
                    scope.launch { offsetX.animateTo(0f) }
                },
            ) { change, dragAmount ->
                // Only travel towards the chosen side moves the row; a drag the
                // other way on a row that has not moved is left alone so it can
                // still reach whatever else wants horizontal gestures.
                val next = (raw + dragAmount).let {
                    if (dir < 0f) it.coerceIn(-maxDrag, 0f) else it.coerceIn(0f, maxDrag)
                }
                if (next != raw) change.consume()
                raw = next
                scope.launch { offsetX.snapTo(next) }
                val past = next * dir >= threshold
                if (past != armed) {
                    armed = past
                    // Haptic on catching it AND on letting it go, so the finger
                    // knows the state without the eyes.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
        },
    ) {
        val progress = (offsetX.value * dir / threshold).coerceIn(0f, 1f)
        // The arrow rides in from under the edge the row is heading towards, so
        // the gesture says what it is doing before it happens.
        if (progress > 0.02f) {
            Icon(
                if (dir < 0f) Icons.AutoMirrored.Filled.ArrowBack else Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = RcqTheme.colors.textSecondary.copy(alpha = progress),
                modifier = Modifier
                    .align(if (dir < 0f) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 12.dp)
                    .size(20.dp),
            )
        }
        Box(Modifier.offset { IntOffset(offsetX.value.roundToInt(), 0) }) { content() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(session: Session, m: ChatMessage, senderName: String?, senderAvatarId: String? = null, senderAvatarKey: String? = null, onRetry: () -> Unit, onLongPress: () -> Unit, onOpenGroup: (Int) -> Unit = {}, onViewImage: (ByteArray) -> Unit = {}, onViewVideo: (ByteArray) -> Unit = {}, mentionNick: ((Int) -> String?)? = null, onMentionClick: ((Int) -> Unit)? = null, mentionMatch: ((String, Int) -> Pair<Int, Int>?)? = null, highlighted: Boolean = false, onTapReply: ((String) -> Unit)? = null, onSenderClick: (() -> Unit)? = null, onShowReactors: (ChatMessage) -> Unit = {}, replyAuthorOverride: String? = null) {
    val c = RcqTheme.colors
    val failed = m.state == DeliveryState.FAILED
    // When a chat wallpaper is set, the time/ticks row sits on the wallpaper
    // (not on a bubble), so the gray text washes out on a gradient — give it a
    // bubble-like pill for contrast. No-op on the default (no-wallpaper) chat.
    val onWallpaper = LocalStores.chatBackground.collectAsState().value.isNotEmpty()
    // Cap a bubble so a long message leaves a gap to the far edge — keeps the
    // L/R alignment (mine vs theirs) readable, not just the colour (tester #7).
    // 0.86, not 0.78: the gap only has to be wide enough to read the alignment
    // (and to hold the per-message buttons that will live there), and a tester
    // pointed out we were spending a fifth of the screen on empty margin.
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.86f).dp
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(start = 4.dp, bottom = 1.dp)
                    .then(if (onSenderClick != null) Modifier.clickable { onSenderClick() } else Modifier),
            ) {
                // Beside the nick, never instead of it, and only when there is
                // a picture: without one the line stays what it always was.
                SenderAvatar(senderAvatarId, senderAvatarKey, session, 15.dp)
                Text(senderName, color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
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
            VideoBubble(session, m, onLongPress, onViewVideo)
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.padding(bottom = 2.dp)
                            .then(if (onSenderClick != null) Modifier.clickable { onSenderClick() } else Modifier),
                    ) {
                        SenderAvatar(senderAvatarId, senderAvatarKey, session, 16.dp)
                        Text(senderName, color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
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
                    mentionNick = mentionNick, onMentionClick = onMentionClick, mentionMatch = mentionMatch,
                    maxLines = if (collapsed) 14 else Int.MAX_VALUE,
                    onTextLayout = { if (collapsed) bodyOverflow = it.hasVisualOverflow },
                    // The bubble's long-press has to survive on top of a link:
                    // Compose gives every gesture inside one to the link itself.
                    onLongPress = onLongPress,
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

/** The whole album, full screen, one item per page.
 *
 *  ⚠ The grid draws four tiles however many items the batch holds, and a tap
 *  used to open the ONE file under the finger. So a batch of ten had four you
 *  could look at and six that existed nowhere in the interface: no swipe, no
 *  list, nothing (#691, #675, #689). Every item of the batch is a page here,
 *  the counter says where you are, and a photo page fetches its bytes when it
 *  comes into view rather than pulling ten files down to open one.
 *
 *  A video is a page too, as its poster with a play disc: the first cut paged
 *  photos only, and a batch of five clips, or four clips ahead of two photos,
 *  had the same unreachable tail as before (found in review). Tapping the disc
 *  hands the clip to the video player above this dialog; closing the player
 *  lands back on the same page. Nothing of a clip is fetched until asked. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AlbumPagerViewer(
    session: Session,
    items: List<ChatMessage>,
    startIndex: Int,
    onShare: (ChatMessage, ByteArray) -> Unit = { _, _ -> },
    onSave: (ChatMessage, ByteArray) -> Unit = { _, _ -> },
    onPlayVideo: (ByteArray) -> Unit = {},
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) { onDismiss(); return }
    val scope = rememberCoroutineScope()
    val pager = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = startIndex.coerceIn(0, items.size - 1), pageCount = { items.size },
    )
    // One copy of each page's bytes, shared between the page and the
    // save/share buttons. Two produceState()s on the same id used to race
    // past the cache and download the same blob twice (found in review).
    // Pictures only: a clip can be tens of megabytes and would otherwise sit
    // here for the dialog's life after one play; the session's own cache
    // covers a second play.
    val loaded = remember(items) { mutableStateMapOf<String, ByteArray>() }
    suspend fun bytesOf(m: ChatMessage): ByteArray? {
        loaded[m.id]?.let { return it }
        val mid = m.mediaId ?: return null
        val key = m.mediaKey ?: return null
        return session.fetchImage(mid, key, m.groupId?.let { session.groupHost(it) })
            ?.also { if (m.kind != "video") loaded[m.id] = it }
    }
    // Save/share fetch on the tap (a clip is not pulled down for the buttons);
    // while that runs the buttons are a spinner, not two more downloads.
    var actionBusy by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            androidx.compose.foundation.pager.HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val m = items[page]
                val isVideo = m.kind == "video"
                if (isVideo) {
                    // Poster from the bubble's own thumbnail; the clip itself
                    // is fetched only when the disc is tapped.
                    val poster = remember(m.id) {
                        m.thumbB64?.takeIf { it.isNotEmpty() }?.let {
                            runCatching {
                                val b = android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
                                BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
                            }.getOrNull()
                        }
                    }
                    var fetching by remember(m.id) { mutableStateOf(false) }
                    var playFailed by remember(m.id) { mutableStateOf(false) }
                    Box(
                        Modifier.fillMaxSize().clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (poster != null) {
                            Image(bitmap = poster, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        }
                        if (fetching) {
                            CircularProgressIndicator(color = RcqTheme.colors.accent)
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                ViewerAction(Icons.Filled.PlayArrow, stringResource(R.string.chat_play_video), Modifier.size(72.dp)) {
                                    if (fetching) return@ViewerAction
                                    fetching = true; playFailed = false
                                    scope.launch {
                                        val b = bytesOf(m)
                                        fetching = false
                                        if (b != null) onPlayVideo(b) else playFailed = true
                                    }
                                }
                                // A fetch that came back empty used to leave a
                                // disc that did nothing. Say so; the disc retries.
                                if (playFailed) Text(stringResource(R.string.media_load_failed), color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                } else {
                    // `attempt` re-keys the fetch so a tap on the failure text
                    // tries again; a null after a finished attempt is a failure,
                    // not "still loading".
                    var attempt by remember(m.id) { mutableStateOf(0) }
                    var tried by remember(m.id) { mutableStateOf(false) }
                    val bytes by produceState<ByteArray?>(initialValue = loaded[m.id], m.id, attempt) {
                        if (value == null) { tried = false; value = bytesOf(m); tried = true }
                    }
                    var scale by remember(m.id) { mutableStateOf(1f) }
                    var offset by remember(m.id) { mutableStateOf(Offset.Zero) }
                    val transform = rememberTransformableState { zoomChange, panChange, _ ->
                        scale = (scale * zoomChange).coerceIn(1f, 5f)
                        offset = if (scale > 1f) offset + panChange else Offset.Zero
                    }
                    Box(
                        Modifier.fillMaxSize().clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        val b = bytes
                        when {
                            b == null && tried -> Text(
                                stringResource(R.string.media_load_failed), color = Color.White, fontSize = 14.sp,
                                modifier = Modifier.clickable { attempt += 1 }.padding(24.dp),
                            )
                            b == null -> CircularProgressIndicator(color = RcqTheme.colors.accent)
                            b.isGif() -> SafeAnimatedGif(b, Modifier.fillMaxWidth())
                            else -> rememberSampledBitmap(b, maxPx = 2560)?.let { img ->
                                Image(
                                    bitmap = img,
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                        // ⚠ Seen with my own eyes: with a plain
                                        // transformable() the pager never turned a
                                        // page. Any drag past touch slop is a pan to
                                        // it, consumed before the pager sees it. At
                                        // scale 1 there is nothing to pan, so the
                                        // drag is handed on: one finger turns pages,
                                        // two fingers zoom, and a zoomed picture pans
                                        // instead of flipping.
                                        .transformable(transform, canPan = { scale > 1f })
                                        .graphicsLayer(
                                            scaleX = scale, scaleY = scale,
                                            translationX = offset.x, translationY = offset.y,
                                        ),
                                )
                            }
                        }
                    }
                }
            }
            val current = items.getOrNull(pager.currentPage)
            ViewerAction(Icons.Filled.Close, stringResource(R.string.common_close),
                Modifier.align(Alignment.TopEnd).padding(16.dp), onDismiss)
            if (current != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                ) {
                    // Save and share act on the page you are looking at. A photo
                    // page has its bytes already; a clip is fetched on the tap.
                    if (actionBusy) {
                        CircularProgressIndicator(color = RcqTheme.colors.accent, modifier = Modifier.size(36.dp).padding(6.dp), strokeWidth = 2.dp)
                    } else {
                        ViewerAction(Icons.Filled.Download, stringResource(R.string.media_save)) {
                            actionBusy = true
                            scope.launch { try { bytesOf(current)?.let { onSave(current, it) } } finally { actionBusy = false } }
                        }
                        ViewerAction(Icons.Filled.Share, stringResource(R.string.media_share)) {
                            actionBusy = true
                            scope.launch { try { bytesOf(current)?.let { onShare(current, it) } } finally { actionBusy = false } }
                        }
                    }
                }
            }
            if (items.size > 1) {
                Text(
                    "${pager.currentPage + 1} / ${items.size}",
                    color = Color.White, fontSize = 13.sp,
                    // The dialog is laid out under the system bar and its own
                    // insets read zero (see activityNavigationBarBottom): the
                    // bar's real height plus a margin, whatever kind of bar.
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = activityNavigationBarBottom() + 16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
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
            // White glyphs laid straight on the photo disappear over a light
            // one — "не видно или видно еле-еле, смотря какой фон" (tester).
            // A scrim behind each one makes them legible over anything, and
            // makes the tap target the whole disc rather than the strokes.
            ViewerAction(Icons.Filled.Close, stringResource(R.string.common_close),
                Modifier.align(Alignment.TopEnd).padding(16.dp), onDismiss)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            ) {
                ViewerAction(Icons.Filled.Download, stringResource(R.string.media_save)) { onSave(bytes) }
                ViewerAction(Icons.Filled.Share, stringResource(R.string.media_share)) { onShare(bytes) }
            }
        }
    }
}

/// One control of the full-screen media viewer: a white glyph on a dark disc,
/// so it stays readable whatever the photo underneath happens to be.
/// Shared with the video viewer (VideoViewer.kt) — the two must not drift into
/// two different-looking sets of buttons.
@Composable
internal fun ViewerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

/// Audio the platform decoder actually handles. Anything outside this list
/// keeps the old behaviour and opens in another app: a play button that
/// produces silence is worse than no play button.
///
/// The extension check is not belt-and-braces, it is load-bearing: document
/// pickers routinely hand back `application/octet-stream` for .opus and .flac,
/// and audio-only .m4a often arrives labelled `video/mp4`.
private val PLAYABLE_AUDIO_EXT = setOf(
    "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "amr", "3gp", "mka",
)

private fun isPlayableAudio(mime: String?, name: String?): Boolean {
    val ext = name?.substringAfterLast('.', "")?.lowercase()
    if (ext != null && ext in PLAYABLE_AUDIO_EXT) return true
    val m = mime?.lowercase() ?: return false
    if (!m.startsWith("audio/")) return false
    // WMA and ALAC are `audio/*` and are NOT decodable on stock Android.
    return !m.contains("x-ms-wma") && !m.contains("alac") && !m.contains("aiff")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileBubble(session: Session, m: ChatMessage, onLongPress: () -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playable = isPlayableAudio(m.fileMime, m.fileName)
    // Keyed off the shared player, never off local state: a recycled row would
    // otherwise draw someone else's pause glyph.
    val isCurrent = AudioPlayer.playingId == m.id
    val loading = AudioPlayer.loadingId == m.id

    val openExternally = {
        val mid = m.mediaId; val key = m.mediaKey
        if (mid != null && key != null) scope.launch {
            val bytes = session.fetchImage(mid, key, m.groupId?.let { session.groupHost(it) })
            if (bytes != null) openFile(context, bytes, m.fileName ?: "file", m.fileMime ?: "application/octet-stream")
        }
        Unit
    }

    val playTap = {
        if (isCurrent) {
            AudioPlayer.toggle(context, m.id, java.io.File(context.cacheDir, "files"))
            Unit
        } else {
            val mid = m.mediaId; val key = m.mediaKey
            if (mid != null && key != null) scope.launch {
                AudioPlayer.setLoading(m.id)
                val bytes = try {
                    session.fetchImage(mid, key, m.groupId?.let { session.groupHost(it) })
                } finally {
                    AudioPlayer.setLoading(null)
                }
                if (bytes != null) {
                    val dir = java.io.File(context.cacheDir, "files").apply { mkdirs() }
                    val ext = m.fileName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() } ?: "m4a"
                    val f = java.io.File(dir, "audio-${m.id}.$ext")
                    if (!f.exists() || f.length() == 0L) f.writeBytes(bytes)
                    // A codec this device cannot decode falls back to whatever
                    // app the person already uses for music.
                    AudioPlayer.toggle(context, m.id, f, onError = { openExternally() })
                }
            }
            Unit
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (m.fromMe) c.bubbleSelf else c.bubbleOther)
            .combinedClickable(
                // Tapping the row still opens the file elsewhere; only the
                // round button plays it here.
                onClick = { if (playable) playTap() else openExternally() },
                onLongClick = onLongPress,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (playable) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(c.accent.copy(alpha = 0.15f))
                    .clickable { playTap() },
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (isCurrent && AudioPlayer.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (isCurrent && AudioPlayer.isPlaying) R.string.chat_pause_audio else R.string.chat_play_audio),
                        tint = c.accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        } else {
            Icon(Icons.Filled.Description, null, tint = c.accent, modifier = Modifier.size(24.dp))
        }
        Column {
            Text(m.fileName ?: "file", color = c.textPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (playable && isCurrent && AudioPlayer.durationMs > 0) {
                val dur = AudioPlayer.durationMs
                val pos = AudioPlayer.positionMs
                Column(modifier = Modifier.width(160.dp)) {
                    // Tap anywhere on the track to jump there. A draggable
                    // scrubber is the next step, not this one.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .pointerInput(m.id) {
                                detectTapGestures { off ->
                                    AudioPlayer.seekToFraction(off.x / size.width.toFloat())
                                }
                            },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(c.textSecondary.copy(alpha = 0.3f)),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((pos.toFloat() / dur).coerceIn(0f, 1f))
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(c.accent),
                        )
                    }
                    Text(
                        formatDuration(pos / 1000) + " / " + formatDuration(dur / 1000),
                        color = c.textSecondary,
                        fontSize = 11.sp,
                    )
                }
            } else {
                Text(formatFileSize(m.fileSize ?: 0L), color = c.textSecondary, fontSize = 11.sp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceBubble(session: Session, m: ChatMessage, onLongPress: () -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Same shared player as audio files. It used to be a MediaPlayer remembered
    // by this composable, which meant scrolling the note off screen killed it
    // and two notes could talk over each other.
    val isCurrent = AudioPlayer.playingId == m.id
    val playing = isCurrent && AudioPlayer.isPlaying
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (m.fromMe) c.bubbleSelf else c.bubbleOther)
            .combinedClickable(
                onClick = {
                    if (isCurrent) {
                        AudioPlayer.toggle(context, m.id, java.io.File(context.cacheDir, "voice-${m.id}.m4a"))
                    } else {
                        val mid = m.mediaId; val key = m.mediaKey
                        if (mid != null && key != null) scope.launch {
                            AudioPlayer.setLoading(m.id)
                            val bytes = try {
                                session.fetchImage(mid, key, m.groupId?.let { session.groupHost(it) })
                            } finally {
                                AudioPlayer.setLoading(null)
                            }
                            if (bytes != null) runCatching {
                                val f = java.io.File(context.cacheDir, "voice-${m.id}.m4a")
                                if (!f.exists() || f.length() == 0L) f.writeBytes(bytes)
                                AudioPlayer.toggle(context, m.id, f)
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
        Text(
            if (isCurrent && AudioPlayer.durationMs > 0) formatDuration(AudioPlayer.positionMs / 1000)
            else formatDuration(m.durationSec ?: 0),
            color = c.textPrimary,
            fontSize = 14.sp,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoBubble(session: Session, m: ChatMessage, onLongPress: () -> Unit, onView: (ByteArray) -> Unit = {}) {
    val c = RcqTheme.colors
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
                        // Decrypt, then hand the BYTES to the in-app player.
                        // This used to write the plaintext clip into the shared
                        // FileProvider cache and fire an ACTION_VIEW chooser,
                        // which meant a private video left the app the moment
                        // you watched it.
                        val bytes = session.fetchImage(mid, key, m.groupId?.let { session.groupHost(it) })
                        if (bytes != null) onView(bytes)
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
    // decodeUpright, not BitmapFactory: the camera's orientation tag has to be
    // applied to the pixels here, because the JPEG we write below carries no
    // tag for a viewer to apply later (#527).
    val src = decodeUpright(raw) ?: return null
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
