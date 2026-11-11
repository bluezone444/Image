package dev.chargelog.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** "What does a usual day look like" over a period, for the statistics dashboard. */
data class Summary(
    /** Days the averages are based on. */
    val days: List<DayStat>,
    /** False when no fully recorded day exists yet and partial days were used instead. */
    val completeDaysOnly: Boolean,
    val avgUsage: Double?,
    val medianUsage: Int?,
    val avgMin: Double?,
    val avgMax: Double?,
    /** Lowest / highest SOC seen in the period, with the day it happened. */
    val lowest: DayStat?,
    val highest: DayStat?,
    val chargesPerDay: Double?,
    val chargeCount: Int,
    val avgChargeStart: Double?,
    val avgChargeEnd: Double?,
    val avgChargeMinutes: Double?,
)

object Summaries {

    /**
     * @param period daily stats for the period, oldest first, ending today
     * @param sessions sessions overlapping the period
     * @param firstSampleTs when recording started; that day is only partly recorded
     * @param dayType only days of this type count, and only charges started on such days
     */
    fun of(
        period: List<DayStat>,
        sessions: List<Session>,
        firstSampleTs: Long?,
        today: LocalDate,
        zone: ZoneId,
        dayType: DayType = DayType.ALL,
    ): Summary {
        val firstDay = firstSampleTs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val recorded = StatsFilter.days(period, dayType).filter { it.minSoc != null && (firstDay == null || !it.day.isBefore(firstDay)) }
        // A full day is one recorded from midnight to midnight: not the install day, not today.
        val complete = recorded.filter { it.day != firstDay && it.day != today }
        val base = complete.ifEmpty { recorded }

        val baseDays = base.map { it.day }.toSet()
        fun dayOf(ts: Long) = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        val chargesInBase = sessions.count { it.type == SessionType.CHARGE && dayOf(it.startTs) in baseDays }

        val periodStart = period.firstOrNull()?.day
        val charges = StatsFilter.sessions(sessions, dayType, zone).filter {
            it.type == SessionType.CHARGE && !it.open && it.delta > 0 &&
                (periodStart == null || !dayOf(it.startTs).isBefore(periodStart))
        }

        val usages = base.map { it.usage }.sorted()
        return Summary(
            days = base,
            completeDaysOnly = complete.isNotEmpty(),
            avgUsage = usages.averageOrNull(),
            medianUsage = if (usages.isEmpty()) null else usages[usages.size / 2],
            avgMin = base.mapNotNull { it.minSoc }.averageOrNull(),
            avgMax = base.mapNotNull { it.maxSoc }.averageOrNull(),
            lowest = recorded.minByOrNull { it.minSoc!! },
            highest = recorded.maxByOrNull { it.maxSoc!! },
            chargesPerDay = if (base.isEmpty()) null else chargesInBase.toDouble() / base.size,
            chargeCount = charges.size,
            avgChargeStart = charges.map { it.startSoc }.averageOrNull(),
            avgChargeEnd = charges.map { it.endSoc }.averageOrNull(),
            avgChargeMinutes = charges.map { (it.endTs!! - it.startTs) / 60_000.0 }.averageOrNull(),
        )
    }

    private fun List<Int>.averageOrNull(): Double? = if (isEmpty()) null else average()

    @JvmName("averageOrNullDouble")
    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
