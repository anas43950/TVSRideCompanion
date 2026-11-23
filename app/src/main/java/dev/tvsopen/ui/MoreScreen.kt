package dev.tvsopen.ui

import android.Manifest
import android.content.Context
import dev.tvsopen.transport.CallReply
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import dev.tvsopen.ui.theme.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.tvsopen.data.DataExport
import dev.tvsopen.data.DataImport
import dev.tvsopen.log.Log
import dev.tvsopen.session.Session
import android.content.ClipData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.tvsopen.prefs.HandlebarCallAction
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.protocol.ApacheN112Driver
import dev.tvsopen.transport.label
import dev.tvsopen.ui.theme.ThemeMode
import dev.tvsopen.ui.theme.ThemePrefs
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * More / settings screen.
 */
@Composable
fun MoreScreen(
    connection: dev.tvsopen.session.ConnectionState1,
    hud: dev.tvsopen.session.HudState,
    riderName: String?,
    onRiderNameChange: (String?) -> Unit,
    deviceNickname: String?,
    onDeviceNicknameChange: (String?) -> Unit,
    /** Turning auto-connect on may need a one-time system dialog, so the activity handles it. */
    onAutoConnectChange: (Boolean) -> Unit,
    navMirroring: Boolean,
    onNavMirrorChange: (Boolean) -> Unit,
    notificationAccessGranted: Boolean,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val ctx = LocalContext.current
    // Bumped when an import restores settings, so the cards that copy a
    // setting into their own editing state read it again.
    var settingsVersion by remember { mutableIntStateOf(0) }
    // Read from prefs, which an import may have changed since the caller read them.
    val rider = remember(settingsVersion, riderName) { RidePrefs.load(ctx).riderName }
    val nickname = remember(settingsVersion, deviceNickname) { RidePrefs.load(ctx).deviceNickname }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("CONNECTION", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(connection.model.displayName, style = MaterialTheme.typography.titleMedium)
                    // .label, not the enum name: this printed "CONNECTING".
                    Text("State: ${connection.state.label}")
                    connection.address?.let { Text("MAC: $it") }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onScan, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Bluetooth, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Scan")
                        }
                        OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }

        item {
            Text("NAMES", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            NamesCard(
                riderName = rider,
                onRiderNameChange = onRiderNameChange,
                nickname = nickname,
                onNicknameChange = onDeviceNicknameChange,
            )
        }

        item {
            Text("ALERTS", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item { AlertsCard() }

        item {
            Text("HANDLE BUTTON BEHAVIOUR", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Keyed so an import that restores the call reply is read again.
        item { key(settingsVersion) { HandleButtonCard() } }

        item {
            Text("KEEP THE CONNECTION ALIVE", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            DozeCard(
                onAutoConnectChange = onAutoConnectChange,
            )
        }

        item {
            Text("NAVIGATION", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            NavigationCard(
                enabled = navMirroring,
                hud = hud,
                notificationAccessGranted = notificationAccessGranted,
                onChange = onNavMirrorChange,
            )
        }

        item {
            Text("APPEARANCE", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item { AppearanceCard() }

        item {
            Text("YOUR DATA", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            DataCard(
                onSettingsRestored = { s ->
                    settingsVersion++
                    // Store them the way an edit would, which also sends the
                    // new names to the bike when it is connected.
                    s.riderName?.let { onRiderNameChange(RidePrefs.load(ctx).riderName) }
                    s.deviceNickname?.let { onDeviceNicknameChange(RidePrefs.load(ctx).deviceNickname) }
                },
            )
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

/**
 * Export the rides and settings to a JSON file and share it, or read an export
 * back in. Files go through the share sheet and the system file picker, so no
 * storage permission is needed.
 */
@Composable
private fun DataCard(onSettingsRestored: (DataImport.Settings) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            status = try {
                val text = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                } ?: throw DataImport.NotAnExport("unreadable")
                val contents = DataImport.parse(text)
                val rides = Session.editRides { DataImport.merge(it, contents) }
                val restored = DataImport.restore(RidePrefs.load(ctx), contents.settings)
                if (restored > 0) onSettingsRestored(contents.settings)
                DataImport.message(rides, restored)
            } catch (e: DataImport.NotAnExport) {
                "Not a TVSRide export file"
            } catch (e: Exception) {
                Log.w("DataCard", "Import failed", e)
                "Could not read that file"
            } finally {
                busy = false
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Export or import rides and settings as a JSON file. " +
                    "Everything stays on your device unless you share it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val uri = withContext(Dispatchers.IO) { DataExport.writeFile(ctx) }
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, "TVSRide export")
                                    clipData = ClipData.newRawUri("TVSRide export", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(Intent.createChooser(intent, "Export data"))
                                status = null
                            } catch (e: Exception) {
                                Log.w("DataCard", "Export failed", e)
                                status = "Could not write the export file"
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Text("Export & share")
                }
                OutlinedButton(
                    enabled = !busy,
                    // Many file managers label .json as octet-stream or text.
                    onClick = {
                        picker.launch(arrayOf("application/json", "application/octet-stream", "text/*"))
                    },
                ) {
                    Text("Import file")
                }
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Turn-by-turn on the cluster from whatever maps app is navigating.
 *
 * Start navigation in Google Maps (or any maps app) as usual: the app reads the
 * next turn, distance and street from the navigation notification and sends
 * them to the cluster. Needs notification access, granted once in Settings.
 */
@Composable
private fun NavigationCard(
    enabled: Boolean,
    hud: dev.tvsopen.session.HudState,
    notificationAccessGranted: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Show navigation on the cluster", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !enabled -> "Off — turns are not sent"
                            !notificationAccessGranted -> "Needs notification access (see Overview)"
                            hud.active -> "Live from ${hud.provider ?: "your maps app"}"
                            else -> "On — start navigation in Google Maps"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppSwitch(checked = enabled, onCheckedChange = onChange)
            }
        }
    }
}

/**
 * The two names the cluster shows: the rider name in the console greeting on
 * ignition, and the device nickname for this phone ("Connected to <name>" on
 * the Apache RTR 160 4V, its `V` frame; "APACHE" when none is saved).
 * Save sends only the names that changed.
 */
@Composable
private fun NamesCard(
    riderName: String?,
    onRiderNameChange: (String?) -> Unit,
    nickname: String?,
    onNicknameChange: (String?) -> Unit,
) {
    val maxNickname = RidePrefs.MAX_NICKNAME_CHARS
    var editingRider by remember(riderName) { mutableStateOf(riderName.orEmpty()) }
    var editingNickname by remember(nickname) { mutableStateOf(nickname.orEmpty()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = editingRider,
                onValueChange = { if (it.length <= 17) editingRider = it },
                label = { Text("Console greeting") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("${editingRider.length}/17") },
            )
            OutlinedTextField(
                value = editingNickname,
                onValueChange = { if (it.length <= maxNickname) editingNickname = it },
                label = { Text("Device nickname") },
                placeholder = { Text(ApacheN112Driver.DEFAULT_NICKNAME) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("${editingNickname.length}/$maxNickname") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val rider = editingRider.trim().takeIf { it.isNotEmpty() }
                    val nick = editingNickname.trim().takeIf { it.isNotEmpty() }
                    if (rider != riderName) onRiderNameChange(rider)
                    if (nick != nickname) onNicknameChange(nick)
                    editingRider = rider.orEmpty()
                    editingNickname = nick.orEmpty()
                }) { Text("Save") }
                OutlinedButton(onClick = {
                    editingRider = ""
                    editingNickname = ""
                    if (riderName != null) onRiderNameChange(null)
                    if (nickname != null) onNicknameChange(null)
                }) { Text("Clear") }
            }
        }
    }
}

/**
 * Doze exemption.
 *
 * This is the difference between a working app and one that dies mid-ride. Doze
 * suspends the GATT client within minutes of the screen going off, the
 * presence packet stops, and the cluster drops the link. The working Jupiter
 * companion asks for the same exemption.
 *
 * Android has no runtime dialog for this one — it is granted in system settings
 * or via the request intent — so the card states the state plainly and links
 * out, rather than pretending a button can do it.
 */
@Composable
private fun DozeCard(
    onAutoConnectChange: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var exempt by remember { mutableStateOf(isDozeExempt(ctx)) }
    val prefs = remember { RidePrefs.load(ctx) }

    // Re-read on every resume, not once. The grant happens in system settings,
    // so `remember` alone kept reporting "not exempt" until the process died.
    LifecycleResumeEffect(Unit) {
        exempt = isDozeExempt(ctx)
        onPauseOrDispose { }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (exempt) "Battery optimisation: exempt"
                else "Battery optimisation: not exempt",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Without this the phone sleeps mid-ride and the cluster drops the " +
                    "connection. It does not give the app network access — it only " +
                    "stops Android suspending it.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            if (!exempt) {
                OutlinedButton(onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${ctx.packageName}"),
                            ),
                        )
                    }.onFailure {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                            )
                        }
                    }
                    // Re-read after the settings round-trip.
                    scope.launch {
                        delay(1500)
                        exempt = isDozeExempt(ctx)
                    }
                }) { Text("Open battery settings") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppSwitch(
                    checked = prefs.autoConnect,
                    onCheckedChange = { onAutoConnectChange(it) },
                )
                Spacer(Modifier.size(12.dp))
                Column {
                    Text("Auto-connect", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Connect to your saved vehicle by itself when it is switched on, " +
                            "after a reboot, and when Bluetooth comes back. Works best with " +
                            "battery optimisation exempt (above).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** True when this package is on the battery-optimisation allowlist. */
private fun isDozeExempt(ctx: Context): Boolean {
    val pm = ctx.getSystemService(android.os.PowerManager::class.java) ?: return false
    return runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }
        .getOrDefault(false)
}

/**
 * Light / Dark / Auto.
 *
 * A segmented row rather than a switch: the middle option is "follow the
 * system", and a boolean cannot express three states without lying about one
 * of them.
 */
@Composable
private fun AppearanceCard() {
    val ctx = LocalContext.current
    val current = ThemePrefs.mode

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = current == mode,
                    onClick = { ThemePrefs.set(ctx, mode) },
                    label = { Text(mode.label, textAlign = TextAlign.Center) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Calls and messages mirrored to the cluster. The handlebar button's own
 * behaviour (and its reply) lives in [HandleButtonCard].
 */
@Composable
private fun AlertsCard() {
    val ctx = LocalContext.current
    val prefs = remember { RidePrefs.load(ctx) }
    var mirrorCalls by remember { mutableStateOf(prefs.mirrorPhoneCalls) }
    var mirrorMessages by remember { mutableStateOf(prefs.mirrorMessages) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AlertSwitch("Phone & app calls", mirrorCalls) {
                mirrorCalls = it
                prefs.mirrorPhoneCalls = it
            }
            HorizontalDivider()
            AlertSwitch("Messages", mirrorMessages) {
                mirrorMessages = it
                prefs.mirrorMessages = it
            }
        }
    }
}

/**
 * The Apache's handlebar button, pressed while a call is ringing: reject the
 * call (and, when on, text the caller) or answer it. Reject is the default.
 * The reply controls show only under Reject. See CallReply.
 */
@Composable
private fun HandleButtonCard() {
    val ctx = LocalContext.current
    val prefs = remember { RidePrefs.load(ctx) }
    var action by remember { mutableStateOf(prefs.handlebarCallAction) }
    var enabled by remember { mutableStateOf(prefs.callReplyEnabled) }
    var saved by remember { mutableStateOf(prefs.callReplyText) }
    var editing by remember { mutableStateOf(prefs.callReplyText ?: RidePrefs.DEFAULT_CALL_REPLY) }
    // Accept only needs to answer the call; Reject also sends the reply SMS.
    fun needed() = if (action == HandlebarCallAction.ACCEPT) {
        listOf(Manifest.permission.ANSWER_PHONE_CALLS)
    } else {
        CallReply.PERMISSIONS
    }
    fun missingPermissions() = needed().filter {
        ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
    }
    var missing by remember { mutableStateOf(missingPermissions()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = missingPermissions()
    }
    val max = RidePrefs.MAX_CALL_REPLY_CHARS
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "What the handlebar button does to an incoming call.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HandlebarCallAction.entries.forEach { opt ->
                    FilterChip(
                        selected = action == opt,
                        onClick = {
                            action = opt
                            prefs.handlebarCallAction = opt
                            missing = missingPermissions()
                        },
                        label = { Text(opt.label, textAlign = TextAlign.Center) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (action == HandlebarCallAction.REJECT) {
                HorizontalDivider()
                AlertSwitch("Reply when I reject a call", enabled) {
                    enabled = it
                    prefs.callReplyEnabled = it
                }
                OutlinedTextField(
                    value = editing,
                    onValueChange = { if (it.length <= max) editing = it },
                    label = { Text("Reply text") },
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("${editing.length}/$max") },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(enabled = enabled, onClick = {
                        val trimmed = editing.trim()
                        // Saving the default text stores nothing, so it follows the default.
                        prefs.callReplyText = trimmed.takeIf { it.isNotEmpty() && it != RidePrefs.DEFAULT_CALL_REPLY }
                        saved = prefs.callReplyText
                        editing = saved ?: RidePrefs.DEFAULT_CALL_REPLY
                    }) { Text("Save") }
                    OutlinedButton(enabled = enabled, onClick = {
                        prefs.callReplyText = null
                        saved = null
                        editing = RidePrefs.DEFAULT_CALL_REPLY
                    }) { Text("Reset") }
                    Text(
                        if (saved == null) "Using the default text" else "Saved",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (missing.isNotEmpty()) {
                val warning = if (action == HandlebarCallAction.ACCEPT) {
                    "Needs permission to answer calls."
                } else {
                    "Needs permission to end calls, send SMS and read the caller from the call log and contacts."
                }
                Text(
                    warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = { launcher.launch(missing.toTypedArray()) }) {
                    Text("Grant permissions")
                }
            }
        }
    }
}

@Composable
private fun AlertSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
        AppSwitch(checked = checked, onCheckedChange = onChange)
    }
}
