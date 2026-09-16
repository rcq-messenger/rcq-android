package app.rcq.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import kotlinx.coroutines.launch

/**
 * "Become a resident of {host}" on a guest copy (decision D7, spec 2026-09-15
 * sections 9.1 and 12.1).
 *
 * A guest copy takes part in rooms and nothing else. Settling turns the SAME row
 * into a resident's account, number and rooms included: with an entry voucher
 * or an invite in the one code box, or with nothing on an open island. Opened
 * from the guest-copy banner of an account that is itself a copy ([host] null)
 * and from the group screen of a room where our copy on its island is a guest.
 *
 * ⚠ A code box only: no purchase link here. The island's own door screen is
 * where entry is bought.
 */
@Composable
internal fun GuestSettleSheet(session: Session, host: String?, onDismiss: () -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()
    val island = host ?: session.currentServer
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    RcqSheet(onDismiss = onDismiss, title = stringResource(R.string.guest_settle_action, island)) {
        if (!done) {
            RcqField(
                value = code,
                onValueChange = { if (it.length <= 512) code = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(R.string.guest_settle_code_hint),
                singleLine = true,
                enabled = !busy,
            )
            Spacer(Modifier.height(10.dp))
        }
        said?.let {
            Text(
                it,
                color = if (done) c.textPrimary else c.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (!done) {
            SettleRow(stringResource(if (busy) R.string.residency_redeeming else R.string.residency_redeem), dimmed = busy) {
                if (!busy) {
                    busy = true
                    said = null
                    scope.launch {
                        val r = session.settleGuestCopy(host, code)
                        busy = false
                        said = r.sentence
                        done = r.done
                    }
                }
            }
        }
        SettleRow(stringResource(if (done) R.string.common_close else R.string.common_cancel), dimmed = true, onClick = onDismiss)
    }
}

@Composable
private fun SettleRow(label: String, dimmed: Boolean, onClick: () -> Unit) {
    val c = RcqTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (dimmed) c.textSecondary else c.accent,
            fontSize = 16.sp,
            fontWeight = if (dimmed) FontWeight.Normal else FontWeight.Medium,
        )
    }
}
