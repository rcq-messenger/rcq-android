package app.rcq.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.security.BiometricGate
import app.rcq.android.security.PanicPinService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen PIN entry shown whenever the app is locked (panic-PIN). A correct
 * real PIN flips [PanicPinService.locked] to false, which recomposes the host
 * away from this screen and lets [Session.start] reopen the message DB under the
 * unlocked key. Wrong attempts escalate into a lockout countdown.
 */
@Composable
fun PinLockScreen(session: Session, onWiped: () -> Unit = {}, onAccountChanged: (Int) -> Unit = {}) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lockedOutUntil by remember { mutableStateOf(PanicPinService.lockedOutUntil(context)) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(lockedOutUntil) {
        while (lockedOutUntil != null && lockedOutUntil!! > System.currentTimeMillis()) {
            nowMs = System.currentTimeMillis()
            delay(500)
        }
        lockedOutUntil = null
    }
    val remainingSec: Long? = lockedOutUntil?.let { ((it - nowMs) / 1000 + 1).coerceAtLeast(0) }
    val canSubmit = pin.length >= 4 && remainingSec == null && !busy

    // Biometric unlock (panic-PIN phase 4): offered only when a biometric blob
    // is enrolled and we can host the prompt (MainActivity is a FragmentActivity).
    val activity = remember(context) { context.findFragmentActivity() }
    val bioEnabled = remember { activity != null && PanicPinService.biometricEnabled(context) }
    var triedBio by remember { mutableStateOf(false) }
    // Kept separate from [busy] (the PIN-submit gate): a biometric prompt that
    // fails to call back must never disable the PIN field — that would lock the
    // user out entirely.
    var bioInFlight by remember { mutableStateOf(false) }

    fun tryBiometric() {
        val act = activity ?: return
        if (bioInFlight || remainingSec != null) return
        bioInFlight = true
        error = null
        BiometricGate.unlock(
            act,
            context.getString(R.string.pin_biometric_prompt_title),
            context.getString(R.string.pin_biometric_prompt_subtitle),
            context.getString(R.string.pin_biometric_cancel),
        ) { blob ->
            if (blob != null) {
                scope.launch {
                    // Biometric always unlocks the REAL session: ensure decoy is
                    // off first (it can't actually be on — biometric and decoy
                    // are mutually exclusive — but stay safe against a race).
                    app.rcq.android.data.AccountManager.exitDecoyMode()
                    val ok = withContext(Dispatchers.Default) { PanicPinService.applyBiometricUnlock(context, blob) }
                    if (!ok) bioInFlight = false
                    // success → PanicPinService.locked flips false → host recomposes away
                }
            } else {
                bioInFlight = false // cancelled / failed: fall back to PIN entry
            }
        }
    }

    // Prompt once automatically when the lock screen appears (iOS parity). A
    // short settle delay lets the activity window regain focus after a resume,
    // otherwise the system can silently drop the prompt request.
    LaunchedEffect(Unit) {
        if (bioEnabled && !triedBio) { triedBio = true; delay(400); tryBiometric() }
    }

    fun submit() {
        if (!canSubmit) return
        busy = true
        error = null
        scope.launch {
            val res = withContext(Dispatchers.Default) { PanicPinService.submit(context, pin) }
            busy = false
            when (res) {
                PanicPinService.SubmitResult.REAL -> {
                    // Real PIN reveals everything: make sure decoy mode is off.
                    app.rcq.android.data.AccountManager.exitDecoyMode()
                    // A decoy left over from the old model has to be rebuilt
                    // once, and only a real PIN entry can do it (the rebuild
                    // writes the real slot). Raised here rather than at boot so
                    // a biometric unlock never lands on it.
                    session.refreshDecoyMigration()
                    // host recomposes away
                }
                PanicPinService.SubmitResult.DECOY -> {
                    // Switch to the decoy account + hide the rest, THEN unlock.
                    busy = true
                    withContext(Dispatchers.Default) { session.applyDecoyUnlock() }
                    // The active account changed — update the host's uin so the
                    // header shows the decoy's number, not the hidden account's.
                    session.uin?.let { onAccountChanged(it) }
                }
                PanicPinService.SubmitResult.WIPE -> {
                    // Duress wipe: erase everything, then drop to onboarding.
                    // No error shown — it silently resets to a fresh install.
                    busy = true
                    withContext(Dispatchers.Default) { session.wipeEverything() }
                    onWiped()
                }
                PanicPinService.SubmitResult.WRONG -> {
                    error = context.getString(R.string.pin_wrong)
                    pin = ""
                    lockedOutUntil = PanicPinService.lockedOutUntil(context)
                }
                PanicPinService.SubmitResult.LOCKED_OUT -> {
                    pin = ""
                    lockedOutUntil = PanicPinService.lockedOutUntil(context)
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Lock, null, tint = c.accent, modifier = Modifier.size(48.dp))
        Text(
            stringResource(R.string.pin_lock_title),
            color = c.textPrimary,
            fontSize = 22.sp,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            stringResource(R.string.pin_lock_subtitle),
            color = c.textSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        RcqField(
            value = pin,
            onValueChange = { if (it.length <= 12 && it.all { ch -> ch.isDigit() }) pin = it },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            enabled = remainingSec == null && !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        )
        if (remainingSec != null) {
            Text(
                stringResource(R.string.pin_locked_out, remainingSec),
                color = c.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        error?.let {
            Text(it, color = Color(0xFFE5484D), fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        }
        CapsuleButton(
            label = if (busy) stringResource(R.string.pin_busy) else stringResource(R.string.pin_unlock),
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) { submit() }
        if (bioEnabled) {
            TextButton(
                onClick = { tryBiometric() },
                enabled = remainingSec == null && !bioInFlight && !busy,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Icon(Icons.Filled.Fingerprint, null, tint = c.accent, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.pin_biometric_lock_use),
                    color = c.accent,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
