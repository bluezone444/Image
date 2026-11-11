package dev.chargelog.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.chargelog.core.Plug
import dev.chargelog.data.VoltageProbe
import java.util.Locale

/**
 * Live charger diagnostics: shows everything the system reports about the current power
 * source, so we can see which values tell chargers (e.g. 25 W vs 65 W) apart on this phone.
 */
class ChargerActivity : Activity() {

    private lateinit var p: Palette
    private lateinit var summary: LinearLayout
    private lateinit var raw: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var last: Intent? = null
    /** Highest measured battery input power while this screen was open, in watts. */
    private var peakW = 0.0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { last = intent; refresh() }
    }

    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Palette(this)
        summary = column()
        raw = label("", 12f, p.ink2).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        val page = column().apply {
            val pad = dpi(16f)
            setPadding(pad, pad, pad, pad)
            addView(label("충전기 진단", 20f, p.ink, bold = true))
            addView(label("충전기를 연결한 채로 1분 정도 두면 값이 안정됩니다. 충전기마다 이 화면을 캡처해 비교하세요.",
                13f, p.ink2), lp(MATCH, WRAP, top = dpi(4f)))
            addView(card(p).apply { addView(summary) }, lp(MATCH, WRAP, top = dpi(12f)))
            addView(row().apply {
                addView(button("공유") { share() }, lp(0, WRAP, 1f))
                addView(button("최고값 초기화") { peakW = 0.0; refresh() }, lp(0, WRAP, 1f, left = dpi(8f)))
            }, lp(MATCH, WRAP, top = dpi(12f)))
            addView(card(p).apply {
                addView(label("시스템이 보고하는 전체 값", 15f, p.ink, bold = true))
                addView(raw, lp(MATCH, WRAP, top = dpi(8f)))
            }, lp(MATCH, WRAP, top = dpi(12f)))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(p.bg); addView(page) })
    }

    override fun onStart() {
        super.onStart()
        last = registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        unregisterReceiver(receiver)
        super.onStop()
    }

    private fun button(text: String, action: () -> Unit): TextView =
        label(text, 15f, p.ink, bold = true).apply {
            gravity = android.view.Gravity.CENTER
            background = roundRect(p.card, 14f)
            setPadding(dpi(12f), dpi(13f), dpi(12f), dpi(13f))
            setOnClickListener { action() }
        }

    private fun bm() = getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    private fun report(): Pair<List<Pair<String, String>>, String> {
        val i = last
        val bm = bm()
        val now = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val avg = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val mv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val maxUa = i?.getIntExtra("max_charging_current", -1) ?: -1
        val maxUv = i?.getIntExtra("max_charging_voltage", -1) ?: -1

        // CURRENT_NOW is specified in µA but some devices report mA; values under 20 000 are
        // taken as mA. Sign conventions also differ, so use the magnitude.
        val amps = Math.abs(now).let { if (it in 1 until 20_000) it / 1000.0 else it / 1_000_000.0 }
        val batteryW = if (plugged != 0 && now != Int.MIN_VALUE) amps * mv / 1000.0 else 0.0
        if (batteryW > peakW) peakW = batteryW
        val negotiatedW = if (maxUa > 0 && maxUv > 0) maxUa.toDouble() * maxUv / 1e12 else null

        val f = { v: Double -> String.format(Locale.US, "%.1f W", v) }
        val rows = listOf(
            "연결" to (if (plugged == 0) "없음" else Plug.labelKo(plugged)),
            "충전기 최대 (보고값)" to (negotiatedW?.let {
                "${f(it)}  (${String.format(Locale.US, "%.2f A × %.1f V", maxUa / 1e6, maxUv / 1e6)})"
            } ?: "보고 안 함"),
            "현재 배터리 입력" to (if (plugged == 0) "-" else f(batteryW)),
            "이 화면 최고 입력" to (if (peakW > 0) f(peakW) else "-"),
            "배터리 전압 (\"voltage\")" to "$mv mV",
            "전압(참조값)" to (VoltageProbe.reference(i)?.let {
                "${it.mv} mV · ${it.key}" + if (it.isOcv) " (OCV)" else " (부하 전압)"
            } ?: "-"),
            "전류 (CURRENT_NOW)" to "$now",
            "전류 (평균)" to "$avg",
        )

        val sb = StringBuilder()
        sb.append("[OCV 후보]\n")
        for ((k, v) in VoltageProbe.report(i)) sb.append(k).append(" = ").append(v).append('\n')
        sb.append("\n[배터리 브로드캐스트 전체]\n")
        val extras = i?.extras
        if (extras != null) {
            for (k in extras.keySet().sorted()) {
                @Suppress("DEPRECATION")
                sb.append(k).append(" = ").append(extras.get(k)).append('\n')
            }
        }
        sb.append("\nCURRENT_NOW = ").append(now)
            .append("\nCURRENT_AVERAGE = ").append(avg)
            .append("\nCHARGE_COUNTER = ").append(counter)
        val st = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        sb.append("\nPROPERTY_STATUS = ").append(st)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            sb.append("\ncomputeChargeTimeRemaining = ").append(bm.computeChargeTimeRemaining())
        }
        sb.append("\nmodel = ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
            .append(" / Android ").append(android.os.Build.VERSION.RELEASE)
        return rows to sb.toString()
    }

    private fun refresh() {
        val (rows, text) = report()
        summary.removeAllViews()
        rows.forEach { (k, v) -> summary.addView(kv(p, k, v)) }
        raw.text = text
    }

    private fun share() {
        val (rows, text) = report()
        val body = rows.joinToString("\n") { "${it.first}: ${it.second}" } + "\n\n" + text
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), "충전기 진단 공유"
        ))
    }
}
