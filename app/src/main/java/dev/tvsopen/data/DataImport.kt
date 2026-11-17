// Reading an export file back in: moving to a new phone, or restoring after a
// reinstall. Rides are merged; of the settings, the console greeting (rider
// name), call reply (on/off and its text) and the device name are restored.
// The rest of the file's settings are left alone.

package dev.tvsopen.data

import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.session.Ride
import dev.tvsopen.session.RideRules
import org.json.JSONObject

object DataImport {

    /** What an export file holds that an import uses. */
    data class Contents(
        val rides: List<Ride>,
        val totals: RideRules.Totals,
        val settings: Settings = Settings(),
    )

    /** A setting the file carries. Its value may be null, meaning the default. */
    data class Present<T>(val value: T)

    /**
     * The settings an import restores. A null field means the file does not
     * carry that setting (an export from before it was added), so the one on
     * this phone is kept.
     */
    data class Settings(
        val riderName: Present<String?>? = null,
        val callReplyEnabled: Boolean? = null,
        val callReplyText: Present<String?>? = null,
        val deviceNickname: Present<String?>? = null,
    ) {
        /** How many settings this file carries. */
        val count: Int
            get() = listOfNotNull(riderName, callReplyEnabled, callReplyText, deviceNickname).size
    }

    /**
     * How an import went: rides added, rides already on this phone, and rides
     * not usable (still open in the file, or under 50 m).
     */
    data class Result(val added: Int, val alreadyHere: Int, val unusable: Int) {
        val message: String
            get() = buildString {
                append("Added $added ${if (added == 1) "ride" else "rides"} · $alreadyHere already here")
                if (unusable > 0) append(" · $unusable unfinished or too short")
            }
    }

    /** The console greeting's limit, as the More screen enforces it. */
    private const val MAX_RIDER_NAME_CHARS = 17

    /** Not an export file, or one from a newer app version. */
    class NotAnExport(message: String) : Exception(message)

    /** Read an export file. Throws [NotAnExport] when it is not one. */
    fun parse(json: String): Contents {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: throw NotAnExport("not JSON")
        if (root.optString("format") != DataExport.FORMAT) throw NotAnExport("wrong format")
        val version = root.optInt("version", -1)
        if (version < 1 || version > DataExport.VERSION) throw NotAnExport("version $version")

        val arr = root.optJSONArray("rides")
        val rides = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
            if (!o.has("startedAt")) return@mapNotNull null
            Ride(
                startedAt = o.getLong("startedAt"),
                endedAt = if (o.isNull("endedAt")) null else o.optLong("endedAt"),
                distanceKm = o.optDouble("distanceKm", 0.0),
                topSpeedKmh = if (o.isNull("topSpeedKmh")) null else o.optInt("topSpeedKmh"),
            )
        }
        val totals = RideRules.Totals(
            root.optInt("rideTotalCount", 0),
            root.optDouble("rideTotalDistanceKm", 0.0),
        )
        return Contents(rides, totals, parseSettings(root.optJSONObject("prefs")))
    }

    private fun parseSettings(p: JSONObject?): Settings {
        if (p == null) return Settings()
        fun text(key: String): Present<String?>? =
            if (!p.has(key)) null else Present(if (p.isNull(key)) null else p.optString(key))
        return Settings(
            riderName = text("riderName"),
            callReplyEnabled = if (p.has("callReplyEnabled") && !p.isNull("callReplyEnabled")) {
                p.optBoolean("callReplyEnabled")
            } else {
                null
            },
            callReplyText = text("callReplyText"),
            deviceNickname = text("deviceNickname"),
        )
    }

    /**
     * Write the file's settings to this phone; settings the file does not
     * carry are left as they are. Returns how many were restored.
     */
    fun restore(prefs: RidePrefs, s: Settings): Int {
        s.riderName?.let { prefs.riderName = it.value?.take(MAX_RIDER_NAME_CHARS) }
        s.callReplyEnabled?.let { prefs.callReplyEnabled = it }
        s.callReplyText?.let { prefs.callReplyText = it.value }
        s.deviceNickname?.let { prefs.deviceNickname = it.value }
        return s.count
    }

    /** The line shown after an import. */
    fun message(rides: Result?, settingsRestored: Int): String = buildString {
        append(rides?.message ?: "Ride storage is not ready yet; no rides added")
        if (settingsRestored > 0) {
            append(" · $settingsRestored ${if (settingsRestored == 1) "setting" else "settings"} restored")
        }
    }

    /**
     * Add the file's rides to [state]. Pure.
     *
     * Skipped: rides already here (same start time), rides still open in the
     * file, and rides under [RideRules.MIN_RIDE_KM].
     *
     * Totals: a phone with no rides and no totals (new phone, reinstall) takes
     * the file's totals, which also cover rides older than the file's list.
     * Otherwise each added ride is added to the totals here.
     */
    fun merge(state: RideRules.State, file: Contents): Pair<RideRules.State, Result> {
        val here = state.rides.map { it.startedAt }.toHashSet()
        val usable = file.rides.filter(RideRules::counts)
        val added = usable.filter { it.startedAt !in here }.distinctBy { it.startedAt }
        val result = Result(
            added = added.size,
            alreadyHere = usable.size - added.size,
            unusable = file.rides.size - usable.size,
        )
        if (added.isEmpty()) return state to result

        val fresh = state.rides.isEmpty() && state.totals.rides == 0 && state.totals.distanceKm == 0.0
        val totals = if (fresh) {
            // Never below what the added rides themselves make up.
            val own = added.fold(RideRules.Totals(0, 0.0)) { t, r -> t + r }
            RideRules.Totals(
                maxOf(file.totals.rides, own.rides),
                maxOf(file.totals.distanceKm, own.distanceKm),
            )
        } else {
            added.fold(state.totals) { t, r -> t + r }
        }
        // The ride log expects an open ride first; everything else newest first.
        val (open, closed) = (state.rides + added).partition { it.inProgress }
        val rides = open + closed.sortedByDescending { it.startedAt }
        return RideRules.State(rides, totals) to result
    }
}
