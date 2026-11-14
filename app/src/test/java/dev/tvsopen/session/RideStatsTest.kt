// Per-ride top speed and average speed (Rides tab).
//
// Top speed is the highest live speed seen while connected; the average is
// distance ÷ duration, stops included. Invented values only.

package dev.tvsopen.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideStatsTest {

    private val start = 1_000_000L
    private val hour = 3_600_000L

    @Test
    fun `top speed is the highest sample`() {
        var ride = Ride(start, null, 0.0)
        listOf(20, 55, 48, null, 61, 30).forEachIndexed { i, kmh ->
            ride = ride.withSample(distanceKm = i.toDouble(), speedKmh = kmh)
        }
        assertEquals(61, ride.topSpeedKmh)
        assertEquals(5.0, ride.distanceKm, 0.0)
    }

    @Test
    fun `no speed samples leave top speed unknown`() {
        val ride = Ride(start, null, 0.0).withSample(2.0, null)
        assertNull(ride.topSpeedKmh)
    }

    @Test
    fun `a negative distance keeps the previous one`() {
        val ride = Ride(start, null, 3.0).withSample(-1.0, 40)
        assertEquals(3.0, ride.distanceKm, 0.0)
        assertEquals(40, ride.topSpeedKmh)
    }

    @Test
    fun `average speed is distance over duration`() {
        val ride = Ride(start, start + hour / 2, 15.0)
        assertEquals(30.0, ride.avgSpeedKmh()!!, 0.001)
    }

    @Test
    fun `average speed needs distance and a minute of ride`() {
        assertNull(Ride(start, start + hour, 0.0).avgSpeedKmh())
        assertNull(Ride(start, start + 30_000L, 1.0).avgSpeedKmh())
    }

    @Test
    fun `an open ride averages up to now`() {
        val ride = Ride(start, null, 10.0)
        assertEquals(20.0, ride.avgSpeedKmh(now = start + hour / 2)!!, 0.001)
    }
}
