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
        class Acc { var usage = 0; var charged = 0; var min: Int? = null; var max: Int? = null
            fun see(level: Int) {
                min = min?.let { minOf(it, level) } ?: level
                max = max?.let { maxOf(it, level) } ?: level
            }
        }
        val accs = LinkedHashMap<LocalDate, Acc>()
        var d = from
        while (!d.isAfter(to)) { accs[d] = Acc(); d = d.plusDays(1) }

        var prev: Sample? = baseline
        var prevDay: LocalDate? = null
        for (s in samples) {
            val day = Instant.ofEpochMilli(s.ts).atZone(zone).toLocalDate()
            val p = prev
            // The SOC held since the previous reading also belongs to every day it spans.
            if (p != null) {
                var carry = prevDay?.plusDays(1) ?: from
                while (!carry.isAfter(day)) { accs[carry]?.see(p.level); carry = carry.plusDays(1) }
            }
            val acc = accs[day]
            if (acc != null) {
                acc.see(s.level)
                if (p != null) {
                    val diff = s.level - p.level
                    if (diff < 0) acc.usage -= diff else acc.charged += diff
                }
            }
            prev = s
            prevDay = day
        }
        // Days after the final reading keep its SOC (up to today, the caller's `to`).
        val p = prev
        if (p != null) {
            var carry = prevDay?.plusDays(1) ?: from
            while (!carry.isAfter(to)) { accs[carry]?.see(p.level); carry = carry.plusDays(1) }
        }
        return accs.map { (day, a) -> DayStat(day, a.usage, a.charged, a.min, a.max) }
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
