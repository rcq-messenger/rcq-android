package app.rcq.android.push.embedded

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.security.SecureRandom

/**
 * RCQ's own UnifiedPush distributor, talking to our own push server.
 *
 * WHY THIS EXISTS. Android cannot use FCM in the target region, so background
 * wakes ride UnifiedPush — which until now meant the user had to install ntfy
 * and let it talk to the public ntfy.sh. That combination could not work here,
 * and the numbers said so: 81% of the server's wakes were being refused. Public
 * ntfy charges its rate bucket to the SUBSCRIBER, so every RCQ user behind one
 * Russian carrier NAT shared a single 60-request bucket; and with subscriber-
 * based rate limiting it answers 507 for any topic whose phone is not connected
 * at that instant — which, for the Play build of ntfy, routes through FCM and so
 * is most of the time. Paying ntfy.sh changes neither gate.
 *
 * So RCQ now ships its own distributor pointed at push.rcq.app, where we set
 * both knobs ourselves. No third-party app, no third-party quota, and one less
 * party that learns anything at all: only our own island sees the wake.
 *
 * WHAT A DISTRIBUTOR IS. UnifiedPush is a broadcast contract between an app and
 * whatever component distributes its pushes. The app broadcasts REGISTER with an
 * instance token; the distributor answers NEW_ENDPOINT with a URL the app hands
 * to its server; the distributor later broadcasts MESSAGE for each wake that
 * arrives. Implementing that contract in-process (an "embedded distributor")
 * means everything downstream — [app.rcq.android.push.RcqPushService], the
 * notification building, the call wake — stays exactly as it was: to the rest of
 * the app this is just another distributor, and the user can still switch back
 * to ntfy in Settings.
 *
 * The endpoint path is the whole secret: our server POSTs to
 * `https://push.rcq.app/<topic>?up=1`, and knowing the topic is what grants the
 * ability to wake this device. It is therefore minted from [SecureRandom] and
 * never leaves the device except to the island the account lives on.
 */
object EmbeddedDistributor {

    /** Actions an app broadcasts AT a distributor (UnifiedPush spec). */
    const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
    const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
    const val ACTION_MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"

    /** Actions a distributor broadcasts BACK at the app; the connector library
     *  declares the receiver for these, which then drives our PushService. */
    const val ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
    const val ACTION_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
    const val ACTION_REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
    const val ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"

    const val EXTRA_APPLICATION = "application"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_ENDPOINT = "endpoint"
    const val EXTRA_BYTES_MESSAGE = "bytesMessage"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_MESSAGE_ID = "id"
    const val EXTRA_REASON = "reason"

    /** Our push server. A UnifiedPush topic is conventionally prefixed `up`, and
     *  `?up=1` marks the publish as a UnifiedPush one on the ntfy side. */
    const val PUSH_HOST = "https://push.rcq.app"
    private const val TOPIC_PREFIX = "up"
    private const val TOPIC_RANDOM_CHARS = 16

    private const val PREFS = "rcq_embedded_push"
    private const val K_TOPIC = "topic"
    private const val K_TOKEN = "token"
    private const val K_SINCE = "since_id"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The topic this device listens on, or null if nothing is registered. */
    fun topic(ctx: Context): String? = prefs(ctx).getString(K_TOPIC, null)

    /** The connector's instance token for the current registration. Kept so a
     *  MESSAGE broadcast can be addressed to the right registration. */
    fun token(ctx: Context): String? = prefs(ctx).getString(K_TOKEN, null)

    fun endpoint(ctx: Context): String? = topic(ctx)?.let { "$PUSH_HOST/$it?up=1" }

    /** Highest message id already handed to the app. Sent as ntfy's `since` on
     *  reconnect so a wake that arrived while the socket was down is replayed
     *  rather than lost (the server caches for 12h). */
    fun since(ctx: Context): String? = prefs(ctx).getString(K_SINCE, null)

    fun setSince(ctx: Context, id: String) {
        prefs(ctx).edit().putString(K_SINCE, id).apply()
    }

    /** Mint (or reuse) this device's topic for `token`. Reusing across
     *  re-registrations matters: the server already knows the old endpoint, and
     *  churning it would strand wakes addressed to the previous topic. A token
     *  change means a fresh registration, so that does mint a new one. */
    fun ensureTopic(ctx: Context, token: String): String {
        val p = prefs(ctx)
        val existing = p.getString(K_TOPIC, null)
        if (existing != null && p.getString(K_TOKEN, null) == token) return existing
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val rnd = SecureRandom()
        val topic = buildString {
            append(TOPIC_PREFIX)
            repeat(TOPIC_RANDOM_CHARS) { append(alphabet[rnd.nextInt(alphabet.length)]) }
        }
        p.edit().putString(K_TOPIC, topic).putString(K_TOKEN, token).remove(K_SINCE).apply()
        return topic
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    /** True when this device is registered with the embedded distributor. */
    fun isActive(ctx: Context): Boolean = topic(ctx) != null

    /** Start (or nudge) the socket service. Safe to call repeatedly.
     *
     *  Starting a foreground service is not always permitted from the
     *  background (Android 12+), and this is called from a broadcast receiver.
     *  A refusal is not fatal — the next foreground pass calls this again — so
     *  it is caught rather than allowed to crash the receiver. */
    fun ensureRunning(ctx: Context) {
        if (!isActive(ctx)) return
        runCatching {
            ContextCompat.startForegroundService(
                ctx.applicationContext,
                Intent(ctx.applicationContext, PushSocketService::class.java),
            )
        }
    }

    fun stop(ctx: Context) {
        runCatching {
            ctx.applicationContext.stopService(
                Intent(ctx.applicationContext, PushSocketService::class.java),
            )
        }
    }
}
