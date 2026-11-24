package dev.tvsopen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import dev.tvsopen.ui.theme.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.transport.Discovered
import dev.tvsopen.transport.ScooterScanner

/**
 * Pick-a-scooter.
 *
 * The first screen that can end with a connection. Choosing a row persists the
 * address and starts the ride service — the code path that was unreachable
 * until now, because nothing ever supplied an address.
 *
 * Tapping a row connects straight away. The model is not asked: this build
 * targets the Apache RTR 160 4V, and the driver is chosen by the Bluetooth
 * service the vehicle offers (the `5500` service → the N112 driver) regardless
 * of any model label, so a picker here would only add a dead choice.
 */
@Composable
fun ScanScreen(
    scanner: ScooterScanner,
    onPick: (address: String) -> Unit,
    onBack: () -> Unit,
    /** Show the system "Turn on Bluetooth?" dialog, then scan once it is on. */
    onRequestBluetooth: () -> Unit,
) {
    val state by scanner.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var onlySmartXonnect by remember {
        mutableStateOf(RidePrefs.load(context).onlySmartXonnect)
    }

    // Stop scanning when the screen leaves composition; nothing else to tear
    // down now that pairing is gone.
    DisposableEffect(Unit) {
        onDispose { scanner.stop() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text("Find your vehicle", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (state.scanning) "Scanning — turn the ignition on"
                    else "Scan stopped",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.scanning) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }

        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Only TVS clusters",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        // Kept short on purpose: a three-line wrap runs the
                        // text up against the switch and reads as clipped.
                        "Turn off if it never appears — flipping rescans",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppSwitch(
                    checked = onlySmartXonnect,
                    onCheckedChange = { enabled ->
                        onlySmartXonnect = enabled
                        RidePrefs.load(context).onlySmartXonnect = enabled
                        scanner.setFilter(enabled)
                    },
                )
            }
        }

        state.error?.let { err ->
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    err,
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        when {
            state.devices.isEmpty() && state.scanning -> CentredNote(
                "Looking for clusters…\n\nTurn the ignition on and make sure " +
                    "Bluetooth is visible to other devices.",
            )

            // The scan is time-boxed, so this is the normal resting state after
            // any successful-but-empty run. Without a button here the only way
            // to scan again is to back out and re-enter, which reads as a bug.
            state.devices.isEmpty() -> CentredNote(
                text = when {
                    state.bluetoothOff ->
                        "Bluetooth is off.\n\nTurn it on to look for your vehicle."
                    state.error != null -> "The scan could not run."
                    else ->
                        "Nothing found.\n\nScan again with the ignition on. If it " +
                            "still shows nothing, switch off \"Only TVS clusters\"."
                },
                action = {
                    // With the radio off, "Scan again" would only fail the same
                    // way; offer the fix instead.
                    Button(
                        onClick = {
                            if (state.bluetoothOff) onRequestBluetooth()
                            else scanner.start(onlySmartXonnect)
                        },
                    ) {
                        Icon(
                            Icons.Filled.Bluetooth,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.bluetoothOff) "Turn on Bluetooth" else "Scan again")
                    }
                },
            )

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.devices, key = { it.address }) { d ->
                    DeviceRow(device = d, onConnect = { onPick(d.address) })
                }
            }
        }
    }
}

/**
 * Empty/working state for the scan list.
 *
 * The horizontal padding is load-bearing: without it `Text` measures against
 * the full screen width and long lines overflow both edges rather than
 * wrapping, which is how this looked before.
 */
@Composable
private fun CentredNote(
    text: String,
    action: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.BluetoothSearching,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Spacer(Modifier.height(20.dp))
                action()
            }
        }
    }
}

/**
 * One discovered vehicle. Tapping it connects — no model picker and no pairing,
 * because the Apache N112 bonds with nothing and its driver is chosen from the
 * service it offers, not from a label the rider would have to guess.
 */
@Composable
private fun DeviceRow(
    device: Discovered,
    onConnect: () -> Unit,
) {
    Surface(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (device.isSmartXonnect) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (device.isSmartXonnect) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.name ?: "Unnamed device",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                Text(
                    when {
                        device.byServiceUuid -> "SmartXonnect"
                        // Name-only. Saying "SmartXonnect" here would be a
                        // claim we cannot back: the service UUID was absent
                        // from the advertisement, so this is a guess.
                        device.byName -> "likely cluster (name only)"
                        else -> "not a cluster"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
