// Export, then import: the round trip, merging, and refusing other files.
// Invented rides.

package dev.tvsopen.data

import dev.tvsopen.session.Ride
import dev.tvsopen.session.RideRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DataImportTest {

    private val settings = DataExport.Settings(
        riderName = "Rider",
        navigationMirroring = true,
        mirrorPhoneCalls = true,
        mirrorMessages = false,
        theme = "SYSTEM",
        callReplyEnabled = false,
        callReplyText = "Riding, will call back",
        deviceNickname = "Example Phone",
    )

    private val a = Ride(1700000000000, 1700000600000, 12.3, 71)
    private val b = Ride(1699900000000, 1699900900000, 4.2, null)
    private val c = Ride(1699800000000, 1699800300000, 7.5, 55)

    private fun export(rides: List<Ride>, totals: RideRules.Totals) =
        DataImport.parse(DataExport.toJson(rides, totals, settings, 1700000700000))

    @Test
    fun `export then parse gives the same rides and totals`() {
        val got = export(listOf(a, b), RideRules.Totals(30, 400.5))
        assertEquals(listOf(a, b), got.rides)
        assertEquals(RideRules.Totals(30, 400.5), got.totals)
    }

    @Test
    fun `an empty phone takes the file's totals`() {
        val empty = RideRules.State(emptyList(), RideRules.Totals(0, 0.0))
        val (state, result) = DataImport.merge(empty, export(listOf(a, b), RideRules.Totals(30, 400.5)))
        assertEquals(listOf(a, b), state.rides)
        assertEquals(RideRules.Totals(30, 400.5), state.totals)
        assertEquals(DataImport.Result(added = 2, alreadyHere = 0, unusable = 0), result)
        assertEquals("Added 2 rides · 0 already here", result.message)
    }

    @Test
    fun `rides already here are skipped and only new ones add to the totals`() {
        val here = RideRules.State(listOf(a, c), RideRules.Totals(2, 19.8))
        val (state, result) = DataImport.merge(here, export(listOf(a, b), RideRules.Totals(30, 400.5)))
        // Newest first, b slotted between a and c.
        assertEquals(listOf(a, b, c), state.rides)
        assertEquals(3, state.totals.rides)
        assertEquals(24.0, state.totals.distanceKm, 1e-9)
        assertEquals(DataImport.Result(added = 1, alreadyHere = 1, unusable = 0), result)
    }

    @Test
    fun `importing the same file twice adds nothing`() {
        val file = export(listOf(a, b), RideRules.Totals(2, 16.5))
        val empty = RideRules.State(emptyList(), RideRules.Totals(0, 0.0))
        val once = DataImport.merge(empty, file).first
        val (twice, result) = DataImport.merge(once, file)
        assertEquals(once, twice)
        assertEquals("Added 0 rides · 2 already here", result.message)
    }

    @Test
    fun `open and short rides in the file are skipped`() {
        val open = Ride(1700100000000, null, 3.0, 40)
        val short = Ride(1700050000000, 1700050060000, 0.0, null)
        val empty = RideRules.State(emptyList(), RideRules.Totals(0, 0.0))
        val (state, result) = DataImport.merge(empty, export(listOf(open, short, a), RideRules.Totals(1, 12.3)))
        assertEquals(listOf(a), state.rides)
        assertEquals(DataImport.Result(added = 1, alreadyHere = 0, unusable = 2), result)
    }

    @Test
    fun `an open ride on this phone stays first`() {
        val riding = Ride(1690000000000, null, 1.0, 30)
        val here = RideRules.State(listOf(riding), RideRules.Totals(0, 0.0))
        val (state, _) = DataImport.merge(here, export(listOf(a), RideRules.Totals(1, 12.3)))
        assertEquals(listOf(riding, a), state.rides)
    }

    @Test
    fun `greeting, call reply and device name come back from the file`() {
        val got = export(listOf(a), RideRules.Totals(1, 12.3)).settings
        assertEquals(
            DataImport.Settings(
                riderName = DataImport.Present("Rider"),
                callReplyEnabled = false,
                callReplyText = DataImport.Present("Riding, will call back"),
                deviceNickname = DataImport.Present("Example Phone"),
            ),
            got,
        )
        assertEquals(4, got.count)
    }

    @Test
    fun `a default in the file restores the default`() {
        val defaults = settings.copy(riderName = null, callReplyText = null, deviceNickname = null)
        val got = DataImport.parse(DataExport.toJson(emptyList(), RideRules.Totals(0, 0.0), defaults, 0L)).settings
        assertEquals(DataImport.Present<String?>(null), got.riderName)
        assertEquals(DataImport.Present<String?>(null), got.callReplyText)
        assertEquals(DataImport.Present<String?>(null), got.deviceNickname)
    }

    @Test
    fun `an older file restores the greeting it carries and nothing else`() {
        // The shape exports had before call reply and device name were added.
        val old = """{"format":"tvsride-export","version":1,"rides":[],
            "prefs":{"riderName":"Rider","theme":"AUTO"}}"""
        val got = DataImport.parse(old).settings
        assertEquals(DataImport.Settings(riderName = DataImport.Present("Rider")), got)
        assertEquals(1, got.count)
    }

    @Test
    fun `a file with no settings restores none`() {
        val bare = """{"format":"tvsride-export","version":1,"rides":[]}"""
        val got = DataImport.parse(bare).settings
        assertEquals(DataImport.Settings(), got)
        assertEquals(0, got.count)
    }

    @Test
    fun `the import message counts restored settings`() {
        val r = DataImport.Result(added = 0, alreadyHere = 16, unusable = 0)
        assertEquals("Added 0 rides · 16 already here · 3 settings restored", DataImport.message(r, 3))
        assertEquals("Added 0 rides · 16 already here", DataImport.message(r, 0))
    }

    @Test
    fun `other files are refused`() {
        assertThrows(DataImport.NotAnExport::class.java) { DataImport.parse("hello") }
        assertThrows(DataImport.NotAnExport::class.java) { DataImport.parse("""{"format":"other","version":1}""") }
        assertThrows(DataImport.NotAnExport::class.java) {
            DataImport.parse("""{"format":"tvsride-export","version":2,"rides":[]}""")
        }
    }
}
