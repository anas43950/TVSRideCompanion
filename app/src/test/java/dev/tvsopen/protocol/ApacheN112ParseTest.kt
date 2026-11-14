// Inbound records from the Apache RTR 160 4V (TVS project N112).
//
// One notification is a run of `tag + fixed-length payload` records, e.g. a
// heartbeat-only `V 01`, or `A spd unit R hi lo` packed together. Lengths and
// scaling come from the official `N112ClusterDataReceiver`. The failure this
// guards against is re-anchoring mid-record: one wrong length turns every later
// byte into a bogus tag, which reads like plausible telemetry.
//
// Clean-room: every value below is invented, not a reading from the rider's bike.

package dev.tvsopen.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ApacheN112ParseTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val driver = ApacheN112Driver()

    @Test
    fun `a notification splits into its records`() {
        val records = ApacheN112Driver.parseRecords(bytes('A'.code, 42, 0, 'R'.code, 0x0B, 0xB8))
        assertEquals(listOf('A', 'R'), records.map { it.tag })
        assertEquals(2, records[0].payload.size)
        assertEquals(2, records[1].payload.size)
    }

    @Test
    fun `parsing stops at an unknown tag and keeps what came before`() {
        val records = ApacheN112Driver.parseRecords(bytes('V'.code, 1, 0x7E, 1, 2, 3))
        assertEquals(listOf('V'), records.map { it.tag })
    }

    @Test
    fun `a record cut short is dropped, not read past the end`() {
        // 'O' needs 4 payload bytes; only 2 arrive.
        val records = ApacheN112Driver.parseRecords(bytes('V'.code, 1, 'O'.code, 0, 1))
        assertEquals(listOf('V'), records.map { it.tag })
    }

    @Test
    fun `long text records do not swallow the next record`() {
        val j = ByteArray(19).also { it[0] = 'J'.code.toByte() }
        val records = ApacheN112Driver.parseRecords(j + bytes('V'.code, 1))
        assertEquals(listOf('J', 'V'), records.map { it.tag })
    }

    @Test
    fun `speed is one byte in km per h`() {
        val f = driver.decode(bytes('A'.code, 42, 0))!!
        assertEquals(42L, f.field(Field.SPEED))
        assertEquals(ApacheN112Driver.RECORDS_DATA_ID, f.dataId)
    }

    @Test
    fun `speed with the mph flag is converted`() {
        val f = driver.decode(bytes('A'.code, 50, 1))!!
        assertEquals(80L, f.field(Field.SPEED)) // 50 mph = 80.47 km/h
    }

    @Test
    fun `speed byte FF means no reading and shows as 0`() {
        val f = driver.decode(bytes('A'.code, 0xFF, 0))!!
        assertEquals(0L, f.field(Field.SPEED))
    }

    @Test
    fun `odometer is three bytes big-endian, kept in tenths`() {
        // 0x01E240 = 123456 tenths = 12345.6 km; Session divides by 10.
        val f = driver.decode(bytes('O'.code, 0x01, 0xE2, 0x40, 0))!!
        assertEquals(123456L, f.field(Field.ODOMETER))
    }

    @Test
    fun `odometer with the miles flag is converted to km`() {
        // 1000.0 miles = 1609.3 km -> 16093 tenths
        val f = driver.decode(bytes('O'.code, 0x00, 0x27, 0x10, 1))!!
        assertEquals(16093L, f.field(Field.ODOMETER))
    }

    @Test
    fun `rpm is two bytes big-endian and unsigned`() {
        val f = driver.decode(bytes('R'.code, 0xC3, 0x50))!!
        assertEquals(50000L, f.field(Field.ENGINE_RPM))
    }

    @Test
    fun `several records in one notification all decode`() {
        val f = driver.decode(
            bytes(
                'A'.code, 30, 0,
                'R'.code, 0x0B, 0xB8,
                'P'.code, 3,
                'C'.code, 0x00, 0x96,
                'I'.code, 120, 0,
                'Z'.code, 35, 0,
            ),
        )!!
        assertEquals(30L, f.field(Field.SPEED))
        assertEquals(3000L, f.field(Field.ENGINE_RPM))
        assertEquals(3L, f.field(Field.GEAR_POSITION))
        assertEquals(150L, f.field(Field.DISTANCE_TO_EMPTY))
        assertEquals(120L, f.field(Field.TOP_SPEED))
        assertEquals(35L, f.field(Field.AVERAGE_SPEED))
    }

    @Test
    fun `a heartbeat-only notification decodes with no values`() {
        // Most notifications are just `V 01`. They still prove the link is live,
        // so they decode, but must not overwrite any reading.
        val f = driver.decode(bytes('V'.code, 1))
        assertNotNull(f)
        assertEquals(0, f!!.values.size)
    }

    @Test
    fun `something that is not a record is not decoded`() {
        // A SmartXonnect challenge: 0x9B is not an ASCII record tag. (Note 0x5A
        // *is* — it is 'Z', average speed — so a 5A-led frame would parse.)
        assertNull(driver.decode(bytes(0x9B, 0xF2, 0x00)))
        assertNull(driver.decode(ByteArray(0)))
    }

    @Test
    fun `a Y record is the handlebar call button`() {
        val d = driver.decode(bytes(0x45, 0x00, 0x59, 0x01))
        assertNotNull(d)
        assertEquals(1L, d!!.field(Field.CALL_DISCONNECT))
    }

    @Test
    fun `unparsed bytes count what the parser could not read`() {
        assertEquals(0, ApacheN112Driver.unparsedBytes(bytes(0x45, 0x00, 0x56, 0x01)))
        // 0x9B is no record tag: everything from it on is unread.
        assertEquals(2, ApacheN112Driver.unparsedBytes(bytes(0x56, 0x01, 0x9B, 0x00)))
        // A record cut short.
        assertEquals(2, ApacheN112Driver.unparsedBytes(bytes(0x56, 0x01, 0x41, 0x10)))
    }

    @Test
    fun `the caller is resent every 400 ms while ringing`() {
        assertEquals(400L, driver.callerRepeatMs)
    }
}
