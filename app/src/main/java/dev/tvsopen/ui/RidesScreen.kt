package dev.tvsopen.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.tvsopen.session.Ride
import dev.tvsopen.ui.theme.NumberStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ride history, newest first.
 *
 * A ride is one connected period with the vehicle, recorded on this phone only
 * (there is no account or cloud). Distance comes from the odometer, top speed
 * from the live speed, and the average is distance ÷ duration, stops included.
 */
@Composable
fun RidesScreen(
    rides: List<Ride>,
    totalRides: Int,
    totalDistanceKm: Double,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(2.dp))
            Text("Rides", style = MaterialTheme.typography.headlineLarge)
            Text(
                "$totalRides rides · ${String.format(Locale.US, "%.1f", totalDistanceKm)} km in total",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Kept on this phone only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (rides.isEmpty()) {
            item {
                Text(
                    "No rides yet. A ride is recorded while your vehicle is connected and moving.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }

        items(rides, key = { it.startedAt }) { ride -> RideRow(ride) }
    }
}

@Composable
private fun RideRow(ride: Ride) {
    val now = System.currentTimeMillis()
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        rideDate(ride.startedAt),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (ride.inProgress) "${clock(ride.startedAt)} – now · Riding now"
                        else "${clock(ride.startedAt)} – ${clock(ride.endedAt!!)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ride.inProgress) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (ride.inProgress) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                Text(
                    String.format(Locale.US, "%.1f km", ride.distanceKm),
                    style = NumberStyle,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Duration", durationLabel(ride.durationMinutes(now)))
                Stat("Top speed", ride.topSpeedKmh?.let { "$it km/h" } ?: "—")
                Stat(
                    "Avg speed",
                    ride.avgSpeedKmh(now)?.let { String.format(Locale.US, "%.0f km/h", it) } ?: "—",
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun rideDate(millis: Long): String =
    SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(Date(millis))

private fun clock(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

/** `48 min`, `1 h 05 min`. */
internal fun durationLabel(minutes: Long): String =
    if (minutes < 60) "$minutes min"
    else String.format(Locale.US, "%d h %02d min", minutes / 60, minutes % 60)
