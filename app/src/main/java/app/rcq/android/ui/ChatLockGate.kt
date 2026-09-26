package app.rcq.android.ui

import androidx.activity.compose.BackHandler
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
 *  before it shows itself: the locked chat, and the PIN settings (#1045). */
@Composable
fun PinGate(
    title: String,
    hint: String,
    onBack: () -> Unit,
    onUnlocked: () -> Unit,
    wrongHint: String? = null,
) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    BackHandler { onBack() }

    fun submit() {
        if (PanicPinService.verifySessionPin(context, pin)) onUnlocked() else { error = true; pin = "" }
    }

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
            if (error && wrongHint != null) wrongHint else hint,
            color = if (error && wrongHint != null) androidx.compose.ui.graphics.Color(0xFFE5484D) else c.textSecondary,
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
            onValueChange = { pin = it.filter(Char::isDigit).take(12); error = false },
            isError = error,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = { submit() }, enabled = pin.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(context.getString(R.string.pin_unlock))
        }
    }
}
