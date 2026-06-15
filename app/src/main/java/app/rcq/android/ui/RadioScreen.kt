package app.rcq.android.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.model.RadioMessage
import app.rcq.android.nearby.RadioPeer

/**
 * Radio — the offline BLE + Wi-Fi-Direct local mesh screen. Discovery list
 * (people + rooms) that flips into a chat once a session is up. Mirrors the iOS
 * RadioDiscoveryView + RadioChatView. UI is real; the radio underneath is
 * compile-verified only (no emulator radio).
 */
@Composable
fun RadioScreen(session: Session, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val radio = session.radio
    val context = LocalContext.current

    val online by radio.isOnline.collectAsState()
    val discovered by radio.discovered.collectAsState()
    val activeOneToOne by radio.activeOneToOne.collectAsState()
    val activeRoom by radio.activeRoom.collectAsState()
    val connecting by radio.connecting.collectAsState()
    val lastError by radio.lastError.collectAsState()

    val inChat = activeOneToOne != null || activeRoom != null || connecting

    LaunchedEffect(lastError) {
        lastError?.let {
            Toast.makeText(context, radioErrorText(context, it), Toast.LENGTH_LONG).show()
            radio.clearError()
        }
    }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) radio.startDiscovery()
        else Toast.makeText(context, context.getString(R.string.radio_perm_needed), Toast.LENGTH_LONG).show()
    }
    fun goOnline() {
        val needed = requiredPermissions()
        val ok = needed.all { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (ok) radio.startDiscovery() else perms.launch(needed)
    }

    if (inChat) {
        RadioChatView(session, onLeave = { radio.leaveActiveSession() }, onBack = onBack)
    } else {
        RadioDiscoveryView(
            session = session,
            online = online,
            discovered = discovered,
            onBack = onBack,
            onGoOnline = ::goOnline,
            onStop = { radio.stop() },
        )
    }
}

// ── discovery ─────────────────────────────────────────────────────────
@Composable
private fun RadioDiscoveryView(
    session: Session,
    online: Boolean,
    discovered: List<RadioPeer>,
    onBack: () -> Unit,
    onGoOnline: () -> Unit,
    onStop: () -> Unit,
) {
    val c = RcqTheme.colors
    val radio = session.radio
    val onAir by session.nearby.displayName.collectAsState()
    val anon by session.nearby.anonymous.collectAsState()

    var showCreateRoom by remember { mutableStateOf(false) }
    var joinTarget by remember { mutableStateOf<RadioPeer?>(null) }

    val people = discovered.filter { it.kind == RadioPeer.Kind.OneToOne }
    val rooms = discovered.filter { it.kind == RadioPeer.Kind.Room }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        RadioTopBar(stringResource(R.string.radio_title), onBack)

        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.radio_on_air), color = c.textPrimary, fontSize = 15.sp)
                    Text(
                        if (anon) onAir else stringResource(R.string.nearby_real_name),
                        color = c.textSecondary, fontSize = 12.sp,
                    )
                }
            }
            if (online) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CapsuleButton(stringResource(R.string.radio_create_room), modifier = Modifier.weight(1f)) { showCreateRoom = true }
                    CapsuleButton(stringResource(R.string.radio_stop), modifier = Modifier.weight(1f)) { onStop() }
                }
            } else {
                CapsuleButton(stringResource(R.string.radio_start), modifier = Modifier.fillMaxWidth()) { onGoOnline() }
            }
            Text(stringResource(R.string.radio_privacy), color = c.textSecondary, fontSize = 11.sp)
        }

        if (online) {
            if (people.isEmpty() && rooms.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.radio_searching), color = c.textSecondary, fontSize = 14.sp)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rooms.isNotEmpty()) {
                        item("rh") { SectionLabel(stringResource(R.string.radio_rooms)) }
                        items(rooms, key = { "r_${it.endpointId}" }) { room ->
                            PeerRow(
                                title = room.room?.name?.ifBlank { stringResource(R.string.radio_room) } ?: stringResource(R.string.radio_room),
                                subtitle = if (room.room?.needsPassword == true) stringResource(R.string.radio_locked_room) else stringResource(R.string.radio_open_room),
                                connecting = room.state == RadioPeer.ConnectionState.Connecting,
                                onClick = {
                                    if (room.room?.needsPassword == true) joinTarget = room
                                    else radio.joinRoom(room, null)
                                },
                            )
                        }
                    }
                    if (people.isNotEmpty()) {
                        item("ph") { SectionLabel(stringResource(R.string.radio_people)) }
                        items(people, key = { "p_${it.endpointId}" }) { person ->
                            PeerRow(
                                title = person.displayName.ifBlank { stringResource(R.string.nearby_stranger) },
                                subtitle = stringResource(R.string.radio_tap_to_chat),
                                connecting = person.state == RadioPeer.ConnectionState.Connecting,
                                onClick = { radio.inviteOneToOne(person) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateRoom) {
        CreateRoomDialog(
            onDismiss = { showCreateRoom = false },
            onCreate = { name, pwd -> showCreateRoom = false; radio.createRoom(name, pwd) },
        )
    }
    joinTarget?.let { room ->
        JoinRoomDialog(
            roomName = room.room?.name.orEmpty(),
            onDismiss = { joinTarget = null },
            onJoin = { pwd -> joinTarget = null; radio.joinRoom(room, pwd) },
        )
    }
}

// ── chat ──────────────────────────────────────────────────────────────
@Composable
private fun RadioChatView(session: Session, onLeave: () -> Unit, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val radio = session.radio
    val context = LocalContext.current

    val messages by radio.messages.collectAsState()
    val roster by radio.roster.collectAsState()
    val activeRoom by radio.activeRoom.collectAsState()
    val activeOneToOne by radio.activeOneToOne.collectAsState()
    val connecting by radio.connecting.collectAsState()
    val isTalking by radio.isTalking.collectAsState()
    val speakers by radio.activeSpeakers.collectAsState()

    var draft by remember { mutableStateOf("") }
    var reactingTo by remember { mutableStateOf<RadioMessage?>(null) }

    val title = activeRoom?.name?.ifBlank { stringResource(R.string.radio_room) }
        ?: activeOneToOne?.displayName?.ifBlank { stringResource(R.string.nearby_stranger) }
        ?: stringResource(R.string.radio_title)

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) radio.startTalking()
        else Toast.makeText(context, context.getString(R.string.radio_mic_needed), Toast.LENGTH_LONG).show()
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        Row(
            Modifier.fillMaxWidth().background(c.bgSecondary.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = c.accent,
                modifier = Modifier.size(26.dp).clip(CircleShape).clickable { onLeave(); onBack() })
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                val sub = when {
                    connecting -> stringResource(R.string.radio_connecting)
                    activeRoom != null -> stringResource(R.string.radio_here_count, roster.size + 1)
                    else -> stringResource(R.string.radio_unencrypted_hint)
                }
                Text(sub, color = c.textSecondary, fontSize = 12.sp)
            }
            Text(stringResource(R.string.radio_leave), color = c.accent, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onLeave() }.padding(horizontal = 8.dp, vertical = 4.dp))
        }

        if (speakers.isNotEmpty()) {
            Text(
                stringResource(R.string.radio_talking, speakers.joinToString(", ")),
                color = c.accent, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(c.bgSecondary.copy(alpha = 0.3f)).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(messages, key = { it.id }) { m ->
                RadioBubble(m, onLongPress = { reactingTo = m })
            }
        }

        // Composer + PTT.
        Row(
            Modifier.fillMaxWidth().background(c.bgSecondary.copy(alpha = 0.5f)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.radio_message_hint), color = c.textSecondary) },
                maxLines = 4,
            )
            Icon(
                Icons.Filled.Mic, stringResource(R.string.radio_ptt),
                tint = if (isTalking) Color(0xFFE5484D) else c.accent,
                modifier = Modifier.size(40.dp).clip(CircleShape)
                    .background(if (isTalking) c.accent.copy(alpha = 0.15f) else Color.Transparent)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                val hasMic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                    android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (!hasMic) micPerm.launch(Manifest.permission.RECORD_AUDIO)
                                else radio.startTalking()
                                tryAwaitRelease()
                                radio.stopTalking()
                            },
                        )
                    }.padding(8.dp),
            )
            Icon(
                Icons.AutoMirrored.Filled.Send, stringResource(R.string.radio_send),
                tint = if (draft.isBlank()) c.textSecondary else c.accent,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(enabled = draft.isNotBlank()) {
                    radio.send(draft.trim()); draft = ""
                }.padding(8.dp),
            )
        }
    }

    reactingTo?.let { msg ->
        ReactionPickerDialog(
            onDismiss = { reactingTo = null },
            onPick = { asset -> radio.toggleReaction(asset, msg); reactingTo = null },
        )
    }
}

@Composable
private fun RadioBubble(m: RadioMessage, onLongPress: () -> Unit) {
    val c = RcqTheme.colors
    val align = if (m.isFromMe) Alignment.End else Alignment.Start
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Column(
            Modifier.widthIn(max = 280.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (m.isFromMe) c.accent.copy(alpha = 0.18f) else c.bgSecondary)
                .pointerInput(m.id) { detectTapGestures(onLongPress = { onLongPress() }) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!m.isFromMe) {
                Text(m.senderDisplayName, color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.size(2.dp))
            }
            m.replyToBody?.let {
                Text(it, color = c.textSecondary, fontSize = 11.sp, maxLines = 2)
            }
            EmoticonText(m.text, color = c.textPrimary, fontSize = 15.sp)
        }
        if (m.reactions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp)) {
                m.reactions.values.distinct().forEach { asset -> ReactionChip(asset) }
            }
        }
    }
}

// ── dialogs ─────────────────────────────────────────────────────────────
@Composable
private fun ReactionPickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = RcqTheme.colors
    // The user's chosen quick reactions (≤6); defaults to the historical six
    // until customised in the emoji picker.
    val reactionSet by app.rcq.android.data.LocalStores.reactionEmojis.collectAsState()
    Dialog(onDismissRequest = onDismiss) {
        Row(
            Modifier.clip(RoundedCornerShape(28.dp)).background(c.bgSecondary).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            reactionSet.forEach { asset ->
                EmoticonGif(asset, modifier = Modifier.size(34.dp).clickable { onPick(asset) }, animate = false)
            }
        }
    }
}

@Composable
private fun CreateRoomDialog(onDismiss: () -> Unit, onCreate: (String, String?) -> Unit) {
    val c = RcqTheme.colors
    var name by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(18.dp)).background(c.bgSecondary).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.radio_create_room), color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.radio_room_name)) }, singleLine = true)
            OutlinedTextField(pwd, { pwd = it }, label = { Text(stringResource(R.string.radio_room_password_opt)) }, singleLine = true)
            CapsuleButton(stringResource(R.string.radio_create), enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                onCreate(name.trim(), pwd.takeIf { it.isNotBlank() })
            }
        }
    }
}

@Composable
private fun JoinRoomDialog(roomName: String, onDismiss: () -> Unit, onJoin: (String) -> Unit) {
    val c = RcqTheme.colors
    var pwd by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(18.dp)).background(c.bgSecondary).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(roomName.ifBlank { stringResource(R.string.radio_room) }, color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                pwd, { pwd = it },
                label = { Text(stringResource(R.string.radio_room_password)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            CapsuleButton(stringResource(R.string.radio_join), enabled = pwd.isNotBlank(), modifier = Modifier.fillMaxWidth()) { onJoin(pwd) }
        }
    }
}

// ── small parts ─────────────────────────────────────────────────────────
@Composable
private fun RadioTopBar(title: String, onBack: () -> Unit) {
    val c = RcqTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.bgSecondary.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = c.accent,
            modifier = Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onBack))
        Spacer(Modifier.width(8.dp))
        Text(title, color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = RcqTheme.colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
}

@Composable
private fun PeerRow(title: String, subtitle: String, connecting: Boolean, onClick: () -> Unit) {
    val c = RcqTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bgSecondary).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.bgPrimary), contentAlignment = Alignment.Center) {
            Text(title.firstOrNull()?.uppercase() ?: "?", color = c.textPrimary, fontSize = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = c.textSecondary, fontSize = 12.sp)
        }
        if (connecting) CircularProgressIndicator(color = c.accent, modifier = Modifier.size(18.dp))
        else Icon(Icons.Filled.Add, null, tint = c.accent, modifier = Modifier.size(20.dp))
    }
}

private fun requiredPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.NEARBY_WIFI_DEVICES,
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
    else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

private fun radioErrorText(context: android.content.Context, key: String): String {
    val res = when (key) {
        "ble_off" -> R.string.radio_err_ble_off
        "ble_permission" -> R.string.radio_perm_needed
        "wrong_password" -> R.string.radio_err_wrong_password
        "wifip2p_unsupported" -> R.string.radio_err_unsupported
        "wifip2p_peer_not_found" -> R.string.radio_err_peer_not_found
        "mic_denied" -> R.string.radio_mic_needed
        "voice_start_failed" -> R.string.radio_err_voice
        else -> R.string.radio_err_generic
    }
    return context.getString(res)
}
