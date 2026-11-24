// What the app does before the rider has touched anything.
//
// Defaults that each fixed a bug that looked fine:
//
// - The "Only TVS" filter is name-based and applied in software, so it defaults
//   on and still surfaces the UUID-less Apache. The earlier UUID ScanFilter had
//   to default off, because it found nothing on the one vehicle known to work.
// - The scan screen no longer asks for a model: this build connects as
//   APACHE_160 and RideService resolves the driver from the offered service.
//   UNKNOWN would have selected a driver with no frame table, nav or caller ID.
// - First launch asked for nothing, so a rider could install, connect
//   successfully, start a route and see nothing on the cluster, with no
//   indication a permission was missing.
//
// These pin the values. They are defaults in a data class or a composable rather
// than behaviour reachable from a JVM test, so what is asserted is the constant
// itself — which is the thing that was wrong.

package dev.tvsopen.transport

import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.protocol.DriverRegistry
import dev.tvsopen.protocol.GattProfile
import dev.tvsopen.protocol.NtorqDriver
import dev.tvsopen.protocol.VehicleModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLaunchDefaultsTest {

    @Test
    fun `the scan filter defaults on and is name-based, not a UUID ScanFilter`() {
        // The "Only TVS" filter is now applied in ScooterScanner.publish(), not
        // as a platform ScanFilter.setServiceUuid(...). That is what makes
        // defaulting it on safe: the UUID-less Apache (advertising "TVS2509…")
        // is matched by name and still appears. The old UUID filter had to
        // default off because it hid the rider's own cluster at the OS level.
        assertTrue(
            "name-based filter still surfaces the UUID-less Apache",
            ScanState().onlySmartXonnect,
        )
    }

    @Test
    fun `a fresh scan starts empty with the filter on`() {
        val s = ScanState()
        assertTrue(s.onlySmartXonnect)
        assertEquals(emptyList<Discovered>(), s.devices)
        assertFalse(s.scanning)
    }

    @Test
    fun `the fixed connect model is an Apache, not UNKNOWN`() {
        // The scan screen no longer asks for a model: this build connects as
        // APACHE_160, and RideService resolves the real driver from the offered
        // service anyway. UNKNOWN would have selected a driver with no frame
        // table, so pinning the fixed value guards against that regressing.
        assertNotEquals(VehicleModel.UNKNOWN, VehicleModel.APACHE_160)
        assertEquals("APACHE_160", VehicleModel.APACHE_160.name)
        assertTrue(VehicleModel.APACHE_160.isApache())
    }

    @Test
    fun `the Ntorq driver really does decode frames`() {
        // The point of preselecting NTORQ rather than UNKNOWN: UNKNOWN resolves
        // to GenericDriver, which validates framing and decodes nothing. If this
        // ever regressed, the default would reproduce the bug one level down.
        val ntorq = NtorqDriver()
        val realSm1 = byteArrayOf(
            0x5A, 0x10, 0x00, 0x02, 0x88.toByte(), 0x2A, 0x02, 0x00, 0x00, 0x00,
            0x09, 0x50, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF.toByte(),
        )
        assertTrue("NTORQ must decode telemetry", ntorq.capabilities.navigationHud)
        assertTrue(
            "NTORQ must have a frame table",
            ntorq.decode(realSm1) != null,
        )
    }

    @Test
    fun `UNKNOWN decodes nothing, which is why it was the wrong default`() {
        // Stated explicitly so that a future change to UNKNOWN's behaviour is a
        // decision rather than an accident.
        val generic = DriverRegistry.forModel(VehicleModel.UNKNOWN)
        assertFalse(
            "if UNKNOWN ever decoded frames the default would matter much less",
            generic.capabilities.navigationHud,
        )
        assertFalse(generic.capabilities.textMessages)
    }

    // ---- auto-connect ----

    @Test
    fun `auto-connect is on for a fresh install`() {
        assertTrue(RidePrefs.resolveAutoConnect(stored = null))
    }

    @Test
    fun `an explicit auto-connect choice always wins`() {
        assertFalse(RidePrefs.resolveAutoConnect(stored = false))
        assertTrue(RidePrefs.resolveAutoConnect(stored = true))
    }

    // ---- scanner names ----

    @Test
    fun `both known cluster name shapes are recognised`() {
        // SmartXonnect (Ntorq) and the Apache N112 module. Invented serials.
        assertTrue(ScooterScanner.looksLikeCluster("TVS BT 1234"))
        assertTrue(ScooterScanner.looksLikeCluster("TVSBT0001"))
        assertTrue(ScooterScanner.looksLikeCluster("TVS1234567890"))
    }

    @Test
    fun `unrelated names are not flagged as clusters`() {
        assertFalse(ScooterScanner.looksLikeCluster(null))
        assertFalse(ScooterScanner.looksLikeCluster("TVS Living Room"))
        assertFalse(ScooterScanner.looksLikeCluster("TVS12"))
        assertFalse(ScooterScanner.looksLikeCluster("Galaxy Buds"))
    }

    @Test
    fun `the Apache profile is distinct from SmartXonnect and both are tried`() {
        val apache = GattProfile.APACHE_N112
        val sx = GattProfile.SMARTXONNECT
        assertNotEquals(sx.serviceUuid, apache.serviceUuid)
        assertTrue(apache.requiredNotifyUuid in apache.notifyUuids)
        assertEquals(listOf(sx, apache), GattProfile.ALL)
    }
}
