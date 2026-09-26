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
import androidx.lifecycle.repeatOnLifecycle
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
    // Both on the monotonic clock ([PanicPinService.lockedOutUntilElapsed]):
    // a clock changed in Settings neither ends the countdown nor stretches it.
    var lockedOutUntil by remember { mutableStateOf(PanicPinService.lockedOutUntilElapsed(context)) }
    var nowMs by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }

    LaunchedEffect(lockedOutUntil) {
        while (lockedOutUntil != null && lockedOutUntil!! > android.os.SystemClock.elapsedRealtime()) {
            nowMs = android.os.SystemClock.elapsedRealtime()
            delay(500)
        }
        lockedOutUntil = null
    }
    val remainingSec: Long? = lockedOutUntil?.let { ((it - nowMs) / 1000 + 1).coerceAtLeast(0) }
    val canSubmit = pin.length >= 4 && remainingSec == null && !busy

    // Biometric unlock (panic-PIN phase 4): offered only when a biometric blob
    // is enrolled and we can host the prompt (MainActivity is a FragmentActivity).
    // State, not a constant: a key the phone invalidated takes the button away.
    val activity = remember(context) { context.findFragmentActivity() }
    var bioAvailable by remember { mutableStateOf(activity != null && PanicPinService.biometricEnabled(context)) }
    // A prompt is out and has not answered. Guards only the AUTOMATIC prompt,
    // never the button (#1049): a request the system dropped without a word
    // left this true for good, and the button, disabled by it while its
    // accent-coloured label still looked live, did nothing at all.
    var bioInFlight by remember { mutableStateOf(false) }
    // The person closed the prompt ("Use PIN", back) or it failed on this
    // visit: no automatic prompt again until the app has been left and come
    // back to. The button still asks.
    var bioDeclined by remember { mutableStateOf(false) }

    fun tryBiometric(auto: Boolean) {
        val act = activity ?: return
        if (!bioAvailable || remainingSec != null || busy) return
        if (auto && (bioInFlight || bioDeclined)) return
        bioInFlight = true
        if (!auto) error = null
        BiometricGate.unlock(
            act,
            context.getString(R.string.pin_biometric_prompt_title),
            context.getString(R.string.pin_biometric_prompt_subtitle),
            context.getString(R.string.pin_biometric_cancel),
        ) { outcome ->
            when (outcome) {
                is BiometricGate.Outcome.Unlocked -> scope.launch {
                    // Biometric always unlocks the REAL session: ensure decoy is
                    // off first (it can't actually be on — biometric and decoy
                    // are mutually exclusive — but stay safe against a race).
                    app.rcq.android.data.AccountManager.exitDecoyMode()
                    val ok = withContext(Dispatchers.Default) { PanicPinService.applyBiometricUnlock(context, outcome.blob) }
                    if (!ok) {
                        bioInFlight = false; bioDeclined = true
                        error = context.getString(R.string.pin_biometric_unavailable)
                    }
                    // success → PanicPinService.locked flips false → host recomposes away
                }
                is BiometricGate.Outcome.Dismissed -> {
                    bioInFlight = false
                    if (outcome.byPerson) bioDeclined = true
                }
                is BiometricGate.Outcome.Failed -> {
                    bioInFlight = false; bioDeclined = true
                    error = outcome.message?.let { context.getString(R.string.pin_biometric_error, it) }
                        ?: context.getString(R.string.pin_biometric_unavailable)
                }
                BiometricGate.Outcome.KeyReset -> {
                    bioInFlight = false; bioAvailable = false
                    error = context.getString(R.string.pin_biometric_reset)
                }
                // Not in front: asked again on the next resume, below.
                BiometricGate.Outcome.NotReady -> bioInFlight = false
            }
        }
    }

    // ⚠⚠ Prompt by itself every time the lock screen is actually in front: the
    // activity RESUMED and its window FOCUSED (#1049, iQOO Z10 and Huawei Pura
    // 80: the PIN screen with "Use biometrics" under it, and the under-display
    // sensor never lit). It used to prompt once per appearance, 400 ms after
    // the screen was composed, whatever the activity was doing: the lock
    // screen is composed on the way back from the background and at launch
    // under a permission dialog, a request made then is dropped (androidx
    // returns without a callback once the state is saved; an OEM biometric
    // service can ignore an app without focus), and nothing ever asked again.
    // Now each return to the screen asks, once the window has focus, unless
    // the person turned it down on this visit or one is already out.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            // A prompt does not outlive the app leaving the screen, and a new
            // visit may prompt again.
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) { bioInFlight = false; bioDeclined = false }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // This composition's tryBiometric, not the first one's: it reads the
    // lockout countdown, which is a plain value of the composition it ran in.
    val autoPrompt by androidx.compose.runtime.rememberUpdatedState { tryBiometric(auto = true) }
    LaunchedEffect(bioAvailable, lifecycleOwner) {
        if (!bioAvailable) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            view.awaitWindowFocus()
            // A beat for the window transition to settle.
            delay(250)
            autoPrompt()
        }
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
                    lockedOutUntil = PanicPinService.lockedOutUntilElapsed(context)
                }
                PanicPinService.SubmitResult.LOCKED_OUT -> {
                    pin = ""
                    lockedOutUntil = PanicPinService.lockedOutUntilElapsed(context)
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
        if (bioAvailable) {
            TextButton(
                onClick = { tryBiometric(auto = false) },
                enabled = remainingSec == null && !busy,
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

/** Suspends until this view's window has input focus. A biometric prompt asked
 *  for while another window holds it (a permission dialog, the shade, the
 *  transition back from the launcher) can be dropped by the system without a
 *  callback. */
private suspend fun android.view.View.awaitWindowFocus() {
    if (hasWindowFocus()) return
    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
        val listener = object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (!hasFocus) return
                runCatching { viewTreeObserver.removeOnWindowFocusChangeListener(this) }
                if (cont.isActive) cont.resumeWith(Result.success(Unit))
            }
        }
        viewTreeObserver.addOnWindowFocusChangeListener(listener)
        cont.invokeOnCancellation { runCatching { viewTreeObserver.removeOnWindowFocusChangeListener(listener) } }
        // Focus may have arrived between the check and the listener.
        if (hasWindowFocus()) {
            runCatching { viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
            if (cont.isActive) cont.resumeWith(Result.success(Unit))
        }
    }
}
