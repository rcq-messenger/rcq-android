package app.rcq.android.ui

import android.content.Context
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
    is IslandTrust.Entry.Malformed -> ctx.getString(R.string.csrv_unreachable)
    is IslandTrust.Entry.NotAFingerprint -> ctx.getString(R.string.island_trust_not_fingerprint)
    is IslandTrust.Entry.CaOnly -> ctx.getString(R.string.island_trust_ca_only, entry.host)
    is IslandTrust.Entry.Disagrees -> ctx.getString(R.string.island_trust_disagrees, entry.changed.hostPort)
}
