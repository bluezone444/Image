package dev.chargelog.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.chargelog.core.Sample
import dev.chargelog.core.Session
import dev.chargelog.core.SessionType
import dev.chargelog.core.Store

class Db private constructor(context: Context) :
    SQLiteOpenHelper(context, "chargelog.db", null, 1), Store {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE samples(ts INTEGER PRIMARY KEY, level INTEGER NOT NULL, " +
                "plugged INTEGER NOT NULL, status INTEGER NOT NULL, temp INTEGER NOT NULL, " +
                "voltage INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, type INTEGER NOT NULL, " +
                "start_ts INTEGER NOT NULL, start_soc INTEGER NOT NULL, end_ts INTEGER, " +
                "end_soc INTEGER NOT NULL, plug INTEGER NOT NULL, peak_ts INTEGER NOT NULL, " +
                "min_soc INTEGER NOT NULL, max_soc INTEGER NOT NULL, max_temp INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_sessions_start ON sessions(start_ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---- samples ----

    override fun lastSample(): Sample? =
        readableDatabase.rawQuery("SELECT * FROM samples ORDER BY ts DESC LIMIT 1", null)
            .use { if (it.moveToFirst()) sample(it) else null }

    /** Last sample strictly before [ts]. */
    fun sampleBefore(ts: Long): Sample? =
        readableDatabase.rawQuery(
            "SELECT * FROM samples WHERE ts < ? ORDER BY ts DESC LIMIT 1", arrayOf(ts.toString())
        ).use { if (it.moveToFirst()) sample(it) else null }

    override fun insertSample(s: Sample) {
        val v = ContentValues().apply {
            put("ts", s.ts); put("level", s.level); put("plugged", s.plugged)
            put("status", s.status); put("temp", s.temp); put("voltage", s.voltage)
        }
        writableDatabase.insertWithOnConflict("samples", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Samples with from <= ts < to, ascending. */
    fun samples(from: Long, to: Long): List<Sample> {
        val out = ArrayList<Sample>()
        forEachSample(from, to) { out.add(it) }
        return out
    }

    fun forEachSample(from: Long = 0, to: Long = Long.MAX_VALUE, block: (Sample) -> Unit) {
        readableDatabase.rawQuery(
            "SELECT * FROM samples WHERE ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(from.toString(), to.toString())
        ).use { c -> while (c.moveToNext()) block(sample(c)) }
    }

    fun firstSampleTs(): Long? =
        readableDatabase.rawQuery("SELECT MIN(ts) FROM samples", null)
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    fun sampleCount(): Long = count("samples")
    fun sessionCount(): Long = count("sessions")

    private fun count(table: String): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null)
            .use { it.moveToFirst(); it.getLong(0) }

    private fun sample(c: Cursor) = Sample(
        ts = c.getLong(0), level = c.getInt(1), plugged = c.getInt(2),
        status = c.getInt(3), temp = c.getInt(4), voltage = c.getInt(5),
    )

    // ---- sessions ----

    override fun latestSessions(limit: Int): List<Session> = querySessions(
        "SELECT * FROM sessions ORDER BY start_ts DESC, id DESC LIMIT ?", arrayOf(limit.toString())
    )

    /** Sessions overlapping [from, to), oldest first. Open sessions count as running until now. */
    fun sessionsOverlapping(from: Long, to: Long): List<Session> = querySessions(
        "SELECT * FROM sessions WHERE start_ts < ? AND (end_ts IS NULL OR end_ts > ?) " +
            "ORDER BY start_ts, id",
        arrayOf(to.toString(), from.toString())
    )

    fun forEachSession(block: (Session) -> Unit) {
        readableDatabase.rawQuery("SELECT * FROM sessions ORDER BY start_ts, id", null)
            .use { c -> while (c.moveToNext()) block(session(c)) }
    }

    private fun querySessions(sql: String, args: Array<String>): List<Session> {
        val out = ArrayList<Session>()
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(session(c)) }
        return out
    }

    override fun insertSession(s: Session): Long =
        writableDatabase.insertOrThrow("sessions", null, values(s))

    override fun updateSession(s: Session) {
        writableDatabase.update("sessions", values(s), "id = ?", arrayOf(s.id.toString()))
    }

    override fun deleteSession(id: Long) {
        writableDatabase.delete("sessions", "id = ?", arrayOf(id.toString()))
    }

    fun clearAll() {
        writableDatabase.delete("samples", null, null)
        writableDatabase.delete("sessions", null, null)
    }

    private fun values(s: Session) = ContentValues().apply {
        put("type", s.type.code); put("start_ts", s.startTs); put("start_soc", s.startSoc)
        if (s.endTs == null) putNull("end_ts") else put("end_ts", s.endTs)
        put("end_soc", s.endSoc); put("plug", s.plug); put("peak_ts", s.peakTs)
        put("min_soc", s.minSoc); put("max_soc", s.maxSoc); put("max_temp", s.maxTemp)
    }

    private fun session(c: Cursor) = Session(
        id = c.getLong(0), type = SessionType.of(c.getInt(1)), startTs = c.getLong(2),
        startSoc = c.getInt(3), endTs = if (c.isNull(4)) null else c.getLong(4),
        endSoc = c.getInt(5), plug = c.getInt(6), peakTs = c.getLong(7),
        minSoc = c.getInt(8), maxSoc = c.getInt(9), maxTemp = c.getInt(10),
    )

    companion object {
        @Volatile private var instance: Db? = null
        fun get(context: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Db(context.applicationContext).also { instance = it }
            }
    }
}
