package dev.chargelog.data

import dev.chargelog.core.DailyBuilder
import dev.chargelog.core.DayStat
import dev.chargelog.core.Stats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Per-day aggregates with the finished days remembered, so screens that are rebuilt for every
 * new reading do not re-read the whole history each time.
 *
 * Readings are only ever appended after the newest stored one (gap estimates included), so every
 * day before the day of the newest reading can no longer change. Those days are kept; later
 * days are recomputed on each call. [clear] must be called when history is rewritten.
 */
class DayCache(private val db: Db) {
    private var zone: ZoneId? = null
    private var firstTs: Long? = null
    /** Contiguous run of days that can no longer change. */
    private val frozen = java.util.TreeMap<LocalDate, DayStat>()

    @Synchronized
    fun clear() { frozen.clear() }

    /** Aggregates for every day in [from]..[to] (inclusive), oldest first. */
    @Synchronized
    fun range(from: LocalDate, to: LocalDate, zone: ZoneId): List<DayStat> {
        if (to.isBefore(from)) return emptyList()
        val first = db.firstSampleTs()
        if (zone != this.zone || first != firstTs) {
            frozen.clear()
            this.zone = zone
            firstTs = first
        }
        // Read before computing: anything stored meanwhile only makes this bound conservative.
        val newestDay = db.lastSample()?.let { Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate() }

        val out = ArrayList<DayStat>()
        var computeFrom = from
        val reuse = frozen.isNotEmpty() && !frozen.firstKey().isAfter(from)
        if (reuse) {
            val last = frozen.lastKey()
            if (!from.isAfter(last)) out.addAll(frozen.subMap(from, true, minOf(to, last), true).values)
            computeFrom = maxOf(from, last.plusDays(1))
        }
        if (!computeFrom.isAfter(to)) {
            val fresh = compute(computeFrom, to, zone)
            out.addAll(fresh)
            if (newestDay != null) {
                // An older start than the kept run: the new, longer run replaces it.
                if (!reuse) frozen.clear()
                val contiguous = frozen.isEmpty() || frozen.lastKey().plusDays(1) == computeFrom
                if (contiguous) for (d in fresh) if (d.day.isBefore(newestDay)) frozen[d.day] = d
            }
        }
        return out
    }

    private fun compute(from: LocalDate, to: LocalDate, zone: ZoneId): List<DayStat> {
        val start = Stats.dayStart(from, zone)
        val end = Stats.dayStart(to.plusDays(1), zone)
        val b = DailyBuilder(zone, from, to, db.sampleBefore(start)?.level)
        db.forEachLevel(start, end) { ts, level, plugged -> b.add(ts, level, plugged) }
        return b.build()
    }
}
