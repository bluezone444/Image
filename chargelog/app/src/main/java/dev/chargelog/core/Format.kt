package dev.chargelog.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Format {
    private val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val mdhm = DateTimeFormatter.ofPattern("M/d HH:mm")

    fun iso(ts: Long, zone: ZoneId): String =
        iso.format(Instant.ofEpochMilli(ts).atZone(zone).toOffsetDateTime().withNano(0))

    fun time(ts: Long, zone: ZoneId): String = hm.format(Instant.ofEpochMilli(ts).atZone(zone))

    fun dateTime(ts: Long, zone: ZoneId): String = mdhm.format(Instant.ofEpochMilli(ts).atZone(zone))

    fun duration(ms: Long): String {
        val min = (ms / 60_000L).coerceAtLeast(0)
        val h = min / 60
        val m = min % 60
        return when {
            h == 0L -> "${m}분"
            m == 0L -> "${h}시간"
            else -> "${h}시간 ${m}분"
        }
    }

    fun signed(n: Int): String = if (n > 0) "+$n" else if (n < 0) "−${-n}" else "0"

    fun statusLabel(status: Int): String = when (status) {
        2 -> "charging"
        3 -> "discharging"
        4 -> "not_charging"
        5 -> "full"
        else -> "unknown"
    }

    // ---- CSV ----

    const val SAMPLE_HEADER = "timestamp_ms,datetime,soc_pct,plugged,status,temp_c,voltage_mv,ocv_mv"

    fun sampleCsv(s: Sample, zone: ZoneId): String =
        "${s.ts},${iso(s.ts, zone)},${s.level},${Plug.label(s.plugged)},${statusLabel(s.status)}," +
            "${tenths(s.temp)},${s.voltage},${if (s.ocv > 0) s.ocv.toString() else ""}"

    const val SESSION_HEADER =
        "id,type,start_ms,start,start_soc,end_ms,end,end_soc,delta_soc,duration_min,charger," +
            "peak_soc_time,min_soc,max_soc,max_temp_c"

    fun sessionCsv(s: Session, zone: ZoneId): String {
        val end = s.endTs
        return listOf(
            s.id, s.type.name.lowercase(), s.startTs, iso(s.startTs, zone), s.startSoc,
            end ?: "", end?.let { iso(it, zone) } ?: "", s.endSoc, s.delta,
            end?.let { (it - s.startTs) / 60_000L } ?: "",
            if (s.type == SessionType.CHARGE) Plug.label(s.plug) else "",
            iso(s.peakTs, zone), s.minSoc, s.maxSoc, tenths(s.maxTemp),
        ).joinToString(",")
    }

    const val DAY_HEADER = "date,usage_pct,charged_pct,min_soc,max_soc"

    fun dayCsv(d: DayStat): String =
        "${d.day},${d.usage},${d.charged},${d.minSoc ?: ""},${d.maxSoc ?: ""}"

    fun tenths(v: Int): String = "${v / 10}.${Math.abs(v % 10)}"
}
