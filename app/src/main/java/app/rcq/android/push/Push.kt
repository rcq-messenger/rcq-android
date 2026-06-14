package app.rcq.android.push

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.rcq.android.MainActivity
import app.rcq.android.R
import app.rcq.android.call.IncomingCallActivity
import app.rcq.android.call.IncomingCallStore
import app.rcq.android.data.AccountManager
import app.rcq.android.data.SecureStore
import app.rcq.android.net.RcqApi
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush

/**
 * UnifiedPush glue for the Android client. The server (rcq-server-ref) POSTs a
 * JSON wake payload to the endpoint URL we register; a distributor app (ntfy,
 * …) relays it to [RcqPushService]. This object owns the small surface around
 * that: persisting the endpoint, registering it with every local account's
 * island, creating the notification channel, and turning a {type:"msg"} wake
 * into a system notification.
 *
 * STAGE 1 scope: background MESSAGE notifications. The notification shows the
 * server-provided generic title/body/group_name only — NO background
 * decryption (decrypting a v=2 or sender-keys envelope out of band would
 * advance the ratchet and make the live WS/offline-queue copy undecryptable,
 * losing the message). The real sender + text arrive when the app opens and
 * drains the offline queue. Incoming-call wakes ({type:"call"}) are Stage 2.
 */
object Push {
    const val CHANNEL_MESSAGES = "rcq_messages"
    const val CHANNEL_CALLS = "rcq_calls"
    const val CHANNEL_CALLS_RING = "rcq_calls_ring"
    private const val CALL_NOTIF_ID = 0x2C01

    private const val PREFS = "rcq_push"
    private const val K_ENDPOINT = "endpoint"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedEndpoint(ctx: Context): String? = prefs(ctx).getString(K_ENDPOINT, null)
    fun setEndpoint(ctx: Context, url: String) { prefs(ctx).edit().putString(K_ENDPOINT, url).apply() }
    fun clearEndpoint(ctx: Context) { prefs(ctx).edit().remove(K_ENDPOINT).apply() }

    /** Create the message notification channel. Idempotent; safe from
     *  Application.onCreate (also runs on headless starts). */
    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_MESSAGES) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MESSAGES,
                    ctx.getString(R.string.push_channel_messages),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = ctx.getString(R.string.push_channel_messages_desc) },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_CALLS) == null) {
            nm.createNotificationChannel(
                // High importance so a full-screen-intent fires; silent because
                // IncomingCallActivity drives its own ringtone via Ringer.
                NotificationChannel(
                    CHANNEL_CALLS,
                    ctx.getString(R.string.push_channel_calls),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = ctx.getString(R.string.push_channel_calls_desc)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_CALLS_RING) == null) {
            nm.createNotificationChannel(
                // Audible fallback: used when the full-screen intent can't launch
                // (Android 14+ without USE_FULL_SCREEN_INTENT granted) so the call
                // still RINGS as a heads-up instead of being a silent dropped call.
                NotificationChannel(
                    CHANNEL_CALLS_RING,
                    ctx.getString(R.string.push_channel_calls),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = ctx.getString(R.string.push_channel_calls_desc)
                    val ring = android.media.RingtoneManager
                        .getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                    val attrs = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setSound(ring, attrs)
                    enableVibration(true)
                },
            )
        }
    }

    /** Ask the active distributor for a push endpoint, if one is set up.
     *  Non-intrusive: registers against a saved/default distributor and never
     *  forces a picker — a device with no distributor simply gets no push
     *  (degrades to today's foreground-only behaviour). The resulting endpoint
     *  arrives asynchronously in [RcqPushService.onNewEndpoint]. */
    fun registerDistributor(activity: Activity) {
        val ctx = activity.applicationContext
        if (UnifiedPush.getSavedDistributor(ctx) != null) {
            UnifiedPush.register(ctx)
        } else {
            UnifiedPush.tryUseDefaultDistributor(activity) { ok ->
                if (ok) UnifiedPush.register(ctx)
            }
        }
    }

    /** POST [endpoint] to every local account's island so each can wake this
     *  device. Idempotent server-side (upsert on uin+token). Fire-and-forget;
     *  callable headless (reads per-account creds straight from SecureStore). */
    fun registerWithBackend(ctx: Context, endpoint: String) {
        CoroutineScope(Dispatchers.IO).launch {
            for (acct in AccountManager.accounts.value) {
                val store = SecureStore(ctx, acct.id)
                val token = store.token ?: continue
                val host = store.serverHost ?: RcqApi.DEFAULT_HOST
                runCatching {
                    RcqApi("https://$host").apply { setToken(token) }.setPushToken(endpoint)
                }
            }
        }
    }

    /** Build + post a wake notification for a {type:"msg"} push payload. */
    fun showMessage(ctx: Context, json: JsonObject) {
        ensureChannels(ctx)
        fun str(k: String): String? =
            json.get(k)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

        val groupName = str("group_name")
        val isGroup = groupName != null || str("group_id") != null
        val title = groupName ?: str("title") ?: ctx.getString(R.string.app_name)
        val body = str("body") ?: ctx.getString(
            if (isGroup) R.string.push_new_group_message else R.string.push_new_message,
        )

        val tap = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        // Distinct groups get their own notification; all 1:1 pushes collapse
        // into one "New message" (the sealed wake doesn't reveal the sender).
        val id = (str("group_id") ?: "dm").hashCode()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, notif) }
    }

    /** Raise a full-screen incoming-call wake for a {type:"call"} payload, or
     *  dismiss it when kind=="end". The full-screen-intent surfaces
     *  [IncomingCallActivity] over the lock screen; on accept it hands off to
     *  MainActivity which runs the WebRTC answer through the live Session. */
    fun showIncomingCall(ctx: Context, json: JsonObject) {
        fun str(k: String): String? =
            json.get(k)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
        val callId = str("call_id") ?: return
        if (str("kind") == "end") { dismissIncomingCall(ctx, callId); return }
        val sdp = str("sdp") ?: return
        val fromUin = json.get("from_uin")?.takeIf { !it.isJsonNull }?.asInt ?: return
        ensureChannels(ctx)
        val nickname = str("nickname") ?: "#$fromUin"
        IncomingCallStore.offer(
            IncomingCallStore.Pending(
                callId = callId,
                fromUin = fromUin,
                nickname = nickname,
                media = str("media") ?: "video",
                sdp = sdp,
            ),
        )
        val full = Intent(ctx, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        }
        val pi = PendingIntent.getActivity(
            ctx, 1, full,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // On Android 14+ USE_FULL_SCREEN_INTENT is special-access and may be
        // ungranted, so setFullScreenIntent silently degrades to a heads-up. Use
        // the audible ring channel in that case so the call still rings instead
        // of being a silent dropped call; the silent channel only when the FSI
        // can actually launch IncomingCallActivity (which rings via Ringer).
        val fsiOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            (ctx.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true)
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            android.util.Log.w("RCQpush", "incoming call: notifications disabled — call UI cannot be shown")
        }
        val notif = NotificationCompat.Builder(ctx, if (fsiOk) CHANNEL_CALLS else CHANNEL_CALLS_RING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(nickname)
            .setContentText(ctx.getString(R.string.call_incoming))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(CALL_NOTIF_ID, notif) }
    }

    fun cancelCallNotification(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(CALL_NOTIF_ID) }
    }

    /** Caller cancelled before pickup ({kind:"end"}): drop the offer, remove the
     *  notification, and tell a showing IncomingCallActivity to finish. */
    fun dismissIncomingCall(ctx: Context, callId: String) {
        IncomingCallStore.clearIf(callId)
        cancelCallNotification(ctx)
        runCatching {
            ctx.sendBroadcast(
                Intent(IncomingCallActivity.ACTION_CANCEL)
                    .setPackage(ctx.packageName)
                    .putExtra(IncomingCallActivity.EXTRA_CALL_ID, callId),
            )
        }
    }
}
