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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.net.RcqApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * UIN marketplace, iOS UINShopView parity. The user types a 3-9 digit number,
 * the server (/uin/quote) confirms availability, the price flips to accent when
 * free, one tap on the bottom capsule buys it (mock IAP receipt) and migrates
 * the account onto it. Unlike iOS, the Android migration PRESERVES local chat
 * history (it's peer-keyed), so the copy says so.
 */
@Composable
fun UinShopScreen(session: Session, onBack: () -> Unit, onMigrated: (Int) -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()

    var typed by remember { mutableStateOf("") }
    var quote by remember { mutableStateOf<RcqApi.QuoteResponse?>(null) }
    var checking by remember { mutableStateOf(false) }
    var buying by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Resolved here (composable scope) so the purchase callback can use them.
    val takenMsg = stringResource(R.string.uin_shop_error_taken)
    val genericMsg = stringResource(R.string.uin_shop_error_generic)

    val isValidLength = typed.length in 3..9
    // The quote is only meaningful while it still matches what's typed.
    val displayedQuote = quote?.takeIf { it.uin.toString() == typed }
    val isAvailable = displayedQuote?.available == true
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

    fun runPurchase() {
        val parsed = typed.toIntOrNull() ?: return
        buying = true
        scope.launch {
            val receipt = "mock-iap-${System.currentTimeMillis()}"
            when (val r = session.purchaseUin(parsed, receipt)) {
                is Session.PurchaseResult.Success -> onMigrated(r.newUin)
                is Session.PurchaseResult.Taken -> {
                    buying = false
                    quote = null
                    error = takenMsg
                }
                is Session.PurchaseResult.Other -> {
                    buying = false
                    error = r.message?.takeIf { it.isNotBlank() } ?: genericMsg
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

            // Price line (client-side preview by length; the quote is authority
            // for availability only).
            val cents = if (isValidLength) PRICE_CENTS_BY_LENGTH[typed.length] else null
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
                    else stringResource(R.string.uin_shop_plate_digits, typed.length),
                    color = c.textSecondary,
                    fontSize = 12.sp,
                )
            }

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
                canBuy && displayedQuote?.price_cents != null -> Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.accent)
                        .clickable { showConfirm = true }.padding(vertical = 17.dp),
                    Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.uin_shop_buy_cta, priceDisplay(displayedQuote.price_cents!!)),
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

    if (showConfirm) {
        val cents = displayedQuote?.price_cents
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor = c.bgSecondary,
            title = {
                Text(
                    if (cents != null) stringResource(R.string.uin_shop_confirm_title_priced, typed, priceDisplay(cents))
                    else stringResource(R.string.uin_shop_confirm_title),
                    color = c.textPrimary,
                )
            },
            text = { Text(stringResource(R.string.uin_shop_confirm_body), color = c.textSecondary) },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; runPurchase() }) {
                    Text(stringResource(R.string.uin_shop_confirm_cta), color = c.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.common_cancel), color = c.textSecondary)
                }
            },
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
