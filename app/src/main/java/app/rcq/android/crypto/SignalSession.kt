package app.rcq.android.crypto

import android.util.Base64
import android.util.Log
import app.rcq.android.net.RcqApi
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle

/**
 * v=2 forward secrecy — libsignal Double Ratchet session layer (steps 4 + 5).
 * Android port of the iOS SignalSession + CryptoService.encryptStage3 /
 * decryptV2. The outer ECIES wrap is shared with v=1 and lives in
 * [SealedSender] ([SealedSender.wrapV2] / [unwrapV2]); this file owns the
 * libsignal half: PQXDH session establishment and per-message ratchet
 * encrypt/decrypt.
 *
 * Everything here is ADDITIVE over v=1. The caller negotiates: v=2 when a
 * session exists or can be established, else v=1 — so a peer without a
 * published bundle (or a transient failure) always falls back to the proven
 * v=1 path, never a broken send.
 *
 * Trust: TOFU, identical to iOS (see [SignalStores.isTrustedIdentity]). Safety
 * numbers / fingerprint verification is a separate future feature.
 */
object SignalSession {
    private const val TAG = "RCQsignal"
    // A Double Ratchet session belongs to ONE PAIR of devices, so the address
    // carries the peer's install, not just their account. Device 1 is the
    // install holding the account's primary bundle; every other install of the
    // same account registers separately and gets an id of its own.
    private const val DEVICE_ID = SealedSender.PRIMARY_DEVICE_ID

    private fun addressOf(uin: Int, deviceId: Int = DEVICE_ID) =
        SignalProtocolAddress(uin.toString(), deviceId)

    /** A session record we can ENCRYPT on. A record whose current state was
     *  archived still opens what is already in flight, but has no sending
     *  chain and has to be rebuilt before the next send. */
    private fun usableSession(stores: SignalStores, addr: SignalProtocolAddress): Boolean =
        stores.loadSession(addr)?.hasSenderChain() == true

    // ── STEP 4: session establishment (X3DH / PQXDH) ─────────────────

    /**
     * Idempotent session establishment with device [deviceId] of [uin]. If a
     * libsignal session already exists locally, returns immediately. Otherwise
     * fetches that device's pre-key bundle (consuming one one-time pre-key on
     * the server) and runs [SessionBuilder.process] to seed a SessionRecord.
     * Suspends on the HTTP fetch; call once before the first v=2 encrypt to a
     * device.
     *
     * A SECONDARY device is also fetched for when a session already exists but
     * its OUTER key does not: a session seeded from that device's own
     * PreKeySignalMessage never went past a bundle, and without the outer key
     * there is nothing to seal its copy to.
     *
     * A null [deviceId] means the caller could not enumerate the peer's
     * installs (an island with no device registry, or a lookup that failed):
     * the account's single published bundle is fetched by the legacy route and
     * addressed as the primary, exactly as before per-device bundles existed.
     * A named device always goes through the per-device route, the primary
     * included — the legacy route is deliberately 404 for an account that has
     * a QR-linked web session, so that a sender too old to fan out drops to
     * v=1 instead of sealing to one install of two.
     *
     * Returns true if a usable session exists afterwards, false if the peer
     * has no bundle / the fetch failed — in which case the caller stays on
     * v=1. Never throws for the "no bundle" case; the API layer's error is
     * caught and reported as false.
     */
    suspend fun ensureSession(
        stores: SignalStores,
        api: RcqApi,
        uin: Int,
        deviceId: Int? = null,
    ): Boolean {
        val target = deviceId ?: DEVICE_ID
        val addr = addressOf(uin, target)
        // Fast path: existing session. The lock keeps the ratchet read-modify-
        // write atomic against a concurrent encrypt/decrypt on the same account
        // (see [encrypt]/[decrypt]); it is never held across the network fetch.
        val haveSession = synchronized(stores) { usableSession(stores, addr) }
        val needsOuterKey = target != DEVICE_ID && stores.peerDeviceOuterKey(uin, target) == null
        if (haveSession && !needsOuterKey) return true
        val bundle = try {
            fetchBundle(api, uin, deviceId)
        } catch (e: Exception) {
            // No published bundle (peer hasn't bootstrapped v=2) or a transient
            // error: stay on v=1 / leave this device out of the fan-out.
            Log.d(TAG, "no v2 bundle for $uin/$deviceId (${e.javaClass.simpleName}); using v1")
            return false
        }
        if (bundle.uin != uin) {
            // The answer names somebody else (or nobody, which reads as 0).
            // A session seeded under that name would take every message to
            // this peer with it, so refuse and let the caller use v=1.
            Log.w(TAG, "bundle for $uin/$deviceId came back naming ${bundle.uin}; ignoring")
            return false
        }
        rememberOuterKey(stores, bundle, target)
        if (needsOuterKey && stores.peerDeviceOuterKey(uin, target) == null) {
            // An island too old to publish per-device outer keys. Guessing the
            // account key here is what makes a copy this install can never
            // open, so the device drops out of the fan-out instead.
            Log.w(TAG, "device $uin/$target published no sealed-sender key; skipping it")
            return false
        }
        if (haveSession) return true
        return establishSession(stores, bundle, target)
    }

    private suspend fun fetchBundle(api: RcqApi, uin: Int, deviceId: Int?): RcqApi.PeerBundle {
        if (deviceId == null) return api.fetchPeerBundle(uin)
        return try {
            api.fetchPeerDeviceBundle(uin, deviceId)
        } catch (e: Exception) {
            // The per-device route is the one to ask — the legacy route is
            // deliberately 404 for an account that has a second install — but
            // an island too old to have it 404s every device. Only the primary
            // has somewhere to fall back to; a secondary that island does not
            // know about has no bundle there at all.
            if (deviceId != DEVICE_ID) throw e
            api.fetchPeerBundle(uin)
        }
    }

    /** Keep the OUTER (sealed-sender) key of a peer's secondary device. Device
     *  1 is not kept: its outer key is the account identity key the caller
     *  already holds for v=1. */
    private fun rememberOuterKey(stores: SignalStores, bundle: RcqApi.PeerBundle, deviceId: Int) {
        if (deviceId == DEVICE_ID) return
        val outer = bundle.sealed_sender_pub?.let { runCatching { b64d(it) }.getOrNull() } ?: return
        if (outer.isEmpty()) return
        stores.storePeerDeviceOuterKey(bundle.uin, deviceId, outer)
    }

    /**
     * Seed a session into [stores] from an already-fetched [bundle] (no
     * network). Split out from [ensureSession] so it can be driven directly
     * in tests with a locally-built bundle, and as a seam for a future
     * session warm-up. Returns true if a session exists afterwards.
     */
    fun establishSession(
        stores: SignalStores,
        bundle: RcqApi.PeerBundle,
        deviceId: Int = DEVICE_ID,
        force: Boolean = false,
    ): Boolean {
        val addr = addressOf(bundle.uin, deviceId)
        return try {
            val preKeyBundle = buildPreKeyBundle(bundle, deviceId)
            synchronized(stores) {
                // Another sender may have established it while we fetched.
                // [force] is the silence probe replacing a session it has
                // decided is dead — the whole point there is to run the
                // handshake again over one that still looks usable.
                if (!force && usableSession(stores, addr)) return true
                // SessionBuilder takes the four non-Kyber stores (the initiator
                // never consults its own Kyber store — it uses the peer's Kyber
                // pub from the bundle).
                SessionBuilder(stores, stores, stores, stores, addr).process(preKeyBundle)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "session establish failed for ${bundle.uin}: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** What a silence probe found when it re-read a peer device's bundle. */
    enum class ProbeResult {
        /** The bundle could not be read — nothing was touched. */
        UNREACHABLE,

        /** The peer still publishes the identity our session was built on, so
         *  the session is fine and their silence means something else (they
         *  are offline, or asleep). Only the outer key was refreshed. */
        UNCHANGED,

        /** The identity behind that device changed — the install we shared a
         *  ratchet with is gone — and the session was rebuilt. */
        REBUILT,
    }

    /**
     * Re-read [uin]/[deviceId]'s bundle and rebuild the session ONLY if the
     * identity behind it actually changed.
     *
     * ⚠ Deliberately NOT an unconditional rebuild. Dropping a session destroys
     * our RECEIVING chains too: anything that device already sealed to it — a
     * message in flight, a whole offline backlog waiting in the queue — stops
     * decrypting, and the drain acks those rows away. Doing that on a hunch
     * every time a peer is quiet turns a probe meant to RECOVER messages into
     * one that loses them, and a peer whose client answers in v=1 (every phone
     * that has not flipped v2 outbound yet) is quiet by that definition
     * forever. A changed identity key is the one signal that the session is
     * genuinely dead, and it is exactly what a replaced install publishes.
     *
     * The residual case — a dead session behind an UNCHANGED identity — is not
     * silently accepted: it is logged, and the peer's next message re-keys
     * this side through its own prekey material.
     */
    suspend fun probeSession(
        stores: SignalStores,
        api: RcqApi,
        uin: Int,
        deviceId: Int,
    ): ProbeResult {
        val bundle = try {
            fetchBundle(api, uin, deviceId)
        } catch (e: Exception) {
            Log.w(TAG, "silence probe: no bundle for $uin/$deviceId (${e.javaClass.simpleName})")
            return ProbeResult.UNREACHABLE
        }
        if (bundle.uin != uin) return ProbeResult.UNREACHABLE
        // The outer (sealed-sender) key is refreshed either way: it is the one
        // thing that can go stale without the ratchet noticing, and replacing
        // it costs the peer nothing.
        rememberOuterKey(stores, bundle, deviceId)
        val addr = addressOf(uin, deviceId)
        val pinned = synchronized(stores) { stores.getIdentity(addr) }
        val published = runCatching { IdentityKey(b64d(bundle.signal_identity_key)) }.getOrNull()
            ?: return ProbeResult.UNREACHABLE
        if (pinned != null && pinned == published) {
            Log.i(TAG, "silence probe: $uin/$deviceId unchanged; session kept")
            return ProbeResult.UNCHANGED
        }
        // ⚠ NOT deleteSession + establish. libsignal's own handshake ARCHIVES
        // the session it replaces (SessionRecord.promote_state), and archived
        // states still decrypt: whatever that device sealed to the old session
        // before it vanished — a message in flight, a queued backlog — keeps
        // opening. Deleting first would throw exactly that away, which is the
        // loss this probe exists to prevent. `force` is what makes the
        // handshake run over a session that still looks usable.
        Log.w(TAG, "silence probe: identity changed behind $uin/$deviceId — fresh X3DH")
        return if (establishSession(stores, bundle, deviceId, force = true)) {
            ProbeResult.REBUILT
        } else {
            ProbeResult.UNREACHABLE
        }
    }

    private fun buildPreKeyBundle(b: RcqApi.PeerBundle, deviceId: Int): PreKeyBundle {
        val identityKey = IdentityKey(b64d(b.signal_identity_key))
        val signedPub = ECPublicKey(b64d(b.signed_prekey.publicKey))
        val signedSig = b64d(b.signed_prekey.signature)
        val kyberPub = KEMPublicKey(b64d(b.kyber_prekey.publicKey))
        val kyberSig = b64d(b.kyber_prekey.signature)
        val opk = b.one_time_prekey
        return if (opk != null) {
            PreKeyBundle(
                b.registration_id,
                deviceId,
                opk.id,
                ECPublicKey(b64d(opk.publicKey)),
                b.signed_prekey.id,
                signedPub,
                signedSig,
                identityKey,
                b.kyber_prekey.id,
                kyberPub,
                kyberSig,
            )
        } else {
            // Pool exhausted server-side — PQXDH still proceeds without the
            // one-time pre-key (we lose one contributory secret). preKey is
            // nullable in libsignal when preKeyId == NULL_PRE_KEY_ID.
            Log.i(TAG, "bundle for ${b.uin} had no OPK; establishing without one")
            PreKeyBundle(
                b.registration_id,
                deviceId,
                PreKeyBundle.NULL_PRE_KEY_ID,
                null,
                b.signed_prekey.id,
                signedPub,
                signedSig,
                identityKey,
                b.kyber_prekey.id,
                kyberPub,
                kyberSig,
            )
        }
    }

    // ── STEP 5: v=2 send / recv ──────────────────────────────────────

    /**
     * Encrypt [envelope] to [recipientUin] over the established libsignal
     * session, then wrap the ratchet ciphertext in the v=2 outer ECIES
     * addressed to the recipient DEVICE's outer key: [recipientMessagingPub]
     * (the peer's X25519 messaging identity key — the same key v=1 uses;
     * distinct from the libsignal identity key inside the ratchet) for the
     * primary, and the key that install published for itself for a secondary.
     * [ensureSession] must have run first.
     *
     * Throws when a secondary's outer key is unknown. Sealing to the account
     * key instead would hand that install a copy it can never open, which from
     * the outside is exactly the silent loss the fan-out exists to stop — so
     * the device is skipped, never guessed at.
     *
     * libsignal emits a self-contained PreKeySignalMessage ("prekey") on
     * every send until the peer replies, then switches to SignalMessage
     * ("signal"). That property is what makes "encrypt once, resend the
     * identical bytes on retry" safe: a lost first message is always a
     * prekey, so any later retry still carries full session-init material.
     */
    fun encrypt(
        stores: SignalStores,
        envelope: Envelope,
        recipientMessagingPub: ByteArray,
        recipientUin: Int,
        ownUin: Int,
        recipientDeviceId: Int = DEVICE_ID,
        ownDeviceId: Int = DEVICE_ID,
    ): String {
        val addr = addressOf(recipientUin, recipientDeviceId)
        val outerPub =
            if (recipientDeviceId == DEVICE_ID) recipientMessagingPub
            else stores.peerDeviceOuterKey(recipientUin, recipientDeviceId)
                ?: error("no sealed-sender key for $recipientUin/$recipientDeviceId")
        val (kind, libsignalBytes) = synchronized(stores) {
            val cipher = SessionCipher(stores, stores, stores, stores, stores, addr)
            val ciphertext: CiphertextMessage = cipher.encrypt(envelope.toJsonBytes())
            val k = when (ciphertext.type) {
                CiphertextMessage.PREKEY_TYPE -> "prekey"
                CiphertextMessage.WHISPER_TYPE -> "signal"
                else -> error("unexpected libsignal ciphertext type ${ciphertext.type}")
            }
            k to ciphertext.serialize()
        }
        return SealedSender.wrapV2(libsignalBytes, kind, outerPub, ownUin, ownDeviceId)
    }

    /**
     * Decrypt a v=2 payload: peel the outer ECIES, then run the inner
     * libsignal ciphertext through the Double Ratchet. A "prekey" message
     * auto-establishes the inbound session (no server round-trip), so the
     * receive path needs no [ensureSession] call. Synchronous (no network).
     */
    fun decrypt(
        stores: SignalStores,
        payloadB64: String,
        ownIdentityPriv: ByteArray,
        ownIdentityPub: ByteArray,
    ): SealedSender.Decrypted {
        val u = SealedSender.unwrapV2(payloadB64, ownIdentityPriv, ownIdentityPub)
        val addr = addressOf(u.senderUin, u.senderDeviceId)
        val plain: ByteArray = synchronized(stores) {
            val cipher = SessionCipher(stores, stores, stores, stores, stores, addr)
            try {
                when (u.kind) {
                    "prekey" -> cipher.decrypt(PreKeySignalMessage(u.msgBytes))
                    "signal" -> cipher.decrypt(SignalMessage(u.msgBytes))
                    else -> throw SealedSender.DecryptException("unknown v2 kind ${u.kind}")
                }
            } catch (e: DuplicateMessageException) {
                // Same ciphertext delivered twice (live socket + offline queue):
                // the ratchet already consumed it. Benign, propagate quietly so
                // the caller skips re-storing it.
                throw e
            } catch (e: InvalidKeyIdException) {
                // A prekey message references a one-time/signed pre-key that is
                // gone from our store (consumed or rotated), so this half-built
                // session can't complete. Drop it so the next PreKeySignalMessage
                // rebuilds cleanly. Mirrors iOS decryptV2's missingSignedPreKey
                // handling.
                Log.w(TAG, "v2 decrypt InvalidKeyId from ${u.senderUin}/${u.senderDeviceId}; dropping session to allow rebuild")
                stores.deleteSession(addr)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "v2 decrypt failed from ${u.senderUin}/${u.senderDeviceId} (kind=${u.kind}): ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
        }
        // v=2 carries no separate signing pub — the ratchet authenticates the
        // sender — so report an empty one (ingest never reads it).
        return SealedSender.Decrypted(u.senderUin, Envelope.fromJsonBytes(plain), ByteArray(0), senderDeviceId = u.senderDeviceId)
    }

    private fun b64d(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    // ── safety numbers (key-fingerprint verification) ────────────────
    // Closes the server-MITM gap: TOFU accepts whatever identity key the
    // server hands us, so a malicious server could substitute a key. The
    // safety number lets two users compare their pinned identity keys over a
    // trusted out-of-band channel; if the numbers match, no key was swapped.
    // Standard Signal iteration count; the version is fixed (Android is the
    // first client with this, so it sets the convention) and must match on
    // both ends.
    private const val FINGERPRINT_ITERATIONS = 5200
    private const val FINGERPRINT_VERSION = 2

    /**
     * The 60-digit safety number for the conversation between [ownUin] (with
     * libsignal identity [ownIdentity]) and [peerUin] (with [peerIdentity]).
     * Both devices compute the SAME number when each passes its own (self,
     * peer) in the (local, remote) slots — the generator orders the two halves
     * canonically. Formatted in groups of five for reading aloud.
     */
    fun safetyNumber(
        ownUin: Int,
        ownIdentity: IdentityKey,
        peerUin: Int,
        peerIdentity: IdentityKey,
    ): String {
        val fp = NumericFingerprintGenerator(FINGERPRINT_ITERATIONS).createFor(
            FINGERPRINT_VERSION,
            ownUin.toString().toByteArray(Charsets.UTF_8),
            ownIdentity,
            peerUin.toString().toByteArray(Charsets.UTF_8),
            peerIdentity,
        )
        return fp.displayableFingerprint.displayText.chunked(5).joinToString(" ")
    }

    /** Our own libsignal identity public key, or null if not bootstrapped. */
    fun ownIdentity(stores: SignalStores): IdentityKey? =
        if (stores.hasLocalIdentity()) stores.identityKeyPair.publicKey else null

    /** The peer's PINNED libsignal identity (the one our sessions actually
     *  use), or null if we have no session/identity for them yet. */
    fun pinnedIdentity(stores: SignalStores, peerUin: Int): IdentityKey? =
        synchronized(stores) { stores.getIdentity(addressOf(peerUin)) }
}
