package dev.chargelog.core

/**
 * Open-circuit voltage (OCV) for the "전압(참조값)" readout.
 *
 * In priority order:
 * 1. [SRC_DIRECT] – a fuel-gauge OCV the device exposes (broadcast extra or sysfs).
 * 2. [SRC_REST]   – the terminal voltage while almost no current flows (|I| < [REST_A]); the
 *                   ohmic drop is then a few mV, so it is a measured near-OCV.
 * 3. [SRC_IR]     – OCV ≈ V + I·R while discharging (V − I·R while charging), with the internal
 *                   resistance R learned from this phone by [RFit].
 * Polarization is not modelled, so 2 and 3 are approximations and are labelled as such.
 */
object Ocv {
    const val SRC_NONE = 0
    const val SRC_DIRECT = 1
    const val SRC_REST = 2
    const val SRC_IR = 3

    /** Current below which the terminal voltage is taken as OCV. */
    const val REST_A = 0.05
    /** Typical phone cell plus contacts, used until enough points are fitted. */
    const val DEFAULT_R_OHM = 0.12

    fun label(src: Int): String = when (src) {
        SRC_DIRECT -> "OCV (직접)"
        SRC_REST -> "OCV 근사 (휴지 전압)"
        SRC_IR -> "OCV 추정 (V+I·R)"
        else -> "부하 전압"
    }

    fun code(src: Int): String = when (src) {
        SRC_DIRECT -> "direct"
        SRC_REST -> "rest"
        SRC_IR -> "ir_model"
        else -> ""
    }

    /** Reported current as µA: values that only make sense as mA (|x| < 20 000) are scaled. */
    fun toMicroAmps(raw: Int): Int =
        if (raw == Int.MIN_VALUE || raw == Int.MAX_VALUE) 0
        else if (Math.abs(raw) in 1 until 20_000) raw * 1000 else raw

    /**
     * Estimated OCV in mV and how it was obtained, or null without voltage or current.
     * The current's sign differs between devices, so its magnitude is used and the direction
     * comes from the charge status.
     */
    fun estimate(voltageMv: Int, currentUa: Int, chargingNow: Boolean, rOhm: Double): Pair<Int, Int>? {
        if (voltageMv <= 0 || currentUa == 0) return null
        val amps = Math.abs(currentUa) / 1e6
        if (amps < REST_A) return voltageMv to SRC_REST
        val drop = Math.round(amps * rOhm * 1000).toInt()
        return (if (chargingNow) voltageMv - drop else voltageMv + drop) to SRC_IR
    }
}

/**
 * Learns the battery's internal resistance from consecutive readings: at the same SOC, a few
 * seconds apart, while discharging, a jump in current ΔI moves the voltage by −R·ΔI.
 * Keeps the median of the recent accepted estimates.
 */
class RFit(initial: List<Double> = emptyList()) {
    private val values = ArrayList<Double>(initial.takeLast(MAX))
    private var prev: Point? = null

    private class Point(val ts: Long, val level: Int, val discharging: Boolean, val mv: Int, val amps: Double)

    val count: Int get() = values.size
    val samples: List<Double> get() = values.toList()

    /** Median of the fitted values once [MIN_POINTS] exist, else the default. */
    val ohms: Double
        get() = if (values.size < MIN_POINTS) Ocv.DEFAULT_R_OHM
        else values.sorted().let { it[it.size / 2] }

    /** @return true when the reading produced a new accepted estimate. */
    fun add(ts: Long, level: Int, discharging: Boolean, voltageMv: Int, currentUa: Int): Boolean {
        if (voltageMv <= 0 || currentUa == 0) return false
        val cur = Point(ts, level, discharging, voltageMv, Math.abs(currentUa) / 1e6)
        val p = prev
        prev = cur
        if (p == null || !p.discharging || !cur.discharging || p.level != cur.level) return false
        if (cur.ts - p.ts !in 1..MAX_GAP_MS) return false
        val di = cur.amps - p.amps
        if (Math.abs(di) < MIN_DI_A) return false
        val r = -(cur.mv - p.mv) / 1000.0 / di
        if (r !in R_RANGE) return false
        values.add(r)
        if (values.size > MAX) values.removeAt(0)
        return true
    }

    companion object {
        const val MIN_POINTS = 5
        const val MAX = 60
        const val MAX_GAP_MS = 180_000L
        const val MIN_DI_A = 0.15
        val R_RANGE = 0.02..0.5
    }
}
