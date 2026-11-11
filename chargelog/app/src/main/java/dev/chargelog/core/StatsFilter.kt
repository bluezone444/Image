package dev.chargelog.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Which days of the week the statistics count. */
enum class DayType(val label: String) {
    ALL("전체"), WEEKDAY("평일"), WEEKEND("주말");

    /** Saturday and Sunday are the weekend; every other day is a weekday. */
    fun matches(day: LocalDate): Boolean {
        val weekend = day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY
        return when (this) {
            ALL -> true
            WEEKDAY -> !weekend
            WEEKEND -> weekend
        }
    }

    companion object {
        fun of(name: String?): DayType = values().firstOrNull { it.name == name } ?: ALL
    }
}

/** How far back the statistics look. [days] is null for "since recording started". */
enum class StatsPeriod(val label: String, val days: Int?) {
    D7("7일", 7), D30("30일", 30), D90("90일", 90), ALL("전체", null);

    /**
     * First day of the period ending [today] (inclusive). [ALL] starts on [firstDay], the day of
     * the first stored reading, or today when nothing is recorded yet (or the clock is behind it).
     */
    fun start(today: LocalDate, firstDay: LocalDate?): LocalDate = when (val n = days) {
        null -> if (firstDay == null || firstDay.isAfter(today)) today else firstDay
        else -> today.minusDays(n - 1L)
    }

    companion object {
        fun of(name: String?): StatsPeriod = values().firstOrNull { it.name == name } ?: D30
    }
}

/** Day-type filtering shared by the summary, the per-day charts, charging habits and the table. */
object StatsFilter {

    fun days(days: List<DayStat>, type: DayType): List<DayStat> =
        if (type == DayType.ALL) days else days.filter { type.matches(it.day) }

    /** Sessions that started on a day of the given type. */
    fun sessions(sessions: List<Session>, type: DayType, zone: ZoneId): List<Session> =
        if (type == DayType.ALL) sessions
        else sessions.filter { type.matches(Instant.ofEpochMilli(it.startTs).atZone(zone).toLocalDate()) }

    /** For each day, whether the filter counts it; same order as [days]. */
    fun mask(days: List<DayStat>, type: DayType): BooleanArray =
        BooleanArray(days.size) { type.matches(days[it].day) }
}
