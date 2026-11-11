package dev.chargelog.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    private val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val mdhm = DateTimeFormatter.ofPattern("M/d HH:mm")

    fun iso(ts: Long, zone: ZoneId): String =
        iso.format(Instant.ofEpochMilli(ts).atZone(zone).toOffsetDateTime().withNano(0))

    fun time(ts: Long, zone: ZoneId): String = hm.format(Instant.ofEpochMilli(ts).atZone(zone))

    fun dateTime(ts: Long, zone: ZoneId): String = mdhm.format(Instant.ofEpochMilli(ts).atZone(zone))

    private val mdhms = DateTimeFormatter.ofPattern("M/d HH:mm:ss")
    private val hms = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** "11/11 20:50:12". */
    fun dateTimeSec(ts: Long, zone: ZoneId): String = mdhms.format(Instant.ofEpochMilli(ts).atZone(zone))

    /** "20:50:12". */
    fun timeSec(ts: Long, zone: ZoneId): String = hms.format(Instant.ofEpochMilli(ts).atZone(zone))

    /** "1시간 39분 12초", "22분 5초", "48초". */
    fun durationSec(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0)
        val h = total / 3600; val m = (total % 3600) / 60; val sec = total % 60
        return listOfNotNull(
            if (h > 0) "${h}시간" else null,
            if (m > 0 || h > 0) "${m}분" else null,
            "${sec}초",
        ).joinToString(" ")
    }

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

    fun healthLabel(h: Int): String = when (h) {
        2 -> "good"; 3 -> "overheat"; 4 -> "dead"; 5 -> "over_voltage"; 6 -> "failure"; 7 -> "cold"
        1 -> "unknown"; else -> ""
    }

    private fun milli(micro: Int): String = if (micro == 0) "" else String.format(Locale.US, "%.1f", micro / 1000.0)

    const val SAMPLE_HEADER = "timestamp_ms,datetime,soc_pct,plugged,status,health,temp_c,voltage_mv," +
        "current_ma,charge_counter_mah,ocv_mv,ocv_source,charger_max_ma,charger_max_mv,charger_max_w," +
        "cycle_count,estimated"

    fun sampleCsv(s: Sample, zone: ZoneId): String = listOf(
        s.ts, iso(s.ts, zone), s.level, Plug.label(s.plugged), statusLabel(s.status), healthLabel(s.health),
        tenths(s.temp), s.voltage, milli(s.currentUa), milli(s.chargeUah),
        if (s.ocv > 0) s.ocv else "", Ocv.code(s.ocvSrc),
        milli(s.maxChargeUa), milli(s.maxChargeUv),
        if (s.maxChargeUa > 0 && s.maxChargeUv > 0) String.format(Locale.US, "%.1f", s.maxChargeUa.toDouble() * s.maxChargeUv / 1e12) else "",
        if (s.cycles >= 0) s.cycles else "", if (s.est) 1 else 0,
    ).joinToString(",")

    const val SESSION_HEADER =
        "id,type,start_ms,start,start_soc,end_ms,end,end_soc,delta_soc,duration_min,rate_pct_per_hour,charger," +
            "peak_soc_time,min_soc,max_soc,max_temp_c,estimated,peak_input_w,charger_reported_w,charge_tier"

    fun sessionCsv(s: Session, zone: ZoneId, spec: ChargeSpec.Result? = null): String {
        val end = s.endTs
        return listOf(
            s.id, s.type.name.lowercase(), s.startTs, iso(s.startTs, zone), s.startSoc,
            end ?: "", end?.let { iso(it, zone) } ?: "", s.endSoc, s.delta,
            end?.let { (it - s.startTs) / 60_000L } ?: "",
            end?.let { s.ratePerHour(it)?.let { r -> String.format(Locale.US, "%.2f", r) } } ?: "",
            if (s.type == SessionType.CHARGE) Plug.label(s.plug) else "",
            iso(s.peakTs, zone), s.minSoc, s.maxSoc, tenths(s.maxTemp), if (s.est) 1 else 0,
            spec?.peakInputW?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            spec?.reportedW?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            spec?.code() ?: "",
        ).joinToString(",")
    }

    const val DAY_HEADER = "date,usage_pct,charged_pct,min_soc,max_soc,charge_sessions,readings,estimated_readings"

    fun dayCsv(d: DayStat, charges: Int = 0, readings: Int = 0, estimated: Int = 0): String =
        "${d.day},${d.usage},${d.charged},${d.minSoc ?: ""},${d.maxSoc ?: ""},$charges,$readings,$estimated"

    fun tenths(v: Int): String = "${v / 10}.${Math.abs(v % 10)}"
}
