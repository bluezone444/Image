package dev.chargelog.data

import android.os.Build
import android.util.JsonWriter
import dev.chargelog.core.DayStat
import dev.chargelog.core.Format
import dev.chargelog.core.Plug
import dev.chargelog.core.SessionType
import dev.chargelog.core.Stats
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ExportKind(val label: String, val fileStem: String, val mime: String, val ext: String) {
    SAMPLES_CSV("SOC 기록 (CSV)", "chargelog_samples", "text/csv", "csv"),
    SESSIONS_CSV("충전·방전 세션 (CSV)", "chargelog_sessions", "text/csv", "csv"),
    DAILY_CSV("일별 요약 (CSV)", "chargelog_daily", "text/csv", "csv"),
    ALL_JSON("전체 데이터 (JSON)", "chargelog_all", "application/json", "json"),
}

class Exporter(private val db: Db, private val zone: ZoneId = ZoneId.systemDefault()) {

    fun write(kind: ExportKind, out: OutputStream) {
        when (kind) {
            ExportKind.SAMPLES_CSV -> csv(out, Format.SAMPLE_HEADER) { line ->
                db.forEachSample { line(Format.sampleCsv(it, zone)) }
            }
            ExportKind.SESSIONS_CSV -> csv(out, Format.SESSION_HEADER) { line ->
                db.forEachSession { line(Format.sessionCsv(it, zone)) }
            }
            ExportKind.DAILY_CSV -> csv(out, Format.DAY_HEADER) { line ->
                allDays().forEach { line(Format.dayCsv(it)) }
            }
            ExportKind.ALL_JSON -> json(out)
        }
    }

    fun allDays(): List<DayStat> {
        val first = db.firstSampleTs() ?: return emptyList()
        val from = Instant.ofEpochMilli(first).atZone(zone).toLocalDate()
        return Stats.daily(db.samples(0, Long.MAX_VALUE), null, zone, from, LocalDate.now(zone))
    }

    private fun csv(out: OutputStream, header: String, body: ((String) -> Unit) -> Unit) {
        // BOM so spreadsheet apps detect UTF-8.
        OutputStreamWriter(out, Charsets.UTF_8).buffered().use { w ->
            w.write("﻿"); w.write(header); w.write("\n")
            body { w.write(it); w.write("\n") }
        }
    }

    private fun json(out: OutputStream) {
        JsonWriter(OutputStreamWriter(out, Charsets.UTF_8).buffered()).use { w ->
            w.setIndent(" ")
            w.beginObject()
            w.name("exported_at").value(Format.iso(System.currentTimeMillis(), zone))
            w.name("timezone").value(zone.id)
            w.name("device").value("${Build.MANUFACTURER} ${Build.MODEL}")
            w.name("android").value(Build.VERSION.RELEASE)

            w.name("samples").beginArray()
            db.forEachSample { s ->
                w.beginObject()
                w.name("ts").value(s.ts)
                w.name("datetime").value(Format.iso(s.ts, zone))
                w.name("soc").value(s.level.toLong())
                w.name("plugged").value(Plug.label(s.plugged))
                w.name("status").value(Format.statusLabel(s.status))
                w.name("temp_c").value(s.temp / 10.0)
                w.name("voltage_mv").value(s.voltage.toLong())
                if (s.ocv > 0) w.name("ocv_mv").value(s.ocv.toLong()) else w.name("ocv_mv").nullValue()
                w.name("estimated").value(s.est)
                w.endObject()
            }
            w.endArray()

            w.name("sessions").beginArray()
            db.forEachSession { s ->
                w.beginObject()
                w.name("id").value(s.id)
                w.name("type").value(s.type.name.lowercase())
                w.name("start_ts").value(s.startTs)
                w.name("start").value(Format.iso(s.startTs, zone))
                w.name("start_soc").value(s.startSoc.toLong())
                val end = s.endTs
                if (end == null) {
                    w.name("end_ts").nullValue(); w.name("end").nullValue()
                } else {
                    w.name("end_ts").value(end); w.name("end").value(Format.iso(end, zone))
                }
                w.name("end_soc").value(s.endSoc.toLong())
                w.name("delta_soc").value(s.delta.toLong())
                if (s.type == SessionType.CHARGE) w.name("charger").value(Plug.label(s.plug))
                w.name("peak_soc_time").value(Format.iso(s.peakTs, zone))
                w.name("min_soc").value(s.minSoc.toLong())
                w.name("max_soc").value(s.maxSoc.toLong())
                w.name("max_temp_c").value(s.maxTemp / 10.0)
                w.name("estimated").value(s.est)
                w.endObject()
            }
            w.endArray()

            w.name("daily").beginArray()
            allDays().forEach { d ->
                w.beginObject()
                w.name("date").value(d.day.toString())
                w.name("usage_pct").value(d.usage.toLong())
                w.name("charged_pct").value(d.charged.toLong())
                if (d.minSoc == null) w.name("min_soc").nullValue() else w.name("min_soc").value(d.minSoc.toLong())
                if (d.maxSoc == null) w.name("max_soc").nullValue() else w.name("max_soc").value(d.maxSoc.toLong())
                w.endObject()
            }
            w.endArray()
            w.endObject()
        }
    }
}
