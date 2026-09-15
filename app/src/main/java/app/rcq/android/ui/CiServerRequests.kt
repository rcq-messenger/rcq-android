package app.rcq.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.rcq.android.R
import app.rcq.android.model.RcqGroup
import app.rcq.android.net.CrossIslandRequestsStore
import app.rcq.android.net.GuestPendingRequests

/**
 * The line under a request that came from a guest poll (F1 of the 15.09
 * cross-island spec): which island holds it and whether that person shares a
 * room with us there, or why the row is still here after an accept.
 *
 * "via group" is read off cached rosters only, never fetched, and it is the
 * island's word: B serves those rosters too. It is a hint for recognising
 * somebody, which is why a row without a shared room is shown, not folded away.
 */
@Composable
internal fun ciServerLine(r: CrossIslandRequestsStore.Request, groups: List<RcqGroup>): String {
    if (r.viaKeyChanged) return stringResource(R.string.ci_srv_key_differs, r.host)
    if (r.srvAcceptTries >= GuestPendingRequests.MAX_ACCEPT_TRIES) return stringResource(R.string.ci_srv_gave_up, r.host)
    if (r.srvAcceptTries > 0) return stringResource(R.string.ci_srv_retrying, r.host)
    val rooms = GuestPendingRequests.sharedRooms(groups, r.host, r.uin)
    val via = when (rooms.size) {
        0 -> stringResource(R.string.ci_srv_no_group)
        1 -> stringResource(R.string.ci_srv_via_group, rooms[0])
        else -> stringResource(R.string.ci_srv_via_groups, rooms[0], rooms.size - 1)
    }
    return stringResource(R.string.ci_srv_subtitle, r.host) + " · " + via
}

/**
 * Accepting a guest-poll request, as a sheet. The requester is known only
 * through island [CrossIslandRequestsStore.Request.host], and accepting hands
 * them our home number, so the sheet says both before anything leaves (critic
 * 6). [keyWarn]: the island's card differs from a key we saw for that number in
 * one of its rooms; the accept then reads as a warning.
 */
@Composable
internal fun CiServerAcceptSheet(
    req: CrossIslandRequestsStore.Request,
    keyWarn: Boolean,
    onDismiss: () -> Unit,
    onAccept: () -> Unit,
) {
    val address = "${req.uin}@${req.host}"
    val title = req.nickname?.takeIf { it.isNotBlank() }?.let { "$it · $address" } ?: address
    val hint = stringResource(R.string.ci_srv_accept_hint, req.host)
    val warning = if (keyWarn) stringResource(R.string.ci_srv_key_differs, req.host) else null
    RcqAskSheet(
        onDismiss = onDismiss,
        title = title,
        body = listOfNotNull(warning, hint).joinToString("\n\n"),
        actions = listOf(SheetAction(stringResource(R.string.home_accept), destructive = keyWarn, onClick = onAccept)),
    )
}
