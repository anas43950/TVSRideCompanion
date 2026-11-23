// Last fuel level, odometer and top speed, kept across app restarts. Invented
// values.

package dev.tvsopen.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastReadingsTest {

    @Test
    fun `a new reading is stored`() {
        val saved = LastReadings.Values()
        val t = Telemetry(fuelBars = 7, lowFuel = false, odometerKm = 6789.4)
        assertEquals(LastReadings.Values(7, false, 6789.4), LastReadings.update(saved, t))
    }

    @Test
    fun `an unchanged reading writes nothing`() {
        val saved = LastReadings.Values(7, false, 6789.4)
        val t = Telemetry(fuelBars = 7, lowFuel = false, odometerKm = 6789.4, speedKmh = 40)
        assertNull(LastReadings.update(saved, t))
    }

    @Test
    fun `a reading the bike has not sent keeps the stored one`() {
        val saved = LastReadings.Values(7, false, 6789.4)
        // Odometer moved, fuel not received yet in this process.
        val t = Telemetry(odometerKm = 6789.5)
        assertEquals(LastReadings.Values(7, false, 6789.5), LastReadings.update(saved, t))
    }

    @Test
    fun `top speed is stored and a new high replaces it`() {
        val first = LastReadings.update(LastReadings.Values(), Telemetry(topSpeedKmh = 88))
        assertEquals(88, first?.topSpeedKmh)
        // A new high the bike reports replaces the stored one.
        val higher = LastReadings.update(LastReadings.Values(topSpeedKmh = 88), Telemetry(topSpeedKmh = 102))
        assertEquals(102, higher?.topSpeedKmh)
        // No top speed this frame keeps the stored one and writes nothing.
        assertNull(LastReadings.update(LastReadings.Values(topSpeedKmh = 102), Telemetry(speedKmh = 40)))
        // Restore fills it in when the process has not received one yet.
        assertEquals(102, LastReadings.restore(Telemetry(), LastReadings.Values(topSpeedKmh = 102)).topSpeedKmh)
    }

    @Test
    fun `restore fills only what this process has not received`() {
        val saved = LastReadings.Values(7, false, 6789.4)
        val restored = LastReadings.restore(Telemetry(), saved)
        assertEquals(7, restored.fuelBars)
        assertEquals(false, restored.lowFuel)
        assertEquals(6789.4, restored.odometerKm!!, 0.0)
        // Never reads as live, and "This connection" stays unset.
        assertEquals(0L, restored.lastFrameAt)
        assertNull(restored.connectedDistanceKm())

        val live = Telemetry(fuelBars = 6, odometerKm = 6800.0)
        val kept = LastReadings.restore(live, saved)
        assertEquals(6, kept.fuelBars)
        assertEquals(6800.0, kept.odometerKm!!, 0.0)
    }

    @Test
    fun `nothing stored restores nothing`() {
        assertEquals(Telemetry(), LastReadings.restore(Telemetry(), LastReadings.Values()))
    }
}
