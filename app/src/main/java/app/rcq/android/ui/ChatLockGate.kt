package app.rcq.android.ui

import androidx.activity.compose.BackHandler
import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import app.rcq.android.security.BiometricGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.security.PanicPinService

/**
 * PIN gate shown before opening a per-chat-locked conversation. Verifies the
 * PIN that opened this session ([PanicPinService.verifySessionPin] — never the
 * wipe PIN, so this never triggers a wipe). On success [onUnlocked] reveals the
 * chat; back returns to the list without opening it.
 *
 * ⚠ The chat lock is the app PIN asked a second time, not a PIN of its own
 * (#1045 asked why they are the same). That is the design: the vault has one
 * real slot, and a second secret per chat would need slots, a lockout and a
 * recovery story of its own.
 */
@Composable
fun ChatLockGate(onBack: () -> Unit, onUnlocked: () -> Unit) {
    val context = LocalContext.current
    PinGate(
        title = context.getString(R.string.chat_locked_title),
        hint = context.getString(R.string.chat_locked_hint),
        onBack = onBack,
        onUnlocked = onUnlocked,
    )
}

/** The body of [ChatLockGate] for any screen that asks for the session's PIN
 *  before it shows itself: the locked chat, and the PIN settings (#1045).
 *
 *  [allowBiometric]: offer the biometric prompt as the PIN's equal, where the
 *  app itself accepts one (see [gateBiometricAvailable]). */
@Composable
fun PinGate(
    title: String,
    hint: String,
    onBack: () -> Unit,
    onUnlocked: () -> Unit,
    wrongHint: String? = null,
    allowBiometric: Boolean = true,
) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    val gate = rememberPinGate()
    val error = gate.wrong
    BackHandler { onBack() }

    fun submit() = gate.submit(pin, onOk = onUnlocked, onWrong = { pin = "" })

    val bio = allowBiometric && gateBiometricAvailable(context)
    val lockedSec = gate.remainingSec

    Column(
        Modifier.fillMaxSize().background(c.bgPrimary).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = c.accent, modifier = Modifier.height(40.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, color = c.textPrimary, fontSize = 20.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                lockedSec != null -> stringResource(R.string.pin_locked_out, lockedSec.toInt())
                error && wrongHint != null -> wrongHint
                else -> hint
            },
            color = if (lockedSec != null || (error && wrongHint != null)) androidx.compose.ui.graphics.Color(0xFFE5484D) else c.textSecondary,
            fontSize = 14.sp,
            // A hint that wraps (the PIN settings one does) reads as a ragged
            // left edge under a centred title otherwise.
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        // ⚠ Keeps the Material field: it is the only one with keyboardActions
        // (Done submits the PIN), which RcqField does not carry. A lock screen
        // where the keyboard's Done key stops working is worse than a lock
        // screen with an outline.
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(12); gate.wrong = false },
            isError = error,
            singleLine = true,
            // Not while checking (the check takes a moment on purpose) and not
            // while locked out: typing into a field that cannot be submitted
            // reads as a broken button.
            enabled = !gate.busy && lockedSec == null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = { submit() }, enabled = pin.isNotEmpty() && !gate.busy && lockedSec == null, modifier = Modifier.fillMaxWidth()) {
            if (gate.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.pin_unlock))
        }
        if (bio && lockedSec == null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { askGateBiometric(context, title, hint, onUnlocked) }) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.pin_biometric_lock_use), color = c.accent)
            }
        }
    }
}

/** One in-app PIN check in flight: whether it is running, whether the last
 *  answer was wrong, and the lockout it may have earned. Shared by every gate
 *  that asks for the PIN inside the unlocked app (#1045 review), so all of
 *  them count on the lock screen's counter ([PanicPinService.checkGatePin])
 *  and none of them runs PBKDF2 on the main thread. */
@Stable
internal class PinGateController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val realOnly: Boolean,
) {
    var busy by mutableStateOf(false)
        private set
    var wrong by mutableStateOf(false)
    var lockedOutUntil by mutableStateOf(PanicPinService.lockedOutUntil(context))
        private set
    var nowMs by mutableLongStateOf(System.currentTimeMillis())
        internal set

    /** Seconds left of a lockout, or null when there is none. */
    val remainingSec: Long?
        get() {
            val until = lockedOutUntil ?: return null
            if (until <= nowMs) return null
            return ((until - nowMs) / 1000 + 1).coerceAtLeast(1)
        }

    fun submit(pin: String, onOk: () -> Unit, onWrong: () -> Unit = {}) {
        if (busy || pin.isEmpty() || remainingSec != null) return
        busy = true
        wrong = false
        scope.launch {
            val r = withContext(Dispatchers.Default) { PanicPinService.checkGatePin(context, pin, realOnly) }
            busy = false
            nowMs = System.currentTimeMillis()
            when (r) {
                PanicPinService.GateCheck.OK -> onOk()
                PanicPinService.GateCheck.WRONG -> {
                    wrong = true
                    lockedOutUntil = PanicPinService.lockedOutUntil(context)
                    onWrong()
                }
                PanicPinService.GateCheck.LOCKED_OUT -> {
                    lockedOutUntil = PanicPinService.lockedOutUntil(context)
                    onWrong()
                }
            }
        }
    }
}

/** A [PinGateController] for this composition, with the lockout countdown
 *  ticking while there is one. [realOnly]: the real PIN and nothing else (a
 *  locked section, the recovery phrase); otherwise the PIN of this session. */
@Composable
internal fun rememberPinGate(realOnly: Boolean = false): PinGateController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gate = remember { PinGateController(context, scope, realOnly) }
    LaunchedEffect(gate.lockedOutUntil) {
        while (true) {
            val until = gate.lockedOutUntil ?: break
            gate.nowMs = System.currentTimeMillis()
            if (gate.nowMs >= until) break
            delay(500)
        }
    }
    return gate
}

/** Whether a gate may offer the biometric prompt instead of the PIN: exactly
 *  when the lock screen does (biometric unlock switched on, an activity to
 *  host the prompt), and never in a decoy session, where the only thing a
 *  fingerprint can open is the real account. */
internal fun gateBiometricAvailable(context: Context): Boolean =
    PanicPinService.biometricEnabled(context) && !PanicPinService.inDecoySession &&
        context.findFragmentActivity() != null

/** Ask for the fingerprint or face, and call [onOk] on a real success. The
 *  prompt decrypts the biometric-sealed vault blob; getting it back is the
 *  proof, the blob itself is not used. */
internal fun askGateBiometric(context: Context, title: String, subtitle: String, onOk: () -> Unit) {
    val act = context.findFragmentActivity() ?: return
    BiometricGate.unlock(act, title, subtitle, context.getString(R.string.pin_biometric_cancel)) { blob ->
        if (blob != null) onOk()
    }
}
