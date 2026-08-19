package app.rcq.android.crypto

import android.util.Base64
import android.util.Log
import app.rcq.android.net.RcqApi
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * Idempotent libsignal identity bootstrap (v=2 step 3) — Android port of iOS
 * SignalIdentityBootstrap. Ensures the local libsignal identity, signed
 * pre-key, Kyber pre-key (PQXDH) and one-time pre-key pool exist, and the
 * matching PUBLIC material is uploaded to the server so peers can start v=2
 * sessions with us. Best-effort: if anything throws, the caller swallows it
 * and the encrypt path simply stays on v=1.
 *
 * A Double Ratchet session belongs to one PAIR of devices, so an account with
 * two installs needs two published bundles: one install owns the primary slot
 * (POST /keys/bundle, device 1) and the others register separately (POST
 * /keys/devices) for an id of their own. Which one this install is gets
 * resolved once and persisted next to the protocol stores — with one standing
 * exception: an install bootstrapping WITHOUT a local identity always claims
 * the primary slot, and whoever held it steps aside (see [freshBootstrap]).
 */
object SignalBootstrap {
    private const val TAG = "RCQsignal"
    private const val TARGET_OPK = 100
    private const val TOPUP_THRESHOLD = 25
    // libsignal pre-key ids: 31-bit positive, matching iOS.
    private const val MAX_ID = 0x7FFFFFFF
    // Shown next to this install in the account's device list.
    private const val DEVICE_LABEL = "Android"

    suspend fun ensureBootstrapped(
        stores: SignalStores,
        api: RcqApi,
        ownUin: Int,
        sealedSenderPub: ByteArray,
    ) {
        val localUin = stores.localUin()
        if (localUin != null) {
            if (localUin != ownUin) {
                // UIN drift (local stores survived a server-side wipe + new UIN).
                stores.wipe()
            } else {
                adoptDeviceId(stores, api, b64(sealedSenderPub))
                topUpIfNeeded(stores, api, b64(sealedSenderPub))
                return
            }
        }
        freshBootstrap(stores, api, ownUin, b64(sealedSenderPub))
    }

    /**
     * First bootstrap on an install with no local key material: a new install,
     * a reinstall, a restore on another phone.
     *
     * It CLAIMS THE PRIMARY SLOT, even when the account already has a bundle
     * published there. An install with no local identity holds no sessions and
     * so has nothing to lose by taking the slot, and what it usually finds
     * there is its OWN dead predecessor — the private half went with the app
     * data, and every sender too old to fan out keeps addressing that corpse
     * forever, with no endpoint to retire it.
     *
     * A live sibling that held the slot is not harmed either: it sees on its
     * next status check that the published identity is no longer its own and
     * re-registers itself as a secondary ([topUpIfNeeded] → [registerAsSecondary]).
     * One step, and it heals in the direction that keeps the install the user
     * has just set up reachable by everyone, old senders included.
     */
    private suspend fun freshBootstrap(
        stores: SignalStores,
        api: RcqApi,
        ownUin: Int,
        sealedSenderPub: String,
    ) {
        val identity = IdentityKeyPair.generate()
        val registrationId = (1..16380).random()           // 14-bit, backend-enforced range
        stores.storeLocalIdentity(ownUin, identity, registrationId)
        val body = mintKeys(stores, identity, registrationId)
        val assigned = claimSlot(api, body, sealedSenderPub, Slot.PRIMARY)
        stores.storeDeviceId(assigned)
        stores.assignPreKeyPool(body.one_time_prekeys.map { it.id }, assigned)
    }

    /**
     * Which libsignal device an install that already holds local key material
     * is. Runs once — the answer is persisted — and is the repair for every
     * install in the field, all of which published themselves as device 1.
     */
    private suspend fun adoptDeviceId(stores: SignalStores, api: RcqApi, sealedSenderPub: String) {
        if (stores.deviceId() != null) return
        val status = api.keysStatus()
        // Nothing published for this account at all: [topUpIfNeeded] rebuilds
        // from scratch and claims the primary slot on the way.
        if (!status.has_bundle) return
        val identity = stores.getIdentityKeyPair()
        if (holdsPrimary(status, b64(identity.publicKey.serialize()))) {
            stores.storeDeviceId(SealedSender.PRIMARY_DEVICE_ID)
            return
        }
        // Another install of this account holds the primary slot. Publishing
        // over it is precisely what makes two installs share one session on
        // every peer, so this one asks for an id of its own instead.
        registerAsSecondary(stores, api, sealedSenderPub)
    }

    /**
     * Is the primary slot [status] describes ours to publish into? True when
     * it is empty or already holds our own identity — and also on an island
     * too old to report what is in it: that island has ONE slot and nothing to
     * compare against, so this install stays where it already is instead of
     * republishing over whatever is there.
     */
    private fun holdsPrimary(status: RcqApi.KeysStatus, ourIdentity: String): Boolean =
        !status.has_bundle ||
            status.signal_identity_key == null ||
            status.signal_identity_key == ourIdentity

    /**
     * Take a libsignal device id of our own and move this install's ratchets
     * onto it. Reached two ways: by an install that has never resolved which
     * device it is and finds a sibling in the primary slot, and by one that
     * HELD the primary slot until a fresh bootstrap of the same account claimed
     * it. The new slot gets fresh pre-keys; the old ones stay in the store,
     * since messages already sealed against them still have to open.
     */
    private suspend fun registerAsSecondary(stores: SignalStores, api: RcqApi, sealedSenderPub: String) {
        val identity = stores.getIdentityKeyPair()
        val body = mintKeys(stores, identity, stores.getLocalRegistrationId())
        val assigned = try {
            claimSlot(api, body, sealedSenderPub, Slot.SECONDARY)
        } catch (e: NoDeviceRegistry) {
            // One slot on this island, and another install holds it. Publishing
            // over it would put two ratchets back under one address, so this
            // install stays where it is: the sessions it already has keep
            // working, and anyone starting a new one reaches it over v=1, which
            // every install of the account can open.
            Log.i(TAG, "island has no device registry; leaving the primary slot to the install that holds it")
            stores.storeDeviceId(SealedSender.PRIMARY_DEVICE_ID)
            return
        }
        // Persisted FIRST: POST /keys/devices is not idempotent, so an id the
        // server has already handed out and this install has not written down
        // is a device row nobody will ever drain, plus a second registration on
        // the next launch. Everything below may throw; none of it may cost the
        // answer.
        stores.storeDeviceId(assigned)
        stores.assignPreKeyPool(body.one_time_prekeys.map { it.id }, assigned)
        // Sessions seeded while this install answered as device 1 live under
        // that address on every peer, which is not the one our messages name
        // from now on. Archived, not dropped: they still open what is already
        // in flight, and the next send to each peer builds a session under the
        // right address.
        stores.archiveAllSessions()
        Log.i(TAG, "registered as libsignal device $assigned; sessions archived for rebuild")
    }

    /** Which slot [claimSlot] is to publish into. */
    private enum class Slot {
        /** The primary slot is OURS and stays ours: a fresh bootstrap claiming
         *  it, or a deliberate re-key of the install that holds it. */
        PRIMARY,
        /** Another install owns the primary; ask for an id of our own. */
        SECONDARY,
    }

    /** This island keeps a single key slot and knows nothing about devices. */
    private class NoDeviceRegistry : IllegalStateException("island has no device registry")

    /**
     * Publish [body] into a slot of our own and return the libsignal device id
     * this install now owns. A secondary id is assigned by the server — it is
     * never self-asserted.
     *
     * A [Slot.SECONDARY] claim on an island with no device registry throws
     * [NoDeviceRegistry] rather than falling back to the primary slot: the
     * caller asked for a secondary because the primary belongs to somebody
     * else, and quietly uploading over it there is exactly the overwrite that
     * strands the other install's mail.
     */
    private suspend fun claimSlot(
        api: RcqApi,
        body: RcqApi.KeysBundleBody,
        sealedSenderPub: String,
        slot: Slot,
    ): Int {
        if (slot == Slot.PRIMARY) {
            api.uploadKeysBundle(body)
            return SealedSender.PRIMARY_DEVICE_ID
        }
        val assigned = try {
            api.registerDevice(deviceBody(body, sealedSenderPub)).device_id
        } catch (e: Exception) {
            if (e.message?.startsWith("HTTP 404") == true) throw NoDeviceRegistry()
            throw e
        }
        // A secondary id is >= 2. Anything else (an island answering a shape we
        // don't know) is refused rather than persisted: a wrong id is what our
        // drain asks the queue for, and a queue asked for the wrong device
        // answers nothing.
        if (assigned < 2) throw IllegalStateException("device registration returned id $assigned")
        return assigned
    }

    private fun deviceBody(b: RcqApi.KeysBundleBody, sealedSenderPub: String) =
        RcqApi.DeviceBundleBody(
            signal_identity_key = b.signal_identity_key,
            registration_id = b.registration_id,
            signed_prekey = b.signed_prekey,
            kyber_prekey = b.kyber_prekey,
            one_time_prekeys = b.one_time_prekeys,
            label = DEVICE_LABEL,
            sealed_sender_pub = sealedSenderPub,
        )

    /** Generate a signed pre-key, a Kyber pre-key and a one-time pre-key pool
     *  under [identity], store the private halves locally and return the
     *  public bundle to publish. */
    private fun mintKeys(
        stores: SignalStores,
        identity: IdentityKeyPair,
        registrationId: Int,
    ): RcqApi.KeysBundleBody {
        val nowMs = System.currentTimeMillis()

        // Signed pre-key (EC), signed by the identity key.
        val signedId = (1..MAX_ID).random()
        val signedKp = ECKeyPair.generate()
        val signedPub = signedKp.publicKey.serialize()
        val signedSig = identity.privateKey.calculateSignature(signedPub)
        stores.storeSignedPreKey(signedId, SignedPreKeyRecord(signedId, nowMs, signedKp, signedSig))

        // Kyber pre-key (PQXDH post-quantum half). Single rotating key; reuse OK
        // since forward secrecy comes from the EC ephemeral side.
        val kyberId = (1..MAX_ID).random()
        val kyberKp = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberPub = kyberKp.publicKey.serialize()
        val kyberSig = identity.privateKey.calculateSignature(kyberPub)
        stores.storeKyberPreKey(kyberId, KyberPreKeyRecord(kyberId, nowMs, kyberKp, kyberSig))

        // One-time pre-key pool (consumed at X3DH/PQXDH initiation).
        val opks = ArrayList<RcqApi.OneTimePreKeyDto>(TARGET_OPK)
        repeat(TARGET_OPK) {
            val id = (1..MAX_ID).random()
            val kp = ECKeyPair.generate()
            stores.storePreKey(id, PreKeyRecord(id, kp))
            opks.add(RcqApi.OneTimePreKeyDto(id, b64(kp.publicKey.serialize())))
        }

        return RcqApi.KeysBundleBody(
            signal_identity_key = b64(identity.publicKey.serialize()),
            registration_id = registrationId,
            signed_prekey = RcqApi.SignedPreKeyDto(signedId, b64(signedPub), b64(signedSig)),
            kyber_prekey = RcqApi.KyberPreKeyDto(kyberId, b64(kyberPub), b64(kyberSig)),
            one_time_prekeys = opks,
        )
    }

    /**
     * Rotate the libsignal identity in place: mint a brand-new identity + prekey
     * bundle and re-upload it, REPLACING the old one. Upload-FIRST so a failed
     * network call leaves the existing (working) identity untouched instead of
     * desyncing local vs server. Used by account key re-issue — a new libsignal
     * identity changes our safety number, so contacts get a "safety number
     * changed" warning the next time they establish a session with us.
     *
     * The slot is KEPT, not re-resolved against the new key: the identity the
     * server holds is the one this call is replacing, so comparing against it
     * would read our own outgoing key as another install's and demote the
     * primary to a secondary on every key change. An install that is already a
     * secondary re-registers as one — /keys/devices has no in-place update, so
     * it comes back under a NEW device id and the old row is left behind (no
     * endpoint retires it).
     */
    suspend fun rebootstrap(
        stores: SignalStores,
        api: RcqApi,
        ownUin: Int,
        sealedSenderPub: ByteArray,
    ) {
        // An UNRESOLVED device id is not the primary by default — reading it as
        // one is how a re-key on a secondary install lands on top of the
        // sibling that actually holds the slot. With nothing persisted yet the
        // server is asked the same question [adoptDeviceId] asks, against the
        // identity this call is about to REPLACE: that is the key the primary
        // slot holds if this install owns it.
        val slot = when (stores.deviceId()) {
            SealedSender.PRIMARY_DEVICE_ID -> Slot.PRIMARY
            null -> {
                // No local identity to compare with is the fresh-bootstrap
                // case, which claims the slot.
                val ours = runCatching { b64(stores.getIdentityKeyPair().publicKey.serialize()) }.getOrNull()
                if (ours == null || holdsPrimary(api.keysStatus(), ours)) Slot.PRIMARY else Slot.SECONDARY
            }
            else -> Slot.SECONDARY
        }
        val identity = IdentityKeyPair.generate()
        val registrationId = (1..16380).random()
        val nowMs = System.currentTimeMillis()

        val signedId = (1..MAX_ID).random()
        val signedKp = ECKeyPair.generate()
        val signedPub = signedKp.publicKey.serialize()
        val signedSig = identity.privateKey.calculateSignature(signedPub)

        val kyberId = (1..MAX_ID).random()
        val kyberKp = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberPub = kyberKp.publicKey.serialize()
        val kyberSig = identity.privateKey.calculateSignature(kyberPub)

        val opks = ArrayList<Pair<Int, ECKeyPair>>(TARGET_OPK)
        repeat(TARGET_OPK) { opks.add((1..MAX_ID).random() to ECKeyPair.generate()) }

        // Upload BEFORE touching local state.
        val assigned = claimSlot(
            api,
            RcqApi.KeysBundleBody(
                signal_identity_key = b64(identity.publicKey.serialize()),
                registration_id = registrationId,
                signed_prekey = RcqApi.SignedPreKeyDto(signedId, b64(signedPub), b64(signedSig)),
                kyber_prekey = RcqApi.KyberPreKeyDto(kyberId, b64(kyberPub), b64(kyberSig)),
                one_time_prekeys = opks.map { (id, kp) -> RcqApi.OneTimePreKeyDto(id, b64(kp.publicKey.serialize())) },
            ),
            b64(sealedSenderPub),
            slot,
        )
        // Server accepted the new bundle: swap local state to match.
        stores.wipe()
        stores.storeLocalIdentity(ownUin, identity, registrationId)
        stores.storeDeviceId(assigned)
        stores.storeSignedPreKey(signedId, SignedPreKeyRecord(signedId, nowMs, signedKp, signedSig))
        stores.storeKyberPreKey(kyberId, KyberPreKeyRecord(kyberId, nowMs, kyberKp, kyberSig))
        opks.forEach { (id, kp) -> stores.storePreKeyInPool(id, PreKeyRecord(id, kp), assigned) }
    }

    private suspend fun topUpIfNeeded(stores: SignalStores, api: RcqApi, sealedSenderPub: String) {
        val myDevice = stores.deviceId() ?: SealedSender.PRIMARY_DEVICE_ID
        if (myDevice != SealedSender.PRIMARY_DEVICE_ID) {
            // /keys/me/status describes the PRIMARY slot, not ours. What is
            // left of our own pool is only visible locally: the ratchet
            // removes each one-time pre-key as the message that consumed it
            // arrives.
            val remaining = stores.preKeyCount(myDevice)
            if (remaining < TOPUP_THRESHOLD) {
                replenishOpks(stores, api, TARGET_OPK - remaining, myDevice)
            }
            return
        }
        val status = runCatching { api.keysStatus() }.getOrNull() ?: return
        if (!status.has_bundle) {
            // Server forgot us (db wipe) → rebuild from scratch.
            val uin = stores.localUin() ?: return
            stores.wipe()
            freshBootstrap(stores, api, uin, sealedSenderPub)
            return
        }
        // The other half of the rule in [freshBootstrap]: a fresh install of
        // this account claims the primary slot, so the identity published there
        // may no longer be ours. Senders address THAT install as device 1 from
        // now on, and a copy sealed to it opens nowhere else — so this one
        // steps aside and takes an id of its own, which is the single step that
        // makes both installs reachable again.
        val ours = runCatching { b64(stores.getIdentityKeyPair().publicKey.serialize()) }.getOrNull()
        if (ours != null && !holdsPrimary(status, ours)) {
            Log.i(TAG, "primary slot now holds another install's identity; re-registering as a secondary")
            registerAsSecondary(stores, api, sealedSenderPub)
            return
        }
        if (status.one_time_prekey_count < TOPUP_THRESHOLD) {
            replenishOpks(stores, api, (TARGET_OPK - status.one_time_prekey_count).coerceAtLeast(0), myDevice)
        }
    }

    private suspend fun replenishOpks(stores: SignalStores, api: RcqApi, count: Int, deviceId: Int) {
        if (count <= 0) return
        val batch = ArrayList<RcqApi.OneTimePreKeyDto>(count)
        val ids = ArrayList<Int>(count)
        repeat(count) {
            val id = (1..MAX_ID).random()
            val kp = ECKeyPair.generate()
            // Private half first: a key the server hands out and we cannot load
            // opens nothing.
            stores.storePreKey(id, PreKeyRecord(id, kp))
            ids.add(id)
            batch.add(RcqApi.OneTimePreKeyDto(id, b64(kp.publicKey.serialize())))
        }
        val body = RcqApi.PrekeysBody(batch)
        if (deviceId == SealedSender.PRIMARY_DEVICE_ID) api.replenishPrekeys(body)
        else api.replenishDevicePrekeys(deviceId, body)
        // Counted towards this pool only now the island actually holds them.
        // A device that reads its own pool locally and counts keys whose upload
        // failed sees a full pool it never published, and stops topping up for
        // good.
        stores.assignPreKeyPool(ids, deviceId)
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
