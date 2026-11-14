// When a connection is a new ride, the same ride, or not a ride at all.
//
// Pure: no Android, no clock, no storage. RideLog applies these results to
// SharedPreferences; the tests drive them directly.
//
// The rules, as the rider chose them:
// - A reconnect within MERGE_GAP_MS of the last disconnect (or of the last
//   sample, when the app was killed mid-ride) continues the same ride.
// - A ride under MIN_RIDE_KM is not a ride. It stays saved, hidden and
//   uncounted, only until its merge window passes, so a quick reconnect can
//   still join it; then it is dropped.
// - Gap time counts: a joined ride runs from first connect to last disconnect.

package dev.tvsopen.session

object RideRules {

    /** A reconnect within this long continues the previous ride. */
    const val MERGE_GAP_MS = 5 * 60_000L

    /** Shorter rides (parked connections, moving the bike) are not kept. */
    const val MIN_RIDE_KM = 0.5

    /** An open ride is saved at most this often, so a killed app loses little. */
    const val SAVE_EVERY_MS = 30_000L

    /** Running totals across every counted ride, including ones past the window. */
    data class Totals(val rides: Int, val distanceKm: Double) {
        operator fun plus(r: Ride) = Totals(rides + 1, distanceKm + r.distanceKm)
        operator fun minus(r: Ride) = Totals(
            (rides - 1).coerceAtLeast(0),
            (distanceKm - r.distanceKm).coerceAtLeast(0.0),
        )
    }

    /** All saved rides, most recent first, plus the totals. */
    data class State(val rides: List<Ride>, val totals: Totals)

    /** A closed ride long enough to be in the totals. */
    fun counts(r: Ride): Boolean = !r.inProgress && r.distanceKm >= MIN_RIDE_KM

    /** What the Rides tab and the export show: open rides and real ones. */
    fun visible(rides: List<Ride>): List<Ride> =
        rides.filter { it.inProgress || it.distanceKm >= MIN_RIDE_KM }

    /** The last moment the ride was known to be going. */
    private fun lastSeen(r: Ride): Long = r.lastSeenAt ?: r.startedAt

    /**
     * Close rides left open by a killed app once their window has passed (at
     * their last sample, not now), and drop short closed rides whose window
     * has passed. Neither was in the totals, so only a closed-and-long-enough
     * stale ride is added.
     */
    fun settle(s: State, now: Long): State {
        var totals = s.totals
        val rides = s.rides.mapNotNull { r ->
            when {
                r.inProgress && now - lastSeen(r) > MERGE_GAP_MS -> {
                    val closed = r.copy(endedAt = lastSeen(r))
                    if (counts(closed)) {
                        totals += closed
                        closed
                    } else {
                        null
                    }
                }
                !r.inProgress && !counts(r) && now - (r.endedAt ?: r.startedAt) > MERGE_GAP_MS -> null
                else -> r
            }
        }
        return State(rides, totals)
    }

    /** The link came up: continue, rejoin or start a ride. */
    fun onConnect(s: State, now: Long): State {
        val settled = settle(s, now)
        val latest = settled.rides.firstOrNull()
        if (latest != null) {
            // Open and within its window (settle closed it otherwise).
            if (latest.inProgress) return settled
            val endedAt = latest.endedAt ?: latest.startedAt
            if (now - endedAt <= MERGE_GAP_MS) {
                val totals = if (counts(latest)) settled.totals - latest else settled.totals
                val reopened = latest.copy(endedAt = null, lastSeenAt = now)
                return State(listOf(reopened) + settled.rides.drop(1), totals)
            }
        }
        val fresh = Ride(startedAt = now, endedAt = null, distanceKm = 0.0, lastSeenAt = now)
        return State(listOf(fresh) + settled.rides, settled.totals)
    }

    /** The link went down: close the open ride, counting it if long enough. */
    fun onDisconnect(s: State, now: Long): State {
        val open = s.rides.firstOrNull()?.takeIf { it.inProgress } ?: return s
        val closed = open.copy(endedAt = now, lastSeenAt = now)
        val totals = if (counts(closed)) s.totals + closed else s.totals
        return State(listOf(closed) + s.rides.drop(1), totals)
    }

    /**
     * One telemetry sample on the open ride. Distance is the odometer against
     * the ride's own first reading, so a rejoined ride includes what was
     * ridden while disconnected; it never goes down.
     */
    fun onSample(ride: Ride, odometerKm: Double?, speedKmh: Int?, now: Long): Ride {
        val start = ride.startOdometerKm ?: odometerKm
        val travelled = if (odometerKm != null && start != null) odometerKm - start else null
        val distance = if (travelled != null && travelled > ride.distanceKm) travelled else ride.distanceKm
        return ride.copy(startOdometerKm = start, lastSeenAt = now).withSample(distance, speedKmh)
    }
}
