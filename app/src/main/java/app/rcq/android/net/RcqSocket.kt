package app.rcq.android.net

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * WebSocket channel to the backend (rcq-spec 7). Connects to
 * `wss://<host>/ws/<uin>?token=<jwt>` and surfaces every inbound event as
 * a parsed (type, json) pair; the caller branches on `type`
 * (message-family vs contact_* etc). Auto-reconnects with exponential
 * backoff while it's supposed to stay connected.
 */
class RcqSocket(private val baseWsUrl: String = DEFAULT_WS_URL) {

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        // A user-chosen local proxy (Tor/i2p/AWG) is slow to build its first
        // circuit; the default 10s connect can give up before the WS handshake
        // completes. Give local-proxy users a longer connect leash.
        .connectTimeout(if (SingBoxTransport.localProxyMode()) 30 else 10, TimeUnit.SECONDS)
        // Ride the embedded sing-box SOCKS proxy when the transport is engaged
        // (null = direct). Captured at build; Session rebuilds the socket after
        // engaging so it picks the proxy up.
        .proxy(SingBoxTransport.proxy())
        // Stamp X-RCQ-Auth on the WS upgrade so a closed (masquerade) island's
        // socket passes the gate (OkHttp runs the handshake through interceptors).
        .addInterceptor(AccessTokenInterceptor)
        .build()

    private var ws: WebSocket? = null
    private var shouldStayConnected = false
    private var attempt = 0
    private var pingTimer: java.util.Timer? = null

    // Identity guard for listener callbacks: open() rotates sockets, and a
    // cancel()ed socket still fires onFailure asynchronously. Without the
    // generation check that stale callback would flip the connected state
    // to false and schedule a competing reconnect against the fresh socket.
    @Volatile
    private var generation = 0

    private var uin: Int = 0
    private var token: String = ""
    private var onEvent: (type: String, obj: JsonObject) -> Unit = { _, _ -> }
    private var onState: (Boolean) -> Unit = {}

    fun connect(
        uin: Int,
        token: String,
        onEvent: (type: String, obj: JsonObject) -> Unit,
        onState: (connected: Boolean) -> Unit = {},
    ) {
        this.uin = uin
        this.token = token
        this.onEvent = onEvent
        this.onState = onState
        shouldStayConnected = true
        open()
    }

    /** Send a raw JSON frame (e.g. typing). No-op if the socket is down. */
    fun send(json: String): Boolean = ws?.send(json) ?: false

    private fun startPing() {
        pingTimer?.cancel()
        pingTimer = java.util.Timer(true).apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                // App-level heartbeat matching the iOS client; keeps the
                // server's per-socket liveness fresh on top of OkHttp's
                // protocol ping.
                override fun run() { ws?.send("{\"type\":\"ping\"}") }
            }, 25_000, 25_000)
        }
    }

    private fun open() {
        ws?.cancel()
        val gen = ++generation
        val request = Request.Builder().url("$baseWsUrl/ws/$uin?token=$token").build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) return
                attempt = 0
                startPing()
                onState(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                runCatching {
                    val obj = JsonParser.parseString(text).asJsonObject
                    val type = obj.get("type")?.asString ?: return
                    onEvent(type, obj)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation) return
                onState(false)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != generation) return
                onState(false)
                scheduleReconnect()
            }
        })
    }

    /** Tear down the current socket and dial again right away. Used when the
     *  network path changed (VPN dropped/joined, Wi-Fi ↔ cellular): the old
     *  socket is bound to the vanished route and OkHttp's protocol ping takes
     *  up to ~40s to notice, so the connection dot would lie green meanwhile.
     *  Flips the state to "connecting" immediately and resets the backoff. */
    fun reconnectNow() {
        if (!shouldStayConnected) return
        attempt = 0
        onState(false)
        open()
    }

    private fun scheduleReconnect() {
        if (!shouldStayConnected) return
        attempt += 1
        val gen = generation
        val delayMs = min(30_000L, (1000L shl min(attempt - 1, 5)))
        Thread {
            Thread.sleep(delayMs)
            // Skip if a newer socket was dialed while we waited (reconnectNow
            // on a network change) — open() would needlessly cancel it.
            if (shouldStayConnected && gen == generation) open()
        }.apply { isDaemon = true }.start()
    }

    fun disconnect() {
        shouldStayConnected = false
        pingTimer?.cancel()
        pingTimer = null
        ws?.close(1000, null)
        ws = null
    }

    companion object {
        const val DEFAULT_WS_URL = "wss://api.rcq.app"
    }
}
