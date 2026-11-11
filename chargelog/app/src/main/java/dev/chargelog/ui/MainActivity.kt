package dev.chargelog.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
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
import android.widget.ImageView
import dev.chargelog.R
import dev.chargelog.core.DayStat
import dev.chargelog.core.Format
import dev.chargelog.core.Plug
import dev.chargelog.core.Sample
import dev.chargelog.core.Session
import dev.chargelog.core.SessionType
import dev.chargelog.core.Stats
import dev.chargelog.core.Summaries
import dev.chargelog.data.Db
import dev.chargelog.data.ExportKind
import dev.chargelog.data.Exporter
import dev.chargelog.data.Recorder
import dev.chargelog.service.MonitorService
import dev.chargelog.service.Watchdog
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
    private var sessionList: ListView? = null

    private var tab = 0
    private var weekOffset = 0
    private var statsDays = 30
    private var pageTab = -1
    private var receiverOn = false
    private var askedNotif = false
    private var selectedDay: LocalDate? = null
    private var current: Sample? = null

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val prev = current
            val s = Recorder.record(context, intent) ?: return
            current = s
            // ACTION_BATTERY_CHANGED also fires for every temperature/voltage wobble; only
            // rebuild the screen when something it shows has changed.
            if (prev == null || prev.level != s.level || prev.plugged != s.plugged ||
                prev.status != s.status || (tab == 0 && prev.temp / 10 != s.temp / 10)
            ) render()
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

    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onStart() {
        super.onStart()
        // Nothing is recorded until the cover notice has been accepted once.
        if (!coverAccepted()) showCover(firstRun = true) else startMonitoring()
    }

    private fun startMonitoring() {
        tabBar.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            checkSelfPermission(NOTIF_PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotif = true
            requestPermissions(arrayOf(NOTIF_PERMISSION), 1)
        }
        try {
            MonitorService.start(this)
        } catch (e: RuntimeException) {
            Toast.makeText(this, "기록 서비스를 시작하지 못했습니다", Toast.LENGTH_LONG).show()
        }
        // Sticky: delivers the current state right away, then every change while visible.
        val sticky = registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        receiverOn = true
        if (sticky != null) current = Recorder.record(this, sticky) ?: current
        Watchdog.schedule(this)
        render()
        askExemptionOnce()
    }

    // ------------------------------------------------------ keep-alive setup

    private fun isExempt(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    /** System dialog; on Samsung, allowing it sets the app's battery usage to "제한 없음". */
    private fun requestExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        } catch (e: RuntimeException) {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: RuntimeException) {
            Toast.makeText(this, "설정 화면을 열 수 없습니다", Toast.LENGTH_LONG).show()
        }
    }

    private fun askExemptionOnce() {
        if (isExempt()) return
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("asked_exemption", false)) return
        prefs.edit().putBoolean("asked_exemption", true).apply()
        AlertDialog.Builder(this)
            .setTitle("계속 기록하려면")
            .setMessage("휴대폰이 이 앱을 절전 상태로 보내면 충전·방전 기록이 끊깁니다.\n\n" +
                "다음 화면에서 '허용'을 누르면 배터리 최적화에서 제외되어(삼성: 배터리 '제한 없음') " +
                "앱을 닫아도 계속 기록합니다. 추가 배터리 소모는 거의 없습니다.")
            .setNegativeButton("나중에", null)
            .setPositiveButton("설정") { _, _ -> requestExemption() }
            .show()
    }

    /** Shown on top of Home while recording can still be stopped by the system. */
    private fun keepAliveBanner(): View = card(p).apply {
        addView(label("⚠ 기록이 끊길 수 있습니다", 15f, p.ink, bold = true))
        addView(label("배터리 최적화에서 제외되지 않아 휴대폰이 이 앱을 절전 상태로 보낼 수 있습니다.",
            13f, p.ink2), lp(MATCH, WRAP, top = dpi(4f)))
        addView(row().apply {
            addView(button("제외 허용") { requestExemption() }, lp(0, WRAP, 1f))
            addView(button("앱 배터리 설정") { openAppSettings() }, lp(0, WRAP, 1f, left = dpi(8f)))
        }, lp(MATCH, WRAP, top = dpi(10f)))
    }

    override fun onStop() {
        if (receiverOn) {
            unregisterReceiver(receiver)
            receiverOn = false
        }
        super.onStop()
    }

    // ------------------------------------------------------------------ cover

    private fun coverAccepted(): Boolean =
        getSharedPreferences("ui", MODE_PRIVATE).getBoolean(COVER_KEY, false)

    /**
     * Full-screen cover: what the app is for and the no-redistribution notice.
     * [firstRun] requires agreement before recording starts; otherwise it is just shown again.
     */
    private fun showCover(firstRun: Boolean) {
        tabBar.visibility = View.GONE
        homeScroll = null
        sessionList = null
        pageTab = -1

        val page = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = dpi(20f)
            setPadding(pad, dpi(48f), pad, pad)

            addView(ImageView(context).apply { setImageResource(R.drawable.ic_launcher) }, lp(dpi(88f), dpi(88f)))
            addView(label("충전 로그", 30f, p.ink, bold = true), lp(WRAP, WRAP, top = dpi(16f)))
            addView(label("배터리 충전·방전 모니터링", 16f, p.ink2), lp(WRAP, WRAP, top = dpi(4f)))
            val version = try {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (e: Exception) { null }
            if (version != null) addView(label("버전 $version", 12f, p.muted), lp(WRAP, WRAP, top = dpi(4f)))

            addView(card(p).apply {
                addView(title("이 앱은 충전 모니터링 전용입니다"))
                addView(label(
                    "• 충전 시작·종료 시각과 그때의 배터리 잔량(SOC)을 기록합니다\n" +
                        "• 하루 사용량, 충전–방전 세션별 사용량, 최저·최고 SOC를 보여줍니다\n" +
                        "• 충전 방식, 배터리 온도·전압을 함께 기록합니다\n" +
                        "• 기록은 이 휴대폰 안에만 저장되며 인터넷으로 전송하지 않습니다\n" +
                        "• 정확한 기록을 위해 상시 알림을 띄우고 백그라운드에서 동작합니다",
                    14f, p.ink2).apply { setLineSpacing(dp(4f), 1f) }, lp(MATCH, WRAP, top = dpi(8f)))
            }, lp(MATCH, WRAP, top = dpi(28f)))

            addView(column().apply {
                background = GradientDrawable().apply {
                    setColor(p.card)
                    cornerRadius = dp(22f)
                    setStroke(dpi(2f), p.danger)
                }
                val cp = dpi(18f)
                setPadding(cp, cp, cp, cp)
                addView(label("⚠ 주의", 17f, p.danger, bold = true))
                addView(label(
                    "이 앱은 개인 충전 모니터링 목적으로 제작되었습니다.\n\n" +
                        "제작자의 허락 없이 이 앱(APK)과 소스 코드를 임의로 복제·수정·배포하거나 " +
                        "다른 사람에게 전달하지 마세요.\n\n" +
                        "표시되는 값은 휴대폰이 보고하는 측정값으로, 정확성을 보장하지 않습니다.",
                    14f, p.ink).apply { setLineSpacing(dp(3f), 1f) }, lp(MATCH, WRAP, top = dpi(8f)))
            }, lp(MATCH, WRAP, top = dpi(12f)))

            if (firstRun) {
                addView(label("동의하고 시작", 16f, p.card, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = roundRect(p.ink, 16f)
                    setPadding(dpi(12f), dpi(16f), dpi(12f), dpi(16f))
                    setOnClickListener {
                        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean(COVER_KEY, true).apply()
                        startMonitoring()
                    }
                }, lp(MATCH, WRAP, top = dpi(24f)))
                addView(label("동의하지 않으면 앱을 닫아주세요. 동의 전에는 아무것도 기록하지 않습니다.",
                    12f, p.muted).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP, top = dpi(10f)))
            } else {
                addView(button("닫기") { tabBar.visibility = View.VISIBLE; render() }, lp(MATCH, WRAP, top = dpi(24f)))
            }
        }
        content.removeAllViews()
        content.addView(ScrollView(this).apply { addView(page) })
    }

    // ------------------------------------------------------------------ shell

    private fun render() {
        renderTabs()
        when (tab) {
            0 -> renderHome()
            1 -> renderStats()
            2 -> renderSessions()
            else -> renderData()
        }
    }

    private fun renderTabs() {
        tabBar.removeAllViews()
        listOf("홈", "통계", "세션", "데이터").forEachIndexed { i, name ->
            val on = i == tab
            tabBar.addView(label(name, 15f, if (on) p.ink else p.muted, bold = on).apply {
                gravity = Gravity.CENTER
                setOnClickListener { if (tab != i) { tab = i; sessionList = null; render() } }
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
        if (!isExempt()) page.addView(keepAliveBanner(), lp(MATCH, WRAP).apply { bottomMargin = dpi(12f) })
        page.addView(hero(todayStat, now))
        page.addView(sessionCard(now), lp(MATCH, WRAP, top = dpi(12f)))
        page.addView(weekCard(week, average, week.indexOfFirst { it.day == day }, today),
            lp(MATCH, WRAP, top = dpi(12f)))
        page.addView(dayCard(week.first { it.day == day }, now), lp(MATCH, WRAP, top = dpi(12f)))

        sessionList = null
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

    // ----------------------------------------------------------------- stats

    private fun pct(v: Double?): String = v?.let { "${Math.round(it)}%" } ?: "-"

    private fun renderStats() {
        val today = LocalDate.now(zone)
        val fromDay = today.minusDays(statsDays - 1L)
        val from = Stats.dayStart(fromDay, zone)
        val to = Stats.dayStart(today.plusDays(1), zone)
        val days = Stats.daily(db.samples(from, to), db.sampleBefore(from), zone, fromDay, today)
        val sessions = db.sessionsOverlapping(from, to)
        val sum = Summaries.of(days, sessions, db.firstSampleTs(), today, zone)
        val todayStat = days.last()
        val md = DateTimeFormatter.ofPattern("M/d")

        val page = page()

        // Period selector.
        page.addView(row().apply {
            addView(title("통계"), lp(0, WRAP, 1f))
            for (n in intArrayOf(7, 30, 90)) {
                val on = n == statsDays
                addView(label("${n}일", 14f, if (on) p.card else p.ink2, bold = on).apply {
                    gravity = Gravity.CENTER
                    background = roundRect(if (on) p.ink else p.card, 16f)
                    setPadding(dpi(14f), dpi(7f), dpi(14f), dpi(7f))
                    setOnClickListener { if (statsDays != n) { statsDays = n; render() } }
                }, lp(WRAP, WRAP, left = dpi(6f)))
            }
        }, lp(MATCH, WRAP).apply { leftMargin = dpi(6f) })

        if (sum.days.isEmpty()) {
            page.addView(card(p).apply {
                addView(label("아직 통계를 낼 기록이 없습니다. 하루 이상 기록되면 평소 사용 패턴이 표시됩니다.",
                    14f, p.ink2))
            }, lp(MATCH, WRAP, top = dpi(12f)))
            setPage(page)
            return
        }

        // Typical day.
        page.addView(card(p).apply {
            addView(title("평소 하루"))
            addView(row().apply {
                addView(tile(p, "평균 사용량", pct(sum.avgUsage)), lp(0, WRAP, 1f))
                addView(tile(p, "평균 최저", pct(sum.avgMin)), lp(0, WRAP, 1f))
                addView(tile(p, "평균 최고", pct(sum.avgMax)), lp(0, WRAP, 1f))
                addView(tile(p, "충전", sum.chargesPerDay?.let { String.format(Locale.US, "%.1f회", it) } ?: "-"),
                    lp(0, WRAP, 1f))
            }, lp(MATCH, WRAP, top = dpi(12f)))
            val basis = if (sum.completeDaysOnly) "하루 전체가 기록된 ${sum.days.size}일 기준 (설치일·오늘 제외)"
                else "아직 하루 전체가 기록된 날이 없어 기록된 구간 기준입니다"
            addView(label(basis + " · 사용량 중앙값 ${sum.medianUsage}%", 12f, p.muted), lp(MATCH, WRAP, top = dpi(10f)))
        }, lp(MATCH, WRAP, top = dpi(12f)))

        // Today against the typical day.
        page.addView(card(p).apply {
            addView(title("오늘 vs 평소"))
            addView(kv(p, "사용량", "${todayStat.usage}%  /  평소 ${pct(sum.avgUsage)}"), lp(MATCH, WRAP, top = dpi(8f)))
            sum.avgUsage?.takeIf { it > 0 }?.let { avg ->
                addView(LevelBar(context, p).apply {
                    level = Math.round(todayStat.usage * 100 / avg).toInt().coerceIn(0, 100)
                }, lp(MATCH, WRAP, top = dpi(2f)).apply { bottomMargin = dpi(6f) })
            }
            addView(kv(p, "최저 SOC", "${todayStat.minSoc?.let { "$it%" } ?: "-"}  /  평소 ${pct(sum.avgMin)}"))
            addView(kv(p, "최고 SOC", "${todayStat.maxSoc?.let { "$it%" } ?: "-"}  /  평소 ${pct(sum.avgMax)}"))
            addView(kv(p, "충전량", "${todayStat.charged}%"))
        }, lp(MATCH, WRAP, top = dpi(12f)))

        // Daily usage.
        page.addView(card(p).apply {
            addView(title("일일 사용량"))
            addView(UsageChartView(context, p).apply { this.days = days; average = sum.avgUsage },
                lp(MATCH, WRAP, top = dpi(8f)))
            addView(label("막대: 하루 SOC 사용 합계 · 점선: 평균 ${pct(sum.avgUsage)} · 누르면 날짜별 값",
                12f, p.muted))
        }, lp(MATCH, WRAP, top = dpi(12f)))

        // Daily SOC range.
        page.addView(card(p).apply {
            addView(title("일일 SOC 범위 (최저–최고)"))
            addView(RangeChartView(context, p).apply {
                avgMin = sum.avgMin; avgMax = sum.avgMax; this.days = days
            }, lp(MATCH, WRAP, top = dpi(8f)))
            addView(label("막대: 그날 최저~최고 · 점선: 평균 최저 ${pct(sum.avgMin)} / 평균 최고 ${pct(sum.avgMax)}",
                12f, p.muted))
            sum.lowest?.let { addView(kv(p, "기간 최저", "${it.minSoc}% (${md.format(it.day)})"), lp(MATCH, WRAP, top = dpi(8f))) }
            sum.highest?.let { addView(kv(p, "기간 최고", "${it.maxSoc}% (${md.format(it.day)})")) }
        }, lp(MATCH, WRAP, top = dpi(12f)))

        // Charging habits.
        page.addView(card(p).apply {
            addView(title("충전 습관"))
            addView(kv(p, "완료된 충전", "${sum.chargeCount}회"), lp(MATCH, WRAP, top = dpi(8f)))
            addView(kv(p, "평균 충전 시작 SOC", pct(sum.avgChargeStart)))
            addView(kv(p, "평균 충전 종료 SOC", pct(sum.avgChargeEnd)))
            addView(kv(p, "평균 충전 시간", sum.avgChargeMinutes?.let { Format.duration((it * 60_000).toLong()) } ?: "-"))
        }, lp(MATCH, WRAP, top = dpi(12f)))

        // Day-by-day table.
        page.addView(card(p).apply {
            addView(title("일별 기록"))
            fun cells(vararg v: String, head: Boolean = false) = row().apply {
                v.forEachIndexed { i, t ->
                    addView(label(t, if (head) 12f else 14f, if (head) p.ink2 else p.ink, bold = !head && i == 1).apply {
                        gravity = if (i == 0) Gravity.START else Gravity.END
                    }, lp(0, WRAP, if (i == 0) 1.4f else 1f))
                }
                setPadding(0, dpi(6f), 0, dpi(6f))
            }
            addView(cells("날짜", "사용", "충전", "최저", "최고", head = true), lp(MATCH, WRAP, top = dpi(8f)))
            val dfmt = DateTimeFormatter.ofPattern("M/d (E)", Locale.KOREAN)
            for (d in days.asReversed()) {
                if (d.minSoc == null) continue
                addView(View(context).apply { setBackgroundColor(p.grid) }, lp(MATCH, dpi(1f)))
                addView(cells(dfmt.format(d.day), "${d.usage}%", "${d.charged}%", "${d.minSoc}%", "${d.maxSoc}%"))
            }
        }, lp(MATCH, WRAP, top = dpi(12f)))

        setPage(page)
    }

    /** Shows a scrolling page that is rebuilt from scratch (not Home or Sessions). */
    private fun setPage(page: View) {
        // Rebuilt after a battery update: stay where the user was scrolled to.
        val old = content.getChildAt(0) as? ScrollView
        val keepY = if (old != null && old !== homeScroll && pageTab == tab) old.scrollY else 0
        pageTab = tab
        homeScroll = null
        sessionList = null
        content.removeAllViews()
        content.addView(ScrollView(this).apply {
            addView(page)
            if (keepY > 0) post { scrollTo(0, keepY) }
        })
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

    private sealed class Row {
        class Day(val stat: DayStat, val charges: Int) : Row()
        class Item(val session: Session) : Row()
    }

    private fun renderSessions() {
        val now = System.currentTimeMillis()
        val sessions = db.latestSessions(SESSION_HISTORY_LIMIT)
        val groups = Stats.sessionsByDay(sessions, zone, now)

        // Daily totals for every listed day, computed from the SOC samples in one pass.
        val stats = HashMap<LocalDate, DayStat>()
        if (groups.isNotEmpty()) {
            val oldest = groups.last().first
            val newest = groups.first().first
            val from = Stats.dayStart(oldest, zone)
            val to = Stats.dayStart(newest.plusDays(1), zone)
            Stats.daily(db.samples(from, to), db.sampleBefore(from), zone, oldest, newest)
                .forEach { stats[it.day] = it }
        }
        val rows = ArrayList<Row>()
        for ((day, list) in groups) {
            val dayFrom = Stats.dayStart(day, zone)
            val dayTo = Stats.dayStart(day.plusDays(1), zone)
            val charges = list.count { it.type == SessionType.CHARGE && it.startTs in dayFrom until dayTo }
            rows.add(Row.Day(stats[day] ?: DayStat(day, 0, 0, null, null), charges))
            list.forEach { rows.add(Row.Item(it)) }
        }

        // Keep the scroll position when the list is rebuilt for a new reading.
        val oldList = sessionList
        val keepPos = oldList?.firstVisiblePosition ?: 0
        val keepTop = oldList?.getChildAt(0)?.top ?: 0
        val list = ListView(this).apply {
            divider = null
            clipToPadding = false
            setPadding(dpi(16f), dpi(0f), dpi(16f), dpi(16f))
            adapter = object : BaseAdapter() {
                override fun getCount() = rows.size
                override fun getItem(i: Int) = rows[i]
                override fun getItemId(i: Int) = i.toLong()
                override fun getViewTypeCount() = 2
                override fun getItemViewType(i: Int) = if (rows[i] is Row.Day) 0 else 1
                override fun isEnabled(i: Int) = false
                override fun getView(i: Int, convert: View?, parent: ViewGroup): View =
                    when (val row = rows[i]) {
                        is Row.Day -> dayHeader(row.stat, row.charges)
                        is Row.Item -> FrameLayout(context).apply {
                            setPadding(0, dpi(4f), 0, dpi(4f))
                            addView(card(p).apply {
                                val pad = dpi(14f)
                                setPadding(pad, pad, pad, pad)
                                addView(sessionRow(row.session, now))
                            })
                        }
                    }
            }
        }
        val page = column().apply {
            addView(title("충전·사용 세션").apply { setPadding(dpi(22f), dpi(20f), dpi(22f), dpi(4f)) })
            if (rows.isEmpty()) {
                addView(label("아직 기록된 세션이 없습니다.", 14f, p.ink2).apply {
                    setPadding(dpi(22f), dpi(8f), dpi(22f), 0)
                })
            } else {
                addView(list, lp(MATCH, 0, 1f))
            }
        }
        if (oldList != null && rows.isNotEmpty()) list.setSelectionFromTop(keepPos, keepTop)
        sessionList = list
        homeScroll = null
        content.removeAllViews()
        content.addView(page)
    }

    /** Date heading with that day's totals, above the day's session cards. */
    private fun dayHeader(stat: DayStat, charges: Int): View = column().apply {
        setPadding(dpi(6f), dpi(18f), dpi(6f), dpi(6f))
        val today = LocalDate.now(zone)
        val name = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN).format(stat.day) +
            when (stat.day) { today -> " · 오늘"; today.minusDays(1) -> " · 어제"; else -> "" }
        addView(label(name, 15f, p.ink, bold = true))
        val parts = arrayListOf("사용 ${stat.usage}%", "충전 ${stat.charged}%", "충전 ${charges}회")
        if (stat.minSoc != null && stat.maxSoc != null) parts.add("SOC ${stat.minSoc}–${stat.maxSoc}%")
        addView(label(parts.joinToString(" · "), 13f, p.ink2), lp(MATCH, WRAP, top = dpi(2f)))
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
            db.lastSample()?.let { addView(kv(p, "마지막 기록", Format.dateTime(it.ts, zone))) }
            val exempt = isExempt()
            addView(kv(p, "배터리 최적화 제외", if (exempt) "설정됨" else "미설정"))
            addView(label(
                (if (exempt) "" else "제외하지 않으면 휴대폰이 앱을 절전 상태로 보내 기록이 끊길 수 있습니다.\n") +
                    "삼성: 앱 정보 → 배터리 → '제한 없음'이면 됩니다. 15분마다 기록 상태를 점검해 " +
                    "중단되었으면 다시 시작합니다.",
                12f, p.ink2), lp(MATCH, WRAP, top = dpi(4f)))
            if (!exempt) addView(button("배터리 최적화에서 제외") { requestExemption() }, lp(MATCH, WRAP, top = dpi(10f)))
            addView(button("앱 배터리 설정 열기") { openAppSettings() }, lp(MATCH, WRAP, top = dpi(10f)))
        })

        page.addView(card(p).apply {
            addView(title("앱 안내"))
            addView(label("충전 모니터링 전용 앱입니다. 임의로 배포하지 마세요.", 13f, p.ink2),
                lp(MATCH, WRAP, top = dpi(4f)))
            addView(button("안내 화면 다시 보기") { showCover(firstRun = false) }, lp(MATCH, WRAP, top = dpi(10f)))
        }, lp(MATCH, WRAP, top = dpi(12f)))

        page.addView(card(p).apply {
            addView(title("충전기 진단"))
            addView(label("연결된 충전기에 대해 휴대폰이 보고하는 값(최대 전류·전압, 실제 입력 전력)을 실시간으로 봅니다.",
                13f, p.ink2), lp(MATCH, WRAP, top = dpi(4f)))
            addView(button("충전기 진단 열기") {
                startActivity(Intent(context, ChargerActivity::class.java))
            }, lp(MATCH, WRAP, top = dpi(10f)))
        }, lp(MATCH, WRAP, top = dpi(12f)))

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

        setPage(page)
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
        private const val COVER_KEY = "cover_accepted_v1"
        /** Roughly a year of typical use; older sessions remain in the DB and in exports. */
        private const val SESSION_HISTORY_LIMIT = 3000
        private const val NOTIF_PERMISSION = "android.permission.POST_NOTIFICATIONS"
    }
}
