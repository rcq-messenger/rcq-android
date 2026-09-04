package app.rcq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.data.UinInvoices
import app.rcq.android.net.TillApi
import app.rcq.android.Session
import app.rcq.android.net.RcqApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * UIN marketplace. The user types a 3-9 digit number, the server (/uin/quote)
 * confirms availability, the price flips to accent when free, one tap on the
 * bottom capsule takes it.
 *
 * Taking it no longer makes you it. The number lands in the account's
 * collection (POST /uin/purchase with switch=false) and answering as it is a
 * second, deliberate step — offered right here for whoever wants it now, and
 * on My numbers for everyone else. Buying and changing the identity everyone
 * knows you by used to be the same tap, which is a bad thing to have one
 * button away from browsing. Either way local chat history survives: it is
 * peer-keyed, and only our own number changes.
 */
@Composable
fun UinShopScreen(
    session: Session,
    onBack: () -> Unit,
    onMigrated: (Int) -> Unit,
    onOpenMyUins: () -> Unit,
) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()

    var typed by remember { mutableStateOf("") }
    var quote by remember { mutableStateOf<RcqApi.QuoteResponse?>(null) }
    var checking by remember { mutableStateOf(false) }
    var buying by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    /// What other people are selling. A window with a refresh, not a
    /// catalogue: the number somebody actually wants is found by typing it,
    /// where the quote names the seller and the price.
    var listings by remember { mutableStateOf<List<RcqApi.UinListingItem>>(emptyList()) }
    var loadingListings by remember { mutableStateOf(false) }

    suspend fun loadListings() {
        loadingListings = true
        listings = session.uinListings(12)
        loadingListings = false
    }
    LaunchedEffect(Unit) { loadListings() }
    // Set once the number is in the collection: the "it is yours, move onto it
    // now or later?" step.
    var held by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Resolved here (composable scope) so the purchase callback can use them.
    val takenMsg = stringResource(R.string.uin_shop_error_taken)
    val tooManyMsg = stringResource(R.string.uin_shop_error_too_many)
    val reservedMsg = stringResource(R.string.uin_shop_error_reserved)
    val genericMsg = stringResource(R.string.uin_shop_error_generic)
    val takenPaidMsg = stringResource(R.string.uin_shop_error_taken_paid)

    // The number being paid for right now, if the checkout sheet is open.
    var checkout by remember { mutableStateOf<Int?>(null) }
    var redeeming by remember { mutableStateOf(false) }
    // ⚠ Guards the one thing here that money depends on: a voucher is redeemed
    // ONCE. The till keeps handing it back on every poll, so the guard has to
    // outlive recomposition.
    val redeemed = remember { mutableStateOf(mutableSetOf<String>()) }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { UinInvoices.init(ctx) }

    val isValidLength = typed.length in 3..9
    // The quote is only meaningful while it still matches what's typed.
    val displayedQuote = quote?.takeIf { it.uin.toString() == typed }
    val isAvailable = displayedQuote?.available == true
    // ⚠⚠ A paid number is recognised by `acquire`, NOT by `available`. The
    // island keeps `available` false for scarce stock on purpose, because three
    // released clients read that one field and would otherwise offer, for
    // nothing, exactly the numbers that are for sale.
    // A number the island sells, and one a PERSON sells. Both end in a payment
    // and a voucher, so the button is the same; what differs is whose money it
    // becomes and whether a name is shown beside the price.
    val forSale = isValidLength &&
        (displayedQuote?.acquire == "purchase" || displayedQuote?.acquire == "resale") &&
        (displayedQuote.price_cents ?: 0) > 0
    val sellerUin = displayedQuote?.takeIf { it.acquire == "resale" }?.seller_uin
    // An invoice this device already opened for the number in the field. It is
    // holding that number, which is precisely why the quote says unavailable.
    val resumable = if (isValidLength) typed.toIntOrNull()?.let { UinInvoices.forUin(it) } else null
    val canBuy = isValidLength && isAvailable && !buying

    // Debounced availability lookup. LaunchedEffect cancels the prior coroutine
    // on every keystroke, so the 250ms sleep IS the debounce and a stale apply
    // can't land (the cancelled run never reaches the assignment).
    LaunchedEffect(typed) {
        error = null
        quote = null
        val parsed = typed.toIntOrNull()
        if (parsed != null && parsed > 0 && typed.length in 3..9) {
            checking = true
            delay(250)
            val q = runCatching { session.quoteUin(parsed) }.getOrNull()
            if (q != null && q.uin.toString() == typed) quote = q
            checking = false
        } else {
            checking = false
        }
    }

    fun onChange(raw: String) {
        // Digits only, max 9; strip leading zeros (a UIN is an integer, "007"
        // is just 7) so the plate shows the number the server will actually
        // quote.
        var f = raw.filter { it.isDigit() }.take(9)
        f = f.dropWhile { it == '0' }
        typed = f
    }

    /**
     * Turn a voucher into a number.
     *
     * ⚠ Called from two places that both mean "somebody has paid": the open
     * checkout, and the sweep below that finds a payment made before the app
     * was last closed. Both go through here so the once-only guard lives in one
     * place.
     */
    fun redeem(target: Int, voucher: String, invoiceId: String) {
        if (!redeemed.value.add(invoiceId)) return
        redeeming = true
        error = null
        scope.launch {
            val r = runCatching { session.redeemUin(target, voucher) }
            redeeming = false
            checkout = null
            r.onSuccess {
                UinInvoices.forget(invoiceId)
                held = target
            }.onFailure { e ->
                val body = e.message.orEmpty()
                // ⚠ `voucher_spent` is not a failure to paint red: it means the
                // number is already in the collection, which is what the buyer
                // wanted. Anything else keeps the invoice so a retry is still
                // possible.
                if (body.contains("voucher_spent")) {
                    UinInvoices.forget(invoiceId)
                    held = target
                } else {
                    redeemed.value.remove(invoiceId)
                    error = if (body.contains("taken")) takenPaidMsg else genericMsg
                }
            }
        }
    }

    // ⚠⚠ A payment that landed while nobody was looking. This is the whole
    // reason invoices are written to disk: somebody pays, closes the app before
    // the confirmation, and comes back - without this sweep their money bought
    // a voucher sitting in a till nobody asks. Runs once per open, quietly, and
    // only ever finishes what was already paid for.
    LaunchedEffect(Unit) {
        UinInvoices.init(ctx)
        for (open in UinInvoices.all()) {
            val inv = runCatching { TillApi.invoice(open.id, open.checkoutUrl) }.getOrNull() ?: continue
            when {
                inv.status == "paid" && !inv.voucher.isNullOrBlank() ->
                    redeem(inv.uin, inv.voucher, inv.id)
                inv.status == "expired" -> UinInvoices.forget(inv.id)
            }
        }
    }

    fun runPurchase() {
        val parsed = typed.toIntOrNull() ?: return
        buying = true
        scope.launch {
            // ⚠⚠ switch = FALSE, and this line has a price on it. It was
            // flipped to true on 2026-09-01, when collections were closed and
            // the island refused switch=false outright. Collections reopened on
            // 03.09 and this client was not changed, so "take" still meant
            // "move onto it" - and moving means the number you were answering
            // as is given up. That is how the founder lost #911: he took an
            // ordinary seven-digit number and a three-digit one went back on
            // the shelf a second later. Taking a number and BECOMING it are two
            // deliberate steps again, and the Held branch below is the first.
            when (val r = session.purchaseUin(parsed, switch = false)) {
                is Session.PurchaseResult.Held -> {
                    buying = false
                    held = parsed
                }
                // The server only switches when asked, but if it ever does the
                // account really has moved and the caller has to hear about it.
                is Session.PurchaseResult.Success -> onMigrated(r.newUin)
                is Session.PurchaseResult.Taken -> {
                    buying = false
                    quote = null
                    error = takenMsg
                }
                is Session.PurchaseResult.TooMany -> {
                    buying = false
                    error = tooManyMsg
                }
                is Session.PurchaseResult.Reserved -> {
                    buying = false
                    quote = null
                    error = reservedMsg
                }
                else -> {
                    buying = false
                    error = (r as? Session.PurchaseResult.Other)?.message?.takeIf { it.isNotBlank() } ?: genericMsg
                }
            }
        }
    }

    fun moveOnto(target: Int) {
        buying = true
        scope.launch {
            when (val r = session.activateUin(target)) {
                is Session.PurchaseResult.Success -> onMigrated(r.newUin)
                else -> {
                    buying = false
                    error = (r as? Session.PurchaseResult.Other)?.message?.takeIf { it.isNotBlank() } ?: genericMsg
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        // Back bar
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.common_back),
                tint = c.accent,
                modifier = Modifier.size(26.dp).clickable(onClick = onBack),
            )
            Text(
                stringResource(R.string.uin_shop_title),
                color = c.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // Status line
            Box(Modifier.fillMaxWidth().height(20.dp), Alignment.Center) {
                StatusLine(c, typed, isValidLength, checking, displayedQuote)
            }

            // Price line. ⚠⚠ THE QUOTE WINS when there is one, and the local
            // ladder is only the preview shown before the island has answered.
            // With a resale on screen the two disagree openly: the ladder says
            // what a five-digit number costs from the ISLAND ($49.99) while the
            // seller is asking $120, and the button below already showed the
            // seller's figure. One screen, one price.
            // ⚠⚠ And NOT over a number that costs nothing. The island returns
            // a real `price_cents` for ordinary space too — it is the ladder
            // figure, not a charge — so preferring the quote unconditionally
            // put "$1.99" over a number the very next screen hands over free.
            // The web page settled this the same way months ago.
            val cents = when {
                displayedQuote?.acquire == "free" -> null
                displayedQuote?.price_cents != null -> displayedQuote.price_cents
                isValidLength -> PRICE_CENTS_BY_LENGTH[typed.length]
                else -> null
            }
            Box(Modifier.fillMaxWidth().height(48.dp), Alignment.Center) {
                if (cents != null) {
                    Text(
                        priceDisplay(cents),
                        color = if (isAvailable) c.accent else c.textPrimary,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Plate / number input
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.bgSecondary)
                    .padding(vertical = 28.dp, horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicTextField(
                    value = typed,
                    onValueChange = ::onChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = c.textPrimary,
                        fontSize = 46.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    ),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth(), Alignment.Center) {
                            if (typed.isEmpty()) {
                                Text(
                                    "—",
                                    color = c.textSecondary.copy(alpha = 0.32f),
                                    fontSize = 46.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (typed.isEmpty()) stringResource(R.string.uin_shop_plate_hint)
                    else pluralStringResource(R.plurals.uin_digits, typed.length, typed.length),
                    color = c.textSecondary,
                    fontSize = 12.sp,
                )
            }

            // FROM PEOPLE. Only drawn when there is something in it: an empty
            // heading over nothing reads as broken, and a market with no
            // sellers yet is simply a market with no sellers yet.
            if (listings.isNotEmpty()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.uin_shop_from_people).uppercase(),
                            color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        )
                        Text(
                            stringResource(R.string.uin_shop_show_others),
                            color = if (loadingListings) c.textSecondary else c.accent,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable(enabled = !loadingListings) {
                                scope.launch { loadListings() }
                            },
                        )
                    }
                    listings.forEach { l ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(c.bgSecondary)
                                .clickable(enabled = !l.held) { typed = l.uin.toString() }
                                .padding(vertical = 13.dp, horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(l.uin.toString(), color = c.textPrimary, fontSize = 17.sp,
                                     fontWeight = FontWeight.Medium)
                                Text(
                                    stringResource(R.string.uin_shop_status_resale, l.seller_uin.toString()),
                                    color = c.textSecondary, fontSize = 11.sp,
                                )
                            }
                            // A listing somebody is paying for says so instead of
                            // vanishing: a row that disappeared would read as
                            // "the number is gone", and it is not.
                            Text(
                                if (l.held) stringResource(R.string.uin_shop_being_paid) else l.price_display,
                                color = if (l.held) c.textSecondary else c.accent,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // The collection is a screen away, and this is where somebody who
            // just took a number goes looking for it.
            Text(
                stringResource(R.string.uin_shop_my_numbers),
                color = c.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenMyUins).padding(vertical = 4.dp),
                textAlign = TextAlign.Center,
            )

            // Info block
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                InfoRow(c, R.string.uin_shop_info_what_title, R.string.uin_shop_info_what_body)
                InfoRow(c, R.string.uin_shop_info_migrate_title, R.string.uin_shop_info_migrate_body)
                session.uin?.let { own ->
                    Text(
                        stringResource(R.string.uin_shop_info_current, own.toString()),
                        color = c.textSecondary.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        // Bottom CTA
        Box(Modifier.fillMaxWidth().background(c.bgPrimary).padding(horizontal = 22.dp, vertical = 14.dp)) {
            when {
                buying -> CapsuleLabel(c) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.uin_shop_buy_processing), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                redeeming -> CapsuleLabel(c) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.uin_shop_buy_processing), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                // Somebody who closed the app mid-payment. Offered before the
                // buy, because the number reads as unavailable to them — our
                // own invoice is what is holding it.
                resumable != null -> Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.accent)
                        .clickable { checkout = typed.toIntOrNull() }.padding(vertical = 17.dp),
                    Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.uin_shop_cta_resume),
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                forSale -> Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.accent)
                        .clickable { checkout = typed.toIntOrNull() }.padding(vertical = 17.dp),
                    Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.uin_shop_buy_short),
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                canBuy && displayedQuote?.price_cents != null -> Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.accent)
                        .clickable { showConfirm = true }.padding(vertical = 17.dp),
                    Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.uin_shop_buy_short),
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                error != null -> Text(
                    error!!,
                    color = c.statusBusy,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 17.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    checkout?.let { target ->
        UinCheckoutSheet(
            uin = target,
            priceDisplay = displayedQuote?.price_cents?.let { priceDisplay(it) } ?: "",
            // Straight from the quote for THIS number on THIS island, never a
            // default. See the note on the parameter.
            // ⚠⚠ The invoice's OWN till wins when there is one: an invoice id
            // exists only at the till that issued it, so resuming against any
            // other is told "no such invoice" about a payment really in flight.
            checkoutUrl = UinInvoices.forUin(target)?.checkoutUrl ?: displayedQuote?.checkout_url,
            resumeId = UinInvoices.forUin(target)?.id,
            onPaid = { voucher, invoiceId -> redeem(target, voucher, invoiceId) },
            onDismiss = { checkout = null },
        )
    }

    if (showConfirm) {
        val cents = displayedQuote?.price_cents
        RcqAskSheet(
            onDismiss = { showConfirm = false },
            title = if (cents != null) stringResource(R.string.uin_shop_confirm_title_priced, typed, priceDisplay(cents))
            else stringResource(R.string.uin_shop_confirm_title),
            body = stringResource(R.string.uin_shop_confirm_body),
            actions = listOf(
                SheetAction(stringResource(R.string.uin_shop_confirm_cta)) { showConfirm = false; runPurchase() },
            ),
        )
    }

    // The number is in the collection. Moving onto it is the second, separate
    // step; "Later" leaves the account exactly as it was and the number safe —
    // so it IS the way out of this sheet, and names the cancel row.
    held?.let { target ->
        RcqAskSheet(
            onDismiss = { held = null; typed = "" },
            title = stringResource(R.string.uin_shop_held_title, target.toString()),
            body = stringResource(R.string.uin_shop_held_body, (session.uin ?: 0).toString()),
            actions = listOf(
                SheetAction(stringResource(R.string.uin_shop_held_now)) { held = null; moveOnto(target) },
            ),
            cancelLabel = stringResource(R.string.uin_shop_held_later),
        )
    }
}

@Composable
private fun StatusLine(c: RcqColors, typed: String, isValidLength: Boolean, checking: Boolean, quote: RcqApi.QuoteResponse?) {
    when {
        typed.isEmpty() -> StatusText(stringResource(R.string.uin_shop_status_idle), c.textSecondary)
        !isValidLength -> StatusText(stringResource(R.string.uin_shop_hint_too_short), c.textSecondary)
        checking && quote == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CircularProgressIndicator(color = c.textSecondary, strokeWidth = 2.dp, modifier = Modifier.size(13.dp))
            StatusText(stringResource(R.string.uin_shop_status_checking), c.textSecondary)
        }
        // ⚠ A number that is FOR SALE reads as unavailable in `available` —
        // the island keeps that field false for scarce stock so older clients
        // do not offer it for free. Saying "Unavailable" in red above a live
        // "Buy for $199" button is the screen contradicting itself, so the
        // status keys off `acquire` the same way the button does.
        // The same trap, one step further: a number a PERSON is selling also
        // reads as unavailable, and saying so above a live buy button is the
        // screen contradicting itself twice over. Naming the seller is more
        // useful than either word.
        quote != null && quote.acquire == "resale" && (quote.price_cents ?: 0) > 0 ->
            StatusText(
                stringResource(R.string.uin_shop_status_resale, (quote.seller_uin ?: 0).toString()),
                c.accent,
            )
        quote != null && quote.acquire == "purchase" && (quote.price_cents ?: 0) > 0 ->
            StatusText(stringResource(R.string.uin_shop_status_for_sale), c.accent)
        quote != null -> if (quote.available) {
            StatusText(stringResource(R.string.uin_shop_status_available), c.accent)
        } else {
            StatusText(reasonText(quote.reason), c.statusBusy)
        }
    }
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun reasonText(reason: String?): String = when (reason) {
    "taken" -> stringResource(R.string.uin_shop_status_taken)
    "too_short" -> stringResource(R.string.uin_shop_status_too_short)
    "too_long" -> stringResource(R.string.uin_shop_status_too_long)
    "self" -> stringResource(R.string.uin_shop_status_self)
    else -> stringResource(R.string.uin_shop_status_unavailable)
}

@Composable
private fun InfoRow(c: RcqColors, titleRes: Int, bodyRes: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(titleRes), color = c.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(bodyRes), color = c.textSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun CapsuleLabel(c: RcqColors, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.accent).padding(vertical = 17.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

private val PRICE_CENTS_BY_LENGTH = mapOf(9 to 99, 8 to 199, 7 to 499, 6 to 1499, 5 to 4999, 4 to 19900, 3 to 99900)

private fun priceDisplay(cents: Int): String {
    val dollars = cents / 100.0
    return if (cents % 100 == 0) String.format(Locale.US, "$%.0f", dollars)
    else String.format(Locale.US, "$%.2f", dollars)
}
