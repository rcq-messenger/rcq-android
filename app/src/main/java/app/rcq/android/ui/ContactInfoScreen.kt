package app.rcq.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.data.LocalStores
import app.rcq.android.model.UserStatus
import app.rcq.android.net.RcqApi
import kotlinx.coroutines.launch

private val DANGER = Color(0xFFE5484D)

/** Profile card for a 1:1 contact — the peer analogue of [GroupInfoScreen],
 *  opened by tapping the chat header. Shows presence/last-seen, status
 *  message, and any visibility-gated profile fields the server returns,
 *  plus per-contact actions (favorite, mute, block, remove). */
@Composable
internal fun ContactInfoScreen(session: Session, uin: Int, onBack: () -> Unit, onRemoved: () -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val contacts by session.contacts.collectAsState()
    val contact = contacts.firstOrNull { it.uin == uin }

    val thread = LocalStores.peerThread(uin)
    val favorites by LocalStores.favorites.collectAsState()
    val muted by LocalStores.muted.collectAsState()
    val isFav = thread in favorites
    val isMuted = thread in muted

    var profile by remember { mutableStateOf<RcqApi.MeProfile?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var showSafety by remember { mutableStateOf(false) }
    var safetyNumber by remember { mutableStateOf<String?>(null) }
    var safetyLoading by remember { mutableStateOf(false) }
    var identityChanged by remember { mutableStateOf(false) }

    // §5c: a cross-island contact's profile lives on ITS island — our own
    // /users/{uin}/info 404s, so render from the locally-stored card
    // (nickname/gender/status) and skip the visit ping (it'd mis-route to our
    // own island's uin).
    val crossIslandHost = contact?.host
    LaunchedEffect(uin) {
        if (crossIslandHost == null) {
            profile = session.loadPeerProfile(uin)
            identityChanged = session.peerIdentityChanged(uin)
            runCatching { session.sendVisit(uin) }
        }
    }

    val nickname = profile?.nickname ?: contact?.nickname ?: session.contactName(uin)
    val presence = contact?.presence ?: UserStatus.OFFLINE
    val statusMessage = profile?.status_message?.takeIf { it.isNotBlank() }
        ?: contact?.statusMessage?.takeIf { it.isNotBlank() }
    val blocked = contact?.blocked == true

    fun copyUin() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("UIN", "$uin"))
        Toast.makeText(context, context.getString(R.string.common_uin_copied), Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = c.accent, modifier = Modifier.size(26.dp).clickable(onClick = onBack))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.ci_title), color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // Identity block.
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusIcon(presence, size = 80.dp, crossIsland = crossIslandHost != null)
                Text(nickname, color = c.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                val sub = when {
                    // Cross-island: presence/last_seen don't cross islands — show
                    // the island instead of a misleading "offline".
                    crossIslandHost != null -> crossIslandHost
                    presence == UserStatus.OFFLINE && contact?.lastSeen != null -> stringResource(R.string.last_seen_fmt, relativeLastSeen(contact.lastSeen!!, context))
                    else -> stringResource(presence.labelRes).lowercase()
                }
                Text(sub, color = c.textSecondary, fontSize = 13.sp, fontFamily = if (crossIslandHost != null) FontFamily.Monospace else FontFamily.Default)
                statusMessage?.let { Text(it, color = c.textPrimary, fontSize = 14.sp, textAlign = TextAlign.Center) }
            }

            Spacer(Modifier.height(10.dp))

            // UIN row (copyable).
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bgSecondary).clickable { copyUin() }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("UIN", color = c.textSecondary, fontSize = 12.sp)
                    Text("#$uin", color = c.textMono, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                }
                Icon(Icons.Filled.ContentCopy, stringResource(R.string.common_copy_uin), tint = c.textSecondary, modifier = Modifier.size(18.dp))
            }

            // Safety-number-changed warning (re-register / new device / MITM).
            if (identityChanged) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bgSecondary).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, null, tint = DANGER, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.ci_identity_changed), color = c.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
            }

            // Profile fields (only those the server let us see). Labels are
            // resolved here because buildList's lambda isn't composable.
            val lblAge = stringResource(R.string.pe_age)
            val lblGender = stringResource(R.string.common_gender)
            val lblCity = stringResource(R.string.common_city)
            val lblCountry = stringResource(R.string.common_country)
            val lblAbout = stringResource(R.string.common_about)
            val genderValue = when (profile?.gender?.lowercase()) {
                "male", "m" -> stringResource(R.string.common_male)
                "female", "f" -> stringResource(R.string.common_female)
                "other" -> stringResource(R.string.common_other)
                else -> null
            }
            val fields = buildList {
                profile?.age?.takeIf { it > 0 }?.let { add(lblAge to it.toString()) }
                genderValue?.let { add(lblGender to it) }
                profile?.city?.takeIf { it.isNotBlank() }?.let { add(lblCity to it) }
                profile?.country?.takeIf { it.isNotBlank() }?.let { add(lblCountry to it) }
                profile?.about?.takeIf { it.isNotBlank() }?.let { add(lblAbout to it) }
            }
            if (fields.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bgSecondary)) {
                    fields.forEachIndexed { i, (label, value) ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 16.dp).background(c.divider))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                            Text(label, color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.width(96.dp))
                            Text(value, color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // Actions.
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bgSecondary)) {
                InfoAction(if (isFav) Icons.Filled.Star else Icons.Filled.StarBorder, stringResource(if (isFav) R.string.ci_remove_fav else R.string.ci_add_fav)) { LocalStores.toggleFavorite(thread) }
                InfoDivider()
                InfoAction(Icons.Filled.NotificationsOff, stringResource(if (isMuted) R.string.ci_unmute else R.string.ci_mute)) { LocalStores.toggleMute(thread) }
                InfoDivider()
                InfoAction(Icons.Outlined.Block, stringResource(if (blocked) R.string.ci_unblock else R.string.ci_block), danger = !blocked) {
                    scope.launch { runCatching { session.toggleBlock(uin) } }
                }
                InfoDivider()
                InfoAction(Icons.Filled.Lock, stringResource(R.string.ci_safety)) {
                    showSafety = true
                    safetyLoading = true
                    // Opening it to re-check counts as acknowledging the change.
                    if (identityChanged) {
                        session.acknowledgePeerIdentity(uin)
                        identityChanged = false
                    }
                    scope.launch {
                        safetyNumber = session.safetyNumber(uin)
                        safetyLoading = false
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bgSecondary).clickable { confirmRemove = true }.padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.PersonRemove, null, tint = DANGER, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.ci_remove), color = DANGER, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(R.string.ci_remove_title, nickname), color = c.textPrimary) },
            text = { Text(stringResource(R.string.ci_remove_body), color = c.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch { runCatching { session.removeContact(uin) }; onRemoved() }
                }) { Text(stringResource(R.string.common_remove), color = DANGER) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
        )
    }

    if (showSafety) {
        AlertDialog(
            onDismissRequest = { showSafety = false },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(R.string.ci_safety_title), color = c.textPrimary) },
            text = {
                Column {
                    when {
                        safetyLoading -> Text(stringResource(R.string.ci_safety_loading), color = c.textSecondary)
                        safetyNumber == null -> Text(stringResource(R.string.ci_safety_unavailable), color = c.textSecondary)
                        else -> {
                            Text(
                                safetyNumber!!,
                                color = c.textPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 16.sp,
                                lineHeight = 26.sp,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.ci_safety_body), color = c.textSecondary, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSafety = false }) { Text(stringResource(R.string.common_close), color = c.accent) } },
        )
    }
}

@Composable
private fun InfoAction(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = RcqTheme.colors
    val tint = if (danger) DANGER else c.accent
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, color = if (danger) DANGER else c.textPrimary, fontSize = 16.sp)
    }
}

@Composable
private fun InfoDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 48.dp).background(RcqTheme.colors.divider))
}
