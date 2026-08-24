package app.rcq.android.push

import android.content.Context
import app.rcq.android.R
import app.rcq.android.crypto.Envelope
import app.rcq.android.crypto.SealedSender
import app.rcq.android.data.LocalStores
import app.rcq.android.data.SecureStore
import app.rcq.android.model.Contact
import app.rcq.android.net.CrossIslandStore
import app.rcq.android.security.PanicPinService
import com.google.gson.Gson
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

/**
 * Opens the sealed envelope a wake carries, so the notification can name the
 * sender, preview the message, send the tap to the right thread and honour
 * "mentions only" — the four things a generic "New message" cannot do. The
 * server has always shipped the ciphertext in the wake's `env` (same one APNs
 * carries for the iOS NSE); this is the Android side of that.
 *
 * ⚠ ONLY v=1 envelopes are opened, and that limit IS the design. A v=2
 * (libsignal) decrypt advances the Double Ratchet, and a sender-keys `gmsg`
 * decrypt advances the group chain — doing either out of band would leave the
 * copy that arrives over the live socket (or the offline queue) undecryptable,
 * and [app.rcq.android.Session] swallows the resulting DuplicateMessageException
 * on purpose, so the message would vanish with no bubble and no log line. iOS
 * can decrypt everything only because its NSE hands the plaintext to the app
 * through PushDecryptCache; until Android has that same handoff, anything but
 * v=1 keeps the generic wake. v=1 itself is pure ECDH + HKDF + AEAD + Ed25519
 * verify over stored-state-free inputs, so opening it twice costs nothing —
 * Session already re-ingests held v=1 payloads when a cross-island request is
 * accepted.
 *
 * Everything here must survive a HEADLESS start: a wake can land in a process
 * with no Session, no bound account and no roster in memory, so every read goes
 * straight to per-account storage instead of through the live stores.
 */
internal object PushEnvelope {

    /** What a successfully opened wake tells the notification. */
    data class Opened(
        val senderUin: Int,
        /** Roster nickname, or "#<uin>" when we hold no name for them. */
        val senderName: String,
        /** Message preview, or null for a control envelope that must raise no
         *  banner at all (read receipt, reaction, delete, sender-key admin …). */
        val preview: String?,
        val mentionsMe: Boolean,
        /** A cross-island sender whose request we have NOT accepted. Their
         *  message goes to the quarantine, not to a thread, so the wake must
         *  stay anonymous and must not offer to open a chat that would send
         *  the reply to the wrong island. */
        val quarantined: Boolean,
    )

    /**
     * Decrypt [envB64] with [accountId]'s identity key. Returns null whenever
     * the wake has to stay GENERIC: a non-v=1 envelope, a locked app, a missing
     * key, or any decrypt failure (a wake we cannot read is still a wake worth
     * showing). An envelope that opened but carries nothing to show comes back
     * with a null [Opened.preview] instead — the difference decides whether the
     * caller falls back to the generic wake or stays silent.
     */
    fun open(ctx: Context, accountId: String, envB64: String): Opened? {
        // Panic PIN: while the app is locked its own history is unreadable, so
        // putting the plaintext on the lock screen would walk straight around
        // the lock. Stay generic until the user unlocks.
        //
        // ⚠⚠ A DECOY session is not locked, and that is the hole. Every read
        // below goes to per-account storage BY ACCOUNT ID ([nameFor] reads the
        // cached roster, [CrossIslandStore.getFor] the cross-island one), so it
        // bypasses the decoy's namespace binding entirely: a wake arriving
        // while someone is being coerced would put a real contact's name and a
        // line of their message on the screen of a phone that is supposed to
        // hold nobody. A generic "new message" is exactly what an ordinary
        // account looks like, so staying generic costs the decoy nothing.
        if (PanicPinService.isLocked || PanicPinService.inDecoySession) return null

        val store = SecureStore(ctx, accountId)
        val me = store.uin ?: return null
        val dec = decrypt(ctx, accountId, envB64) ?: return null

        val host = dec.senderHost
        val quarantined = host != null &&
            !host.equals(store.serverHost ?: "", ignoreCase = true) &&
            CrossIslandStore.getFor(ctx, accountId, dec.senderUin, host) == null

        // Our own message, carboned to this device from another one of ours, is
        // reported as an opened envelope with NOTHING TO SHOW rather than as a
        // failure to open — returning null here would send the caller down the
        // generic-wake fallback and buzz the phone about the message the user
        // just sent from their other device.
        val preview = if (dec.senderUin == me) null else preview(ctx, dec.envelope)
        return Opened(
            senderUin = dec.senderUin,
            senderName = nameFor(ctx, accountId, dec.senderUin, host) ?: "#${dec.senderUin}",
            preview = preview,
            mentionsMe = preview != null && mentionsMe(preview, me, store.nickname),
            quarantined = quarantined,
        )
    }

    /** v=1 decrypt with [accountId]'s identity key, or null when the envelope
     *  is not v=1, the account has no key, or it does not open for us. The
     *  panic-PIN / decoy rules are NOT here: each caller states its own, because
     *  they differ (see [openCall]). */
    private fun decrypt(ctx: Context, accountId: String, envB64: String): SealedSender.Decrypted? {
        if (SealedSender.wireVersion(envB64) != 1) return null
        val priv = SecureStore(ctx, accountId).identityPrivate ?: return null
        return runCatching {
            SealedSender.decryptV1(
                envB64,
                priv,
                X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded,
            )
        }.getOrNull()
    }

    /** What a §5d cross-island CALL wake carries once opened. */
    data class Call(
        val senderUin: Int,
        val senderHost: String,
        /** The caller's name from this account's cross-island roster, or null
         *  when we hold none — the ringer then falls back to a neutral label
         *  rather than inventing one. */
        val senderName: String?,
        /** The §5d signal verbatim: call_offer / call_end / … */
        val sig: String,
        val callId: String,
        /** "audio" or "video". */
        val media: String,
        val sdp: String,
        /** Sender epoch SECONDS — a wake replayed hours later must not ring. */
        val ts: Long,
        /** The sender is an ACCEPTED cross-island contact of this account.
         *  Only they may make this phone ring (§5d); a stranger's deposit is a
         *  request, not a call. */
        val accepted: Boolean,
    )

    /**
     * Open the sealed envelope a §5d CALL wake carries (`envType: "call"`).
     *
     * The recipient's island cannot see who is calling, the call id, audio vs
     * video or the SDP — all of it is inside this envelope — so the wake it
     * sends is anonymous by construction and the ring has nothing to show
     * until this runs. Returns null when the wake must stay generic: no `env`
     * (the island drops one too large for a push), a non-v=1 envelope, a
     * decrypt failure, or an envelope that is not a call signal at all.
     *
     * ⚠ Deliberately NOT gated on [PanicPinService.isLocked], unlike [open]:
     * nothing this returns goes on the lock screen by itself. The call id,
     * media and SDP are wire plumbing, and whether the caller's NAME is shown
     * is the ringer's decision (it stays neutral while locked). A locked phone
     * must still be able to ring — otherwise the lock silently forwards every
     * call to voicemail. A DECOY session still gets nothing: a phone that is
     * supposed to hold nobody must not announce a real contact.
     */
    fun openCall(ctx: Context, accountId: String, envB64: String): Call? {
        if (PanicPinService.inDecoySession) return null
        val dec = decrypt(ctx, accountId, envB64) ?: return null
        val cs = dec.envelope as? Envelope.CallSignal ?: return null
        // §5d exists only across islands: a same-island call rides the WS as
        // plaintext call_* events and never arrives as an envelope.
        val host = dec.senderHost ?: return null
        val store = SecureStore(ctx, accountId)
        val ownHosts = setOf(
            store.serverHost ?: app.rcq.android.net.RcqApi.DEFAULT_HOST,
            app.rcq.android.net.RelayConfigStore.frontHost,
        ).filter { it.isNotBlank() }
        if (ownHosts.any { it.equals(host, ignoreCase = true) }) return null
        val ci = CrossIslandStore.getFor(ctx, accountId, dec.senderUin, host)
        return Call(
            senderUin = dec.senderUin,
            senderHost = host,
            senderName = ci?.nickname?.takeIf { it.isNotBlank() },
            sig = cs.sig,
            callId = cs.cid,
            media = cs.data["media"] ?: "video",
            sdp = cs.data["sdp"].orEmpty(),
            ts = cs.ts,
            accepted = ci != null,
        )
    }

    /** One line of the message for the banner, or null when the envelope
     *  carries no new message at all. Mirrors [app.rcq.android.Session]'s
     *  in-app previews, localized rather than hardcoded English (the iOS NSE
     *  hardcodes; there is no reason to copy that here). */
    private fun preview(ctx: Context, env: Envelope): String? = when (env) {
        is Envelope.Text -> env.text.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.kind_message)
        is Envelope.Photo -> env.caption?.takeIf { it.isNotBlank() }
            ?: "📷 " + ctx.getString(R.string.kind_photo)
        is Envelope.Video -> env.caption?.takeIf { it.isNotBlank() }
            ?: "🎬 " + ctx.getString(R.string.kind_video)
        is Envelope.Voice -> "🎤 " + ctx.getString(R.string.kind_voice)
        is Envelope.File -> "📎 " + (env.fileName.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.kind_file))
        is Envelope.Location -> "📍 " + ctx.getString(R.string.kind_location)
        is Envelope.Poll -> "📊 " + (env.question.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.kind_message))
        is Envelope.ScreenshotTaken -> "📸 " + ctx.getString(R.string.push_kind_screenshot)
        is Envelope.RelayShare -> "🛡️ " + ctx.getString(R.string.push_kind_relay_share)
        // Control envelopes: receipts, reactions, edits, deletes, presence
        // pings, secure-screen sync, call signaling, federation records and
        // sender-key admin. None of them is a new message, and waking the user
        // for one is the "ложные уведомления, новых сообщений нет" report.
        //
        // A §5d CallSignal is here on purpose too: it must never raise a
        // MESSAGE banner. It gets a ring instead, through [openCall] — a call
        // wake arrives with `envType: "call"` and never reaches this function.
        else -> null
    }

    /** Same rule the in-app gate uses ([app.rcq.android.Session.bodyMentionsMe]):
     *  our numeric handle, or our nickname after an @. Deliberately wider than
     *  the iOS NSE, which only ever looks for "#<uin>" and therefore lets an
     *  @nick mention through as silence. */
    private fun mentionsMe(body: String, me: Int, nickname: String?): Boolean {
        if (body.isEmpty()) return false
        if (body.contains("#$me")) return true
        val nick = nickname?.takeIf { it.isNotBlank() } ?: return false
        return body.contains("@$nick", ignoreCase = true)
    }

    /** The sender's display name from whatever this account has on disk: the
     *  cached roster first, then the cross-island store (a peer on another
     *  island is never in the roster cache). Null → the caller falls back to
     *  "#<uin>", which is what the app shows for an unknown handle too.
     *
     *  Internal rather than private since 2026-08-24: the CALL wake needs it
     *  too. The island used to put the caller's `nickname` in the call push,
     *  which handed the distributor (a Cloudflare edge, for push.rcq.app) the
     *  caller's name beside the callee's number; it sends only `from_uin` now
     *  and the name is looked up here, exactly as the message wake already
     *  did. Headless-safe by construction, like everything else in here. */
    internal fun nameFor(ctx: Context, accountId: String, uin: Int, host: String?): String? {
        LocalStores.cachedContactsJsonFor(accountId)?.let { json ->
            runCatching { Gson().fromJson(json, Array<Contact>::class.java) }
                .getOrNull()
                ?.firstOrNull { it.uin == uin }
                ?.nickname
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        if (host != null) {
            CrossIslandStore.getFor(ctx, accountId, uin, host)
                ?.nickname
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return null
    }
}
