package app.kotowski.keepsimalive.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SimConfigEntity::class, SendHistoryEntity::class], version = 1, exportSchema = true)
abstract class KeepaliveDatabase : RoomDatabase() {
    abstract fun simConfigDao(): SimConfigDao

    abstract fun simHistoryDao(): SimHistoryDao

    companion object {
        const val NAME = "keepalive.db"
    }
}

// One open (PENDING/SENDING) history row per SIM is a hard invariant of the send engine;
// Room cannot express a partial unique index in entity annotations, so it is created here
// on every database open.
object KeepaliveDatabaseCallback : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        // Drop first: the old index's WHERE carries the RETRYING literal and CREATE IF NOT
        // EXISTS would keep it; dropping also keeps the rename below from ever tripping the
        // old index's constraint.
        db.execSQL("DROP INDEX IF EXISTS index_send_history_one_open_per_sim")
        // Upgrade edge: an install may hold an open RETRYING row at upgrade time — rename it
        // in place (idempotent; a no-op from the second open on). Safe under the
        // one-open-row invariant: the renamed row is the SIM's only open row.
        db.execSQL("UPDATE send_history SET outcome = 'PENDING' WHERE outcome = 'RETRYING'")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_send_history_one_open_per_sim ON send_history (simId) WHERE outcome IN ('PENDING', 'SENDING')",
        )
    }
}
