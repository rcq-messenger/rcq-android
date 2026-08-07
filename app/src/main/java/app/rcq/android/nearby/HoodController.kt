package app.rcq.android.nearby

import app.rcq.android.net.RcqApi
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Hood Chat (district chat) — the district thread for a geohash bucket.
 * scoped to a geohash bucket, the server-backed half of People Nearby. Port of
 * Mirrors the iOS HoodChatService. NOT end-to-end encrypted
 * (pseudonymous via the Nearby display name); the chat UI shows an "unencrypted"
 * notice. Chat state flows through the WS (hood_* events routed from
 * Session.handleEvent).
 */
class HoodController(
    private val scope: CoroutineScope,
    private val api: () -> RcqApi,
    private val send: (JsonObject) -> Unit,
    private val nick: () -> String,
    private val isAnonymous: () -> Boolean,
) {
    private val gson = Gson()

    // ── chat ──────────────────────────────────────────────────────────
    private val _bucket = MutableStateFlow<String?>(null)
    val bucket: StateFlow<String?> = _bucket.asStateFlow()
    private val _messages = MutableStateFlow<List<RcqApi.HoodMessage>>(emptyList())
    val messages: StateFlow<List<RcqApi.HoodMessage>> = _messages.asStateFlow()
    private val _bucketCount = MutableStateFlow(0)
    val bucketCount: StateFlow<Int> = _bucketCount.asStateFlow()

    fun joinChat(bucket: String) {
        _bucket.value = bucket
        _messages.value = emptyList()
        // Subscribe BEFORE the catch-up fetch so the count includes us and any
        // in-flight hood_message is fanned out to us.
        send(JsonObject().apply { addProperty("type", "hood_subscribe"); addProperty("bucket", bucket) })
        scope.launch { runCatching { api().hoodMessages(bucket) }.onSuccess { upsertAll(it) } }
    }

    fun leaveChat() {
        if (_bucket.value != null) send(JsonObject().apply { addProperty("type", "hood_unsubscribe") })
        _bucket.value = null
        _messages.value = emptyList()
        _bucketCount.value = 0
    }

    fun sendMessage(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _bucket.value == null) return
        scope.launch {
            runCatching {
                api().hoodSend(RcqApi.HoodSendBody(body = t, nickname = nick(), anonymous = isAnonymous()))
            }
            // No optimistic append: the hood_message broadcast adds it.
        }
    }

    fun deleteMessage(id: Int) {
        scope.launch { runCatching { api().hoodDelete(id) } }
    }

    fun react(id: Int, emoji: String) {
        scope.launch { runCatching { api().hoodReact(id, emoji) } }
    }

    /** Routed from Session.handleEvent for hood_* events. */
    fun onSignal(type: String, obj: JsonObject) {
        val active = _bucket.value
        when (type) {
            "hood_message" -> {
                val m = runCatching { gson.fromJson(obj.getAsJsonObject("message"), RcqApi.HoodMessage::class.java) }.getOrNull() ?: return
                if (active == null || m.bucket_id != active) return
                upsertOne(m)
                obj.get("bucket_count")?.takeIf { !it.isJsonNull }?.asInt?.let { _bucketCount.value = it }
            }
            "hood_count" -> {
                if (obj.get("bucket_id")?.asString != active) return
                _bucketCount.value = obj.get("count")?.asInt ?: _bucketCount.value
            }
            "hood_delete" -> {
                if (obj.get("bucket_id")?.asString != active) return
                val id = obj.get("message_id")?.asInt ?: return
                _messages.value = _messages.value.map { if (it.id == id) it.copy(deleted = true, body = "") else it }
            }
            "hood_reaction" -> {
                if (obj.get("bucket_id")?.asString != active) return
                val id = obj.get("message_id")?.asInt ?: return
                val reactions = runCatching {
                    gson.fromJson(obj.getAsJsonObject("reactions"), Map::class.java) as Map<String, String>
                }.getOrDefault(emptyMap())
                _messages.value = _messages.value.map { if (it.id == id) it.copy(reactions = reactions) else it }
            }
        }
    }

    private fun upsertAll(list: RcqApi.HoodList) {
        _messages.value = list.messages.sortedBy { it.id }
        _bucketCount.value = list.bucket_count
    }

    private fun upsertOne(m: RcqApi.HoodMessage) {
        val cur = _messages.value
        _messages.value = if (cur.any { it.id == m.id }) cur.map { if (it.id == m.id) m else it }
        else (cur + m).sortedBy { it.id }
    }

    fun teardown() {
        if (_bucket.value != null) runCatching { send(JsonObject().apply { addProperty("type", "hood_unsubscribe") }) }
        _bucket.value = null
        _messages.value = emptyList()
        _bucketCount.value = 0
    }
}
