package dev.chargelog.core

/**
 * What happened inside one charge session, from its stored readings:
 * - [fullTs]: first reading at 100 % SOC.
 * - [stopTs]/[stopSoc]: first reading, after charging was seen, where the phone was still plugged
 *   in but no longer charging (status FULL, or NOT_CHARGING such as battery protection or
 *   adaptive charging holding at 80–85 %).
 * - the unplug time is the session's own end ([Session.endTs]); sessions end only on unplug.
 * Status and SOC changes are stored the moment they are reported, so these times are as precise
 * as the system's battery broadcasts.
 */
data class ChargeEvents(val fullTs: Long?, val stopTs: Long?, val stopSoc: Int?, val stopFull: Boolean) {

    /** Time the charger stayed connected after charging stopped, or null. */
    fun heldMs(s: Session, now: Long): Long? = stopTs?.let { (s.endTs ?: now) - it }

    companion object {
        const val STATUS_CHARGING = 2
        const val STATUS_NOT_CHARGING = 4
        const val STATUS_FULL = 5

        fun of(readings: List<Sample>): ChargeEvents {
            val obs = readings.filter { !it.est }.sortedBy { it.ts }
            val full = obs.firstOrNull { it.level >= 100 }?.ts
            var sawCharging = false
            var stop: Sample? = null
            for (r in obs) {
                if (!r.charging) continue
                if (r.status == STATUS_CHARGING) { sawCharging = true; continue }
                if (sawCharging && (r.status == STATUS_FULL || r.status == STATUS_NOT_CHARGING)) { stop = r; break }
            }
            return ChargeEvents(full, stop?.ts, stop?.level, stop != null && (stop.status == STATUS_FULL || stop.level >= 100))
        }
    }
}
