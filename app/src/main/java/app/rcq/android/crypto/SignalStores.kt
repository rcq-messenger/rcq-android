package app.rcq.android.crypto

import android.util.Log
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore

/**
 * libsignal protocol stores backed by [SignalStoreDb], for v=2 forward
 * secrecy. Android port of the iOS SignalProtocolStores. One instance per
 * active account (its [SignalStoreDb] is per-account).
 *
 * Trust model: a NEW peer identity, or a CHANGED one, is admitted only after
 * the caller checked it against what the island publishes for that number and
 * device ([admitIdentity], see Session.decryptInbound); a receiving identity
 * that differs from the pinned one is refused otherwise ([isTrustedIdentity]).
 * It used to be trust-on-first-use with every rotation accepted, and since a
 * v=2 envelope names its sender in unsigned fields, anybody holding our bundle
 * could open a session as any number, our own included. A change that is
 * admitted is still flagged for the safety-number screen ([saveIdentity]).
 */
class SignalStores(private val db: SignalStoreDb) :
    IdentityKeyStore, PreKeyStore, SignedPreKeyStore, KyberPreKeyStore, SessionStore {

    private fun addressKey(address: SignalProtocolAddress): String =
        "${address.name}:${address.deviceId}"

    // ── local identity helpers ───────────────────────────────────────
    fun localUin(): Int? = db.loadLocalIdentity()?.first

    fun hasLocalIdentity(): Boolean = db.loadLocalIdentity() != null

    fun localAddress(): SignalProtocolAddress {
        val uin = localUin() ?: error("no local libsignal identity (not bootstrapped)")
        return SignalProtocolAddress(uin.toString(), 1)
    }

    fun storeLocalIdentity(uin: Int, identityKeyPair: IdentityKeyPair, registrationId: Int) {
        db.storeLocalIdentity(uin, identityKeyPair.serialize(), registrationId)
    }

    /** Which libsignal device of the account this install is (1 = the one
     *  holding the primary bundle), or null while it is still unresolved.
     *  Persisted here so it survives restarts alongside the ratchet state it
     *  belongs to. */
    fun deviceId(): Int? = db.loadDeviceId()

    fun storeDeviceId(deviceId: Int) = db.storeDeviceId(deviceId)

    /** One-time pre-keys of [poolDevice]'s pool still unspent locally. Every
     *  one a peer consumed was removed by the ratchet on receipt, so this
     *  tracks what the server can still hand out for a device whose pool it
     *  does not report.
     *
     *  Counted per POOL, not per table: an install that started as the primary
     *  and later registered as a secondary keeps the primary's keys (messages
     *  already sealed against them still have to open), and counting those
     *  towards the new pool is what leaves a secondary at "plenty left"
     *  forever while its published pool drains to nothing. */
    fun preKeyCount(poolDevice: Int): Int = db.countPreKeysInPool(poolDevice)

    /** Store a one-time pre-key whose public half goes to [poolDevice]'s
     *  published pool. */
    fun storePreKeyInPool(id: Int, record: PreKeyRecord, poolDevice: Int) =
        db.putPreKey(id, record.serialize(), poolDevice)

    /** Record which pool [ids] were published to, once the server has answered
     *  which device this install is. */
    fun assignPreKeyPool(ids: List<Int>, poolDevice: Int) = db.setPreKeyPool(ids, poolDevice)

    /** The X25519 key an envelope for [uin]'s device [deviceId] must be
     *  ECIES-sealed to, or null if we have never seen that device's bundle.
     *  Only SECONDARY devices are kept here — device 1's outer key is the
     *  account identity key every contact row already carries. */
    fun peerDeviceOuterKey(uin: Int, deviceId: Int): ByteArray? =
        db.blobByAddress("device_outer_keys", "outer_key", "$uin:$deviceId")

    fun storePeerDeviceOuterKey(uin: Int, deviceId: Int, outerKey: ByteArray) =
        db.putBlobByAddress("device_outer_keys", "outer_key", "$uin:$deviceId", outerKey)

    /**
     * Archive the current state of every session: each record still opens
     * messages already in flight, but has no sending chain left, so the next
     * send builds a fresh one. Used when this install turns out NOT to hold
     * the account's primary slot — its sessions were seeded under device 1 on
     * every peer, which is not the address our messages now name.
     */
    fun archiveAllSessions() {
        var failed = 0
        for (address in db.allAddresses("sessions")) {
            val blob = db.blobByAddress("sessions", "record", address) ?: continue
            // Guarded per row. One record libsignal refuses to parse (a blob
            // half-written, or from a version this build does not know) used to
            // abort the whole sweep, leaving every session after it with a live
            // sending chain under an address the peer stopped answering — the
            // caller has already persisted the new device id by then, so there
            // is no second pass to fix it.
            runCatching {
                val record = SessionRecord(blob)
                record.archiveCurrentState()
                db.putBlobByAddress("sessions", "record", address, record.serialize())
            }.onFailure {
                failed++
                Log.w("RCQsignal", "session $address could not be archived: ${it.javaClass.simpleName}")
            }
        }
        if (failed > 0) Log.w("RCQsignal", "$failed of the account's sessions were left unarchived")
    }

    /** Drop all libsignal state (re-bootstrap on UIN drift / server wipe). */
    fun wipe() = db.clear()

    // ── IdentityKeyStore ─────────────────────────────────────────────
    override fun getIdentityKeyPair(): IdentityKeyPair {
        val row = db.loadLocalIdentity() ?: error("no local libsignal identity")
        return IdentityKeyPair(row.second)
    }

    override fun getLocalRegistrationId(): Int =
        db.loadLocalIdentity()?.third ?: error("no local libsignal identity")

    /** "<uin>:<device>|<identity b64>" admitted for the next receive: the
     *  caller checked them against the island. In memory: a restart re-checks. */
    private val admitted: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun admittedKey(address: SignalProtocolAddress, identityKey: IdentityKey): String =
        addressKey(address) + "|" + java.util.Base64.getEncoder().encodeToString(identityKey.serialize())

    /** [identityKey] for [address] was checked against the island's published
     *  bundle: a receiving session may be built on it even though it differs
     *  from the identity pinned there. */
    fun admitIdentity(address: SignalProtocolAddress, identityKey: IdentityKey) {
        admitted.add(admittedKey(address, identityKey))
    }

    /** Set while a SignalMessage is being opened, see [IdentityTrustRule]:
     *  it decrypts only on a session state this store already holds. libsignal
     *  calls back on the calling thread, inside synchronized(stores). */
    private val establishedReceive = ThreadLocal<Boolean>()

    /** Run [block] (the decrypt of a SignalMessage) with the pin check and
     *  the pin movement of an established session, see [IdentityTrustRule]. */
    fun <T> openOnEstablishedSession(block: () -> T): T {
        establishedReceive.set(true)
        try {
            return block()
        } finally {
            establishedReceive.remove()
        }
    }

    private fun onEstablishedSession(): Boolean = establishedReceive.get() == true

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val key = addressKey(address)
        val existing = db.blobByAddress("identities", "identity_key", key)
        // An archived state's identity must not pull the pin back: no
        // ping-pong, no false "safety number changed".
        if (IdentityTrustRule.keepsPin(onEstablishedSession(), existing != null)) {
            return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
        val serialized = identityKey.serialize()
        db.putBlobByAddress("identities", "identity_key", key, serialized)
        admitted.remove(admittedKey(address, identityKey))
        return if (existing != null && !existing.contentEquals(serialized)) {
            // The peer's identity key changed under a known address: re-register,
            // new device, or a server swapping keys (MITM). Flag it so the UI can
            // warn the user to re-verify the safety number; TOFU still accepts it.
            db.markIdentityChanged(key)
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    /** Has [uin]'s pinned identity changed since the user last verified it? */
    fun peerIdentityChanged(uin: Int): Boolean =
        db.isIdentityChanged(addressKey(SignalProtocolAddress(uin.toString(), 1)))

    /** Clear the change flag for [uin] (the user re-verified). */
    fun acknowledgePeerIdentity(uin: Int) =
        db.clearIdentityChanged(addressKey(SignalProtocolAddress(uin.toString(), 1)))

    /**
     * SENDING: a session we build is built from a bundle the island served, so
     * its identity is the island's word already. RECEIVING a SignalMessage: a
     * state we already hold, built on an identity trusted when it was made
     * ([openOnEstablishedSession]). RECEIVING anything else: the pinned
     * identity, a first one (the caller checks a first identity against the
     * island before it decrypts), or one admitted by [admitIdentity]. A
     * different identity that nobody checked is refused: libsignal then throws
     * before any state moves, and the row stays on the island for the check.
     * See [IdentityTrustRule].
     */
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val sending = direction == IdentityKeyStore.Direction.SENDING
        val established = onEstablishedSession()
        if (sending || established) return IdentityTrustRule.trusted(sending, established, null, false)
        val pinned = getIdentity(address)
        return IdentityTrustRule.trusted(
            sending = false,
            established = false,
            pinnedMatches = pinned?.let { it == identityKey },
            admitted = pinned != null && admittedKey(address, identityKey) in admitted,
        )
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val blob = db.blobByAddress("identities", "identity_key", addressKey(address)) ?: return null
        return IdentityKey(blob)
    }

    // ── PreKeyStore ──────────────────────────────────────────────────
    override fun loadPreKey(id: Int): PreKeyRecord {
        val blob = db.recordByInt("prekeys", "prekey_id", id) ?: throw InvalidKeyIdException("no prekey $id")
        return PreKeyRecord(blob)
    }

    override fun storePreKey(id: Int, record: PreKeyRecord) =
        db.putPreKey(id, record.serialize(), poolDevice = null)

    override fun containsPreKey(id: Int): Boolean = db.containsInt("prekeys", "prekey_id", id)

    override fun removePreKey(id: Int) = db.deleteByInt("prekeys", "prekey_id", id)

    // ── SignedPreKeyStore ────────────────────────────────────────────
    override fun loadSignedPreKey(id: Int): SignedPreKeyRecord {
        val blob = db.recordByInt("signed_prekeys", "signed_prekey_id", id)
            ?: throw InvalidKeyIdException("no signed prekey $id")
        return SignedPreKeyRecord(blob)
    }

    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> =
        db.allRecords("signed_prekeys").mapTo(ArrayList()) { SignedPreKeyRecord(it) }

    override fun storeSignedPreKey(id: Int, record: SignedPreKeyRecord) =
        db.putRecordByInt("signed_prekeys", "signed_prekey_id", id, record.serialize())

    override fun containsSignedPreKey(id: Int): Boolean = db.containsInt("signed_prekeys", "signed_prekey_id", id)

    override fun removeSignedPreKey(id: Int) = db.deleteByInt("signed_prekeys", "signed_prekey_id", id)

    // ── KyberPreKeyStore ─────────────────────────────────────────────
    override fun loadKyberPreKey(id: Int): KyberPreKeyRecord {
        val blob = db.recordByInt("kyber_prekeys", "kyber_prekey_id", id)
            ?: throw InvalidKeyIdException("no kyber prekey $id")
        return KyberPreKeyRecord(blob)
    }

    override fun loadKyberPreKeys(): MutableList<KyberPreKeyRecord> =
        db.allRecords("kyber_prekeys").mapTo(ArrayList()) { KyberPreKeyRecord(it) }

    override fun storeKyberPreKey(id: Int, record: KyberPreKeyRecord) =
        db.putRecordByInt("kyber_prekeys", "kyber_prekey_id", id, record.serialize())

    override fun containsKyberPreKey(id: Int): Boolean = db.containsInt("kyber_prekeys", "kyber_prekey_id", id)

    /** No-op: single rotating last-resort Kyber pre-key, reusable (iOS parity). */
    override fun markKyberPreKeyUsed(id: Int, signedPreKeyId: Int, baseKey: ECPublicKey) = Unit

    // ── SessionStore ─────────────────────────────────────────────────
    override fun loadSession(address: SignalProtocolAddress): SessionRecord? {
        val blob = db.blobByAddress("sessions", "record", addressKey(address)) ?: return null
        return SessionRecord(blob)
    }

    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> =
        addresses.mapNotNullTo(ArrayList()) { loadSession(it) }

    override fun getSubDeviceSessions(name: String): MutableList<Int> =
        db.addressesWithName("sessions", name)
            .mapNotNull { it.substringAfterLast(':').toIntOrNull() }
            .filter { it != 1 }
            .toMutableList()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) =
        db.putBlobByAddress("sessions", "record", addressKey(address), record.serialize())

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        db.containsAddress("sessions", addressKey(address))

    override fun deleteSession(address: SignalProtocolAddress) =
        db.deleteByAddress("sessions", addressKey(address))

    override fun deleteAllSessions(name: String) = db.deleteByAddressPrefix("sessions", name)
}
