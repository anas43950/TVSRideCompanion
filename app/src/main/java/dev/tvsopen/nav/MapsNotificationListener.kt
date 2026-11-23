package dev.tvsopen.nav

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.tvsopen.log.Log
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.session.ClusterAlert
import dev.tvsopen.session.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One turn-by-turn instruction, normalised across map providers.
 */
data class NavInstruction(
    /** "Turn left", "Roundabout, exit 2", … — the maneuver. */
    val title: String,
    /** "Onto NH44" — the street. */
    val street: String,
    /** Metres to the maneuver; -1 when unknown. */
    val distanceMeters: Int,
    val etaMinutes: Int = 0,
    /** Resolved cluster pictogram id, or null when unresolved. */
    val pictogram: Int?,
    val provider: String,
) {
    /**
     * Best single line for this cluster's display.
     *
     * The maneuver first, the street second. The provider that is actually in
     * use here (`com.tvsm.connect`) posts the maneuver as the title and trip
     * metrics (`"2.32 km • 3 min • 11:40 AM ETA"`) as the text — preferring the
     * street therefore showed an ETA fragment where the turn instruction
     * belongs, on both lines at once. The Google Maps shape is the mirror:
     * its title is distance-only (`"550 m"`), so [NavParsing] leaves the
     * maneuver empty and this falls through to the street, which is the road.
     *
     * The raw instruction is compressed to short codes ([NavShortCodes]) so a
     * 10/12-character row fits far more, prefixed with an ASCII arrow for
     * glanceability, and rolled ([RollingText]) when it still overflows so the
     * tail scrolls into view instead of being clipped. [tick] advances once
     * per bridge send; pass 0 for a static snapshot.
     *
     * The 17-byte payload is still sent; the bytes beyond the row are
     * zero-padded and the `0xFF` terminator stays at index 19, because that is
     * the frame shape the cluster expects. Only the character count changed.
     */
    fun displayLine(maxChars: Int = RowChars.NTORQ, tick: Int = 0): String {
        val combined = listOf(title, street)
            .firstOrNull { it.isNotBlank() } ?: "Navigating"
        val compressed = NavShortCodes.compress(combined)
        val arrow = NavShortCodes.asciiArrow(pictogram)
        val full = if (arrow.isNotEmpty()) "$arrow $compressed" else compressed
        return RollingText.window(full, maxChars, tick)
    }

    /**
     * The ETA / distance line, in TVS's own rendering.
     *
     * `10Km 700m` for multi-kilometre distances (as the manual prints it),
     * plain metres below that. Truncated to the row: without the take, a
     * `"ETA 3m 2Km 320m"` line overran the 10-character maneuver row and the
     * cluster clipped the distance off the end. Rolls like [displayLine] when
     * it still overflows.
     */
    fun metricsLine(maxChars: Int = RowChars.NTORQ_LINE_1, tick: Int = 0): String {
        val d = if (distanceMeters >= 1000)
            "${distanceMeters / 1000}Km ${distanceMeters % 1000}m"
        else "${distanceMeters}m"
        val raw = if (etaMinutes > 0) "ETA ${etaMinutes}m $d" else d
        return RollingText.window(NavShortCodes.compress(raw), maxChars, tick)
    }
}

/**
 * Reads turn-by-turn out of *any* maps app.
 *
 * This is the same approach as the official app
 * (`com.tvs.ble.feature.NotificationListenerService`): the cluster has no idea
 * what a maps app is, so we take instructions from whatever app is foreground
 * and forward them over BLE. No map SDK, no API key, no account — which is
 * what makes "use Google Maps without a TVS account" work.
 *
 * ## Recognising a navigation notification
 *
 * There is no standard flag, so we accept a notification when it
 *  - comes from a known maps package, **or**
 *  - looks like navigation (has the navigation extras we use).
 *
 * A user-configurable package allowlist comes first so a new maps app works
 * without a code change.
 */
class MapsNotificationListener : NotificationListenerService() {

    private val _instruction = MutableStateFlow<NavInstruction?>(null)
    val instruction: StateFlow<NavInstruction?> = _instruction.asStateFlow()

    /** True when a maps app is currently navigating. */
    private val _navigating = MutableStateFlow(false)
    val navigating: StateFlow<Boolean> = _navigating.asStateFlow()

    /** Packages to trust in addition to structural detection. */
    @Volatile
    var allowedPackages: Set<String> = DEFAULT_MAPS_PACKAGES

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val pkg = n.packageName
        val extras = n.notification?.extras ?: return
        if (pkg.isBlank()) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val ongoing = n.isOngoing

        if (NavParsing.isNavigation(pkg, ongoing, allowedPackages)) {
            postNav(pkg, NavParsing.parse(title, text, bigText, subText))
            return
        }

        val prefs = RidePrefs.load(this)
        val category = n.notification?.category
        val isCall = category == Notification.CATEGORY_CALL ||
            extras.containsKey("android.telecom.extra.INCOMING_CALL_EXTRAS") ||
            extras.containsKey("android.telecom.extra.INCOMING_CALL_ADDRESS")
        if (isCall) {
            // Phone dialer calls follow the phone-calls toggle; VoIP/app calls
            // (WhatsApp, Telegram, …) are mirrored when phone calls are enabled,
            // since the cluster shows "who is calling" regardless of transport.
            if (prefs.mirrorPhoneCalls) {
                postCall(n.key, pkg, extras, title, text)
            }
            return
        }

        val style = extractMessagingStyle(extras)
        val hasMessageStyle = style != null
        val isMessage = hasMessageStyle || category == Notification.CATEGORY_MESSAGE
        trackSms(n, isMessage, style?.messages?.size)
        // Alert only for a real SMS, using the exact gate the unread counter
        // uses (SmsUnread.isSmsMessage), so the alert and the count always
        // agree. A message from WhatsApp or any other non-SMS app lights no
        // SMS-slot alert, just as it already does not bump the marker.
        // Mirroring every messaging app here was leftover from an earlier
        // design; the counter was narrowed to SMS and this had not been.
        val defaultSms = runCatching {
            android.provider.Telephony.Sms.getDefaultSmsPackage(this)
        }.getOrNull()
        if (prefs.mirrorMessages && SmsUnread.isSmsMessage(pkg, defaultSms, ongoing, isMessage)) {
            postMessage(n.key, pkg, extras, title)
        }
    }

    private fun postNav(pkg: String, parsed: ParsedNav) {
        val step = NavInstruction(
            title = parsed.maneuver,
            street = parsed.street,
            distanceMeters = parsed.distanceMeters,
            etaMinutes = parsed.etaMinutes,
            pictogram = parsed.pictogram,
            provider = pkg,
        )
        Log.i(
            TAG,
            "nav from $pkg: '${step.title}' / '${step.street}' " +
                "d=${step.distanceMeters}m eta=${step.etaMinutes}m pic=${step.pictogram}",
        )
        Session.onHud {
            it.copy(
                active = true,
                instructionTitle = step.title,
                street = step.street,
                distanceMeters = step.distanceMeters,
                etaMinutes = step.etaMinutes,
                pictogramId = step.pictogram,
                provider = step.provider,
            )
        }
        pendingClear?.let { handler.removeCallbacks(it) }
        pendingClear = null
        _instruction.value = step
        _navigating.value = true
    }

    private fun postCall(key: String, pkg: String, extras: Bundle, title: String, text: String) {
        val number = extras.getString("android.telecom.extra.INCOMING_CALL_ADDRESS")
            ?: extras.getString("android.text")
            ?: text.takeIf { it.isNotBlank() }
            ?: ""
        val name = title.ifBlank { number }.ifBlank { "Incoming call" }
        val label = appLabel(pkg).takeUnless { it == "Phone" } ?: ""
        Log.i(TAG, "call from $pkg: '$name' '$number'")
        Session.onAlert(
            ClusterAlert(
                kind = ClusterAlert.Kind.CALL,
                title = name,
                subtitle = if (label.isNotBlank() && number.isNotBlank()) "$label · $number" else number.ifBlank { label },
                key = key,
            ),
        )
    }

    private fun postMessage(key: String, pkg: String, extras: Bundle, title: String) {
        val info = extractMessageInfo(extras, title)
        val app = appLabel(pkg)
        val display = SmsUnread.alertText(info.title, app)
        Log.i(TAG, "message from $pkg: '$display'")
        Session.onAlert(
            ClusterAlert(
                kind = ClusterAlert.Kind.MESSAGE,
                title = display,
                subtitle = app,
                key = key,
            ),
        )
    }

    private data class MessageInfo(val title: String, val count: Int)

    private fun extractMessageInfo(extras: Bundle, fallbackTitle: String): MessageInfo {
        val style = extractMessagingStyle(extras)
        if (style != null) {
            val conv = style.conversationTitle?.toString()?.trim().orEmpty()
            val messages = style.messages
            val count = messages.size
            val sender = conv.ifBlank {
                messages.lastOrNull()?.senderPerson?.name?.toString()?.trim().orEmpty()
            }
            return MessageInfo(sender.ifBlank { fallbackTitle }, count.coerceAtLeast(1))
        }
        return MessageInfo(fallbackTitle, 1)
    }

    @Suppress("DEPRECATION")
    private fun extractMessagingStyle(extras: Bundle): Notification.MessagingStyle? {
        val p = extras.getParcelable<android.os.Parcelable>("android.messagingStyle")
            ?: extras.getParcelable<android.os.Parcelable>(EXTRA_MESSAGING_STYLE_KEY)
        return p as? Notification.MessagingStyle
    }

    private fun appLabel(pkg: String): String = when (pkg) {
        "com.whatsapp" -> "WhatsApp"
        "org.telegram.messenger", "org.telegram.messenger.web" -> "Telegram"
        "com.google.android.apps.messaging" -> "Messages"
        "com.facebook.orca" -> "Messenger"
        "com.instagram.android" -> "Instagram"
        "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer" -> "Phone"
        else -> pkg.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val removed = sbn ?: return
        val key = removed.key
        // Clear a call/message alert posted from this notification.
        Session.clearAlert(key)
        if (smsCounts.remove(key) != null) publishUnreadSms()

        if (removed.packageName !in allowedPackages) return
        pendingClear?.let { handler.removeCallbacks(it) }
        pendingClear = Runnable {
            Log.i(TAG, "navigation ended (${removed.packageName})")
            Session.onHud { it.copy(active = false) }
            _navigating.value = false
            _instruction.value = null
        }.also { handler.postDelayed(it, CLEAR_GRACE_MS) }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingClear: Runnable? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "listener connected; allowed: ${allowedPackages.joinToString()}")
        // Unread SMS already in the shade when the app (re)starts still count.
        smsCounts.clear()
        runCatching { activeNotifications }.getOrNull()?.forEach { n ->
            val extras = n.notification?.extras ?: return@forEach
            val style = extractMessagingStyle(extras)
            val isMessage = style != null || n.notification?.category == Notification.CATEGORY_MESSAGE
            trackSms(n, isMessage, style?.messages?.size, publish = false)
        }
        publishUnreadSms()
    }

    /** Messages each SMS notification in the shade stands for, by notification key. */
    private val smsCounts = mutableMapOf<String, Int>()

    /**
     * Keep the unread-SMS count for the cluster's marker (the 5th byte of the
     * N112 `X` frame, confirmed on the bike). A notification that stops being
     * an SMS message, or goes away, stops counting.
     */
    private fun trackSms(n: StatusBarNotification, isMessage: Boolean, styleMessages: Int?, publish: Boolean = true) {
        val defaultSms = runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull()
        val isSummary = ((n.notification?.flags ?: 0) and Notification.FLAG_GROUP_SUMMARY) != 0
        val changed = if (SmsUnread.isSmsMessage(n.packageName, defaultSms, n.isOngoing, isMessage)) {
            smsCounts.put(n.key, SmsUnread.messagesIn(isSummary, styleMessages)) != SmsUnread.messagesIn(isSummary, styleMessages)
        } else {
            smsCounts.remove(n.key) != null
        }
        if (changed && publish) publishUnreadSms()
    }

    private fun publishUnreadSms() {
        val total = smsCounts.values.sum()
        if (Session.unreadSms.value != total) Log.i(TAG, "unread SMS -> $total")
        Session.setUnreadSms(total)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "listener disconnected — navigation mirroring unavailable")
    }

    companion object {
        private const val TAG = "MapsNavListener"

        private const val EXTRA_MESSAGING_STYLE_KEY = "android.messagingStyle"

        /**
         * How long a cancelled navigation notification is given to be replaced.
         *
         * Nine seconds was the gap actually observed, so this sits just above
         * it. Long enough to survive a refresh, short enough that genuinely
         * ending a route clears the cluster promptly.
         */
        private const val CLEAR_GRACE_MS = 12_000L

        val DEFAULT_MAPS_PACKAGES = setOf(
            "com.google.android.apps.maps",       // Google Maps
            "com.mappls.app",                     // MapMyIndia
            "com.mmi.android.mmaps",              // Mapmyne
            "org.organicmaps",                    // Organic Maps
            "com.here.app.maps",                  // HERE WeGo
            "com.waze",                           // Waze
            "com.tomtom.gotcha",                  // TomTom Go
            "com.transit.app.transitmaps",        // Transit
            "com.immobiletrack",
        )
    }
}
/**
 * How many characters each cluster text line actually shows.
 *
 * The protocol's text payload is 17 bytes and the Jupiter app fills all 17, which
 * is where the original assumption came from. TVS's Ntorq 125 manual documents
 * what this cluster renders. The rider confirms the display is asymmetric:
 * **10 characters on line 1, 12 on line 2**. The manual's line-2 examples
 * corroborate the longer limit with two visible cuts:
 *
 * - `DISTANCE REMAINING` -> `DISTANCE REM`
 * - `STAY SAME ROAD` -> `STAY SAME RD`
 *
 * Sending 17 characters means the tail is clipped on both rows. Line 1 must be
 * capped at 10; line 2 at 12. See [RowChars].
 */
object RowChars {
    /** Ntorq physical line 1, confirmed by the rider. */
    const val NTORQ_LINE_1 = 10

    /** Ntorq physical line 2, confirmed by the rider and shown in TVS's manual. */
    const val NTORQ_LINE_2 = 12

    /** Longest Ntorq row, retained for the legacy single-row path. */
    const val NTORQ = NTORQ_LINE_2

    /**
     * The Jupiter's row, kept for the Jupiter driver.
     *
     * The 17-byte payload is the frame shape both clusters accept; only the
     * usable characters differ, so this changes how much text is *sent*, never
     * how the frame is laid out.
     */
    const val JUPITER = 17
}
