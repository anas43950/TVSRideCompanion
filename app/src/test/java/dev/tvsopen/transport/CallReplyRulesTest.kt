// The handlebar end-call button: when a `Y` value is a press, and which
// call-log entry (if any) gets the reply SMS. Invented numbers only.

package dev.tvsopen.transport

import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.transport.CallReplyRules.LoggedCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallReplyRulesTest {

    private val t0 = 1_000_000L
    private val number = "+910000000000"

    @Test
    fun `Y 01 is a press, Y 00 is not`() {
        assertTrue(CallReplyRules.isPress(1, t0, null))
        assertFalse(CallReplyRules.isPress(0, t0, null))
        assertFalse(CallReplyRules.isPress(null, t0, null))
    }

    @Test
    fun `a held button is one press until the cooldown passes`() {
        assertFalse(CallReplyRules.isPress(1, t0 + 500, lastPressAt = t0))
        assertTrue(CallReplyRules.isPress(1, t0 + CallReplyRules.COOLDOWN_MS, lastPressAt = t0))
    }

    @Test
    fun `a rejected or missed call newer than the press gets the reply`() {
        val before = t0
        assertEquals(number, CallReplyRules.numberToReply(LoggedCall(number, CallReplyRules.TYPE_REJECTED, t0 + 1), before))
        assertEquals(number, CallReplyRules.numberToReply(LoggedCall(number, CallReplyRules.TYPE_MISSED, t0 + 1), before))
    }

    @Test
    fun `an older call already in the log gets nothing`() {
        // The rejected call has not reached the log yet; the newest entry is an old missed call.
        assertNull(CallReplyRules.numberToReply(LoggedCall(number, CallReplyRules.TYPE_MISSED, t0), t0))
    }

    @Test
    fun `an answered call gets nothing`() {
        val incoming = 1 // CallLog.Calls.INCOMING_TYPE
        assertNull(CallReplyRules.numberToReply(LoggedCall(number, incoming, t0 + 1), t0))
    }

    @Test
    fun `withheld numbers get nothing`() {
        assertNull(CallReplyRules.numberToReply(LoggedCall("", CallReplyRules.TYPE_REJECTED, t0 + 1), t0))
        assertNull(CallReplyRules.numberToReply(LoggedCall("-2", CallReplyRules.TYPE_REJECTED, t0 + 1), t0))
        assertNull(CallReplyRules.numberToReply(LoggedCall(null, CallReplyRules.TYPE_REJECTED, t0 + 1), t0))
    }

    @Test
    fun `an empty log before the press still finds the rejected call`() {
        assertEquals(number, CallReplyRules.numberToReply(LoggedCall(number, CallReplyRules.TYPE_REJECTED, t0), null))
    }

    @Test
    fun `reply text defaults when nothing is saved`() {
        assertEquals("Riding the bike currently, call again if it's urgent.", RidePrefs.DEFAULT_CALL_REPLY)
        assertEquals(RidePrefs.DEFAULT_CALL_REPLY, CallReplyRules.replyText(null))
        assertEquals(RidePrefs.DEFAULT_CALL_REPLY, CallReplyRules.replyText("   "))
        assertEquals("On the road", CallReplyRules.replyText(" On the road "))
    }
}
