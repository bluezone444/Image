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

    fun dayStart(day: LocalDate, zone: ZoneId): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()
}
