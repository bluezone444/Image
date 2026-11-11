package dev.chargelog.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.JsonWriter
import dev.chargelog.core.ChargeSpec
import dev.chargelog.core.DayStat
import dev.chargelog.core.Format
import dev.chargelog.core.GapFiller
import dev.chargelog.core.Ocv
import dev.chargelog.core.Plug
import dev.chargelog.core.Sample
import dev.chargelog.core.SessionType
import dev.chargelog.core.Stats
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportKind(val label: String, val fileStem: String, val mime: String, val ext: String) {
    ALL_ZIP("전체 상세 (ZIP: CSV 3종 + 메타데이터)", "chargelog_export", "application/zip", "zip"),
    ALL_JSON("전체 상세 (JSON 한 파일)", "chargelog_all", "application/json", "json"),
    SAMPLES_CSV("측정 기록 (CSV)", "chargelog_samples", "text/csv", "csv"),
    SESSIONS_CSV("충전·방전 세션 (CSV)", "chargelog_sessions", "text/csv", "csv"),
    DAILY_CSV("일별 요약 (CSV)", "chargelog_daily", "text/csv", "csv"),
}

/** Writes every recorded field. Nothing is aggregated away: samples carry all measured values. */
class Exporter(private val context: Context, private val db: Db, private val zone: ZoneId = ZoneId.systemDefault()) {

    fun write(kind: ExportKind, out: OutputStream) {
        when (kind) {
            ExportKind.ALL_ZIP -> zip(out)
            ExportKind.ALL_JSON -> writer(out).use { json(it) }
            ExportKind.SAMPLES_CSV -> writer(out).use { samplesCsv(it) }
            ExportKind.SESSIONS_CSV -> writer(out).use { sessionsCsv(it) }
            ExportKind.DAILY_CSV -> writer(out).use { dailyCsv(it) }
        }
    }

    private fun writer(out: OutputStream): Writer = OutputStreamWriter(out, Charsets.UTF_8).buffered()

    private fun zip(out: OutputStream) {
        ZipOutputStream(out).use { z ->
            fun entry(name: String, body: (Writer) -> Unit) {
                z.putNextEntry(ZipEntry(name))
                // Keep the zip open when the entry's writer is closed.
                val w = OutputStreamWriter(object : FilterOutputStream(z) {
                    override fun close() { flush() }
                    override fun write(b: ByteArray, off: Int, len: Int) { out0(b, off, len) }
                    private fun out0(b: ByteArray, off: Int, len: Int) = z.write(b, off, len)
                }, Charsets.UTF_8).buffered()
                w.use(body)
                z.closeEntry()
            }
            entry("samples.csv") { samplesCsv(it) }
            entry("sessions.csv") { sessionsCsv(it) }
            entry("daily.csv") { dailyCsv(it) }
            entry("meta.json") { w -> JsonWriter(w).apply { setIndent(" ") }.let { meta(it, standalone = true); it.flush() } }
            entry("README.txt") { it.write(README) }
        }
    }

    // ---- CSV ----

    private fun bom(w: Writer, header: String) { w.write("﻿"); w.write(header); w.write("\n") }

    private fun samplesCsv(w: Writer) {
        bom(w, Format.SAMPLE_HEADER)
        db.forEachSample { w.write(Format.sampleCsv(it, zone)); w.write("\n") }
    }

    private fun sessionsCsv(w: Writer) {
        bom(w, Format.SESSION_HEADER)
        db.forEachSession { w.write(Format.sessionCsv(it, zone, spec(it), events(it))); w.write("\n") }
    }

    private fun dailyCsv(w: Writer) {
        bom(w, Format.DAY_HEADER)
        val extra = dayExtras()
        allDays().forEach { d ->
            val e = extra[d.day]
            w.write(Format.dayCsv(d, e?.get(0) ?: 0, e?.get(1) ?: 0, e?.get(2) ?: 0)); w.write("\n")
        }
    }

    fun allDays(): List<DayStat> {
        val first = db.firstSampleTs() ?: return emptyList()
        val from = Instant.ofEpochMilli(first).atZone(zone).toLocalDate()
        return Stats.daily(db.samples(0, Long.MAX_VALUE), null, zone, from, LocalDate.now(zone))
    }

    /** Per day: [charge sessions started, readings, estimated readings]. */
    private fun dayExtras(): Map<LocalDate, IntArray> {
        val m = HashMap<LocalDate, IntArray>()
        fun day(ts: Long) = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        db.forEachSession { if (it.type == SessionType.CHARGE) m.getOrPut(day(it.startTs)) { IntArray(3) }[0]++ }
        db.forEachSample {
            val a = m.getOrPut(day(it.ts)) { IntArray(3) }
            a[1]++
            if (it.est) a[2]++
        }
        return m
    }

    // ---- JSON ----

    private fun meta(w: JsonWriter, standalone: Boolean) {
        if (standalone) w.beginObject()
        val now = System.currentTimeMillis()
        w.name("exported_at").value(Format.iso(now, zone))
        w.name("timezone").value(zone.id)
        w.name("app_version").value(appVersion())
        w.name("device").value("${Build.MANUFACTURER} ${Build.MODEL}")
        w.name("phone_max_wired_w").let { n -> phoneMax?.let { n.value(it.wiredW.toLong()) } ?: n.nullValue() }
        w.name("phone_max_wireless_w").let { n -> phoneMax?.wirelessW?.let { n.value(it.toLong()) } ?: n.nullValue() }
        w.name("android").value(Build.VERSION.RELEASE)
        w.name("sdk").value(Build.VERSION.SDK_INT.toLong())
        w.name("record_start").value(db.firstSampleTs()?.let { Format.iso(it, zone) })
        w.name("record_last").value(db.lastSample()?.ts?.let { Format.iso(it, zone) })
        w.name("sample_count").value(db.sampleCount())
        w.name("estimated_sample_count").value(db.estimatedCount())
        w.name("session_count").value(db.sessionCount())

        val fit = Recorder.rFit(context)
        w.name("internal_resistance").beginObject()
        w.name("ohms_used").value(fit.ohms)
        w.name("fitted_points").value(fit.count.toLong())
        w.name("is_default").value(fit.count < dev.chargelog.core.RFit.MIN_POINTS)
        w.name("points").beginArray(); fit.samples.forEach { w.value(it) }; w.endArray()
        w.endObject()

        val rates = GapFiller.rates(db.latestSessions(30), now)
        w.name("usual_rates_pct_per_hour").beginObject()
        w.name("discharge").value(rates.dischargePerHour)
        w.name("charge").value(rates.chargePerHour)
        w.endObject()

        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        w.name("ocv_probe").beginArray()
        VoltageProbe.report(sticky).forEach { (k, v) -> w.beginObject(); w.name("source").value(k); w.name("value").value(v); w.endObject() }
        w.endArray()
        w.name("battery_broadcast_snapshot").beginObject()
        sticky?.extras?.let { ex ->
            for (k in ex.keySet().sorted()) {
                @Suppress("DEPRECATION")
                w.name(k).value(ex.get(k)?.toString())
            }
        }
        w.endObject()
        w.name("columns").value(README)
        if (standalone) w.endObject()
    }

    private fun json(out: Writer) {
        val w = JsonWriter(out)
        w.setIndent(" ")
        w.beginObject()
        meta(w, standalone = false)

        w.name("samples").beginArray()
        db.forEachSample { s -> sampleJson(w, s) }
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
            if (end == null) { w.name("end_ts").nullValue(); w.name("end").nullValue() }
            else { w.name("end_ts").value(end); w.name("end").value(Format.iso(end, zone)) }
            w.name("end_soc").value(s.endSoc.toLong())
            w.name("delta_soc").value(s.delta.toLong())
            w.name("duration_min").value(s.durationMs(end ?: System.currentTimeMillis()) / 60_000L)
            s.ratePerHour(end ?: System.currentTimeMillis())?.let { w.name("rate_pct_per_hour").value(it) }
            if (s.type == SessionType.CHARGE) w.name("charger").value(Plug.label(s.plug))
            w.name("peak_soc_time").value(Format.iso(s.peakTs, zone))
            w.name("min_soc").value(s.minSoc.toLong())
            w.name("max_soc").value(s.maxSoc.toLong())
            w.name("max_temp_c").value(s.maxTemp / 10.0)
            w.name("estimated").value(s.est)
            events(s)?.let { ev ->
                ev.fullTs?.let { w.name("full_100_time").value(Format.iso(it, zone)) }
                ev.stopTs?.let { w.name("charge_stop_time").value(Format.iso(it, zone)) }
                ev.stopSoc?.let { w.name("charge_stop_soc").value(it.toLong()) }
                w.name("end_reason").value(Format.endReason(s, ev))
            }
            spec(s)?.let { r ->
                w.name("charge_tier").value(r.code())
                w.name("charge_tier_label").value(r.label())
                w.name("charge_tier_confident").value(r.confident)
                r.peakInputW?.let { w.name("peak_input_w").value(it) }
                r.reportedW?.let { w.name("charger_reported_w").value(it) }
            }
            w.endObject()
        }
        w.endArray()

        w.name("daily").beginArray()
        val extra = dayExtras()
        allDays().forEach { d ->
            val e = extra[d.day]
            w.beginObject()
            w.name("date").value(d.day.toString())
            w.name("usage_pct").value(d.usage.toLong())
            w.name("charged_pct").value(d.charged.toLong())
            if (d.minSoc == null) w.name("min_soc").nullValue() else w.name("min_soc").value(d.minSoc.toLong())
            if (d.maxSoc == null) w.name("max_soc").nullValue() else w.name("max_soc").value(d.maxSoc.toLong())
            w.name("charge_sessions").value((e?.get(0) ?: 0).toLong())
            w.name("readings").value((e?.get(1) ?: 0).toLong())
            w.name("estimated_readings").value((e?.get(2) ?: 0).toLong())
            w.endObject()
        }
        w.endArray()
        w.endObject()
        w.flush()
    }

    private fun sampleJson(w: JsonWriter, s: Sample) {
        w.beginObject()
        w.name("ts").value(s.ts)
        w.name("datetime").value(Format.iso(s.ts, zone))
        w.name("soc").value(s.level.toLong())
        w.name("plugged").value(Plug.label(s.plugged))
        w.name("status").value(Format.statusLabel(s.status))
        w.name("health").value(Format.healthLabel(s.health))
        w.name("temp_c").value(s.temp / 10.0)
        w.name("voltage_mv").value(s.voltage.toLong())
        if (s.currentUa != 0) w.name("current_ma").value(s.currentUa / 1000.0) else w.name("current_ma").nullValue()
        if (s.chargeUah > 0) w.name("charge_counter_mah").value(s.chargeUah / 1000.0) else w.name("charge_counter_mah").nullValue()
        if (s.ocv > 0) w.name("ocv_mv").value(s.ocv.toLong()) else w.name("ocv_mv").nullValue()
        w.name("ocv_source").value(Ocv.code(s.ocvSrc))
        if (s.maxChargeUa > 0) w.name("charger_max_ma").value(s.maxChargeUa / 1000.0)
        if (s.maxChargeUv > 0) w.name("charger_max_mv").value(s.maxChargeUv / 1000.0)
        if (s.cycles >= 0) w.name("cycle_count").value(s.cycles.toLong())
        w.name("estimated").value(s.est)
        w.endObject()
    }

    private val phoneMax by lazy { ChargeSpec.phone(Build.MODEL) }

    private fun events(s: dev.chargelog.core.Session): dev.chargelog.core.ChargeEvents? =
        if (s.type != SessionType.CHARGE) null
        else dev.chargelog.core.ChargeEvents.of(db.samples(s.startTs, (s.endTs ?: System.currentTimeMillis()) + 1))

    private fun spec(s: dev.chargelog.core.Session): ChargeSpec.Result? =
        if (s.type != SessionType.CHARGE) null
        else ChargeSpec.classify(db.samples(s.startTs, (s.endTs ?: System.currentTimeMillis()) + 1), s.startSoc, phoneMax)

    private fun appVersion(): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    companion object {
        val README = """
충전 로그 내보내기 — 열 설명
(시각은 ISO 8601, 기기 시간대 기준. 빈 칸은 기기가 보고하지 않은 값)

samples.csv — 측정 기록 (잔량·충전 상태·상태값·온도 1°C 변화마다 1행)
  timestamp_ms        유닉스 시각(ms)
  datetime            ISO 8601 시각
  soc_pct             배터리 잔량(%)
  plugged             none / ac / usb / wireless / estimated(추정 충전)
  status              charging / discharging / not_charging / full / unknown
  health              good / overheat / cold / ... (EXTRA_HEALTH)
  temp_c              배터리 온도(°C, EXTRA_TEMPERATURE)
  voltage_mv          단자 전압(mV, EXTRA_VOLTAGE "voltage", 부하 상태)
  current_ma          배터리 전류(mA, BATTERY_PROPERTY_CURRENT_NOW, 부호는 기기 보고 그대로)
  charge_counter_mah  남은 전하량(mAh, BATTERY_PROPERTY_CHARGE_COUNTER)
  ocv_mv              개방 전압 OCV(mV)
  ocv_source          direct = 기기 연료게이지 OCV 직접 값
                      rest = 휴지 전압(|전류| < 50mA일 때 단자 전압 ≈ OCV)
                      ir_model = 단자 전압 + 전류×내부저항(충전 중엔 −) 추정
  charger_max_ma/mv/w 충전기 최대 전류·전압·전력(max_charging_current/voltage 보고값)
  cycle_count         충전 사이클 수(Android 14+)
  estimated           1 = 기록이 끊긴 구간을 추정해 채운 값

sessions.csv — 충전(charge)·사용(discharge) 세션
  start/end, start_soc/end_soc, delta_soc, duration_min, rate_pct_per_hour,
  charger, peak_soc_time(최고 잔량 도달 시각), min_soc, max_soc, max_temp_c,
  estimated(추정 구간 포함 여부),
  peak_input_w(충전 중 배터리 입력 최고 W, 휴대폰 최대치를 넘는 비정상 값은 제외),
  charger_reported_w(충전기 보고 최대 W),
  charge_tier(유선: normal_10w / fast_15w / super_25w / super2_45w / super3_60w,
              무선: wireless_5w / 10w / 15w / 20w / 25w — 가장 가까운 단계, 휴대폰 최대치 이하.
              _min이 붙으면 잔량이 높을 때 시작해 그 이상일 수 있음.
              pending_short = 충전이 3분 미만이라 판정 보류, pending_idle = 충전 전류 없음)

daily.csv — 일별 요약
  usage_pct(하루 잔량 감소 합계, 100% 초과 가능), charged_pct, min_soc, max_soc,
  charge_sessions, readings(측정 행 수), estimated_readings

meta.json — 기기·앱 정보, 내부저항 추정값과 근거 데이터, 평소 충전·사용 속도,
  OCV 후보 경로 확인 결과, 내보낼 때의 배터리 브로드캐스트 전체 값
""".trimIndent()
    }
}
