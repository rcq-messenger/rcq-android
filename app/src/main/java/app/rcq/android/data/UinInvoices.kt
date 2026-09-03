package app.rcq.android.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Invoices this device has opened for a number, so a payment is never stranded.
 *
 * ⚠⚠ THIS IS THE WHOLE REASON THE FILE EXISTS. Somebody taps buy, sends the
 * transfer, and closes the app before it confirms. Without a record here their
 * money bought a voucher sitting in a till nobody will ever ask for again: the
 * invoice id is the only thing that can find it, the till has no idea who they
 * are, and the island has never heard of the payment. So the id is written down
 * BEFORE anything else can fail, and the shop sweeps this list on every open.
 *
 * ⚠ Not per account, and deliberately: the payment belongs to this device and
 * this browser tab of a life, not to whichever number is active at the moment.
 * Somebody who buys a number, moves onto it (which changes the active account)
 * and only then sees the confirmation must still be able to redeem.
 *
 * Nothing secret to the island or the till lives here. It is secret to whoever
 * holds this phone, which is why the panic wipe takes it with everything else.
 */
object UinInvoices {

    private const val PREFS = "rcq_uin_invoices"
    private const val KEY = "open.v1"
    private const val MAX = 20

    private val gson = Gson()
    private var prefs: SharedPreferences? = null

    /** ⚠⚠ [checkoutUrl] is WHICH till issued it. An invoice id only exists at
     *  the till that made it, so a sweep that asks a different one gets a
     *  confident "no such invoice" for a payment really in flight — and the
     *  voucher somebody paid for is never collected. Null on rows written
     *  before islands could name their own till; those were all ours. */
    data class Open(
        val id: String = "",
        val uin: Int = 0,
        val chain: String = "",
        val at: Long = 0,
        val checkoutUrl: String? = null,
    )

    fun init(ctx: Context) {
        if (prefs == null) {
            prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun all(): List<Open> {
        val raw = prefs?.getString(KEY, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<Open>>(raw, object : TypeToken<List<Open>>() {}.type)
        }.getOrNull().orEmpty()
    }

    /** Newest first, capped. Called the moment an invoice exists, before the
     *  address is even shown. */
    fun remember(id: String, uin: Int, chain: String, checkoutUrl: String? = null) {
        val next = (listOf(Open(id, uin, chain, System.currentTimeMillis(), checkoutUrl)) +
            all().filter { it.id != id }).take(MAX)
        prefs?.edit()?.putString(KEY, gson.toJson(next))?.apply()
    }

    fun forget(id: String) {
        prefs?.edit()?.putString(KEY, gson.toJson(all().filter { it.id != id }))?.apply()
    }

    /** The open invoice for this number, if this device has one. What turns
     *  "that number is unavailable" (it is - our own invoice is holding it)
     *  into "finish paying". */
    fun forUin(uin: Int): Open? = all().firstOrNull { it.uin == uin }

    fun wipeAll(ctx: Context) {
        init(ctx)
        prefs?.edit()?.clear()?.apply()
    }
}
