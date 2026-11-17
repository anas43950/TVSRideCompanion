// Small display rules on Overview and Rides.

package dev.tvsopen.ui

import dev.tvsopen.session.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Test

class LabelsTest {

    @Test
    fun `gear shows N for neutral and a dash with no reading`() {
        assertEquals("N", gearLabel(0))
        assertEquals("3", gearLabel(3))
        assertEquals("—", gearLabel(null))
    }

    @Test
    fun `disconnected keeps saved fuel and distance, blanks live data`() {
        val live = Telemetry(
            speedKmh = 42,
            gear = 3,
            topSpeedKmh = 88,
            engineRpm = 4200,
            fuelBars = 6,
            lowFuel = false,
            odometerKm = 1234.5,
            odometerAtConnectKm = 1200.0,
        )
        // Connected: the real snapshot flows through untouched.
        assertEquals(live, shownTelemetry(live, connected = true))
        // Disconnected: only the persisted last-known readings remain — fuel
        // and total distance stay visible, live tiles fall back to "—", and
        // "This connection" is gone with odometerAtConnectKm dropped.
        assertEquals(
            Telemetry(fuelBars = 6, lowFuel = false, odometerKm = 1234.5),
            shownTelemetry(live, connected = false),
        )
    }

    @Test
    fun `ride duration reads as minutes, then hours and minutes`() {
        assertEquals("0 min", durationLabel(0))
        assertEquals("48 min", durationLabel(48))
        assertEquals("1 h 05 min", durationLabel(65))
        assertEquals("2 h 00 min", durationLabel(120))
    }
}
