package dev.tvsopen.transport

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.tvsopen.R
import dev.tvsopen.log.Log
import dev.tvsopen.nav.NavInstruction
import dev.tvsopen.nav.NavigationBridge
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.protocol.ApacheN112Driver
import dev.tvsopen.protocol.Command
import dev.tvsopen.protocol.DashState
import dev.tvsopen.protocol.DriverRegistry
import dev.tvsopen.protocol.GattProfile
import dev.tvsopen.protocol.NtorqDriver
import dev.tvsopen.protocol.OpeningContext
import dev.tvsopen.protocol.OutboundId
import dev.tvsopen.protocol.PacketBuilder
import dev.tvsopen.protocol.PhoneStatus
import dev.tvsopen.protocol.ProtocolDriver
import dev.tvsopen.protocol.VehicleModel
import dev.tvsopen.session.ClusterAlert
import dev.tvsopen.session.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the BLE link and the HUD alive for as long as the session runs.
 *
 * A foreground service, because a ride outlasts any background execution
 * window and the cluster drops the link the moment the presence packet stops.
 */
class RideService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bridge: NavigationBridge? = null
    private var ble: ScooterBleService? = null
    /** Parents the per-connection collectors so a reconnect or disconnect
     * cancels them. Without this every connect() added three more collectors
     * on the same flows, and a stale one could restart the *old* bridge's
     * keepalive after it was stopped — duplicate pings. */
    private var linkJob: Job? = null
    private var lastModel: VehicleModel = VehicleModel.NTORQ
    private var lastAddress: String? = null
    /** Driver for the current link; needed outside connect() by the rider-name send. */
    private var driver: ProtocolDriver? = null
    /** Replays cluster media keys and call-switch presses on the phone. */
    private var mediaKeys: MediaKeys? = null
    private var callButtons: CallButtons? = null
    /** The Apache's handlebar end-call button and its reply SMS. */
    private var callReply: CallReply? = null
    /**
     * Last call-switch value seen on 0x10 byte 12.
     *
     * The cluster repeats the switch inside a ~50 Hz stream, so acting on every
     * frame would answer and hang up twenty times in half a second. Only a
     * change from 0 to a command value is a press.
     */
    private var lastCallSwitch = 0
    /**
     * Unexpected-drop recovery.
     *
     * The cluster drops the link on its own schedule (ignition, range, a stack
     * hiccup) and never reconnects to us. The official app retries with a
     * 5/10/20/40/60-second backoff, capped at ten attempts, and only then
     * gives up. Without it a ride that starts with one drop is over.
     */
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    /** Stops the service if a background wait outlasts [HANDOFF_AFTER_MS]; see [scheduleHandoff]. */
    private var handoffJob: Job? = null
    /** Set by [teardown] so a deliberate disconnect does not trigger a retry. */
    private var tearingDown = false
    /**
     * Held while connected, released on disconnect and on teardown.
     *
     * A foreground service alone does not keep the CPU awake: with the screen
     * off the coroutines driving the 2 s keepalive and 1.5 s nav control stall,
     * the cluster times out the HUD (the control frame is a liveness signal),
     * and then the link. The Doze exemption keeps the system from killing us;
     * this keeps us actually running. Partial, not full — the screen stays off.
     */
    private var linkWakeLock: android.os.PowerManager.WakeLock? = null


    private fun holdWakeLock() {
        if (linkWakeLock?.isHeld == true) return
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return
        linkWakeLock = pm.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK, "TVSRide:link",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.i(TAG, "wake lock held for link lifetime")
    }

    private fun releaseWakeLock() {
        runCatching { if (linkWakeLock?.isHeld == true) linkWakeLock?.release() }
        linkWakeLock = null
    }

    override fun onCreate() {
        super.onCreate()
        // Logs go to the app's own files so a crash can still be diagnosed.
        Log.enableFileSink(java.io.File(filesDir, "logs"))
        Log.i(TAG, "service created (sdk ${android.os.Build.VERSION.SDK_INT})")
        // Rides must record when auto-connect starts the service with no
        // screen ever opened in this process; MainActivity's call alone left
        // the ride log null then. Idempotent.
        Session.attachStorage(applicationContext)

        // A `connectedDevice` foreground service needs BLUETOOTH_CONNECT or
        // BLUETOOTH_SCAN to be *granted*, not merely declared. Starting it
        // before the permission dialog is answered throws SecurityException
        // here, so degrade instead of throwing.
        //
        // Note this catch only covers the synchronous SecurityException. A
        // rejected *notification* is reported asynchronously by the system as a
        // RemoteServiceException and cannot be caught here — that is why
        // `buildNotification` creates the channel itself.
        try {
            startForeground(NOTIF_ID, buildNotification("Starting…"))
        } catch (e: SecurityException) {
            Log.e(TAG, "cannot enter foreground: ${e.message}")
            Log.e(TAG, "BLUETOOTH_CONNECT/SCAN not granted; stopping rather than idling")
            // Stopping is the point. If we keep running after a refused
            // startForeground, Android kills the whole app ~10s later with
            // ForegroundServiceDidNotStartInTimeException — a crash that blames
            // whichever Compose handler happened to start the service, rather
            // than the missing permission. MainActivity checks first, so
            // reaching this means we were started some other way.
            stopSelf()
            return
        }
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        bluetoothReceiverRegistered = true
    }

    private var bluetoothReceiverRegistered = false

    /**
     * Re-arm auto-connect when the phone's Bluetooth comes back on.
     *
     * Turning Bluetooth off drops the link, and the background connect issued
     * after the drop cannot start while the radio is off. Without this the app
     * stayed disconnected until it was reopened.
     */
    private val bluetoothStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                android.bluetooth.BluetoothAdapter.STATE_ON -> onBluetoothOn()
                android.bluetooth.BluetoothAdapter.STATE_OFF -> Log.i(TAG, "Bluetooth turned off")
            }
        }
    }

    private fun onBluetoothOn() {
        val prefs = RidePrefs.load(this)
        val address = lastAddress ?: prefs.lastAddress ?: return
        if (!prefs.autoConnect || tearingDown) return
        if (ble?.state?.value == ConnectionState.CONNECTED) return
        val model = lastAddress?.let { lastModel }
            ?: prefs.lastModelId?.let { runCatching { VehicleModel.valueOf(it) }.getOrNull() }
            ?: VehicleModel.NTORQ
        Log.i(TAG, "Bluetooth is back on; waiting for $address")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(AUTO_RECONNECT_DELAY_MS)
            if (ble?.state?.value == ConnectionState.CONNECTED) return@launch
            connect(address, model, background = true)
        }
    }

    /** Called once the activity has the runtime BLE permissions. */
    private fun enterForeground() {
        runCatching { startForeground(NOTIF_ID, buildNotification("Connecting…")) }
            .onFailure { Log.w(TAG, "foreground still refused: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                if (address.isNullOrBlank()) {
                    Log.w(TAG, "connect requested without an address; ignoring")
                } else {
                    val model = intent.getStringExtra(EXTRA_MODEL)
                        ?.let { runCatching { VehicleModel.valueOf(it) }.getOrNull() }
                        ?: VehicleModel.NTORQ
                    connect(address, model, intent.getBooleanExtra(EXTRA_BACKGROUND, false))
                }
            }
            ACTION_START_NAV -> bridge?.startNavigation()
            ACTION_STOP_NAV -> bridge?.stopNavigation()
            ACTION_UPDATE_RIDER_NAME -> sendRiderNameIfConnected()
            ACTION_UPDATE_DEVICE_NAME -> sendDeviceNameIfConnected()
            ACTION_DISCONNECT -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            null -> Unit
            // START_STICKY delivers a null intent after the process is killed.
            // Falling through leaves the service alive and foregrounded with no
            // link, which looks connected on the dash and never recovers. Re-open
            // the last known cluster instead; harmless when there is none.
            else -> resumeLastConnection()
        }
        return START_STICKY
    }

    /**
     * Re-open the last link, if one is remembered and ride mode is still on.
     *
     * This is what makes `START_STICKY` and `RECEIVE_BOOT_COMPLETED` mean
     * something: both start this service without an address, and without this
     * the app would come back to life doing nothing.
     */
    private fun resumeLastConnection() {
        val prefs = RidePrefs.load(this)
        val address = prefs.lastAddress
        if (address.isNullOrBlank()) {
            Log.i(TAG, "sticky restart with no remembered cluster; idling")
            return
        }
        if (!prefs.autoConnect && ble?.let { it.state.value == ConnectionState.CONNECTED } != true) {
            // Only resume automatically when auto-connect is on; otherwise stay
            // idle rather than silently reattaching to the vehicle.
            Log.i(TAG, "sticky restart; not resuming (auto-connect is off)")
            return
        }
        val model = prefs.lastModelId
            ?.let { runCatching { VehicleModel.valueOf(it) }.getOrNull() }
            ?: VehicleModel.NTORQ
        Log.i(TAG, "sticky restart; resuming $address")
        // Background: the vehicle may well be off, and a direct connect would
        // give up after ~30 s.
        connect(address, model, background = true)
    }

    /**
     * Connect and begin streaming. Called from the UI once a scooter is picked.
     *
     * Returns false when the link could not even be started (no adapter, bad
     * address, missing permission) — the caller gets "not started" rather than
     * silence, because `transport.connect` itself only returns a Boolean and a
     * SecurityException from `connectGatt` would otherwise kill the service.
     */
    fun connect(address: String, requested: VehicleModel, background: Boolean = false): Boolean {
        if (shouldReuseLink(lastAddress, address, ble?.state?.value)) {
            Log.i(TAG, "connect: already ${ble?.state?.value?.label?.lowercase()} to $address; keeping the link")
            return true
        }
        // Stop the previous link first: same scope, so a second connect without
        // this leaves both keepalive loops running and the cluster gets double
        // pings.
        linkJob?.cancel()
        linkJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        handoffJob?.cancel()
        handoffJob = null
        tearingDown = false
        bridge?.stopKeepalive()
        bridge?.stopNavigation()

        // By now the permissions the service was refused at startup have been
        // granted, so the foreground notification can go up properly.
        enterForeground()
        val transport = ble ?: ScooterBleService(applicationContext).also { ble = it }
        if (transport.isOpen) {
            // A pending background connect (or a link being replaced) still
            // holds the GATT client, and `transport.connect` refuses while it
            // does. Close it first, then come back.
            scope.launch {
                runCatching { transport.disconnect() }
                connect(address, requested, background)
            }
            return true
        }
        val prefs = RidePrefs.load(this)
        val knownProfile = prefs.detectedProfile(address)
        val driver = driverFor(requested, knownProfile)
        // The Apache driver may stand in for a model the rider never picked
        // (a fresh scan defaults to Ntorq); show the vehicle it actually is.
        val model = if (driver is ApacheN112Driver) driver.model else requested
        val b = NavigationBridge(transport, scope, driver)
        bridge = b
        this.driver = driver
        mediaKeys = mediaKeys ?: MediaKeys(applicationContext)
        callButtons = callButtons ?: CallButtons(applicationContext)
        callReply = callReply ?: CallReply(applicationContext)
        lastCallSwitch = 0
        lastModel = model
        lastAddress = address
        prefs.updateLastConnection(address, model.name)

        val waiting = if (background) ConnectionState.RECONNECTING else ConnectionState.CONNECTING
        Session.onConnection(waiting, model, address)
        Log.i(
            TAG,
            "${if (background) "waiting for" else "connecting to"} ${model.displayName} at $address " +
                "(driver ${driver::class.simpleName}, profile ${knownProfile ?: "not yet detected"})",
        )
        notify(if (background) "Waiting for ${model.displayName}…" else "Connecting to ${model.displayName}…")

        val started = try {
            transport.connect(address, background)
        } catch (e: SecurityException) {
            Log.e(TAG, "connect refused (permission?): ${e.message}")
            false
        }
        if (!started) {
            // The three causes need saying apart. "link already active or
            // address rejected" was two unrelated failures in one string, and
            // the dead-link one — a GATT client left over from a previous
            // session — looked like a transient no-op rather than the reason the
            // app could not reconnect all day.
            val alreadyUp = Session.connection.value.state == ConnectionState.CONNECTED
            val reason = when {
                transport.adapter == null -> "no Bluetooth adapter"
                alreadyUp -> "already connected"
                else -> "address rejected, or a stale GATT client is still held"
            }
            Log.w(TAG, "connect not started: $reason")
            notify("Could not start connection ($reason)")
            Session.onConnection(ConnectionState.DISCONNECTED, model, address)
            return false
        }
        if (background) scheduleHandoff(address)

        linkJob = scope.launch {
            // The link owns a wake lock while connected; without it the
            // keepalive and nav loops stall the moment the screen goes off.
            // Held on CONNECTED and released on DISCONNECTED rather than for
            // the whole job: after a drop the collectors stay alive waiting
            // for a reconnect, and holding a lock through that idle wait would
            // drain the battery for nothing.
            // One for the bridge (nav state)…
            //
            // Driven by `Session.hud`, not by BLE frames. It used to be a
            // `transport.frames.collect` that re-read the instruction on every
            // frame through `MapsNotificationListenerRef` — and that ref was
            // never assigned anywhere, so the bridge was handed `null` every
            // time. Navigation mirroring therefore sent **nothing at all**, and
            // looked fine because the UI strip reads `Session.hud` itself and
            // never went through the bridge. Sampling navigation on the BLE
            // clock was wrong twice over: it couples two unrelated rates, and it
            // means a nav step waits for the next frame to be forwarded.
            launch {
                Session.hud.collect { hud ->
                    b.onInstruction(
                        NavInstruction(
                            title = hud.instructionTitle.orEmpty(),
                            street = hud.street.orEmpty(),
                            distanceMeters = hud.distanceMeters,
                            etaMinutes = hud.etaMinutes,
                            pictogram = hud.pictogramId,
                            provider = hud.provider.orEmpty(),
                        ).takeIf { hud.active },
                    )
                }
            }
            // …one decoding telemetry into the UI session, answering the
            // cluster's handshake when it challenges…
            launch {
                transport.frames.collect { frame ->
                    if (frame.direction != BleFrame.Direction.INBOUND) return@collect
                    val response = driver.buildChallengeResponse(frame.bytes)
                    if (response != null) {
                        Log.i(TAG, "answering cluster challenge")
                        transport.write(response)
                    } else {
                        if (driver is ApacheN112Driver) logUnusualN112(frame.bytes)
                        val decoded = driver.decode(frame.bytes)
                        if (decoded != null) {
                            Session.onFrame(decoded)
                            handleClusterButton(driver, decoded)
                        }
                    }
                }
            }
            // …and one following the link state. The bridge keeps its own HUD
            // counters, so frames-sent and errors are mirrored into the Session
            // the UI actually reads — otherwise "Hud frames sent" stays 0
            // forever while the bridge counts privately.
            launch {
                transport.state.collect { st ->
                    Session.onConnection(st, model, address)
                    Log.d(TAG, "state -> $st")
                    when (st) {
                        ConnectionState.CONNECTED -> {
                            val detected = transport.profile.value
                            if (needsDriverSwitch(knownProfile, detected)) {
                                switchDriver(address, model, detected!!)
                                return@collect
                            }
                            if (detected != null && detected.id != knownProfile) {
                                prefs.updateDetectedProfile(address, detected.id)
                            }
                            handoffJob?.cancel()
                            reconnectAttempt = 0
                            notify("Connected to ${model.displayName}")
                            holdWakeLock()
                            // Auto-start here, not only from the activity's
                            // LaunchedEffect: if the UI process dies while the
                            // screen is off, nothing else sends START_NAV and
                            // the mirror silently stays down. startNavigation
                            // early-returns when already running, and the
                            // toggle's explicit STOP_NAV still wins — the next
                            // onInstruction(null) clears it.
                            if (RidePrefs.load(this@RideService).navigationMirroring) {
                                b.startNavigation()
                            }
                            // Clusters with an opening sequence get the greeting
                            // and the keepalive from it instead, once
                            // notifications are on (the collector below).
                            if (driver.hasOpeningSequence) return@collect
                            // Greeting first, like the official sendInitialData:
                            // the cluster shows it on ignition, and a name saved
                            // while disconnected goes out on the next connect.
                            sendRiderNameIfConnected()
                            if (driver.capabilities.keepalive) {
                                b.startKeepalive {
                                    DashState(
                                        timeMillis = System.currentTimeMillis(),
                                        batteryBars = batteryBars(),
                                        signalBars = 4,
                                    )
                                }
                            }
                        }
                        ConnectionState.DISCONNECTED -> {
                            releaseWakeLock()
                            notify("Disconnected")
                            scheduleReconnect(address, model)
                        }
                        else -> Unit
                    }
                }
            }
            // …one for clusters that wait to be spoken to (the Apache N112):
            // opening frames once notifications are on, then the keepalive and
            // the periodic phone-status frame. `collectLatest`, so a drop (which
            // clears `subscribed`) stops the loop and a reconnect restarts it.
            if (driver.hasOpeningSequence) launch {
                transport.subscribed.collectLatest { on ->
                    if (!on) return@collectLatest
                    if (needsDriverSwitch(knownProfile, transport.profile.value)) return@collectLatest
                    val opening = driver.openingSequence(openingContext())
                    Log.i(TAG, "sending opening sequence (${opening.size} frames)")
                    for (frame in opening) {
                        transport.write(frame)
                        delay(OPENING_GAP_MS)
                    }
                    if (driver.capabilities.keepalive) {
                        b.startKeepalive { DashState(timeMillis = System.currentTimeMillis()) }
                    }
                    while (true) {
                        delay(PHONE_STATUS_EVERY_MS)
                        val status = driver.buildPhoneStatus(phoneStatus()) ?: break
                        transport.write(status)
                    }
                }
            }
            launch {
                var lastAlert: ClusterAlert? = null
                // collectLatest: a new alert (or the call ending) cancels a
                // caller repeat still running for the previous one.
                Session.alert.collectLatest { alert ->
                    val previous = lastAlert
                    lastAlert = alert
                    val t = ble ?: return@collectLatest
                    if (t.state.value != ConnectionState.CONNECTED) return@collectLatest
                    if (alert != null) {
                        sendAlert(driver, t, alert)
                    } else if (previous?.kind == ClusterAlert.Kind.CALL) {
                        sendCallEnded(driver, t)
                    }
                }
            }
            launch {
                b.hud.collect { h ->
                    Session.onHud { it.copy(framesSent = h.framesSent, lastError = h.lastError) }
                }
            }
        }
        return true
    }

    /**
     * Pick the driver for a link.
     *
     * The detected Bluetooth layout decides the protocol: a vehicle offering
     * the Apache `5500` service speaks N112 whatever model was picked, because
     * no other driver can talk to that service. Otherwise the model decides, as
     * before — `sharesNtorqProtocol()` rather than a list of models here, so
     * this cannot drift from the set `Nucleus` registers.
     */
    private fun driverFor(model: VehicleModel, profileId: String?): ProtocolDriver = when {
        profileId == GattProfile.APACHE_N112.id ->
            ApacheN112Driver(if (model.isApache()) model else VehicleModel.APACHE_160)
        model.sharesNtorqProtocol() -> NtorqDriver()
        else -> DriverRegistry.forModel(model)
    }

    /**
     * Whether the link was opened with the wrong protocol for what the vehicle
     * turned out to offer. Only the N112 / not-N112 split matters: every other
     * profile is handled by the model-based choice in [driverFor].
     */
    private fun needsDriverSwitch(knownProfile: String?, detected: GattProfile?): Boolean {
        if (detected == null) return false
        val wasN112 = knownProfile == GattProfile.APACHE_N112.id
        val isN112 = detected == GattProfile.APACHE_N112
        return wasN112 != isN112
    }

    /**
     * Remember what the vehicle offered and reconnect with the matching driver.
     *
     * Happens once per vehicle: the profile is saved, so the next connection
     * starts with the right driver. Costs one extra connect (a couple of
     * seconds) on first contact with an Apache.
     */
    private fun switchDriver(address: String, model: VehicleModel, detected: GattProfile) {
        val prefs = RidePrefs.load(this)
        prefs.updateDetectedProfile(address, detected.id)
        val next = if (detected == GattProfile.APACHE_N112 && !model.isApache()) {
            VehicleModel.APACHE_160
        } else {
            model
        }
        if (next != model) prefs.updateSelectedModel(address, next.name)
        Log.i(TAG, "$address offers ${detected.id}; reconnecting with the ${next.displayName} driver")
        val transport = ble ?: return
        scope.launch {
            linkJob?.cancel()
            linkJob = null
            runCatching { transport.disconnect() }
            connect(address, next)
        }
    }

    /** Clock, names and phone status for a driver's opening frames. */
    private fun openingContext(): OpeningContext {
        val prefs = RidePrefs.load(this)
        val now = java.util.Calendar.getInstance()
        return OpeningContext(
            hourOfDay = now.get(java.util.Calendar.HOUR_OF_DAY),
            minute = now.get(java.util.Calendar.MINUTE),
            riderName = prefs.riderName,
            deviceNickname = prefs.deviceNickname,
            phoneStatus = phoneStatus(),
        )
    }

    /**
     * Real phone values for the cluster's status icons, in the official scales.
     *
     * Each source degrades to 0 on its own rather than failing the frame:
     * network type needs READ_PHONE_STATE and missed calls READ_CALL_LOG (both
     * optional caller-ID permissions). Unread messages are always 0 — reading
     * them needs SMS access, which this app does not request.
     */
    private fun phoneStatus(): PhoneStatus {
        val percent = getSystemService(android.os.BatteryManager::class.java)
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val tm = getSystemService(android.telephony.TelephonyManager::class.java)
        val signal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { tm?.signalStrength?.level }.getOrNull() ?: 0
        } else {
            0
        }
        val networkType = if (granted(Manifest.permission.READ_PHONE_STATE)) {
            runCatching { tm?.dataNetworkType }.getOrNull() ?: 0
        } else {
            0
        }
        return PhoneStatus(
            signalLevel = signal,
            batteryLevel = ApacheN112Driver.batteryLevel(percent),
            missedCalls = if (granted(Manifest.permission.READ_CALL_LOG)) newMissedCalls() else 0,
            // The cluster's SMS marker; follows the message-mirroring toggle.
            unreadMessages = if (RidePrefs.load(this).mirrorMessages) Session.unreadSms.value else 0,
            networkType = ApacheN112Driver.networkClass(networkType),
        )
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Missed calls not yet seen in the phone app, as the cluster's badge. */
    @SuppressLint("MissingPermission")
    private fun newMissedCalls(): Int = runCatching {
        contentResolver.query(
            android.provider.CallLog.Calls.CONTENT_URI,
            arrayOf(android.provider.CallLog.Calls._ID),
            "${android.provider.CallLog.Calls.TYPE} = ? AND ${android.provider.CallLog.Calls.NEW} = 1",
            arrayOf(android.provider.CallLog.Calls.MISSED_TYPE.toString()),
            null,
        )?.use { it.count } ?: 0
    }.getOrDefault(0)

    /** Re-send the device nickname to clusters that show one. */
    private fun sendDeviceNameIfConnected() {
        val transport = ble ?: return
        if (driver !is ApacheN112Driver) return
        if (transport.state.value != ConnectionState.CONNECTED) {
            Log.i(TAG, "device nickname saved; will send on next connection")
            return
        }
        val nickname = RidePrefs.load(this).deviceNickname ?: ApacheN112Driver.DEFAULT_NICKNAME
        scope.launch {
            if (transport.write(ApacheN112Driver.deviceName(nickname))) {
                Log.i(TAG, "device nickname sent (${nickname.length} chars)")
            }
        }
    }

    /** Coarse phone battery as 0-5 bars, for the cluster ping packet. */
    private fun batteryBars(): Int {
        val pct = android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY
        val level = getSystemService(android.os.BatteryManager::class.java)
            ?.getIntProperty(pct) ?: -1
        if (level < 0) return 0
        return (level / 20).coerceIn(0, 5)
    }

    /**
     * Send the saved rider name when there is a live link, else just remember it.
     *
     * A blank name is not "send an empty greeting" — the cluster shows
     * `HELLO RIDER` in that state, so there is nothing to write. A save made
     * while disconnected goes out on the next connect instead, which is why
     * this never warns: no link is the normal case, not a fault.
     */
    private fun sendRiderNameIfConnected() {
        val name = RidePrefs.load(this).riderName
        val transport = ble
        val drv = driver
        if (name.isNullOrBlank() || transport == null || drv == null) return
        if (transport.state.value != ConnectionState.CONNECTED) {
            Log.i(TAG, "rider name saved; will send on next connection")
            return
        }
        val frame = drv.buildRiderName(name) ?: return
        scope.launch {
            if (transport.write(frame)) Log.i(TAG, "rider name sent (${name.length} chars)")
        }
    }

    private suspend fun sendAlert(
        driver: ProtocolDriver,
        transport: ScooterBleService,
        alert: ClusterAlert,
    ) {
        when (alert.kind) {
            ClusterAlert.Kind.CALL -> {
                val number = alert.subtitle.substringAfterLast('·').trim().ifBlank { alert.subtitle }
                val name = alert.title.takeIf { it.isNotBlank() && it != number }
                val frames = driver.buildCallerFrames(number, null, name)
                val repeatMs = driver.callerRepeatMs
                if (frames.isNotEmpty() && repeatMs != null) {
                    Log.i(TAG, "cluster call alert: '$name' (repeating every $repeatMs ms while ringing)")
                    repeatCallerWhileRinging(transport, frames, repeatMs)
                } else if (frames.isNotEmpty()) {
                    Log.i(TAG, "cluster call alert: '$name' '$number' (${frames.size} frames)")
                    frames.forEach { transport.write(it) }
                } else {
                    // Generic fallback: a plain text row with the caller.
                    val text = if (name != null) "$name $number" else number.ifBlank { alert.title }
                    transport.write(PacketBuilder.textRow(OutboundId.CALLER, text))
                }
            }
            ClusterAlert.Kind.MESSAGE -> {
                val text = alert.title.take(17)
                Log.i(TAG, "cluster message alert: '$text'")
                val frame = driver.buildSms(text) ?: PacketBuilder.textRow(OutboundId.SMS, text)
                transport.write(frame)
            }
        }
    }

    /**
     * Full, unthrottled log lines for the N112 frames worth seeing whole: any
     * with a `Y` (handlebar call button) record, and any the parser could not
     * read to the end. The frame dump is paced to one per data id per second,
     * so a rare record could otherwise go unseen.
     */
    private fun logUnusualN112(raw: ByteArray) {
        val hex = raw.joinToString(" ") { "%02X".format(it) }
        if (ApacheN112Driver.parseRecords(raw).any { it.tag == 'Y' }) {
            Log.i(TAG, "N112 Y record: $hex")
        }
        val left = ApacheN112Driver.unparsedBytes(raw)
        if (left > 0) Log.i(TAG, "N112 frame not fully parsed ($left bytes left): $hex")
    }

    /**
     * Resend the caller until the phone stops ringing (answered, rejected or
     * ended). The alert clearing also stops it, via collectLatest. Capped, so
     * a ringing state that never reads back cannot hold the screen forever.
     */
    private suspend fun repeatCallerWhileRinging(
        transport: ScooterBleService,
        frames: List<ByteArray>,
        repeatMs: Long,
    ) {
        val until = System.currentTimeMillis() + CALLER_REPEAT_MAX_MS
        var sends = 0
        while (System.currentTimeMillis() < until && phoneRinging() != false) {
            frames.forEach { transport.write(it) }
            sends++
            delay(repeatMs)
        }
        Log.i(TAG, "caller repeat stopped after $sends sends (ringing=${phoneRinging()})")
    }

    /** Whether the phone is ringing; null when it cannot be read (no READ_PHONE_STATE). */
    @Suppress("DEPRECATION")
    private fun phoneRinging(): Boolean? = runCatching {
        getSystemService(android.telephony.TelephonyManager::class.java)?.callState ==
            android.telephony.TelephonyManager.CALL_STATE_RINGING
    }.getOrNull()

    private suspend fun sendCallEnded(driver: ProtocolDriver, transport: ScooterBleService) {
        // Best-effort clear: send an idle/ended caller frame where the driver
        // supports it. The cluster typically times the display out on its own.
        val frame = driver.buildCallerInfo("", null, Command.CALL_IDLE)
        if (frame != null) {
            Log.i(TAG, "cluster call alert cleared")
            transport.write(frame)
        }
    }

    /**
     * Act on the two inbound channels the cluster uses as a remote control.
     *
     * `0x6B` carries media commands on both families (`0x54` is the ACK/doc
     * channel, not a command). `0x10` byte 12 carries the call switch on the
     * Jupiter; on the Ntorq the same offset is transcribed as a location tag,
     * so the call path is gated on the driver's capability rather than on the
     * id alone.
     */
    private fun handleClusterButton(driver: ProtocolDriver, frame: dev.tvsopen.protocol.DecodedFrame) {
        if (frame.dataId == ApacheN112Driver.RECORDS_DATA_ID) {
            // Apache N112: the `Y` record is the handlebar call button.
            frame.field(dev.tvsopen.protocol.Field.CALL_DISCONNECT)?.let { callReply?.onButton(it.toInt(), scope) }
            return
        }
        if (frame.dataId == 0x6B && driver.capabilities.musicKeys) {
            val cmd = frame.field(dev.tvsopen.protocol.Field.MUSIC_COMMAND)?.toInt() ?: return
            mediaKeys?.handle(cmd)
            return
        }
        if (frame.dataId == 0x10 && driver.capabilities.callButtons) {
            val sw = frame.field(dev.tvsopen.protocol.Field.SWITCH_STATUS)?.toInt() ?: 0
            // Edge-trigger: 0 means "no command", 1..4 is a press. The stream
            // repeats the same value for tens of frames, so act only on the
            // transition into a command.
            if (sw in 1..4 && sw != lastCallSwitch) {
                callButtons?.handle(sw)
            }
            lastCallSwitch = sw
        }
    }

    /**
     * Retry an unexpected drop with the official backoff.
     *
     * Launched on the service scope, not on `linkJob`: [connect] cancels
     * `linkJob`, so a retry launched inside it would cancel itself the moment
     * it fired. Ten attempts then stop; a deliberate disconnect never reaches
     * here because [teardown] sets [tearingDown].
     */
    private fun scheduleReconnect(address: String, model: VehicleModel) {
        if (tearingDown) return
        if (reconnectJob?.isActive == true) return
        if (ble?.unsupportedDevice == true) {
            // It offered no service this app can drive; retrying only repeats that.
            Log.w(TAG, "not reconnecting: $address offers no supported service")
            return
        }
        if (RidePrefs.load(this).autoConnect) {
            // Auto-connect: no backoff and no giving up. A background connect
            // request costs almost nothing while it waits — the phone's
            // Bluetooth controller does the waiting — and links the moment the
            // vehicle advertises again, e.g. after the ignition is switched back
            // on. The short delay lets the old GATT client finish closing.
            Log.i(TAG, "link dropped; waiting for $address in the background")
            reconnectJob = scope.launch {
                delay(AUTO_RECONNECT_DELAY_MS)
                if (ble?.state?.value == ConnectionState.CONNECTED) return@launch
                connect(address, model, background = true)
            }
            return
        }
        if (reconnectAttempt >= RECONNECT_DELAYS_MS.size) {
            Log.w(TAG, "giving up after ${RECONNECT_DELAYS_MS.size} reconnect attempts")
            return
        }
        val attempt = reconnectAttempt
        reconnectAttempt++
        val wait = RECONNECT_DELAYS_MS[attempt]
        Log.i(TAG, "unexpected drop; reconnect ${attempt + 1}/${RECONNECT_DELAYS_MS.size} in ${wait}ms")
        reconnectJob = scope.launch {
            delay(wait)
            if (ble?.state?.value == ConnectionState.CONNECTED) return@launch
            connect(address, model)
        }
    }

    /**
     * Hand waiting over to Android after a while, when it can do the waiting.
     *
     * A background connect needs this service running, which means a
     * notification on screen for as long as the vehicle is off — possibly all
     * night. When Companion Device Manager is watching for the vehicle there is
     * no need: after [HANDOFF_AFTER_MS] without it coming back, the service
     * stops, and [VehiclePresenceService] starts it again when the vehicle
     * reappears. The grace period covers a brief drop mid-ride, during which
     * the vehicle may never look "gone" to Android and so would not "reappear".
     */
    private fun scheduleHandoff(address: String) {
        handoffJob?.cancel()
        handoffJob = null
        val observed = RidePrefs.load(this).observedCompanion
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !address.equals(observed, ignoreCase = true)) {
            return
        }
        handoffJob = scope.launch {
            delay(HANDOFF_AFTER_MS)
            if (ble?.state?.value == ConnectionState.CONNECTED) return@launch
            Log.i(TAG, "vehicle not back; stopping until Android reports it in range")
            withContext(Dispatchers.Main) {
                teardown()
                stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun teardown() {
        tearingDown = true
        reconnectJob?.cancel()
        reconnectJob = null
        handoffJob?.cancel()
        handoffJob = null
        linkJob?.cancel()
        linkJob = null
        bridge?.stopKeepalive()
        bridge?.stopNavigation()
        releaseWakeLock()
        driver = null
        // The state collector is gone with linkJob, so publish the disconnect
        // explicitly — otherwise the UI keeps showing the old link as live.
        Session.onConnection(ConnectionState.DISCONNECTED, lastModel, lastAddress)
        // ScooterBleService.disconnect() is suspend so it can take the write
        // mutex. NonCancellable, because onDestroy cancels the scope right
        // after this and a plain child would die before the GATT client closed.
        val transport = ble
        if (transport != null) {
            scope.launch(NonCancellable) {
                runCatching { transport.disconnect() }
                    .onFailure { Log.w(TAG, "disconnect failed: ${it.message}") }
            }
        }
        bridge = null
    }

    override fun onDestroy() {
        if (bluetoothReceiverRegistered) {
            runCatching { unregisterReceiver(bluetoothStateReceiver) }
            bluetoothReceiverRegistered = false
        }
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    /**
     * Create the channel if it is missing.
     *
     * This has to be callable from `buildNotification`, not just from `notify`.
     * `startForeground` posts to the channel too, and posting to a channel that
     * does not exist yet fails as
     * `CannotPostForegroundServiceNotificationException: Bad notification for
     * startForeground` — an exception the *system* posts back to the app's main
     * thread, so the `try`/`catch` around `startForeground` cannot catch it and
     * the process dies. That is exactly what happened: the channel was only
     * created as a side effect of `notify`, which nothing reached because no
     * connection had ever completed, so the first `startForeground` in
     * `onCreate` always faced a channel that had never been created.
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ride", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notify(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        // Before the builder, not after: an FGS notification is a real post.
        ensureChannel()
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, dev.tvsopen.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("TVSRide")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    companion object {
        /** Longest a ringing call keeps the caller screen refreshed. */
        private const val CALLER_REPEAT_MAX_MS = 90_000L

        private const val TAG = "RideService"
        const val CHANNEL = "ride"
        const val NOTIF_ID = 1
        const val ACTION_CONNECT = "dev.tvsopen.CONNECT"
        const val ACTION_START_NAV = "dev.tvsopen.START_NAV"
        const val ACTION_STOP_NAV = "dev.tvsopen.STOP_NAV"
        const val ACTION_UPDATE_RIDER_NAME = "dev.tvsopen.UPDATE_RIDER_NAME"
        const val ACTION_UPDATE_DEVICE_NAME = "dev.tvsopen.UPDATE_DEVICE_NAME"

        /** Gap between opening frames; the official app waits 100 ms after each. */
        private const val OPENING_GAP_MS = 100L

        /** Phone-status re-send interval; ~3 s in the HCI capture of the official app. */
        private const val PHONE_STATUS_EVERY_MS = 3_000L
        const val ACTION_DISCONNECT = "dev.tvsopen.DISCONNECT"

        const val EXTRA_ADDRESS = "address"
        const val EXTRA_MODEL = "model"

        /** True: wait for the vehicle with a background connect instead of a 30 s direct one. */
        const val EXTRA_BACKGROUND = "background"

        /** Pause before re-arming a background connect after a drop. */
        private const val AUTO_RECONNECT_DELAY_MS = 2_000L

        /** How long to wait in the background before leaving it to Android; see [scheduleHandoff]. */
        private const val HANDOFF_AFTER_MS = 120_000L

        /**
         * Official reconnect backoff, seconds: 5, 10, 20, 40, then 60 for the
         * remaining attempts. Indexed by attempt number.
         */
        private val RECONNECT_DELAYS_MS = longArrayOf(
            5_000, 10_000, 20_000, 40_000,
            60_000, 60_000, 60_000, 60_000, 60_000, 60_000,
        )
    }
}

/**
 * Removed. It was the reason navigation mirroring never sent a frame: the
 * bridge read `current` on every BLE frame, and nothing ever assigned it, so
 * the instruction was always null. The bridge now collects `Session.hud`, which
 * is the same data the listener already writes.
 */
