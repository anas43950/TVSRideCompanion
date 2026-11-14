// Small persisted settings that outlive the process.
//
// Separate from ui.theme.ThemePrefs because these affect behaviour rather than
// appearance, and the ride state in particular is read by a BroadcastReceiver
// that runs with no UI and no Activity.

package dev.tvsopen.prefs

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class RidePrefs private constructor(private val ctx: Context) {

    private val sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * Connect to the saved vehicle on its own: when it is switched on, after a
     * reboot, after Bluetooth comes back, and after a dropped link.
     * On by default: connecting to the vehicle you already chose is what the
     * app is for.
     */
    var autoConnect by mutableStateOf(
        resolveAutoConnect(
            stored = if (sp.contains(KEY_AUTO_CONNECT)) sp.getBoolean(KEY_AUTO_CONNECT, true) else null,
        ),
    )
        private set

    /**
     * Named `updateX`, not `setX`: a `private set` on a delegated `var` already
     * generates a `setX(Z)V` method, so a `fun setAutoConnect(Boolean)` collides
     * with it at the JVM signature level and fails the build.
     */
    fun updateAutoConnect(on: Boolean) {
        autoConnect = on
        sp.edit().putBoolean(KEY_AUTO_CONNECT, on).apply()
    }

    /**
     * Last link the service established, for reconnect-after-reboot. Plain
     * getters, not observable state: only MainActivity reads them once at
     * startup, and SharedPreferences is the source of truth.
     */
    val lastAddress: String?
        get() = sp.getString(KEY_LAST_ADDRESS, null)

    val lastModelId: String?
        get() = sp.getString(KEY_LAST_MODEL, null)

    fun updateLastConnection(address: String, modelId: String) {
        sp.edit()
            .putString(KEY_LAST_ADDRESS, address)
            .putString(KEY_LAST_MODEL, modelId)
            .apply()
    }

    /** Explicit user's selection from the scan row; survives later rediscovery. */
    fun updateSelectedModel(address: String, modelId: String) {
        sp.edit().putString(modelKey(address), modelId).apply()
    }

    fun selectedModel(address: String): String? = sp.getString(modelKey(address), null)

    /**
     * The Bluetooth layout ([dev.tvsopen.protocol.GattProfile.id]) last
     * detected on this vehicle, so the next connection starts with the right
     * driver instead of discovering it and reconnecting.
     */
    fun detectedProfile(address: String): String? = sp.getString(profileKey(address), null)

    fun updateDetectedProfile(address: String, profileId: String) {
        sp.edit().putString(profileKey(address), profileId).apply()
    }

    /**
     * Whether the rider has already been shown the companion-device dialog for
     * this vehicle, so declining it is not answered with the same dialog on
     * every launch. Turning auto-connect on asks again regardless.
     */
    fun companionAsked(address: String): Boolean = sp.getBoolean(companionAskedKey(address), false)

    fun markCompanionAsked(address: String) {
        sp.edit().putBoolean(companionAskedKey(address), true).apply()
    }

    /**
     * The vehicle Companion Device Manager is currently watching for, if any.
     *
     * While set, the ride service can stop after a drop instead of waiting in
     * the background with a notification up: the system wakes the app when the
     * vehicle reappears.
     */
    var observedCompanion: String?
        get() = sp.getString(KEY_OBSERVED_COMPANION, null)
        set(value) {
            sp.edit().apply {
                if (value == null) remove(KEY_OBSERVED_COMPANION) else putString(KEY_OBSERVED_COMPANION, value)
            }.apply()
        }

    /**
     * Name of this phone as the cluster shows it: the Apache N112's `V` frame
     * reads "Connected to <name>".
     */
    var deviceNickname: String?
        get() = sp.getString(KEY_DEVICE_NICKNAME, null)
        set(value) {
            val trimmed = value?.trim()?.take(MAX_NICKNAME_CHARS)?.takeIf { it.isNotEmpty() }
            sp.edit().apply {
                if (trimmed == null) remove(KEY_DEVICE_NICKNAME) else putString(KEY_DEVICE_NICKNAME, trimmed)
            }.apply()
        }

    /** Scan preference; default is unfiltered because the Ntorq advertises no UUID. */
    var onlySmartXonnect: Boolean
        get() = sp.getBoolean(KEY_ONLY_SMART_XONNECT, false)
        set(value) { sp.edit().putBoolean(KEY_ONLY_SMART_XONNECT, value).apply() }

    /** Navigation mirroring is the product default; preserve an explicit opt-out. */
    var navigationMirroring: Boolean
        get() = sp.getBoolean(KEY_NAVIGATION_MIRRORING, true)
        set(value) { sp.edit().putBoolean(KEY_NAVIGATION_MIRRORING, value).apply() }

    /** Mirror incoming phone calls to the cluster (caller name/number). */
    var mirrorPhoneCalls: Boolean
        get() = sp.getBoolean(KEY_MIRROR_PHONE_CALLS, true)
        set(value) { sp.edit().putBoolean(KEY_MIRROR_PHONE_CALLS, value).apply() }

    /** Mirror message/app notifications (sender and count, never the message text). */
    var mirrorMessages: Boolean
        get() = sp.getBoolean(KEY_MIRROR_MESSAGES, false)
        set(value) { sp.edit().putBoolean(KEY_MIRROR_MESSAGES, value).apply() }

    /**
     * Text a caller gets when the handlebar button rejects their call. On by
     * default; contacts only (see CallReply).
     */
    var callReplyEnabled: Boolean
        get() = sp.getBoolean(KEY_CALL_REPLY_ENABLED, true)
        set(value) { sp.edit().putBoolean(KEY_CALL_REPLY_ENABLED, value).apply() }

    /** The reply text; null (the default) means [DEFAULT_CALL_REPLY]. */
    var callReplyText: String?
        get() = sp.getString(KEY_CALL_REPLY_TEXT, null)
        set(value) {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
            sp.edit().apply {
                if (trimmed == null) remove(KEY_CALL_REPLY_TEXT) else putString(KEY_CALL_REPLY_TEXT, trimmed)
            }.apply()
        }

    /** Local profile name; UI can edit it without sending an unverified packet. */
    var riderName: String?
        get() = sp.getString(KEY_RIDER_NAME, null)
        set(value) {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
            sp.edit().apply {
                if (trimmed == null) remove(KEY_RIDER_NAME) else putString(KEY_RIDER_NAME, trimmed)
            }.apply()
        }

    companion object {
        private const val NAME = "tvsride"
        private const val KEY_LAST_ADDRESS = "last_address"
        private const val KEY_LAST_MODEL = "last_model"
        private const val KEY_RIDER_NAME = "rider_name"
        private const val KEY_ONLY_SMART_XONNECT = "only_smart_xonnect"
        private const val KEY_NAVIGATION_MIRRORING = "navigation_mirroring"
        private const val KEY_MIRROR_PHONE_CALLS = "mirror_phone_calls"
        private const val KEY_MIRROR_MESSAGES = "mirror_messages"
        private const val KEY_CALL_REPLY_ENABLED = "call_reply_enabled"
        private const val KEY_CALL_REPLY_TEXT = "call_reply_text"
        const val DEFAULT_CALL_REPLY = "Riding the bike currently, call again if it's urgent."
        const val MAX_CALL_REPLY_CHARS = 160
        private const val KEY_SELECTED_MODEL_PREFIX = "selected_model_"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_DEVICE_NICKNAME = "device_nickname"
        private const val KEY_PROFILE_PREFIX = "gatt_profile_"
        private const val KEY_COMPANION_ASKED_PREFIX = "companion_asked_"
        private const val KEY_OBSERVED_COMPANION = "observed_companion"

        private fun companionAskedKey(address: String) = "$KEY_COMPANION_ASKED_PREFIX$address"

        /** The Apache's `V` frame carries at most 16 characters. */
        const val MAX_NICKNAME_CHARS = 16

        private fun modelKey(address: String) = "$KEY_SELECTED_MODEL_PREFIX$address"
        private fun profileKey(address: String) = "$KEY_PROFILE_PREFIX$address"

        /** Auto-connect's effective value: an explicit choice, else on. */
        fun resolveAutoConnect(stored: Boolean?): Boolean = stored ?: true

        @Volatile
        private var instance: RidePrefs? = null

        fun load(context: Context): RidePrefs =
            instance ?: synchronized(this) {
                instance ?: RidePrefs(context.applicationContext)
                    .also { instance = it }
            }
    }
}
