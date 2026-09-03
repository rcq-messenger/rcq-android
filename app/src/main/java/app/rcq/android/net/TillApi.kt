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

    private const val BASE = "https://console-api.rcq.app"
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

    suspend fun prices(): Prices = withContext(Dispatchers.IO) {
        call(Request.Builder().url("$BASE/v1/uin/prices").get().build())
    }

    /**
     * Reserve the number and quote an exact amount for it.
     *
     * ⚠ The amount is exact to the last digit because that is what tells this
     * payment from every other one: every open invoice gets its own tail. A
     * rounded amount is a payment nobody can attribute.
     */
    suspend fun createInvoice(uin: Int, chain: String): Invoice = withContext(Dispatchers.IO) {
        val body = gson.toJson(mapOf("uin" to uin, "chain" to chain)).toRequestBody(JSON)
        call(Request.Builder().url("$BASE/v1/uin/invoice").post(body).build())
    }

    suspend fun invoice(id: String): Invoice = withContext(Dispatchers.IO) {
        call(Request.Builder().url("$BASE/v1/uin/invoice/$id").get().build())
    }
}
