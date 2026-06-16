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
 * user's REAL PIN ([PanicPinService.verifyRealPin] — never the panic PIN, so this
 * never triggers a wipe). On success [onUnlocked] reveals the chat; back returns
 * to the list without opening it.
 */
@Composable
fun ChatLockGate(onBack: () -> Unit, onUnlocked: () -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    BackHandler { onBack() }

    fun submit() {
        if (PanicPinService.verifyRealPin(context, pin)) onUnlocked() else { error = true; pin = "" }
    }

    Column(
        Modifier.fillMaxSize().background(c.bgPrimary).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = c.accent, modifier = Modifier.height(40.dp))
        Spacer(Modifier.height(16.dp))
        Text(context.getString(R.string.chat_locked_title), color = c.textPrimary, fontSize = 20.sp)
        Spacer(Modifier.height(6.dp))
        Text(context.getString(R.string.chat_locked_hint), color = c.textSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))
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
