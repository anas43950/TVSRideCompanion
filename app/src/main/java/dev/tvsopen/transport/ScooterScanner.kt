// BLE discovery.
//
// This is the missing step between "the app has a full protocol stack" and
// "the app can talk to a scooter". Everything downstream of *which device is it*
// already exists; nothing downstream of *finding it* does.
//
// Two design choices worth stating, because both are judgement calls:
//
//   - **Filtered by default, in software.** The "Only TVS" filter narrows this
//     from "every wearable in the room" to "things that look like a TVS
//     cluster", by UUID or by name. It is applied in `publish()`, not as a
//     platform `ScanFilter`: the Apache advertises service `5500` (or no UUID
//     at all), so a hardware UUID filter would hide the one device we want. The
//     escape hatch to an unfiltered list stays for anything that advertises
//     neither the UUID nor a `TVS…` name.
//   - **Time-boxed.** Scanning is the single most expensive thing this app can
//     do to the battery, and it has no reason to keep running once the scooter
//     is found. The scan stops itself rather than relying on the UI to
//     remember.

package dev.tvsopen.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import dev.tvsopen.log.Log
import dev.tvsopen.protocol.Gatt
import dev.tvsopen.protocol.VehicleModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** One device seen during a scan. */
data class Discovered(
    val address: String,
    val name: String?,
    val rssi: Int,
    /** Either signal fired. Drives the SmartXonnect-only filter. */
    val isSmartXonnect: Boolean,
    /** The service UUID was in the advertisement. The strong signal. */
    val byServiceUuid: Boolean,
    /** The advertised name looks like a TVS cluster. Weaker, and labelled so. */
    val byName: Boolean,
    val lastSeenMillis: Long,
    /** Best guess from the advertised name, or UNKNOWN. A hint, not a decision. */
    val guessedModel: VehicleModel,
)

data class ScanState(
    val scanning: Boolean = false,

    /**
     * Whether to restrict the list to things that look like a TVS cluster —
     * matched by service UUID *or* by a `TVS…` name (see [Discovered.isSmartXonnect]).
     *
     * **Default on.** This is now a *software* filter in `publish()`, not a
     * platform `ScanFilter`, so it no longer hides the UUID-less Apache: the
     * scan still sees every advertisement and keeps only the cluster-looking
     * ones. An earlier version mapped this to `ScanFilter.setServiceUuid(...)`
     * and had to default off, because that filtered the rider's own cluster out
     * at the OS level. Turning it off here just widens the list to every BLE
     * device for the rare case that advertises neither signal.
     */
    val onlySmartXonnect: Boolean = true,
    val devices: List<Discovered> = emptyList(),
    /** Set when the scan could not start at all — usually a missing permission. */
    val error: String? = null,

    /**
     * The scan could not run because the phone's Bluetooth is switched off.
     *
     * Kept apart from [error] so the screen can offer the fix (the system
     * "Turn on Bluetooth?" dialog) instead of only describing the problem. With
     * Bluetooth off the app used to fail silently, or — on Android 13+, where
     * `startScan` throws `IllegalStateException` — blame a missing permission.
     */
    val bluetoothOff: Boolean = false,
)

class ScooterScanner(private val context: Context) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val found = ConcurrentHashMap<String, Discovered>()

    val isSupported: Boolean
        get() = bluetoothManager?.adapter?.isEnabled == true

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)

        // Batch callback: the platform prefers this on API 23+, and using it
        // avoids the app being throttled for ignoring `reportDelay`.
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { record(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed, code=$errorCode")
            _state.value = _state.value.copy(
                scanning = false,
                error = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "A scan is already running"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                        "The app could not be registered for scanning"
                    SCAN_FAILED_FEATURE_UNSUPPORTED ->
                        "This device does not support BLE scanning"
                    SCAN_FAILED_INTERNAL_ERROR -> "Bluetooth stack error — try again"
                    else -> "Scan failed (code $errorCode)"
                },
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun record(result: ScanResult) {
        val addr = result.device?.address ?: return
        val services: List<ParcelUuid> = runCatching {
            result.scanRecord?.serviceUuids ?: emptyList()
        }.getOrDefault(emptyList())
        val isSx = services.any { it.uuid?.toString()?.equals(Gatt.SERVICE, true) == true }

        val name = result.scanRecord?.deviceName
            ?: result.device?.name
        val byName = looksLikeCluster(name)
        val d = Discovered(
            address = addr,
            name = name,
            rssi = result.rssi,
            // **Either signal counts.** Filtering only on the service UUID risks
            // hiding the one device we want: an advertisement packet need not
            // carry the service UUID even when the GATT table does, and we have
            // never had a cluster in range to check which it does. Community
            // reports of pairing describe the device advertising as "TVS BT…",
            // so the name is treated as a second, weaker signal — and the UI says
            // which one fired rather than implying certainty.
            isSmartXonnect = isSx || byName,
            byServiceUuid = isSx,
            byName = byName,
            lastSeenMillis = SystemClock.elapsedRealtime(),
            guessedModel = VehicleModel.detect(name),
        )
        val prev = found[addr]
        // Keep the strongest reading: RSSI jitters and the last packet is often
        // a missed one, so a naive overwrite makes the list flicker.
        if (prev == null || d.rssi > prev.rssi) found[addr] = d
        publish()
    }

    private fun publish() {
        val only = _state.value.onlySmartXonnect
        val now = SystemClock.elapsedRealtime()
        val list = found.values
            .filter { !only || it.isSmartXonnect }
            // Drop anything not heard for a while so stale entries do not
            // masquerade as available.
            .filter { now - it.lastSeenMillis < STALE_AFTER_MS }
            .sortedWith(compareByDescending<Discovered> { it.isSmartXonnect }.thenByDescending { it.rssi })
        _state.value = _state.value.copy(devices = list)
    }

    @SuppressLint("MissingPermission")
    fun start(
        onlySmartXonnect: Boolean = _state.value.onlySmartXonnect,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ) {
        if (_state.value.scanning) return

        // Checked before asking for the scanner, not inferred from its absence:
        // on current Android `bluetoothLeScanner` is non-null with the radio off,
        // and `startScan` then throws, which the catch below used to report as a
        // missing permission.
        if (bluetoothManager?.adapter?.isEnabled != true) {
            reportBluetoothOff()
            return
        }
        val scanner: BluetoothLeScanner? = runCatching {
            bluetoothManager?.adapter?.bluetoothLeScanner
        }.getOrNull()

        if (scanner == null) {
            reportBluetoothOff()
            return
        }

        found.clear()
        _state.value = _state.value.copy(
            scanning = true,
            onlySmartXonnect = onlySmartXonnect,
            devices = emptyList(),
            error = null,
            bluetoothOff = false,
        )

        // No platform ScanFilter, ever: the Apache advertises service `5500` or
        // no UUID at all, so a hardware UUID filter would drop it before
        // `record()` runs. The "Only TVS" narrowing is done in `publish()`
        // instead, which can match on the advertised name as well.
        val filters = emptyList<ScanFilter>()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val failure = runCatching { scanner.startScan(filters, settings, callback) }
            .onFailure {
                Log.e(TAG, "startScan refused: ${it.message}")
            }.exceptionOrNull()

        if (failure is IllegalStateException) {
            // "BT Adapter is not turned ON": the radio went off between the
            // check above and the call.
            reportBluetoothOff()
            return
        }
        if (failure != null) {
            _state.value = _state.value.copy(
                scanning = false,
                error = "Could not start scanning. Grant Bluetooth permission in " +
                    "More → Permissions and try again.",
            )
            return
        }
        Log.i(TAG, "scanning, onlySmartXonnect=$onlySmartXonnect")

        mainHandler.postDelayed({ stop(timeoutReached = true) }, timeoutMillis)
    }

    /**
     * Flip the service-UUID filter, restarting the scan.
     *
     * Restarting is not only about applying the new filter to the platform.
     * Flipping this switch means "my scooter did not show up, try a different
     * way of looking" — so it has to run a scan even when the last one already
     * timed out. Guarding on `wasScanning` left the switch dead in precisely the
     * situation it was added for: someone staring at an empty list after the
     * timeout taps it, nothing happens, and the escape hatch looks broken.
     */
    fun setFilter(onlySmartXonnect: Boolean) {
        if (_state.value.onlySmartXonnect == onlySmartXonnect) return
        val wasScanning = _state.value.scanning
        stop()
        // Unfiltered scans keep whatever was already found; a fresh filtered scan
        // starts clean, because the old entries may not have advertised the UUID.
        if (!wasScanning) found.clear()
        publish()
        start(onlySmartXonnect)
    }

    @SuppressLint("MissingPermission")
    fun stop(timeoutReached: Boolean = false) {
        mainHandler.removeCallbacksAndMessages(null)
        if (!_state.value.scanning) return
        runCatching {
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(callback)
        }.onFailure { Log.w(TAG, "stopScan: ${it.message}") }
        _state.value = _state.value.copy(scanning = false)
        if (timeoutReached) Log.i(TAG, "scan timed out")
    }

    fun clear() {
        found.clear()
        publish()
    }

    private fun reportBluetoothOff() {
        Log.w(TAG, "scan not started: Bluetooth is off")
        _state.value = _state.value.copy(
            scanning = false,
            bluetoothOff = true,
            error = "Bluetooth is off. Turn it on to find your vehicle.",
        )
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        private const val TAG = "Scanner"
        private const val DEFAULT_TIMEOUT_MS = 20_000L
        private const val STALE_AFTER_MS = 8_000L

        /**
         * Advertised names of TVS clusters look like `TVS BT…`.
         *
         * **Inferred, not measured** — this comes from community accounts of
         * pairing, not from a cluster seen in a scan record. It is therefore a
         * second signal alongside the service UUID rather than a replacement for
         * it, and the scan UI labels which one matched. If the real prefix
         * differs this simply never fires; it cannot hide a device, only ever
         * add a possibly-false one.
         */
        val CLUSTER_NAME_RE = Regex("""\bTVS\s*(?:BT|\d{6,})""", RegexOption.IGNORE_CASE)

        /**
         * Whether an advertised name looks like a TVS cluster.
         *
         * Two shapes are known: `TVS BT…` (SmartXonnect, e.g. the Ntorq) and
         * `TVS` followed by a long digit string (the Apache RTR 160 4V's module
         * advertises as `TVS2509…`; seen in nRF Connect, 2026-11-14). The digit
         * run is required to be long so a name that merely starts with "TVS" —
         * a TV, say — is not flagged.
         */
        fun looksLikeCluster(name: String?): Boolean =
            name != null && CLUSTER_NAME_RE.containsMatchIn(name)

        // Values from android.bluetooth.le.ScanCallback, inlined so the
        // mapping stays next to the message it produces.
        private const val SCAN_FAILED_ALREADY_STARTED = 1
        private const val SCAN_FAILED_APPLICATION_REGISTRATION_FAILED = 2
        private const val SCAN_FAILED_INTERNAL_ERROR = 3
        private const val SCAN_FAILED_FEATURE_UNSUPPORTED = 4
    }
}
