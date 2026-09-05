package app.rcq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.net.RcqApi
import kotlinx.coroutines.launch

/**
 * The numbers this account holds, and which one it answers as.
 *
 * The shop and this screen are deliberately two places. Taking a number and
 * BECOMING it used to be the same tap, which put "everyone who knows me loses
 * me" one button away from browsing; the server split the two (POST
 * /uin/purchase{switch:false} then POST /uin/activate) and this is the second
 * half. Switching here is reversible: the number in use goes into the
 * collection rather than back into the pool, so it can always be switched back.
 *
 * Reachable regardless of the shop toggle — an operator who closes their shop
 * must not strand people on the wrong number, and a self-hoster can hand a
 * member a second number by hand (POST /admin/uin/grant).
 */
/** The most an island will take for one number, mirroring `price_cents`'s own
 *  bound on the server. One number, so the field and any message about it can
 *  never drift apart. */
private const val MAX_PRICE_USD = 100_000.0

/** A TRON address, by shape. ⚠ Not a checksum: this catches the mistakes people
 *  actually make — an Ethereum address pasted from the wrong wallet (0x…), a
 *  TON one (UQ…/EQ…), a truncated paste — and those are the ones that cost a
 *  seller their number and their payment at once. The island only checks the
 *  string is non-empty, and nothing downstream can undo a payment sent
 *  somewhere the seller cannot reach. */
/** Dollars, the way money is written. Local to this file: the shop's own
 *  formatter is private to its own, and one number formatted two ways is how a
 *  screen ends up disagreeing with itself. */
private fun usdText(dollars: Double): String =
    if (dollars % 1.0 == 0.0) "$" + dollars.toLong() else String.format("$%.2f", dollars)

private val TRON_ADDRESS = Regex("^T[1-9A-HJ-NP-Za-km-z]{33}$")

@Composable
fun MyUinsScreen(session: Session, onBack: () -> Unit, onActivated: (Int) -> Unit) {
    val c = RcqTheme.colors
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var data by remember { mutableStateOf<RcqApi.MyUinsResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Int?>(null) }
    var activating by remember { mutableStateOf<Int?>(null) }
    var confirmRelease by remember { mutableStateOf<Int?>(null) }
    var releasing by remember { mutableStateOf<Int?>(null) }
    /// The number whose sale sheet is open, and what is typed into it.
    var sellTarget by remember { mutableStateOf<Int?>(null) }
    var sellPrice by remember { mutableStateOf("") }
    var sellWallet by remember { mutableStateOf("") }
    var selling by remember { mutableStateOf(false) }
    val genericMsg = stringResource(R.string.uin_shop_error_generic)

    suspend fun reload() {
        loading = true
        error = null
        runCatching { session.myUins() }
            .onSuccess { data = it }
            .onFailure { error = it.message?.takeIf { m -> m.isNotBlank() } ?: genericMsg }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    fun release(uin: Int) {
        releasing = uin
        scope.launch {
            runCatching { session.releaseUin(uin) }
                .onFailure { error = it.message?.takeIf { m -> m.isNotBlank() } ?: genericMsg }
            releasing = null
            // Reload rather than dropping the row locally: the server is the
            // one that knows whether the release actually happened.
            reload()
        }
    }

    fun unlist(uin: Int) {
        scope.launch {
            // ⚠⚠ The message has to survive the reload. `reload()` clears
            // `error` as its second statement, and it used to be called right
            // after the failure set one — same coroutine, no suspension in
            // between — so every refusal on this path was wiped before a frame
            // could draw it and the sheet closed as though it had worked. The
            // reason is kept and put back.
            var failure: String? = null
            runCatching { session.unlistUin(uin) }
                .onFailure { failure = it.message?.takeIf { m -> m.isNotBlank() } ?: genericMsg }
            reload()
            failure?.let { error = it }
        }
    }

    fun putOnSale() {
        val target = sellTarget ?: return
        // ⚠ Cents, rounded once here rather than left to float arithmetic
        // downstream: this figure is what somebody is charged.
        val cents = (sellPrice.toDoubleOrNull() ?: 0.0).let { Math.round(it * 100).toInt() }
        val wallet = sellWallet.trim()
        if (cents <= 0 || wallet.isEmpty()) return
        selling = true
        scope.launch {
            var failure: String? = null
            runCatching { session.listUin(target, cents, wallet) }
                .onFailure { failure = it.message?.takeIf { m -> m.isNotBlank() } ?: genericMsg }
            selling = false
            // ⚠ The sheet stays open on a refusal. Closing it discarded what
            // the person typed and said nothing, so the only way to learn the
            // listing had not happened was to notice the row had not changed.
            if (failure == null) sellTarget = null
            reload()
            failure?.let { error = it }
        }
    }

    fun activate(uin: Int) {
        activating = uin
        scope.launch {
            when (val r = session.activateUin(uin)) {
                is Session.PurchaseResult.Success -> onActivated(r.newUin)
                is Session.PurchaseResult.NotOwned -> {
                    activating = null
                    reload()
                }
                is Session.PurchaseResult.Other -> {
                    activating = null
                    error = r.message?.takeIf { it.isNotBlank() } ?: genericMsg
                }
                // /activate either switches or fails; Held/Taken cannot happen.
                else -> activating = null
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.common_back),
                tint = c.accent,
                modifier = Modifier.size(26.dp).clickable(onClick = onBack),
            )
            Text(
                stringResource(R.string.my_uins_title),
                color = c.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val active = data?.active ?: session.uin
            if (active != null && active > 0) {
                SectionCaption(c, stringResource(R.string.my_uins_active_caption))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.bgSecondary)
                        .padding(vertical = 18.dp, horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(active.toString(), color = c.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.my_uins_active_sub), color = c.textSecondary, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(6.dp))
            // "3 of 10" — the cap exists, so it should be visible before you
            // hit it rather than only in the refusal at the eleventh number.
            val owned = data?.owned.orEmpty()
            val cap = data?.max_owned ?: 10
            // A cap of zero means the island closed collections (2026-09-01:
            // one number per account, everywhere). Then there is no shelf to
            // draw and no count to draw it with — "0 of 0" over an empty list
            // reads as a bug, and the honest version is one sentence.
            val closed = cap <= 0 && owned.isEmpty()
            if (closed) {
                Text(
                    stringResource(R.string.my_uins_closed),
                    color = c.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    textAlign = TextAlign.Center,
                )
            } else {
            SectionCaption(
                c,
                stringResource(R.string.my_uins_held_caption) + "  " +
                    stringResource(R.string.my_uins_held_count, owned.size, cap),
            )
            when {
                loading && data == null -> Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), Alignment.Center) {
                    CircularProgressIndicator(color = c.textSecondary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
                owned.isEmpty() -> Text(
                    stringResource(R.string.my_uins_empty),
                    color = c.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    textAlign = TextAlign.Center,
                )
                else -> owned.forEach { item ->
                    HeldRow(
                        c, item,
                        busy = activating == item.uin || releasing == item.uin,
                        enabled = activating == null && releasing == null,
                        listing = data?.listed?.firstOrNull { it.uin == item.uin },
                        onTap = { confirm = item.uin },
                        onRelease = { confirmRelease = item.uin },
                        onSell = { sellTarget = item.uin; sellPrice = ""; sellWallet = "" },
                        onUnlist = { unlist(item.uin) },
                    )
                }
            }
            }

            error?.let {
                Text(
                    it,
                    color = c.statusBusy,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(8.dp))
            if (!closed) Text(
                stringResource(R.string.my_uins_footer),
                color = c.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    confirm?.let { target ->
        RcqAskSheet(
            onDismiss = { confirm = null },
            title = stringResource(R.string.my_uins_confirm_title, target.toString()),
            body = stringResource(R.string.my_uins_confirm_body, (data?.active ?: session.uin ?: 0).toString()),
            actions = listOf(
                SheetAction(
                    label = stringResource(R.string.my_uins_confirm_cta),
                    onClick = { confirm = null; activate(target) },
                ),
            ),
        )
    }

    // Putting your own number on sale. Two fields and one warning, and the
    // warning is the important part: the buyer pays that address directly and
    // nothing here can undo a payment sent to the wrong one.
    sellTarget?.let { target ->
        RcqSheet(onDismiss = { if (!selling) sellTarget = null },
                 title = stringResource(R.string.my_uins_sell_title, target.toString())) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.my_uins_sell_price), color = c.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                RcqField(
                    value = sellPrice,
                    // ⚠ Capped at what the island will actually accept
                    // ($100,000, `price_cents` le=100_000_00). Without it a
                    // mistyped or mis-focused figure sails through the form and
                    // comes back as a bare 422 nobody can act on — and a stray
                    // paste into the wrong field is the commonest way there.
                    // ⚠⚠ NO COMMA. It is ambiguous about money and the
                    // ambiguity is expensive: "1,500" is fifteen hundred to one
                    // person and one and a half to another, and this field
                    // decides what a stranger pays. Guessing a locale would put
                    // a $1,500 number on sale for $1.50, so it is not accepted
                    // at all — digits and at most one dot, which reads the same
                    // everywhere.
                    onValueChange = { v ->
                        var seenDot = false
                        val cleaned = buildString {
                            for (ch in v) {
                                if (ch.isDigit()) append(ch)
                                else if (ch == '.' && !seenDot) { seenDot = true; append(ch) }
                            }
                        }
                        val asNumber = cleaned.toDoubleOrNull()
                        if (cleaned.isEmpty() || (asNumber != null && asNumber <= MAX_PRICE_USD)) {
                            sellPrice = cleaned
                        }
                    },
                    placeholder = "250",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.my_uins_sell_wallet), color = c.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                RcqField(
                    value = sellWallet,
                    onValueChange = { sellWallet = it.trim() },
                    placeholder = "T...",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.my_uins_sell_note),
                    color = c.textSecondary, fontSize = 11.sp, lineHeight = 15.sp,
                )
                Spacer(Modifier.height(18.dp))
                // ⚠ The parsed figure, read back before it is committed. The
                // field takes text and the sale cannot be undone, so the last
                // thing a seller sees is the number a stranger will pay.
                val parsed = sellPrice.toDoubleOrNull()
                if (parsed != null && parsed > 0) {
                    Text(
                        stringResource(R.string.my_uins_sell_echo, usdText(parsed)),
                        color = c.textPrimary, fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (sellWallet.isNotEmpty() && !TRON_ADDRESS.matches(sellWallet)) {
                    Text(
                        stringResource(R.string.my_uins_sell_bad_address),
                        color = c.statusBusy, fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                CapsuleButton(
                    label = stringResource(R.string.my_uins_sell_cta),
                    enabled = !selling && (parsed ?: 0.0) > 0 && TRON_ADDRESS.matches(sellWallet),
                    modifier = Modifier.fillMaxWidth(),
                ) { putOnSale() }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    confirmRelease?.let { target ->
        RcqAskSheet(
            onDismiss = { confirmRelease = null },
            title = stringResource(R.string.my_uins_release_title, target.toString()),
            // The warning lives here rather than on the row: the number goes
            // back into the pool and somebody else can take it, so this is not
            // undoable and should be read once, not glanced at.
            body = stringResource(R.string.my_uins_release_body),
            actions = listOf(
                SheetAction(
                    label = stringResource(R.string.my_uins_release_cta),
                    destructive = true,
                    onClick = { confirmRelease = null; release(target) },
                ),
            ),
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HeldRow(
    c: RcqColors,
    item: RcqApi.OwnedUinItem,
    busy: Boolean,
    enabled: Boolean,
    /** What this number is on sale for, when it is. Null means it is not. */
    listing: RcqApi.UinListingItem?,
    onTap: () -> Unit,
    onRelease: () -> Unit,
    onSell: () -> Unit,
    onUnlist: () -> Unit,
) {
    // ⚠ FlowRow, not Row: three Russian labels and a number do not fit one
    // line on a narrow phone, and the number lost — it broke into a column of
    // single digits (#898, screenshot). The actions drop to their own line
    // when the row is tight and stay beside the number when it is not, which
    // is what the web page does.
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bgSecondary)
            .clickable(enabled = enabled, onClick = onTap)
            .padding(vertical = 15.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(
            Modifier.weight(1f).widthIn(min = 96.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                item.uin.toString(),
                color = c.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, softWrap = false,
            )
            Text(
                if (listing != null)
                    stringResource(R.string.my_uins_on_sale, listing.price_display)
                else pluralStringResource(R.plurals.uin_digits, item.length, item.length),
                color = if (listing != null) c.accent else c.textSecondary,
                fontSize = 11.sp,
            )
        }
        if (busy) {
            CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            // Release before Use, quieter than it: the collection fills up with
            // numbers nobody picked (every switch parks the previous one here),
            // and there was no way to get rid of one. Deliberately a plain
            // secondary label, not a red destructive button — this is tidying
            // up, and the confirm dialog carries the warning.
            // Selling is a third thing you can do with a number you hold. A
            // number already on the market offers the way back off it instead,
            // because putting it up twice is not a thing you can want.
            Text(
                stringResource(
                    if (listing != null) R.string.my_uins_unlist else R.string.my_uins_sell
                ),
                color = c.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier
                    // Nothing is sold inside the Play build: Use/Release stay, Sell does not.
                    .clickable(enabled = enabled && (listing != null || !app.rcq.android.BuildConfig.PLAY_STORE), onClick = if (listing != null) onUnlist else onSell)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
            Spacer(Modifier.width(2.dp))
            Text(
                stringResource(R.string.my_uins_release),
                color = c.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.clickable(enabled = enabled, onClick = onRelease).padding(horizontal = 6.dp, vertical = 8.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.my_uins_use), color = c.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SectionCaption(c: RcqColors, text: String) {
    Text(text.uppercase(), color = c.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
}
