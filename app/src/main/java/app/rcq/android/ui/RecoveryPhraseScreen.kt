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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.security.PanicPinService

/**
 * Shows the active account's 24-word recovery phrase (BIP39 over the identity
 * seed) so the user can write it down and later restore the account on a fresh
 * device. Legacy accounts (no seed) get a clear "not available" notice.
 */
@Composable
fun RecoveryPhraseScreen(session: Session, onBack: () -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    // Seed accounts get a 24-word phrase; legacy (pre-seed) accounts fall back
    // to a 48-word raw-key export. Only truly keyless accounts show nothing.
    // Mutable so a key re-issue can swap in the freshly-minted 24-word phrase.
    var phrase by remember { mutableStateOf(session.recoveryPhrase() ?: session.legacyExportPhrase()) }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        Row(
            Modifier.fillMaxWidth().background(c.bgSecondary.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = c.accent,
                modifier = Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onBack))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.recovery_title), color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        if (phrase == null) {
            Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.recovery_legacy), color = c.textSecondary, fontSize = 14.sp)
            }
            return@Column
        }

        // The phrase grants full account access forever, so re-gate it behind the
        // PIN when one is set (the app being unlocked isn't enough — someone with
        // a briefly-unlocked phone shouldn't grab it). No PIN = no gate.
        var unlocked by remember {
            mutableStateOf(!PanicPinService.isConfigured(context))
        }
        if (!unlocked) {
            PinGate(onVerified = { unlocked = true })
            return@Column
        }

        // Non-null capture (guarded above) — a delegated `var` doesn't smart-cast.
        val words = phrase ?: return@Column
        val scope = rememberCoroutineScope()
        var confirmReissue by remember { mutableStateOf(false) }
        var rotating by remember { mutableStateOf(false) }
        // Blurred until asked for, the way iOS and the web already do it. The
        // phrase is the whole account, and it was being painted the instant the
        // screen opened: a glance over a shoulder, a screen recording or a
        // screenshot in a support chat was enough to take it.
        var revealed by remember { mutableStateOf(false) }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.recovery_warn), color = Color(0xFFE5A50A), fontSize = 13.sp)

            // Two-column numbered grid of the words (24 for seed, 48 for legacy).
            val mid = words.size / 2
            Box {
            Row(
                Modifier.fillMaxWidth()
                    .then(if (revealed) Modifier else Modifier.blur(12.dp))
                    .then(if (revealed) Modifier else Modifier.alpha(0.65f)),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                listOf(words.subList(0, mid), words.subList(mid, words.size)).forEachIndexed { col, half ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        half.forEachIndexed { i, w ->
                            val n = col * mid + i + 1
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.bgSecondary).padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("$n", color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.width(22.dp))
                                Text(w, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }

            if (!revealed) {
                Box(Modifier.matchParentSize().clickable { revealed = true }, contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.recovery_reveal),
                        color = c.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.bgSecondary).padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            }

            CapsuleButton(stringResource(R.string.recovery_copy), enabled = !rotating && revealed, modifier = Modifier.fillMaxWidth()) {
                copyToClipboard(context, words.joinToString(" "))
                Toast.makeText(context, context.getString(R.string.recovery_copied), Toast.LENGTH_SHORT).show()
            }
            Text(stringResource(R.string.recovery_note), color = c.textSecondary, fontSize = 12.sp)

            // ── Key re-issue (rotation) ──────────────────────────────────
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.reissue_title), color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.reissue_explain), color = c.textSecondary, fontSize = 12.sp)
            CapsuleButton(
                if (rotating) stringResource(R.string.reissue_working) else stringResource(R.string.reissue_cta),
                enabled = !rotating,
                modifier = Modifier.fillMaxWidth(),
            ) { confirmReissue = true }
        }

        if (confirmReissue) {
            AlertDialog(
                onDismissRequest = { confirmReissue = false },
                containerColor = c.bgSecondary,
                title = { Text(stringResource(R.string.reissue_confirm_title), color = c.textPrimary) },
                text = { Text(stringResource(R.string.reissue_confirm_body), color = c.textSecondary) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmReissue = false
                        rotating = true
                        scope.launch {
                            runCatching { session.reissueKeys() }
                                .onSuccess { newPhrase ->
                                    phrase = newPhrase
                                    Toast.makeText(context, context.getString(R.string.reissue_done), Toast.LENGTH_LONG).show()
                                }
                                .onFailure {
                                    Toast.makeText(context, context.getString(R.string.reissue_failed), Toast.LENGTH_LONG).show()
                                }
                            rotating = false
                        }
                    }) { Text(stringResource(R.string.reissue_cta), color = Color(0xFFE5484D)) }
                },
                dismissButton = { TextButton(onClick = { confirmReissue = false }) { Text(stringResource(R.string.common_cancel), color = c.textSecondary) } },
            )
        }
    }
}

/** PIN re-entry gate shown before the phrase when a PIN is configured. Verifies
 *  the REAL PIN only (decoy/wipe rejected), with no unlock side effects. */
@Composable
private fun PinGate(onVerified: () -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.recovery_pin_prompt), color = c.textPrimary, fontSize = 15.sp)
        OutlinedTextField(
            value = pin,
            onValueChange = { v -> pin = v.filter { it.isDigit() }; error = false },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = error,
            modifier = Modifier.fillMaxWidth(),
        )
        if (error) {
            Text(stringResource(R.string.recovery_pin_wrong), color = Color(0xFFE5484D), fontSize = 13.sp)
        }
        CapsuleButton(stringResource(R.string.recovery_pin_unlock), modifier = Modifier.fillMaxWidth()) {
            if (PanicPinService.verifyRealPin(context, pin)) onVerified()
            else { error = true; pin = "" }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("recovery phrase", text))
}
