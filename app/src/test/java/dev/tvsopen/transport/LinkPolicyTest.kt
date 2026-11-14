// Opening the app while auto-connect had already linked the vehicle used to
// tear that link down and rebuild it. These pin when a connect request leaves
// the current link alone. Invented addresses.

package dev.tvsopen.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPolicyTest {

    private val bike = "00:11:22:33:44:55"
    private val other = "66:77:88:99:AA:BB"

    @Test
    fun `a live link to the same vehicle is kept`() {
        assertTrue(shouldReuseLink(bike, bike, ConnectionState.CONNECTED))
        assertTrue(shouldReuseLink(bike, bike, ConnectionState.CONNECTING))
    }

    @Test
    fun `address case does not matter`() {
        assertTrue(shouldReuseLink(bike.lowercase(), bike, ConnectionState.CONNECTED))
    }

    @Test
    fun `a different vehicle replaces the link`() {
        assertFalse(shouldReuseLink(bike, other, ConnectionState.CONNECTED))
    }

    @Test
    fun `a down or waiting link is (re)connected`() {
        assertFalse(shouldReuseLink(bike, bike, ConnectionState.DISCONNECTED))
        // A background connect still waiting: a direct connect should replace it.
        assertFalse(shouldReuseLink(bike, bike, ConnectionState.RECONNECTING))
        assertFalse(shouldReuseLink(null, bike, null))
    }
}
