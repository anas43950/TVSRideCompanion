// Rides tab filters, sort and day grouping. Fixed zone and "now"; invented rides.

package dev.tvsopen.ui

import dev.tvsopen.session.Ride
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class RideBrowseTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    /** Wednesday 18 Nov 2026, 10:00 local. */
    private val now = at(2026, 11, 18, 10, 0)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun ride(start: Long, km: Double, open: Boolean = false) =
        Ride(start, if (open) null else start + 20 * 60_000L, km)

    private fun starts(rides: List<Ride>) = rides.map { it.startedAt }

    @Test
    fun `a ride past midnight stays on the day it started`() {
        val r = Ride(at(2026, 11, 17, 23, 50), at(2026, 11, 18, 0, 20), 9.0)
        val groups = RideBrowse.groupByDay(listOf(r), zone)
        assertEquals(LocalDate.of(2026, 11, 17), groups.single().date)
        assertEquals(emptyList<Ride>(), RideBrowse.filter(listOf(r), RideRange.Today, zone, now))
    }

    @Test
    fun `today starts at local midnight`() {
        val first = ride(at(2026, 11, 18, 0, 0), 1.0)
        val before = ride(at(2026, 11, 17, 23, 59), 1.0)
        val got = RideBrowse.filter(listOf(first, before), RideRange.Today, zone, now)
        assertEquals(starts(listOf(first)), starts(got))
    }

    @Test
    fun `this week follows the locale's first day`() {
        val sunday = ride(at(2026, 11, 15, 9, 0), 1.0)
        val monday = ride(at(2026, 11, 16, 9, 0), 1.0)
        val saturday = ride(at(2026, 11, 14, 9, 0), 1.0)
        val all = listOf(monday, sunday, saturday)
        // UK weeks start on Monday, US weeks on Sunday.
        assertEquals(
            starts(listOf(monday)),
            starts(RideBrowse.filter(all, RideRange.ThisWeek, zone, now, Locale.UK)),
        )
        assertEquals(
            starts(listOf(monday, sunday)),
            starts(RideBrowse.filter(all, RideRange.ThisWeek, zone, now, Locale.US)),
        )
    }

    @Test
    fun `this month starts on the first`() {
        val first = ride(at(2026, 11, 1, 6, 0), 1.0)
        val lastOfOctober = ride(at(2026, 10, 31, 22, 0), 1.0)
        val got = RideBrowse.filter(listOf(first, lastOfOctober), RideRange.ThisMonth, zone, now)
        assertEquals(starts(listOf(first)), starts(got))
    }

    @Test
    fun `a custom range includes both end days, in either order`() {
        val rides = (10..16).map { ride(at(2026, 11, it, 12, 0), 1.0) }
        val range = RideRange.Custom(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 14))
        val got = RideBrowse.filter(rides, range, zone, now)
        assertEquals(listOf(12, 13, 14), got.map { RideBrowse.dayOf(it, zone).dayOfMonth })
        val reversed = RideRange.Custom(range.to, range.from)
        assertEquals(starts(got), starts(RideBrowse.filter(rides, reversed, zone, now)))
    }

    @Test
    fun `all keeps every ride`() {
        val rides = listOf(ride(at(2025, 1, 1, 8, 0), 1.0), ride(now, 2.0))
        assertEquals(starts(rides), starts(RideBrowse.filter(rides, RideRange.All, zone, now)))
    }

    @Test
    fun `day totals add up, the open ride included`() {
        val rides = listOf(
            ride(at(2026, 11, 18, 9, 10), 8.1),
            ride(at(2026, 11, 18, 7, 0), 4.2),
            ride(at(2026, 11, 18, 9, 50), 12.3, open = true),
            ride(at(2026, 11, 17, 20, 15), 4.2),
        )
        val groups = RideBrowse.groupByDay(rides, zone)
        assertEquals(listOf(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 17)), groups.map { it.date })
        val today = groups.first()
        assertEquals(3, today.rideCount)
        assertEquals(24.6, today.distanceKm, 1e-9)
        assertEquals("3 rides · 24.6 km", RideBrowse.countLabel(RideSummary(today.rideCount, today.distanceKm)))
        assertEquals("1 ride · 4.2 km", RideBrowse.countLabel(RideSummary(1, 4.2)))
        // Newest first within the day.
        assertEquals(
            listOf(at(2026, 11, 18, 9, 50), at(2026, 11, 18, 9, 10), at(2026, 11, 18, 7, 0)),
            starts(today.rides),
        )
    }

    @Test
    fun `longest first sorts by distance across days`() {
        val a = ride(at(2026, 11, 18, 9, 0), 3.0)
        val b = ride(at(2026, 11, 10, 9, 0), 40.0)
        val c = ride(at(2026, 11, 15, 9, 0), 12.0)
        val d = ride(at(2026, 11, 16, 9, 0), 12.0)
        val got = RideBrowse.sort(listOf(a, b, c, d), RideSort.LONGEST)
        // Ties put the newer ride first.
        assertEquals(starts(listOf(b, d, c, a)), starts(got))
        assertEquals(starts(listOf(a, d, c, b)), starts(RideBrowse.sort(listOf(b, c, a, d), RideSort.NEWEST)))
    }

    @Test
    fun `day labels`() {
        val today = LocalDate.of(2026, 11, 18)
        assertEquals("Today", RideBrowse.dayLabel(today, today, Locale.UK))
        assertEquals("Yesterday", RideBrowse.dayLabel(today.minusDays(1), today, Locale.UK))
        assertEquals("Sat 14 Nov", RideBrowse.dayLabel(LocalDate.of(2026, 11, 14), today, Locale.UK))
        assertEquals("Wed 31 Dec 2025", RideBrowse.dayLabel(LocalDate.of(2025, 12, 31), today, Locale.UK))
    }

    @Test
    fun `range labels`() {
        val today = LocalDate.of(2026, 11, 18)
        fun label(a: LocalDate, b: LocalDate) = RideBrowse.spanLabel(a, b, today, Locale.UK)
        assertEquals("14 Nov", label(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 14)))
        assertEquals("12 – 14 Nov", label(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 14)))
        assertEquals("28 Oct – 3 Nov", label(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 3)))
        assertEquals("28 Dec 2025 – 3 Jan 2026", label(LocalDate.of(2025, 12, 28), LocalDate.of(2026, 1, 3)))
        assertEquals("This week", RideBrowse.rangeLabel(RideRange.ThisWeek, today, Locale.UK))
    }
}
