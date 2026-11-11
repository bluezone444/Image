package dev.chargelog.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.chargelog.core.DayStat
import dev.chargelog.core.Format
import dev.chargelog.core.Plug
import dev.chargelog.core.Sample
import dev.chargelog.core.Session
import dev.chargelog.core.SessionType
import dev.chargelog.core.Stats
import dev.chargelog.data.Db
import dev.chargelog.data.ExportKind
import dev.chargelog.data.Exporter
import dev.chargelog.data.Recorder
import dev.chargelog.service.MonitorService
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var p: Palette
    private lateinit var db: Db
    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private var homeScroll: ScrollView? = null

    private var tab = 0
    private var weekOffset = 0
    private var selectedDay: LocalDate? = null
    private var current: Sample? = null

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            current = Recorder.record(context, intent) ?: current
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Palette(this)
        db = Db.get(this)
        tab = savedInstanceState?.getInt("tab") ?: 0

        content = FrameLayout(this)
        tabBar = row().apply { setBackgroundColor(p.card) }
        setContentView(column().apply {
            setBackgroundColor(p.bg)
            addView(content, lp(MATCH, 0, 1f))
            addView(tabBar, lp(MATCH, dpi(56f)))
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(NOTIF_PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(NOTIF_PERMISSION), 1)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onStart() {
        super.onStart()
        try {
            MonitorService.start(this)
        } catch (e: RuntimeException) {
            Toast.makeText(this, "기록 서비스를 시작하지 못했습니다", Toast.LENGTH_LONG).show()
        }
        // Sticky: delivers the current state right away, then every change while visible.
        val sticky = registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) current = Recorder.record(this, sticky) ?: current
        render()
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        super.onStop()
    }

    // ------------------------------------------------------------------ shell

    private fun render() {
        renderTabs()
        when (tab) {
            0 -> renderHome()
            1 -> renderSessions()
            else -> renderData()
        }
    }

    private fun renderTabs() {
        tabBar.removeAllViews()
        listOf("홈", "세션", "데이터").forEachIndexed { i, name ->
            val on = i == tab
            tabBar.addView(label(name, 15f, if (on) p.ink else p.muted, bold = on).apply {
                gravity = Gravity.CENTER
                setOnClickListener { if (tab != i) { tab = i; render() } }
            }, lp(0, MATCH, 1f))
        }
    }

    private fun page(): LinearLayout = column().apply {
        val pad = dpi(16f)
        setPadding(pad, pad, pad, pad)
    }

    private fun title(text: String): TextView = label(text, 17f, p.ink, bold = true)

    // ------------------------------------------------------------------ home

    private fun renderHome() {
        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()
        val weekEnd = today.minusDays(7L * weekOffset)
        val weekStart = weekEnd.minusDays(6)
        val day = selectedDay?.takeIf { !it.isBefore(weekStart) && !it.isAfter(weekEnd) } ?: weekEnd
        selectedDay = day

        // Two weeks in one pass: the earlier week only feeds the comparison average.
        val rangeStart = weekStart.minusDays(7)
        val from = Stats.dayStart(rangeStart, zone)
        val to = Stats.dayStart(weekEnd.plusDays(1), zone)
        val all = Stats.daily(db.samples(from, to), db.sampleBefore(from), zone, rangeStart, weekEnd)
        val prevWeek = all.take(7).filter { it.minSoc != null }
        val average = if (prevWeek.isEmpty()) null else prevWeek.sumOf { it.usage } / prevWeek.size
        val week = all.drop(7)
        val todayStat = if (weekOffset == 0) week.last() else dayStat(today)

        val page = page()
        page.addView(hero(todayStat, now))
        page.addView(sessionCard(now), lp(MATCH, WRAP, top = dpi(12f)))
        page.addView(weekCard(week, average, week.indexOfFirst { it.day == day }, today),
            lp(MATCH, WRAP, top = dpi(12f)))
        page.addView(dayCard(week.first { it.day == day }, now), lp(MATCH, WRAP, top = dpi(12f)))

        val scroll = homeScroll ?: ScrollView(this).also { homeScroll = it }
        scroll.removeAllViews()
        scroll.addView(page)
        if (scroll.parent == null) {
            content.removeAllViews()
            content.addView(scroll)
        }
    }

    private fun dayStat(day: LocalDate): DayStat {
        val from = Stats.dayStart(day, zone)
        val to = Stats.dayStart(day.plusDays(1), zone)
        return Stats.daily(db.samples(from, to), db.sampleBefore(from), zone, day, day).first()
    }

    private fun hero(today: DayStat, now: Long): View = column().apply {
        val s = current
        setPadding(dpi(6f), dpi(8f), dpi(6f), dpi(4f))
        addView(row().apply {
            addView(label(if (s == null) "--%" else "${s.level}%", 52f, p.ink, bold = true), lp(0, WRAP, 1f))
            val state = when {
                s == null -> "측정 대기"
                s.charging && s.status == 5 -> "충전 완료 · ${Plug.labelKo(s.plugged)}"
                s.charging -> "충전 중 · ${Plug.labelKo(s.plugged)}"
                else -> "사용 중"
            }
            addView(label(state, 15f, p.ink2))
        })
        addView(LevelBar(context, p).apply { level = s?.level ?: 0 }, lp(MATCH, WRAP, top = dpi(6f)))
        addView(row().apply {
            addView(tile(p, "오늘 사용량", "${today.usage}%"), lp(0, WRAP, 1f))
            addView(tile(p, "오늘 충전량", "${today.charged}%"), lp(0, WRAP, 1f))
            addView(tile(p, "최저", today.minSoc?.let { "$it%" } ?: "-"), lp(0, WRAP, 1f))
            addView(tile(p, "최고", today.maxSoc?.let { "$it%" } ?: "-"), lp(0, WRAP, 1f))
        }, lp(MATCH, WRAP, top = dpi(16f)))
        if (s != null) {
            addView(label("배터리 온도 ${Format.tenths(s.temp)}°C · 전압 ${s.voltage}mV", 12f, p.muted),
                lp(MATCH, WRAP, top = dpi(10f)))
        }
    }

    private fun sessionCard(now: Long): View = card(p).apply {
        val recent = db.latestSessions(3)
        val open = recent.firstOrNull { it.open }
        if (open == null) {
            addView(title("현재 세션"))
            addView(label("아직 기록이 없습니다.", 14f, p.ink2), lp(MATCH, WRAP, top = dpi(6f)))
            return@apply
        }
        val charge = open.type == SessionType.CHARGE
        addView(title(if (charge) "현재 세션 · 충전 (${Plug.labelKo(open.plug)})" else "현재 세션 · 사용"))
        addView(kv(p, "시작", "${Format.dateTime(open.startTs, zone)} · ${open.startSoc}%"),
            lp(MATCH, WRAP, top = dpi(8f)))
        addView(kv(p, "현재", "${open.endSoc}% (${Format.signed(open.delta)}%)"))
        addView(kv(p, "경과", Format.duration(open.durationMs(now))))
        open.ratePerHour(now)?.let {
            addView(kv(p, if (charge) "평균 충전 속도" else "평균 소모 속도", String.format(Locale.US, "%.1f%%/시간", it)))
        }
        if (charge && open.maxSoc > open.startSoc && now - open.peakTs > 20 * 60_000L) {
            addView(kv(p, "최고 SOC 도달", "${Format.time(open.peakTs, zone)} · ${open.maxSoc}%"))
        }
        recent.firstOrNull { !it.open }?.let { prev ->
            addView(View(context).apply { setBackgroundColor(p.grid) }, lp(MATCH, dpi(1f), top = dpi(10f)))
            addView(label(if (prev.type == SessionType.CHARGE) "직전 충전" else "직전 사용", 12f, p.ink2),
                lp(MATCH, WRAP, top = dpi(10f)))
            addView(label(sessionLine1(prev), 14f, p.ink, bold = true), lp(MATCH, WRAP, top = dpi(2f)))
            addView(label(sessionLine2(prev, now), 13f, p.ink2))
        }
    }

    private fun weekCard(week: List<DayStat>, average: Int?, selected: Int, today: LocalDate): View =
        card(p).apply {
            val fmt = DateTimeFormatter.ofPattern("M/d")
            addView(row().apply {
                addView(title("일일 사용량"), lp(0, WRAP, 1f))
                addView(navButton("‹") { weekOffset++; selectedDay = null; render() })
                addView(label("${fmt.format(week.first().day)} – ${fmt.format(week.last().day)}", 13f, p.ink2)
                    .apply { setPadding(dpi(4f), 0, dpi(4f), 0) })
                addView(navButton("›", enabled = weekOffset > 0) { weekOffset--; selectedDay = null; render() })
            })
            addView(BarChartView(context, p).apply {
                set(week, average, selected)
                onSelect = { i ->
                    if (!week[i].day.isAfter(today)) { selectedDay = week[i].day; render() }
                }
            }, lp(MATCH, WRAP, top = dpi(8f)))
            val total = week.sumOf { it.usage }
            addView(label(
                "이번 주 합계 ${total}%" + (average?.let { " · 지난주 하루 평균 ${it}% (점선)" } ?: ""),
                13f, p.ink2
            ), lp(MATCH, WRAP, top = dpi(4f)))
        }

    private fun navButton(text: String, enabled: Boolean = true, action: () -> Unit): TextView =
        label(text, 22f, if (enabled) p.ink else p.grid, bold = true).apply {
            gravity = Gravity.CENTER
            minimumWidth = dpi(40f)
            minimumHeight = dpi(40f)
            if (enabled) setOnClickListener { action() }
        }

    private fun dayCard(stat: DayStat, now: Long): View = card(p).apply {
        val day = stat.day
        val start = Stats.dayStart(day, zone)
        val end = Stats.dayStart(day.plusDays(1), zone)
        val sessions = db.sessionsOverlapping(start, end)
        val points = ArrayList<Sample>()
        db.sampleBefore(start)?.let { points.add(it.copy(ts = start)) }
        points.addAll(db.samples(start, end))

        addView(title(DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN).format(day)))
        addView(row().apply {
            addView(tile(p, "사용량", "${stat.usage}%"), lp(0, WRAP, 1f))
            addView(tile(p, "충전량", "${stat.charged}%"), lp(0, WRAP, 1f))
            addView(tile(p, "최저 SOC", stat.minSoc?.let { "$it%" } ?: "-"), lp(0, WRAP, 1f))
            addView(tile(p, "최고 SOC", stat.maxSoc?.let { "$it%" } ?: "-"), lp(0, WRAP, 1f))
        }, lp(MATCH, WRAP, top = dpi(10f)))

        if (points.isEmpty()) {
            addView(label("이 날의 기록이 없습니다.", 14f, p.ink2), lp(MATCH, WRAP, top = dpi(12f)))
            return@apply
        }
        addView(SocChartView(context, p).apply { set(points, sessions, start, end, now, zone) },
            lp(MATCH, WRAP, top = dpi(8f)))
        addView(label("SOC(%) · 음영은 충전 구간 · 그래프를 누르면 시각별 값", 12f, p.muted))

        val charges = sessions.count { it.type == SessionType.CHARGE }
        addView(label("세션 ${sessions.size}개 (충전 ${charges}회)", 13f, p.ink2), lp(MATCH, WRAP, top = dpi(14f)))
        for (s in sessions.asReversed()) addView(sessionRow(s, now), lp(MATCH, WRAP, top = dpi(10f)))
    }

    // -------------------------------------------------------------- sessions

    private fun sessionLine1(s: Session): String {
        val kind = if (s.type == SessionType.CHARGE) "충전" else "사용"
        return "$kind  ${s.startSoc}% → ${s.endSoc}%  (${Format.signed(s.delta)}%)"
    }

    private fun sessionLine2(s: Session, now: Long): String {
        val endTs = s.endTs
        val span = Format.dateTime(s.startTs, zone) + " → " +
            (if (endTs == null) "진행 중" else Format.dateTime(endTs, zone))
        val parts = arrayListOf(span, Format.duration(s.durationMs(now)))
        s.ratePerHour(now)?.let { parts.add(String.format(Locale.US, "%.1f%%/시간", it)) }
        if (s.type == SessionType.CHARGE) {
            parts.add(Plug.labelKo(s.plug))
            val settled = (endTs ?: now) - s.peakTs > 20 * 60_000L
            if (settled && s.maxSoc > s.startSoc) parts.add("${s.maxSoc}% 도달 ${Format.time(s.peakTs, zone)}")
        }
        return parts.joinToString(" · ")
    }

    private fun sessionRow(s: Session, now: Long): View = row().apply {
        // Shape + label carry the type; color only reinforces it.
        val charge = s.type == SessionType.CHARGE
        addView(View(context).apply {
            background = roundRect(if (charge) p.accent else p.muted, 2f)
        }, lp(dpi(4f), dpi(34f)))
        addView(column().apply {
            addView(label(sessionLine1(s), 14f, p.ink, bold = true))
            addView(label(sessionLine2(s, now), 12f, p.ink2))
        }, lp(0, WRAP, 1f, left = dpi(10f)))
    }

    private fun renderSessions() {
        val now = System.currentTimeMillis()
        val items = db.latestSessions(500)
        val list = ListView(this).apply {
            divider = null
            clipToPadding = false
            setPadding(dpi(16f), dpi(8f), dpi(16f), dpi(16f))
            adapter = object : BaseAdapter() {
                override fun getCount() = items.size
                override fun getItem(i: Int) = items[i]
                override fun getItemId(i: Int) = items[i].id
                override fun getView(i: Int, convert: View?, parent: ViewGroup): View =
                    FrameLayout(context).apply {
                        setPadding(0, dpi(4f), 0, dpi(4f))
                        addView(card(p).apply {
                            val pad = dpi(14f)
                            setPadding(pad, pad, pad, pad)
                            addView(sessionRow(items[i], now))
                        })
                    }
            }
        }
        val page = column().apply {
            addView(title("충전·사용 세션").apply { setPadding(dpi(22f), dpi(20f), dpi(22f), dpi(4f)) })
            if (items.isEmpty()) {
                addView(label("아직 기록된 세션이 없습니다.", 14f, p.ink2).apply {
                    setPadding(dpi(22f), dpi(8f), dpi(22f), 0)
                })
            } else {
                addView(list, lp(MATCH, 0, 1f))
            }
        }
        homeScroll = null
        content.removeAllViews()
        content.addView(page)
    }

    // ------------------------------------------------------------------ data

    private fun renderData() {
        val page = page()

        page.addView(card(p).apply {
            addView(title("기록 상태"))
            val first = db.firstSampleTs()
            addView(kv(p, "기록 시작", first?.let { Format.dateTime(it, zone) } ?: "-"), lp(MATCH, WRAP, top = dpi(8f)))
            addView(kv(p, "SOC 기록 수", db.sampleCount().toString()))
            addView(kv(p, "세션 수", db.sessionCount().toString()))
            val pm = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            val exempt = pm.isIgnoringBatteryOptimizations(packageName)
            addView(kv(p, "배터리 최적화 제외", if (exempt) "설정됨" else "미설정"))
            if (!exempt) {
                addView(label("제외하지 않으면 시스템이 기록을 중단시켜 구간이 비어 보일 수 있습니다.", 12f, p.ink2))
                addView(button("배터리 최적화에서 제외") {
                    try {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")))
                    } catch (e: RuntimeException) {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }, lp(MATCH, WRAP, top = dpi(10f)))
            }
        })

        page.addView(card(p).apply {
            addView(title("내보내기"))
            addView(label("저장할 위치를 고르면 파일로 저장됩니다. 시각은 ISO 8601 형식입니다.", 13f, p.ink2),
                lp(MATCH, WRAP, top = dpi(4f)))
            for (kind in ExportKind.values()) {
                addView(button(kind.label) { startExport(kind) }, lp(MATCH, WRAP, top = dpi(10f)))
            }
        }, lp(MATCH, WRAP, top = dpi(12f)))

        page.addView(card(p).apply {
            addView(title("초기화"))
            addView(button("모든 기록 삭제", danger = true) {
                AlertDialog.Builder(context)
                    .setTitle("모든 기록을 삭제할까요?")
                    .setMessage("SOC 기록과 세션이 모두 지워지며 되돌릴 수 없습니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("삭제") { _, _ ->
                        db.clearAll()
                        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                            ?.let { Recorder.record(context, it) }
                        render()
                    }
                    .show()
            }, lp(MATCH, WRAP, top = dpi(10f)))
        }, lp(MATCH, WRAP, top = dpi(12f)))

        homeScroll = null
        content.removeAllViews()
        content.addView(ScrollView(this).apply { addView(page) })
    }

    private fun button(text: String, danger: Boolean = false, action: () -> Unit): TextView =
        label(text, 15f, if (danger) p.danger else p.ink, bold = true).apply {
            gravity = Gravity.CENTER
            background = roundRect(p.bg, 14f)
            setPadding(dpi(12f), dpi(13f), dpi(12f), dpi(13f))
            setOnClickListener { action() }
        }

    private fun startExport(kind: ExportKind) {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").format(java.time.LocalDateTime.now(zone))
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(kind.mime)
            .putExtra(Intent.EXTRA_TITLE, "${kind.fileStem}_$stamp.${kind.ext}")
        try {
            startActivityForResult(intent, REQ_EXPORT + kind.ordinal)
        } catch (e: RuntimeException) {
            Toast.makeText(this, "파일 저장 화면을 열 수 없습니다", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val kind = ExportKind.values().getOrNull(requestCode - REQ_EXPORT) ?: return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        Thread {
            val message = try {
                val out = contentResolver.openOutputStream(uri) ?: throw java.io.IOException("no stream")
                out.use { Exporter(db, zone).write(kind, it) }
                "저장했습니다: ${kind.label}"
            } catch (e: Exception) {
                "저장 실패: ${e.message}"
            }
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }.start()
    }

    companion object {
        private const val REQ_EXPORT = 100
        private const val NOTIF_PERMISSION = "android.permission.POST_NOTIFICATIONS"
    }
}
