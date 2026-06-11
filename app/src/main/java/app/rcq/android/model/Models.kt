package app.rcq.android.model

/** A mutual contact, as returned by GET /contacts (server-authoritative). */
data class Contact(
    val uin: Int,
    val nickname: String,
    val identityKey: String,        // base64 raw X25519 public
    val signingKey: String?,        // base64 raw Ed25519 public
    val status: String? = null,     // online|away|dnd|offline
    val statusMessage: String? = null,
    val blocked: Boolean = false,
    val gender: String? = null,     // "m" | "f" | null (visibility-gated)
    val lastSeen: Long? = null,     // epoch millis, null when hidden/online
    val callable: Boolean = true,   // false = peer's call_policy is "nobody" (hide call buttons)
    // Cross-island contact's island (local-only, never sent by the server —
    // Gson leaves it null for roster rows). Drives the "Other islands" home
    // section + the gray presence flower.
    val host: String? = null,
) {
    /** Presence as a typed enum (server never sends `invisible` for peers). */
    val presence: UserStatus get() = UserStatus.from(status)
}

/** An inbound contact request awaiting the user's accept/decline. */
data class PendingRequest(
    val requestId: Int,
    val fromUin: Int,
    val fromNickname: String,
)

/** A contact request WE sent: still pending, or declined by the recipient
 *  (surfaced so the sender learns the outcome — there's no push for it). */
data class OutgoingRequest(
    val toUin: Int,
    val toNickname: String,
    val state: String,   // "pending" | "declined"
)

/** Delivery state of an outgoing message. Incoming messages are always
 *  DELIVERED. READ is set when the peer sends back a read receipt. */
enum class DeliveryState { SENDING, SENT, DELIVERED, FAILED, READ }

/** A single chat message, persisted locally (the server doesn't keep
 *  messages after delivery). `id` is the envelope UUID — the dedup key
 *  that prevents a message arriving via both WebSocket and the queue
 *  drain from showing twice (rcq-spec 6.3.1). */
/** A poll message's content, stored as JSON in [ChatMessage.body] for
 *  `kind == "poll"` (the live vote tallies are fetched fresh from /polls/{id}
 *  and never persisted). [pollId] is the server id used to vote/close. */
data class PollContent(
    val pollId: Int,
    val question: String,
    val options: List<String>,
    val singleChoice: Boolean,
    val anonymous: Boolean,
) {
    fun toJson(): String = org.json.JSONObject().apply {
        put("poll", pollId)
        put("q", question)
        put("opts", org.json.JSONArray(options))
        put("sc", singleChoice)
        put("anon", anonymous)
    }.toString()

    companion object {
        fun fromJson(body: String): PollContent? = runCatching {
            val o = org.json.JSONObject(body)
            val arr = o.getJSONArray("opts")
            PollContent(
                pollId = o.optInt("poll"),
                question = o.optString("q"),
                options = (0 until arr.length()).map { arr.getString(it) },
                singleChoice = o.optBoolean("sc", true),
                anonymous = o.optBoolean("anon", false),
            )
        }.getOrNull()
    }
}

data class ChatMessage(
    val id: String,
    val peerUin: Int,             // 1:1 peer; 0 for group messages
    val fromMe: Boolean,
    val body: String,             // text, or caption for media
    val sentAt: Long,
    val state: DeliveryState = DeliveryState.DELIVERED,
    val kind: String = "text",    // "text" | "photo"
    val mediaId: String? = null,
    val mediaKey: String? = null,
    val replyToSnippet: String? = null,
    val replyToAuthor: String? = null,
    val replyToId: String? = null, // id of the message this replies to (jump-to-original)
    val groupId: Int? = null,     // non-null => this message belongs to a group thread
    val senderUin: Int? = null,   // group message author (for sender labels)
    val reactions: Map<Int, String> = emptyMap(),  // reactor UIN -> emoticon asset (one per user)
    val edited: Boolean = false,  // body was replaced by a later edit envelope
    val fileName: String? = null, // kind == "file": original name
    val fileMime: String? = null, // kind == "file": MIME type
    val fileSize: Long? = null,   // kind == "file": size in bytes
    val durationSec: Int? = null, // kind == "voice" / "video": clip length in seconds
    val thumbB64: String? = null, // kind == "video": base64 JPEG poster frame
    val lat: Double? = null,      // kind == "location"
    val lng: Double? = null,      // kind == "location"
    val spoiler: Boolean = false, // photo/video sent blurred; tap-to-reveal
    val albumId: String? = null,  // photo/video grouped into a media album
)
