package app.rcq.android.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.net.RcqApi
import kotlinx.coroutines.launch

/**
 * The numbers this account holds, and which one it answers as.
 *
 * The shop and this screen are deliberately two places. Taking a number and
 * BECOMING it used to be the same tap, which put "everyone who knows me loses
 * me" one button away from browsing; the server split the two (POST
 * /uin/purchase{switch:false} then POST /uin/activate) and this is the second
 * half. Switching here is reversible: the number in use goes into the
 * collection rather than back into the pool, so it can always be switched back.
 *
 * Reachable regardless of the shop toggle — an operator who closes their shop
 * must not strand people on the wrong number, and a self-hoster can hand a
 * member a second number by hand (POST /admin/uin/grant).
 */
@Composable
fun MyUinsScreen(session: Session, onBack: () -> Unit, onActivated: (Int) -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var data by remember { mutableStateOf<RcqApi.MyUinsResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Int?>(null) }
    var activating by remember { mutableStateOf<Int?>(null) }
    var confirmRelease by remember { mutableStateOf<Int?>(null) }
    var releasing by remember { mutableStateOf<Int?>(null) }
    val genericMsg = stringResource(R.string.uin_shop_error_generic)

    suspend fun reload() {
        loading = true
        error = null
        runCatching { session.myUins() }
            .onSuccess { data = it }
            .onFailure { error = it.message?.takeIf { m -> m.isNotBlank() } ?: genericMsg }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    fun release(uin: Int) {
        releasing = uin
        scope.launch {
            runCatching { session.releaseUin(uin) }
                .onFailure { error = it.message?.takeIf { m -> m.isNotBlank() } ?: genericMsg }
            releasing = null
            // Reload rather than dropping the row locally: the server is the
            // one that knows whether the release actually happened.
            reload()
        }
    }

    fun activate(uin: Int) {
        activating = uin
        scope.launch {
            when (val r = session.activateUin(uin)) {
                is Session.PurchaseResult.Success -> onActivated(r.newUin)
                is Session.PurchaseResult.NotOwned -> {
                    activating = null
                    reload()
                }
                is Session.PurchaseResult.Other -> {
                    activating = null
                    error = r.message?.takeIf { it.isNotBlank() } ?: genericMsg
                }
                // /activate either switches or fails; Held/Taken cannot happen.
                else -> activating = null
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.common_back),
                tint = c.accent,
                modifier = Modifier.size(26.dp).clickable(onClick = onBack),
            )
            Text(
                stringResource(R.string.my_uins_title),
                color = c.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val active = data?.active ?: session.uin
            if (active != null && active > 0) {
                SectionCaption(c, stringResource(R.string.my_uins_active_caption))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.bgSecondary)
                        .padding(vertical = 18.dp, horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(active.toString(), color = c.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.my_uins_active_sub), color = c.textSecondary, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(6.dp))
            // "3 of 10" — the cap exists, so it should be visible before you
            // hit it rather than only in the refusal at the eleventh number.
            val owned = data?.owned.orEmpty()
            val cap = data?.max_owned ?: 10
            SectionCaption(
                c,
                stringResource(R.string.my_uins_held_caption) + "  " +
                    stringResource(R.string.my_uins_held_count, owned.size, cap),
            )
            when {
                loading && data == null -> Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), Alignment.Center) {
                    CircularProgressIndicator(color = c.textSecondary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
                owned.isEmpty() -> Text(
                    stringResource(R.string.my_uins_empty),
                    color = c.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    textAlign = TextAlign.Center,
                )
                else -> owned.forEach { item ->
                    HeldRow(
                        c, item,
                        busy = activating == item.uin || releasing == item.uin,
                        enabled = activating == null && releasing == null,
                        onTap = { confirm = item.uin },
                        onRelease = { confirmRelease = item.uin },
                    )
                }
            }

            error?.let {
                Text(
                    it,
                    color = c.statusBusy,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.my_uins_footer),
                color = c.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    confirm?.let { target ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(R.string.my_uins_confirm_title, target.toString()), color = c.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.my_uins_confirm_body, (data?.active ?: session.uin ?: 0).toString()),
                    color = c.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm = null; activate(target) }) {
                    Text(stringResource(R.string.my_uins_confirm_cta), color = c.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) {
                    Text(stringResource(R.string.common_cancel), color = c.textSecondary)
                }
            },
        )
    }

    confirmRelease?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmRelease = null },
            containerColor = c.bgSecondary,
            title = { Text(stringResource(R.string.my_uins_release_title, target.toString()), color = c.textPrimary) },
            // The warning lives here rather than on the row: the number goes
            // back into the pool and somebody else can take it, so this is not
            // undoable and should be read once, not glanced at.
            text = { Text(stringResource(R.string.my_uins_release_body), color = c.textSecondary) },
            confirmButton = {
                TextButton(onClick = { confirmRelease = null; release(target) }) {
                    Text(stringResource(R.string.my_uins_release_cta), color = Color(0xFFE5484D))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRelease = null }) {
                    Text(stringResource(R.string.common_cancel), color = c.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun HeldRow(
    c: RcqColors,
    item: RcqApi.OwnedUinItem,
    busy: Boolean,
    enabled: Boolean,
    onTap: () -> Unit,
    onRelease: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bgSecondary)
            .clickable(enabled = enabled, onClick = onTap)
            .padding(vertical = 15.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.uin.toString(), color = c.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Medium)
            Text(
                pluralStringResource(R.plurals.uin_digits, item.length, item.length),
                color = c.textSecondary,
                fontSize = 11.sp,
            )
        }
        if (busy) {
            CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        } else {
            // Release before Use, quieter than it: the collection fills up with
            // numbers nobody picked (every switch parks the previous one here),
            // and there was no way to get rid of one. Deliberately a plain
            // secondary label, not a red destructive button — this is tidying
            // up, and the confirm dialog carries the warning.
            Text(
                stringResource(R.string.my_uins_release),
                color = c.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.clickable(enabled = enabled, onClick = onRelease).padding(horizontal = 6.dp, vertical = 8.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.my_uins_use), color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SectionCaption(c: RcqColors, text: String) {
    Text(text.uppercase(), color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
}
