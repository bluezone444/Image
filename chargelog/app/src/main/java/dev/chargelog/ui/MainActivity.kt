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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
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
import dev.chargelog.core.DayType
import dev.chargelog.core.StatsFilter
import dev.chargelog.core.StatsPeriod
import dev.chargelog.core.ThemeMode
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
import dev.chargelog.core.ChargeSpec
import dev.chargelog.core.Drain
import dev.chargelog.core.Ocv
import dev.chargelog.core.Volt
import dev.chargelog.service.MonitorService
import dev.chargelog.service.Watchdog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var p: Palette
    private lateinit var db: Db
    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private var sessionList: ListView? = null

    private var tab = 0
    private var statsPeriod = StatsPeriod.D30
    private var statsType = DayType.ALL
    /** Rows shown in the Stats day table; grows with "더 보기". */
    private var tableRows = TABLE_PAGE
    /** Sessions tab: 0 all, 1 charges only, 2 use only. */
    private var sessionFilter = 0
    private var pageTab = -1
    private var pageScroll: ScrollView? = null
    /** True while the cover (notice) screen replaces the tabs. */
    private var coverShown = false
    /** A finger is down: screen rebuilds wait so a pinch or scrub is not cut off. */
    private var touching = false
    private var renderPending = false
    private var exporting = false
    private var shownDay: LocalDate? = null
    private val handler = Handler(Looper.getMainLooper())
    private var receiverOn = false
    private var askedNotif = false
    /** Day shown in the Stats tab's day detail; null picks a sensible default. */
    private var statsDay: LocalDate? = null
    private var current: Sample? = null
    /** Latest battery broadcast, used to name the voltage source. */
    private var batteryIntent: Intent? = null

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val prev = current
            val s = Recorder.record(context, intent) ?: return
            current = s
            batteryIntent = intent
            // ACTION_BATTERY_CHANGED also fires for every temperature/voltage wobble; only
            // rebuild the screen when something it shows has changed.
            if (prev == null || prev.level != s.level || prev.plugged != s.plugged ||
                prev.status != s.status || (tab == 0 && prev.temp / 10 != s.temp / 10)
            ) refresh()
        }
    }

    /** Keeps Home's "now" moving and notices midnight on every tab. */
    private val minuteTick = object : Runnable {
        override fun run() {
            if (!coverShown && (tab == 0 || shownDay != LocalDate.now(zone))) refresh()
            handler.postDelayed(this, 60_000L)
        }
    }

    /** Rebuild for new data; postponed while the user is touching the screen. */
    private fun refresh() {
        if (coverShown) return
        if (touching) renderPending = true else render()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> touching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                // Posted so the views still receive this event before they are replaced.
                if (renderPending) content.post {
                    if (renderPending && !touching) { renderPending = false; if (!coverShown && !isFinishing) render() }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Theme.applyTo(this)
        super.onCreate(savedInstanceState)
        p = Palette(this)
        db = Db.get(this)
        savedInstanceState?.let { st ->
            tab = st.getInt("tab")
            statsPeriod = StatsPeriod.of(st.getString("statsPeriod"))
            statsType = DayType.of(st.getString("statsType"))
            statsDay = st.getLong("statsDay", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let { LocalDate.ofEpochDay(it) }
            sessionFilter = st.getInt("sessionFilter")
            askedNotif = st.getBoolean("askedNotif")
        }

        content = FrameLayout(this)
        tabBar = row().apply { setBackgroundColor(p.card) }
        setContentView(column().apply {
            setBackgroundColor(p.bg)
            addView(content, lp(MATCH, 0, 1f))
            addView(View(context).apply { setBackgroundColor(p.line) }, lp(MATCH, Math.max(1, dpi(1f))))
            addView(tabBar, lp(MATCH, WRAP))
        })
        Theme.paintWindow(this, p, p.card)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
        outState.putString("statsPeriod", statsPeriod.name)
        outState.putString("statsType", statsType.name)
        statsDay?.let { outState.putLong("statsDay", it.toEpochDay()) }
        outState.putInt("sessionFilter", sessionFilter)
        outState.putBoolean("askedNotif", askedNotif)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            // The notice opened from the Data tab closes back to it.
            coverShown && coverAccepted() -> closeCover()
            !coverShown && tab != 0 -> { tab = 0; sessionList = null; render() }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onStart() {
        super.onStart()
        // Nothing is recorded until the cover notice has been accepted once.
        if (!coverAccepted()) showCover(firstRun = true) else startMonitoring()
    }

    private fun closeCover() {
        coverShown = false
        tabBar.visibility = View.VISIBLE
        Theme.paintWindow(this, p, p.card)
        render()
    }

    private fun startMonitoring() {
        coverShown = false
        tabBar.visibility = View.VISIBLE
        Theme.paintWindow(this, p, p.card)
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
        if (sticky != null) {
            current = Recorder.record(this, sticky) ?: current
            batteryIntent = sticky
        }
        handler.removeCallbacks(minuteTick)
        handler.postDelayed(minuteTick, 60_000L)
        Watchdog.schedule(this)
        render()
        askExemptionOnce()
        // Earlier history gets its gaps estimated once, off the main thread.
        val app = applicationContext
        Thread {
            if (Recorder.rebuildOnce(app)) runOnUiThread { if (!isFinishing && !isDestroyed) refresh() }
        }.start()
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

    /** Slim warning on Home while recording can still be stopped by the system. */
    private fun keepAliveBanner(compact: Boolean = false): View = row().apply {
        background = roundRect(p.warnBg, 14f, p.warnLine)
        setPadding(dpi(12f), dpi(if (compact) 6f else 9f), dpi(8f), dpi(if (compact) 6f else 9f))
        addView(column().apply {
            addView(label("기록이 끊길 수 있습니다", 14f, p.ink, bold = true))
            if (!compact) addView(label("배터리 최적화 제외가 필요합니다", 13f, p.ink2), lp(WRAP, WRAP, top = dpi(3f)))
        }, lp(0, WRAP, 1f))
        addView(label("설정", 14f, p.ink, bold = true).apply {
            background = roundRect(p.card, 10f, p.line)
            setPadding(dpi(14f), dpi(7f), dpi(14f), dpi(7f))
            setOnClickListener { requestExemption() }
        })
    }

    override fun onStop() {
        handler.removeCallbacks(minuteTick)
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
        coverShown = true
        renderPending = false
        tabBar.visibility = View.GONE
        Theme.paintWindow(this, p, p.bg)
        sessionList = null
        pageScroll = null
        pageTab = -1

        val version = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { null }

        // Header: soft brand gradient with icon, name and tagline.
        val header = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf((p.soc and 0x00FFFFFF) or 0x33000000, p.bg))
            setPadding(dpi(24f), dpi(56f), dpi(24f), dpi(28f))
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_launcher) }, lp(dpi(76f), dpi(76f)))
            addView(label("충전 로그", 30f, p.ink, bold = true), lp(WRAP, WRAP, top = dpi(18f)))
            addView(label("배터리 충전·방전을 기록하고 분석합니다", 15f, p.ink2), lp(WRAP, WRAP, top = dpi(6f)))
            if (version != null) addView(label("v$version", 13f, p.muted), lp(WRAP, WRAP, top = dpi(6f)))
        }

        fun feature(color: Int, title: String, body: String) = row().apply {
            gravity = Gravity.TOP
            addView(View(context).apply { background = roundRect(color, 5f) }, lp(dpi(10f), dpi(10f), top = dpi(5f)))
            addView(column().apply {
                addView(label(title, 15f, p.ink, bold = true))
                addView(label(body, 14f, p.ink2).apply { setLineSpacing(dp(2f), 1f) }, lp(MATCH, WRAP, top = dpi(3f)))
            }, lp(0, WRAP, 1f, left = dpi(12f)))
        }

        val body = column().apply {
            setPadding(dpi(20f), 0, dpi(20f), dpi(28f))

            addView(card(p, 18f).apply {
                addView(caption("이 앱은 충전 모니터링 전용입니다", p))
                addView(feature(p.soc, "충전 시작·종료 기록", "충전기를 꽂고 뺀 시각과 그때의 잔량(SOC), 충전 방식과 충전 속도 등급"),
                    lp(MATCH, WRAP, top = dpi(14f)))
                addView(feature(p.ink2, "사용량 분석", "하루 사용량, 충전–방전 세션별 사용량, 최저·최고 SOC와 평소 대비"),
                    lp(MATCH, WRAP, top = dpi(14f)))
                addView(feature(p.temp, "배터리 상태", "온도, 방전 전류를 일정한 주기로 기록하고 그래프로 표시"),
                    lp(MATCH, WRAP, top = dpi(14f)))
                addView(View(context).apply { setBackgroundColor(p.line) }, lp(MATCH, Math.max(1, dpi(1f)), top = dpi(16f)))
                addView(label("기록은 이 휴대폰 안에만 저장되며 인터넷으로 전송하지 않습니다. 정확한 기록을 위해 " +
                    "상시 알림을 띄우고 백그라운드에서 동작합니다.", 13f, p.muted).apply { setLineSpacing(dp(2f), 1f) },
                    lp(MATCH, WRAP, top = dpi(12f)))
            })

            // Warning: hairline card with a red accent bar.
            addView(row().apply {
                gravity = Gravity.TOP
                background = roundRect(p.card, 18f, p.line)
                setPadding(0, dpi(16f), dpi(16f), dpi(16f))
                addView(View(context).apply { background = roundRect(p.danger, 2f) },
                    lp(dpi(4f), MATCH).apply { leftMargin = dpi(14f) })
                addView(column().apply {
                    addView(label("주의", 15f, p.danger, bold = true))
                    addView(label(
                        "이 앱은 개인 충전 모니터링 목적으로 제작되었습니다. 제작자의 허락 없이 이 앱(APK)과 소스 코드를 " +
                            "임의로 복제·수정·배포하거나 다른 사람에게 전달하지 마세요.", 15f, p.ink
                    ).apply { setLineSpacing(dp(3f), 1f) }, lp(MATCH, WRAP, top = dpi(6f)))
                    addView(label("표시되는 값은 휴대폰이 보고하는 측정값과 그로부터 계산한 추정값으로, 정확성을 보장하지 않습니다.",
                        12f, p.ink2).apply { setLineSpacing(dp(2f), 1f) }, lp(MATCH, WRAP, top = dpi(8f)))
                }, lp(0, WRAP, 1f, left = dpi(12f)))
            }, lp(MATCH, WRAP, top = dpi(12f)))

            if (firstRun) {
                var agreed = false
                val start = label("동의하고 시작", 16f, p.muted, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = roundRect(p.line, 16f)
                    setPadding(dpi(12f), dpi(16f), dpi(12f), dpi(16f))
                    isEnabled = false
                    setOnClickListener {
                        if (!agreed) return@setOnClickListener
                        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean(COVER_KEY, true).apply()
                        startMonitoring()
                    }
                }
                val box = View(context)
                fun paintBox() {
                    box.background = if (agreed) roundRect(p.soc, 6f) else roundRect(p.card, 6f, p.muted)
                }
                paintBox()
                addView(row().apply {
                    setPadding(dpi(4f), dpi(14f), dpi(4f), dpi(14f))
                    addView(box, lp(dpi(22f), dpi(22f)))
                    addView(label("위 내용을 확인했으며 개인 용도로만 사용합니다", 15f, p.ink), lp(0, WRAP, 1f, left = dpi(12f)))
                    setOnClickListener {
                        agreed = !agreed
                        paintBox()
                        start.isEnabled = agreed
                        start.setTextColor(if (agreed) p.card else p.muted)
                        start.background = roundRect(if (agreed) p.ink else p.line, 16f)
                    }
                }, lp(MATCH, WRAP, top = dpi(10f)))
                addView(start, lp(MATCH, WRAP, top = dpi(4f)))
                addView(label("동의 전에는 아무것도 기록하지 않습니다", 13f, p.muted).apply { gravity = Gravity.CENTER },
                    lp(MATCH, WRAP, top = dpi(10f)))
            } else {
                addView(button("닫기") { closeCover() }, lp(MATCH, WRAP, top = dpi(20f)))
            }
        }

        content.removeAllViews()
        content.addView(ScrollView(this).apply {
            addView(column().apply {
                addView(header, lp(MATCH, WRAP))
                addView(body, lp(MATCH, WRAP))
            })
        })
    }

    // ------------------------------------------------------------------ shell

    private fun render() {
        // The cover owns the screen until it is accepted or closed; a battery update must not
        // swap it for a tab (which used to leave the app without its tab bar).
        if (coverShown) return
        renderPending = false
        shownDay = LocalDate.now(zone)
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
            tabBar.addView(column().apply {
                gravity = Gravity.CENTER
                setPadding(0, dpi(15f), 0, dpi(12f))
                contentDescription = name
                // WRAP so the label sits centered over the indicator (a vertical
                // LinearLayout would otherwise stretch it and left-align the text).
                addView(label(name, 15f, if (on) p.ink else p.ink2, bold = on), lp(WRAP, WRAP))
                addView(View(context).apply {
                    background = roundRect(if (on) p.ink else android.graphics.Color.TRANSPARENT, 2f)
                }, lp(dpi(24f), dpi(3f), top = dpi(7f)))
                setOnClickListener { if (tab != i) { tab = i; sessionList = null; render() } }
            }, lp(0, WRAP, 1f))
        }
    }

    private fun page(): LinearLayout = column().apply {
        setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(20f))
    }

    private fun title(text: String): TextView = label(text, 16f, p.ink, bold = true)

    /** Page heading: large title with an optional control on the right. */
    private fun pageHeader(text: String, trailing: View? = null): LinearLayout = row().apply {
        addView(label(text, 24f, p.ink, bold = true), lp(0, WRAP, 1f))
        if (trailing != null) addView(trailing)
        setPadding(dpi(2f), 0, 0, 0)
    }

    private fun gap(): Int = dpi(10f)

    private fun dateTitle(day: LocalDate): String =
        DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN).format(day)

    private fun relativeDay(day: LocalDate): String {
        val today = LocalDate.now(zone)
        return when (day) { today -> " · 오늘"; today.minusDays(1) -> " · 어제"; else -> "" }
    }

    /** Readings for [start, end), starting with the SOC carried in from before [start]. */
    private fun dayPoints(start: Long, end: Long): List<Sample> {
        val pts = ArrayList<Sample>()
        db.sampleBefore(start)?.let { pts.add(it.copy(ts = start)) }
        pts.addAll(db.samples(start, end))
        return pts
    }

    private fun pct(v: Double?): String = v?.let { "${Math.round(it)}%" } ?: "-"
    private fun pct(v: Int?): String = v?.let { "$it%" } ?: "-"
    private val v2 = { mv: Int -> String.format(Locale.US, "%.2fV", mv / 1000.0) }
    private val c1 = { t: Int -> "${Format.tenths(t)}°C" }
    private val ma = { v: Int -> "${v}mA" }

    // ------------------------------------------------------------------ home

    /** One screen, no scrolling: the battery now, today against a usual day, and today's curves. */
    private fun renderHome() {
        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()
        val start = Stats.dayStart(today, zone)
        val end = Stats.dayStart(today.plusDays(1), zone)
        val points = dayPoints(start, end)

        // Usual day = last 30 days, fully recorded days only.
        val from = today.minusDays(29)
        val fromTs = Stats.dayStart(from, zone)
        val days = db.days.range(from, today, zone)
        val usual = Summaries.of(days, db.sessionsOverlapping(fromTs, end), db.firstSampleTs(), today, zone)
            .takeIf { it.completeDaysOnly }
        val todayStat = days.last()

        // Everything must fit one screen. On a short screen or with a large system font the
        // fixed cards are tightened so the SOC chart keeps a usable height.
        val exempt = isExempt()
        val cfg = resources.configuration
        val needDp = 670 + (if (exempt) 0 else 70) + 260 * (cfg.fontScale - 1f)
        val compact = cfg.screenHeightDp < needDp
        val gap = if (compact) dpi(8f) else gap()
        val pad = if (compact) 12f else 14f

        val root = column().apply { setPadding(dpi(16f), dpi(if (compact) 10f else 14f), dpi(16f), dpi(if (compact) 8f else 12f)) }
        root.addView(row().apply {
            addView(label("충전 로그", if (compact) 20f else 22f, p.ink, bold = true), lp(0, WRAP, 1f))
            addView(label(dateTitle(today), 14f, p.ink2))
            setPadding(dpi(2f), 0, 0, 0)
        })
        if (!exempt) root.addView(keepAliveBanner(compact), lp(MATCH, WRAP, top = gap))
        root.addView(statusCard(now, compact), lp(MATCH, WRAP, top = gap))
        root.addView(todayCard(todayStat, usual, pad), lp(MATCH, WRAP, top = gap))
        root.addView(card(p, pad).apply {
            addView(row().apply {
                addView(title("오늘 SOC"), lp(0, WRAP, 1f))
                if (points.any { it.est }) addView(legend(p, "추정", p.ink2, dashed = true), lp(WRAP, WRAP).apply { rightMargin = dpi(12f) })
                addView(legend(p, "충전 구간", p.accentDim))
            })
            if (points.isEmpty()) {
                addView(label("아직 오늘 기록이 없습니다.", 14f, p.ink2), lp(MATCH, WRAP, top = dpi(10f)))
            } else {
                val sessions = db.sessionsOverlapping(start, end)
                addView(SocChartView(context, p).apply {
                    zoomKey = "home-soc"
                    set(points, sessions, start, end, now, zone)
                }, lp(MATCH, 0, 1f, top = dpi(4f)))
            }
        }, lp(MATCH, 0, 1f, top = gap))
        root.addView(sensorCard(points, start, now, compact), lp(MATCH, WRAP, top = gap))

        sessionList = null
        pageScroll = null
        pageTab = tab
        content.removeAllViews()
        content.addView(root)
    }

    private fun statusCard(now: Long, compact: Boolean = false): View = card(p, if (compact) 12f else 14f).apply {
        val s = current
        val recent = db.latestSessions(3)
        val open = recent.firstOrNull { it.open }
        addView(row().apply {
            addView(RingGauge(context, p).apply {
                level = s?.level ?: 0
                caption = "배터리"
            }, (if (compact) dpi(88f) else dpi(112f)).let { lp(it, it) })
            addView(column().apply {
                val (state, dot) = when {
                    s == null -> "측정 대기" to p.muted
                    s.charging && s.status == 5 -> "충전 완료 · ${Plug.labelKo(s.plugged)}" to p.soc
                    s.charging -> "충전 중 · " + (open?.let { chargeSpec(it, now) }?.takeIf { !it.pending }?.let {
                        if (it.wireless) "무선 ${it.watts}W" else it.tier.label
                    } ?: Plug.labelKo(s.plugged)) to p.soc
                    else -> "사용 중" to p.muted
                }
                addView(chip(p, state, dot), lp(WRAP, WRAP))
                if (open != null) {
                    val charge = open.type == SessionType.CHARGE
                    addView(caption((if (charge) "이번 충전" else "충전 후 사용") + if (open.est) " · 추정 포함" else "", p),
                        lp(WRAP, WRAP, top = dpi(if (compact) 6f else 10f)))
                    addView(row().apply {
                        gravity = Gravity.BOTTOM
                        addView(number("${Format.signed(open.delta)}%", 26f, p.ink))
                        addView(label("  ${open.startSoc}% → ${open.endSoc}%", 14f, p.ink2),
                            lp(WRAP, WRAP).apply { bottomMargin = dpi(2f) })
                    }, lp(WRAP, WRAP, top = dpi(4f)))
                    val parts = arrayListOf("${Format.time(open.startTs, zone)} 시작", Format.duration(open.durationMs(now)))
                    open.ratePerHour(now)?.let { parts.add(String.format(Locale.US, "%.1f%%/시간", it)) }
                    addView(label(parts.joinToString(" · "), 13f, p.ink2), lp(WRAP, WRAP, top = dpi(5f)))
                }
                if (!compact) recent.firstOrNull { !it.open }?.let { prev ->
                    val kind = if (prev.type == SessionType.CHARGE) "직전 충전" else "직전 사용"
                    addView(label("$kind ${prev.startSoc}→${prev.endSoc}% · ${Format.duration(prev.durationMs(now))}",
                        12f, p.muted), lp(WRAP, WRAP, top = dpi(4f)))
                }
            }, lp(0, WRAP, 1f, left = dpi(16f)))
        })
    }

    private fun todayCard(t: DayStat, usual: dev.chargelog.core.Summary?, pad: Float = 14f): View = card(p, pad).apply {
        addView(sectionTitle(p, "오늘", if (usual != null) "평소 = 최근 ${usual.days.size}일 평균" else "평소 비교는 하루 전체 기록 후"))
        fun note(v: Double?) = if (usual == null) null else "평소 ${pct(v)}"
        addView(tileRow(p, listOf(
            tile(p, "사용량", "${t.usage}%", note(usual?.avgUsage)),
            tile(p, "충전량", "${t.charged}%", if (usual == null) null else
                "충전 ${usual.chargesPerDay?.let { String.format(Locale.US, "%.1f", it) } ?: "-"}회/일"),
            tile(p, "최저", pct(t.minSoc), note(usual?.avgMin)),
            tile(p, "최고", pct(t.maxSoc), note(usual?.avgMax)),
        )), lp(MATCH, WRAP, top = dpi(if (pad < 14f) 7f else 10f)))
    }

    /** Temperature and discharge current as small sparklines side by side. */
    private fun sensorCard(points: List<Sample>, start: Long, now: Long, compact: Boolean = false): View = card(p, if (compact) 12f else 14f).apply {
        val s = current
        val observed = points.filter { !it.est }
        val temps = observed.filter { it.temp > 0 }.map { it.ts to it.temp } +
            listOfNotNull(s?.takeIf { it.temp > 0 }?.let { now to it.temp })
        // Discharge current replaces voltage on the dashboard; voltage is only stored and exported.
        val drain = Drain.series(points, now, s)
        val drainSum = Drain.summary(drain)
        val drainNow = when {
            s == null -> "-"
            s.charging -> "충전 중"
            s.currentUa != 0 -> "${Math.abs(s.currentUa) / 1000}mA"
            else -> "-"
        }
        fun half(
            name: String, now0: String, pts: List<Pair<Long, Int>>, fmt: (Int) -> String, span: Int, color: Int,
            source: String? = null, rangeText: String? = null, fromZero: Boolean = false,
        ) =
            column().apply {
                addView(row().apply {
                    addView(caption(name, p), lp(0, WRAP, 1f))
                    addView(number(now0, 16f, p.ink))
                })
                addView(Sparkline(context, p, color, fromZero).apply { set(pts, start, maxOf(now, start + 1), span) },
                    lp(MATCH, dpi(if (compact) 28f else 40f), top = dpi(if (compact) 4f else 6f)))
                addView(label(rangeText ?: if (pts.isEmpty()) "기록 없음" else "오늘 ${fmt(pts.minOf { it.second })} – ${fmt(pts.maxOf { it.second })}",
                    11f, p.muted), lp(WRAP, WRAP, top = dpi(4f)))
                if (source != null) addView(label(source, 12f, p.muted), lp(WRAP, WRAP, top = dpi(2f)))
            }
        addView(tileRow(p, listOf(
            half("배터리 온도", s?.let { c1(it.temp) } ?: "-", temps, c1, 20, p.temp),
            half("방전 전류", drainNow, drain, ma, 300, p.volt,
                rangeText = drainSum?.let { "오늘 평균 ${it.first}mA · 최대 ${it.second}mA" } ?: "오늘 방전 기록 없음",
                fromZero = true),
        )))
    }

    // ----------------------------------------------------------------- stats

    private fun renderStats() {
        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()
        val firstTs = db.firstSampleTs()
        val firstDay = firstTs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val fromDay = statsPeriod.start(today, firstDay)
        val from = Stats.dayStart(fromDay, zone)
        val to = Stats.dayStart(today.plusDays(1), zone)
        val days = db.days.range(fromDay, today, zone)
        val sessions = db.sessionsOverlapping(from, to)
        // One filter for everything below: the summary, both charts, charging habits, the table.
        val sum = Summaries.of(days, sessions, firstTs, today, zone, statsType)
        val counted = StatsFilter.mask(days, statsType)
        val filtered = statsType != DayType.ALL
        val md = DateTimeFormatter.ofPattern("M/d")

        // Filters stay put above the scrolling page.
        val periods = StatsPeriod.values().toList()
        val types = DayType.values().toList()
        val header = column().apply {
            setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(8f))
            addView(pageHeader("통계", segmented(p, periods.map { it.label }, periods.indexOf(statsPeriod)) {
                statsPeriod = periods[it]; statsDay = null; tableRows = TABLE_PAGE; pageTab = -1; render()
            }))
            addView(row().apply {
                val basis = if (sum.days.isEmpty()) "해당하는 날 없음" else "${statsType.label.takeIf { filtered } ?: "기록"} ${sum.days.size}일 기준"
                addView(label("${md.format(fromDay)} – ${md.format(today)}\n$basis", 12f, p.ink2).apply {
                    setLineSpacing(dp(2f), 1f)
                }, lp(0, WRAP, 1f, left = dpi(2f)))
                addView(segmented(p, types.map { it.label }, types.indexOf(statsType)) {
                    statsType = types[it]; statsDay = null; tableRows = TABLE_PAGE; render()
                }, lp(WRAP, WRAP, left = dpi(8f)))
            }, lp(MATCH, WRAP, top = dpi(8f)))
        }

        val page = page().apply { setPadding(dpi(16f), dpi(2f), dpi(16f), dpi(20f)) }

        if (sum.days.isEmpty()) {
            val anyRecorded = days.any { it.minSoc != null }
            page.addView(card(p).apply {
                addView(label(
                    if (filtered && anyRecorded) "이 기간에는 기록된 ${statsType.label}이 없습니다. 기간을 늘리거나 요일을 '전체'로 바꿔 보세요."
                    else "아직 통계를 낼 기록이 없습니다. 하루 이상 기록되면 평소 사용 패턴이 표시됩니다.",
                    15f, p.ink2).apply { setLineSpacing(dp(3f), 1f) })
            }, lp(MATCH, WRAP, top = dpi(6f)))
            setPage(page, header)
            return
        }

        // Usual day.
        page.addView(card(p).apply {
            val scope = if (filtered) "${statsType.label} " else ""
            addView(sectionTitle(p, "평소 하루",
                if (sum.completeDaysOnly) "${scope}완전 기록 ${sum.days.size}일 기준" else "${scope}부분 기록 기준"))
            addView(tileRow(p, listOf(
                tile(p, "사용량", pct(sum.avgUsage), "중앙값 ${pct(sum.medianUsage)}"),
                tile(p, "최저 SOC", pct(sum.avgMin)),
                tile(p, "최고 SOC", pct(sum.avgMax)),
                tile(p, "충전", sum.chargesPerDay?.let { String.format(Locale.US, "%.1f회", it) } ?: "-", "하루 평균"),
            )), lp(MATCH, WRAP, top = dpi(12f)))
            if (!sum.completeDaysOnly) addView(label("하루 전체가 기록된 날이 생기면 그 날들만으로 계산합니다.",
                12f, p.muted), lp(MATCH, WRAP, top = dpi(10f)))
        }, lp(MATCH, WRAP, top = dpi(6f)))

        // Daily usage, tap a day for its detail. Days outside the filter stay on the time axis,
        // drawn faint, and can still be opened.
        fun pick(ok: (Int) -> Boolean) = days.indices.lastOrNull { days[it].day != today && days[it].minSoc != null && ok(it) }
        val defaultDay = (pick { counted[it] } ?: pick { true })?.let { days[it].day } ?: today
        val day = statsDay?.takeIf { d -> days.any { it.day == d } } ?: defaultDay
        val selectedIdx = days.indexOfFirst { it.day == day }
        page.addView(card(p).apply {
            addView(sectionTitle(p, "일일 사용량", "탭: 그날 상세"))
            addView(row().apply {
                addView(legend(p, "사용량", p.soc))
                addView(legend(p, "평균 ${pct(sum.avgUsage)}", p.ink2, dashed = true), lp(WRAP, WRAP, left = dpi(12f)))
                if (filtered) addView(legend(p, "평균 제외", p.faint), lp(WRAP, WRAP, left = dpi(12f)))
            }, lp(WRAP, WRAP, top = dpi(6f)))
            addView(UsageChartView(context, p).apply {
                zoomKey = "usage-${statsPeriod.name}"
                this.days = days; average = sum.avgUsage
                this.counted = counted
                selected = selectedIdx
                onSelect = { i -> if (statsDay != days[i].day) { statsDay = days[i].day; render() } }
            }, lp(MATCH, WRAP, top = dpi(4f)))
            addView(label("두 손가락으로 벌리면 확대(가로·세로) · 두 번 탭하면 원래대로", 12f, p.muted),
                lp(MATCH, WRAP, top = dpi(4f)))
        }, lp(MATCH, WRAP, top = gap()))

        page.addView(dayDetail(days[selectedIdx], now), lp(MATCH, WRAP, top = gap()))

        // SOC range per day.
        page.addView(card(p).apply {
            addView(sectionTitle(p, "일일 SOC 범위"))
            addView(row().apply {
                addView(legend(p, "최저–최고", p.soc))
                addView(legend(p, "평균 최저·최고", p.ink2, dashed = true), lp(WRAP, WRAP, left = dpi(12f)))
                if (filtered) addView(legend(p, "평균 제외", p.faint), lp(WRAP, WRAP, left = dpi(12f)))
            }, lp(WRAP, WRAP, top = dpi(6f)))
            addView(RangeChartView(context, p).apply {
                zoomKey = "range-${statsPeriod.name}"
                avgMin = sum.avgMin; avgMax = sum.avgMax; this.days = days
                this.counted = counted
                selected = selectedIdx
                onSelect = { i -> if (statsDay != days[i].day) { statsDay = days[i].day; render() } }
            }, lp(MATCH, WRAP, top = dpi(4f)))
            addView(tileRow(p, listOf(
                tile(p, "기간 최저", pct(sum.lowest?.minSoc), sum.lowest?.let { md.format(it.day) }, 18f),
                tile(p, "기간 최고", pct(sum.highest?.maxSoc), sum.highest?.let { md.format(it.day) }, 18f),
            )), lp(MATCH, WRAP, top = dpi(10f)))
        }, lp(MATCH, WRAP, top = gap()))

        // Charging habits: completed charges that started on a counted day.
        page.addView(card(p).apply {
            addView(sectionTitle(p, "충전 습관",
                (if (filtered) "${statsType.label}에 시작 · " else "") + "완료된 충전 ${sum.chargeCount}회"))
            addView(tileRow(p, listOf(
                tile(p, "시작 SOC", pct(sum.avgChargeStart), "평균", 18f),
                tile(p, "종료 SOC", pct(sum.avgChargeEnd), "평균", 18f),
                tile(p, "충전 시간", sum.avgChargeMinutes?.let { Format.duration((it * 60_000).toLong()) } ?: "-", "평균", 18f),
            )), lp(MATCH, WRAP, top = dpi(12f)))
        }, lp(MATCH, WRAP, top = gap()))

        // Day-by-day table, newest first; long periods are shown a page at a time.
        page.addView(card(p).apply {
            val rows = days.indices.reversed().filter { days[it].minSoc != null && counted[it] }.map { days[it] }
            addView(sectionTitle(p, "일별 기록", (if (filtered) "${statsType.label} " else "") + "${rows.size}일"))
            fun cells(vararg v: String, head: Boolean = false, bold: Boolean = false) = row().apply {
                v.forEachIndexed { i, t ->
                    val tv = if (head) caption(t, p) else if (i == 0) label(t, 13f, if (bold) p.ink else p.ink2, bold = bold)
                        else number(t, 14f, p.ink)
                    tv.gravity = if (i == 0) Gravity.START else Gravity.END
                    tv.maxLines = 1
                    addView(tv, lp(0, WRAP, if (i == 0) 1.6f else 1f))
                }
                setPadding(0, dpi(9f), 0, dpi(9f))
            }
            addView(cells("날짜", "사용", "충전", "최저", "최고", head = true), lp(MATCH, WRAP, top = dpi(6f)))
            val dfmt = DateTimeFormatter.ofPattern("M/d (E)", Locale.KOREAN)
            for (d in rows.take(tableRows)) {
                addView(View(context).apply { setBackgroundColor(p.grid) }, lp(MATCH, Math.max(1, dpi(1f))))
                addView(cells(dfmt.format(d.day), "${d.usage}%", "${d.charged}%", "${d.minSoc}%", "${d.maxSoc}%",
                    bold = d.day == day).apply {
                    setOnClickListener { if (statsDay != d.day) { statsDay = d.day; render() } }
                })
            }
            if (rows.size > tableRows) addView(button("이전 기록 더 보기 (${rows.size - tableRows}일)") {
                tableRows += TABLE_PAGE * 2; render()
            }, lp(MATCH, WRAP, top = dpi(8f)))
        }, lp(MATCH, WRAP, top = gap()))

        setPage(page, header)
    }

    /** One day in detail: totals, SOC curve, temperature and voltage. */
    private fun dayDetail(stat: DayStat, now: Long): View = card(p).apply {
        val day = stat.day
        val start = Stats.dayStart(day, zone)
        val end = Stats.dayStart(day.plusDays(1), zone)
        val sessions = db.sessionsOverlapping(start, end)
        val points = dayPoints(start, end)
        val charges = sessions.count { it.type == SessionType.CHARGE && it.startTs >= start }

        addView(sectionTitle(p, dateTitle(day) + relativeDay(day), "세션 ${sessions.size} · 충전 ${charges}회"))
        addView(tileRow(p, listOf(
            tile(p, "사용량", "${stat.usage}%", size = 18f),
            tile(p, "충전량", "${stat.charged}%", size = 18f),
            tile(p, "최저", pct(stat.minSoc), size = 18f),
            tile(p, "최고", pct(stat.maxSoc), size = 18f),
        )), lp(MATCH, WRAP, top = dpi(12f)))
        if (points.isEmpty()) {
            addView(label("이 날의 기록이 없습니다.", 14f, p.ink2), lp(MATCH, WRAP, top = dpi(12f)))
            return@apply
        }
        addView(row().apply {
            addView(legend(p, "SOC", p.soc))
            if (points.any { it.est }) addView(legend(p, "추정", p.ink2, dashed = true), lp(WRAP, WRAP, left = dpi(12f)))
            addView(legend(p, "충전 구간", p.accentDim), lp(WRAP, WRAP, left = dpi(12f)))
        }, lp(WRAP, WRAP, top = dpi(14f)))
        addView(SocChartView(context, p).apply {
            zoomKey = "detail-soc"
            set(points, sessions, start, end, now, zone)
        }, lp(MATCH, WRAP, top = dpi(2f)))
        val observed = points.filter { !it.est }
        val temps = observed.filter { it.temp > 0 }.map { it.ts to it.temp }
        val drain = Drain.series(points, minOf(now, end), if (now in start until end) current else null)
        val drainSum = Drain.summary(drain)
        miniChart("배터리 온도", temps, c1, 20, p.temp, start, end, now)?.let { addView(it, lp(MATCH, WRAP, top = dpi(12f))) }
        miniChart("방전 전류", drain, ma, 300, p.volt, start, end, now,
            note = "충전 중 구간 제외 · 측정 간격의 평균 (전하량 감소 ÷ 시간)",
            range = drainSum?.let { "평균 ${it.first}mA · 최대 ${it.second}mA" },
            fromZero = true, gapLabel = "방전 아님")?.let { addView(it, lp(MATCH, WRAP, top = dpi(8f))) }
    }

    /** Shows a scrolling page that is rebuilt from scratch (Stats, Data). */
    private fun setPage(page: View, header: View? = null) {
        // Rebuilt after a battery update or a selection: stay where the user was scrolled to.
        val keepY = if (pageTab == tab) pageScroll?.scrollY ?: 0 else 0
        pageTab = tab
        sessionList = null
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page)
            if (keepY > 0) post { scrollTo(0, keepY) }
        }
        pageScroll = scroll
        content.removeAllViews()
        content.addView(if (header == null) scroll else column().apply {
            addView(header, lp(MATCH, WRAP))
            addView(scroll, lp(MATCH, 0, 1f))
        })
    }

    /** Title row with the day's range, above a compact line chart. Null when there is no data. */
    private fun miniChart(
        name: String, pts: List<Pair<Long, Int>>, fmt: (Int) -> String, minSpan: Int, color: Int,
        start: Long, end: Long, now: Long, note: String? = null, range: String? = null,
        fromZero: Boolean = false, gapLabel: String = "-",
    ): View? {
        val values = pts.filter { it.second != Drain.BREAK }
        if (values.isEmpty()) return null
        return column().apply {
            addView(row().apply {
                addView(View(context).apply { background = roundRect(color, 2f) }, lp(dpi(10f), dpi(3f)))
                addView(label(name, 14f, p.ink, bold = true), lp(0, WRAP, 1f, left = dpi(6f)))
                addView(label(range ?: "${fmt(values.minOf { it.second })} – ${fmt(values.maxOf { it.second })}", 13f, p.ink2))
            })
            if (note != null) addView(label(note, 12f, p.muted), lp(WRAP, WRAP, top = dpi(3f)))
            addView(MiniLineChart(context, p, color, fmt, minSpan, fromZero, gapLabel).apply {
                zoomKey = "detail-$name"
                set(pts, start, end, now, zone)
            },
                lp(MATCH, WRAP, top = dpi(2f)))
        }
    }

    // -------------------------------------------------------------- sessions

    private fun sessionLine1(s: Session): String {
        val kind = if (s.type == SessionType.CHARGE) "충전" else "사용"
        val est = if (s.est) "  · 추정" else ""
        return "$kind  ${s.startSoc}% → ${s.endSoc}%  (${Format.signed(s.delta)}%)$est"
    }

    private val phone: ChargeSpec.Phone? by lazy { ChargeSpec.phone(Build.MODEL) }
    private val specCache = HashMap<Long, ChargeSpec.Result?>()

    /** Charging tier of a charge session; closed sessions are cached. */
    private fun chargeSpec(s: Session, now: Long): ChargeSpec.Result? {
        if (s.type != SessionType.CHARGE) return null
        if (!s.open && specCache.containsKey(s.id)) return specCache[s.id]
        val r = ChargeSpec.classify(db.samples(s.startTs, (s.endTs ?: now) + 1), s.startSoc, phone)
        if (!s.open) specCache[s.id] = r
        return r
    }

    /** Time line: start → end to the second (end date only when it differs) and duration. */
    private fun sessionTimeLine(s: Session, now: Long): String {
        val endTs = s.endTs
        val sameDay = endTs != null && java.time.Instant.ofEpochMilli(endTs).atZone(zone).toLocalDate() ==
            java.time.Instant.ofEpochMilli(s.startTs).atZone(zone).toLocalDate()
        val end = when {
            endTs == null -> "진행 중"
            sameDay -> Format.timeSec(endTs, zone)
            else -> Format.dateTimeSec(endTs, zone)
        }
        return "${Format.dateTimeSec(s.startTs, zone)} → $end  (${Format.durationSec(s.durationMs(now))})"
    }

    /** Value line: rate, and for charging the tier and when the top SOC was reached. */
    private fun sessionLine2(s: Session, now: Long): String {
        val endTs = s.endTs
        val parts = arrayListOf<String>()
        s.ratePerHour(now)?.let { parts.add(String.format(Locale.US, "%.1f%%/시간", it)) }
        if (s.type == SessionType.CHARGE) {
            val spec = chargeSpec(s, now)
            parts.add(spec?.label() ?: Plug.labelKo(s.plug))
            val settled = (endTs ?: now) - s.peakTs > 20 * 60_000L
            if (settled && s.maxSoc > s.startSoc) parts.add("${s.maxSoc}% 도달 ${Format.timeSec(s.peakTs, zone)}")
        }
        if (parts.isEmpty()) parts.add(if (s.open) "측정 중" else "-")
        return parts.joinToString(" · ")
    }

    /** Session row: ⚡ badge for charging, 📱 for use; the card tint is applied by [sessionCard]. */
    private fun sessionRow(s: Session, now: Long): View = row().apply {
        val charge = s.type == SessionType.CHARGE
        addView(label(if (charge) "⚡" else "📱", 18f, p.ink).apply {
            gravity = Gravity.CENTER
            background = roundRect(if (charge) p.chargeBadge else p.useBadge, 20f)
        }, lp(dpi(40f), dpi(40f)))
        addView(column().apply {
            addView(label(sessionLine1(s), 15f, p.ink, bold = true))
            addView(number(sessionTimeLine(s, now), 13f, p.ink2), lp(WRAP, WRAP, top = dpi(5f)))
            addView(label(sessionLine2(s, now), 14f, p.ink, bold = true).apply { alpha = 0.85f },
                lp(WRAP, WRAP, top = dpi(4f)))
        }, lp(0, WRAP, 1f, left = dpi(10f)))
    }

    /** Tinted card: light red while charging, light blue while in use. */
    private fun sessionCard(s: Session, now: Long): View = column().apply {
        val charge = s.type == SessionType.CHARGE
        background = roundRect(if (charge) p.chargeBg else p.useBg, 18f, if (charge) p.chargeLine else p.useLine)
        val pad = dpi(14f)
        setPadding(pad, pad, pad, pad)
        addView(sessionRow(s, now))
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
            db.days.range(oldest, newest, zone).forEach { stats[it.day] = it }
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
                            addView(sessionCard(row.session, now))
                        }
                    }
            }
        }
        val page = column().apply {
            addView(pageHeader("세션").apply { setPadding(dpi(18f), dpi(14f), dpi(16f), dpi(2f)) })
            if (rows.isEmpty()) {
                addView(label("아직 기록된 세션이 없습니다.", 15f, p.ink2).apply {
                    setPadding(dpi(22f), dpi(8f), dpi(22f), 0)
                })
            } else {
                addView(list, lp(MATCH, 0, 1f))
            }
        }
        if (oldList != null && rows.isNotEmpty()) list.setSelectionFromTop(keepPos, keepTop)
        sessionList = list
        pageScroll = null
        pageTab = tab
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
        addView(label(parts.joinToString(" · "), 13f, p.ink2), lp(MATCH, WRAP, top = dpi(4f)))
    }

    // ------------------------------------------------------------------ data

    private fun renderData() {
        val page = page()
        page.addView(pageHeader("데이터"))

        // Appearance: follows the system or is fixed; applied at once by recreating the screen.
        page.addView(card(p).apply {
            val mode = Theme.mode(this@MainActivity)
            addView(sectionTitle(p, "화면 모드", if (p.dark) "현재 다크" else "현재 라이트"))
            ThemeMode.values().forEachIndexed { i, m ->
                val note = if (m == ThemeMode.SYSTEM)
                    "휴대폰의 다크 모드 설정에 맞춥니다 (지금 ${if (Theme.systemDark(this@MainActivity)) "다크" else "라이트"})" else null
                addView(optionRow(p, m.label, note, m == mode) {
                    Theme.setMode(this@MainActivity, m)
                    recreate()
                }, lp(MATCH, WRAP, top = if (i == 0) dpi(4f) else 0))
            }
        }, lp(MATCH, WRAP, top = dpi(14f)))

        page.addView(card(p).apply {
            addView(title("기록 상태"))
            val first = db.firstSampleTs()
            addView(kv(p, "기록 시작", first?.let { Format.dateTime(it, zone) } ?: "-"), lp(MATCH, WRAP, top = dpi(8f)))
            addView(kv(p, "SOC 기록 수", db.sampleCount().toString()))
            addView(kv(p, "세션 수", db.sessionCount().toString()))
            val estCount = db.estimatedCount()
            addView(kv(p, "추정 기록 수", estCount.toString()))
            addView(label(
                "기록이 끊겼던 구간(강제 중지, 전원 꺼짐 등)은 앞뒤 잔량과 평소 충전·사용 속도로 추정해 채웁니다. " +
                    "그래프에서는 점선, 세션에서는 '추정'으로 표시되며 내보내기 파일의 estimated 열이 1입니다. " +
                    "온도·전압 차트에는 추정값을 쓰지 않습니다.", 13f, p.ink2), lp(MATCH, WRAP, top = dpi(2f)).apply { bottomMargin = dpi(6f) })
            db.lastSample()?.let { addView(kv(p, "마지막 기록", Format.dateTime(it.ts, zone))) }
            addView(kv(p, "이 휴대폰 최대 충전", phone?.let {
                "유선 ${it.wiredW}W" + (it.wirelessW?.let { w -> " · 무선 ${w}W" } ?: "")
            } ?: "목록에 없는 기종"))
            addView(label("모델 ${Build.MODEL}. 충전 세션마다 충전기 보고 최대 전력과 실제 배터리 입력 최고값으로 " +
                "가장 가까운 단계(유선 10·15·25·45·60W, 무선 5·10·15·20·25W)로 판정합니다. 휴대폰 최대치를 넘는 값은 " +
                "'최대 지원'으로 표시하고, 충전이 3분 미만이면 '판정 보류'로 둡니다. " +
                "잔량 70% 이상에서 시작한 충전은 속도가 제한돼 실제보다 낮게 나올 수 있습니다.", 13f, p.muted),
                lp(MATCH, WRAP).apply { bottomMargin = dpi(4f) })
            val cur = current
            addView(kv(p, "전압(참조값, 저장만)", cur?.let { "${Volt.reference(it)} mV" } ?: "-"))
            addView(label(Recorder.describeVoltage(this@MainActivity, cur), 13f, p.ink2), lp(MATCH, WRAP))
            addView(label("전압은 순간 변동이 커서 대시보드에는 표시하지 않고, 기록마다 저장해 내보내기(samples.csv의 " +
                "voltage_mv, ocv_mv, ocv_source)에서 활용할 수 있습니다.", 13f, p.muted), lp(MATCH, WRAP, top = dpi(4f)))
            if (cur != null && cur.ocvSrc != Ocv.SRC_DIRECT) addView(label(
                "앱이 읽을 수 있는 연료게이지 OCV(batt_vfocv, voltage_ocv 등)를 찾지 못해, 전류가 50mA 미만일 때는 " +
                    "단자 전압을 OCV 근사로, 그 밖에는 단자 전압 + 전류×내부저항으로 OCV를 추정합니다. " +
                    "내부저항은 이 폰의 전류 변화 때 전압 변화로 계속 학습합니다. 확인한 후보 경로는 충전기 진단 화면에 있습니다.",
                12f, p.muted), lp(MATCH, WRAP, top = dpi(4f)).apply { bottomMargin = dpi(4f) })
            val exempt = isExempt()
            addView(kv(p, "배터리 최적화 제외", if (exempt) "설정됨" else "미설정"))
            addView(label(
                (if (exempt) "" else "제외하지 않으면 휴대폰이 앱을 절전 상태로 보내 기록이 끊길 수 있습니다.\n") +
                    "삼성: 앱 정보 → 배터리 → '제한 없음'이면 됩니다. 15분마다 기록 상태를 점검해 " +
                    "중단되었으면 다시 시작합니다.",
                12f, p.ink2), lp(MATCH, WRAP, top = dpi(4f)))
            if (!exempt) addView(button("배터리 최적화에서 제외") { requestExemption() }, lp(MATCH, WRAP, top = dpi(10f)))
            addView(button("앱 배터리 설정 열기") { openAppSettings() }, lp(MATCH, WRAP, top = dpi(10f)))
        }, lp(MATCH, WRAP, top = dpi(14f)))

        // Recording cadence.
        page.addView(card(p).apply {
            val cur = Recorder.cadence(this@MainActivity)
            addView(sectionTitle(p, "기록 주기", cur.describe()))
            val all = dev.chargelog.core.Cadence.values().toList()
            addView(segmented(p, all.map { it.label }, all.indexOf(cur)) { i ->
                Recorder.setCadence(this@MainActivity, all[i])
                Watchdog.schedule(this@MainActivity)
                render()
            }, lp(WRAP, WRAP, top = dpi(10f)))
            addView(label(all.joinToString("\n") { "${it.label}: ${it.describe()} · 하루 약 ${it.rowsPerDay()}줄" },
                12f, p.ink2).apply { setLineSpacing(dp(3f), 1f) }, lp(MATCH, WRAP, top = dpi(10f)))
            addView(label(
                "값이 바뀌지 않아도 이 주기마다 잔량·온도·전류를 한 줄씩 저장합니다. 화면이 켜져 있을 때는 휴대폰이 " +
                    "이미 깨어 있어 추가 배터리 소모가 거의 없고, 꺼져 있을 때는 정확한 알람으로 잠깐 깨워 한 줄만 " +
                    "저장합니다(깊은 대기 상태에서는 시스템이 간격을 늘릴 수 있음). 잔량 1%·충전기 연결·온도 1°C " +
                    "변화는 주기와 상관없이 바로 기록됩니다.", 13f, p.muted), lp(MATCH, WRAP, top = dpi(8f)))
        }, lp(MATCH, WRAP, top = dpi(12f)))

        page.addView(card(p).apply {
            addView(title("앱 안내"))
            addView(label("충전 모니터링 전용 앱입니다. 임의로 배포하지 마세요.", 14f, p.ink2),
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
            addView(label("저장할 위치를 고르면 파일로 저장됩니다. 시각은 ISO 8601 형식입니다.", 14f, p.ink2),
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
                        specCache.clear()
                        statsDay = null
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
        label(text, 14f, if (danger) p.danger else p.ink, bold = true).apply {
            gravity = Gravity.CENTER
            background = roundRect(p.bg, 12f, p.line)
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
                out.use { Exporter(this, db, zone).write(kind, it) }
                "저장했습니다: ${kind.label}"
            } catch (e: Exception) {
                "저장 실패: ${e.message}"
            }
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }.start()
    }

    companion object {
        private const val REQ_EXPORT = 100
        private const val TABLE_PAGE = 31
        private const val COVER_KEY = "cover_accepted_v1"
        /** Roughly a year of typical use; older sessions remain in the DB and in exports. */
        private const val SESSION_HISTORY_LIMIT = 3000
        private const val NOTIF_PERMISSION = "android.permission.POST_NOTIFICATIONS"
    }
}
