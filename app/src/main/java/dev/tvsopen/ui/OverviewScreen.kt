package dev.tvsopen.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tvsopen.session.ConnectionState1
import dev.tvsopen.session.HudState
import dev.tvsopen.session.Telemetry
import dev.tvsopen.transport.ConnectionState
import dev.tvsopen.transport.label
import dev.tvsopen.ui.theme.LocalTilePalette
import dev.tvsopen.ui.theme.NumberStyle

/**
 * Overview: the app's main screen.
 *
 * Built for the Apache RTR 160 4V, the vehicle this branch targets: only what
 * its cluster actually sends is shown. Live speed, gear, this-connection
 * distance and engine RPM; the 8-bar fuel gauge; and the persisted records,
 * total distance and top speed. The Ntorq-only tiles (battery voltage, coolant,
 * fuel economy, firmware, best 0–60, service, check engine, range) never arrive
 * from this bike and were removed.
 *
 * Connection state and the Connect button sit at the top, because with no Home
 * tab this is the first thing the app shows.
 *
 * Every absent value renders as an em dash. A `0` would be a lie: it would mean
 * the UI invented a reading rather than reporting that nothing arrived.
 */
@Composable
fun OverviewScreen(
    telemetry: Telemetry,
    hud: HudState,
    connection: ConnectionState1,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    /** Calls, SMS and navigation all arrive as notifications. */
    notificationAccessGranted: Boolean,
    onGrantNotificationAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = connection.state == ConnectionState.CONNECTED
    // Disconnected, the moment-to-moment tiles (speed, gear, RPM, this-
    // connection distance) blank to "—" instead of lingering on the last values
    // Session still holds. Fuel, total distance and top speed are the exception:
    // they are the persisted last-known readings (LastReadings), kept visible
    // with the bike off on purpose, so shownTelemetry carries them through.
    val shown = shownTelemetry(telemetry, connected)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Header(connection, connected)

        if (!notificationAccessGranted) {
            Spacer(Modifier.height(10.dp))
            NotificationAccessBanner(onGrantNotificationAccess)
        }

        if (hud.active) {
            Spacer(Modifier.height(10.dp))
            NavigationStrip(hud)
        }

        Spacer(Modifier.height(14.dp))
        ConnectCard(connected, connection.state.label, telemetry, onConnect, onDisconnect)

        Spacer(Modifier.height(20.dp))
        SectionLabel("Live")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigStat(
                Modifier.weight(1f),
                Icons.Filled.Speed,
                shown.speedKmh?.toString() ?: "—",
                "km/h",
                "Speed",
                LocalTilePalette.current.largeLeft,
            )
            BigStat(
                Modifier.weight(1f),
                Icons.Filled.Settings,
                gearLabel(shown.gear),
                "",
                "Gear",
                LocalTilePalette.current.largeRight,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigStat(
                Modifier.weight(1f),
                Icons.Filled.Route,
                // Live: distance since this link came up; blanks with the bike off.
                shown.connectedDistanceKm()?.let { String.format("%.1f", it) } ?: "—",
                "km",
                "This connection",
                LocalTilePalette.current.neutral,
            )
            BigStat(
                Modifier.weight(1f),
                Icons.Filled.Bolt,
                shown.engineRpm?.toString() ?: "—",
                "rpm",
                // Current engine speed, not a peak.
                "Engine RPM",
                LocalTilePalette.current.neutral,
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Fuel")
        FuelGauge(shown.fuelBars, shown.lowFuel == true)

        Spacer(Modifier.height(20.dp))
        SectionLabel("Records")

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DistanceStat(
                Modifier.weight(1f),
                Icons.Filled.Route,
                shown.odometerKm?.let { String.format("%.1f", it) } ?: "—",
                "Total distance (km)",
            )
            DistanceStat(
                Modifier.weight(1f),
                Icons.Filled.Speed,
                // Persisted like the odometer: the bike updates it only on a
                // new high, so it stays visible with the bike off.
                shown.topSpeedKmh?.toString() ?: "—",
                "Top speed (km/h)",
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * What the tiles should read from. Moment-to-moment live data belongs to a live
 * link, so once the bike is not connected this blanks it — speed, gear, RPM and
 * the this-connection distance all go null and read as `—`, instead of
 * lingering on the last values Session still holds after a drop.
 *
 * Fuel, total distance (odometer) and top speed are kept: they are the
 * persisted last-known readings [LastReadings] stores precisely so they stay
 * visible with the bike off, and blanking them would undo that. This carries
 * exactly those fields through — the same ones LastReadings persists — and
 * drops the rest. (odometerAtConnectKm is among the dropped, so "This
 * connection" reads `—` while disconnected.)
 */
internal fun shownTelemetry(telemetry: Telemetry, connected: Boolean): Telemetry =
    if (connected) {
        telemetry
    } else {
        Telemetry(
            fuelBars = telemetry.fuelBars,
            lowFuel = telemetry.lowFuel,
            odometerKm = telemetry.odometerKm,
            topSpeedKmh = telemetry.topSpeedKmh,
        )
    }

/** `N` for neutral (0), the gear number otherwise, `—` with no reading. */
internal fun gearLabel(gear: Int?): String = when (gear) {
    null -> "—"
    0 -> "N"
    else -> gear.toString()
}

@Composable
private fun Header(connection: ConnectionState1, connected: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("TVSRide", style = MaterialTheme.typography.headlineLarge)
            Text(
                connection.model.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Pill(if (connected) "Connected" else connection.state.label, connected)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(36.dp)
                .background(
                    if (connected) LocalTilePalette.current.connected
                    else MaterialTheme.colorScheme.error,
                    CircleShape,
                ),
        )
    }
}

@Composable
private fun Pill(text: String, on: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (on) LocalTilePalette.current.accent
        else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Connect when there is no link; when there is, say how fresh the data is and
 * offer Disconnect.
 *
 * Freshness is the single most useful "is this actually working" signal: the
 * tiles keep their last values, so without it the screen looks identical
 * whether the link is streaming or died a minute ago.
 */
@Composable
private fun ConnectCard(
    connected: Boolean,
    stateLabel: String,
    telemetry: Telemetry,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val age = remember(telemetry.lastFrameAt) {
        if (telemetry.lastFrameAt == 0L) null
        else System.currentTimeMillis() - telemetry.lastFrameAt
    }
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (connected) "Connected" else "Connect",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (!connected) stateLabel
                    else when {
                        age == null -> "No data received yet"
                        age < 3_000 -> "Data live"
                        age < Telemetry.STALE_AFTER_MS -> "Data ${age / 1000}s old"
                        else -> "Data stale — ${age / 1000}s since last"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (connected) {
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            }
        }
    }
    // Only the disconnected card is tappable as a whole: a stray tap on a live
    // card must not drop the link, so that needs the explicit button.
    if (connected) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = LocalTilePalette.current.accent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            content = content,
        )
    } else {
        Surface(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = LocalTilePalette.current.accent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            content = content,
        )
    }
}

@Composable
private fun NavigationStrip(hud: HudState) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(14.dp)) {
            // Both fields are skipped when blank rather than defaulted. Google
            // Maps puts the distance in the title, so the title is legitimately
            // empty once that is moved to the distance row.
            hud.instructionTitle?.takeIf { it.isNotBlank() }?.let {
                val arrow = dev.tvsopen.nav.NavShortCodes.uiArrow(hud.pictogramId)
                Text(
                    if (arrow.isNotEmpty()) "$arrow $it" else it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            hud.street?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            // -1 is the "no distance in the notification" sentinel; never
            // render it as a figure.
            val distance = hud.distanceMeters
            Text(
                if (distance >= 0) "$distance m · ${hud.etaMinutes} min"
                else "${hud.etaMinutes} min to arrival",
                style = NumberStyle,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
/**
 * Eyebrow section label, per `DESIGN.md` (`badge-pill` / `caption-strong`:
 * small uppercase muted label). Text-only: on a dense telemetry screen a
 * filled pill behind every heading would be noise.
 */
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun BigStat(
    modifier: Modifier,
    icon: ImageVector,
    value: String,
    unit: String,
    label: String,
    background: Color,
) {
    Surface(
        modifier.height(140.dp),
        shape = RoundedCornerShape(24.dp),
        color = background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = NumberStyle,
                    fontSize = 28.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        unit,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DistanceStat(
    modifier: Modifier,
    icon: ImageVector,
    value: String,
    label: String,
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                value,
                style = NumberStyle,
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Eight-segment fuel gauge, as on the RTR 160 4V cluster.
 *
 * The mapping from the bike's fuel byte rests on one reading so far (full tank
 * reads 9 → 8 bars; see ApacheN112Driver.fuelBars). Segments fill from the E
 * side. "No reading" renders differently from "empty" so the two cannot be
 * confused.
 */
@Composable
private fun FuelGauge(bars: Int?, low: Boolean) {
    val filled = bars ?: 0
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "E",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    repeat(Telemetry.FUEL_BAR_MAX) { i ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(22.dp)
                                .background(
                                    when {
                                        bars == null || i >= filled ->
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                        low -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.primary
                                    },
                                    RoundedCornerShape(4.dp),
                                ),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "F",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Shown until notification access is granted, then gone. Without it the
 * cluster gets no incoming calls, SMS or navigation, and a reinstall always
 * loses it: it is a system setting, not a permission a popup can restore.
 */
@Composable
private fun NotificationAccessBanner(onGrant: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Notification access is off",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "Incoming calls, SMS and navigation can't reach your vehicle until you allow it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(onClick = onGrant) { Text("Grant notification access") }
        }
    }
}
