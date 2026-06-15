package app.rcq.android.net

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Closed-island network gate (masquerade) — the CLIENT side.
 *
 * A fully-private island runs the masquerade Caddyfile: every request must carry
 * `X-RCQ-Auth: <token>` or it gets a decoy. We keep a DEVICE-GLOBAL per-host
 * token map and stamp it onto EVERY outbound request to that host via an OkHttp
 * interceptor (so contact islands, multihome backup islands AND visited-group
 * islands are all covered uniformly — the gate is per-device-per-host, one token
 * per host is enough). A host with no token sends NO header, so public islands
 * are completely unaffected.
 */
object AccessTokenStore {
    private const val PREFS = "rcq_access_tokens"
    private val mem = ConcurrentHashMap<String, String>()
    @Volatile private var prefs: android.content.SharedPreferences? = null

    /** Seed the in-memory map from prefs once at app start (RcqApp.onCreate).
     *  The interceptor reads memory only, so it needs no Context. */
    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        mem.clear()
        for ((k, v) in p.all) if (v is String && v.isNotEmpty()) mem[k] = v
    }

    fun get(host: String): String? = mem[host]?.takeIf { it.isNotEmpty() }

    fun set(host: String, token: String) {
        val t = token.trim()
        if (t.isEmpty()) { clear(host); return }
        mem[host] = t
        prefs?.edit()?.putString(host, t)?.apply()
    }

    fun clear(host: String) {
        mem.remove(host)
        prefs?.edit()?.remove(host)?.apply()
    }
}

/** Adds X-RCQ-Auth to any request whose host has a stored access token, unless
 *  the caller already set the header (the redeem call sets the ENTERED token
 *  explicitly and must not be overridden). No token for the host = no header. */
object AccessTokenInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val tok = AccessTokenStore.get(req.url.host)
        return if (tok != null && req.header("X-RCQ-Auth") == null) {
            chain.proceed(req.newBuilder().header("X-RCQ-Auth", tok).build())
        } else {
            chain.proceed(req)
        }
    }
}

/** Stable per-INSTALL id sent with /gate/redeem. 32 hex chars (UUID without
 *  dashes) — matches the backend's 16-64 hex validation. Survives across
 *  account switches (device-global); a reinstall mints a new one (fine — a
 *  reinstall re-redeems). */
object DeviceId {
    fun get(ctx: Context): String {
        val p = ctx.applicationContext.getSharedPreferences("rcq_device", Context.MODE_PRIVATE)
        p.getString("id", null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "")
        p.edit().putString("id", id).apply()
        return id
    }
}

/** Result of trying to redeem an access token a user pasted for a host. */
sealed class RedeemResult {
    /** The host is gated and the token was accepted; the durable token is stored. */
    object Ok : RedeemResult()
    /** The host has no gate (a normal public island) — nothing to do. */
    object NoGate : RedeemResult()
    /** The host is gated but the token was wrong/expired (decoy / non-JSON). */
    object BadToken : RedeemResult()
    /** Network/other error — let the caller retry. */
    object Error : RedeemResult()
}

object AccessRedeemer {
    private val gson = Gson()
    private val JSON = "application/json".toMediaType()
    // A bare client: the interceptor would add a STORED token, but here we want
    // to present the ENTERED token, so we set X-RCQ-Auth explicitly (interceptor
    // then leaves it alone) and use a plain client.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Exchange a pasted access token for a durable per-device token and store it
     * for [host]. Returns [RedeemResult.NoGate] on a clean 404 (no gate router →
     * a normal public island; don't store anything), [Ok] on success, [BadToken]
     * if the gate served the decoy / a non-JSON body.
     */
    fun redeem(ctx: Context, host: String, entered: String): RedeemResult {
        val tok = entered.trim()
        if (tok.isEmpty()) return RedeemResult.NoGate
        val body = JsonObject().apply { addProperty("device_id", DeviceId.get(ctx)) }
        val req = Request.Builder()
            .url("https://$host/gate/redeem")
            .header("X-RCQ-Auth", tok)
            .post(gson.toJson(body).toRequestBody(JSON))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 -> RedeemResult.NoGate // no /gate router → not gated
                    !resp.isSuccessful -> RedeemResult.BadToken
                    else -> {
                        val text = resp.body?.string().orEmpty()
                        val out = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull()
                        val durable = out?.get("token")?.takeIf { !it.isJsonNull }?.asString
                        if (durable.isNullOrEmpty()) {
                            RedeemResult.BadToken // 200 decoy HTML / no token field
                        } else {
                            AccessTokenStore.set(host, durable)
                            RedeemResult.Ok
                        }
                    }
                }
            }
        } catch (_: Exception) {
            RedeemResult.Error
        }
    }
}
