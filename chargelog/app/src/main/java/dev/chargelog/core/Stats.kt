package dev.chargelog.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object Stats {

    /**
     * Per-day aggregates for every day in [from]..[to] (inclusive).
     *
     * @param samples readings in ascending time order covering the range
     * @param baseline the last reading before the range, if any; supplies the SOC carried
     *   into the first day so a drop across midnight is not lost
     */
    fun daily(
        samples: List<Sample>,
        baseline: Sample?,
        zone: ZoneId,
        from: LocalDate,
        to: LocalDate,
    ): List<DayStat> {
        val b = DailyBuilder(zone, from, to, baseline?.level)
        for (s in samples) b.add(s.ts, s.level, s.charging)
        return b.build()
    }

    /**
     * Groups sessions by every local day they were active on, newest day first and newest
     * session first within a day. A session that crosses midnight is listed under each day
     * it covers, so every day's list is complete. Open sessions run until [now].
     */
    fun sessionsByDay(sessions: List<Session>, zone: ZoneId, now: Long): List<Pair<LocalDate, List<Session>>> {
        val byDay = java.util.TreeMap<LocalDate, MutableList<Session>>(compareByDescending { it })
        for (s in sessions) {
            val end = maxOf(s.endTs ?: now, s.startTs)
            val first = Instant.ofEpochMilli(s.startTs).atZone(zone).toLocalDate()
            // An end exactly at midnight does not reach into the next day.
            val last = Instant.ofEpochMilli(if (end > s.startTs) end - 1 else end).atZone(zone).toLocalDate()
            var d = first
            while (!d.isAfter(last)) {
                byDay.getOrPut(d) { ArrayList() }.add(s)
                d = d.plusDays(1)
            }
        }
        val newestFirst = compareByDescending<Session> { it.startTs }.thenByDescending { it.id }
        return byDay.map { (day, list) -> day to list.sortedWith(newestFirst) }
    }

    fun dayStart(day: LocalDate, zone: ZoneId): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()
}

/**
 * Streaming form of [Stats.daily]: feed (time, SOC) readings in ascending time order, then
 * [build]. Lets long histories be aggregated without holding every reading in memory.
 *
 * @param baselineLevel SOC of the last reading before [from], if any
 */
class DailyBuilder(
    private val zone: ZoneId,
    private val from: LocalDate,
    private val to: LocalDate,
    baselineLevel: Int?,
) {
    private class Acc {
        var usage = 0; var charged = 0; var min: Int? = null; var max: Int? = null
        fun see(level: Int) {
            min = min?.let { minOf(it, level) } ?: level
            max = max?.let { maxOf(it, level) } ?: level
        }
    }

    private val accs = LinkedHashMap<LocalDate, Acc>().also {
        var d = from
        while (!d.isAfter(to)) { it[d] = Acc(); d = d.plusDays(1) }
    }
    private var prevLevel: Int? = baselineLevel
    private var prevDay: LocalDate? = null


    // Readings arrive in order, so the local day is looked up once per day, not per reading.
    private var curDay: LocalDate? = null
    private var curStart = 0L
    private var curEnd = 0L

    private fun dayOf(ts: Long): LocalDate {
        val c = curDay
        if (c != null && ts >= curStart && ts < curEnd) return c
        val d = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        curDay = d
        curStart = Stats.dayStart(d, zone)
        curEnd = Stats.dayStart(d.plusDays(1), zone)
        return d
    }

    fun add(ts: Long, level: Int, charging: Boolean = false) {
        val day = dayOf(ts)
        val p = prevLevel
        // The SOC held since the previous reading also belongs to every day it spans.
        if (p != null) {
            var carry = prevDay?.plusDays(1) ?: from
            while (!carry.isAfter(day)) { accs[carry]?.see(p); carry = carry.plusDays(1) }
        }
        val acc = accs[day]
        if (acc != null) {
            acc.see(level)
            if (p != null) {
                val diff = level - p
                // Every drop is discharge and accumulates, small gauge bounces included.
                // Every rise accumulates as charged, 1 % gauge ticks included.
                if (diff < 0) acc.usage -= diff
                else if (diff > 0) acc.charged += diff
            }
        }
        prevLevel = level
        prevDay = day
    }

    fun build(): List<DayStat> {
        // Days after the final reading keep its SOC (up to today, the caller's `to`).
        val p = prevLevel
        if (p != null) {
            var carry = prevDay?.plusDays(1) ?: from
            while (!carry.isAfter(to)) { accs[carry]?.see(p); carry = carry.plusDays(1) }
        }
        return accs.map { (day, a) -> DayStat(day, a.usage, a.charged, a.min, a.max) }
    }
}
