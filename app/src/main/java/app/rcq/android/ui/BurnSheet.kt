package app.rcq.android.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.net.BurnCascade
import app.rcq.android.net.BurnCascade.IslandBurnResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One burn sheet's progress. Snapshot state in a plain holder, because the
 *  work runs on the Settings screen's scope and may finish after the sheet was
 *  dragged away. */
private class BurnFlow {
    enum class Stage { CONFIRM, WORKING, FAILURES }

    var plan by mutableStateOf<Session.BurnPlan?>(null)
    var loaded by mutableStateOf(false)
    var stage by mutableStateOf(Stage.CONFIRM)
    var homePhase by mutableStateOf(false)
    var results by mutableStateOf<Map<String, IslandBurnResult>>(emptyMap())

    /** The sheet is still on screen. */
    @Volatile var open = true

    /** Burned, kept, or refused: nothing is left to undo. */
    @Volatile var settled = false
}

/**
 * Burning the account (F2 of the 15.09 cross-island spec), as a state machine:
 * Confirm → Working (other islands) → Failures (Try again, Burn anyway, Cancel)
 * → Working (home island) → done.
 *
 * ⚠ Every per-island line is the island's claim ("island confirmed deletion"),
 * never a statement that something was deleted: an operator controls the
 * answer on every route.
 */
@Composable
internal fun BurnSheet(session: Session, scope: CoroutineScope, onClose: () -> Unit, onBurned: (Int?) -> Unit) {
    val c = RcqTheme.colors
    val context = LocalContext.current.applicationContext
    val flow = remember { BurnFlow() }
    LaunchedEffect(Unit) {
        flow.plan = session.burnPlan()
        flow.loaded = true
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    /** Keep the account. Copies already deleted elsewhere stay deleted, and
     *  the person is told where. */
    fun cancel() {
        if (flow.settled) return
        flow.settled = true
        flow.plan?.let { session.burnCancel(it) }
        val gone = flow.results.filterValues { it is IslandBurnResult.Confirmed }.keys
        if (gone.isNotEmpty()) toast(context.getString(R.string.burn_cancel_partial, gone.joinToString(", ")))
    }

    fun finishHome(p: Session.BurnPlan) {
        flow.stage = BurnFlow.Stage.WORKING
        flow.homePhase = true
        val remoteDone = flow.results.values.count { it.isDone }
        val anyConfirmed = flow.results.values.any { it is IslandBurnResult.Confirmed }
        scope.launch {
            val f = session.burnFinish(p)
            flow.settled = true
            when (f) {
                is Session.BurnFinish.Burned -> {
                    val lines = mutableListOf<String>()
                    if (remoteDone > 0) lines += context.resources.getQuantityString(R.plurals.burn_done, remoteDone, remoteDone)
                    // Same-key accounts on our island that the island did not
                    // confirm deleting stay on this device, and the person is told.
                    f.kept.forEach { (uin, host) -> lines += context.getString(R.string.burn_sibling_kept, uin, host) }
                    if (lines.isNotEmpty()) toast(lines.joinToString("\n"))
                    onBurned(f.nextUin)
                }
                Session.BurnFinish.HomeFailed -> {
                    toast(context.getString(if (anyConfirmed) R.string.burn_home_failed_partial else R.string.burn_failed))
                    onClose()
                }
                Session.BurnFinish.Refused -> {
                    toast(context.getString(R.string.burn_failed))
                    onClose()
                }
            }
        }
    }

    fun runRemote(p: Session.BurnPlan, only: Set<String>?) {
        flow.stage = BurnFlow.Stage.WORKING
        flow.homePhase = false
        scope.launch {
            try {
                val r = session.burnRemote(p, only)
                flow.results = flow.results + r
                when {
                    flow.results.values.all { it.isDone } -> finishHome(p)
                    // Nobody is left to choose: keep the account.
                    !flow.open -> cancel()
                    else -> { flow.stage = BurnFlow.Stage.FAILURES }
                }
            } catch (e: CancellationException) {
                // The screen went away mid-cascade: the drains must not stay
                // paused behind a burn nobody will finish.
                cancel()
                throw e
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            flow.open = false
            if (flow.stage != BurnFlow.Stage.WORKING) cancel()
        }
    }

    val p = flow.plan
    when (flow.stage) {
        BurnFlow.Stage.CONFIRM -> {
            val parts = mutableListOf(stringResource(R.string.cs_burn_body))
            if (p != null && !p.decoy) {
                val n = p.remoteHosts.size
                if (n > 0) {
                    parts += context.resources.getQuantityString(R.plurals.burn_islands_body, n, n, p.remoteHosts.joinToString(", "))
                }
                if (p.ownedGroups.isNotEmpty()) parts += stringResource(R.string.burn_owned_groups, p.ownedGroups.joinToString(", "))
                p.siblings.forEach { (uin, host) -> parts += stringResource(R.string.burn_sibling, uin, host) }
                parts += stringResource(R.string.burn_not_covered)
            }
            RcqAskSheet(
                onDismiss = onClose,
                title = stringResource(R.string.cs_burn_title),
                body = parts.joinToString("\n\n"),
                actions = listOf(
                    SheetAction(stringResource(R.string.cs_burn_cta), destructive = true, onClick = {
                        val plan = flow.plan
                        when {
                            !flow.loaded -> Unit
                            plan == null -> {
                                flow.settled = true
                                toast(context.getString(R.string.burn_failed))
                                onClose()
                            }
                            plan.remoteHosts.isEmpty() -> finishHome(plan)
                            else -> runRemote(plan, null)
                        }
                    }),
                ),
            )
        }
        BurnFlow.Stage.WORKING -> RcqSheet(onDismiss = onClose, title = stringResource(R.string.cs_burn_title)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                if (!flow.homePhase) Text(stringResource(R.string.burn_progress), color = c.textSecondary, fontSize = 14.sp)
            }
        }
        BurnFlow.Stage.FAILURES -> {
            val failed = flow.results.filterValues { !it.isDone }.keys
            val lines = flow.results.map { (host, r) -> "$host: ${burnRowText(r)}" }
            RcqAskSheet(
                onDismiss = onClose,
                title = context.resources.getQuantityString(R.plurals.burn_failures_title, failed.size, failed.size),
                body = lines.joinToString("\n") + "\n\n" + stringResource(R.string.burn_anyway_hint),
                actions = listOf(
                    SheetAction(stringResource(R.string.burn_retry), onClick = { flow.plan?.let { runRemote(it, failed) } }),
                    SheetAction(stringResource(R.string.burn_anyway), destructive = true, onClick = { flow.plan?.let { finishHome(it) } }),
                ),
            )
        }
    }
}

@Composable
private fun burnRowText(r: IslandBurnResult): String = stringResource(
    when (r) {
        is IslandBurnResult.Confirmed -> R.string.burn_row_confirmed
        IslandBurnResult.AlreadyGone -> R.string.burn_row_already_gone
        IslandBurnResult.NotTried -> R.string.burn_row_not_tried
        is IslandBurnResult.Failed -> when (r.reason) {
            BurnCascade.Reason.OFFLINE -> R.string.burn_row_offline
            BurnCascade.Reason.TIMEOUT -> R.string.burn_row_timeout
            BurnCascade.Reason.SUSPENDED -> R.string.burn_row_suspended
            BurnCascade.Reason.TOO_OLD -> R.string.burn_row_too_old
            BurnCascade.Reason.LIMIT -> R.string.burn_row_limit
            BurnCascade.Reason.SERVER -> R.string.burn_row_server
        }
    },
)
