package dev.chargelog.core

/**
 * Estimates the readings missed while recording was interrupted (app force-stopped, killed
 * by the system, phone off).
 *
 * While recording, every 1 % SOC step is captured, so consecutive readings that are at least
 * [MIN_WINDOW_MS] apart yet differ by [MIN_JUMP] % or more mean data was missed. The gap is
 * rebuilt from the states on both sides and the user's usual rates:
 *
 * - discharging → discharging, SOC lower: linear discharge.
 * - discharging → SOC higher or plugged in: a missed charge. Discharge at the usual rate down to
 *   a low point, then charge at the usual rate, with the low point solved so the times add up.
 * - charging → unplugged: charge on (holding at 100 % if there is time), then discharge.
 * - charging → charging: linear.
 *
 * One estimated reading is produced per 1 % step plus one at each plug change; all have
 * `est = true` and lie strictly between the two real readings.
 */
object GapFiller {
    const val MIN_JUMP = 2
    const val MIN_WINDOW_MS = 5 * 60_000L
    private const val H = 3_600_000.0

    data class Rates(val dischargePerHour: Double, val chargePerHour: Double) {
        companion object {
            val DEFAULT = Rates(6.0, 40.0)
        }
    }

    /** Usual rates from the user's own completed, observed sessions (≥ 30 min). */
    fun rates(sessions: List<Session>, now: Long): Rates {
        val real = sessions.filter { !it.est && !it.open && it.durationMs(now) >= 30 * 60_000L }
        val d = real.filter { it.type == SessionType.DISCHARGE && it.delta < 0 }.mapNotNull { it.ratePerHour(now) }
        val c = real.filter { it.type == SessionType.CHARGE && it.delta > 0 }.mapNotNull { it.ratePerHour(now) }
        return Rates(
            if (d.isEmpty()) Rates.DEFAULT.dischargePerHour else d.average().coerceIn(1.0, 30.0),
            if (c.isEmpty()) Rates.DEFAULT.chargePerHour else c.average().coerceIn(10.0, 120.0),
        )
    }

    private class Seg(val t0: Long, val l0: Double, val t1: Long, val l1: Double, val charging: Boolean)

    /** True when the two consecutive readings show that readings were missed between them. */
    fun isGap(before: Sample, after: Sample): Boolean =
        Math.abs(after.level - before.level) >= MIN_JUMP && after.ts - before.ts >= MIN_WINDOW_MS

    fun fill(before: Sample, after: Sample, rates: Rates): List<Sample> {
        val a = before.level
        val b = after.level
        val t0 = before.ts
        val t1 = after.ts
        if (!isGap(before, after)) return emptyList()
        val hours = (t1 - t0) / H
        val rd = rates.dischargePerHour
        val rc = rates.chargePerHour

        val segs = when {
            !before.charging && b > a -> {
                // Missed charge: down to m, then up to b.
                val m = ((a / rd + b / rc - hours) / (1 / rd + 1 / rc)).coerceIn(0.0, a.toDouble())
                val tm = t0 + ((a - m) / rd * H).toLong().coerceIn(0, t1 - t0)
                listOf(Seg(t0, a.toDouble(), tm, m, false), Seg(tm, m, t1, b.toDouble(), true))
            }
            before.charging && !after.charging -> {
                // Unplugged during the gap: up to peak (maybe hold at 100), then down to b.
                val peak = ((hours + a / rc + b / rd) / (1 / rc + 1 / rd))
                    .coerceIn(maxOf(a, b).toDouble(), 100.0)
                var up = (peak - a) / rc * H
                var down = (peak - b) / rd * H
                val span = (t1 - t0).toDouble()
                if (up + down > span) { val k = span / (up + down); up *= k; down *= k }
                val tPeak = t0 + up.toLong()
                val tDown = t1 - down.toLong()
                listOf(
                    Seg(t0, a.toDouble(), tPeak, peak, true),
                    Seg(tPeak, peak, tDown, peak, true),
                    Seg(tDown, peak, t1, b.toDouble(), false),
                )
            }
            else -> listOf(Seg(t0, a.toDouble(), t1, b.toDouble(), before.charging))
        }

        val chargerPlug = after.plugged.takeIf { it != Plug.NONE }
            ?: before.plugged.takeIf { it != Plug.NONE } ?: Plug.ESTIMATED
        val out = ArrayList<Sample>()
        var lastTs = t0
        fun emit(ts: Long, level: Int, charging: Boolean) {
            val t = maxOf(ts, lastTs + 1)
            if (t >= t1) return
            val f = (t - t0).toDouble() / (t1 - t0)
            out.add(Sample(
                ts = t,
                level = level.coerceIn(0, 100),
                plugged = if (charging) chargerPlug else Plug.NONE,
                status = if (charging) 2 else 3,
                temp = Math.round(before.temp + (after.temp - before.temp) * f).toInt(),
                voltage = Math.round(before.voltage + (after.voltage - before.voltage) * f).toInt(),
                est = true,
            ))
            lastTs = t
        }

        var state = before.charging
        segs.forEachIndexed { i, s ->
            if (s.t1 <= s.t0 && i < segs.size - 1) { state = s.charging; return@forEachIndexed }
            // Plug change at the start of this segment.
            if (s.charging != state) emit(s.t0, Math.round(s.l0).toInt(), s.charging)
            state = s.charging
            val last = i == segs.size - 1
            val from = Math.round(s.l0).toInt()
            val to = Math.round(s.l1).toInt()
            if (from == to) return@forEachIndexed
            val step = if (to > from) 1 else -1
            var level = from + step
            while (if (step > 0) level <= to else level >= to) {
                if (last && level == b) break // the real reading closes the gap
                val f = (level - s.l0) / (s.l1 - s.l0)
                emit(s.t0 + ((s.t1 - s.t0) * f).toLong(), level, s.charging)
                level += step
            }
        }
        return out
    }
}
