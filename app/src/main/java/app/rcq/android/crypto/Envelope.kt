package app.rcq.android.crypto

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

/**
 * Message envelope — the plaintext payload that lives inside a sealed
 * envelope. Mirrors the iOS `Envelope` Codable (CryptoService.swift). The
 * MVP only handles text; other kinds (media, reactions, system, …) decode
 * to [Unknown] and are ignored for now.
 *
 * Wire JSON for a text message (must match iOS byte-for-byte enough for
 * its JSONDecoder to parse, and vice versa):
 *   {"kind":"text","id":"<UUID>","text":"<string>"}
 * iOS uses uppercase UUID strings; we emit the same. Optional fields
 * (ttl, ts, fwdName, reply) are omitted, matching iOS `encodeIfPresent`.
 */
/** Quoted-message context, matching the iOS ReplyContext Codable
 *  ({id, snippet, authorName}), carried under the "reply" key. */
data class Reply(val id: String, val snippet: String, val authorName: String)

sealed interface Envelope {
    /** Disappearing-message TTL in seconds carried by the sender inside the
     *  encrypted envelope (iOS/web key "ttl"); null = permanent. Only the
     *  content kinds below carry it.
     *
     *  Every one of them also carries `ts` — the SENDER'S epoch SECONDS, the
     *  instant the countdown runs from. Same field name and units the `call`,
     *  `contactreq` and `profile` envelopes have always used, and the shape the
     *  web settled on (`web-chat/src/lib/crypto.ts`).
     *
     *  ⚠ WHY THE TIMESTAMP EXISTS. Without it a receiver can only count from
     *  the moment the bytes landed on ITS device, and a phone that was offline
     *  for a week drains the queue and then keeps a "vanishes in 5 minutes"
     *  message for five minutes MORE, a week after its author was told it was
     *  gone. It rides INSIDE the ciphertext, so the island learns nothing from
     *  it.
     *
     *  ⚠ EMITTED ONLY BESIDE A ttl. A timestamp on every message would be a
     *  new piece of metadata inside the envelope for no one's benefit, and the
     *  metadata plan spends its budget the other way. Same rule on the web.
     *
     *  ⚠ ATTACKER-CONTROLLED. It is a number a peer's client chose; the reader
     *  (`Session.expiryFor`) rails it before trusting it. Nothing here does. */
    /** [card] is a GUEST CARD the sender is handing the recipient, on a CLOSED
     *  island. It is the whole of "I wrote to you first, so you may write
     *  back": it needs no server state, no screen and no round trip, because
     *  it rides INSIDE the sealed envelope where only the recipient can read
     *  it. The island sees an opaque blob, exactly as before, and could never
     *  enforce this itself — `/messages/sealed` takes no auth by design and the
     *  message row has no sender column.
     *
     *  ⚠ 1:1 only, and never on an open island: a card is a live credential
     *  with no business travelling to a door that is not locked, or to a room
     *  whose membership can change after the fact. */
    data class Text(val id: String, val text: String, val replyTo: Reply? = null, val ttl: Int? = null, val ts: Long? = null, val card: String? = null) : Envelope
    /** Photo. `mediaId`/`mediaKey` point at the out-of-band encrypted
     *  blob (rcq-spec 9). caption may be empty. [spoiler] = sent blurred,
     *  the recipient taps to reveal (Android-only flag; iOS ignores it). */
    data class Photo(val id: String, val mediaId: String, val mediaKey: String, val caption: String?, val spoiler: Boolean = false, val albumId: String? = null, val ttl: Int? = null, val ts: Long? = null) : Envelope
    /** A reaction to another message (iOS kind "reaction"). Carries no own
     *  message id; [targetId] is the reacted message's UUID, [asset] the
     *  emoji (null clears, currently treated as a no-op on receipt). */
    data class Reaction(val targetId: String, val asset: String?) : Envelope
    /** Delete-for-everyone (iOS kind "delete"): the author retracts the
     *  message [targetId] for all recipients. */
    data class Delete(val targetId: String) : Envelope
    /** Edit (iOS kind "edit"): the author replaces the body of message
     *  [targetId] with [text]. */
    data class Edit(val targetId: String, val text: String) : Envelope
    /** Read receipt (iOS kind "read"): the recipient acknowledges seeing
     *  the messages [targetIds]. The original sender flips those bubbles
     *  to READ. */
    data class ReadReceipt(val targetIds: List<String>) : Envelope
    /** Receiver → sender: "these arrived on my device". Flips the sender's
     *  bubbles from SENT to DELIVERED.
     *
     *  ⚠ Why this exists at all: the second tick used to be decided ONCE, by
     *  the island's answer to the send ("was a socket of theirs live at this
     *  instant"), and never caught up. Three messages written while the peer
     *  was offline kept one tick forever, and the fourth, sent a minute after
     *  they came back, got two — which is exactly what a tester reported.
     *
     *  The island cannot fix it: a deposit is unauthenticated and sealed, so it
     *  never learns who sent what and has nobody to tell. Only the recipient's
     *  own client knows, so only it can say so. */
    data class DeliveredReceipt(val targetIds: List<String>) : Envelope
    /** File attachment (iOS kind "file"). Like [Photo] the bytes live in an
     *  out-of-band encrypted blob; [fileName]/[mime]/[sizeBytes] describe it
     *  for the bubble. */
    data class File(
        val id: String,
        val mediaId: String,
        val mediaKey: String,
        val fileName: String,
        val mime: String,
        val sizeBytes: Long,
        val caption: String?,
        val ttl: Int? = null,
        val ts: Long? = null,
    ) : Envelope
    /** Voice note (iOS kind "voice"). Audio bytes live in an encrypted
     *  blob; [durationSec] drives the bubble timer. */
    data class Voice(
        val id: String,
        val mediaId: String,
        val mediaKey: String,
        val durationSec: Double,
        val ttl: Int? = null,
        val ts: Long? = null,
    ) : Envelope
    /** Video (iOS kind "video"). Bytes in an encrypted blob; [thumbnailB64]
     *  is a base64 JPEG poster frame shown before download, [durationSec]
     *  drives the bubble. */
    data class Video(
        val id: String,
        val mediaId: String,
        val mediaKey: String,
        val thumbnailB64: String,
        val durationSec: Double,
        val caption: String?,
        val spoiler: Boolean = false,
        val albumId: String? = null,
        val ttl: Int? = null,
        val ts: Long? = null,
    ) : Envelope
    /** Shared location (iOS kind "location"). */
    data class Location(val id: String, val lat: Double, val lng: Double, val caption: String?, val ttl: Int? = null, val ts: Long? = null) : Envelope
    /** Profile-view ping (iOS kind "visit"). Fire-and-forget, no bubble:
     *  the recipient tallies it locally for the "profile views" stat.
     *  [at] is seconds since the 2001 reference date, matching the iOS
     *  default JSONEncoder Date encoding. */
    data class Visit(val at: Double) : Envelope {
        fun atEpochMillis(): Long = ((at + APPLE_EPOCH_OFFSET_SEC) * 1000).toLong()
    }
    /** Group poll announcement (iOS kind "poll"). The server-side [pollId]
     *  lets every recipient hit /polls/{id}/vote directly; the question +
     *  options ride encrypted here so a client that can't reach /polls still
     *  renders the ballot. Wire keys are terse to match iOS: poll/q/opts/sc/anon. */
    data class Poll(
        val id: String,
        val pollId: Int,
        val question: String,
        val options: List<String>,
        val singleChoice: Boolean,
        val anonymous: Boolean,
    ) : Envelope
    /** Per-conversation screen-secure toggle, propagated to the peer so BOTH
     *  sides enforce it (iOS wire "secscreen"). Control only — no bubble. */
    data class SecureScreen(val on: Boolean) : Envelope

    /** Sent when the sender took a screenshot in a secure chat (iOS wire
     *  "shot"). The receiver shows "<name> took a screenshot". Control only. */
    data class ScreenshotTaken(val id: String) : Envelope

    /** Multi-device send-side sync (iOS/web wire "carbon"). When you send a
     *  message from one device, that device also seals a Carbon to your OWN
     *  identity (to_uin = you) wrapping the original [env] + its destination
     *  (exactly one of [to] / [gid]). Your other devices unwrap it and file the
     *  inner message as fromMe in the destination thread; the origin device
     *  dedups its own carbon by the inner message's id. */
    data class Carbon(val to: Int?, val gid: Int?, val env: Envelope) : Envelope

    /** Cross-device read marker (megalist A2, wire kind "readmark"). Rides
     *  INSIDE a [Carbon] to my own uin: the thread is the carbon's own
     *  to/gid, [at] is the wall clock of the read in ms. My other devices
     *  drop the badge for that thread, minus whatever arrived after [at].
     *  The island never sees this kind (it is inside the sealed blob) and
     *  the carbon goes out under an ephemeral outer type, so nothing new is
     *  learned and nothing pushes. */
    data class ReadMark(val at: Long) : Envelope

    /** A cross-island request ANSWERED, told to my own other devices (wire
     *  kind "ciack"). Rides INSIDE a [Carbon] to my own uin, under the same
     *  ephemeral outer type the read marker uses, so the island sees nothing
     *  new.
     *
     *  ⚠⚠ Why it exists: a cross-island request is per-INSTALL state. The
     *  conveyor row carries no device id, so every device of the account holds
     *  its own copy, while accepting speaks only to the PEER. Accept on the
     *  desktop and the phone still shows the request - and accepting it there
     *  too is NOT idempotent: it re-fetches the key card and overwrites the
     *  pinned keys, on the one class of peer whose every message is encrypted
     *  to exactly those keys.
     *
     *  [card] rides along on an accept so the other devices copy the TOFU the
     *  accepting device did instead of each doing their own. */
    data class CiAck(
        val uin: Int,
        val host: String,
        val act: String,
        val card: CiCard? = null,
    ) : Envelope

    /** The half of a peer's key card another device needs to hold the same
     *  cross-island contact without fetching anything. */
    data class CiCard(
        val nick: String?,
        val ik: String,
        val sk: String,
        val sik: String?,
        val gender: String?,
        val status: String?,
    )

    /** Room state key hand-off (stage 6 phase 2, wire "gskey", outer type
     *  "skdm"). Carries the AES key a room's sealed identity blob opens
     *  under; [ver] is monotonic with the design doc's equal-version repair
     *  rule. */
    data class GsKey(val gid: Int, val ver: Long, val key: String) : Envelope

    /** Room state key ask-back (wire "gsknack", outer "sknack"): the sender
     *  sees the room's blob and holds no key that opens it. */
    data class GsKnack(val gid: Int) : Envelope

    /** Profile key hand-off (wire "pkey", outer "skdm"). The AES-256-GCM key
     *  the sender's avatar blob is sealed under, handed to ONE contact.
     *
     *  ⚠ The island used to hold this key itself, in `users.avatar_media_key`,
     *  next to the uin and the nickname - so a seized island opened every face
     *  it held. It rides "skdm" because that token already exists and is
     *  already filed as critical: a NEW outer type would itself announce
     *  "this account just changed its picture". See docs/profile-key-design.md. */
    data class PKey(val key: String) : Envelope

    /** Profile key ask-back (wire "pkeyask", outer "sknack"): I can see that
     *  you have a picture and hold no key for it. Only the OWNER can answer,
     *  unlike a room key where any member can. */
    data object PKeyAsk : Envelope

    /** Cross-island call signaling (wire kind "call", spec §5d). Same-island
     *  calls ride the WS as plaintext call_* events; across islands there is
     *  no shared socket, so the SAME signal payload is wrapped here, v=1-sealed
     *  and deposited to the peer's island. [sig] = the WS event type verbatim
     *  (call_offer/call_answer/call_ice/call_end/call_renegotiate*), [cid] =
     *  the call id, [ts] = sender epoch SECONDS (receivers drop stale offers),
     *  [data] = the signal extras (sdp/candidate/media/reason — all strings). */
    data class CallSignal(
        val id: String,
        val sig: String,
        val cid: String,
        val ts: Long,
        val data: Map<String, String>,
    ) : Envelope

    /** Cross-island contact request (wire kind "contactreq", spec §5f). Adding
     *  someone on another island used to be a purely LOCAL act: fetch their
     *  card, write a row, claim success. Nothing was deposited and the peer was
     *  never told — which is why a QR scan announced a request that was never
     *  sent, why §5d cross-island calls stayed gated on a mutual state the
     *  normal flow could not reach, and why §5e had no audience. This envelope
     *  is the missing half of "add": [act] "request" opens a PENDING request on
     *  the peer's side, "accept" makes both sides accepted, "decline" drops the
     *  peer's pending row. [nickname] is the SENDER's display name (so a
     *  request renders before any card fetch), [note] an optional short
     *  greeting, [ts] sender epoch SECONDS. Carries no keys: identity stays
     *  pinned from the open card, never from this envelope. */
    data class ContactRequest(
        val id: String,
        val act: String,
        val ts: Long,
        val nickname: String,
        val note: String? = null,
    ) : Envelope

    /** Cross-island profile refresh (wire kind "profile", spec §5e). A contact
     *  on another island had their name and picture read exactly ONCE, off the
     *  open key card at add time, and nothing ever refreshed them: the
     *  same-island `contact_renamed` broadcast cannot reach a holder on another
     *  island, because the island's contacts table has no host column and so
     *  that holder is not in the audience. The fix is a PUSH, not a poll — the
     *  person who changed their profile deposits this envelope to the islands
     *  of the contacts allowed to see it.
     *
     *  [nickname] is the sender's current display name; [avatarMediaId] +
     *  [avatarMediaKey] name their picture, whose already-encrypted blob is
     *  DEPOSITED to the recipient's own island (§5b `PUT /media/{id}`) before
     *  this envelope goes out — never pulled from the owner's island at draw
     *  time, and the key never published on the open card or the signed record
     *  (both unauthenticated, while `GET /media/{id}` has no auth at all, so
     *  the key IS the access decision). Both absent = no picture / picture
     *  cleared. [ts] is sender epoch SECONDS, used as the stale guard.
     *
     *  Carries NO keys: the pinned identity/signing keys are the
     *  anti-impersonation anchor and this envelope may never write them. */
    data class ProfileUpdate(
        val id: String,
        val ts: Long,
        val nickname: String,
        val avatarMediaId: String? = null,
        val avatarMediaKey: String? = null,
    ) : Envelope

    /** Home-island record self-push (federation gossip B1, wire kind "homerec").
     *  Carries the SENDER's own signed home-island record so a contact caches
     *  where to reach them even after the sender's island dies. [rec] is the
     *  signed record JSON (verified against the sender's pinned signing key on
     *  receipt); never rendered as a message. Cross-client identical. */
    data class HomeRecord(val rec: com.google.gson.JsonObject) : Envelope

    /** Sender-key distribution (wire kind "skdm"): hands one group member the
     *  chain key for a (kid, epoch) so they can derive message keys for the
     *  encrypt-once "gmsg" broadcasts. Rides the per-member ECIES seal via
     *  /messages/group-sealed (envelope_type "skdm"); never rendered. The
     *  receiver binds the kid to the decrypt's authenticated sender.
     *  See RCQ/docs/sender-keys-design.md. */
    data class Skdm(val gid: Int, val kid: String, val epoch: Int, val index: Int, val ck: String) : Envelope

    /** Sender-key recovery request (wire kind "sknack"): I got a gmsg for a kid
     *  I don't hold; the kid's owner re-seals a fresh SKDM. Per-member sealed. */
    data class Sknack(val gid: Int, val kid: String) : Envelope

    /** In-chat bridge share (wire kind "relay_share"): a contact hands you a
     *  relay descriptor to AUGMENT your transport pool (censorship-resistance:
     *  distribute off-config relays peer-to-peer). [relay] is the terse relay
     *  object (ContactRelayStore.relayToJson). Stored as a kind="relay" chat
     *  message + rendered as an Add card; never auto-applied. Cross-client
     *  identical. See RCQ/docs/bridge-sharing-design.md. */
    data class RelayShare(val id: String, val relay: com.google.gson.JsonObject, val note: String? = null) : Envelope

    data class Unknown(val kind: String) : Envelope

    /** Serialize to the exact JSON bytes that get signed and shipped.
     *  Field names match the iOS Envelope CodingKeys. */
    fun toJsonBytes(): ByteArray = when (this) {
        is Text -> JsonObject().apply {
            addProperty("kind", "text")
            addProperty("id", id)
            addProperty("text", text)
            ttl?.let {
                addProperty("ttl", it)
                // Beside the ttl and never on its own — see the interface note.
                ts?.let { sec -> addProperty("ts", sec) }
            }
            replyTo?.let {
                add("reply", JsonObject().apply {
                    addProperty("id", it.id)
                    addProperty("snippet", it.snippet)
                    addProperty("authorName", it.authorName)
                })
            }
            card?.takeIf { it.isNotBlank() }?.let { addProperty("card", it) }
        }.toString().toByteArray(Charsets.UTF_8)
        is Photo -> JsonObject().apply {
            addProperty("kind", "photo")
            addProperty("id", id)
            addProperty("mediaID", mediaId)
            addProperty("mediaKey", mediaKey)
            if (!caption.isNullOrEmpty()) addProperty("caption", caption)
            if (spoiler) addProperty("spoiler", true)
            albumId?.let { addProperty("album", it) }
            ttl?.let {
                addProperty("ttl", it)
                // Beside the ttl and never on its own — see the interface note.
                ts?.let { sec -> addProperty("ts", sec) }
            }
        }.toString().toByteArray(Charsets.UTF_8)
        is Reaction -> JsonObject().apply {
            addProperty("kind", "reaction")
            addProperty("targetID", targetId)
            if (asset != null) addProperty("asset", asset)
        }.toString().toByteArray(Charsets.UTF_8)
        is Delete -> JsonObject().apply {
            addProperty("kind", "delete")
            addProperty("targetID", targetId)
        }.toString().toByteArray(Charsets.UTF_8)
        is Edit -> JsonObject().apply {
            addProperty("kind", "edit")
            addProperty("targetID", targetId)
            addProperty("text", text)
        }.toString().toByteArray(Charsets.UTF_8)
        is ReadReceipt -> JsonObject().apply {
            addProperty("kind", "read")
            add("targetIDs", JsonArray().apply { targetIds.forEach { add(it) } })
        }.toString().toByteArray(Charsets.UTF_8)
        is DeliveredReceipt -> JsonObject().apply {
            addProperty("kind", "delivered")
            add("targetIDs", JsonArray().apply { targetIds.forEach { add(it) } })
        }.toString().toByteArray(Charsets.UTF_8)
        is File -> JsonObject().apply {
            addProperty("kind", "file")
            addProperty("id", id)
            addProperty("mediaID", mediaId)
            addProperty("mediaKey", mediaKey)
            addProperty("fname", fileName)
            addProperty("mime", mime)
            addProperty("size", sizeBytes)
            if (!caption.isNullOrEmpty()) addProperty("caption", caption)
            ttl?.let {
                addProperty("ttl", it)
                // Beside the ttl and never on its own — see the interface note.
                ts?.let { sec -> addProperty("ts", sec) }
            }
        }.toString().toByteArray(Charsets.UTF_8)
        is Voice -> JsonObject().apply {
            addProperty("kind", "voice")
            addProperty("id", id)
            addProperty("mediaID", mediaId)
            addProperty("mediaKey", mediaKey)
            addProperty("durationSec", durationSec)
            ttl?.let {
                addProperty("ttl", it)
                // Beside the ttl and never on its own — see the interface note.
                ts?.let { sec -> addProperty("ts", sec) }
            }
        }.toString().toByteArray(Charsets.UTF_8)
        is Video -> JsonObject().apply {
            addProperty("kind", "video")
            addProperty("id", id)
            addProperty("mediaID", mediaId)
            addProperty("mediaKey", mediaKey)
            addProperty("thumbnailB64", thumbnailB64)
            addProperty("durationSec", durationSec)
            if (!caption.isNullOrEmpty()) addProperty("caption", caption)
            if (spoiler) addProperty("spoiler", true)
            albumId?.let { addProperty("album", it) }
            ttl?.let {
                addProperty("ttl", it)
                // Beside the ttl and never on its own — see the interface note.
                ts?.let { sec -> addProperty("ts", sec) }
            }
        }.toString().toByteArray(Charsets.UTF_8)
        is Location -> JsonObject().apply {
            addProperty("kind", "location")
            addProperty("id", id)
            addProperty("lat", lat)
            addProperty("lng", lng)
            if (!caption.isNullOrEmpty()) addProperty("caption", caption)
            ttl?.let {
                addProperty("ttl", it)
                // Beside the ttl and never on its own — see the interface note.
                ts?.let { sec -> addProperty("ts", sec) }
            }
        }.toString().toByteArray(Charsets.UTF_8)
        is Visit -> JsonObject().apply {
            addProperty("kind", "visit")
            addProperty("at", at)
        }.toString().toByteArray(Charsets.UTF_8)
        is Poll -> JsonObject().apply {
            addProperty("kind", "poll")
            addProperty("id", id)
            addProperty("poll", pollId)
            addProperty("q", question)
            add("opts", JsonArray().apply { options.forEach { add(it) } })
            addProperty("sc", singleChoice)
            addProperty("anon", anonymous)
        }.toString().toByteArray(Charsets.UTF_8)
        is SecureScreen -> JsonObject().apply {
            addProperty("kind", "secscreen")
            addProperty("on", on)
        }.toString().toByteArray(Charsets.UTF_8)
        is ScreenshotTaken -> JsonObject().apply {
            addProperty("kind", "shot")
            addProperty("id", id)
        }.toString().toByteArray(Charsets.UTF_8)
        is PKey -> JsonObject().apply {
            addProperty("kind", "pkey")
            addProperty("key", key)
        }.toString().toByteArray(Charsets.UTF_8)
        is PKeyAsk -> JsonObject().apply {
            addProperty("kind", "pkeyask")
        }.toString().toByteArray(Charsets.UTF_8)
        is GsKey -> JsonObject().apply {
            addProperty("kind", "gskey")
            addProperty("gid", gid)
            addProperty("ver", ver)
            addProperty("key", key)
        }.toString().toByteArray(Charsets.UTF_8)
        is GsKnack -> JsonObject().apply {
            addProperty("kind", "gsknack")
            addProperty("gid", gid)
        }.toString().toByteArray(Charsets.UTF_8)
        is ReadMark -> JsonObject().apply {
            addProperty("kind", "readmark")
            addProperty("at", at)
        }.toString().toByteArray(Charsets.UTF_8)
        is CiAck -> JsonObject().apply {
            addProperty("kind", "ciack")
            addProperty("uin", uin)
            addProperty("host", host)
            addProperty("act", act)
            card?.let { c ->
                add("card", JsonObject().apply {
                    c.nick?.let { addProperty("nick", it) }
                    addProperty("ik", c.ik)
                    addProperty("sk", c.sk)
                    c.sik?.let { addProperty("sik", it) }
                    c.gender?.let { addProperty("gender", it) }
                    c.status?.let { addProperty("status", it) }
                })
            }
        }.toString().toByteArray(Charsets.UTF_8)
        is Carbon -> JsonObject().apply {
            addProperty("kind", "carbon")
            to?.let { addProperty("to", it) }
            gid?.let { addProperty("gid", it) }
            // Nest the inner envelope as a sub-object (parse its own JSON bytes).
            add("env", JsonParser.parseString(String(env.toJsonBytes(), Charsets.UTF_8)).asJsonObject)
        }.toString().toByteArray(Charsets.UTF_8)
        is CallSignal -> JsonObject().apply {
            addProperty("kind", "call")
            addProperty("id", id)
            addProperty("sig", sig)
            addProperty("cid", cid)
            addProperty("ts", ts)
            add("data", JsonObject().apply { data.forEach { (k, v) -> addProperty(k, v) } })
        }.toString().toByteArray(Charsets.UTF_8)
        is ContactRequest -> JsonObject().apply {
            addProperty("kind", "contactreq")
            addProperty("id", id)
            addProperty("ts", ts)
            addProperty("act", act)
            addProperty("nickname", nickname)
            // `note` is OMITTED when there is no greeting, never emitted as
            // JSON null. That is the omit-if-absent rule every other optional
            // on this wire already follows (RelayShare.note two cases below,
            // `ttl?.let`, iOS `encodeIfPresent`, web `if (env.x != null)`), and
            // the §5f block's `"note":"…"|null` is shorthand for "optional",
            // not a demand for an explicit null. iOS and web both omit; this
            // used to emit `"note":null` and was the only client that did.
            // Decoding stays tolerant of BOTH forms (see fromJsonBytes: a
            // JsonNull is not a primitive, so it reads back as null).
            if (!note.isNullOrEmpty()) addProperty("note", note)
        }.toString().toByteArray(Charsets.UTF_8)
        is ProfileUpdate -> JsonObject().apply {
            addProperty("kind", "profile")
            addProperty("id", id)
            addProperty("ts", ts)
            addProperty("nickname", nickname)
            // Same omit-if-absent rule §5f settled on: the picture fields are
            // OMITTED when there is none, never emitted as JSON null (the
            // spec's `"…"|null` is shorthand for "optional"). Decoding stays
            // tolerant of both forms — a JsonNull is not a primitive, so it
            // reads back as null either way.
            //
            // ⚠ ALL-OR-NOTHING, enforced HERE and not only in [profileUpdate].
            // An id without its key names a blob nobody can open (`GET
            // /media/{id}` has no auth, so the key IS the access decision), and
            // web and iOS both collapse a half pair to "no picture" — which,
            // under the snapshot rule, CLEARS the picture the peer holds. Half
            // a pair on the wire would therefore delete our face over there.
            if (!avatarMediaId.isNullOrEmpty() && !avatarMediaKey.isNullOrEmpty()) {
                addProperty("avatar_media_id", avatarMediaId)
                addProperty("avatar_media_key", avatarMediaKey)
            }
        }.toString().toByteArray(Charsets.UTF_8)
        is HomeRecord -> JsonObject().apply {
            addProperty("kind", "homerec")
            add("rec", rec)
        }.toString().toByteArray(Charsets.UTF_8)
        is Skdm -> JsonObject().apply {
            addProperty("kind", "skdm")
            addProperty("gid", gid)
            addProperty("kid", kid)
            addProperty("e", epoch)
            addProperty("i", index)
            addProperty("ck", ck)
        }.toString().toByteArray(Charsets.UTF_8)
        is Sknack -> JsonObject().apply {
            addProperty("kind", "sknack")
            addProperty("gid", gid)
            addProperty("kid", kid)
        }.toString().toByteArray(Charsets.UTF_8)
        is RelayShare -> JsonObject().apply {
            addProperty("kind", "relay_share")
            addProperty("id", id)
            add("relay", relay)
            if (!note.isNullOrEmpty()) addProperty("note", note)
        }.toString().toByteArray(Charsets.UTF_8)
        is Unknown -> JsonObject().apply { addProperty("kind", kind) }
            .toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        /** Seconds between the Unix epoch (1970) and the Apple/Foundation
         *  reference date (2001) — Swift's default JSONEncoder encodes Date
         *  as seconds since 2001, so visit timestamps cross the wire that way. */
        const val APPLE_EPOCH_OFFSET_SEC = 978_307_200.0

        /** How far AHEAD of now a disappearing-message anchor may sit and
         *  still be believed: clock skew between two devices, plus the seconds
         *  a large upload spends between the stamp and the send. */
        const val TTL_ANCHOR_SKEW_MS = 60_000L

        /** ...and how far behind. A year is far past the longest timer any
         *  client offers (7 days), so anything older is not skew. */
        const val TTL_ANCHOR_MAX_AGE_MS = 365L * 24 * 3600 * 1000

        /** An epoch-ms instant a disappearing message's countdown may be
         *  anchored to, or null when it must not be believed.
         *
         *  ⚠ THE VALUES THIS RAILS ARE NOT OURS. A `ts` is a number a peer's
         *  client chose, and the island's deposit stamp is a clock somebody
         *  else runs. An anchor in the FUTURE extends a message's life past
         *  what its own sender promised; an anchor from 1970 (a zero, or a
         *  client sending milliseconds where seconds belong) expires everything
         *  on arrival, which as a way to delete somebody's conversation for
         *  them costs one crafted envelope. Outside the window the caller falls
         *  through to something it trusts more.
         *
         *  Mirrors the web's `sendAnchorMs` (`web-chat/src/lib/disappearing.ts`)
         *  window for window, so the same envelope dies at the same moment on
         *  both clients. */
        fun saneAnchorMs(ms: Long?, nowMs: Long): Long? = ms?.takeIf {
            it > 0 && it <= nowMs + TTL_ANCHOR_SKEW_MS && it >= nowMs - TTL_ANCHOR_MAX_AGE_MS
        }

        /** [saneAnchorMs] applied to the wire's `ts`, which is epoch SECONDS.
         *  Converted BEFORE railing so a millisecond value from a confused
         *  client lands centuries in the future and is refused, rather than
         *  passing as a plausible second count. Overflow lands negative and is
         *  refused by the same test. */
        fun anchorFromTs(ts: Long?, nowMs: Long): Long? =
            saneAnchorMs(ts?.takeIf { it > 0 }?.times(1000L), nowMs)

        /** Build a visit ping stamped at [epochMillis] (epoch ms). */
        fun visit(epochMillis: Long): Visit = Visit(epochMillis / 1000.0 - APPLE_EPOCH_OFFSET_SEC)

        /** The two fields a thread with a disappearing-message timer on it
         *  stamps onto everything it sends: the ttl the sender promises, and
         *  the instant it is counted from.
         *
         *  ⚠ ONE CLOCK READING PER MESSAGE, taken here. The sender's own row
         *  is built from `env.ts` rather than from a second `currentTimeMillis`
         *  at the call site, so both copies of the message die at the same
         *  absolute instant instead of drifting apart by however long the
         *  media upload in between took.
         *
         *  A non-positive ttl is "off": no ttl, and therefore no timestamp. */
        private fun dying(ttl: Int?): Pair<Int?, Long?> {
            val t = ttl?.takeIf { it > 0 } ?: return null to null
            return t to System.currentTimeMillis() / 1000
        }

        fun text(body: String, replyTo: Reply? = null, ttl: Int? = null, card: String? = null): Text {
            val (t, ts) = dying(ttl)
            return Text(id = UUID.randomUUID().toString().uppercase(), text = body, replyTo = replyTo, ttl = t, ts = ts, card = card)
        }

        fun photo(mediaId: String, mediaKey: String, caption: String?, spoiler: Boolean = false, albumId: String? = null, ttl: Int? = null): Photo {
            val (t, ts) = dying(ttl)
            return Photo(UUID.randomUUID().toString().uppercase(), mediaId, mediaKey, caption, spoiler, albumId, t, ts)
        }

        fun reaction(targetId: String, asset: String?): Reaction = Reaction(targetId, asset)

        fun delete(targetId: String): Delete = Delete(targetId)

        fun edit(targetId: String, text: String): Edit = Edit(targetId, text)

        fun readReceipt(targetIds: List<String>): ReadReceipt = ReadReceipt(targetIds)

        fun deliveredReceipt(targetIds: List<String>): DeliveredReceipt = DeliveredReceipt(targetIds)

        fun file(mediaId: String, mediaKey: String, fileName: String, mime: String, sizeBytes: Long, caption: String?, ttl: Int? = null): File {
            val (t, ts) = dying(ttl)
            return File(UUID.randomUUID().toString().uppercase(), mediaId, mediaKey, fileName, mime, sizeBytes, caption, t, ts)
        }

        fun voice(mediaId: String, mediaKey: String, durationSec: Double, ttl: Int? = null): Voice {
            val (t, ts) = dying(ttl)
            return Voice(UUID.randomUUID().toString().uppercase(), mediaId, mediaKey, durationSec, t, ts)
        }

        fun video(mediaId: String, mediaKey: String, thumbnailB64: String, durationSec: Double, caption: String?, spoiler: Boolean = false, albumId: String? = null, ttl: Int? = null): Video {
            val (t, ts) = dying(ttl)
            return Video(UUID.randomUUID().toString().uppercase(), mediaId, mediaKey, thumbnailB64, durationSec, caption, spoiler, albumId, t, ts)
        }

        fun location(lat: Double, lng: Double, caption: String?, ttl: Int? = null): Location {
            val (t, ts) = dying(ttl)
            return Location(UUID.randomUUID().toString().uppercase(), lat, lng, caption, t, ts)
        }

        fun secureScreen(on: Boolean): SecureScreen = SecureScreen(on)

        /** Wrap a call_* WS signal for cross-island deposit, stamped now. */
        fun callSignal(sig: String, cid: String, data: Map<String, String>): CallSignal =
            CallSignal(UUID.randomUUID().toString().uppercase(), sig, cid, System.currentTimeMillis() / 1000, data)

        /** §5f acts. `request` opens a pending request on the peer, `accept`
         *  makes the relationship mutual (the precondition §5d checks),
         *  `decline` drops the peer's pending row. */
        const val ACT_REQUEST = "request"
        const val ACT_ACCEPT = "accept"
        const val ACT_DECLINE = "decline"

        /** Build a §5f contact-request envelope stamped now (epoch SECONDS). */
        fun contactRequest(act: String, nickname: String, note: String? = null): ContactRequest =
            ContactRequest(
                UUID.randomUUID().toString().uppercase(),
                act,
                System.currentTimeMillis() / 1000,
                nickname,
                note?.takeIf { it.isNotBlank() },
            )

        /** Build a §5e profile-refresh envelope stamped now (epoch SECONDS).
         *  Both picture fields or neither — half a picture is no picture. */
        fun profileUpdate(nickname: String, avatarMediaId: String?, avatarMediaKey: String?): ProfileUpdate {
            val id = avatarMediaId?.takeIf { it.isNotEmpty() }
            val key = avatarMediaKey?.takeIf { it.isNotEmpty() }
            val both = id != null && key != null
            return ProfileUpdate(
                UUID.randomUUID().toString().uppercase(),
                System.currentTimeMillis() / 1000,
                nickname,
                if (both) id else null,
                if (both) key else null,
            )
        }

        fun screenshotTaken(): ScreenshotTaken =
            ScreenshotTaken(UUID.randomUUID().toString().uppercase())

        /** Build an in-chat relay share carrying [relay] (the terse relay
         *  object from ContactRelayStore.relayToJson). */
        fun relayShare(relay: com.google.gson.JsonObject, note: String? = null): RelayShare =
            RelayShare(UUID.randomUUID().toString().uppercase(), relay, note)

        fun fromJsonBytes(bytes: ByteArray): Envelope {
            val obj = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
            // `asString` on an object or an array throws, and this runs for
            // EVERY kind — including the §5d/§5e/§5f control envelopes, which
            // arrive from a stranger on another island over an endpoint that is
            // unauthenticated by design. A malformed id is a missing id.
            val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: UUID.randomUUID().toString()
            val reply = obj.getAsJsonObject("reply")?.let {
                Reply(
                    id = it.get("id")?.asString.orEmpty(),
                    snippet = it.get("snippet")?.asString.orEmpty(),
                    authorName = it.get("authorName")?.asString.orEmpty(),
                )
            }
            // Disappearing-message TTL (seconds) the sender packed into the
            // envelope; absent/JSON-null → permanent. Only the content kinds
            // read it — control envelopes ignore any stray value.
            val ttl = obj.get("ttl")?.takeIf { it.isJsonPrimitive }?.asInt
            // The sender's epoch SECONDS, what the countdown is anchored to.
            // Absent from every build older than this one, so the reader must
            // survive its absence; `asLong` on a non-primitive throws, hence the
            // same guard as everything else decoded out of a stranger's bytes.
            // NOT sanity-checked here: this class parses, `Session.expiryFor`
            // decides what to believe.
            val ts = obj.get("ts")?.takeIf { it.isJsonPrimitive }
                ?.let { runCatching { it.asLong }.getOrNull() }
            return when (val kind = obj.get("kind")?.asString) {
                "text" -> Text(
                    id, obj.get("text")?.asString.orEmpty(), reply, ttl, ts,
                    // Bounded on the way in: it arrives from a peer and leaves
                    // as a request header.
                    card = obj.get("card")?.asString?.takeIf { it.isNotBlank() && it.length <= 128 },
                )
                "photo" -> Photo(
                    id = id,
                    mediaId = obj.get("mediaID")?.asString.orEmpty(),
                    mediaKey = obj.get("mediaKey")?.asString.orEmpty(),
                    caption = obj.get("caption")?.asString,
                    spoiler = obj.get("spoiler")?.asBoolean ?: false,
                    albumId = obj.get("album")?.asString,
                    ttl = ttl,
                    ts = ts,
                )
                "reaction" -> Reaction(
                    targetId = obj.get("targetID")?.asString.orEmpty(),
                    asset = obj.get("asset")?.asString,
                )
                "delete" -> Delete(obj.get("targetID")?.asString.orEmpty())
                "edit" -> Edit(
                    targetId = obj.get("targetID")?.asString.orEmpty(),
                    text = obj.get("text")?.asString.orEmpty(),
                )
                "read" -> ReadReceipt(
                    obj.getAsJsonArray("targetIDs")?.mapNotNull { it.asString } ?: emptyList(),
                )
                "delivered" -> DeliveredReceipt(
                    obj.getAsJsonArray("targetIDs")?.mapNotNull { it.asString } ?: emptyList(),
                )
                "file" -> File(
                    id = id,
                    mediaId = obj.get("mediaID")?.asString.orEmpty(),
                    mediaKey = obj.get("mediaKey")?.asString.orEmpty(),
                    fileName = obj.get("fname")?.asString ?: "file",
                    mime = obj.get("mime")?.asString ?: "application/octet-stream",
                    sizeBytes = obj.get("size")?.asLong ?: 0L,
                    caption = obj.get("caption")?.asString,
                    ttl = ttl,
                    ts = ts,
                )
                "voice" -> Voice(
                    id = id,
                    mediaId = obj.get("mediaID")?.asString.orEmpty(),
                    mediaKey = obj.get("mediaKey")?.asString.orEmpty(),
                    durationSec = obj.get("durationSec")?.asDouble ?: 0.0,
                    ttl = ttl,
                    ts = ts,
                )
                "video" -> Video(
                    id = id,
                    mediaId = obj.get("mediaID")?.asString.orEmpty(),
                    mediaKey = obj.get("mediaKey")?.asString.orEmpty(),
                    thumbnailB64 = obj.get("thumbnailB64")?.asString.orEmpty(),
                    durationSec = obj.get("durationSec")?.asDouble ?: 0.0,
                    caption = obj.get("caption")?.asString,
                    spoiler = obj.get("spoiler")?.asBoolean ?: false,
                    albumId = obj.get("album")?.asString,
                    ttl = ttl,
                    ts = ts,
                )
                "location" -> Location(
                    id = id,
                    lat = obj.get("lat")?.asDouble ?: 0.0,
                    lng = obj.get("lng")?.asDouble ?: 0.0,
                    caption = obj.get("caption")?.asString,
                    ttl = ttl,
                    ts = ts,
                )
                "visit" -> Visit(obj.get("at")?.asDouble ?: 0.0)
                "poll" -> Poll(
                    id = id,
                    pollId = obj.get("poll")?.asInt ?: 0,
                    question = obj.get("q")?.asString.orEmpty(),
                    options = obj.getAsJsonArray("opts")?.mapNotNull { it.asString } ?: emptyList(),
                    singleChoice = obj.get("sc")?.asBoolean ?: true,
                    anonymous = obj.get("anon")?.asBoolean ?: false,
                )
                "secscreen" -> SecureScreen(obj.get("on")?.asBoolean ?: false)
                "shot" -> ScreenshotTaken(obj.get("id")?.asString ?: id)
                // §5d. Every field is guarded, like `contactreq` and `profile`
                // below: this envelope arrives from a peer on ANOTHER island
                // over an endpoint that is unauthenticated by design, so a
                // non-string `sig`/`cid` (Gson's `asString` throws
                // UnsupportedOperationException on an object) or a non-numeric
                // `ts` (NumberFormatException) would throw out of the decoder.
                // The queue drain catches per row, so that costs one lost
                // signal rather than the whole drain — but a call signal is
                // exactly the thing that must degrade to "ignored", never to
                // "threw", and the two sibling kinds already do.
                "call" -> CallSignal(
                    id = id,
                    sig = obj.get("sig")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    cid = obj.get("cid")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    ts = obj.get("ts")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: 0L,
                    // `getAsJsonObject` casts, so a `data` that is an array or
                    // a string throws ClassCastException before the filter
                    // below ever runs.
                    data = obj.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.entrySet()
                        ?.mapNotNull { (k, v) -> if (v.isJsonPrimitive) k to v.asString else null }
                        ?.toMap() ?: emptyMap(),
                )
                "contactreq" -> ContactRequest(
                    id = id,
                    act = obj.get("act")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    ts = obj.get("ts")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                    nickname = obj.get("nickname")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    note = obj.get("note")?.takeIf { it.isJsonPrimitive }?.asString,
                )
                "profile" -> ProfileUpdate(
                    id = id,
                    ts = obj.get("ts")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                    nickname = obj.get("nickname")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    avatarMediaId = obj.get("avatar_media_id")?.takeIf { it.isJsonPrimitive }?.asString,
                    avatarMediaKey = obj.get("avatar_media_key")?.takeIf { it.isJsonPrimitive }?.asString,
                )
                "pkey" -> PKey(
                    key = obj.get("key")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                )
                "pkeyask" -> PKeyAsk
                "gskey" -> GsKey(
                    gid = obj.get("gid")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                    ver = obj.get("ver")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                    key = obj.get("key")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                )
                "gsknack" -> GsKnack(
                    gid = obj.get("gid")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                )
                "readmark" -> ReadMark(
                    at = obj.get("at")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                )
                "ciack" -> CiAck(
                    uin = obj.get("uin")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                    host = obj.get("host")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                    act = obj.get("act")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                    card = obj.getAsJsonObject("card")?.let { c ->
                        val ik = c.get("ik")?.takeIf { it.isJsonPrimitive }?.asString
                        val sk = c.get("sk")?.takeIf { it.isJsonPrimitive }?.asString
                        // A card without both halves is not a card: dropping it
                        // leaves the accept to work without one rather than
                        // writing a contact nobody can encrypt to.
                        if (ik.isNullOrBlank() || sk.isNullOrBlank()) null
                        else CiCard(
                            nick = c.get("nick")?.takeIf { it.isJsonPrimitive }?.asString,
                            ik = ik,
                            sk = sk,
                            sik = c.get("sik")?.takeIf { it.isJsonPrimitive }?.asString,
                            gender = c.get("gender")?.takeIf { it.isJsonPrimitive }?.asString,
                            status = c.get("status")?.takeIf { it.isJsonPrimitive }?.asString,
                        )
                    },
                )
                "carbon" -> Carbon(
                    to = obj.get("to")?.asInt,
                    gid = obj.get("gid")?.asInt,
                    env = obj.getAsJsonObject("env")
                        ?.let { fromJsonBytes(it.toString().toByteArray(Charsets.UTF_8)) }
                        ?: Unknown("carbon"),
                )
                "homerec" -> obj.getAsJsonObject("rec")?.let { HomeRecord(it) } ?: Unknown("homerec")
                "skdm" -> Skdm(
                    gid = obj.get("gid")?.asInt ?: 0,
                    kid = obj.get("kid")?.asString.orEmpty(),
                    epoch = obj.get("e")?.asInt ?: 0,
                    index = obj.get("i")?.asInt ?: 0,
                    ck = obj.get("ck")?.asString.orEmpty(),
                )
                "sknack" -> Sknack(
                    gid = obj.get("gid")?.asInt ?: 0,
                    kid = obj.get("kid")?.asString.orEmpty(),
                )
                "relay_share" -> obj.getAsJsonObject("relay")?.let {
                    RelayShare(id, it, obj.get("note")?.asString)
                } ?: Unknown("relay_share")
                else -> Unknown(kind ?: "unknown")
            }
        }
    }
}
