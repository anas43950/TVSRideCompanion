// The last fuel level, odometer and top speed the bike sent, kept across app
// restarts.
//
// Everything else on Overview is live data that only matters while riding,
// and it is held in memory only. These are worth seeing with the bike off:
// the bike sends them once on connect and then only when they change (top
// speed only on a new high), so without this a restarted app shows "—" until
// the next connection.

package dev.tvsopen.session

import android.content.Context

class LastReadings(context: Context) {

    /** What is stored. Null means never received. */
    data class Values(
        val fuelBars: Int? = null,
        val lowFuel: Boolean? = null,
        val odometerKm: Double? = null,
        val topSpeedKmh: Int? = null,
    )

    private val sp = context.applicationContext
        .getSharedPreferences("last_readings", Context.MODE_PRIVATE)

    fun load(): Values = Values(
        fuelBars = if (sp.contains(KEY_FUEL)) sp.getInt(KEY_FUEL, 0) else null,
        lowFuel = if (sp.contains(KEY_LOW)) sp.getBoolean(KEY_LOW, false) else null,
        odometerKm = sp.getString(KEY_ODO, null)?.toDoubleOrNull(),
        topSpeedKmh = if (sp.contains(KEY_TOP)) sp.getInt(KEY_TOP, 0) else null,
    )

    fun save(v: Values) {
        val e = sp.edit()
        v.fuelBars?.let { e.putInt(KEY_FUEL, it) }
        v.lowFuel?.let { e.putBoolean(KEY_LOW, it) }
        v.odometerKm?.let { e.putString(KEY_ODO, it.toString()) }
        v.topSpeedKmh?.let { e.putInt(KEY_TOP, it) }
        e.apply()
    }

    companion object {
        private const val KEY_FUEL = "fuel_bars"
        private const val KEY_LOW = "low_fuel"
        private const val KEY_ODO = "odometer_km"
        private const val KEY_TOP = "top_speed_kmh"

        /**
         * The values to store after a frame, or null when nothing changed.
         * A reading the bike has not sent (null) never overwrites a stored one.
         */
        fun update(saved: Values, t: Telemetry): Values? {
            val next = Values(
                fuelBars = t.fuelBars ?: saved.fuelBars,
                lowFuel = t.lowFuel ?: saved.lowFuel,
                odometerKm = t.odometerKm ?: saved.odometerKm,
                topSpeedKmh = t.topSpeedKmh ?: saved.topSpeedKmh,
            )
            return next.takeIf { it != saved }
        }

        /**
         * Fill in what this process has not received yet from what was stored.
         * The live-data timestamp is left alone, so a restored value never
         * reads as live, and "This connection" stays unset until the bike sends.
         */
        fun restore(cur: Telemetry, saved: Values): Telemetry = cur.copy(
            fuelBars = cur.fuelBars ?: saved.fuelBars,
            lowFuel = cur.lowFuel ?: saved.lowFuel,
            odometerKm = cur.odometerKm ?: saved.odometerKm,
            topSpeedKmh = cur.topSpeedKmh ?: saved.topSpeedKmh,
        )
    }
}
