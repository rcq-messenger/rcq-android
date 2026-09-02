package app.rcq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The conversations the decoy is seeded with.
 *
 * An empty decoy is the tell the whole feature exists to avoid, so the user
 * picks real chats to copy in. What is copied is rewritten on the way
 * (see [app.rcq.android.data.DecoyStore]) — the decoy store never learns a real
 * UIN — which is why this asks for conversations rather than for an account.
 */
@Composable
internal fun DecoyThreadPicker(
    candidates: List<Pair<Int, String>>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
) {
    val c = RcqTheme.colors
    if (candidates.isEmpty()) {
        Text(stringResource(R.string.pin_decoy_no_chats), color = c.textSecondary, fontSize = 13.sp)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        candidates.forEach { (uin, name) ->
            val on = uin in selected
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onToggle(uin) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (on) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                    null,
                    tint = if (on) c.accent else c.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(name, color = c.textPrimary, fontSize = 14.sp)
                    Text("$uin", color = c.textSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * One-time migration for people who already had a decoy PIN (E).
 *
 * The old decoy was a real second account plus a visibility filter, and its
 * slot carried a copy of the real `dataKey` — so the duress PIN opened every
 * account's locked history. It is not something we can quietly rewrite under
 * them: the new decoy needs conversations to show and a PIN of its own, and
 * both have to come from the person who owns them.
 *
 * Shown after an unlock with the REAL PIN and nowhere else. A biometric unlock
 * cannot reach it, because writing the decoy needs the real slot key and
 * biometrics deliberately never hold it. Until this is finished, the OLD decoy
 * PIN keeps working exactly as it did — a duress PIN that stops working while
 * someone is being coerced is worse than an out-of-date one.
 */
@Composable
fun DecoyMigrationScreen(session: Session, onDone: () -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // ⚠ Keyed on the history, NOT computed once. This screen is raised the
    // instant the real PIN is accepted, while start() is still opening the
    // SQLCipher db on a background thread (~1s), so a plain `remember` snapshots
    // an empty map and offers nothing to pick — leaving the one screen that
    // rebuilds the decoy permanently unusable until the next unlock.
    val history by session.messages.collectAsState()
    val candidates = remember(history) { session.decoySeedCandidates() }
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun onlyDigits(s: String) = s.length <= 12 && s.all { it.isDigit() }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.pin_decoy_migrate_title),
                color = c.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(R.string.pin_decoy_migrate_body), color = c.textSecondary, fontSize = 14.sp)
            Text(stringResource(R.string.pin_decoy_migrate_keep), color = c.textSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.pin_decoy_pick),
                color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
            Text(stringResource(R.string.pin_decoy_plausibility), color = c.textSecondary, fontSize = 13.sp)
            DecoyThreadPicker(candidates, selected) { uin ->
                selected = if (uin in selected) selected - uin else selected + uin
            }
            RcqField(
                value = pin,
                onValueChange = { if (onlyDigits(it)) pin = it },
                placeholder = stringResource(R.string.pin_decoy_new),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            RcqField(
                value = confirm,
                onValueChange = { if (onlyDigits(it)) confirm = it },
                placeholder = stringResource(R.string.pin_confirm),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = Color(0xFFE5484D), fontSize = 13.sp) }
            CapsuleButton(
                label = if (busy) stringResource(R.string.pin_busy) else stringResource(R.string.common_save),
                enabled = pin.length >= 4 && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (pin != confirm) { error = context.getString(R.string.pin_mismatch); return@CapsuleButton }
                if (selected.isEmpty()) { error = context.getString(R.string.pin_decoy_needs_chats); return@CapsuleButton }
                scope.launch {
                    busy = true; error = null
                    val ok = withContext(Dispatchers.Default) { session.setDecoyPin(pin, selected.toList()) }
                    busy = false
                    if (ok) { session.dismissDecoyMigration(); onDone() }
                    else error = context.getString(R.string.pin_wipe_taken)
                }
            }
            TextButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        withContext(Dispatchers.Default) { session.removeDecoyPin() }
                        busy = false
                        session.dismissDecoyMigration(); onDone()
                    }
                },
            ) { Text(stringResource(R.string.pin_decoy_drop), color = Color(0xFFE5484D), fontSize = 14.sp) }
            TextButton(enabled = !busy, onClick = { session.dismissDecoyMigration(); onDone() }) {
                Text(stringResource(R.string.pin_decoy_later), color = c.textSecondary, fontSize = 14.sp)
            }
        }
    }
}
