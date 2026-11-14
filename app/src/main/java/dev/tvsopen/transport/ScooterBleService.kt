// BLE transport.
//
// Model-agnostic: it knows about GATT and framing, not about what any
// particular cluster's frames mean. Per-model meaning lives behind
// ProtocolDriver.

package dev.tvsopen.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import dev.tvsopen.log.Log
import dev.tvsopen.protocol.Gatt
import dev.tvsopen.protocol.GattProfile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, RECONNECTING }

/**
 * Human-readable label. The enum's own `name` renders as "DISCONNECTED", which
 * is shouting in a status pill.
 */
val ConnectionState.label: String
    get() = when (this) {
        ConnectionState.DISCONNECTED -> "Disconnected"
        ConnectionState.SCANNING -> "Scanning"
        ConnectionState.CONNECTING -> "Connecting"
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.RECONNECTING -> "Reconnecting"
    }

data class BleFrame(val direction: Direction, val bytes: ByteArray, val atMillis: Long) {
    enum class Direction { INBOUND, OUTBOUND }
    val hex: String get() = bytes.joinToString(" ") { "%02X".format(it) }
    override fun equals(other: Any?) =
        this === other || (other is BleFrame && direction == other.direction &&
            bytes.contentEquals(other.bytes) && atMillis == other.atMillis)
    override fun hashCode() = ((direction.ordinal * 31 + bytes.contentHashCode()) * 31 + atMillis.hashCode())
}

/**
 * One GATT link to one cluster.
 *
 * ## Why the write mutex
 *
 * Android's BLE stack is not thread-safe. With a navigation broadcast, a
 * keepalive ping and button events all in flight, concurrent
 * `writeCharacteristic` calls produce status-133 disconnects. Every write here
 * goes through a [Mutex], serialising them into a FIFO queue.
 *
 * ## Why write-without-response
 *
 * Upstream sets `getCharacteristicsWriteType() == 2`
 * (`WRITE_TYPE_NO_RESPONSE`) and requests a 512-byte MTU on every petrol
 * model, so a full frame fits in one packet and no flow control is needed.
 */
class ScooterBleService(private val context: Context) {

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<BleFrame>(replay = 0, extraBufferCapacity = 256)
    val frames: SharedFlow<BleFrame> = _frames.asSharedFlow()

    private val _lastFrameAt = MutableStateFlow(0L)
    val lastFrameAt: StateFlow<Long> = _lastFrameAt.asStateFlow()

    /**
     * The Bluetooth layout this link matched, or null until discovery finds one.
     *
     * This is how an Apache (`5500` service) is told apart from a SmartXonnect
     * cluster: by what the vehicle offers, not by its advertised name.
     */
    private val _profile = MutableStateFlow<GattProfile?>(null)
    val profile: StateFlow<GattProfile?> = _profile.asStateFlow()

    /**
     * True once every notification subscription (CCCD write) on this link has
     * been answered.
     *
     * The Apache sends nothing until notifications are switched on *and* the
     * phone has spoken, and it forgets the subscription between connections.
     * Its opening frames therefore wait for this, not for [state].
     */
    private val _subscribed = MutableStateFlow(false)
    val subscribed: StateFlow<Boolean> = _subscribed.asStateFlow()

    /**
     * Set when a link came up but offered no service this app can drive.
     *
     * Retrying such a device only repeats the failure, so the ride service reads
     * this before scheduling a reconnect. Cleared on the next [connect].
     */
    @Volatile
    var unsupportedDevice: Boolean = false
        private set

    private val writeLock = Mutex()
    // Written on the binder thread by the GATT callback and read on a coroutine
    // by connect/write, so the visibility guarantee is not optional: without it
    // a reconnect can read a stale non-null and refuse, which is exactly the
    // failure this class had.
    @Volatile
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Set once per connection, so the MTU callback and the fallback timer
     * cannot both start discovery. */
    @Volatile
    private var discoveryStarted = false

    /**
     * Whether the current link outage has already been reported.
     *
     * Guarded by [writeLock] rather than volatile: every read and write happens
     * inside `write`, so the lock is already held and a separate field would
     * only invite a second synchronisation scheme to drift out of step.
     */
    private var dropWarned = false

    val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    /**
     * Whether a GATT client is held: a live link, one being set up, or a
     * background connect request still waiting for the vehicle. [connect]
     * refuses while this is true, so a caller replacing the link must
     * [disconnect] first.
     */
    val isOpen: Boolean get() = gatt != null

    fun isSupported(): Boolean =
        context.packageManager.hasSystemFeature("android.hardware.bluetooth_le")

    // ---- lifecycle ----

    /**
     * Open a link to [address].
     *
     * [background] chooses between the two Android connect modes:
     *
     *  - `false` — a direct connect. Fast, but Android gives up after about 30 s
     *    if the vehicle is not there. Used right after the rider picks a vehicle
     *    from the scan list, when it is known to be advertising.
     *  - `true` — a background connect request (`autoConnect = true`). The
     *    phone's Bluetooth controller waits for the vehicle with no time limit
     *    and almost no battery cost, and links the moment it advertises. This is
     *    what auto-connect is built on; the official app connects this way too
     *    (seen in its logcat, 2026-11-14).
     */
    @SuppressLint("MissingPermission")
    fun connect(address: String, background: Boolean = false): Boolean {
        if (gatt != null) return false
        val a = adapter ?: return false
        if (!a.isEnabled) {
            Log.w(TAG, "connect skipped: Bluetooth is off")
            return false
        }
        val device: BluetoothDevice = try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "bad address $address: ${e.message}")
            return false
        }
        unsupportedDevice = false
        _profile.value = null
        _subscribed.value = false
        _state.value = if (background) ConnectionState.RECONNECTING else ConnectionState.CONNECTING
        Log.i(TAG, if (background) "waiting for $address (background connect)" else "connecting to $address")

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, background, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, background, gattCallback)
        }
        return true
    }

    @SuppressLint("MissingPermission")
    suspend fun disconnect() {
        // Suspend rather than plain fun: withLock is a suspend function, and a
        // non-suspend caller could not use it. Waiting here also guarantees no
        // write is in flight when the GATT client is closed.
        writeLock.withLock {
            gatt?.let { closeGatt(it) }
            writeChar = null
        }
        _state.value = ConnectionState.DISCONNECTED
    }

    /**
     * Close a GATT client and drop our reference to it.
     *
     * `close()` is what releases the client registration; without it the client
     * leaks and stays in `gatt`. That matters because `connect()` treats a
     * non-null `gatt` as "already connected" and refuses — so a single dropped
     * link left the app unable to reconnect for the life of the process, and
     * the only recovery was force-stopping it. Observed on hardware: the cluster
     * dropped with status 8 after ~2 minutes, and every later Connect was
     * refused with "link already active".
     *
     * Called from the binder callback as well as from [disconnect], so the
     * identity check matters — a stale callback must not clear a newer client.
     */
    @SuppressLint("MissingPermission")
    private fun closeGatt(g: BluetoothGatt) {
        writeChar = null
        mainHandler.removeCallbacksAndMessages(null)
        if (gatt === g) {
            gatt = null
            discoveryStarted = false
            _profile.value = null
            _subscribed.value = false
        }
        runCatching { g.close() }
            .onFailure { Log.w(TAG, "gatt close: ${it.message}") }
    }

    /**
     * Enqueue a write. Never throws; BLE failures are reported through
     * [ConnectionState] and the log, because a dropped frame is recoverable
     * and an exception in a caller coroutine is not.
     */
    suspend fun write(bytes: ByteArray): Boolean = writeLock.withLock {
        val g = gatt
        val ch = writeChar
        if (g == null || ch == null || _state.value != ConnectionState.CONNECTED) {
            // Log the *first* drop of an outage, not every attempt.
            //
            // The keepalive pings every 2 s for the whole session, so with the
            // link down this line fired 30 times a minute, forever. It drowned
            // the log: a 13-hour capture was mostly one repeated message, and the
            // frames that actually explain a fault became the hard part to find.
            // The per-frame dedup in `Log.frame` cannot help here because these
            // writes never reach it — they are dropped before any frame is built.
            //
            // Reset on the first successful write, so each outage is reported
            // exactly once and a reconnect is not silent.
            if (!dropWarned) {
                dropWarned = true
                val why = when {
                    g == null -> "no gatt"
                    ch == null -> "no write characteristic"
                    else -> "state is ${_state.value}"
                }
                Log.w(TAG, "link not ready, dropping writes ($why)")
            }
            return@withLock false
        }
        // Explicit <Boolean>: the lambda's last expression is a conditional on
        // `cont.resume(...)` which returns Unit, so inference would pick the
        // wrong T and `ok` would be typed as the continuation result itself.
        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            val result = try {
                // The 3-argument overload exists only on API 33+. Calling it on
                // an older phone throws NoSuchMethodError and takes the writer
                // down, so the legacy path sets the value and type on the
                // characteristic first — that is the only form API 26-32 has.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(
                        ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                    ) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    ch.value = bytes
                    @Suppress("DEPRECATION")
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(ch)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "write refused: ${e.message}")
                false
            }
            // resumeWith, not resume: the `resume` extension on Continuation is
            // ambiguous against CancellableContinuation's member here, and the
            // inferred type leaks into `ok`.
            cont.resumeWith(Result.success(result))
        }
        if (ok) {
            // Re-arm the outage warning: the link came back, so the next drop is
            // news again rather than a continuation of the one already reported.
            dropWarned = false
            emit(BleFrame(BleFrame.Direction.OUTBOUND, bytes, System.currentTimeMillis()))
        }
        ok
    }

    private fun emit(frame: BleFrame) {
        _lastFrameAt.value = frame.atMillis
        _frames.tryEmit(frame)
        // Bytes go in, hex comes out inside Log.frame, which also skips the
        // formatting cost entirely when capture is disabled.
        Log.frame(
            if (frame.direction == BleFrame.Direction.INBOUND) "IN " else "OUT",
            frame.bytes,
            frame.atMillis,
        )
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Still CONNECTING, deliberately. The radio link is up, but
                    // nothing can be sent until discovery finds a service this
                    // app speaks. Reporting "Connected" here showed a green
                    // "Connected" for the Apache while every write was dropped.
                    Log.i(TAG, "GATT link up (status=$status); discovering services")
                    _state.value = ConnectionState.CONNECTING
                    try {
                        // Discovery must NOT be issued while the MTU exchange is
                        // in flight: the stack answers it with BUSY and
                        // `onServicesDiscovered` is then never called, so the
                        // link sits "connected" with no write characteristic
                        // forever. This was invisible on the Ntorq and showed up
                        // on the first peripheral that took the MTU seriously.
                        if (g.requestMtu(Gatt.REQUESTED_MTU)) {
                            mainHandler.postDelayed(
                                { startDiscovery(g) },
                                DISCOVERY_FALLBACK_MS,
                            )
                        } else {
                            startDiscovery(g)
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "post-connect setup refused: ${e.message}")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT disconnected (status=$status)")
                    _state.value = ConnectionState.DISCONNECTED
                    // Must release the client here, not just clear the
                    // characteristic: `connect()` bails out while `gatt` is
                    // non-null, so leaving a dead client in place made every
                    // subsequent reconnect a silent no-op. See [closeGatt].
                    closeGatt(g)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            val match = GattProfile.ALL.firstNotNullOfOrNull { profile ->
                resolveVerifiedService(g, profile)?.let { profile to it }
            }
            if (match == null) {
                // Say what *was* there. Without this list, an Apache that offers
                // only its own `5500` service produced a bare "not found" and
                // took a separate nRF Connect session to diagnose.
                val offered = g.services.orEmpty().joinToString { it.uuid.toString() }
                Log.e(
                    TAG,
                    "no supported service on ${g.device.address} (status=$status); " +
                        "it offers: [$offered]. Disconnecting.",
                )
                unsupportedDevice = true
                runCatching { g.disconnect() }
                return
            }
            val (profile, svc) = match
            val ch = svc.getCharacteristic(UUID.fromString(profile.writeUuid))
            if (ch == null) {
                // Unreachable after resolveVerifiedService, but the compiler
                // needs the check and an explicit refusal beats a crash.
                Log.w(TAG, "write characteristic ${profile.writeUuid} missing")
                return
            }
            writeChar = ch
            _profile.value = profile
            Log.i(
                TAG,
                "service ready: ${profile.id} (${svc.uuid}), " +
                    "write char props=0x${ch.properties.toString(16)}",
            )
            // Usable from here: the write characteristic exists. Set before the
            // CCCD writes so a reply to a challenge that arrives the instant
            // notifications switch on is not dropped as "link not ready".
            _state.value = ConnectionState.CONNECTED
            // Listen before the CCCD write: the cluster can emit the 0x9A/0xF2
            // challenge the instant the descriptor is written, and anything
            // subscribed after that loses it. setCharacteristicNotification
            // only arms the local callback; the descriptor write follows.
            enableNotifications(g, svc, profile)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            emit(BleFrame(BleFrame.Direction.INBOUND, value, System.currentTimeMillis()))
        }

        @Deprecated("Superseded by the ByteArray overload on API 33+")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val v = characteristic.value ?: return
            emit(BleFrame(BleFrame.Direction.INBOUND, v, System.currentTimeMillis()))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU=$mtu status=$status")
            // The MTU exchange is done, so discovery is safe now. Calling this
            // here rather than from onConnectionStateChange is what makes
            // onServicesDiscovered actually fire.
            startDiscovery(g)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            Log.d(TAG, "descriptor write status=$status")
            // One CCCD write at a time: firing the next while one is pending
            // makes the stack refuse it, so the queue advances here.
            writeNextCccd(g)
        }
    }

    /** Pending CCCD writes, drained one per [onDescriptorWrite]. Guarded because
     * the callback arrives on a binder thread, not the caller's. */
    private val cccdQueue = ArrayDeque<BluetoothGattDescriptor>()
    private val cccdLock = Any()

    @SuppressLint("MissingPermission")
    private fun writeNextCccd(g: BluetoothGatt) {
        val next = synchronized(cccdLock) {
            if (cccdQueue.isNotEmpty()) cccdQueue.removeFirst() else null
        }
        if (next == null) {
            // Queue drained: every subscription has been answered.
            if (g === gatt && !_subscribed.value) {
                Log.i(TAG, "notifications enabled")
                _subscribed.value = true
            }
            return
        }
        // The two writeDescriptor overloads differ in return type: the API-33 one
        // returns an Int status code, the deprecated one a Boolean. An
        // if/else mixing them infers `Comparable & Serializable`, so
        // normalise both to Boolean here.
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                g.writeDescriptor(next, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothGatt.GATT_SUCCESS
            } catch (e: SecurityException) {
                Log.e(TAG, "CCCD write refused: ${e.message}")
                false
            }
        } else {
            @Suppress("DEPRECATION")
            next.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            try {
                @Suppress("DEPRECATION")
                g.writeDescriptor(next)
            } catch (e: SecurityException) {
                Log.e(TAG, "CCCD write refused: ${e.message}")
                false
            }
        }
        if (!ok) {
            Log.w(TAG, "CCCD write refused; continuing with the rest")
            writeNextCccd(g)
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices(g: BluetoothGatt): Boolean {
        val ok = g.discoverServices()
        if (!ok) Log.w(TAG, "discoverServices refused")
        return ok
    }

    /**
     * Start service discovery exactly once per connection.
     *
     * Two callers race here on purpose: [onMtuChanged] in the normal case, and
     * the fallback timer posted at connect for stacks that never report an MTU.
     * A stale `g` from a previous connection is ignored so a reconnect can
     * never inherit its predecessor's attempt.
     */
    @SuppressLint("MissingPermission")
    private fun startDiscovery(g: BluetoothGatt) {
        if (g !== gatt || discoveryStarted) return
        discoveryStarted = true
        mainHandler.removeCallbacksAndMessages(null)
        if (!discoverServices(g)) discoveryStarted = false
    }

    /**
     * Resolve the service that actually carries this protocol.
     *
     * The Scoot spec calls this the "verified-open gate", and the ordering
     * matters:
     *
     *  1. The exact service UUID with both characteristics is the primary path.
     *  2. If the service UUID is present but a characteristic is missing, that
     *     is a **hard failure** — not a fallback. A cluster advertising the
     *     service without the write characteristic cannot be driven, and
     *     silently picking another service would drive the wrong one.
     *  3. Only when the service UUID is absent entirely do we fall back to any
     *     GATT service exposing both characteristic UUIDs. The characteristics
     *     are the true protocol fingerprint: a variant cluster that moves the
     *     service UUID still resolves.
     *
     * Observed on the owner's Ntorq: the cluster advertises **no service UUID**
     * at all in its advertising payload, which is why scanning must be
     * name-based. Discovery is a different question and this is where it is
     * answered.
     */
    @SuppressLint("MissingPermission")
    private fun resolveVerifiedService(
        g: BluetoothGatt,
        profile: GattProfile,
    ): android.bluetooth.BluetoothGattService? {
        val serviceUuid = UUID.fromString(profile.serviceUuid)
        val writeUuid = UUID.fromString(profile.writeUuid)
        val readUuid = UUID.fromString(profile.requiredNotifyUuid)

        val exact = g.getService(serviceUuid)
        if (exact != null) {
            val hasBoth = exact.getCharacteristic(writeUuid) != null &&
                exact.getCharacteristic(readUuid) != null
            if (hasBoth) return exact
            Log.e(
                TAG,
                "service $serviceUuid present but a characteristic is missing; " +
                    "refusing the ${profile.id} profile (hard failure, no fallback)",
            )
            return null
        }

        val byCharacteristics = g.services?.firstOrNull {
            it.getCharacteristic(writeUuid) != null && it.getCharacteristic(readUuid) != null
        }
        if (byCharacteristics == null) {
            // Normal for every profile but the one the vehicle uses, so not an error.
            Log.d(TAG, "${profile.id}: no service exposes both $writeUuid and $readUuid")
        } else {
            Log.w(
                TAG,
                "service UUID $serviceUuid absent; resolved by characteristics on ${byCharacteristics.uuid}",
            )
        }
        return byCharacteristics
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        g: BluetoothGatt,
        svc: android.bluetooth.BluetoothGattService,
        profile: GattProfile,
    ) {
        val pending = ArrayList<BluetoothGattDescriptor>()
        // Written on every connection, never skipped as "already on": the
        // Apache forgets the subscription between links, and a test that relied
        // on it persisting got silence (nRF Connect, 2026-11-14 11:38).
        for (uuid in profile.notifyUuids) {
            val ch = svc.getCharacteristic(UUID.fromString(uuid)) ?: continue
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD)
            // Parentheses are required: `and` is an infix *function*, not a bitwise
            // operator, so `props and FLAG != 0` does not parse as intended.
            val canNotify = (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            if (cccd != null && canNotify) pending += cccd
            else Log.d(TAG, "no notifiable CCCD for $uuid")
        }
        if (pending.isEmpty()) {
            _subscribed.value = true
            return
        }
        synchronized(cccdLock) {
            cccdQueue.clear()
            cccdQueue.addAll(pending)
        }
        writeNextCccd(g)
    }

    companion object {
        private const val TAG = "ScooterBle"

        /**
         * How long to wait for `onMtuChanged` before starting discovery anyway.
         *
         * Long enough for a slow peripheral to answer the MTU exchange, short
         * enough that a stack which never reports it does not stall the link.
         */
        private const val DISCOVERY_FALLBACK_MS = 2500L

        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Cheap structural check for an inbound frame, model-agnostic.
         *
         * **Deliberately does not verify a checksum.** Decompiling the official
         * builders showed that the outbound mobile-data and navigation frames
         * carry none, and several inbound layouts (`ApacheBasicData`,
         * `NtorqWifiPasswordFrameData`) declare `CHECKSUM` at index 18 or 19
         * while others declare none. A checksum check here would reject valid
         * frames.
         *
         * Model-specific validation belongs in each [dev.tvsopen.protocol.ProtocolDriver],
         * which knows its own layout.
         */
        fun looksValid(bytes: ByteArray): Boolean {
            if (bytes.size < 3) return false
            val start = bytes[0].toInt() and 0xFF
            return start == 0x5A || start == 0x5B || start == 0x9A || start == 0x9B
        }

        /** Wrap a UUID string for scanning filters. */
        fun parcelUuid(uuid: String): ParcelUuid = ParcelUuid(UUID.fromString(uuid))
    }
}
