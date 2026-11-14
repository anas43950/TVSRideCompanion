// Message alerts and the unread-SMS count, as the cluster shows them.
//
// The Apache N112 shows "SMS FROM" itself and the text after it, so the alert
// text is the sender only. Its `X` status frame carries an unread-message
// count in byte 5, which lights an SMS marker beside the missed-call one
// (confirmed on the bike by an nRF Connect write). The official app always
// sends 0 there; this app counts the SMS notifications still in the shade.
//
// Pure: no Android types, so the tests drive it directly.

package dev.tvsopen.nav

object SmsUnread {

    /** SMS apps recognised even when the default-SMS lookup gives nothing. */
    val SMS_PACKAGES = setOf(
        "com.google.android.apps.messaging", // Google Messages
        "com.samsung.android.messaging",     // Samsung Messages
        "com.android.mms",                   // AOSP and many OEM SMS apps
    )

    /**
     * A notification counts toward unread SMS when it is a message from the
     * phone's SMS app and can be dismissed. Ongoing ones (sending progress,
     * chat-features status) are not messages waiting to be read.
     */
    fun isSmsMessage(pkg: String, defaultSmsPackage: String?, ongoing: Boolean, isMessage: Boolean): Boolean =
        isMessage && !ongoing && (pkg == defaultSmsPackage || pkg in SMS_PACKAGES)

    /**
     * Messages one notification stands for: its conversation's messages, or 1
     * when it has no message list. A group summary repeats its children, so it
     * counts as none.
     */
    fun messagesIn(isGroupSummary: Boolean, styleMessages: Int?): Int = when {
        isGroupSummary -> 0
        styleMessages != null && styleMessages > 0 -> styleMessages
        else -> 1
    }

    /** Text after the cluster's own "SMS FROM": the sender, no app name. */
    fun alertText(sender: String, appLabel: String): String =
        sender.trim().ifBlank { appLabel.trim().ifBlank { "Message" } }
}
