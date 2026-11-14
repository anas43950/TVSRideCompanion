// The Apache's handlebar end-call button, and the reply SMS it can send.
//
// The cluster reports the button as an N112 `Y` record; 1 asks to end the
// call. The official app (N112ClusterDataReceiver.parseDisconnectCall) then
// rejects the call and, when its "auto-reply SMS" setting is on, texts the
// caller. This does the same, with the rider's choices:
// - a ringing call is rejected and the caller gets the reply; a call already
//   in progress is only ended;
// - the reply goes to contacts only, and has its own switch (default on);
// - the number comes from the call log once the rejected call lands there,
//   so no number is ever guessed from a notification.

package dev.tvsopen.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.view.KeyEvent
import android.media.AudioManager
import androidx.core.content.ContextCompat
import dev.tvsopen.log.Log
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.session.ClusterAlert
import dev.tvsopen.session.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The decisions, without Android, so the tests drive them directly. */
object CallReplyRules {

    /** The record repeats while the button is held; one press per window. */
    const val COOLDOWN_MS = 3_000L

    /** How long to wait for the rejected call to reach the call log. */
    const val LOG_WAIT_MS = 6_000L
    const val LOG_POLL_MS = 500L

    /** `CallLog.Calls.MISSED_TYPE` and `REJECTED_TYPE`, spelled out for the JVM tests. */
    const val TYPE_MISSED = 3
    const val TYPE_REJECTED = 5

    data class LoggedCall(val number: String?, val type: Int, val dateMs: Long)

    /** A `Y` value that counts as a new press. */
    fun isPress(value: Int?, now: Long, lastPressAt: Long?): Boolean =
        value == 1 && (lastPressAt == null || now - lastPressAt >= COOLDOWN_MS)

    /**
     * The number to reply to, from the newest call-log entry: a call newer
     * than the newest one logged when the button was pressed (the ringing
     * call is not in the log until it ends), that was not answered, and has a
     * number that can be texted. Private and withheld numbers have none.
     */
    fun numberToReply(newest: LoggedCall?, newestBeforePressMs: Long?): String? {
        newest ?: return null
        if (newestBeforePressMs != null && newest.dateMs <= newestBeforePressMs) return null
        if (newest.type != TYPE_MISSED && newest.type != TYPE_REJECTED) return null
        val number = newest.number?.trim().orEmpty()
        return number.takeIf { n -> n.count { it.isDigit() } >= 3 }
    }

    /** The saved template, or the default when none is saved. */
    fun replyText(saved: String?): String =
        saved?.trim()?.takeIf { it.isNotEmpty() }?.take(RidePrefs.MAX_CALL_REPLY_CHARS)
            ?: RidePrefs.DEFAULT_CALL_REPLY
}

class CallReply(context: Context) {

    private val app = context.applicationContext
    private var lastPressAt: Long? = null

    /** One `Y` value from the cluster. */
    fun onButton(value: Int?, scope: CoroutineScope, now: Long = System.currentTimeMillis()) {
        if (!CallReplyRules.isPress(value, now, lastPressAt)) return
        lastPressAt = now
        val ringing = isRinging()
        Log.i(TAG, "handlebar end-call button (ringing=$ringing)")
        // Before ending the call: the newest entry already logged, so the
        // rejected call can be told apart from an older missed one.
        val before = newestCall()?.dateMs
        if (!endCall()) Log.w(TAG, "could not end the call")
        if (!ringing) return

        val prefs = RidePrefs.load(app)
        if (!prefs.callReplyEnabled) return
        if (!granted(Manifest.permission.SEND_SMS)) {
            Log.w(TAG, "call reply skipped: SMS permission not granted")
            return
        }
        val text = CallReplyRules.replyText(prefs.callReplyText)
        scope.launch(Dispatchers.IO) {
            val number = waitForRejectedCall(before)
            when {
                number == null -> Log.w(TAG, "call reply skipped: no rejected call with a number in the call log")
                !isContact(number) -> Log.i(TAG, "call reply skipped: caller is not a contact")
                else -> send(number, text)
            }
        }
    }

    private suspend fun waitForRejectedCall(before: Long?): String? {
        var waited = 0L
        while (waited < CallReplyRules.LOG_WAIT_MS) {
            delay(CallReplyRules.LOG_POLL_MS)
            waited += CallReplyRules.LOG_POLL_MS
            CallReplyRules.numberToReply(newestCall(), before)?.let { return it }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun isRinging(): Boolean {
        val tm = app.getSystemService(TelephonyManager::class.java)
        val state = runCatching { tm?.callState }.getOrNull()
        // Without READ_PHONE_STATE (Android 12+), fall back to the call alert
        // the notification listener raised for the ringing call.
        return state?.let { it == TelephonyManager.CALL_STATE_RINGING }
            ?: (Session.alert.value?.kind == ClusterAlert.Kind.CALL)
    }

    /**
     * End the call. `TelecomManager.endCall` needs ANSWER_PHONE_CALLS (a
     * runtime permission on Android 9+); without it, the headset hook key is
     * the best a third-party app can do.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun endCall(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            val telecom = app.getSystemService(TelecomManager::class.java)
            val ended = runCatching { telecom?.endCall() }.getOrNull()
            if (ended == true) return true
        }
        val audio = app.getSystemService(AudioManager::class.java) ?: return false
        return runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))
            true
        }.getOrDefault(false)
    }

    private fun newestCall(): CallReplyRules.LoggedCall? {
        if (!granted(Manifest.permission.READ_CALL_LOG)) return null
        return runCatching {
            app.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                if (!c.moveToFirst()) null
                else CallReplyRules.LoggedCall(number = c.getString(0), type = c.getInt(1), dateMs = c.getLong(2))
            }
        }.getOrNull()
    }

    private fun isContact(number: String): Boolean {
        if (!granted(Manifest.permission.READ_CONTACTS)) return false
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            app.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
                ?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun send(number: String, text: String) {
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
        if (sms == null) {
            Log.w(TAG, "call reply skipped: no SMS service")
            return
        }
        runCatching {
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
        }.onSuccess {
            // Never the number: the log can be exported.
            Log.i(TAG, "call reply sent (${text.length} chars)")
        }.onFailure { Log.w(TAG, "call reply failed: ${it.message}") }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "CallReply"

        /** Every permission the call reply uses, for the More screen's grant button. */
        val PERMISSIONS = listOf(
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
        )
    }
}
