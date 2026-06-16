package app.rcq.android.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import app.rcq.android.nearby.NearbyController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NearbyScreen(session: Session, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val controller = session.nearby
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    val people by controller.people.collectAsState()
    val anonymous by controller.anonymous.collectAsState()
    val displayName by controller.displayName.collectAsState()

    val active = state is NearbyController.State.Active
    val activeBucket = (state as? NearbyController.State.Active)?.bucketId
    // Operator can hide Hood Chat via the admin console (Features).
    val hoodEnabled by session.hoodEnabled.collectAsState()

    // Nested nav into the bucket-scoped district screens.
    var hoodChatBucket by remember { mutableStateOf<String?>(null) }
    var bannersBucket by remember { mutableStateOf<String?>(null) }
    hoodChatBucket?.let { return HoodChatScreen(session, it) { hoodChatBucket = null } }
    bannersBucket?.let { return HoodBannersScreen(session, it) { bannersBucket = null } }

    val locPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.values.any { it }
        controller.start(granted)
    }
    fun startSharing() {
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (ok) controller.start(true)
        else locPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        Row(
            Modifier.fillMaxWidth().background(c.bgSecondary.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = c.accent,
                modifier = Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onBack))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.nearby_title), color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        // Control card.
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.nearby_anon), color = c.textPrimary, fontSize = 15.sp)
                    Text(
                        if (anonymous) displayName else stringResource(R.string.nearby_real_name),
                        color = c.textSecondary, fontSize = 12.sp,
                    )
                }
                if (anonymous) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.nearby_regenerate), tint = c.accent,
                        modifier = Modifier.size(22.dp).clip(CircleShape).clickable { controller.regenerateName() })
                    Spacer(Modifier.width(8.dp))
                }
                Switch(checked = anonymous, onCheckedChange = { controller.setAnonymous(it) })
            }

            when (state) {
                is NearbyController.State.Active -> {
                    val exp = (state as NearbyController.State.Active).expiresAtMs
                    CountdownLabel(exp)
                    // Full-width stacked (was a 2-up Row where "Announcements"
                    // overflowed the half-width capsule) so both labels fit.
                    if (hoodEnabled) CapsuleButton(stringResource(R.string.hood_title), modifier = Modifier.fillMaxWidth()) { hoodChatBucket = activeBucket }
                    CapsuleButton(stringResource(R.string.banners_title), modifier = Modifier.fillMaxWidth()) { bannersBucket = activeBucket }
                    CapsuleButton(stringResource(R.string.nearby_stop), modifier = Modifier.fillMaxWidth()) { controller.stop() }
                }
                is NearbyController.State.Pending -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = c.accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.nearby_finding), color = c.textSecondary, fontSize = 14.sp)
                    }
                }
                is NearbyController.State.Denied ->
                    Text(stringResource(R.string.nearby_denied), color = Color(0xFFE5484D), fontSize = 13.sp)
                is NearbyController.State.Error ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.nearby_error), color = Color(0xFFE5484D), fontSize = 13.sp)
                        CapsuleButton(stringResource(R.string.nearby_start), modifier = Modifier.fillMaxWidth()) { startSharing() }
                    }
                else -> CapsuleButton(stringResource(R.string.nearby_start), modifier = Modifier.fillMaxWidth()) { startSharing() }
            }
            Text(stringResource(R.string.nearby_privacy), color = c.textSecondary, fontSize = 11.sp)
        }

        if (active) {
            if (people.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.nearby_empty), color = c.textSecondary, fontSize = 14.sp)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(people, key = { it.uin }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.bgSecondary).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(c.bgPrimary), contentAlignment = Alignment.Center) {
                                Text(p.nickname.firstOrNull()?.uppercase() ?: "?", color = c.textPrimary, fontSize = 16.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.nickname, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    if (p.anonymous) stringResource(R.string.nearby_stranger) else "#${p.uin}",
                                    color = c.textSecondary, fontSize = 12.sp,
                                )
                            }
                            Icon(Icons.Filled.PersonAdd, stringResource(R.string.nearby_add), tint = c.accent,
                                modifier = Modifier.size(22.dp).clip(CircleShape).clickable {
                                    scope.launch {
                                        runCatching { session.addContact(p.uin) }
                                        android.widget.Toast.makeText(context, context.getString(R.string.nearby_request_sent), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                })
                        }
                    }
                }
            }
        }
    }

    // Stop sharing when the screen leaves composition is intentionally NOT done:
    // the server TTL backstops, and leaving the screen shouldn't drop the
    // check-in. The user stops explicitly or it expires.
}

@Composable
private fun CountdownLabel(expiresAtMs: Long) {
    val c = RcqTheme.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAtMs) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val secs = ((expiresAtMs - now) / 1000).coerceAtLeast(0)
    Text(
        stringResource(R.string.nearby_visible_for, secs / 60, secs % 60),
        color = c.accent, fontSize = 13.sp,
    )
}
