package dev.tvsopen.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.RequiresApi
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tvsopen.Nucleus
import dev.tvsopen.log.Log
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.protocol.VehicleModel
import dev.tvsopen.session.Session
import dev.tvsopen.transport.ConnectionState
import dev.tvsopen.transport.RideService
import dev.tvsopen.transport.ScooterPairing
import dev.tvsopen.transport.ScooterScanner
import dev.tvsopen.transport.label
import dev.tvsopen.ui.theme.ThemePrefs
import dev.tvsopen.ui.theme.TVSRideTheme

/**
 * Single-activity host: bottom navigation across Overview, Rides, More and Log.
 *
 * Deliberately thin. The protocol layer is the substance; the UI observes state
 * and sends intents to [RideService]. There is no account, no repository and no
 * network client, because there is no network client in the app at all.
 */
class MainActivity : AppCompatActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // Whatever was granted or refused, the first-run set is now answered.
            // Recording it here rather than only when everything is granted means a
            // rider who declines the phone permissions is not asked again on every
            // launch, and one who accepts is asked for the rest.
            val wasFirstRun = isFirstRun
            if (wasFirstRun) markFirstRunComplete()

            val denied = granted.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                Log.i("MainActivity", "declined: ${denied.joinToString()}")
            }

            // Connect if the permissions needed for BLE were granted, even if
            // the optional phone/contact grants were declined. The initial
            // implementation required every requested permission, which made
            // declining caller-ID permissions prevent scooter connection.
            if (hasBlePermission()) {
                if (RidePrefs.load(this).lastAddress.isNullOrBlank()) openScanner()
                else connectToRememberedCluster()
            }
        }

    /**
     * What to do once the user has answered the "Turn on Bluetooth?" dialog.
     *
     * Held here rather than captured by the launcher callback because
     * `registerForActivityResult` must be registered before the activity
     * starts, while the follow-up (scan, or connect) is only known later.
     */
    private var afterBluetoothOn: (() -> Unit)? = null

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val next = afterBluetoothOn
            afterBluetoothOn = null
            // Read the adapter rather than trusting the result code: some OEM
            // dialogs return RESULT_CANCELED after turning the radio on anyway.
            if (isBluetoothOn()) {
                Log.i("MainActivity", "Bluetooth turned on from the prompt")
                next?.invoke()
            } else {
                Log.i("MainActivity", "Bluetooth left off (result=${result.resultCode})")
                Toast.makeText(
                    this,
                    "Bluetooth is off — turn it on to connect to your vehicle",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    // ---- auto-connect: Companion Device Manager ----

    /** The vehicle the companion dialog was shown for, read back in its result. */
    private var pendingCompanionAddress: String? = null

    /**
     * What to do once the companion step is over, whatever its outcome.
     *
     * Usually "connect". The dialog searches for the vehicle's advertisement,
     * and a vehicle stops advertising the moment it is connected — so
     * connecting first left the dialog spinning on "Looking for devices…"
     * until the rider gave up (seen on the RTR 160 4V, 2026-11-14). Connecting
     * only after the dialog closes is the fix.
     */
    private var afterCompanion: (() -> Unit)? = null

    private val companionLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val address = pendingCompanionAddress
            pendingCompanionAddress = null
            if (address != null) {
                if (result.resultCode == RESULT_OK) {
                    Log.i("MainActivity", "companion association accepted")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) startObservingCompanion(address)
                } else {
                    Log.i("MainActivity", "companion association declined (result=${result.resultCode})")
                }
            }
            finishCompanion()
        }

    private fun finishCompanion() {
        val next = afterCompanion
        afterCompanion = null
        next?.invoke()
    }

    @Suppress("DEPRECATION")
    private fun isCompanionAssociated(address: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val cdm = getSystemService(CompanionDeviceManager::class.java) ?: return false
        // `associations` (MAC strings) is deprecated on 33+ in favour of
        // `myAssociations`, but works on every level this feature runs on.
        return runCatching { cdm.associations }.getOrDefault(emptyList())
            .any { it.equals(address, ignoreCase = true) }
    }

    /**
     * Ask Android to watch for the saved vehicle and wake the app when it appears.
     *
     * The first time per vehicle this shows the system "pair with this device?"
     * dialog. That consent is what lets [dev.tvsopen.transport.VehiclePresenceService]
     * start the ride service from the background when the vehicle is switched
     * on — Android 12+ refuses that otherwise. Below Android 12 there is no
     * presence API, and auto-connect relies on the background connect alone.
     *
     * The vehicle must be advertising, i.e. **not connected**, for the dialog
     * to find it. [then] runs once this is over — accepted, declined, failed,
     * or not needed at all — and is where the caller connects.
     */
    private fun setUpCompanion(address: String, then: () -> Unit = {}) {
        afterCompanion = then
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return finishCompanion()
        val prefs = RidePrefs.load(this)
        if (!prefs.autoConnect) return finishCompanion()
        val cdm = getSystemService(CompanionDeviceManager::class.java) ?: run {
            Log.w("MainActivity", "no Companion Device Manager on this phone")
            return finishCompanion()
        }
        if (isCompanionAssociated(address)) {
            startObservingCompanion(address)
            return finishCompanion()
        }
        prefs.markCompanionAsked(address)
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setDeviceAddress(address.uppercase()).build())
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()
        pendingCompanionAddress = address
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) =
                launchCompanionDialog(intentSender)

            @Deprecated("Superseded by onAssociationPending on API 33+")
            override fun onDeviceFound(intentSender: IntentSender) =
                launchCompanionDialog(intentSender)

            override fun onFailure(error: CharSequence?) {
                pendingCompanionAddress = null
                Log.w("MainActivity", "companion association failed: $error")
                finishCompanion()
            }
        }
        Log.i("MainActivity", "asking to associate the vehicle for auto-connect")
        runCatching { cdm.associate(request, callback, null) }.onFailure {
            pendingCompanionAddress = null
            Log.w("MainActivity", "companion association refused: ${it.message}")
            finishCompanion()
        }
    }

    private fun launchCompanionDialog(sender: IntentSender) {
        runCatching { companionLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .onFailure {
                pendingCompanionAddress = null
                Log.w("MainActivity", "could not show the companion dialog: ${it.message}")
                finishCompanion()
            }
    }

    /**
     * Turning the Auto-connect switch on for a vehicle that has no companion
     * association yet (declined, or never asked).
     *
     * The dialog can only find a vehicle that is advertising, so a live link is
     * closed first, the dialog runs, and the vehicle is reconnected afterwards
     * whatever the outcome.
     */
    private fun setUpAutoConnectForSavedVehicle() {
        val address = RidePrefs.load(this).lastAddress ?: return
        val live = Session.connection.value.state.let {
            it == ConnectionState.CONNECTED || it == ConnectionState.CONNECTING
        }
        val startDialog = { setUpCompanion(address) { connectToRememberedCluster() } }
        if (!live) {
            startDialog()
            return
        }
        Log.i("MainActivity", "disconnecting so the companion dialog can find the vehicle")
        send(RideService.ACTION_DISCONNECT)
        // Give the vehicle a moment to start advertising again after the link closes.
        window.decorView.postDelayed({ startDialog() }, COMPANION_AFTER_DISCONNECT_MS)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startObservingCompanion(address: String) {
        val cdm = getSystemService(CompanionDeviceManager::class.java) ?: return
        runCatching { cdm.startObservingDevicePresence(address) }
            .onSuccess {
                RidePrefs.load(this).observedCompanion = address
                Log.i("MainActivity", "Android is now watching for the vehicle")
            }
            .onFailure { Log.w("MainActivity", "could not watch for the vehicle: ${it.message}") }
    }

    private fun stopObservingCompanion(address: String) {
        val prefs = RidePrefs.load(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(CompanionDeviceManager::class.java)?.let { cdm ->
                runCatching { cdm.stopObservingDevicePresence(address) }
            }
        }
        prefs.observedCompanion = null
        Log.i("MainActivity", "stopped watching for the vehicle")
    }

    /**
     * The More screen's Auto-connect switch.
     *
     * On: run the companion step for the saved vehicle if it still needs it
     * (closing a live link first, see [setUpAutoConnectForSavedVehicle]), then
     * connect. Off: stop watching for the vehicle; a live link is left alone.
     */
    private fun setAutoConnect(on: Boolean) {
        val prefs = RidePrefs.load(this)
        prefs.updateAutoConnect(on)
        Log.i("MainActivity", "auto-connect ${if (on) "on" else "off"}")
        val address = prefs.lastAddress ?: return
        if (!on) {
            stopObservingCompanion(address)
            return
        }
        if (isCompanionAssociated(address)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) startObservingCompanion(address)
            if (hasBlePermission() && Session.connection.value.state == ConnectionState.DISCONNECTED) {
                connectToRememberedCluster()
            }
            return
        }
        setUpAutoConnectForSavedVehicle()
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        getSystemService(BluetoothManager::class.java)?.adapter

    private fun isBluetoothOn(): Boolean = bluetoothAdapter()?.isEnabled == true

    /**
     * Run [then] with Bluetooth on, asking the user to turn it on first if needed.
     *
     * The app used to assume the radio was on: with it off, scanning found
     * nothing and connecting did nothing, and no screen said why. This shows the
     * system "Turn on Bluetooth?" dialog and carries on by itself once the user
     * accepts. Callers must already hold the Bluetooth permissions — on Android
     * 12+ `ACTION_REQUEST_ENABLE` itself needs `BLUETOOTH_CONNECT`.
     */
    private fun ensureBluetoothOn(then: () -> Unit) {
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            Log.w("MainActivity", "no Bluetooth adapter on this phone")
            Toast.makeText(this, "This phone has no Bluetooth", Toast.LENGTH_LONG).show()
            return
        }
        if (adapter.isEnabled) {
            then()
            return
        }
        Log.i("MainActivity", "Bluetooth is off; asking the user to turn it on")
        afterBluetoothOn = then
        runCatching {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }.onFailure {
            afterBluetoothOn = null
            Log.w("MainActivity", "could not show the Bluetooth prompt: ${it.message}")
            Toast.makeText(this, "Turn on Bluetooth in Settings", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Whether this is the first launch, so every permission can be asked at once.
     *
     * Recorded **after** the permission dialog returns rather than before, so a
     * launch that is killed mid-dialog asks again next time instead of
     * recording a set of permissions that were never actually answered.
     */
    private val isFirstRun: Boolean
        get() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_FIRST_RUN, true)

    private fun markFirstRunComplete() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_FIRST_RUN, false).apply()
    }

    /**
     * Everything the app can need, requested in one go on first launch.
     *
     * Three groups, and the grouping is why this is a single prompt sequence
     * rather than one dialog per feature:
     *
     * 1. Bluetooth — without it nothing works.
     * 2. Notification posting (Android 13+) — runtime permission for the
     *    connection foreground-service notice. Notification *listener access*
     *    for maps nav is a separate Settings toggle; Android cannot grant that
     *    through this dialog.
     * 3. Contacts, phone state, call log — caller ID only.
     *
     * Asking once, up front, is better than the alternative this replaces: a
     * rider granted Bluetooth, connected successfully, started a route, and saw
     * nothing on the cluster, with no indication that a permission was missing.
     */
    private fun requestAllPermissionsOnFirstRun() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            missing += Manifest.permission.BLUETOOTH_SCAN
            missing += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        // Caller ID. Denying these costs only the cluster's caller display, and
        // the navigation mirror works without them.
        missing += Manifest.permission.READ_PHONE_STATE
        missing += Manifest.permission.READ_CONTACTS
        missing += Manifest.permission.READ_CALL_LOG
        // Handlebar end-call button and its reply SMS (Apache). Optional too:
        // denying them leaves the button with the headset-key fallback, no SMS.
        missing += Manifest.permission.ANSWER_PHONE_CALLS
        missing += Manifest.permission.SEND_SMS

        // Request CALL_PHONE only when it is declared (it is intentionally not:
        // Android restricts it to the default dialer). READ_PHONE_STATE,
        // READ_CONTACTS and READ_CALL_LOG are all runtime-grantable.

        val needed = missing.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            markFirstRunComplete()
            if (RidePrefs.load(this).lastAddress.isNullOrBlank()) openScanner()
            else connectToRememberedCluster()
            return
        }
        Log.i("MainActivity", "first run: requesting ${needed.size} permissions")
        permissionLauncher.launch(needed.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Nucleus.init()
        // Enable the rotating file sink from the UI process too, so logs are
        // captured even before the ride service starts.
        Log.enableFileSink(java.io.File(filesDir, "logs"))
        ThemePrefs.load(this)
        Session.attachStorage(applicationContext)
        Log.i("App", "activity created; drivers registered; theme=${ThemePrefs.mode}")

        setContent {
            // No theme wrapper here: AppRoot applies TVSRideTheme around the
            // Scaffold. Wrapping twice meant the outer instance supplied the
            // colours and the inner one re-supplied them, so the two could
            // disagree after a theme change.
            AppRoot(
                onNavMirrorChange = { send(it) },
                onScan = { openScanner() },
                onDisconnect = { send(RideService.ACTION_DISCONNECT) },
                onOpenNotificationAccess = { openNotificationAccess() },
                // Re-read on every recomposition: the user grants notification
                // access in system settings and comes back, so a value sampled
                // once in onCreate is permanently stale.
                hasNotificationAccess = notificationAccess,
            )
        }
    }

    /**
     * Overview's Connect card: reconnect to the saved vehicle, or open the
     * scanner when none has been chosen yet.
     */
    private fun connectOrScan() {
        if (RidePrefs.load(this).lastAddress.isNullOrBlank()) {
            openScanner()
        } else if (!hasBlePermission()) {
            requestBlePermissions()
        } else {
            connectToRememberedCluster()
        }
    }

    @Composable
    private fun AppRoot(
        onNavMirrorChange: (String) -> Unit,
        onScan: () -> Unit,
        onDisconnect: () -> Unit,
        onOpenNotificationAccess: () -> Unit,
        hasNotificationAccess: Boolean,
    ) {
        // Overview is the first tab and the one the app opens on.
        var tab by rememberSaveable { mutableStateOf(TAB_OVERVIEW) }
        // On by default: the bridge does nothing until a maps app posts a
        // navigation notification, so there is no cost to having it ready. The
        // switch lives in More for anyone who wants the cluster left alone.
        var navMirroring by rememberSaveable {
            mutableStateOf(RidePrefs.load(this@MainActivity).navigationMirroring)
        }

        val telemetry by Session.telemetry.collectAsStateWithLifecycle()
        val hud by Session.hud.collectAsStateWithLifecycle()
        val connection by Session.connection.collectAsStateWithLifecycle()
        val rides by Session.rides.collectAsStateWithLifecycle()
        val totalRides by Session.totalRides.collectAsStateWithLifecycle()
        val totalRideDistanceKm by Session.totalRideDistanceKm.collectAsStateWithLifecycle()

        // Everything the UI shows comes from the process-scoped Session, which
        // the BLE service and the notification listener both write into. The
        // activity only observes.

        // Start the mirror once a connection exists, because it defaults on.
        // Keyed on the connection state, so it fires on the transition to
        // CONNECTED and not on every recomposition; `startNavigation` is
        // idempotent. Not fired while disconnected, where the bridge would only
        // log dropped writes.
        LaunchedEffect(connection.state, navMirroring) {
            if (navMirroring && connection.state == ConnectionState.CONNECTED) {
                onNavMirrorChange(RideService.ACTION_START_NAV)
            } else if (!navMirroring && connection.state == ConnectionState.CONNECTED) {
                onNavMirrorChange(RideService.ACTION_STOP_NAV)
            }
        }

        TVSRideTheme(mode = ThemePrefs.mode, darkTheme = isSystemInDarkTheme()) {
            Scaffold(
                bottomBar = {
                    // Hidden while the scanner is open. The scan screen is a
                    // full-screen takeover with its own back arrow, so a live
                    // nav bar underneath was an affordance that lied: tapping a
                    // tab highlighted it and left you exactly where you were.
                    if (!scanning.value) NavigationBar {
                        NavigationBarItem(
                            selected = tab == TAB_OVERVIEW,
                            onClick = { tab = TAB_OVERVIEW },
                            icon = { Icon(Icons.Filled.Speed, contentDescription = "Overview") },
                            label = { Text("Overview") },
                        )
                        NavigationBarItem(
                            selected = tab == TAB_RIDES,
                            onClick = { tab = TAB_RIDES },
                            icon = { Icon(Icons.Filled.Route, contentDescription = "Rides") },
                            label = { Text("Rides") },
                        )
                        NavigationBarItem(
                            selected = tab == TAB_MORE,
                            onClick = { tab = TAB_MORE },
                            icon = { Icon(Icons.Filled.MoreHoriz, contentDescription = "More") },
                            label = { Text("More") },
                        )
                        NavigationBarItem(
                            selected = tab == TAB_LOG,
                            onClick = { tab = TAB_LOG },
                            icon = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = "Log") },
                            label = { Text("Log") },
                        )
                    }
                },
            ) { padding ->
                androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                    if (scanning.value) {
                        // Full-screen takeover rather than a dialog: the scan has
                        // a filter switch, a list and a model picker, and a
                        // dialog would either clip or cover the nav bar.
                        ScanScreen(
                            scanner = scanner,
                            pairing = pairing,
                            lastModelId = RidePrefs.load(this@MainActivity).lastModelId,
                            onPick = { address, model ->
                                scanning.value = false
                                RidePrefs.load(this@MainActivity)
                                    .updateSelectedModel(address, model.name)
                                RidePrefs.load(this@MainActivity)
                                    .updateLastConnection(address, model.name)
                                // Companion dialog first, while the vehicle is
                                // still advertising; connect once it closes.
                                setUpCompanion(address) { connectTo(address, model) }
                            },
                            onBack = { scanning.value = false },
                            onRequestBluetooth = {
                                ensureBluetoothOn { scanner.start() }
                            },
                        )
                    } else when (tab) {
                        TAB_OVERVIEW -> OverviewScreen(
                            telemetry = telemetry,
                            hud = hud,
                            connection = connection,
                            onConnect = { connectOrScan() },
                            onDisconnect = onDisconnect,
                            notificationAccessGranted = hasNotificationAccess,
                            onGrantNotificationAccess = onOpenNotificationAccess,
                        )
                        TAB_RIDES -> RidesScreen(
                            rides = rides,
                            totalRides = totalRides,
                            totalDistanceKm = totalRideDistanceKm,
                        )
                        TAB_MORE -> MoreScreen(
                            connection = connection,
                            hud = hud,
                            riderName = RidePrefs.load(this@MainActivity).riderName,
                            onRiderNameChange = { name ->
                                RidePrefs.load(this@MainActivity).riderName = name
                                // Gate the send, not the save: without BLE
                                // permission send() would toast about Bluetooth
                                // for what is only a name being stored. The
                                // service sends it on the next connection anyway.
                                if (hasBlePermission()) {
                                    send(RideService.ACTION_UPDATE_RIDER_NAME)
                                }
                            },
                            deviceNickname = RidePrefs.load(this@MainActivity).deviceNickname,
                            onDeviceNicknameChange = { nickname ->
                                RidePrefs.load(this@MainActivity).deviceNickname = nickname
                                // Only worth waking the service when a link can
                                // carry it; otherwise it goes out on next connect.
                                if (hasBlePermission() && connection.state == ConnectionState.CONNECTED) {
                                    send(RideService.ACTION_UPDATE_DEVICE_NAME)
                                }
                            },
                            onAutoConnectChange = { on -> setAutoConnect(on) },
                            navMirroring = navMirroring,
                            onNavMirrorChange = { on ->
                                navMirroring = on
                                RidePrefs.load(this@MainActivity).navigationMirroring = on
                                onNavMirrorChange(
                                    if (on) RideService.ACTION_START_NAV
                                    else RideService.ACTION_STOP_NAV,
                                )
                            },
                            notificationAccessGranted = hasNotificationAccess,
                            onScan = onScan,
                            onDisconnect = onDisconnect,
                        )
                        else -> LogScreen()
                    }
                }
            }
        }
    }

    /**
     * Ask the ride service to do something.
     *
     * Guarded on the Bluetooth permissions, and the guard is not optional.
     * `RideService` is a `connectedDevice` foreground service, which Android
     * refuses unless `BLUETOOTH_CONNECT` or `BLUETOOTH_SCAN` is **granted**.
     * Without this check the sequence is:
     *
     *   startForegroundService -> service onCreate -> startForeground throws
     *   SecurityException -> caught and logged -> service never calls
     *   startForeground -> ~10s later Android kills the app with
     *   `ForegroundServiceDidNotStartInTimeException`.
     *
     * Five of those, observed on device. Catching the exception in the service
     * stopped the *service* crashing but left the app to die instead, ten
     * seconds later, with a stack trace pointing at a Compose tap handler rather
     * than at the permission that was actually missing. Check before starting.
     */
    private fun send(action: String) {
        if (!hasBlePermission()) {
            Log.w("MainActivity", "refusing to start $action: Bluetooth not granted")
            Toast.makeText(this, "Grant Bluetooth permission first", Toast.LENGTH_LONG).show()
            requestBlePermissions()
            return
        }
        runCatching { startForegroundService(Intent(this, RideService::class.java).setAction(action)) }
            .onFailure {
                Log.w("MainActivity", "could not start ride service: ${it.message}")
                Toast.makeText(this, "Ride service not running", Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Removed: `requestCallerIdPermissions` plus its launcher.
     *
     * It was defined but never called — the first-run flow requests the same
     * three permissions through `permissionLauncher`, and nothing else asked on
     * demand. Worse, its `onResult` was only invoked on the all-granted path;
     * a partial grant dropped the result entirely. Dead code with a live bug in
     * it is worse than no code.
     */

    private fun requestBlePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            connectToRememberedCluster()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun connectToRememberedCluster() {
        val prefs = RidePrefs.load(this)
        val address = prefs.lastAddress
        if (address.isNullOrBlank()) {
            Toast.makeText(this, "No vehicle chosen yet — tap Connect to scan", Toast.LENGTH_LONG)
                .show()
            return
        }
        val model = prefs.lastModelId
            ?.let { runCatching { VehicleModel.valueOf(it) }.getOrNull() }
            ?: VehicleModel.NTORQ
        ensureBluetoothOn { connectTo(address, model) }
    }

    private fun connectTo(address: String, model: VehicleModel) {
        runCatching {
            startForegroundService(
                Intent(this, RideService::class.java)
                    .setAction(RideService.ACTION_CONNECT)
                    .putExtra(RideService.EXTRA_ADDRESS, address)
                    .putExtra(RideService.EXTRA_MODEL, model.name),
            )
        }.onFailure {
            Toast.makeText(this, "Could not start the ride service", Toast.LENGTH_LONG).show()
        }
    }

    /** True when both runtime BLE permissions are already granted. */
    private fun hasBlePermission(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Read on every composition, so it must not touch the disk.
     *
     * `Settings.Secure.getString` is a provider query; doing it per recomposition
     * would hammer it. Cached, and invalidated whenever the activity resumes —
     * which is exactly when the user comes back from granting access.
     */
    private var notificationAccess by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        notificationAccess = hasNotificationAccess()
        if (isFirstRun && hasBlePermission()) {
            // BLE may already have been granted from the system permission
            // manager before this activity's first resume. Still ask for the
            // remaining first-run permissions once.
            if (!autoConnectTried) {
                autoConnectTried = true
                requestAllPermissionsOnFirstRun()
            }
            return
        }
        // Once, on the first resume only. Reconnecting on every resume would
        // fight the user for the GATT link while they are still reading the
        // screen.
        if (!autoConnectTried) {
            autoConnectTried = true
            if (isFirstRun) {
                // First launch: ask for everything at once, then connect. The
                // permission result handler is what performs the connect, so this
                // deliberately does not also call autoConnectOnLaunch — doing both
                // would start two foreground services for the same cluster.
                requestAllPermissionsOnFirstRun()
            } else {
                autoConnectOnLaunch()
            }
        }
    }

    private var autoConnectTried = false

    /**
     * Scan state lives on the activity, not inside AppRoot.
     *
     * Both the Connect card and the More tab's Scan button have to open the
     * same scanner, and the permission callback is wired in onCreate — outside
     * AppRoot entirely. Hoisting it keeps one scanner instance for the whole
     * activity, which also means leaving and re-entering the screen resumes the
     * same scan instead of losing the results.
     */
    private val scanning = mutableStateOf(false)
    private val scanner: ScooterScanner by lazy { ScooterScanner(this) }
    private val pairing: ScooterPairing by lazy { ScooterPairing(this) }

    private fun openScanner() {
        if (!hasBlePermission()) {
            requestBlePermissions()
            return
        }
        scanning.value = true
        // Starting with the radio off is deliberate: the scanner then records
        // `bluetoothOff`, so if the user declines the prompt the scan screen
        // explains why it is empty and offers the button again.
        scanner.start()
        if (!isBluetoothOn()) ensureBluetoothOn { scanner.start() }
    }

    /**
     * Reconnect to the last scooter without scanning, on every launch.
     *
     * The whole point of storing [RidePrefs.lastAddress]. Scanning on each launch
     * would be slower, and on this cluster actively fruitless: it advertises no
     * service UUID, so a UUID-filtered scan finds nothing, and finding it by name
     * means a name that could in principle match something else.
     *
     * The stored address is the only thing that identifies a cluster, so this is
     * also the only path that can reconnect to *your* scooter rather than to
     * whichever cluster happens to be nearest.
     *
     * Failures are silent by design. The cluster is often simply not there — the
     * ignition is off, or the battery is flat — and a toast on every launch would
     * be noise. [RideService] owns the retry and the state the UI shows.
     */
    private fun autoConnectOnLaunch() {
        if (!hasBlePermission()) {
            Log.i("MainActivity", "auto-connect skipped: Bluetooth not granted")
            return
        }
        val prefs = RidePrefs.load(this)
        if (prefs.lastAddress.isNullOrBlank()) {
            // Genuinely first run, or never connected. Say so once, because
            // otherwise Overview just shows "Disconnected" with no
            // explanation of why nothing is happening.
            Log.i("MainActivity", "auto-connect skipped: no vehicle stored yet")
            return
        }
        // Already linked (or linking), e.g. auto-connect brought the vehicle in
        // while the app was closed. Connecting again would tear that link down
        // and rebuild it, which is what opening the app used to do.
        val current = Session.connection.value
        if (current.state == ConnectionState.CONNECTED || current.state == ConnectionState.CONNECTING) {
            Log.i("MainActivity", "auto-connect skipped: already ${current.state.label.lowercase()}")
            return
        }
        val model = prefs.lastModelId
            ?.let { runCatching { VehicleModel.valueOf(it) }.getOrNull() }
            ?: VehicleModel.NTORQ
        Log.i("MainActivity", "auto-connecting to ${model.displayName}")
        connectToRememberedCluster()
        // No companion dialog here: it can only find a vehicle that is not
        // connected, and this is about to connect. More offers it instead.
    }

    private fun hasNotificationAccess(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true

    private fun openNotificationAccess() {
        val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        runCatching { startActivity(i) }
            .onFailure {
                Toast.makeText(this, "Open notification access from system settings", Toast.LENGTH_LONG).show()
            }
    }

    private companion object {
        const val PREFS = "tvsride"
        const val KEY_FIRST_RUN = "first_run_complete"

        // Bottom tabs, in display order.
        const val TAB_OVERVIEW = 0
        const val TAB_RIDES = 1
        const val TAB_MORE = 2
        const val TAB_LOG = 3

        /** Pause after closing a link before the companion dialog searches for the vehicle. */
        const val COMPANION_AFTER_DISCONNECT_MS = 1_500L
    }
}
