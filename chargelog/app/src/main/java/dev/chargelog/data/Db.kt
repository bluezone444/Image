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
    SQLiteOpenHelper(context, "chargelog.db", null, 4), Store {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE samples(ts INTEGER PRIMARY KEY, level INTEGER NOT NULL, " +
                "plugged INTEGER NOT NULL, status INTEGER NOT NULL, temp INTEGER NOT NULL, " +
                "voltage INTEGER NOT NULL, ocv INTEGER NOT NULL DEFAULT 0, est INTEGER NOT NULL DEFAULT 0, " +
                V4_SAMPLE_COLUMNS.joinToString(", ") + ")"
        )
        db.execSQL(
            "CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, type INTEGER NOT NULL, " +
                "start_ts INTEGER NOT NULL, start_soc INTEGER NOT NULL, end_ts INTEGER, " +
                "end_soc INTEGER NOT NULL, plug INTEGER NOT NULL, peak_ts INTEGER NOT NULL, " +
                "min_soc INTEGER NOT NULL, max_soc INTEGER NOT NULL, max_temp INTEGER NOT NULL, " +
                "est INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX idx_sessions_start ON sessions(start_ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE samples ADD COLUMN ocv INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE samples ADD COLUMN est INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sessions ADD COLUMN est INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            V4_SAMPLE_COLUMNS.forEach { db.execSQL("ALTER TABLE samples ADD COLUMN $it") }
            db.execSQL("UPDATE samples SET ocv_src = 1 WHERE ocv > 0")
        }
    }

    /** Per-day aggregates with finished days cached. */
    val days = DayCache(this)

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
            put("status", s.status); put("temp", s.temp); put("voltage", s.voltage); put("ocv", s.ocv)
            put("est", if (s.est) 1 else 0)
            put("current_ua", s.currentUa); put("charge_uah", s.chargeUah); put("health", s.health)
            put("max_ua", s.maxChargeUa); put("max_uv", s.maxChargeUv); put("cycles", s.cycles)
            put("ocv_src", s.ocvSrc)
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

    /** Only (time, SOC) of each sample with from <= ts < to, ascending; for long-range aggregates. */
    fun forEachLevel(from: Long, to: Long, block: (Long, Int) -> Unit) {
        readableDatabase.rawQuery(
            "SELECT ts, level FROM samples WHERE ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(from.toString(), to.toString())
        ).use { c -> while (c.moveToNext()) block(c.getLong(0), c.getInt(1)) }
    }

    fun firstSampleTs(): Long? =
        readableDatabase.rawQuery("SELECT MIN(ts) FROM samples", null)
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    fun sampleCount(): Long = count("samples")
    fun sessionCount(): Long = count("sessions")

    private fun count(table: String): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null)
            .use { it.moveToFirst(); it.getLong(0) }

    private fun sample(c: Cursor): Sample {
        fun i(name: String) = c.getInt(c.getColumnIndexOrThrow(name))
        return Sample(
            ts = c.getLong(c.getColumnIndexOrThrow("ts")), level = i("level"), plugged = i("plugged"),
            status = i("status"), temp = i("temp"), voltage = i("voltage"), ocv = i("ocv"), est = i("est") != 0,
            currentUa = i("current_ua"), chargeUah = i("charge_uah"), health = i("health"),
            maxChargeUa = i("max_ua"), maxChargeUv = i("max_uv"), cycles = i("cycles"), ocvSrc = i("ocv_src"),
        )
    }

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

    fun estimatedCount(): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM samples WHERE est = 1", null)
            .use { it.moveToFirst(); it.getLong(0) }

    /**
     * Replays every observed reading through a fresh [Tracker] so that gaps already in the
     * history get estimated readings and sessions too. Runs in one transaction.
     */
    fun rebuildWithEstimates() {
        val real = samples(0, Long.MAX_VALUE).filter { !it.est }
        val db = writableDatabase
        db.beginTransaction()
        try {
            clearAll()
            val tracker = dev.chargelog.core.Tracker(this)
            real.forEach { tracker.onReading(it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            days.clear()
        }
    }

    fun clearAll() {
        writableDatabase.delete("samples", null, null)
        writableDatabase.delete("sessions", null, null)
        days.clear()
    }

    private fun values(s: Session) = ContentValues().apply {
        put("type", s.type.code); put("start_ts", s.startTs); put("start_soc", s.startSoc)
        if (s.endTs == null) putNull("end_ts") else put("end_ts", s.endTs)
        put("end_soc", s.endSoc); put("plug", s.plug); put("peak_ts", s.peakTs)
        put("min_soc", s.minSoc); put("max_soc", s.maxSoc); put("max_temp", s.maxTemp)
        put("est", if (s.est) 1 else 0)
    }

    private fun session(c: Cursor) = Session(
        id = c.getLong(0), type = SessionType.of(c.getInt(1)), startTs = c.getLong(2),
        startSoc = c.getInt(3), endTs = if (c.isNull(4)) null else c.getLong(4),
        endSoc = c.getInt(5), plug = c.getInt(6), peakTs = c.getLong(7),
        minSoc = c.getInt(8), maxSoc = c.getInt(9), maxTemp = c.getInt(10), est = c.getInt(11) != 0,
    )

    companion object {
        /** Columns added in schema v4 (detailed readings and OCV source). */
        private val V4_SAMPLE_COLUMNS = listOf(
            "current_ua INTEGER NOT NULL DEFAULT 0", "charge_uah INTEGER NOT NULL DEFAULT 0",
            "health INTEGER NOT NULL DEFAULT 0", "max_ua INTEGER NOT NULL DEFAULT 0",
            "max_uv INTEGER NOT NULL DEFAULT 0", "cycles INTEGER NOT NULL DEFAULT -1",
            "ocv_src INTEGER NOT NULL DEFAULT 0",
        )

        @Volatile private var instance: Db? = null
        fun get(context: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Db(context.applicationContext).also { instance = it }
            }
    }
}
