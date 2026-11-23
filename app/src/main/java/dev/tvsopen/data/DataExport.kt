// On-device data export.
//
// The app keeps everything locally (rides, settings, parked spot) and has no
// account or cloud. Exporting produces a single JSON file the rider can keep or
// move to another device, where DataImport reads it back. Nothing is uploaded
// anywhere; the file only leaves the phone if the rider shares it.

package dev.tvsopen.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.session.Ride
import dev.tvsopen.session.RideRules
import dev.tvsopen.session.Session
import dev.tvsopen.ui.theme.ThemePrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DataExport {

    const val FORMAT = "tvsride-export"
    const val VERSION = 1

    /**
     * The FileProvider authority, derived from the running package so it tracks
     * the variant. The manifest declares it as `${applicationId}.export`, so the
     * release build is `dev.tvsopen.export` and the debug build
     * `dev.tvsopen.debug.export`; a hardcoded constant would be wrong in one of
     * them. `context.packageName` is the applicationId at runtime, suffix and
     * all. See the manifest provider and `res/xml/export_paths.xml`.
     */
    private fun authority(context: Context) = "${context.packageName}.export"

    /** The settings that go into an export. */
    data class Settings(
        val riderName: String?,
        val navigationMirroring: Boolean,
        val mirrorPhoneCalls: Boolean,
        val mirrorMessages: Boolean,
        val theme: String,
        val callReplyEnabled: Boolean,
        /** Null means the default reply. */
        val callReplyText: String?,
        /** Null means the default name. */
        val deviceNickname: String?,
        /** The handlebar button's action, as [dev.tvsopen.prefs.HandlebarCallAction.name]. */
        val handlebarCallAction: String,
    )

    /** The export document. Pure, so the tests can read what it writes. */
    fun toJson(
        rides: List<Ride>,
        totals: RideRules.Totals,
        settings: Settings,
        exportedAt: Long,
    ): String {
        val ridesArr = JSONArray()
        rides.forEach { r ->
            ridesArr.put(
                JSONObject()
                    .put("startedAt", r.startedAt)
                    .put("distanceKm", r.distanceKm)
                    .put("topSpeedKmh", r.topSpeedKmh ?: JSONObject.NULL)
                    .put("endedAt", r.endedAt ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", exportedAt)
            .put("rides", ridesArr)
            .put("rideTotalCount", totals.rides)
            .put("rideTotalDistanceKm", totals.distanceKm)
            .put(
                "prefs",
                JSONObject()
                    .put("riderName", settings.riderName ?: JSONObject.NULL)
                    .put("navigationMirroring", settings.navigationMirroring)
                    .put("mirrorPhoneCalls", settings.mirrorPhoneCalls)
                    .put("mirrorMessages", settings.mirrorMessages)
                    .put("theme", settings.theme)
                    .put("callReplyEnabled", settings.callReplyEnabled)
                    .put("callReplyText", settings.callReplyText ?: JSONObject.NULL)
                    .put("deviceNickname", settings.deviceNickname ?: JSONObject.NULL)
                    .put("handlebarCallAction", settings.handlebarCallAction),
            )
            .toString(2)
    }

    /** Build the export document from what the app holds now. */
    fun buildJson(context: Context): String {
        val prefs = RidePrefs.load(context)
        return toJson(
            rides = Session.rides.value,
            totals = RideRules.Totals(Session.totalRides.value, Session.totalRideDistanceKm.value),
            settings = Settings(
                riderName = prefs.riderName,
                navigationMirroring = prefs.navigationMirroring,
                mirrorPhoneCalls = prefs.mirrorPhoneCalls,
                mirrorMessages = prefs.mirrorMessages,
                theme = ThemePrefs.mode.name,
                callReplyEnabled = prefs.callReplyEnabled,
                callReplyText = prefs.callReplyText,
                deviceNickname = prefs.deviceNickname,
                handlebarCallAction = prefs.handlebarCallAction.name,
            ),
            exportedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Write the export to a file in the app's cache, replacing earlier
     * exports so they do not pile up, and return a `content://` link other
     * apps can read once the share intent grants it. Call off the main thread.
     */
    fun writeFile(context: Context): Uri {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "tvsride-export-$stamp.json")
        file.writeText(buildJson(context))
        return FileProvider.getUriForFile(context, authority(context), file)
    }
}
