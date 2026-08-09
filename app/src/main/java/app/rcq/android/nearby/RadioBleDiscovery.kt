package app.rcq.android.nearby

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.util.UUID

/**
 * BLE half of the hybrid Radio transport: cheap, always-on *ambient discovery*.
 * Each online device advertises a compact manufacturer-data blob describing
 * itself (kind, a per-session endpoint id, short name, room id + password flag);
 * scanners filter by our company id + magic prefix and surface peers. The heavy
 * data session (text/voice) runs over Wi-Fi Direct ([RadioWifiDirect]); BLE only
 * answers "who is around me right now" without burning the radio.
 *
 * ## Byte budget (legacy advertising — works on every BLE device)
 * One legacy advertising packet carries ~24 usable bytes of manufacturer data,
 * so the fixed header is kept tiny and the name is truncated:
 * ```
 *   [0..1] magic 'R','C'           filter prefix (namespaces the 0xFFFF id)
 *   [2]    version
 *   [3]    kindflags: bit0 kind (0=1:1, 1=room), bit1 needsPassword
 *   [4..7] endpointId (4 random bytes, stable for this online session)
 *   [8..11] roomId (4 bytes; 0 for 1:1)
 *   [12]   nameLen
 *   [13..] name UTF-8, truncated to fit
 * ```
 * The *full* name + room id arrive over the data channel on connect (roster /
 * room metadata), so a truncated BLE name only affects the pre-join list label.
 * Extended advertising (BLE 5) could carry the full name; left as a follow-up.
 *
 * ## Connection bootstrap
 * The blob deliberately does NOT carry a Wi-Fi Direct MAC (self P2P address is
 * hidden/randomised on many devices). Instead the endpointId is the join token:
 * at connect time [RadioWifiDirect] runs a short Wi-Fi Direct service-discovery
 * burst and matches the peer whose TXT record echoes this endpointId.
 *
 * Compile-verified only — there is no BLE radio on the emulator; real discovery
 * is a two-physical-device check.
 */
class RadioBleDiscovery(
    private val appContext: Context,
    /** Called for every scan hit (deduped by endpointId upstream). */
    private val onPeer: (RadioPeer) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val adapter by lazy {
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var advCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    @Volatile private var current: LocalAdvertisement? = null

    /** What this device broadcasts about itself. */
    data class LocalAdvertisement(
        val endpointId: ByteArray,        // 4 bytes
        val displayName: String,
        val kind: RadioPeer.Kind,
        val room: RadioRoomMetadata?,     // null for 1:1
    )

    // ── lifecycle ─────────────────────────────────────────────────────
    fun start(local: LocalAdvertisement) {
        if (!hasPermission()) { onError("ble_permission"); return }
        if (adapter?.isEnabled != true) { onError("ble_off"); return }
        current = local
        startAdvertising(local)
        startScanning()
    }

    /** Swap the advertised payload (e.g. 1:1 ↔ room) without dropping the scan. */
    fun updateAdvertisement(local: LocalAdvertisement) {
        current = local
        if (!hasPermission() || adapter?.isEnabled != true) return
        stopAdvertising()
        startAdvertising(local)
    }

    fun stop() {
        stopAdvertising()
        stopScanning()
        current = null
    }

    // ── advertising ───────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun startAdvertising(local: LocalAdvertisement) {
        val adv = adapter?.bluetoothLeAdvertiser ?: run { onError("ble_no_advertiser"); return }
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // device name would blow the byte budget
            .addManufacturerData(COMPANY_ID, encodePayload(local))
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "advertise failed: $errorCode")
                onError("ble_advertise_failed_$errorCode")
            }
        }
        advCallback = cb
        runCatching { adv.startAdvertising(settings, data, cb) }
            .onFailure { onError("ble_advertise_ex") }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        val cb = advCallback ?: return
        runCatching { advertiser?.stopAdvertising(cb) }
        advCallback = null
    }

    // ── scanning ──────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun startScanning() {
        val sc = adapter?.bluetoothLeScanner ?: run { onError("ble_no_scanner"); return }
        scanner = sc
        val filter = ScanFilter.Builder()
            .setManufacturerData(COMPANY_ID, MAGIC) // only our radio beacons
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handle(it) }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode")
                onError("ble_scan_failed_$errorCode")
            }
        }
        scanCallback = cb
        runCatching { sc.startScan(listOf(filter), settings, cb) }
            .onFailure { onError("ble_scan_ex") }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        val cb = scanCallback ?: return
        runCatching { scanner?.stopScan(cb) }
        scanCallback = null
    }

    private fun handle(result: ScanResult) {
        val raw = result.scanRecord?.getManufacturerSpecificData(COMPANY_ID) ?: return
        val peer = decodePayload(raw) ?: return
        // Ignore our own beacon (same endpointId).
        if (current?.endpointId?.contentEquals(hexToBytes(peer.endpointId)) == true) return
        onPeer(peer)
    }

    // ── payload codec ─────────────────────────────────────────────────
    private fun encodePayload(local: LocalAdvertisement): ByteArray {
        val isRoom = local.kind == RadioPeer.Kind.Room
        var flags = 0
        if (isRoom) flags = flags or 0x01
        if (local.room?.needsPassword == true) flags = flags or 0x02

        val roomId = if (isRoom) shortRoomIdBytes(local.room!!.roomId) else ByteArray(4)
        val fixed = 13 // magic2 + ver1 + flags1 + eid4 + roomId4 + nameLen1
        val nameBudget = (MAX_MFR_PAYLOAD - fixed).coerceAtLeast(0)
        // A ROOM advertises the ROOM's name; a person advertises their on-air
        // label. Both used to go out as `displayName`, so the name the host
        // typed when creating a room never reached the wire at all and every
        // room in a scanner's list was titled with its host's random label
        // ("Mellow List" instead of the room name — tester report, 0.95).
        // The host's own label still reaches joiners over the data channel
        // with the roster, which is where the full untruncated names live.
        val advertised = if (isRoom) {
            local.room!!.name.ifBlank { local.displayName }
        } else local.displayName
        val nameBytes = advertised.toByteArray(Charsets.UTF_8).let {
            if (it.size > nameBudget) truncateUtf8(advertised, nameBudget) else it
        }

        val buf = ByteBuffer.allocate(fixed + nameBytes.size)
        buf.put(MAGIC)                       // [0..1]
        buf.put(VERSION.toByte())            // [2]
        buf.put(flags.toByte())              // [3]
        buf.put(local.endpointId, 0, 4)      // [4..7]
        buf.put(roomId, 0, 4)                // [8..11]
        buf.put(nameBytes.size.toByte())     // [12]
        buf.put(nameBytes)                   // [13..]
        return buf.array()
    }

    private fun decodePayload(raw: ByteArray): RadioPeer? {
        if (raw.size < 13) return null
        if (raw[0] != MAGIC[0] || raw[1] != MAGIC[1]) return null
        if (raw[2].toInt() != VERSION) return null
        val flags = raw[3].toInt()
        val isRoom = flags and 0x01 != 0
        val needsPwd = flags and 0x02 != 0
        val endpointId = bytesToHex(raw.copyOfRange(4, 8))
        val roomIdBytes = raw.copyOfRange(8, 12)
        val nameLen = raw[12].toInt() and 0xFF
        val name = if (raw.size >= 13 + nameLen && nameLen > 0) {
            String(raw, 13, nameLen, Charsets.UTF_8)
        } else ""

        val room = if (isRoom) RadioRoomMetadata(
            roomId = bytesToHex(roomIdBytes),
            name = name.ifBlank { "Room" },
            needsPassword = needsPwd,
        ) else null

        return RadioPeer(
            // For a room the advertised name IS the room name (see encode), so
            // it must not double as the host's label — the row renders
            // `room.name` and nothing should show a room's title where a
            // person's is expected.
            endpointId = endpointId,
            displayName = if (isRoom) "" else name,
            kind = if (isRoom) RadioPeer.Kind.Room else RadioPeer.Kind.OneToOne,
            room = room,
            wifiMac = null,
            state = RadioPeer.ConnectionState.Discovered,
        )
    }

    fun hasPermission(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.all {
            appContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    companion object {
        private const val TAG = "RadioBle"

        /** 0xFFFF is the reserved "no company / testing" Bluetooth SIG id; the
         *  [MAGIC] prefix namespaces our beacons away from other 0xFFFF users. */
        const val COMPANY_ID = 0xFFFF
        val MAGIC = byteArrayOf('R'.code.toByte(), 'C'.code.toByte())
        private const val VERSION = 1
        private const val MAX_MFR_PAYLOAD = 24

        /** Fixed 128-bit namespace, kept for the Wi-Fi Direct service id too. */
        val SERVICE_UUID: UUID = UUID.fromString("7263710d-0000-1000-8000-00805f9b34fb")
        val SERVICE_PARCEL = ParcelUuid(SERVICE_UUID)

        fun newEndpointId(): ByteArray = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }

        /** Stable 4-byte room id derived from the room's UUID string. */
        fun shortRoomIdBytes(roomUuid: String): ByteArray {
            val h = java.security.MessageDigest.getInstance("SHA-256")
                .digest(roomUuid.toByteArray(Charsets.UTF_8))
            return h.copyOfRange(0, 4)
        }

        fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

        fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) + hex[it * 2 + 1].digitToInt(16)).toByte() }

        /** Largest UTF-8 prefix of [s] that fits in [maxBytes] without splitting a char. */
        private fun truncateUtf8(s: String, maxBytes: Int): ByteArray {
            var end = s.length
            while (end > 0) {
                val bytes = s.substring(0, end).toByteArray(Charsets.UTF_8)
                if (bytes.size <= maxBytes) return bytes
                end--
            }
            return ByteArray(0)
        }
    }
}
