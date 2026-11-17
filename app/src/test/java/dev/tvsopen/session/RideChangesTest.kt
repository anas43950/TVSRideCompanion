// What the ride log writes to the database after a change. Invented rides.

package dev.tvsopen.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideChangesTest {

    private val closed = Ride(1_000L, 2_000L, 5.0, 60, 100.0, 2_000L)
    private val older = Ride(500L, 900L, 3.0, 50, 95.0, 900L)
    private val open = Ride(3_000L, null, 0.0, null, 105.0, 3_000L)

    @Test
    fun `no change writes nothing`() {
        val rides = listOf(closed, older)
        assertTrue(RideChanges.diff(rides, rides.map { it.copy() }).isEmpty)
    }

    @Test
    fun `progress on the open ride writes just that ride`() {
        val moved = open.copy(distanceKm = 1.2, topSpeedKmh = 44, lastSeenAt = 3_500L)
        val got = RideChanges.diff(listOf(open, closed, older), listOf(moved, closed, older))
        assertEquals(listOf(moved), got.upserts)
        assertEquals(emptyList<Long>(), got.deletes)
    }

    @Test
    fun `a new ride is written`() {
        val got = RideChanges.diff(listOf(closed), listOf(open, closed))
        assertEquals(listOf(open), got.upserts)
        assertEquals(emptyList<Long>(), got.deletes)
    }

    @Test
    fun `a ride settle dropped is deleted`() {
        val short = Ride(4_000L, 4_100L, 0.0, null, 105.0, 4_100L)
        val state = RideRules.State(listOf(short, closed), RideRules.Totals(1, 5.0))
        val settled = RideRules.settle(state, 4_100L + RideRules.MERGE_GAP_MS + 1)
        val got = RideChanges.diff(state.rides, settled.rides)
        assertEquals(emptyList<Ride>(), got.upserts)
        assertEquals(listOf(4_000L), got.deletes)
    }
}
