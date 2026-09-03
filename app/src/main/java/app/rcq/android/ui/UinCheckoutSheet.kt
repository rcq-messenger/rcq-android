package app.rcq.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.scale
import app.rcq.android.R
import app.rcq.android.data.UinInvoices
import app.rcq.android.net.TillApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Paying for a number, in one sheet.
 *
 * The shape follows what the money actually needs: pick a chain, send an EXACT
 * amount to an address, wait. No account to create, no card form, no redirect —
 * the amount is what identifies the payment, so the whole checkout is two values
 * and a clock.
 *
 * ⚠ THE QR IS BEHIND A TAP, not on screen by default (founder, 03.09). The
 * person is almost always paying from this same phone, where a code is a picture
 * standing between them and the address they need to copy. It earns its place
 * only when there is a second device to scan with, so it is offered rather than
 * imposed.
 *
 * ⚠⚠ This sheet never touches the island and never sees a token. It hands its
 * caller a signed voucher and stops; redeeming is the shop's job.
 */

// ── the coins, drawn ────────────────────────────────────────────────────
//
// ⚠ Vector paths rather than downloaded images. A payment picker is the last
// place to fetch an icon from somebody else's server: that request says "this
// person is about to pay, right now, from this address", which is exactly what
// the rest of this app works not to emit.
private const val USDT_PATH =
    "M13.42 10.62v-1.6h3.66V6.58H6.93v2.44h3.66v1.6C7.6 10.76 5.36 11.35 5.36 12.06" +
        "c0 .7 2.24 1.3 5.23 1.44v4.62h2.83v-4.62c2.98-.14 5.22-.74 5.22-1.44" +
        "c0-.71-2.24-1.3-5.22-1.44Zm0 2.44v-.01c-.08 0-.47.03-1.35.03-.7 0-1.2-.02-1.38-.03v.01" +
        "c-2.4-.11-4.19-.53-4.19-1.03 0-.5 1.79-.92 4.19-1.03v1.63c.18.01.7.04 1.39.04" +
        ".84 0 1.26-.03 1.34-.04v-1.63c2.39.11 4.18.53 4.18 1.03 0 .5-1.79.92-4.18 1.03Z"
private const val TON_PATH =
    "M16.94 6.5H7.06c-1.02 0-1.67 1.1-1.16 1.99l5.22 9.06c.22.39.78.39 1 0l5.22-9.06" +
        "c.51-.89-.14-1.99-1.16-1.99h-.24Zm-5.42 8.4L10.4 12.6 8.06 8.68a.29.29 0 0 1 .25-.44h3.21v6.66Z" +
        "m4.16-6.22-2.34 3.92-1.12 2.3V8.24h3.21c.24 0 .38.24.25.44Z"

@Composable
private fun CoinMark(chain: String, size: androidx.compose.ui.unit.Dp = 26.dp) {
    val spec = when (chain) {
        "tron" -> Color(0xFF26A17B) to USDT_PATH
        "ton" -> Color(0xFF0098EA) to TON_PATH
        else -> return
    }
    val path = remember(chain) { PathParser().parsePathString(spec.second).toPath() }
    Canvas(Modifier.size(size)) {
        drawCircle(spec.first)
        // The paths are authored on a 24x24 grid; scale to whatever the caller
        // asked for rather than hard-coding a size into the geometry.
        scale(this.size.width / 24f, this.size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(path, Color.White)
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String, mono: Boolean = true) {
    val c = RcqTheme.colors
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(1600); copied = false }
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.bgPrimary)
            .clickable {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(label, value))
                copied = true
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = c.textSecondary, fontSize = 11.sp)
            Text(
                stringResource(if (copied) R.string.uin_pay_copied else R.string.uin_pay_copy),
                color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            value,
            color = c.textPrimary,
            fontSize = 14.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * @param resumeId an invoice this device already opened for [uin]. ⚠ Passing it
 *   is what stops a closed app from stranding somebody mid-payment: without it
 *   the sheet would offer to create a second invoice for a number the first one
 *   is holding, and the till would answer "taken" — by its own reservation.
 * @param onPaid handed the signed voucher and the invoice id. The caller
 *   redeems; this sheet has no idea what an island is.
 */
@Composable
fun UinCheckoutSheet(
    uin: Int,
    priceDisplay: String,
    /** The till of the island selling this number, from its own quote.
     *
     *  ⚠⚠ Passed in rather than assumed, because a till serves ONE island.
     *  Paying the built-in address for a number on somebody else's island
     *  sends real money where the number is not, and nothing undoes it. Null
     *  only for an island too old to name one, which can only be ours. */
    checkoutUrl: String?,
    resumeId: String?,
    onPaid: (voucher: String, invoiceId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = RcqTheme.colors
    var chains by remember { mutableStateOf<List<TillApi.Chain>>(emptyList()) }
    var invoice by remember { mutableStateOf<TillApi.Invoice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf(0L) }
    val handed = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val unreachable = stringResource(R.string.uin_pay_error_unreachable)
    val takenMsg = stringResource(R.string.uin_pay_error_taken)
    val busyMsg = stringResource(R.string.uin_pay_error_busy)
    val genericMsg = stringResource(R.string.uin_pay_error_generic)

    fun say(code: String) = when (code) {
        "uin_taken" -> takenMsg
        "too_busy" -> busyMsg
        "not_for_sale" -> genericMsg
        else -> if (code.startsWith("http_")) genericMsg else unreachable
    }

    // Pick up where a previous visit left off, or ask what we can be paid in.
    LaunchedEffect(resumeId) {
        if (resumeId != null) {
            runCatching { TillApi.invoice(resumeId, checkoutUrl) }
                .onSuccess { inv ->
                    invoice = inv
                    if (inv.status == "paid" && !inv.voucher.isNullOrBlank() && !handed.value) {
                        handed.value = true
                        onPaid(inv.voucher, inv.id)
                    }
                }
                .onFailure { error = unreachable }
        } else {
            runCatching { TillApi.prices(checkoutUrl) }
                .onSuccess { chains = it.chains }
                .onFailure { error = unreachable }
        }
    }

    // Poll while an invoice is open. ⚠ The voucher is handed up exactly once:
    // the till keeps returning it, and redeeming twice is a refusal, not a
    // second number.
    LaunchedEffect(invoice?.id) {
        val id = invoice?.id ?: return@LaunchedEffect
        while (invoice?.status != "paid") {
            // The chains we take confirm in seconds (TON) or about a minute
            // (TRON), and the till's own watcher runs once a minute, so
            // anything faster is asking a question that cannot have changed.
            delay(6000)
            val fresh = runCatching { TillApi.invoice(id, checkoutUrl) }.getOrNull() ?: continue
            invoice = fresh
            if (fresh.status == "paid" && !fresh.voucher.isNullOrBlank() && !handed.value) {
                handed.value = true
                onPaid(fresh.voucher, fresh.id)
                return@LaunchedEffect
            }
        }
    }

    LaunchedEffect(invoice?.expires_at) {
        val exp = invoice?.expires_at ?: return@LaunchedEffect
        while (true) {
            left = (exp - System.currentTimeMillis() / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }

    RcqSheet(onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(uin.toString(), color = c.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(priceDisplay, color = c.textSecondary, fontSize = 15.sp)

            val inv = invoice
            when {
                inv != null && inv.status == "paid" -> {
                    Text(
                        stringResource(R.string.uin_pay_received),
                        color = c.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center,
                    )
                    CircularProgressIndicator(color = c.accent, modifier = Modifier.size(22.dp))
                }

                inv != null -> {
                    Text(
                        stringResource(R.string.uin_pay_send, inv.chain_label),
                        color = c.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center,
                    )
                    CopyRow(stringResource(R.string.uin_pay_amount), inv.amount)
                    CopyRow(stringResource(R.string.uin_pay_address), inv.address)

                    // ⚠ Offered, not imposed — see the file comment.
                    if (!showQr) {
                        Text(
                            stringResource(R.string.uin_pay_show_qr),
                            color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showQr = true }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    } else {
                        val payload = remember(inv.id) { payUri(inv.chain, inv.address, inv.amount) }
                        val bmp = remember(payload) { qrBitmap(payload) }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                filterQuality = FilterQuality.None,
                                modifier = Modifier.size(190.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White)
                                    .padding(10.dp),
                            )
                        }
                        Text(
                            stringResource(R.string.uin_pay_hide_qr),
                            color = c.textSecondary, fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showQr = false }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (left > 0) {
                            CircularProgressIndicator(color = c.textSecondary, modifier = Modifier.size(14.dp))
                            Text(
                                stringResource(
                                    R.string.uin_pay_waiting,
                                    "%02d:%02d".format(left / 60, left % 60),
                                ),
                                color = c.textSecondary, fontSize = 13.sp,
                            )
                        } else {
                            Text(stringResource(R.string.uin_pay_expired),
                                 color = c.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
                        }
                    }
                    Text(
                        stringResource(R.string.uin_pay_exact),
                        color = c.textSecondary, fontSize = 11.sp, textAlign = TextAlign.Center,
                    )
                }

                else -> {
                    Text(
                        stringResource(R.string.uin_pay_pick),
                        color = c.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center,
                    )
                    chains.forEach { ch ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(c.bgPrimary)
                                .clickable(enabled = !busy) {
                                    busy = true; error = null
                                    scope.launch {
                                        runCatching { TillApi.createInvoice(uin, ch.id, checkoutUrl) }
                                            .onSuccess { inv ->
                                                // ⚠⚠ Written down BEFORE anything
                                                // else can fail. An invoice this
                                                // device cannot find again is
                                                // money that cannot be accounted
                                                // for.
                                                UinInvoices.remember(inv.id, inv.uin, inv.chain, checkoutUrl)
                                                invoice = inv
                                            }
                                            .onFailure { e ->
                                                error = say((e as? TillApi.TillException)?.code ?: "offline")
                                            }
                                        busy = false
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CoinMark(ch.id)
                            Text(ch.label, color = c.textPrimary, fontSize = 15.sp,
                                 fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (busy) CircularProgressIndicator(color = c.accent, modifier = Modifier.size(22.dp))
                }
            }

            error?.let {
                Text(it, color = c.statusBusy, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Box(Modifier.height(4.dp))
        }
    }
}

/**
 * Wallets read `ton://` links; a bare address is what every wallet understands
 * when it does not. ⚠ The amount rides in the link where the scheme has a place
 * for it and is ALWAYS shown as text too: a wallet that ignored the parameter
 * would otherwise send a figure that matches nothing.
 */
internal fun payUri(chain: String, address: String, amount: String): String =
    if (chain == "ton") {
        val nano = runCatching { (amount.toDouble() * 1_000_000_000L).toLong() }.getOrDefault(0L)
        "ton://transfer/$address?amount=$nano"
    } else {
        address
    }
