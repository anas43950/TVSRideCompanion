// Outbound frames for the Apache RTR 160 4V (TVS project N112).
//
// The bytes and their order were taken from an HCI capture of the official app
// and confirmed by a manual replay in nRF Connect (2026-11-14): the bike streams
// telemetry only after notifications are on, a phone-status frame, and `57 11`.
// A wrong byte here is invisible until the bike silently sends nothing, so the
// exact bytes are pinned.
//
// Clean-room: every name and value below is invented. Never paste the rider's
// name, nickname, address or readings into this file.

package dev.tvsopen.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApacheN112FrameTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    // ---- time ----

    @Test
    fun `afternoon time is 12-hour with the PM flag`() {
        // 14:30 -> 'H', 2, 30, PM
        assertArrayEquals(bytes(0x48, 2, 30, 1), ApacheN112Driver.time(14, 30))
    }

    @Test
    fun `morning time carries the AM flag`() {
        assertArrayEquals(bytes(0x48, 11, 5, 0), ApacheN112Driver.time(11, 5))
    }

    @Test
    fun `midnight hour is 12 AM, not 0`() {
        assertArrayEquals(bytes(0x48, 12, 0, 0), ApacheN112Driver.time(0, 0))
    }

    @Test
    fun `noon hour is 12 PM`() {
        assertArrayEquals(bytes(0x48, 12, 59, 1), ApacheN112Driver.time(12, 59))
    }

    @Test
    fun `last minute of the day`() {
        assertArrayEquals(bytes(0x48, 11, 59, 1), ApacheN112Driver.time(23, 59))
    }

    // ---- phone status ----

    @Test
    fun `phone status is X then five values in order`() {
        val f = ApacheN112Driver.phoneStatus(
            PhoneStatus(signalLevel = 2, batteryLevel = 4, missedCalls = 1, unreadMessages = 3, networkType = 3),
        )
        assertArrayEquals(bytes(0x58, 2, 4, 1, 3, 3), f)
    }

    @Test
    fun `phone status clamps out-of-range values`() {
        val f = ApacheN112Driver.phoneStatus(
            PhoneStatus(signalLevel = 9, batteryLevel = -1, missedCalls = 500, unreadMessages = 0, networkType = 7),
        )
        assertArrayEquals(bytes(0x58, 4, 0, 99, 0, 3), f)
    }

    @Test
    fun `battery percent maps to the official six levels`() {
        val expected = mapOf(
            -1 to 0, 0 to 0, 9 to 0, 10 to 1, 19 to 1, 20 to 2, 39 to 2,
            40 to 3, 59 to 3, 60 to 4, 79 to 4, 80 to 5, 100 to 5,
        )
        expected.forEach { (pct, level) ->
            assertEquals("battery $pct%", level, ApacheN112Driver.batteryLevel(pct))
        }
    }

    @Test
    fun `network type maps to the official classes`() {
        assertEquals(1, ApacheN112Driver.networkClass(2)) // EDGE
        assertEquals(2, ApacheN112Driver.networkClass(3)) // UMTS
        assertEquals(2, ApacheN112Driver.networkClass(15)) // HSPA+
        assertEquals(3, ApacheN112Driver.networkClass(13)) // LTE
        assertEquals(0, ApacheN112Driver.networkClass(20)) // NR: not in the official table
        assertEquals(0, ApacheN112Driver.networkClass(0)) // unknown
    }

    // ---- names ----

    @Test
    fun `device name is V, text, dollar`() {
        assertArrayEquals(ascii("VTest Phone$"), ApacheN112Driver.deviceName("Test Phone"))
    }

    @Test
    fun `rider name is M, text, dollar`() {
        assertArrayEquals(ascii("MTest Rider$"), ApacheN112Driver.riderName("Test Rider"))
    }

    @Test
    fun `names are cut at 16 characters and keep the terminator`() {
        val f = ApacheN112Driver.riderName("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        assertArrayEquals(ascii("MABCDEFGHIJKLMNOP$"), f)
        assertEquals(18, f.size)
    }

    @Test
    fun `caller name allows 18 characters`() {
        assertArrayEquals(
            ascii("CABCDEFGHIJKLMNOPQR$"),
            ApacheN112Driver.callerName("ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
        )
    }

    @Test
    fun `sms sender is capped so the terminator survives`() {
        val f = ApacheN112Driver.smsSender("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        assertEquals('$'.code.toByte(), f.last())
        assertEquals('I'.code.toByte(), f.first())
        assertTrue(f.size <= 18)
    }

    @Test
    fun `names are sanitised to cluster-safe ASCII`() {
        // An emoji must never reach the cluster.
        val f = ApacheN112Driver.riderName("Sam 😀 Lee")
        assertTrue(f.all { it in 0x20..0x7E })
    }

    // ---- driver ----

    @Test
    fun `keepalive is 4C 5B`() {
        assertArrayEquals(bytes(0x4C, 0x5B), ApacheN112Driver().buildPing(DashState(0L)))
    }

    @Test
    fun `call over clears the caller screen with D 01`() {
        val f = ApacheN112Driver().buildCallerInfo("", null, Command.CALL_IDLE)
        assertArrayEquals(bytes(0x44, 0x01), f)
    }

    @Test
    fun `incoming call prefers the contact name over the number`() {
        val frames = ApacheN112Driver().buildCallerFrames("+910000000000", null, "Test Caller")
        assertEquals(1, frames.size)
        assertArrayEquals(ascii("CTest Caller$"), frames[0])
    }

    @Test
    fun `incoming call falls back to the number`() {
        val frames = ApacheN112Driver().buildCallerFrames("+910000000000", null, null)
        assertArrayEquals(ascii("C+910000000000$"), frames[0])
    }

    @Test
    fun `blank sms sender sends nothing`() {
        assertNull(ApacheN112Driver().buildSms("  "))
    }

    // ---- navigation (official N112sendNavigationData) ----

    @Test
    fun `navigation uses its own frames every 400 ms`() {
        val d = ApacheN112Driver()
        assertEquals(true, d.capabilities.navigationHud)
        assertEquals(400L, d.navFrameIntervalMs)
        // The SmartXonnect control/text frames are not used for this bike.
        assertNull(d.buildNavControl(NavStep(0, 0, 0, 0, "Example Road")))
    }

    @Test
    fun `a one-line turn is a single N frame`() {
        // TURN RIGHT (3) at 200 m: 200 m -> 2000 tenths = 0x07D0, unit 0, 1 line.
        val frames = ApacheN112Driver.navFrames(pictogram = 3, meters = 200)
        assertEquals(1, frames.size)
        assertArrayEquals(
            bytes(0x4E, 0x07, 0xD0, 0, 3, 1) + ascii("TURN RIGHT") + bytes(0x24),
            frames[0],
        )
    }

    @Test
    fun `a two-line turn adds an O frame`() {
        // SHARP:LEFT TURN (1) at 120 m: rounded to 100 m -> 1000 tenths = 0x03E8.
        val frames = ApacheN112Driver.navFrames(pictogram = 1, meters = 120)
        assertEquals(2, frames.size)
        assertArrayEquals(bytes(0x4E, 0x03, 0xE8, 0, 1, 2) + ascii("SHARP") + bytes(0x24), frames[0])
        assertArrayEquals(bytes(0x4F) + ascii("LEFT TURN") + bytes(0x24), frames[1])
    }

    @Test
    fun `beyond 1 km the cluster is told to continue straight, in tenths of a km`() {
        // 2.5 km, whatever the upcoming turn: 25 tenths of a km, unit 1, code 7.
        val frames = ApacheN112Driver.navFrames(pictogram = 0, meters = 2500)
        assertArrayEquals(bytes(0x4E, 0x00, 0x19, 1, 7, 2) + ascii("Continue") + bytes(0x24), frames[0])
        assertArrayEquals(bytes(0x4F) + ascii("Straight") + bytes(0x24), frames[1])
    }

    @Test
    fun `distance rounds to 50 m steps within 1 km`() {
        assertArrayEquals(bytes(0x00, 0x00, 0), ApacheN112Driver.navDistance(20))
        assertArrayEquals(bytes(0x01, 0xF4, 0), ApacheN112Driver.navDistance(60)) // 50 m
        assertArrayEquals(bytes(0x27, 0x10, 0), ApacheN112Driver.navDistance(1000)) // 1000 m
        assertArrayEquals(bytes(0x00, 0x0A, 1), ApacheN112Driver.navDistance(1001)) // 1.0 km
    }

    @Test
    fun `a suggested U-turn uses the special code`() {
        val frames = ApacheN112Driver.navFrames(pictogram = 41, meters = 300)
        assertArrayEquals(bytes(0x4E, 0, 0, 0, 0x29, 2) + ascii("Make A") + bytes(0x24), frames[0])
        assertArrayEquals(bytes(0x4F) + ascii("U Turn") + bytes(0x24), frames[1])
    }

    @Test
    fun `app pictograms fold onto N112 codes`() {
        (0..25).forEach { assertEquals(it, ApacheN112Driver.navCode(it)) }
        assertEquals(0, ApacheN112Driver.navCode(56)) // exit to the left
        assertEquals(5, ApacheN112Driver.navCode(51)) // exit half right
        assertEquals(9, ApacheN112Driver.navCode(34)) // highway exit
        assertEquals(7, ApacheN112Driver.navCode(66)) // roundabout: continue
        assertEquals(7, ApacheN112Driver.navCode(999)) // unknown: continue
    }

    @Test
    fun `every turn text fits the cluster lines`() {
        // Line 2 of the Ntorq-class display holds 12; keep both lines within it.
        ApacheN112Driver.NAV_TEXT.values.forEach { text ->
            text.split(':').forEach { line ->
                assertTrue("'$line' too long", line.trim().length <= 12)
            }
        }
    }

    // ---- fuel ----

    @Test
    fun `fuel byte maps to eight bars`() {
        assertEquals(8, ApacheN112Driver.fuelBars(9)) // full tank, as read on the bike
        assertEquals(1, ApacheN112Driver.fuelBars(2))
        assertEquals(0, ApacheN112Driver.fuelBars(1))
        assertEquals(0, ApacheN112Driver.fuelBars(0))
        assertEquals(8, ApacheN112Driver.fuelBars(40)) // clamped, never more than the gauge
        assertNull(ApacheN112Driver.fuelBars(0xFF))
    }

    @Test
    fun `low fuel is below 2, as in the official app`() {
        assertEquals(true, ApacheN112Driver.isLowFuel(1))
        assertEquals(false, ApacheN112Driver.isLowFuel(2))
        assertNull(ApacheN112Driver.isLowFuel(0xFF))
    }

    // ---- opening sequence ----

    private val status = PhoneStatus(signalLevel = 1, batteryLevel = 3)

    @Test
    fun `opening sequence matches the captured order`() {
        val seq = ApacheN112Driver().openingSequence(
            OpeningContext(
                hourOfDay = 9,
                minute = 15,
                riderName = "Test Rider",
                deviceNickname = "Test Phone",
                phoneStatus = status,
            ),
        )
        val v = ascii("VTest Phone$")
        val x = bytes(0x58, 1, 3, 0, 0, 0)
        val h = bytes(0x48, 9, 15, 0)
        val m = ascii("MTest Rider$")
        val expected = listOf(v, x, x, x, bytes(0x57, 0x11), h, h, m, h, m, bytes(0x4C, 0x5B))
        assertEquals(expected.size, seq.size)
        expected.zip(seq).forEachIndexed { i, (want, got) ->
            assertArrayEquals("frame $i", want, got)
        }
    }

    @Test
    fun `the status frame comes before the start frame`() {
        // The one ordering the bike was seen to depend on: without X before
        // 57 11 it sent nothing.
        val seq = ApacheN112Driver().openingSequence(OpeningContext(10, 0, null, null, status))
        val firstX = seq.indexOfFirst { it[0] == 0x58.toByte() }
        val start = seq.indexOfFirst { it.contentEquals(bytes(0x57, 0x11)) }
        assertTrue(firstX in 0 until start)
    }

    @Test
    fun `no rider name means no M frames and the default nickname`() {
        val seq = ApacheN112Driver().openingSequence(OpeningContext(10, 0, null, " ", status))
        assertTrue(seq.none { it[0] == 'M'.code.toByte() })
        assertArrayEquals(ascii("V${ApacheN112Driver.DEFAULT_NICKNAME}$"), seq.first())
    }
}
