package dev.chargelog.core

/**
 * How often a reading is stored regardless of change. While the screen is on the phone is
 * awake anyway, so short intervals cost almost nothing; with the screen off each reading needs
 * a wake-up, so the interval is longer.
 */
enum class Cadence(val label: String, val awakeMs: Long, val sleepMs: Long) {
    DENSE("촘촘", 30_000L, 5 * 60_000L),
    STANDARD("표준", 60_000L, 5 * 60_000L),
    SAVER("절약", 5 * 60_000L, 15 * 60_000L);

    /** Rough stored readings per day for a typical 5 h of screen-on time. */
    fun rowsPerDay(screenOnHours: Double = 5.0): Int =
        (screenOnHours * 3_600_000 / awakeMs + (24 - screenOnHours) * 3_600_000 / sleepMs).toInt()

    fun describe(): String = "화면 켜짐 ${span(awakeMs)} · 꺼짐 ${span(sleepMs)}"

    companion object {
        val DEFAULT = STANDARD
        /** Periodic readings closer than this to the previous stored one are skipped. */
        const val MIN_GAP_MS = 20_000L

        fun of(name: String?): Cadence = values().firstOrNull { it.name == name } ?: DEFAULT

        private fun span(ms: Long) = if (ms < 60_000L) "${ms / 1000}초" else "${ms / 60_000L}분"
    }
}
