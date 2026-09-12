package app.rcq.android.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Invoices this device has opened for ENTRY to an island (residency), so a
 * payment is never stranded: the twin of [UinInvoices], for the same reason
 * that file exists. Somebody taps buy, sends the transfer, closes the app
 * before it confirms; the invoice id is the only thing that can find the
 * voucher afterwards, so it is written down BEFORE anything else can fail.
 *
 * ⚠ Its OWN file, not a row in [UinInvoices]. The number shop sweeps that
 * list through the number endpoint and redeems what it finds paid as a
 * number; an entry invoice there would come back "no such invoice" and be
 * dropped. The code sheets and the residency row sweep this one, for the
 * island they are on.
 *
 * Not per account, like the number list: a person buying entry usually has
 * no account here yet. The panic wipe takes it with everything else.
 */
object EntryInvoices {

    private const val PREFS = "rcq_entry_invoices"
    private const val KEY = "open.v1"
    private const val MAX = 20

    private val gson = Gson()
    private var prefs: SharedPreferences? = null

    /** [tillUrl] is WHICH till issued it: an invoice id only exists at the
     *  till that made it. Always set, because entry is only ever sold at a
     *  till the island named. */
    data class Open(
        val id: String = "",
        val host: String = "",
        val chain: String = "",
        val at: Long = 0,
        val tillUrl: String = "",
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

    fun remember(id: String, host: String, chain: String, tillUrl: String) {
        val next = (listOf(Open(id, host, chain, System.currentTimeMillis(), tillUrl)) +
            all().filter { it.id != id }).take(MAX)
        prefs?.edit()?.putString(KEY, gson.toJson(next))?.apply()
    }

    fun forget(id: String) {
        prefs?.edit()?.putString(KEY, gson.toJson(all().filter { it.id != id }))?.apply()
    }

    /** The open invoice for this island, if this device has one: what turns a
     *  second tap on "buy" into "finish paying". */
    fun forHost(host: String): Open? = all().firstOrNull { it.host.equals(host, ignoreCase = true) }

    fun wipeAll(ctx: Context) {
        init(ctx)
        prefs?.edit()?.clear()?.apply()
    }
}
