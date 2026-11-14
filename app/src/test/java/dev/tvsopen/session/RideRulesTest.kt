// When connections join into one ride, and when they are not a ride at all.
// Invented times and odometer values only.

package dev.tvsopen.session

import dev.tvsopen.session.RideRules.State
import dev.tvsopen.session.RideRules.Totals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideRulesTest {

    private val min = 60_000L
    private val t0 = 1_000_000_000L
    private val empty = State(emptyList(), Totals(0, 0.0))

    /** Connect at [at], ride from [odoFrom] to [odoTo], disconnect at [until]. */
    private fun ride(s: State, at: Long, odoFrom: Double, odoTo: Double, until: Long): State {
        var st = RideRules.onConnect(s, at)
        st = sample(st, odoFrom, at)
        st = sample(st, odoTo, until)
        return RideRules.onDisconnect(st, until)
    }

    private fun sample(s: State, odo: Double?, at: Long, speed: Int? = null): State {
        val open = s.rides.first()
        return s.copy(rides = listOf(RideRules.onSample(open, odo, speed, at)) + s.rides.drop(1))
    }

    @Test
    fun `a reconnect within 5 minutes joins the ride`() {
        var s = ride(empty, t0, 1000.0, 1004.0, t0 + 20 * min)
        assertEquals(Totals(1, 4.0), s.totals)

        s = ride(s, t0 + 24 * min, 1004.0, 1012.3, t0 + 40 * min)
        assertEquals(1, s.rides.size)
        val r = s.rides.single()
        assertEquals(12.3, r.distanceKm, 1e-9)
        assertEquals(t0, r.startedAt)
        assertEquals(t0 + 40 * min, r.endedAt)
        // Taken back out on reopen, counted again with the new distance.
        assertEquals(1, s.totals.rides)
        assertEquals(12.3, s.totals.distanceKm, 1e-9)
    }

    @Test
    fun `a reconnect after more than 5 minutes is a new ride`() {
        var s = ride(empty, t0, 1000.0, 1004.0, t0 + 20 * min)
        s = ride(s, t0 + 26 * min, 1004.0, 1006.0, t0 + 40 * min)
        assertEquals(2, s.rides.size)
        assertEquals(2.0, s.rides.first().distanceKm, 1e-9)
        assertEquals(2, s.totals.rides)
        assertEquals(6.0, s.totals.distanceKm, 1e-9)
    }

    @Test
    fun `a joined ride includes distance ridden while disconnected`() {
        var s = ride(empty, t0, 1000.0, 1004.0, t0 + 20 * min)
        // Odometer moved 1 km during the 3-minute gap.
        s = ride(s, t0 + 23 * min, 1005.0, 1006.0, t0 + 30 * min)
        assertEquals(6.0, s.rides.single().distanceKm, 1e-9)
    }

    @Test
    fun `a parked connection is hidden, uncounted, then dropped`() {
        var s = ride(empty, t0, 1000.0, 1000.3, t0 + 2 * min)
        assertEquals(1, s.rides.size)
        assertTrue(RideRules.visible(s.rides).isEmpty())
        assertEquals(Totals(0, 0.0), s.totals)

        s = RideRules.settle(s, t0 + 8 * min)
        assertTrue(s.rides.isEmpty())
    }

    @Test
    fun `a short ride still joins a quick reconnect`() {
        var s = ride(empty, t0, 1000.0, 1000.3, t0 + 2 * min)
        s = ride(s, t0 + 5 * min, 1000.3, 1003.0, t0 + 15 * min)
        assertEquals(3.0, s.rides.single().distanceKm, 1e-9)
        assertEquals(Totals(1, 3.0), s.totals)
    }

    @Test
    fun `a ride left open by a killed app continues within 5 minutes`() {
        var s = RideRules.onConnect(empty, t0)
        s = sample(s, 1000.0, t0)
        s = sample(s, 1002.0, t0 + 10 * min)
        // The app dies here; it restarts and reconnects 3 minutes after the last sample.
        s = RideRules.onConnect(s, t0 + 13 * min)
        assertEquals(1, s.rides.size)
        assertNull(s.rides.single().endedAt)
        s = sample(s, 1005.0, t0 + 20 * min)
        assertEquals(5.0, s.rides.single().distanceKm, 1e-9)
    }

    @Test
    fun `a stale open ride is closed at its last sample, not now`() {
        var s = RideRules.onConnect(empty, t0)
        s = sample(s, 1000.0, t0)
        s = sample(s, 1002.0, t0 + 10 * min)
        s = RideRules.onConnect(s, t0 + 30 * min)
        assertEquals(2, s.rides.size)
        val old = s.rides[1]
        assertEquals(t0 + 10 * min, old.endedAt)
        assertEquals(Totals(1, 2.0), s.totals)
        assertNull(s.rides.first().endedAt)
    }

    @Test
    fun `top speed carries across a joined ride`() {
        var s = RideRules.onConnect(empty, t0)
        s = sample(s, 1000.0, t0, speed = 72)
        s = sample(s, 1003.0, t0 + 10 * min, speed = 40)
        s = RideRules.onDisconnect(s, t0 + 10 * min)
        s = RideRules.onConnect(s, t0 + 12 * min)
        s = sample(s, 1004.0, t0 + 15 * min, speed = 55)
        assertEquals(72, s.rides.single().topSpeedKmh)
    }

    @Test
    fun `distance never goes down`() {
        var s = RideRules.onConnect(empty, t0)
        s = sample(s, 1000.0, t0)
        s = sample(s, 1003.0, t0 + min)
        s = sample(s, 999.0, t0 + 2 * min)
        s = sample(s, null, t0 + 3 * min)
        assertEquals(3.0, s.rides.single().distanceKm, 1e-9)
    }

    @Test
    fun `totals never go negative`() {
        assertEquals(Totals(0, 0.0), Totals(0, 0.0) - Ride(t0, t0 + min, 0.1))
    }
}
