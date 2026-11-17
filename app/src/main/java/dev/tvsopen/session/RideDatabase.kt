// Where the ride history lives: a Room database, `databases/rides.db`.
//
// RideLog keeps the rides in memory and decides what changed (RideRules does
// the deciding); this file only stores rows. A ride is keyed by its start time:
// a rejoined ride keeps it, and two rides never start in the same millisecond,
// so `Ride` needs no id of its own and an imported ride is recognised by it.

package dev.tvsopen.session

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert

/** One stored ride. Same fields as [Ride]. */
@Entity(tableName = "rides")
data class RideRow(
    @PrimaryKey val startedAt: Long,
    val endedAt: Long?,
    val distanceKm: Double,
    val topSpeedKmh: Int?,
    val startOdometerKm: Double?,
    val lastSeenAt: Long?,
) {
    fun toRide() = Ride(startedAt, endedAt, distanceKm, topSpeedKmh, startOdometerKm, lastSeenAt)

    companion object {
        fun of(r: Ride) = RideRow(r.startedAt, r.endedAt, r.distanceKm, r.topSpeedKmh, r.startOdometerKm, r.lastSeenAt)
    }
}

/**
 * The lifetime totals, one row. Kept apart from the rides because they also
 * cover rides the old 100- and 1000-ride limits dropped.
 */
@Entity(tableName = "ride_totals")
data class TotalsRow(
    @PrimaryKey val id: Int = 0,
    val rides: Int,
    val distanceKm: Double,
) {
    fun toTotals() = RideRules.Totals(rides, distanceKm)

    companion object {
        fun of(t: RideRules.Totals) = TotalsRow(0, t.rides, t.distanceKm)
    }
}

@Dao
abstract class RideDao {
    @Query("SELECT * FROM rides ORDER BY startedAt DESC")
    abstract fun all(): List<RideRow>

    @Upsert
    abstract fun upsert(rows: List<RideRow>)

    @Query("DELETE FROM rides WHERE startedAt IN (:startedAts)")
    abstract fun delete(startedAts: List<Long>)

    @Query("SELECT * FROM ride_totals WHERE id = 0")
    abstract fun totals(): TotalsRow?

    @Upsert
    abstract fun putTotals(row: TotalsRow)

    /** One change, all or nothing: the rides that changed and the totals. */
    @Transaction
    open fun apply(changes: RideChanges, totals: RideRules.Totals) {
        if (changes.upserts.isNotEmpty()) upsert(changes.upserts.map(RideRow::of))
        // SQLite caps bound parameters per statement; chunk to stay under it.
        changes.deletes.chunked(500).forEach { delete(it) }
        putTotals(TotalsRow.of(totals))
    }
}

@Database(entities = [RideRow::class, TotalsRow::class], version = 1, exportSchema = true)
abstract class RideDatabase : RoomDatabase() {
    abstract fun rides(): RideDao

    companion object {
        @Volatile
        private var instance: RideDatabase? = null

        fun get(context: Context): RideDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, RideDatabase::class.java, "rides.db")
                .build()
                .also { instance = it }
        }
    }
}

/**
 * What to write to go from one ride list to the next: rides that are new or
 * changed, and the start times of rides that are gone. Pure.
 */
data class RideChanges(val upserts: List<Ride>, val deletes: List<Long>) {
    val isEmpty: Boolean get() = upserts.isEmpty() && deletes.isEmpty()

    companion object {
        fun diff(old: List<Ride>, new: List<Ride>): RideChanges {
            val before = old.associateBy { it.startedAt }
            val after = new.map { it.startedAt }.toHashSet()
            return RideChanges(
                upserts = new.filter { before[it.startedAt] != it },
                deletes = old.map { it.startedAt }.filter { it !in after },
            )
        }
    }
}
