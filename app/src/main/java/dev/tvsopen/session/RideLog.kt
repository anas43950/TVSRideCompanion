// Ride history, computed on the phone.
//
// The official app's "My Rides" list, Total Rides and Last Ride all come from
// TVS's servers, tied to an account. This app has neither, so the same numbers
// are derived locally from what the cluster already sends: a ride is one or
// more connected periods (reconnects within 5 min join, see RideRules), its
// distance is the odometer delta from its first reading, its duration is wall
// clock, its top speed the highest live speed seen while connected.
//
// The consequence is deliberate: a ride recorded here exists only on this
// phone and is lost on uninstall unless exported first (More → Your data).
// That is the price of having no account, and a fair trade for not needing one.

package dev.tvsopen.session

import android.content.Context
import dev.tvsopen.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One ride, open or closed. */
data class Ride(
    val startedAt: Long,
    val endedAt: Long?,
    val distanceKm: Double,
    /** Highest live speed seen during the ride; null until there is a speed reading. */
    val topSpeedKmh: Int? = null,
    /** Odometer at the ride's first reading; distance is measured from it. */
    val startOdometerKm: Double? = null,
    /** Last telemetry sample, so a ride left open by a killed app can be closed at it. */
    val lastSeenAt: Long? = null,
) {
    val inProgress: Boolean get() = endedAt == null

    fun durationMinutes(now: Long = System.currentTimeMillis()): Long {
        val end = endedAt ?: now
        return ((end - startedAt).coerceAtLeast(0L)) / 60_000L
    }

    /**
     * Average speed over the whole ride, stops included: distance ÷ duration.
     * Null until there is both distance and at least a minute of ride.
     */
    fun avgSpeedKmh(now: Long = System.currentTimeMillis()): Double? {
        val end = endedAt ?: now
        val hours = (end - startedAt).coerceAtLeast(0L) / 3_600_000.0
        if (distanceKm <= 0.0 || hours < 1.0 / 60) return null
        return distanceKm / hours
    }

    /** Fold one telemetry sample into this ride. Negative distance is ignored. */
    fun withSample(distanceKm: Double, speedKmh: Int?): Ride = copy(
        distanceKm = if (distanceKm >= 0) distanceKm else this.distanceKm,
        topSpeedKmh = when {
            speedKmh == null || speedKmh < 0 -> topSpeedKmh
            topSpeedKmh == null -> speedKmh
            else -> maxOf(topSpeedKmh, speedKmh)
        },
    )
}

/**
 * Local ride log: every ride, plus two running totals.
 *
 * The rides are held in memory and stored in [RideDatabase]; each save writes
 * only the rows that changed, normally just the open ride. What counts as a
 * ride, and when two connections are one ride, is decided by [RideRules]; this
 * class only applies and stores the result.
 *
 * All work runs in order on one background thread, because Room refuses the
 * main thread: [start], [update], [finish] and [edit] queue behind the initial
 * load, so a connect that arrives during startup still sees every ride.
 * Changes are reported through [onChange], from that thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RideLog(
    context: Context,
    private val onChange: (rides: List<Ride>, totals: RideRules.Totals) -> Unit,
) {

    private val appContext = context.applicationContext

    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + worker)

    private var state = RideRules.State(emptyList(), RideRules.Totals(0, 0.0))
    private var lastSavedAt = 0L

    /** Database access; null if the database could not be opened. */
    private var dao: RideDao? = null

    /** The rides as the database holds them, so a save writes only the difference. */
    private var written: List<Ride> = emptyList()

    init {
        scope.launch {
            val loaded = runCatching {
                val d = RideDatabase.get(appContext).rides()
                val s = RideRules.State(
                    d.all().map(RideRow::toRide),
                    d.totals()?.toTotals() ?: RideRules.Totals(0, 0.0),
                )
                dao = d
                s
            }.getOrElse { e ->
                // Rides still track in memory; they just are not saved this launch.
                Log.e(TAG, "Ride database unavailable; rides will not be saved", e)
                state
            }
            state = loaded
            written = loaded.rides
            commit(RideRules.settle(loaded, System.currentTimeMillis()))
        }
    }

    /** Store [s] and publish it. */
    private fun commit(s: RideRules.State) {
        state = s
        val d = dao
        if (d != null) {
            val changes = RideChanges.diff(written, s.rides)
            runCatching { d.apply(changes, s.totals) }
                .onSuccess { written = s.rides }
                // `written` stays as it was, so the next save retries these rows.
                .onFailure { Log.e(TAG, "Saving rides failed", it) }
        }
        lastSavedAt = System.currentTimeMillis()
        onChange(RideRules.visible(s.rides), s.totals)
    }

    /** The link came up: continue, rejoin or start a ride. */
    fun start(now: Long = System.currentTimeMillis()) {
        scope.launch { commit(RideRules.onConnect(state, now)) }
    }

    /**
     * Records progress on the open ride.
     *
     * Saved at most every [RideRules.SAVE_EVERY_MS], not on every frame: often
     * enough that a killed app loses half a minute at most, rarely enough that
     * the log is never a disk write at telemetry rate.
     */
    fun update(odometerKm: Double?, speedKmh: Int?, now: Long = System.currentTimeMillis()) {
        scope.launch {
            val open = state.rides.firstOrNull()?.takeIf { it.inProgress } ?: return@launch
            val next = state.copy(rides = listOf(RideRules.onSample(open, odometerKm, speedKmh, now)) + state.rides.drop(1))
            if (now - lastSavedAt >= RideRules.SAVE_EVERY_MS) {
                commit(next)
            } else {
                state = next
                onChange(RideRules.visible(next.rides), next.totals)
            }
        }
    }

    /** The link went down: close the open ride, counting it if long enough. */
    fun finish(now: Long = System.currentTimeMillis()) {
        scope.launch { commit(RideRules.onDisconnect(state, now)) }
    }

    /**
     * Change the rides in one step, such as an import: [block] gets the
     * current rides and totals and returns the new ones plus a result.
     */
    suspend fun <T> edit(block: (RideRules.State) -> Pair<RideRules.State, T>): T = withContext(worker) {
        val (next, result) = block(state)
        commit(next)
        result
    }

    companion object {
        private const val TAG = "RideLog"
    }
}
