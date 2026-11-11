package dev.chargelog.core

/** Charger kinds, matching BatteryManager.BATTERY_PLUGGED_* values. */
object Plug {
    const val NONE = 0
    const val AC = 1
    const val USB = 2
    const val WIRELESS = 4

    fun label(p: Int): String = when (p) {
        NONE -> "none"
        AC -> "ac"
        USB -> "usb"
        WIRELESS -> "wireless"
        else -> "other"
    }

    fun labelKo(p: Int): String = when (p) {
        NONE -> "-"
        AC -> "충전기"
        USB -> "USB"
        WIRELESS -> "무선"
        else -> "기타"
    }
}

/** One battery reading. [level] is SOC in percent, [temp] in 0.1 °C, [voltage] in mV. */
data class Sample(
    val ts: Long,
    val level: Int,
    val plugged: Int,
    val status: Int,
    val temp: Int,
    val voltage: Int,
) {
    val charging: Boolean get() = plugged != Plug.NONE
}

enum class SessionType(val code: Int) {
    DISCHARGE(0), CHARGE(1);

    companion object {
        fun of(code: Int) = if (code == 1) CHARGE else DISCHARGE
    }
}

/**
 * A continuous plugged (CHARGE) or unplugged (DISCHARGE) period.
 * While open, [endTs] is null and [endSoc] holds the latest SOC seen.
 * [peakTs] is when the highest SOC of the session was first reached, i.e. for a charge
 * session the moment charging effectively finished (100% or the protection limit).
 */
data class Session(
    val id: Long = 0,
    val type: SessionType,
    val startTs: Long,
    val startSoc: Int,
    val endTs: Long? = null,
    val endSoc: Int,
    val plug: Int = Plug.NONE,
    val peakTs: Long,
    val minSoc: Int,
    val maxSoc: Int,
    val maxTemp: Int,
) {
    val open: Boolean get() = endTs == null

    /** Signed SOC change over the session (+ for charge, − for discharge). */
    val delta: Int get() = endSoc - startSoc

    fun durationMs(now: Long): Long = (endTs ?: now) - startTs

    /** Absolute SOC change per hour, or null when the session is too short to say. */
    fun ratePerHour(now: Long): Double? {
        val d = durationMs(now)
        if (d < 5 * 60_000L) return null
        return Math.abs(delta) * 3_600_000.0 / d
    }
}

/** Aggregates for one local calendar day. */
data class DayStat(
    val day: java.time.LocalDate,
    /** Sum of all SOC drops during the day; can exceed 100. */
    val usage: Int,
    /** Sum of all SOC rises during the day. */
    val charged: Int,
    val minSoc: Int?,
    val maxSoc: Int?,
)
