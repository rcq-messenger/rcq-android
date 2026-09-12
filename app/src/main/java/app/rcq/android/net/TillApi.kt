package app.rcq.android.net

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The till: the only part of RCQ that knows what money is, and it is not an
 * island.
 *
 * The island holds the numbers and has no wallet, no price list and no way to
 * be paid. This service watches the operator's own wallets through public block
 * explorers and, when a transfer lands, signs a document saying "number N was
 * paid for". The buyer carries that document from one to the other, and neither
 * half learns the other's: the till never sees an account or a token, the island
 * never sees a chain, an amount or an address.
 *
 * ⚠⚠ EVERY CALL GOES THROUGH THE APP'S TRANSPORT. This is a foreign host, the
 * same category as another island, and the rule there is already written down:
 * a client built before the transport came up keeps going direct, which both
 * fails on a censored network AND leaks the host outside the tunnel. Somebody
 * who cannot reach the flagship cannot reach the till either, and the moment
 * they are buying is the worst moment to emit a plaintext connection to a
 * payments host.
 *
 * ⚠ No token is ever sent here, and none is accepted. The invoice id IS the
 * credential — whoever holds it can read the voucher — so it is kept on this
 * device (see [app.rcq.android.data.UinInvoices]) and nowhere else.
 */
object TillApi {

    /** The checkout compiled in, used only when an island does not name its own.
     *
     *  ⚠⚠ It serves ONE island — ours. Paying it for a number on somebody
     *  else's island puts real money where the number is not, and there is no
     *  way back. So every call below takes the address from the island's quote
     *  (`checkout_url`) when there is one, and this is the fallback for islands
     *  too old to send the field, which can only be ours. */
    private const val BUILT_IN = "https://console-api.rcq.app"

    private fun base(checkoutUrl: String?): String =
        checkoutUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: BUILT_IN
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()

    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(if (SingBoxTransport.localProxyMode()) 30 else 10, TimeUnit.SECONDS)
        .readTimeout(if (SingBoxTransport.localProxyMode()) 30 else 15, TimeUnit.SECONDS)
        .callTimeout(if (SingBoxTransport.localProxyMode()) 90 else 30, TimeUnit.SECONDS)
        .build()

    @Volatile private var proxied: OkHttpClient? = null

    private fun http(): OkHttpClient {
        val p = SingBoxTransport.proxy() ?: return base
        return proxied ?: base.newBuilder().proxy(p).build().also { proxied = it }
    }

    /** What a number costs and what we can be paid in. */
    data class Chain(val id: String = "", val label: String = "", val confirmations: Int = 1)
    data class Prices(
        val prices_cents: Map<String, Int> = emptyMap(),
        val chains: List<Chain> = emptyList(),
    )

    /**
     * An open or settled invoice.
     *
     * `voucher` arrives only once the transfer has confirmed, and the till keeps
     * handing it back on every poll afterwards. ⚠ That is deliberate and the
     * opposite of the relay shop's one-read account key: a voucher can only be
     * spent ONCE anyway, on the island, by nonce — while a single-read voucher
     * would leave somebody who lost the reply paid up and holding nothing.
     */
    data class Invoice(
        val id: String = "",
        val uin: Int = 0,
        val chain: String = "",
        val chain_label: String = "",
        val address: String = "",
        val amount: String = "",
        val usd: Double = 0.0,
        val confirmations: Int = 1,
        val expires_at: Long = 0,
        val status: String = "pending",
        val paid_at: Long? = null,
        val voucher: String? = null,
    )

    /** A refusal the person can be told about, by the till's own word. */
    class TillException(val code: String) : IOException(code)

    /** What entry to an island costs and which chains its till takes for it.
     *  `price_cents == 0` is "not on sale": no island answering the till, or
     *  no price set by its operator. */
    data class EntryQuote(
        val host: String = "",
        val price_cents: Int = 0,
        val chains: List<Chain> = emptyList(),
    )

    /** An invoice for ENTRY (residency) rather than a number: [Invoice] minus
     *  the number plus the island it is for. The voucher it ends in is
     *  redeemed at registration or, for an account already here, at
     *  `POST /residency/redeem`. */
    data class EntryInvoice(
        val id: String = "",
        val host: String = "",
        val chain: String = "",
        val chain_label: String = "",
        val address: String = "",
        val amount: String = "",
        val usd: Double = 0.0,
        val confirmations: Int = 1,
        val expires_at: Long = 0,
        val status: String = "pending",
        val paid_at: Long? = null,
        val voucher: String? = null,
    )

    /** The till for ENTRY, and only the one the island named. ⚠⚠ NO FALLBACK,
     *  unlike [base]: numbers needed [BUILT_IN] for islands too old to name a
     *  till, and it took a header (`X-RCQ-Checkout`) to keep that from sending
     *  a self-hoster's customer to pay us. Entry was born after islands could
     *  name their till, so an island that names none simply sells nothing in
     *  the app, and a caller with an empty address gets a refusal here rather
     *  than an invoice from the wrong till. */
    private fun entryBase(tillUrl: String): String {
        val named = tillUrl.trim().trimEnd('/')
        if (!named.startsWith("https://", ignoreCase = true)) throw TillException("no_till")
        return named
    }

    private inline fun <reified T> call(req: Request): T {
        http().newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val code = runCatching {
                    gson.fromJson(body, Map::class.java)["error"]?.toString()
                }.getOrNull() ?: "http_${r.code}"
                throw TillException(code)
            }
            return gson.fromJson(body, T::class.java)
        }
    }

    suspend fun prices(checkoutUrl: String? = null): Prices = withContext(Dispatchers.IO) {
        call(Request.Builder().url("${base(checkoutUrl)}/v1/uin/prices").get().build())
    }

    /**
     * Reserve the number and quote an exact amount for it.
     *
     * ⚠ The amount is exact to the last digit because that is what tells this
     * payment from every other one: every open invoice gets its own tail. A
     * rounded amount is a payment nobody can attribute.
     */
    suspend fun createInvoice(uin: Int, chain: String, checkoutUrl: String? = null): Invoice =
        withContext(Dispatchers.IO) {
            val body = gson.toJson(mapOf("uin" to uin, "chain" to chain)).toRequestBody(JSON)
            call(Request.Builder().url("${base(checkoutUrl)}/v1/uin/invoice").post(body).build())
        }

    /** ⚠ Takes the same address the invoice was created against. An invoice id
     *  only exists at the till that issued it, so asking a different one
     *  answers a confident "no such invoice" for a payment really in flight. */
    suspend fun invoice(id: String, checkoutUrl: String? = null): Invoice =
        withContext(Dispatchers.IO) {
            call(Request.Builder().url("${base(checkoutUrl)}/v1/uin/invoice/$id").get().build())
        }

    // ── entry (residency), at the island's OWN till and nowhere else ──

    /** The price the ISLAND publishes and the chains its operator takes; the
     *  till asks the island, signed, and keeps the answer a minute. */
    suspend fun entryQuote(host: String, tillUrl: String): EntryQuote = withContext(Dispatchers.IO) {
        val b = entryBase(tillUrl)
        call(Request.Builder().url("$b/v1/entry/quote?host=${java.net.URLEncoder.encode(host, "UTF-8")}").get().build())
    }

    /** Write an invoice for entry to [host]. The address on it is the island
     *  operator's wallet, handed to the till per invoice by the island itself. */
    suspend fun createEntryInvoice(host: String, chain: String, tillUrl: String): EntryInvoice =
        withContext(Dispatchers.IO) {
            val b = entryBase(tillUrl)
            val body = gson.toJson(mapOf("host" to host, "chain" to chain)).toRequestBody(JSON)
            call(Request.Builder().url("$b/v1/entry/invoice").post(body).build())
        }

    suspend fun entryInvoice(id: String, tillUrl: String): EntryInvoice = withContext(Dispatchers.IO) {
        val b = entryBase(tillUrl)
        call(Request.Builder().url("$b/v1/entry/invoice/$id").get().build())
    }
}
