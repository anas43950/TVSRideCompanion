package dev.tvsopen.ui

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
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import dev.tvsopen.Nucleus
import dev.tvsopen.data.DataExport
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.protocol.ApacheN112Driver
import dev.tvsopen.protocol.Confidence
import dev.tvsopen.protocol.Pictograms
import dev.tvsopen.transport.label
import dev.tvsopen.ui.theme.ThemeMode
import dev.tvsopen.ui.theme.ThemePrefs
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * More / settings screen.
 *
 * The transparency panel is the important part: it states exactly which
 * protocol facts are verified and which still await hardware confirmation, so
 * a user reading this app knows what they can trust.
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
            Text("NAMES", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            NamesCard(
                riderName = riderName,
                onRiderNameChange = onRiderNameChange,
                nickname = deviceNickname,
                onNicknameChange = onDeviceNicknameChange,
            )
        }

        item {
            Text("ALERTS", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            val ctx = LocalContext.current
            val prefs = remember { RidePrefs.load(ctx) }
            var mirrorCalls by remember { mutableStateOf(prefs.mirrorPhoneCalls) }
            var mirrorMessages by remember { mutableStateOf(prefs.mirrorMessages) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Phone & app calls", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Show incoming calls (phone, WhatsApp, Telegram, etc.) on the cluster.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AppSwitch(
                            checked = mirrorCalls,
                            onCheckedChange = {
                                mirrorCalls = it
                                prefs.mirrorPhoneCalls = it
                            },
                        )
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Messages", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Show the sender (WhatsApp, Telegram, SMS, etc.) and the unread-SMS marker. Message text is never sent.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AppSwitch(
                            checked = mirrorMessages,
                            onCheckedChange = {
                                mirrorMessages = it
                                prefs.mirrorMessages = it
                            },
                        )
                    }
                }
            }
        }

        item { CallReplyCard() }

        item {
            Text("PROTOCOL TRANSPARENCY", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "What is verified, and what still needs your vehicle",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Nucleus.describe().forEachIndexed { index, (model, facts) ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Text(
                            model.displayName,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        facts.forEach { (fact, confidence) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    when (confidence) {
                                        Confidence.VERIFIED_FROM_APK -> "●"
                                        Confidence.INHERITED_UNVERIFIED -> "◐"
                                        Confidence.NEEDS_CAPTURE -> "○"
                                    },
                                    color = when (confidence) {
                                        Confidence.VERIFIED_FROM_APK -> MaterialTheme.colorScheme.primary
                                        Confidence.INHERITED_UNVERIFIED -> MaterialTheme.colorScheme.tertiary
                                        Confidence.NEEDS_CAPTURE -> MaterialTheme.colorScheme.outline
                                    },
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Text(fact, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "● verified from the official APK   " +
                            "◐ inherited, unverified   ○ needs capture",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Pictograms (${Pictograms.ids.size})",
                        style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Official app table. Two tables disagree upstream — " +
                            "see docs/NAVIGATION.md if an arrow renders wrong.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Navigation display", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Every instruction is compressed before it is sent: " +
                            "Turn left onto Example Road becomes <- LFT to Example Rd.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "The arrow prefix marks the turn at a glance " +
                            "(<- left, -> right, ^ straight, U U-turn, O roundabout).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Text wider than the row scrolls one window per send " +
                            "instead of clipping its tail.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Both cluster lines go out together (0x4F + 0x50, rows = 2); " +
                            "a single alternating line when the second has nothing to show.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Compression, arrows and scrolling run on the phone and are " +
                            "unit-tested. Whether the Ntorq renders both lines at once " +
                            "still needs your vehicle.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Science, null)
                    Text("TVSRide", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "No account. No TVS server. No INTERNET permission.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        item {
            Text("YOUR DATA", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            val ctx = LocalContext.current
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Export rides and settings as JSON. " +
                            "Everything stays on your device unless you share it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {
                        val json = DataExport.buildJson(ctx)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_SUBJECT, "TVSRide export")
                            putExtra(Intent.EXTRA_TEXT, json)
                        }
                        ctx.startActivity(Intent.createChooser(intent, "Export data"))
                    }) {
                        Text("Export & share")
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
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
 * The Apache's handlebar end-call button: a ringing call is rejected and,
 * when on, the caller (contacts only) gets this text. See CallReply.
 */
@Composable
private fun CallReplyCard() {
    val ctx = LocalContext.current
    val prefs = remember { RidePrefs.load(ctx) }
    var enabled by remember { mutableStateOf(prefs.callReplyEnabled) }
    var saved by remember { mutableStateOf(prefs.callReplyText) }
    var editing by remember { mutableStateOf(prefs.callReplyText ?: RidePrefs.DEFAULT_CALL_REPLY) }
    fun missingPermissions() = CallReply.PERMISSIONS.filter {
        ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
    }
    var missing by remember { mutableStateOf(missingPermissions()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = missingPermissions()
    }
    val max = RidePrefs.MAX_CALL_REPLY_CHARS
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Reply when I reject a call", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Press the handlebar call button twice (Apache RTR 160 4V) to end the call; " +
                            "one press only hides it on the dashboard. " +
                            "When this is on, a caller in your contacts also gets this text by SMS. " +
                            "Normal SMS charges apply.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppSwitch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        prefs.callReplyEnabled = it
                    },
                )
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
            Text(
                if (saved == null) "Using the default text" else "Saved",
                style = MaterialTheme.typography.bodySmall,
            )
            if (missing.isNotEmpty()) {
                Text(
                    "Needs permission to end calls, send SMS and read the caller from the call log and contacts.",
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
