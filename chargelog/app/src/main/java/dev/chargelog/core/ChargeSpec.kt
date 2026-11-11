package dev.chargelog.core

import java.util.Locale

/**
 * Samsung charging tiers and how a charge session is classified.
 *
 * Android does not say which Samsung mode is active, so the tier is inferred from what the
 * phone reports and measures during the session:
 * - the charger's negotiated maximum (max_charging_current × max_charging_voltage), and
 * - the peak power actually going into the battery (|current| × battery voltage), which is
 *   lower than the charger rating (conversion losses, ~85–90 %) and drops above ~70–80 % SOC.
 * The result never exceeds what the phone itself supports ([phoneMaxWatts]).
 */
object ChargeSpec {

    enum class Tier(val watts: Int, val label: String, val reportedMin: Double, val measuredMin: Double) {
        NORMAL(10, "일반 충전", 0.0, 0.0),
        FAST(15, "고속 충전 15W", 12.0, 9.5),
        SUPER(25, "초고속 충전 25W", 20.0, 16.0),
        SUPER2(45, "초고속 충전 2.0 45W", 35.0, 30.0),
        SUPER3(60, "초고속 충전 3.0 60W", 50.0, 44.0);

        companion object {
            fun forWatts(w: Int): Tier = values().lastOrNull { it.watts <= w } ?: NORMAL
        }
    }

    /**
     * Wired maximum by model prefix (Samsung model codes, region suffix ignored). Only models
     * whose rating could be confirmed are listed; others return null.
     */
    private val PHONE_MAX = linkedMapOf(
        "SM-S901" to 25, "SM-S906" to 45, "SM-S908" to 45,      // S22, S22+, S22 Ultra
        "SM-S911" to 25, "SM-S916" to 45, "SM-S918" to 45,      // S23 series
        "SM-S921" to 25, "SM-S926" to 45, "SM-S928" to 45,      // S24 series
        "SM-S721" to 25,                                         // S24 FE
        "SM-S931" to 25, "SM-S936" to 45, "SM-S938" to 45,      // S25 series
        "SM-S937" to 25,                                         // S25 Edge
        "SM-S948" to 60,                                         // S26 Ultra
        "SM-F946" to 25, "SM-F956" to 25, "SM-F966" to 25,      // Z Fold5/6/7
        "SM-F731" to 25, "SM-F741" to 25, "SM-F766" to 25,      // Z Flip5/6/7
        "SM-A356" to 25, "SM-A556" to 25,                        // A35, A55
        "SM-A266" to 25, "SM-A366" to 45, "SM-A566" to 45,      // A26, A36, A56
    )

    fun phoneMaxWatts(model: String): Int? {
        val m = model.uppercase(Locale.US)
        return PHONE_MAX.entries.firstOrNull { m.startsWith(it.key) }?.value
    }

    data class Result(
        val tier: Tier,
        /** Highest measured battery input in W, or null without current readings. */
        val peakInputW: Double?,
        /** Charger's reported maximum in W, or null when not reported. */
        val reportedW: Double?,
        /** True when the phone's own limit, not the charger, decided the tier. */
        val limitedByPhone: Boolean,
        /** False when the session began too full to reach full speed, so the tier may be low. */
        val confident: Boolean,
        val wireless: Boolean,
    ) {
        fun label(): String {
            if (wireless) return "무선 충전" + (peakInputW?.let { String.format(Locale.US, " · 최고 %.1fW", it) } ?: "")
            val base = tier.label + (if (limitedByPhone) " (휴대폰 최대)" else "")
            return if (confident) base else "$base 이상일 수 있음"
        }
    }

    /** Battery input power of one reading in W, or null without current/voltage. */
    fun inputWatts(s: Sample): Double? =
        if (s.currentUa == 0 || s.voltage <= 0) null else Math.abs(s.currentUa) / 1e6 * s.voltage / 1000.0

    /**
     * Classifies a charge session from its observed readings (est readings are ignored).
     * @param startSoc SOC at plug-in; above ~70 % the phone never reaches full speed
     */
    fun classify(readings: List<Sample>, startSoc: Int, phoneMaxW: Int?): Result? {
        val obs = readings.filter { !it.est && it.charging }
        if (obs.isEmpty()) return null
        val wireless = obs.any { it.plugged == Plug.WIRELESS }
        val charging = obs.filter { it.status == 2 }
        val peak = charging.mapNotNull { inputWatts(it) }.maxOrNull()
        val reported = obs.filter { it.maxChargeUa > 0 && it.maxChargeUv > 0 }
            .maxOfOrNull { it.maxChargeUa.toDouble() * it.maxChargeUv / 1e12 }

        var tier = Tier.NORMAL
        for (t in Tier.values()) {
            val byReport = reported != null && reported >= t.reportedMin
            val byMeasure = peak != null && peak >= t.measuredMin
            if (byReport || byMeasure) tier = t
        }
        var limited = false
        if (phoneMaxW != null && tier.watts > phoneMaxW) {
            tier = Tier.forWatts(phoneMaxW); limited = true
        }
        // Started high and nothing else showed the speed: the measurement cannot prove the tier.
        val confident = startSoc < 70 || reported != null || limited
        return Result(tier, peak, reported, limited, confident, wireless)
    }
}
