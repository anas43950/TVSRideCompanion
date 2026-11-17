// Filtering, sorting and day grouping for the Rides tab.
//
// Kept free of Compose and Android so the JVM tests drive it directly: the
// time zone, "now" and the locale are parameters, never read from the system
// inside these functions.

package dev.tvsopen.ui

import dev.tvsopen.session.Ride
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/** Which days the Rides tab shows. */
sealed interface RideRange {
    data object All : RideRange
    data object Today : RideRange
    data object ThisWeek : RideRange
    data object ThisMonth : RideRange

    /** Both ends inclusive. */
    data class Custom(val from: LocalDate, val to: LocalDate) : RideRange
}

enum class RideSort { NEWEST, LONGEST }

/** One day's rides, with that day's totals. */
data class DayGroup(
    val date: LocalDate,
    val rides: List<Ride>,
    val rideCount: Int,
    val distanceKm: Double,
)

/** Count and distance for a list of rides; an open ride counts with its distance so far. */
data class RideSummary(val rideCount: Int, val distanceKm: Double)

object RideBrowse {

    /** The day a ride belongs to: the day it started, so a ride past midnight stays put. */
    fun dayOf(ride: Ride, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(ride.startedAt).atZone(zone).toLocalDate()

    /** The inclusive day span [range] covers, or null for [RideRange.All]. */
    fun span(
        range: RideRange,
        zone: ZoneId,
        now: Long,
        locale: Locale = Locale.getDefault(),
    ): ClosedRange<LocalDate>? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (range) {
            RideRange.All -> null
            RideRange.Today -> today..today
            RideRange.ThisWeek -> {
                val first = WeekFields.of(locale).firstDayOfWeek
                today.with(TemporalAdjusters.previousOrSame(first))..today
            }
            RideRange.ThisMonth -> today.withDayOfMonth(1)..today
            is RideRange.Custom ->
                if (range.from <= range.to) range.from..range.to else range.to..range.from
        }
    }

    /** Rides that started on a day inside [range], order kept. */
    fun filter(
        rides: List<Ride>,
        range: RideRange,
        zone: ZoneId,
        now: Long,
        locale: Locale = Locale.getDefault(),
    ): List<Ride> {
        val span = span(range, zone, now, locale) ?: return rides
        return rides.filter { dayOf(it, zone) in span }
    }

    /** Newest first, or longest first with the newer ride ahead on a tie. */
    fun sort(rides: List<Ride>, sort: RideSort): List<Ride> = when (sort) {
        RideSort.NEWEST -> rides.sortedByDescending { it.startedAt }
        RideSort.LONGEST -> rides.sortedWith(
            compareByDescending<Ride> { it.distanceKm }.thenByDescending { it.startedAt },
        )
    }

    /** Days newest first, rides newest first within each day. */
    fun groupByDay(rides: List<Ride>, zone: ZoneId): List<DayGroup> =
        rides.sortedByDescending { it.startedAt }
            .groupBy { dayOf(it, zone) }
            .map { (date, dayRides) ->
                val s = summary(dayRides)
                DayGroup(date, dayRides, s.rideCount, s.distanceKm)
            }
            .sortedByDescending { it.date }

    fun summary(rides: List<Ride>): RideSummary =
        RideSummary(rides.size, rides.sumOf { it.distanceKm })

    /** "Today", "Yesterday", otherwise `Sat 14 Nov`, with the year only when it is not this year. */
    fun dayLabel(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String =
        when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.format(
                DateTimeFormatter.ofPattern(
                    if (date.year == today.year) "EEE d MMM" else "EEE d MMM yyyy",
                    locale,
                ),
            )
        }

    /** `14 Nov`, `12 – 14 Nov`, `28 Oct – 3 Nov`, years added when they differ from [today]'s. */
    fun spanLabel(from: LocalDate, to: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String {
        val (a, b) = if (from <= to) from to to else to to from
        val withYear = a.year != today.year || b.year != today.year
        fun fmt(d: LocalDate, pattern: String) = d.format(DateTimeFormatter.ofPattern(pattern, locale))
        val full = if (withYear) "d MMM yyyy" else "d MMM"
        return when {
            a == b -> fmt(a, full)
            a.year == b.year && a.month == b.month -> "${a.dayOfMonth} – ${fmt(b, full)}"
            a.year == b.year && !withYear -> "${fmt(a, "d MMM")} – ${fmt(b, "d MMM")}"
            else -> "${fmt(a, full)} – ${fmt(b, full)}"
        }
    }

    /** The name the summary line uses for [range]. */
    fun rangeLabel(range: RideRange, today: LocalDate, locale: Locale = Locale.getDefault()): String =
        when (range) {
            RideRange.All -> "All"
            RideRange.Today -> "Today"
            RideRange.ThisWeek -> "This week"
            RideRange.ThisMonth -> "This month"
            is RideRange.Custom -> spanLabel(range.from, range.to, today, locale)
        }

    /** `1 ride · 4.2 km`, `3 rides · 24.6 km`. */
    fun countLabel(s: RideSummary): String =
        "${s.rideCount} ${if (s.rideCount == 1) "ride" else "rides"} · " +
            String.format(Locale.US, "%.1f km", s.distanceKm)
}
