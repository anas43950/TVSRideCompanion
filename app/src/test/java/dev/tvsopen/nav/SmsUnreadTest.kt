// The cluster's SMS alert text and its unread-SMS marker count.
// Invented names only.

package dev.tvsopen.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsUnreadTest {

    private val samsung = "com.samsung.android.messaging"

    @Test
    fun `alert text is the sender with no app name`() {
        // The cluster showed "SMS FROM <Contact> MESSAGING" when the app name was appended.
        assertEquals("Test Contact", SmsUnread.alertText("Test Contact", "Messaging"))
        assertEquals("Test Contact", SmsUnread.alertText("  Test Contact ", "WhatsApp"))
    }

    @Test
    fun `alert text falls back to the app, then to Message`() {
        assertEquals("Messaging", SmsUnread.alertText("", "Messaging"))
        assertEquals("Message", SmsUnread.alertText(" ", ""))
    }

    @Test
    fun `a message from the default SMS app counts`() {
        assertTrue(SmsUnread.isSmsMessage("org.example.sms", "org.example.sms", ongoing = false, isMessage = true))
        assertTrue(SmsUnread.isSmsMessage(samsung, null, ongoing = false, isMessage = true))
    }

    @Test
    fun `other apps, ongoing and non-message notifications do not count`() {
        assertFalse(SmsUnread.isSmsMessage("com.whatsapp", samsung, ongoing = false, isMessage = true))
        assertFalse(SmsUnread.isSmsMessage(samsung, samsung, ongoing = true, isMessage = true))
        assertFalse(SmsUnread.isSmsMessage(samsung, samsung, ongoing = false, isMessage = false))
    }

    @Test
    fun `a conversation counts its messages and a group summary counts none`() {
        assertEquals(3, SmsUnread.messagesIn(isGroupSummary = false, styleMessages = 3))
        assertEquals(1, SmsUnread.messagesIn(isGroupSummary = false, styleMessages = null))
        assertEquals(1, SmsUnread.messagesIn(isGroupSummary = false, styleMessages = 0))
        assertEquals(0, SmsUnread.messagesIn(isGroupSummary = true, styleMessages = 4))
    }
}
