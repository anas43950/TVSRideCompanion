package dev.tvsopen.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.tvsopen.session.Ride
import dev.tvsopen.ui.theme.NumberStyle
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Ride history, grouped by the day each ride started, with date filters.
 *
 * A ride is one connected period with the vehicle, recorded on this phone only
 * (there is no account or cloud). Distance comes from the odometer, top speed
 * from the live speed, and the average is distance ÷ duration, stops included.
 * The filtering and grouping themselves live in [RideBrowse].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RidesScreen(
    rides: List<Ride>,
    totalRides: Int,
    totalDistanceKm: Double,
    modifier: Modifier = Modifier,
) {
    var range by rememberSaveable(stateSaver = RangeSaver) { mutableStateOf<RideRange>(RideRange.All) }
    var sort by rememberSaveable { mutableStateOf(RideSort.NEWEST) }
    var picking by rememberSaveable { mutableStateOf(false) }

    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val shown = RideBrowse.sort(RideBrowse.filter(rides, range, zone, now), sort)

    if (picking) {
        RangePickerDialog(
            initial = range as? RideRange.Custom,
            today = today,
            onDismiss = { picking = false },
            onPicked = {
                range = it
                picking = false
            },
        )
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(2.dp))
            Text("Rides", style = MaterialTheme.typography.headlineLarge)
            Text(
                if (range == RideRange.All) {
                    "$totalRides rides · ${String.format(Locale.US, "%.1f", totalDistanceKm)} km in total"
                } else {
                    "${RideBrowse.rangeLabel(range, today)} · ${RideBrowse.countLabel(RideBrowse.summary(shown))}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (rides.isNotEmpty()) {
            item {
                FilterRow(
                    range = range,
                    sort = sort,
                    today = today,
                    onRange = { range = it },
                    onPickDates = { picking = true },
                    onToggleSort = {
                        sort = if (sort == RideSort.NEWEST) RideSort.LONGEST else RideSort.NEWEST
                    },
                )
            }
        }

        when {
            rides.isEmpty() -> item {
                Text(
                    "No rides yet. A ride is recorded while your vehicle is connected and moving.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
            shown.isEmpty() -> item {
                Column(Modifier.padding(top = 24.dp)) {
                    Text(
                        "No rides in this period.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { range = RideRange.All }) { Text("Show all") }
                }
            }
            sort == RideSort.LONGEST ->
                // One flat list: day headers would break up the distance order.
                items(shown, key = { it.startedAt }) { ride -> RideCard { RideRow(ride, showDate = true, showLabels = true) } }
            else -> RideBrowse.groupByDay(shown, zone).forEach { day ->
                stickyHeader(key = "day-${day.date.toEpochDay()}") { DayHeader(day, today) }
                item(key = "rides-${day.date.toEpochDay()}") { DayCard(day) }
            }
        }
    }
}

@Composable
private fun FilterRow(
    range: RideRange,
    sort: RideSort,
    today: LocalDate,
    onRange: (RideRange) -> Unit,
    onPickDates: () -> Unit,
    onToggleSort: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            RideRange.All to "All",
            RideRange.Today to "Today",
            RideRange.ThisWeek to "This week",
            RideRange.ThisMonth to "This month",
        ).forEach { (r, label) ->
            FilterChip(selected = range == r, onClick = { onRange(r) }, label = { Text(label) })
        }
        FilterChip(
            selected = range is RideRange.Custom,
            onClick = onPickDates,
            label = {
                Text(
                    (range as? RideRange.Custom)?.let { RideBrowse.spanLabel(it.from, it.to, today) }
                        ?: "Pick dates",
                )
            },
        )
        FilterChip(
            selected = sort == RideSort.LONGEST,
            onClick = onToggleSort,
            leadingIcon = {
                Icon(Icons.Filled.SwapVert, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize))
            },
            label = { Text(if (sort == RideSort.NEWEST) "Newest first" else "Longest first") },
        )
    }
}

/** `TODAY · 3 rides · 24.6 km`, on the page background so cards scroll under it. */
@Composable
private fun DayHeader(day: DayGroup, today: LocalDate) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Text(
            "${RideBrowse.dayLabel(day.date, today).uppercase(Locale.getDefault())} · " +
                RideBrowse.countLabel(RideSummary(day.rideCount, day.distanceKm)),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
    }
}

/**
 * Calendar for a custom range. Future days cannot be picked.
 *
 * The picker works in UTC-midnight millis, so days are converted by epoch day,
 * never through the phone's zone, which would shift them by a day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePickerDialog(
    initial: RideRange.Custom?,
    today: LocalDate,
    onDismiss: () -> Unit,
    onPicked: (RideRange.Custom) -> Unit,
) {
    val todayDay = today.toEpochDay()
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.from?.toEpochDay()?.times(DAY_MS),
        initialSelectedEndDateMillis = initial?.to?.toEpochDay()?.times(DAY_MS),
        yearRange = 2020..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis / DAY_MS <= todayDay
            override fun isSelectableYear(year: Int) = year <= today.year
        },
    )
    val start = state.selectedStartDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = start != null,
                onClick = {
                    val from = LocalDate.ofEpochDay(start!! / DAY_MS)
                    // A single tapped day is a one-day range.
                    val to = state.selectedEndDateMillis?.let { LocalDate.ofEpochDay(it / DAY_MS) } ?: from
                    onPicked(RideRange.Custom(from, to))
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(state = state, modifier = Modifier.weight(1f))
    }
}

private const val DAY_MS = 86_400_000L

/** Saves a [RideRange] as a kind plus two epoch days, so it survives rotation. */
private val RangeSaver = Saver<RideRange, List<Long>>(
    save = { r ->
        when (r) {
            RideRange.All -> listOf(0L)
            RideRange.Today -> listOf(1L)
            RideRange.ThisWeek -> listOf(2L)
            RideRange.ThisMonth -> listOf(3L)
            is RideRange.Custom -> listOf(4L, r.from.toEpochDay(), r.to.toEpochDay())
        }
    },
    restore = { l ->
        when (l.firstOrNull()) {
            1L -> RideRange.Today
            2L -> RideRange.ThisWeek
            3L -> RideRange.ThisMonth
            4L -> RideRange.Custom(LocalDate.ofEpochDay(l[1]), LocalDate.ofEpochDay(l[2]))
            else -> RideRange.All
        }
    },
)

/** The card surface every ride sits on. */
@Composable
private fun RideCard(content: @Composable () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        content = content,
    )
}

/** One card for the whole day, its rides newest first, split by hairlines. */
@Composable
private fun DayCard(day: DayGroup) {
    RideCard {
        Column {
            // The stat names once, at the top; each ride below has values only.
            StatColumns(
                STAT_LABELS,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
            day.rides.forEachIndexed { i, ride ->
                if (i > 0) {
                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                RideRow(ride, showDate = false, showLabels = false)
            }
        }
    }
}

/**
 * One ride's lines, without a surface: [RideCard] or [DayCard] supplies it.
 * [showLabels] puts the stat names under the values; a day card shows them
 * once at its top instead.
 */
@Composable
private fun RideRow(ride: Ride, showDate: Boolean, showLabels: Boolean) {
    val now = System.currentTimeMillis()
    val times = if (ride.inProgress) "${clock(ride.startedAt)} – now"
    else "${clock(ride.startedAt)} – ${clock(ride.endedAt!!)}"
    Column(Modifier.padding(16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                if (showDate) {
                    Text(rideDate(ride.startedAt), style = MaterialTheme.typography.titleMedium)
                } else {
                    // Start and end are context; the distance and stats are the reading.
                    Text(
                        times,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val sub = when {
                    showDate && ride.inProgress -> "$times · Riding now"
                    showDate -> times
                    ride.inProgress -> "Riding now"
                    else -> null
                }
                sub?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ride.inProgress) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (ride.inProgress) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            Text(
                String.format(Locale.US, "%.1f km", ride.distanceKm),
                style = NumberStyle,
            )
        }
        Spacer(Modifier.height(10.dp))
        StatColumns(
            listOf(
                durationLabel(ride.durationMinutes(now)),
                ride.topSpeedKmh?.let { "$it km/h" } ?: "—",
                ride.avgSpeedKmh(now)?.let { String.format(Locale.US, "%.0f km/h", it) } ?: "—",
            ),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        if (showLabels) {
            StatColumns(
                STAT_LABELS,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val STAT_LABELS = listOf("Duration", "Top speed", "Avg speed")

/**
 * Three equal columns, aligned start / centre / end, so a label row and the
 * value rows under it line up whatever the text widths.
 */
@Composable
private fun StatColumns(
    texts: List<String>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
) {
    Row(modifier.fillMaxWidth()) {
        texts.forEachIndexed { i, t ->
            Text(
                t,
                style = style,
                color = color,
                fontWeight = fontWeight,
                textAlign = when (i) {
                    0 -> TextAlign.Start
                    texts.lastIndex -> TextAlign.End
                    else -> TextAlign.Center
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun rideDate(millis: Long): String =
    SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(Date(millis))

/** `6:02 pm`, 12-hour with the locale's am/pm marker. */
private fun clock(millis: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))

/** `48 min`, `1 h 05 min`. */
internal fun durationLabel(minutes: Long): String =
    if (minutes < 60) "$minutes min"
    else String.format(Locale.US, "%d h %02d min", minutes / 60, minutes % 60)
