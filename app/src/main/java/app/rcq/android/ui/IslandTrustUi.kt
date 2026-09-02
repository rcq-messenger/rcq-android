package app.rcq.android.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.net.IslandTrust

/**
 * What an address form says under the field when the `#fp` fragment of design
 * §3 cannot be used, or null when the address is fine.
 *
 * One place because there are six forms taking an island address (the picker,
 * the add-account dialog, restore, the custom-server screen, the backup-island
 * field, and [app.rcq.android.Session.normalizeHost] as the backstop for
 * everything that is not a form), and they have to say the same thing about
 * the same address. [IslandTrust.adopt] has already done the deciding: a
 * disagreement raised the banner and dialled nothing.
 */
fun islandAddressError(ctx: Context, entry: IslandTrust.Entry): String? = when (entry) {
    is IslandTrust.Entry.Ok, is IslandTrust.Entry.Empty -> null
    is IslandTrust.Entry.Malformed -> ctx.getString(R.string.island_trust_not_an_address)
    is IslandTrust.Entry.NotAFingerprint -> ctx.getString(R.string.island_trust_not_fingerprint)
    is IslandTrust.Entry.CaOnly -> ctx.getString(R.string.island_trust_ca_only, entry.host)
    is IslandTrust.Entry.Disagrees -> ctx.getString(R.string.island_trust_disagrees, entry.changed.hostPort)
}

/**
 * Every island refused right now, drawn where the person is standing.
 *
 * ⚠ The banner used to exist on the main screen alone, and the accept button
 * of §5.2 was therefore unreachable on every path that has no main screen
 * under it: first-run onboarding, restore, and the failed-to-register screen.
 * A person who typed the operator's `host:8443#fp` on first run against an
 * island whose certificate had since been rotated got "decide at the notice"
 * and nothing to decide at, with "Try again" repeating the same refusal for
 * ever. The settings forms are the same story one screen away from Home.
 */
@Composable
fun IslandTrustNotices(modifier: Modifier = Modifier) {
    val changed by IslandTrust.changed.collectAsState()
    val hidden by IslandTrust.hidden.collectAsState()
    val visible = changed.filterKeys { it !in hidden }
    if (visible.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        for ((_, ch) in visible) IslandTrustBanner(ch)
    }
}

/// An island presented a certificate this device does not trust (design §5.2).
/// Red, in the banner slot with the two below it; the island stays refused and
/// offline until one of the two buttons is pressed. "Not now" only folds the
/// banner away: the next refused handshake brings it back.
@Composable
internal fun IslandTrustBanner(ch: IslandTrust.Changed) {
    val c = RcqTheme.colors
    val body = when {
        // Not the form's sentence: this IS the notice it points at, and being
        // sent to look for it from inside it is no help.
        ch.typedNew -> stringResource(R.string.island_trust_disagrees_banner, ch.hostPort)
        ch.typed -> stringResource(R.string.island_trust_changed_typed, ch.hostPort)
        else -> stringResource(R.string.island_trust_changed, ch.hostPort)
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp)).background(c.bgSecondary.copy(alpha = LocalHomeVeil.current)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, tint = c.statusBusy, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(ch.hostPort, color = c.statusBusy, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Text(body, color = c.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(8.dp))
        IslandTrustFingerprint(
            label = stringResource(R.string.island_trust_on_file),
            value = ch.old?.let { IslandTrust.displayFingerprint(it) }
                ?: stringResource(R.string.island_trust_via_ca),
        )
        Spacer(Modifier.height(6.dp))
        IslandTrustFingerprint(
            label = stringResource(if (ch.typedNew) R.string.island_trust_entered else R.string.island_trust_presented),
            value = IslandTrust.displayFingerprint(ch.new),
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { IslandTrust.later(ch.key) }) {
                Text(stringResource(R.string.island_trust_later), color = c.textSecondary)
            }
            TextButton(onClick = { IslandTrust.accept(ch.key) }) {
                Text(stringResource(R.string.island_trust_accept), color = c.accent)
            }
        }
    }
}

/**
 * The first-use notice (design §5.1), with its own snackbar body.
 *
 * ⚠ The default `Snackbar(snackbarData = …)` draws the message in the theme's
 * body style, which is proportional, and the fingerprint is 16 groups of 4
 * wrapped four to a line: in a proportional font the columns drift, and this
 * notice exists for exactly one purpose, holding four lines of hex next to
 * what the operator published. The banner and the Settings row already set
 * Monospace; the one place the fingerprint is FIRST seen did not.
 */
@Composable
internal fun IslandFirstUseSnackbar(data: SnackbarData, fp: String?) {
    val c = RcqTheme.colors
    Snackbar(
        actionOnNewLine = true,
        containerColor = c.bgSecondary,
        contentColor = c.textPrimary,
        action = {
            data.visuals.actionLabel?.let {
                TextButton(onClick = { data.dismiss() }) { Text(it, color = c.accent) }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(data.visuals.message, color = c.textPrimary, fontSize = 13.sp, lineHeight = 18.sp)
            if (fp != null) {
                Text(
                    IslandTrust.displayFingerprint(fp),
                    color = c.textPrimary, fontSize = 13.sp, lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                stringResource(R.string.island_trust_first_use_check),
                color = c.textSecondary, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
    }
}

/** A fingerprint reads as a grid only in a fixed-width font (design §2). */
@Composable
internal fun IslandTrustFingerprint(label: String, value: String) {
    val c = RcqTheme.colors
    Text(label, color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Text(value, color = c.textPrimary, fontSize = 13.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
}
