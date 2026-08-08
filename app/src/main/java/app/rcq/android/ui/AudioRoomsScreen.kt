package app.rcq.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.core.content.ContextCompat
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.call.AudioRoomController
import kotlinx.coroutines.launch

@Composable
fun AudioRoomsScreen(session: Session, onBack: () -> Unit) {
    val controller = session.audioRooms
    val activeId by controller.activeRoomId.collectAsState()

    LaunchedEffect(Unit) { controller.refresh() }

    if (activeId != null) {
        InRoomView(session)
    } else {
        RoomListView(session, onBack)
    }
}

@Composable
private fun RoomListView(session: Session, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val controller = session.audioRooms
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rooms by controller.rooms.collectAsState()
    val joinError by controller.joinError.collectAsState()
    val ownUin = session.uin

    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var joinKey by remember { mutableStateOf("") }
    var pendingEnter by remember { mutableStateOf<AudioRoomController.Room?>(null) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val room = pendingEnter; pendingEnter = null
        if (granted && room != null) controller.enter(room)
    }
    fun enterRoom(room: AudioRoomController.Room) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            controller.enter(room)
        } else {
            pendingEnter = room
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        Row(
            Modifier.fillMaxWidth().background(c.bgSecondary.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = c.accent,
                modifier = Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onBack))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.rooms_title), color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Add, stringResource(R.string.rooms_create), tint = c.accent,
                modifier = Modifier.size(26.dp).clip(CircleShape).clickable { newName = ""; showCreate = true })
        }

        joinError?.let {
            Text(roomErrorText(it), color = Color(0xFFE5484D), fontSize = 13.sp, modifier = Modifier.padding(16.dp, 8.dp))
        }

        // Join by key
        Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = joinKey, onValueChange = { joinKey = it.take(16) },
                label = { Text(stringResource(R.string.rooms_join_key)) },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii),
            )
            Spacer(Modifier.width(8.dp))
            CapsuleButton(stringResource(R.string.rooms_join)) {
                val k = joinKey.trim(); if (k.length >= 4) scope.launch { if (controller.joinByKey(k)) joinKey = "" }
            }
        }

        if (rooms.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.rooms_empty), color = c.textSecondary, fontSize = 14.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rooms, key = { it.id }) { room ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bgSecondary)
                            .clickable { enterRoom(room) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(room.name, color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            val sub = if (room.activeCount > 0)
                                stringResource(R.string.rooms_in_room, room.activeCount) + " · " + stringResource(R.string.rooms_key_fmt, room.joinKey)
                            else stringResource(R.string.rooms_key_fmt, room.joinKey)
                            Text(sub, color = c.textSecondary, fontSize = 12.sp)
                        }
                        // The key is the only way anyone else gets in, and it was
                        // printed as text you had to retype by hand (#418).
                        Icon(
                            Icons.Filled.ContentCopy, stringResource(R.string.rooms_copy_key), tint = c.textSecondary,
                            modifier = Modifier.size(20.dp).clip(CircleShape).clickable {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("RCQ room key", room.joinKey))
                                Toast.makeText(context, context.getString(R.string.rooms_key_copied), Toast.LENGTH_SHORT).show()
                            },
                        )
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            Icons.Filled.Share, stringResource(R.string.rooms_share_key), tint = c.textSecondary,
                            modifier = Modifier.size(20.dp).clip(CircleShape).clickable {
                                val text = context.getString(R.string.rooms_share_text, room.name, room.joinKey)
                                context.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) },
                                    null,
                                ))
                            },
                        )
                        if (room.ownerUin == ownUin) {
                            Spacer(Modifier.width(12.dp))
                            Icon(Icons.Filled.Delete, stringResource(R.string.rooms_delete), tint = c.textSecondary,
                                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { scope.launch { controller.delete(room.id) } })
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.rooms_create), color = c.textPrimary) },
            text = {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it.take(64) },
                    label = { Text(stringResource(R.string.rooms_name)) }, singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = newName.trim()
                    if (n.isNotEmpty()) { showCreate = false; scope.launch { controller.create(n) } }
                }) { Text(stringResource(R.string.rooms_create), color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
            containerColor = c.bgSecondary,
        )
    }
}

@Composable
private fun InRoomView(session: Session) {
    val c = RcqTheme.colors
    val controller = session.audioRooms
    val name by controller.activeRoomName.collectAsState()
    val roster by controller.roster.collectAsState()
    val muted by controller.localMuted.collectAsState()
    val joining by controller.joining.collectAsState()
    val ownUin = session.uin
    val members = roster.values.sortedBy { it.uin }

    Column(Modifier.fillMaxSize().background(Color(0xFF0E0F12))) {
        Spacer(Modifier.height(48.dp))
        Text(name ?: stringResource(R.string.rooms_title), color = Color.White, fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().padding(16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(
            if (joining) stringResource(R.string.rooms_joining) else stringResource(R.string.rooms_in_room, members.size),
            color = Color(0xFFB8BCC4), fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(members, key = { it.uin }) { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF2A2D34)), contentAlignment = Alignment.Center) {
                        Text(m.nickname.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (m.uin == ownUin) stringResource(R.string.rooms_you) else m.nickname,
                        color = Color.White, fontSize = 16.sp,
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 40.dp), horizontalArrangement = Arrangement.Center,
        ) {
            RoomButton(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, stringResource(R.string.call_mute),
                if (muted) Color(0xFF4A4D55) else Color(0xFF2A2D34)) { controller.toggleMute() }
            Spacer(Modifier.width(28.dp))
            RoomButton(Icons.Filled.CallEnd, stringResource(R.string.rooms_leave), Color(0xFFE5484D)) { controller.exit() }
        }
    }
}

@Composable
private fun RoomButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bg: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(60.dp).clip(CircleShape).background(bg).clickable { onClick() }, contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color(0xFFB8BCC4), fontSize = 12.sp)
    }
}

@Composable
private fun roomErrorText(reason: String): String = stringResource(
    when (reason) {
        "in_call" -> R.string.rooms_err_in_call
        "busy" -> R.string.rooms_err_busy
        "full" -> R.string.rooms_err_full
        "not_member" -> R.string.rooms_err_not_member
        "no_such_room" -> R.string.rooms_err_no_room
        else -> R.string.rooms_err_generic
    },
)
