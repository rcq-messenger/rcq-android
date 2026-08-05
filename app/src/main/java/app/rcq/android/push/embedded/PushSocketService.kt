package app.rcq.android.push.embedded

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import app.rcq.android.MainActivity
import app.rcq.android.R
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * The connection half of RCQ's embedded UnifiedPush distributor: one long-lived
 * WebSocket to push.rcq.app, held open by a foreground service.
 *
 * A foreground service (and its permanent notification) is the price of
 * background push without Google's services — it is exactly what the ntfy app
 * does, and what every RU-market messenger does. The notification is put on its
 * own MIN-importance channel so the system shows it as quietly as it allows,
 * and the user can still hide it via channel settings without killing delivery.
 *
 * On each `message` event the raw body is handed to the app through the
 * UnifiedPush MESSAGE broadcast, which the connector library routes into
 * [app.rcq.android.push.RcqPushService] — the same path a wake from ntfy took,
 * so nothing downstream had to change.
 *
 * Reconnects use exponential backoff with jitter, and resume from the last
 * delivered message id (`since`), so a wake that landed while the socket was
 * down is replayed from the server's 12h cache instead of being lost.
 */
class PushSocketService : Service() {

    companion object {
        private const val TAG = "RCQpushsock"
        const val CHANNEL_ID = "rcq_push_service"
        private const val NOTIF_ID = 0x2C02

        /** Backoff bounds for reconnects. The ceiling is deliberately low: a
         *  push socket that waits ten minutes to come back is a push socket
         *  that misses the message the user is waiting for. */
        private const val BACKOFF_MIN_MS = 2_000L
        private const val BACKOFF_MAX_MS = 60_000L
    }

    private var client: OkHttpClient? = null
    /** Transport the current client is pinned to, so a change can be spotted. */
    private var usingProxy: java.net.Proxy? = null
    private var socket: WebSocket? = null
    /** Topic the live socket is subscribed to. A re-registration mints a new
     *  topic, and without this the service happily kept listening on the old
     *  one — connected, healthy, and deaf to every wake the server sent. */
    private var connectedTopic: String? = null
    /** Host the live socket is subscribed through. A signed-config push can name
     *  a push front (or withdraw one) while this socket is up and the topic
     *  unchanged, and the dial host is fixed for the life of the socket — so
     *  without this the new name would only take effect at the next unrelated
     *  reconnect, which on a working socket may be never. */
    private var connectedHost: String? = null
    @Volatile private var stopping = false
    private var attempt = 0
    /** Bumped every time we deliberately drop a socket. A superseded socket
     *  still reports onClosed/onFailure, and treating that as a network failure
     *  scheduled a reconnect on top of the one we had just made — two live
     *  sockets, every wake delivered twice. Callbacks carrying an old
     *  generation are ignored. */
    private var generation = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        connect()
    }

    /** A client bound to the transport in force right now. Rebuilt whenever
     *  that changes, because a socket opened directly cannot start using the
     *  tunnel (and vice versa) — OkHttp fixes the route at construction. */
    private fun buildClient(proxy: java.net.Proxy?): OkHttpClient =
        OkHttpClient.Builder()
            // No read timeout: this socket is idle by design between wakes. The
            // server sends a keepalive every 45s, and pingInterval gives us our
            // own liveness check on top.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(50, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .proxy(proxy)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val wanted = EmbeddedDistributor.topic(this)
        val wantedHost = EmbeddedDistributor.subscribeHost()
        val forced = intent?.getBooleanExtra(EmbeddedDistributor.EXTRA_RECONNECT, false) == true
        if (socket != null && (forced || connectedTopic != wanted || connectedHost != wantedHost)) {
            Log.i(TAG, when {
                forced -> "transport changed — redialling"
                connectedTopic != wanted -> "topic changed — resubscribing"
                else -> "push host changed — redialling via $wantedHost"
            })
            dropSocket()
        }
        if (socket == null && !stopping) connect()
        // START_STICKY: if the system reclaims us, come back. Without push the
        // app is foreground-only, which is the whole problem being solved here.
        return START_STICKY
    }

    /** Retire the current socket without letting its own close callback look
     *  like a network failure. */
    private fun dropSocket() {
        generation += 1
        handler.removeCallbacksAndMessages(null)
        runCatching { socket?.close(1000, "superseded") }
        socket = null
        connectedTopic = null
        connectedHost = null
    }

    override fun onDestroy() {
        stopping = true
        dropSocket()
        client?.dispatcher?.executorService?.shutdown()
        super.onDestroy()
    }

    private fun startInForeground() {
        ensureChannel(this)
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // "Hide" opens THIS channel's system settings: blocking the channel
        // there removes the row for good while the service keeps running
        // (Android 13+ shows only the Task Manager "active apps" pill). That
        // is the honest way to get rid of the notice — the OS will not allow
        // a background socket without a foreground service.
        val hide = PendingIntent.getActivity(
            this, 1,
            Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.push_service_title))
            .setContentText(getString(R.string.push_service_body))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(tap)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(0, getString(R.string.push_service_hide), hide)
            .build()
        // Android 14+ requires the service type at startForeground time; older
        // releases reject the 3-arg overload, so the branch is on the platform
        // API rather than a compat shim.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }.onFailure { Log.w(TAG, "startForeground refused: $it") }
    }

    private fun connect() {
        val topic = EmbeddedDistributor.topic(this) ?: run {
            Log.w(TAG, "no topic registered — stopping")
            stopSelf(); return
        }
        // Ride the censorship-circumvention tunnel whenever it is engaged. A
        // push socket pinned to a direct route would be the one part of the app
        // that dies the moment the push host is blocked, while everything else
        // kept working through relays — which is precisely when a user needs to
        // be told they have a message.
        val proxy = runCatching { app.rcq.android.net.SingBoxTransport.proxy() }.getOrNull()
        if (client == null || proxy != usingProxy) {
            client?.dispatcher?.executorService?.shutdown()
            client = buildClient(proxy)
            usingProxy = proxy
            Log.i(TAG, "transport: ${if (proxy != null) "relay" else "direct"}")
        }
        val since = EmbeddedDistributor.since(this)
        // Subscribe leg only — the endpoint the island publishes to is
        // unaffected. Re-read per dial so a config push that names a front
        // reaches the socket on its next reconnect, with no app release.
        val host = EmbeddedDistributor.subscribeHost()
        val url = buildString {
            append(host.replaceFirst("https://", "wss://"))
            append('/').append(topic).append("/ws")
            if (since != null) append("?since=").append(since)
        }
        if (host != EmbeddedDistributor.PUSH_HOST) Log.i(TAG, "subscribing via front $host")
        val req = Request.Builder().url(url).build()
        connectedTopic = topic
        connectedHost = host
        socket = client?.newWebSocket(req, Listener(generation))
    }

    private fun scheduleReconnect() {
        if (stopping) return
        socket = null
        attempt += 1
        val base = min(BACKOFF_MIN_MS shl (attempt - 1).coerceAtMost(5), BACKOFF_MAX_MS)
        // Jitter so a network-wide blip does not bring every device back in
        // lockstep — the same thundering herd that drained the ntfy.sh bucket.
        val delay = (base * (0.7 + Random.nextDouble() * 0.6)).toLong()
        Log.w(TAG, "reconnect in ${delay}ms (attempt $attempt)")
        handler.postDelayed({ if (!stopping) connect() }, delay)
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {

        /** True once this socket has been superseded (topic change, shutdown). */
        private fun stale(): Boolean = gen != generation || stopping

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (stale()) { runCatching { webSocket.close(1000, "stale") }; return }
            attempt = 0
            Log.i(TAG, "connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (stale()) return
            val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
            fun str(k: String) = obj.get(k)?.takeIf { !it.isJsonNull }?.asString
            when (str("event")) {
                // "open" and "keepalive" only prove the socket is alive.
                "message" -> {
                    val body = str("message") ?: return
                    val id = str("id")
                    deliver(body, id)
                    if (id != null) EmbeddedDistributor.setSince(this@PushSocketService, id)
                }
                else -> Unit
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (stale()) return
            Log.w(TAG, "socket failed: ${t.javaClass.simpleName}: ${t.message}")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (stale()) return
            Log.w(TAG, "socket closed ($code $reason)")
            scheduleReconnect()
        }
    }

    /** Hand one wake to the app over the UnifiedPush MESSAGE broadcast. The
     *  body is the exact bytes the server POSTed (our sealed wake JSON); the
     *  connector library delivers it to RcqPushService.onMessage. */
    private fun deliver(body: String, id: String?) {
        val token = EmbeddedDistributor.token(this) ?: return
        val intent = Intent(EmbeddedDistributor.ACTION_MESSAGE).apply {
            `package` = packageName
            putExtra(EmbeddedDistributor.EXTRA_TOKEN, token)
            putExtra(EmbeddedDistributor.EXTRA_BYTES_MESSAGE, body.toByteArray(Charsets.UTF_8))
            putExtra(EmbeddedDistributor.EXTRA_MESSAGE, body)
            if (id != null) putExtra(EmbeddedDistributor.EXTRA_MESSAGE_ID, id)
        }
        sendBroadcast(intent)
    }
}

/** MIN-importance channel for the connection notification: required by the
 *  system for a foreground service, and deliberately as quiet as a channel can
 *  be made. Separate from the message channels so silencing it cannot silence
 *  an actual message. */
internal fun ensureChannel(ctx: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
    // Deliberately UNCONDITIONAL: re-creating an existing channel refreshes its
    // user-visible name/description (the self-explaining copy), while the
    // importance keeps whatever the user set — the platform never lets code
    // raise it back, which is exactly right here.
    nm.createNotificationChannel(
        NotificationChannel(
            PushSocketService.CHANNEL_ID,
            ctx.getString(R.string.push_service_channel),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = ctx.getString(R.string.push_service_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        },
    )
}
