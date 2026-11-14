// Ride history, computed on the phone.
//
// The official app's "My Rides" list, Total Rides and Last Ride all come from
// TVS's servers, tied to an account. This app has neither, so the same numbers
// are derived locally from what the cluster already sends: a ride is one or
// more connected periods (reconnects within 5 min join, see RideRules), its
// distance is the odometer delta from its first reading, its duration is wall
// clock, its top speed the highest live speed seen while connected.
//
// The consequence is deliberate and stated in the UI: a ride recorded here
// exists only on this phone and is lost on uninstall. That is the price of
// having no account, and it is a fair trade for not needing one.

package dev.tvsopen.session

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

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
 * Bounded local ride log.
 *
 * Keeps [MAX_RIDES] recent rides plus two running totals that survive the
 * window. Plain JSON in SharedPreferences, readable over `adb` when something
 * looks wrong. What counts as a ride, and when two connections are one ride,
 * is decided by [RideRules]; this class only stores the result.
 */
class RideLog(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("tvsride", Context.MODE_PRIVATE)

    private var state: RideRules.State
    private var lastSavedAt = 0L

    private val _rides = MutableStateFlow<List<Ride>>(emptyList())

    /** Most recent first; only what [RideRules.visible] lets through. */
    val rides: StateFlow<List<Ride>> = _rides.asStateFlow()

    /** Every counted ride ever, including ones the bounded window has dropped. */
    val totalRides: Int get() = state.totals.rides

    /** Running total across all counted rides, not just the retained window. */
    val totalDistanceKm: Double get() = state.totals.distanceKm

    init {
        val now = System.currentTimeMillis()
        val s = RideRules.State(
            load(),
            RideRules.Totals(
                sp.getInt(KEY_TOTAL, 0),
                sp.getString(KEY_DIST, null)?.toDoubleOrNull() ?: 0.0,
            ),
        )
        state = s
        commit(RideRules.settle(s, now))
    }

    private fun load(): List<Ride> = runCatching {
        val arr = JSONArray(sp.getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Ride(
                startedAt = o.optLong("s"),
                endedAt = if (o.isNull("e")) null else o.optLong("e"),
                distanceKm = o.optDouble("d", 0.0),
                topSpeedKmh = if (o.has("t") && !o.isNull("t")) o.optInt("t") else null,
                startOdometerKm = if (o.has("o") && !o.isNull("o")) o.optDouble("o") else null,
                lastSeenAt = if (o.has("l") && !o.isNull("l")) o.optLong("l") else null,
            )
        }
    }.getOrDefault(emptyList())

    /** Store [s] and publish it. */
    private fun commit(s: RideRules.State) {
        state = s
        val arr = JSONArray()
        s.rides.take(MAX_RIDES).forEach { r ->
            arr.put(
                JSONObject()
                    .put("s", r.startedAt)
                    .put("e", r.endedAt ?: JSONObject.NULL)
                    .put("d", r.distanceKm)
                    .put("t", r.topSpeedKmh ?: JSONObject.NULL)
                    .put("o", r.startOdometerKm ?: JSONObject.NULL)
                    .put("l", r.lastSeenAt ?: JSONObject.NULL),
            )
        }
        sp.edit()
            .putString(KEY, arr.toString())
            .putInt(KEY_TOTAL, s.totals.rides)
            .putString(KEY_DIST, s.totals.distanceKm.toString())
            .apply()
        lastSavedAt = System.currentTimeMillis()
        publish()
    }

    private fun publish() {
        _rides.value = RideRules.visible(state.rides)
    }

    /** The link came up: continue, rejoin or start a ride. */
    fun start(now: Long = System.currentTimeMillis()) {
        commit(RideRules.onConnect(state, now))
    }

    /**
     * Records progress on the open ride.
     *
     * Saved at most every [RideRules.SAVE_EVERY_MS], not on every frame: often
     * enough that a killed app loses half a minute at most, rarely enough that
     * the log is never a disk write at telemetry rate.
     */
    fun update(odometerKm: Double?, speedKmh: Int?, now: Long = System.currentTimeMillis()) {
        val open = state.rides.firstOrNull()?.takeIf { it.inProgress } ?: return
        val next = state.copy(rides = listOf(RideRules.onSample(open, odometerKm, speedKmh, now)) + state.rides.drop(1))
        if (now - lastSavedAt >= RideRules.SAVE_EVERY_MS) {
            commit(next)
        } else {
            state = next
            publish()
        }
    }

    /** The link went down: close the open ride, counting it if long enough. */
    fun finish(now: Long = System.currentTimeMillis()) {
        commit(RideRules.onDisconnect(state, now))
    }

    companion object {
        private const val KEY = "ride_log"
        private const val KEY_TOTAL = "ride_total_count"
        private const val KEY_DIST = "ride_total_distance"

        private const val MAX_RIDES = 100
    }
}
