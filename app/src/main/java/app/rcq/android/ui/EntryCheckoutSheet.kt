package app.rcq.android.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.BuildConfig
import app.rcq.android.R
import app.rcq.android.data.EntryInvoices
import app.rcq.android.net.RcqApi
import app.rcq.android.net.TillApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Paying for ENTRY (residency) to an island, in one sheet: [UinCheckoutSheet]
 * with the island where the number was.
 *
 * ⚠⚠ THE TILL IS THE ISLAND'S OWN, named on its /server/info as `till_url`,
 * and this sheet is never drawn for an island that names none. There is no
 * built-in fallback: the flagship's till compiled in would take a
 * self-hoster's customer's money for an account on somebody else's island,
 * and a Worker has no refund path. The price and the wallet on the invoice
 * are the island's too: the till asks the island for both, per invoice, so
 * what the picker quoted is what is charged and the money lands with the
 * operator who is selling.
 *
 * Under the pay controls, every time: who is selling and what can be given
 * back. On the flagship the seller is the RCQ team and the policy is at
 * rcq.app/terms#refunds; on a self-hosted island the OPERATOR is the seller,
 * their `terms_url` is opened when they set one, and when they did not the
 * sheet says refunds are their decision. Never rcq.app's terms for somebody
 * else's sale.
 *
 * ⚠⚠ SIDELOAD ONLY. The Play flavour never draws this sheet or the button
 * that opens it ([EntryBuyButton]): a store build that takes crypto for a
 * purchase the store does not handle is the shape both stores object to.
 * The Play build keeps the flagship's price as a fact and nothing more.
 *
 * This sheet never touches the island and never sees a token. It hands its
 * caller the signed access code and stops; the code sheets paste it into the
 * field, the residency row in Settings spends it on the account already here.
 */
@Composable
fun EntryCheckoutSheet(
    host: String,
    islandName: String,
    priceDisplay: String,
    tillUrl: String,
    termsUrl: String,
    resumeId: String?,
    onPaid: (voucher: String, invoiceId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = RcqTheme.colors
    val ctx = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var chains by remember { mutableStateOf<List<TillApi.Chain>>(emptyList()) }
    var invoice by remember { mutableStateOf<TillApi.EntryInvoice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf(0L) }
    val handed = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val flagship = host.equals(RcqApi.DEFAULT_HOST, ignoreCase = true)

    val unreachable = stringResource(R.string.uin_pay_error_unreachable)
    val notForSale = stringResource(R.string.entry_pay_not_for_sale)
    val busyMsg = stringResource(R.string.uin_pay_error_busy)
    val genericMsg = stringResource(R.string.uin_pay_error_generic)

    fun say(code: String) = when (code) {
        "entry_not_for_sale", "bad_host", "island_no_wallet", "no_till" -> notForSale
        "too_busy" -> busyMsg
        else -> if (code.startsWith("http_")) genericMsg else unreachable
    }

    LaunchedEffect(Unit) { EntryInvoices.init(ctx) }

    LaunchedEffect(resumeId) {
        if (resumeId != null) {
            runCatching { TillApi.entryInvoice(resumeId, tillUrl) }
                .onSuccess { inv ->
                    invoice = inv
                    if (inv.status == "paid" && !inv.voucher.isNullOrBlank() && !handed.value) {
                        handed.value = true
                        onPaid(inv.voucher, inv.id)
                    }
                }
                .onFailure { error = unreachable }
        } else {
            runCatching { TillApi.entryQuote(host, tillUrl) }
                .onSuccess { q ->
                    // The ISLAND's answer, through its till: a price of zero
                    // is "not on sale", whatever the picker said a minute ago.
                    if (q.price_cents <= 0 || q.chains.isEmpty()) error = notForSale
                    chains = q.chains
                }
                .onFailure { e -> error = say((e as? TillApi.TillException)?.code ?: "offline") }
        }
    }

    // Poll while an invoice is open; the voucher is handed up exactly once.
    LaunchedEffect(invoice?.id) {
        val id = invoice?.id ?: return@LaunchedEffect
        while (invoice?.status != "paid") {
            delay(6000)
            val fresh = runCatching { TillApi.entryInvoice(id, tillUrl) }.getOrNull() ?: continue
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

    // Who sells and what comes back, beside every control that takes money.
    @Composable
    fun Legal() {
        Column(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (flagship) stringResource(R.string.entry_pay_seller_rcq)
                else stringResource(R.string.entry_pay_seller_operator, islandName.ifBlank { host }, host),
                color = c.textSecondary, fontSize = 11.sp, textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.entry_pay_refund),
                color = c.textSecondary, fontSize = 11.sp, textAlign = TextAlign.Center,
            )
            if (termsUrl.startsWith("http", ignoreCase = true)) {
                Text(
                    stringResource(R.string.entry_pay_refund_link),
                    color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { runCatching { uriHandler.openUri(termsUrl) } }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            } else {
                Text(
                    stringResource(R.string.entry_pay_refund_operator),
                    color = c.textSecondary, fontSize = 11.sp, textAlign = TextAlign.Center,
                )
            }
        }
    }

    RcqSheet(onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.entry_pay_title, islandName.ifBlank { host }),
                color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(host, color = c.textSecondary, fontSize = 12.sp)
            Text(priceDisplay, color = c.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)

            val inv = invoice
            when {
                inv != null && inv.status == "paid" -> {
                    Text(
                        stringResource(R.string.entry_pay_paid),
                        color = c.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center,
                    )
                }

                inv != null -> {
                    Text(
                        stringResource(R.string.uin_pay_send, inv.chain_label),
                        color = c.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center,
                    )
                    CopyRow(stringResource(R.string.uin_pay_amount), inv.amount)
                    CopyRow(stringResource(R.string.uin_pay_address), inv.address)
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
                                stringResource(R.string.uin_pay_waiting, "%02d:%02d".format(left / 60, left % 60)),
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
                    Legal()
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
                                        runCatching { TillApi.createEntryInvoice(host, ch.id, tillUrl) }
                                            .onSuccess { inv ->
                                                // Written down BEFORE anything else
                                                // can fail: an invoice this device
                                                // cannot find again is money that
                                                // cannot be accounted for.
                                                EntryInvoices.remember(inv.id, inv.host, inv.chain, tillUrl)
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
                            Text(ch.label, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (busy) CircularProgressIndicator(color = c.accent, modifier = Modifier.size(22.dp))
                    Legal()
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
 * "Buy entry · $15" for the sheets that ask for a code, and the sheet behind
 * it. Asks the island for its door itself, so both callers stay one line;
 * draws NOTHING unless the island sells entry, names a till, and this is the
 * sideload build (see [EntryCheckoutSheet]). On open it also sweeps this
 * device's entry invoices for [host]: a payment that landed while nobody
 * was looking is handed to [onCode] like a fresh one, which is what it is.
 *
 * ⚠ [info] may be passed by a caller that already has the island's answer;
 * without it the island is asked once here.
 */
@Composable
internal fun EntryBuyButton(
    host: String,
    info: RcqApi.ServerInfoResponse? = null,
    onCode: (String) -> Unit,
) {
    if (BuildConfig.PLAY_STORE) return
    val c = RcqTheme.colors
    val ctx = LocalContext.current
    var fetched by remember(host) { mutableStateOf(info) }
    var open by remember { mutableStateOf(false) }
    var resume by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(host) {
        EntryInvoices.init(ctx)
        if (fetched == null) fetched = runCatching { RcqApi.serverInfoOf(host) }.getOrNull()
        val caps = fetched?.capabilities ?: return@LaunchedEffect
        val till = caps.till_url.trim()
        if (!till.startsWith("https://", ignoreCase = true)) return@LaunchedEffect
        // A payment that landed while nobody was looking.
        for (stored in EntryInvoices.all().filter { it.host.equals(host, ignoreCase = true) }) {
            val inv = runCatching { TillApi.entryInvoice(stored.id, stored.tillUrl.ifBlank { till }) }.getOrNull() ?: continue
            if (inv.status == "paid" && !inv.voucher.isNullOrBlank()) {
                EntryInvoices.forget(inv.id)
                onCode(inv.voucher)
                return@LaunchedEffect
            }
            if (inv.status == "expired") EntryInvoices.forget(inv.id)
        }
    }
    val caps = fetched?.capabilities ?: return
    val till = caps.till_url.trim().takeIf { it.startsWith("https://", ignoreCase = true) } ?: return
    val cents = caps.entry_price_cents
    if (cents <= 0) return
    val price = if (cents % 100 == 0) "$" + (cents / 100) else "$" + "%.2f".format(cents / 100.0)
    Text(
        stringResource(R.string.entry_buy, price),
        color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                resume = EntryInvoices.forHost(host)?.id
                open = true
            }
            .padding(vertical = 6.dp, horizontal = 4.dp),
    )
    if (open) {
        EntryCheckoutSheet(
            host = host,
            islandName = fetched?.name?.trim().orEmpty(),
            priceDisplay = stringResource(R.string.island_entry_price, price),
            tillUrl = till,
            termsUrl = caps.terms_url.trim(),
            resumeId = resume,
            onPaid = { voucher, id ->
                EntryInvoices.forget(id)
                open = false
                onCode(voucher)
            },
            onDismiss = { open = false },
        )
    }
}
