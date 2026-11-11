package dev.chargelog.core

/**
 * Discharge current ("how hard the phone is being used") as a step series in mA.
 *
 * Each interval between two observed readings while unplugged gets its average current from
 * the drop of the charge counter (µAh lost ÷ time), which is smooth and reflects real use.
 * Short intervals or devices without a counter fall back to the reported instantaneous current.
 * Charging stretches are not discharge and appear as gaps ([BREAK] markers).
 */
object Drain {
    const val BREAK = Int.MIN_VALUE
    private const val MIN_COUNTER_MS = 180_000L
    private const val MAX_MA = 8000

    /** Average discharge current between two readings, or null when it cannot be told. */
    fun rate(a: Sample, b: Sample): Int? {
        val dt = b.ts - a.ts
        if (dt <= 0) return null
        if (a.chargeUah > 0 && b.chargeUah > 0 && dt >= MIN_COUNTER_MS) {
            val dq = a.chargeUah - b.chargeUah
            if (dq >= 0) return Math.round(dq / (dt / 3_600_000.0) / 1000.0).toInt().takeIf { it in 0..MAX_MA }
        }
        val i = if (b.currentUa != 0) b.currentUa else a.currentUa
        return if (i != 0) (Math.abs(i) / 1000).takeIf { it <= MAX_MA } else null
    }

    /**
     * Rolling average ending at reading [i]: the charge-counter drop since the latest reading at
     * least [MIN_COUNTER_MS] earlier, without crossing a charging stretch. Keeps frequent
     * (periodic) readings smooth instead of falling back to the noisy instantaneous current.
     */
    private fun windowRate(obs: List<Sample>, i: Int): Int? {
        val b = obs[i]
        if (b.charging || b.chargeUah <= 0) return null
        var j = i - 1
        while (j >= 0) {
            val a = obs[j]
            if (a.charging || a.chargeUah <= 0) return null
            val dt = b.ts - a.ts
            if (dt >= MIN_COUNTER_MS) {
                val dq = a.chargeUah - b.chargeUah
                if (dq < 0) return null
                return Math.round(dq / (dt / 3_600_000.0) / 1000.0).toInt().takeIf { it in 0..MAX_MA }
            }
            j--
        }
        return null
    }

    /**
     * Step series over [samples] (ascending), continued to [now] with the [current] reading.
     * Two points per interval (start and end at the same value); [BREAK] where not discharging.
     */
    fun series(samples: List<Sample>, now: Long, current: Sample?): List<Pair<Long, Int>> {
        val obs = samples.filter { !it.est }.toMutableList()
        if (current != null && obs.isNotEmpty() && now > obs.last().ts) obs.add(current.copy(ts = now))
        val out = ArrayList<Pair<Long, Int>>()
        fun brk(ts: Long) { if (out.isNotEmpty() && out.last().second != BREAK) out.add(ts to BREAK) }
        for (i in 1 until obs.size) {
            val a = obs[i - 1]; val b = obs[i]
            val ma = if (a.charging) null else windowRate(obs, i) ?: rate(a, b)
            if (ma == null) { brk(a.ts); continue }
            out.add(a.ts to ma); out.add(b.ts to ma)
        }
        return out
    }

    /** Time-weighted average and maximum (mA) of a [series]; null when it is empty. */
    fun summary(series: List<Pair<Long, Int>>): Pair<Int, Int>? {
        var weighted = 0.0; var total = 0L; var max = -1
        for (i in 1 until series.size) {
            val (t0, v0) = series[i - 1]; val (t1, v1) = series[i]
            if (v0 == BREAK || v1 == BREAK || v0 != v1) continue
            weighted += v0.toDouble() * (t1 - t0); total += t1 - t0
            if (v0 > max) max = v0
        }
        if (total <= 0) return null
        return Math.round(weighted / total).toInt() to max
    }
}
