package dev.chargelog.core

import java.util.Locale

/**
 * Samsung charging tiers and how a charge session is classified.
 *
 * Android does not say which Samsung mode is active, so the tier is inferred from what the
 * phone reports and measures during the session:
 * - the charger's negotiated maximum (max_charging_current × max_charging_voltage), and
 * - the peak power actually going into the battery (|current| × battery voltage), which is
 *   lower than the charger rating; it is divided by [EFFICIENCY] to estimate the charger side.
 * The estimate is matched to the nearest tier the phone supports ([phone]); it never exceeds
 * the phone's own maximum, and sessions too short to show the speed are not classified at all.
 */
object ChargeSpec {

    enum class Tier(val watts: Int, val label: String) {
        NORMAL(10, "일반 충전"),
        FAST(15, "고속 충전 15W"),
        SUPER(25, "초고속 충전 25W"),
        SUPER2(45, "초고속 충전 2.0 45W"),
        SUPER3(60, "초고속 충전 3.0 60W");

        companion object {
            fun forWatts(w: Int): Tier = values().lastOrNull { it.watts <= w } ?: NORMAL
        }
    }

    /** Wireless charging steps in W. */
    val WIRELESS_TIERS = listOf(5, 10, 15, 20, 25)

    /** Battery-side power ÷ this ≈ charger-side power (conversion losses). */
    const val EFFICIENCY = 0.85
    /** Charging must be observed for at least this long before a session is classified. */
    const val MIN_OBSERVED_MS = 3 * 60_000L
    /** Readings above this multiple of the phone's maximum are measurement errors. */
    const val BAD_READING_FACTOR = 1.5
    /** A lone reading this many times its neighbours is a glitch, not the charging speed. */
    private const val SPIKE_FACTOR = 3.0
    private const val SPIKE_MIN_W = 5.0
    /** Estimates up to this much over the phone's maximum are ordinary measurement spread. */
    private const val OVER_TOLERANCE = 1.1

    /** What a phone model supports. [wirelessW] is null when not confirmed. */
    data class Phone(val wiredW: Int, val wirelessW: Int? = null)

    /**
     * Maximums by model prefix (Samsung model codes, region suffix ignored). Only models whose
     * rating could be confirmed are listed; others return null.
     */
    private val PHONES = linkedMapOf(
        "SM-S901" to Phone(25), "SM-S906" to Phone(45), "SM-S908" to Phone(45),   // S22, S22+, S22 Ultra
        "SM-S911" to Phone(25), "SM-S916" to Phone(45), "SM-S918" to Phone(45),   // S23 series
        "SM-S921" to Phone(25), "SM-S926" to Phone(45), "SM-S928" to Phone(45),   // S24 series
        "SM-S721" to Phone(25),                                                    // S24 FE
        "SM-S931" to Phone(25), "SM-S936" to Phone(45), "SM-S938" to Phone(45),   // S25 series
        "SM-S937" to Phone(25),                                                    // S25 Edge
        "SM-S948" to Phone(60),                                                    // S26 Ultra
        "SM-F946" to Phone(25), "SM-F956" to Phone(25), "SM-F966" to Phone(25),   // Z Fold5/6/7
        "SM-F976" to Phone(45, 20),                                                // Z Fold8 Ultra (Qi 2.2 20W)
        "SM-F731" to Phone(25), "SM-F741" to Phone(25), "SM-F766" to Phone(25),   // Z Flip5/6/7
        "SM-A356" to Phone(25), "SM-A556" to Phone(25),                            // A35, A55
        "SM-A266" to Phone(25), "SM-A366" to Phone(45), "SM-A566" to Phone(45),   // A26, A36, A56
    )

    fun phone(model: String): Phone? {
        val m = model.uppercase(Locale.US)
        return PHONES.entries.firstOrNull { m.startsWith(it.key) }?.value
    }

    fun phoneMaxWatts(model: String): Int? = phone(model)?.wiredW

    data class Result(
        /** Wired tier; meaningless when [wireless] or [pending]. */
        val tier: Tier,
        /** Classified charging power in W (wired tier or wireless step); 0 when [pending]. */
        val watts: Int,
        /** Highest plausible measured battery input in W, or null without usable readings. */
        val peakInputW: Double?,
        /** Charger's reported maximum in W, or null when not reported (or implausible). */
        val reportedW: Double?,
        /** True when the readings pointed above the phone's maximum, which is shown instead. */
        val limitedByPhone: Boolean,
        /** False when the session began too full to reach full speed, so the tier may be low. */
        val confident: Boolean,
        val wireless: Boolean,
        /** True when charging was not observed long enough to tell the speed. */
        val pending: Boolean = false,
        /** With [pending]: true for a short session, false when no charging current was seen. */
        val short: Boolean = false,
    ) {
        fun label(): String {
            if (pending) return if (short) "판정 보류 (짧은 충전)" else "판정 보류 (충전 전류 없음)"
            val base = (if (wireless) "무선 ${watts}W" else tier.label)
            return base
        }

        /** Stable code for exports. */
        fun code(): String = when {
            pending -> if (short) "pending_short" else "pending_idle"
            wireless -> "wireless_${watts}w" + if (confident) "" else "_min"
            else -> "${tier.name.lowercase()}_${tier.watts}w" + if (confident) "" else "_min"
        }
    }

    /** Battery input power of one reading in W, or null without current/voltage. */
    fun inputWatts(s: Sample): Double? =
        if (s.currentUa == 0 || s.voltage <= 0) null else Math.abs(s.currentUa) / 1e6 * s.voltage / 1000.0

    /** The allowed value closest to [w]; ties go to the lower one. */
    fun nearest(w: Double, allowed: List<Int>): Int = allowed.minByOrNull { Math.abs(it - w) }!!

    /**
     * Highest measured input among plausible readings: values above [ceilingW] and lone spikes
     * (several times both neighbours) are dropped, e.g. a current reported in the wrong unit.
     */
    fun plausiblePeak(watts: List<Double>, ceilingW: Double): Double? {
        var peak: Double? = null
        for (i in watts.indices) {
            val w = watts[i]
            if (w > ceilingW) continue
            val around = listOfNotNull(watts.getOrNull(i - 1), watts.getOrNull(i + 1)).filter { it <= ceilingW }
            if (w >= SPIKE_MIN_W && around.isNotEmpty() && w > SPIKE_FACTOR * around.max()) continue
            if (peak == null || w > peak) peak = w
        }
        return peak
    }

    /**
     * Classifies a charge session from its observed readings (est readings are ignored).
     * @param startSoc SOC at plug-in; above ~70 % the phone never reaches full speed
     * @param phone what this phone supports, or null for an unlisted model
     */
    fun classify(readings: List<Sample>, startSoc: Int, phone: Phone?): Result? {
        val obs = readings.filter { !it.est && it.charging }
        if (obs.isEmpty()) return null
        val wireless = obs.any { it.plugged == Plug.WIRELESS }
        val charging = obs.filter { it.status == 2 }

        // Too little charging seen (just plugged in, or a few seconds on the charger): the
        // current is still ramping up, so any figure would be meaningless.
        val observedMs = if (charging.isEmpty()) 0L else charging.last().ts - charging.first().ts
        if (observedMs < MIN_OBSERVED_MS) {
            val short = obs.last().ts - obs.first().ts < MIN_OBSERVED_MS
            return Result(Tier.NORMAL, 0, null, null, false, false, wireless, pending = true, short = short)
        }

        val maxW = if (wireless) phone?.wirelessW else phone?.wiredW
        val allowed = (if (wireless) WIRELESS_TIERS else Tier.values().map { it.watts })
            .let { all -> if (maxW == null) all else all.filter { it <= maxW }.ifEmpty { listOf(all.first()) } }
        val ceiling = (maxW ?: allowed.last()) * BAD_READING_FACTOR

        val peak = plausiblePeak(charging.mapNotNull { inputWatts(it) }, ceiling)
        val reported = obs.filter { it.maxChargeUa > 0 && it.maxChargeUv > 0 }
            .map { it.maxChargeUa.toDouble() * it.maxChargeUv / 1e12 }
            .filter { it <= REPORTED_MAX_W }.maxOrNull()

        // Charger-side estimate: what the charger says it offers, or what went in plus losses.
        val estimate = maxOf(reported ?: 0.0, (peak ?: 0.0) / EFFICIENCY)
        val watts = nearest(estimate, allowed)
        val limited = maxW != null && estimate > maxW * OVER_TOLERANCE
        // Started high and nothing else showed the speed: the measurement cannot prove the tier.
        val confident = startSoc < 70 || reported != null || limited
        // A measured value above what the phone supports is not shown as a figure.
        val shownPeak = peak?.takeIf { maxW == null || it <= maxW }
        val tier = if (wireless) Tier.NORMAL else Tier.values().first { it.watts == watts }
        return Result(tier, watts, shownPeak, reported, limited, confident, wireless)
    }

    /** Above any phone charger; larger "reported" values are not watts from a real charger. */
    private const val REPORTED_MAX_W = 150.0
}
