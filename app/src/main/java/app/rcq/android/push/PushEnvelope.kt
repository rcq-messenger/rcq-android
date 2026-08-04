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
        if (PanicPinService.isLocked) return null
        if (SealedSender.wireVersion(envB64) != 1) return null

        val store = SecureStore(ctx, accountId)
        val priv = store.identityPrivate ?: return null
        val me = store.uin ?: return null
        val dec = runCatching {
            SealedSender.decryptV1(
                envB64,
                priv,
                X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded,
            )
        }.getOrNull() ?: return null

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
     *  "#<uin>", which is what the app shows for an unknown handle too. */
    private fun nameFor(ctx: Context, accountId: String, uin: Int, host: String?): String? {
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
