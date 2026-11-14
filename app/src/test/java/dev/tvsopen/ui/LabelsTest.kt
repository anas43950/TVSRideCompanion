// Small display rules on Overview and Rides.

package dev.tvsopen.ui

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
    fun `ride duration reads as minutes, then hours and minutes`() {
        assertEquals("0 min", durationLabel(0))
        assertEquals("48 min", durationLabel(48))
        assertEquals("1 h 05 min", durationLabel(65))
        assertEquals("2 h 00 min", durationLabel(120))
    }
}
