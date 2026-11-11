package dev.chargelog.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class FakeStore : Store {
    val samples = ArrayList<Sample>()
    val sessions = ArrayList<Session>()
    private var nextId = 1L
    override fun lastSample() = samples.lastOrNull()
    override fun insertSample(s: Sample) { samples.add(s) }
    override fun latestSessions(limit: Int) =
        sessions.sortedWith(compareByDescending<Session> { it.startTs }.thenByDescending { it.id }).take(limit)
    override fun insertSession(s: Session): Long { val id = nextId++; sessions.add(s.copy(id = id)); return id }
    override fun updateSession(s: Session) { sessions[sessions.indexOfFirst { it.id == s.id }] = s }
}

class CoreTest {
    private val min = 60_000L
    private val zone = ZoneId.of("Asia/Seoul")
    private fun r(tMin: Long, level: Int, plug: Int = Plug.NONE, temp: Int = 300) =
        Sample(tMin * min, level, plug, if (plug == 0) 3 else 2, temp, 4000)

    @Test fun dedupesUnchangedReadings() {
        val st = FakeStore(); val t = Tracker(st)
        assertTrue(t.onReading(r(0, 50)))
        assertTrue(!t.onReading(r(1, 50)))
        assertTrue(t.onReading(r(2, 49)))
        assertEquals(2, st.samples.size)
        assertEquals(1, st.sessions.size)
    }

    @Test fun temperatureStepsAreSampled() {
        val st = FakeStore(); val t = Tracker(st)
        t.onReading(r(0, 50, temp = 300))
        assertTrue(!t.onReading(r(1, 50, temp = 309)))   // < 1 °C: ignored
        assertTrue(t.onReading(r(2, 50, temp = 311)))    // 1.1 °C since last stored
        assertTrue(!t.onReading(r(3, 50, temp = 305)))
        assertEquals(listOf(300, 311), st.samples.map { it.temp })
    }

    @Test fun recordsChargeStartAndEnd() {
        val st = FakeStore(); val t = Tracker(st)
        t.onReading(r(0, 30))
        t.onReading(r(60, 18))
        t.onReading(r(61, 18, Plug.AC))          // plug in
        t.onReading(r(120, 60, Plug.AC, temp = 380))
        t.onReading(r(200, 100, Plug.AC))
        t.onReading(r(400, 100, Plug.AC))        // sits at 100
        t.onReading(r(500, 100))                  // unplug
        t.onReading(r(560, 90))

        val s = st.sessions.sortedBy { it.startTs }
        assertEquals(3, s.size)
        val (d1, c, d2) = s
        assertEquals(SessionType.DISCHARGE, d1.type)
        assertEquals(30, d1.startSoc); assertEquals(18, d1.endSoc); assertEquals(61 * min, d1.endTs)
        assertEquals(-12, d1.delta)

        assertEquals(SessionType.CHARGE, c.type)
        assertEquals(61 * min, c.startTs); assertEquals(18, c.startSoc)
        assertEquals(500 * min, c.endTs); assertEquals(100, c.endSoc)
        assertEquals(200 * min, c.peakTs)        // reached 100% long before unplugging
        assertEquals(Plug.AC, c.plug); assertEquals(380, c.maxTemp)

        assertTrue(d2.open)
        assertEquals(100, d2.startSoc); assertEquals(90, d2.endSoc); assertEquals(90, d2.minSoc)
    }

    @Test fun quickReplugKeepsEverySession() {
        val st = FakeStore(); val t = Tracker(st)
        t.onReading(r(0, 40, Plug.WIRELESS))
        t.onReading(r(30, 60, Plug.WIRELESS))
        t.onReading(r(31, 60))                    // unplug
        t.onReading(r(32, 60, Plug.WIRELESS))     // re-plug a minute later
        t.onReading(r(33, 60))                    // and again
        t.onReading(r(34, 61, Plug.WIRELESS))
        t.onReading(r(60, 80, Plug.WIRELESS))
        val s = st.sessions.sortedBy { it.startTs }
        assertEquals(5, s.size)
        assertEquals(listOf(SessionType.CHARGE, SessionType.DISCHARGE, SessionType.CHARGE,
            SessionType.DISCHARGE, SessionType.CHARGE), s.map { it.type })
        // The first charge is closed and untouched by what came after.
        assertEquals(40, s[0].startSoc); assertEquals(60, s[0].endSoc); assertEquals(31 * min, s[0].endTs)
        // Sessions chain without gaps and only the last one is open.
        for (i in 1 until s.size) assertEquals(s[i - 1].endTs, s[i].startTs)
        assertEquals(1, s.count { it.open }); assertTrue(s.last().open)
        assertEquals(80, s.last().endSoc)
    }

    @Test fun longUnplugStartsNewSessions() {
        val st = FakeStore(); val t = Tracker(st)
        t.onReading(r(0, 40, Plug.AC))
        t.onReading(r(30, 60))
        t.onReading(r(40, 58, Plug.AC))
        assertEquals(3, st.sessions.size)
    }

    @Test fun clockGoingBackwardsDoesNotStopRecording() {
        val st = FakeStore(); val t = Tracker(st)
        t.onReading(r(100, 50))
        t.onReading(r(40, 49))                    // clock set back an hour
        t.onReading(r(41, 49, Plug.AC))
        assertEquals(listOf(50, 49, 49), st.samples.map { it.level })
        assertTrue(st.samples.zipWithNext().all { (a, b) -> b.ts > a.ts })
        val s = st.sessions.sortedBy { it.startTs }
        assertEquals(2, s.size)
        assertTrue(s[0].endTs!! >= s[0].startTs)
        assertEquals(SessionType.CHARGE, s[1].type)
    }

    @Test fun dailyUsageSplitsAtMidnightAndCanExceed100() {
        val day = LocalDate.of(2026, 11, 10)
        val t0 = Stats.dayStart(day, zone)
        fun s(offMin: Long, level: Int) = Sample(t0 + offMin * min, level, 0, 3, 300, 4000)
        val baseline = s(-30, 90)                 // 23:30 the day before
        val samples = listOf(
            s(60, 80),          // -10 (counts toward the 10th, crossing midnight)
            s(600, 20),         // -60
            s(700, 100),        // +80
            s(1300, 35),        // -65
            s(1440 + 60, 30),   // next day: -5
        )
        val out = Stats.daily(samples, baseline, zone, day, day.plusDays(2))
        assertEquals(3, out.size)
        assertEquals(135, out[0].usage); assertEquals(80, out[0].charged)
        assertEquals(20, out[0].minSoc); assertEquals(100, out[0].maxSoc)
        assertEquals(5, out[1].usage); assertEquals(30, out[1].minSoc); assertEquals(35, out[1].maxSoc)
        assertEquals(0, out[2].usage); assertEquals(30, out[2].minSoc)   // carried SOC
    }

    @Test fun sessionsGroupedByEveryDayTheyCover() {
        val d10 = LocalDate.of(2026, 11, 10)
        val t0 = Stats.dayStart(d10, zone)
        fun sess(id: Long, type: SessionType, fromMin: Long, toMin: Long?) =
            Session(id, type, t0 + fromMin * min, 50, toMin?.let { t0 + it * min }, 50, 0,
                t0 + fromMin * min, 50, 50, 300)
        val a = sess(1, SessionType.DISCHARGE, 600, 1380)        // 10th only
        val b = sess(2, SessionType.CHARGE, 1380, 1440 + 420)    // 23:00 → 07:00 next day
        val c = sess(3, SessionType.DISCHARGE, 1440 + 420, 2880) // ends exactly at midnight
        val d = sess(4, SessionType.CHARGE, 2880, null)          // open, on the 12th
        val now = t0 + (2880 + 60) * min
        val g = Stats.sessionsByDay(listOf(a, b, c, d), zone, now)
        assertEquals(listOf(d10.plusDays(2), d10.plusDays(1), d10), g.map { it.first })
        assertEquals(listOf(4L), g[0].second.map { it.id })
        assertEquals(listOf(3L, 2L), g[1].second.map { it.id })
        assertEquals(listOf(2L, 1L), g[2].second.map { it.id })
    }

    @Test fun summaryUsesOnlyFullyRecordedDays() {
        val d = LocalDate.of(2026, 11, 1)
        fun day(i: Long, usage: Int, mn: Int?, mx: Int?) = DayStat(d.plusDays(i), usage, 0, mn, mx)
        val period = listOf(
            day(0, 0, null, null),     // before install
            day(1, 20, 60, 80),        // install day (partial)
            day(2, 120, 15, 100),
            day(3, 80, 30, 95),
            day(4, 100, 25, 100),
            day(5, 10, 90, 100),       // today (partial)
        )
        val installTs = Stats.dayStart(d.plusDays(1), zone) + 15 * 60 * min
        fun charge(dayIdx: Long, startSoc: Int, endSoc: Int, minutes: Long): Session {
            val t = Stats.dayStart(d.plusDays(dayIdx), zone) + 22 * 60 * min
            return Session(0, SessionType.CHARGE, t, startSoc, t + minutes * min, endSoc, 1, t, startSoc, endSoc, 300)
        }
        val sessions = listOf(charge(2, 20, 100, 120), charge(3, 30, 80, 60), charge(3, 40, 60, 30))
        val sum = Summaries.of(period, sessions, installTs, d.plusDays(5), zone)
        assertTrue(sum.completeDaysOnly)
        assertEquals(listOf(d.plusDays(2), d.plusDays(3), d.plusDays(4)), sum.days.map { it.day })
        assertEquals(100.0, sum.avgUsage!!, 1e-9)
        assertEquals(100, sum.medianUsage)
        assertEquals((15 + 30 + 25) / 3.0, sum.avgMin!!, 1e-9)
        assertEquals((100 + 95 + 100) / 3.0, sum.avgMax!!, 1e-9)
        assertEquals(15, sum.lowest!!.minSoc); assertEquals(d.plusDays(2), sum.lowest!!.day)
        assertEquals(1.0, sum.chargesPerDay!!, 1e-9)
        assertEquals(3, sum.chargeCount)
        assertEquals(30.0, sum.avgChargeStart!!, 1e-9)
        assertEquals(80.0, sum.avgChargeEnd!!, 1e-9)
        assertEquals(70.0, sum.avgChargeMinutes!!, 1e-9)
    }

    @Test fun summaryFallsBackToPartialDays() {
        val d = LocalDate.of(2026, 11, 11)
        val sum = Summaries.of(listOf(DayStat(d, 12, 0, 48, 61)), emptyList(),
            Stats.dayStart(d, zone) + 60 * min, d, zone)
        assertTrue(!sum.completeDaysOnly)
        assertEquals(12.0, sum.avgUsage!!, 1e-9)
        assertNull(sum.avgChargeStart)
    }

    @Test fun voltageUnits() {
        assertEquals(3850, Volt.toMv(3850))
        assertEquals(3850, Volt.toMv(3_850_123))
        assertNull(Volt.toMv(0)); assertNull(Volt.toMv(385)); assertNull(Volt.toMv(38_500))
        val s = Sample(0, 50, 0, 3, 300, 3900)
        assertEquals(3900, Volt.reference(s))
        assertEquals(3870, Volt.reference(s.copy(ocv = 3870)))
        assertTrue(Format.sampleCsv(s.copy(ocv = 3870, ocvSrc = Ocv.SRC_DIRECT), zone).contains(",3900,,,3870,direct,"))
    }

    // ---- gap estimation ----

    private fun checkFill(st: FakeStore, from: Long, to: Long) {
        val est = st.samples.filter { it.est }
        assertTrue(est.all { it.ts > from && it.ts < to })
        assertTrue(st.samples.zipWithNext().all { (a, b) -> b.ts > a.ts })
        // Consecutive stored readings never jump by more than 1 % once filled.
        assertTrue(st.samples.zipWithNext().all { (a, b) -> Math.abs(a.level - b.level) <= 1 })
    }

    @Test fun smallChangesAreNotEstimated() {
        val st = FakeStore(); val t = Tracker(st)
        t.onReading(r(0, 60))
        t.onReading(r(300, 61))                          // +1 after 5 h: recalibration, not a gap
        t.onReading(r(301, 58))                          // −3 within a minute: sudden jump, too short
        assertTrue(st.samples.none { it.est })
        assertEquals(1, st.sessions.size)
    }

    @Test fun missedDischargeIsLinear() {
        val st = FakeStore(); val t = Tracker(st)
        t.onReading(r(0, 80))
        t.onReading(r(360, 60))                          // 6 h later, 20 % lower
        checkFill(st, 0, 360 * min)
        val est = st.samples.filter { it.est }
        assertEquals((79 downTo 61).toList(), est.map { it.level })
        assertTrue(est.all { !it.charging })
        // Evenly spread: 61 % sits close to the end.
        assertTrue(est.last().ts > 330 * min)
        assertEquals(1, st.sessions.size)
        assertTrue(st.sessions[0].est)
    }

    @Test fun missedChargeWhileUnpluggedBecomesEstimatedChargeSession() {
        val st = FakeStore(); val t = Tracker(st)
        t.onReading(r(0, 30))
        t.onReading(r(8 * 60, 90))                       // 8 h later, unplugged at 90 %
        checkFill(st, 0, 8 * 60 * min)
        val s = st.sessions.sortedBy { it.startTs }
        assertEquals(listOf(SessionType.DISCHARGE, SessionType.CHARGE, SessionType.DISCHARGE), s.map { it.type })
        val charge = s[1]
        assertTrue(charge.est)
        assertEquals(Plug.ESTIMATED, charge.plug)
        assertTrue(charge.startSoc <= 30)                // went down first, then charged
        assertEquals(90, charge.endSoc)
        assertEquals(8 * 60 * min, charge.endTs)         // unplug seen at the real reading
        assertTrue(s[2].open && !s[2].est)
    }

    @Test fun pluggedInDuringGapContinuesIntoRealChargeSession() {
        val st = FakeStore(); val t = Tracker(st)
        t.onReading(r(0, 40))
        t.onReading(r(3 * 60, 70, Plug.AC))              // found charging at 70 %
        checkFill(st, 0, 3 * 60 * min)
        val s = st.sessions.sortedBy { it.startTs }
        assertEquals(listOf(SessionType.DISCHARGE, SessionType.CHARGE), s.map { it.type })
        assertTrue(s[1].open && s[1].est)
        assertEquals(Plug.AC, s[1].plug)                 // real charger replaces the estimate
        assertEquals(70, s[1].endSoc)
    }

    @Test fun unpluggedDuringGapChargesToFullThenDischarges() {
        val st = FakeStore(); val t = Tracker(st)
        t.onReading(r(0, 30, Plug.AC))
        t.onReading(r(9 * 60, 85))                       // overnight: found unplugged at 85 %
        checkFill(st, 0, 9 * 60 * min)
        assertEquals(100, st.samples.maxOf { it.level })
        val s = st.sessions.sortedBy { it.startTs }
        assertEquals(listOf(SessionType.CHARGE, SessionType.DISCHARGE), s.map { it.type })
        assertEquals(100, s[0].maxSoc)
        assertTrue(s[0].est && s[1].est)
        assertTrue(s[1].open)
    }

    @Test fun ratesComeFromObservedSessions() {
        val h = 60 * min
        val sessions = listOf(
            Session(1, SessionType.DISCHARGE, 0, 100, 10 * h, 50, 0, 0, 50, 100, 300),     // 5 %/h
            Session(2, SessionType.CHARGE, 10 * h, 50, 11 * h, 100, 1, 0, 50, 100, 300),   // 50 %/h
            Session(3, SessionType.CHARGE, 11 * h, 0, 12 * h, 90, 1, 0, 0, 90, 300, est = true),
        )
        val rates = GapFiller.rates(sessions, 12 * h)
        assertEquals(5.0, rates.dischargePerHour, 1e-9)
        assertEquals(50.0, rates.chargePerHour, 1e-9)
    }

    @Test fun ocvSourcesAndUnits() {
        assertEquals(0, Ocv.toMicroAmps(Int.MIN_VALUE))
        assertNull(Ocv.estimate(3900, 0, false, 0.1))          // no current: cannot say
        assertEquals(3900 to Ocv.SRC_REST, Ocv.estimate(3900, -30_000, false, 0.1))
        assertEquals(3980 to Ocv.SRC_IR, Ocv.estimate(3900, -800_000, false, 0.1))    // discharge: V + I·R
        assertEquals(4100 to Ocv.SRC_IR, Ocv.estimate(4200, 1_000_000, true, 0.1))    // charging: V − I·R
    }

    @Test fun internalResistanceFit() {
        val fit = RFit()
        assertEquals(Ocv.DEFAULT_R_OHM, fit.ohms, 1e-9)
        var t = 0L
        // Same SOC, discharging; every jump of 0.5 A drops the voltage by 50 mV → 0.1 Ω.
        repeat(6) {
            fit.add(t, 50, true, 3900, -200_000); t += 10_000
            assertTrue(fit.add(t, 50, true, 3850, -700_000)); t += 10_000
        }
        assertEquals(0.1, fit.ohms, 1e-6)
        assertTrue(!fit.add(t + 10_000, 49, true, 3800, -1_200_000))  // SOC changed: ignored
        assertTrue(!fit.add(t + 20_000, 49, false, 3700, -2_200_000)) // charging: ignored
    }

    @Test fun dischargeCurrentFromChargeCounter() {
        fun smp(m: Long, level: Int, q: Int, plug: Int = Plug.NONE, cur: Int = 0) =
            Sample(m * min, level, plug, if (plug == 0) 3 else 2, 300, 3900, chargeUah = q, currentUa = cur)
        val samples = listOf(
            smp(0, 80, 4_000_000),
            smp(60, 70, 3_600_000),            // 400 mAh in 1 h → 400 mA
            smp(90, 65, 3_300_000),            // 300 mAh in 30 min → 600 mA
            smp(91, 65, 3_300_000, Plug.AC),   // 1 min, counter flat, no current → no value
            smp(150, 90, 4_200_000, Plug.AC),  // charging: gap
            smp(151, 90, 4_200_000),           // unplugged
        )
        val now = 211 * min
        val ser = Drain.series(samples, now, smp(0, 88, 4_100_000, cur = -250_000))
        // 0–60 at 400, 60–90 at 600, gap, then 151–now at 100 mAh / 60 min = 100 mA
        assertEquals(listOf(0L to 400, 60 * min to 400, 60 * min to 600, 90 * min to 600, 90 * min to Drain.BREAK,
            151 * min to 100, now to 100), ser)
        val (avg, max) = Drain.summary(ser)!!
        assertEquals((400 * 60 + 600 * 30 + 100 * 60) / 150, avg)
        assertEquals(600, max)
        // Short interval without counter change falls back to the instantaneous current.
        assertEquals(812, Drain.rate(smp(0, 50, 0), smp(1, 50, 0, cur = -812_000)))
    }

    @Test fun periodicReadingsAreStoredOnSchedule() {
        val st = FakeStore(); val t = Tracker(st)
        t.onReading(r(0, 70))
        assertTrue(!t.onReading(r(1, 70)))                                   // event, nothing changed
        assertTrue(t.onReading(r(2, 70), periodic = true))                   // scheduled: stored
        assertTrue(!t.onReading(Sample(2 * min + 10_000, 70, 0, 3, 300, 4000), periodic = true)) // < 20 s apart
        assertEquals(2, st.samples.size)
        assertEquals(1, st.sessions.size)
    }

    @Test fun minuteReadingsUseRollingCounterAverage() {
        // One reading per minute at 600 mA (10 mAh per minute), instantaneous current noisy.
        val samples = (0..10).map { m ->
            Sample(m * min, 60, 0, 3, 300, 3900, chargeUah = 3_000_000 - m * 10_000,
                currentUa = if (m % 2 == 0) -150_000 else -1_900_000)
        }
        val ser = Drain.series(samples, 10 * min, null)
        val values = ser.map { it.second }.filter { it != Drain.BREAK }
        // First 3 minutes: no 3-minute window yet → instantaneous; afterwards a steady 600 mA.
        assertTrue(values.drop(6).all { it == 600 })
        assertEquals(600, Drain.summary(ser.drop(6))!!.first)
    }

    @Test fun cadencePresets() {
        assertEquals(Cadence.STANDARD, Cadence.of(null))
        assertEquals(Cadence.SAVER, Cadence.of("SAVER"))
        assertEquals(5 * 60 + 19 * 12, Cadence.STANDARD.rowsPerDay())
    }

    private fun chg(
        tMin: Double, ua: Int, mv: Int = 4000, plug: Int = Plug.AC, maxUa: Int = 0, maxUv: Int = 0, status: Int = 2,
    ) = Sample((tMin * min).toLong(), 40, plug, status, 300, mv, currentUa = ua, maxChargeUa = maxUa, maxChargeUv = maxUv)

    @Test fun phoneTableLookup() {
        assertEquals(45, ChargeSpec.phoneMaxWatts("SM-S938N"))
        assertEquals(60, ChargeSpec.phoneMaxWatts("sm-s948u"))
        assertNull(ChargeSpec.phoneMaxWatts("Pixel 9")); assertNull(ChargeSpec.phone("Pixel 9"))
        assertNull(ChargeSpec.phone("SM-S938N")!!.wirelessW)      // wireless rating not confirmed
        // Galaxy Z Fold8 Ultra: 45 W wired, 20 W wireless (Korean model has the N suffix).
        assertEquals(ChargeSpec.Phone(45, 20), ChargeSpec.phone("SM-F976N"))
        assertEquals(ChargeSpec.Phone(45, 20), ChargeSpec.phone("sm-f976b"))
        assertEquals(45, ChargeSpec.phoneMaxWatts("SM-F976N"))
    }

    @Test fun chargeTierIsNearestSupportedStep() {
        val p45 = ChargeSpec.Phone(45, 20)
        // 22 W into the battery ÷ 0.85 ≈ 25.9 W at the charger → 25 W super fast.
        var r = ChargeSpec.classify(listOf(chg(0.0, 1_000_000), chg(2.0, 5_500_000), chg(5.0, 5_000_000)), 30, p45)!!
        assertEquals(ChargeSpec.Tier.SUPER, r.tier); assertEquals(25, r.watts)
        assertEquals(22.0, r.peakInputW!!, 1e-9); assertTrue(r.confident); assertTrue(!r.pending)
        assertEquals("초고속 충전 25W", r.label()); assertEquals("super_25w", r.code())
        // 36 W measured ≈ 42 W → 45 W (2.0).
        r = ChargeSpec.classify(listOf(chg(0.0, 9_000_000), chg(4.0, 8_800_000)), 20, p45)!!
        assertEquals(ChargeSpec.Tier.SUPER2, r.tier); assertTrue(!r.limitedByPhone)
        // 10 W measured ≈ 11.8 W → nearer to 10 than to 15.
        r = ChargeSpec.classify(listOf(chg(0.0, 2_500_000), chg(5.0, 2_400_000)), 20, p45)!!
        assertEquals(ChargeSpec.Tier.NORMAL, r.tier)
        // 11.2 W measured ≈ 13.2 W → 15 W fast.
        r = ChargeSpec.classify(listOf(chg(0.0, 2_800_000), chg(5.0, 2_700_000)), 20, p45)!!
        assertEquals(ChargeSpec.Tier.FAST, r.tier)
        // Reported 9 V × 1.67 A ≈ 15 W → fast charging, also on an unlisted phone.
        r = ChargeSpec.classify(listOf(chg(0.0, 500_000, maxUa = 1_670_000, maxUv = 9_000_000),
            chg(5.0, 500_000, maxUa = 1_670_000, maxUv = 9_000_000)), 50, null)!!
        assertEquals(ChargeSpec.Tier.FAST, r.tier)
        // Started at 85 % with only a trickle measured: not conclusive.
        r = ChargeSpec.classify(listOf(chg(0.0, 700_000), chg(10.0, 600_000)), 85, p45)!!
        assertEquals(ChargeSpec.Tier.NORMAL, r.tier); assertTrue(!r.confident)
        assertEquals("일반 충전", r.label()); assertEquals("normal_10w_min", r.code())
        assertEquals(15, ChargeSpec.nearest(12.6, listOf(10, 15, 25))); assertEquals(10, ChargeSpec.nearest(12.5, listOf(10, 15, 25)))
    }

    @Test fun chargePowerNeverExceedsPhoneMaximum() {
        val fold = ChargeSpec.phone("SM-F976N")
        // A 65 W charger reports 20 V × 3.25 A on a 45 W phone → the phone's maximum, flagged.
        var r = ChargeSpec.classify(listOf(chg(0.0, 2_000_000, maxUa = 3_250_000, maxUv = 20_000_000),
            chg(5.0, 8_000_000, maxUa = 3_250_000, maxUv = 20_000_000)), 50, fold)!!
        assertEquals(ChargeSpec.Tier.SUPER2, r.tier); assertTrue(r.limitedByPhone)
        assertEquals("초고속 충전 2.0 45W (최대 지원)", r.label())
        // Same charger on a 25 W phone.
        r = ChargeSpec.classify(listOf(chg(0.0, 2_000_000, maxUa = 4_500_000, maxUv = 10_000_000),
            chg(5.0, 2_000_000, maxUa = 4_500_000, maxUv = 10_000_000)), 50, ChargeSpec.Phone(25))!!
        assertEquals(ChargeSpec.Tier.SUPER, r.tier); assertTrue(r.limitedByPhone)
        // Measured 52 W on a 45 W phone (within 1.5×): classified at the maximum, figure not shown.
        r = ChargeSpec.classify(listOf(chg(0.0, 13_000_000), chg(1.0, 12_800_000), chg(5.0, 12_500_000)), 20, fold)!!
        assertEquals(45, r.watts); assertTrue(r.limitedByPhone); assertNull(r.peakInputW)
        // 80 W (> 1.5 × 45) is a bad reading: ignored, the rest decides.
        r = ChargeSpec.classify(listOf(chg(0.0, 5_500_000), chg(2.0, 20_000_000), chg(5.0, 5_400_000)), 20, fold)!!
        assertEquals(ChargeSpec.Tier.SUPER, r.tier); assertEquals(22.0, r.peakInputW!!, 1e-9); assertTrue(!r.limitedByPhone)
    }

    @Test fun wirelessPowerIsCappedAndStepped() {
        val fold = ChargeSpec.phone("SM-F976N")
        fun w(t: Double, ua: Int) = chg(t, ua, plug = Plug.WIRELESS)
        // The reported bug: 19 250 µA read as 19.25 A → 77 W. Dropped; the real ~12 W remains.
        var r = ChargeSpec.classify(listOf(w(0.0, 2_900_000), w(2.0, 3_000_000), w(4.0, 19_250_000), w(6.0, 2_950_000)), 30, fold)!!
        assertTrue(r.wireless); assertEquals(12.0, r.peakInputW!!, 1e-9)
        assertEquals(15, r.watts); assertEquals("무선 15W", r.label()); assertEquals("wireless_15w", r.code())
        // 22 W measured ≈ 26 W estimated: over the 20 W this phone supports → shown as the maximum.
        r = ChargeSpec.classify(listOf(w(0.0, 5_500_000), w(4.0, 5_400_000)), 30, fold)!!
        assertEquals(20, r.watts); assertTrue(r.limitedByPhone); assertNull(r.peakInputW)
        assertEquals("무선 20W (최대 지원)", r.label()); assertEquals("wireless_20w", r.code())
        // ~4 W → 5 W step; phone without a confirmed wireless rating still gets a step.
        r = ChargeSpec.classify(listOf(w(0.0, 1_000_000), w(4.0, 1_050_000)), 30, ChargeSpec.Phone(45))!!
        assertEquals(5, r.watts); assertTrue(!r.limitedByPhone)
        // A wrongly scaled lone spike below the ceiling (12 W among 0.2 W) is not the peak either.
        r = ChargeSpec.classify(listOf(w(0.0, 50_000), w(2.0, 3_000_000), w(4.0, 40_000), w(6.0, 45_000)), 30, fold)!!
        assertEquals(5, r.watts); assertEquals(0.2, r.peakInputW!!, 1e-9)
        assertEquals(22.0, ChargeSpec.plausiblePeak(listOf(4.0, 22.0, 21.0, 77.0), 67.5)!!, 1e-9)
        assertNull(ChargeSpec.plausiblePeak(listOf(77.0, 90.0), 30.0))
    }

    @Test fun shortChargeIsNotClassified() {
        val fold = ChargeSpec.phone("SM-F976N")
        // 90 seconds on the charger, current still ramping: no "1W", no tier.
        var r = ChargeSpec.classify(listOf(chg(0.0, 250_000), chg(1.5, 300_000)), 40, fold)!!
        assertTrue(r.pending); assertTrue(r.short); assertNull(r.peakInputW); assertEquals(0, r.watts)
        assertEquals("판정 보류 (짧은 충전)", r.label()); assertEquals("pending_short", r.code())
        // A single reading, even a strong one.
        r = ChargeSpec.classify(listOf(chg(0.0, 9_000_000)), 20, fold)!!
        assertTrue(r.pending)
        // Three minutes of charging is enough.
        r = ChargeSpec.classify(listOf(chg(0.0, 250_000), chg(3.0, 5_500_000), chg(3.5, 5_400_000)), 40, fold)!!
        assertTrue(!r.pending); assertEquals(25, r.watts)
        // Plugged in for an hour but full (never "charging"): held, and not called short.
        r = ChargeSpec.classify(listOf(chg(0.0, 10_000, status = 5), chg(60.0, 8_000, status = 5)), 100, fold)!!
        assertTrue(r.pending); assertTrue(!r.short); assertEquals("pending_idle", r.code())
        // Not plugged at all: nothing to classify.
        assertNull(ChargeSpec.classify(listOf(chg(0.0, -500_000, plug = Plug.NONE, status = 3)), 40, fold))
        // The export column carries the hold.
        val s = Session(7, SessionType.CHARGE, 0, 40, 90_000, 41, 1, 0, 40, 41, 300)
        assertTrue(Format.sessionCsv(s, zone, ChargeSpec.classify(listOf(chg(0.0, 250_000), chg(1.5, 300_000)), 40, fold))
            .endsWith(",,,pending_short"))
    }

    @Test fun currentUnitIsDecidedPerDevice() {
        // Unknown device: small values are mA.
        assertEquals(-812_000, Ocv.toMicroAmps(-812))
        assertEquals(1_250_000, Ocv.toMicroAmps(1_250_000))
        // A large reading proves the device reports µA ...
        assertTrue(Ocv.provesMicroAmps(1_250_000)); assertTrue(Ocv.provesMicroAmps(-20_000))
        assertTrue(!Ocv.provesMicroAmps(19_999)); assertTrue(!Ocv.provesMicroAmps(Int.MIN_VALUE))
        // ... and from then on small values stay µA: 19 250 µA is 19 mA, not 19 A (77 W).
        assertEquals(19_250, Ocv.toMicroAmps(19_250, deviceReportsMicroAmps = true))
        assertEquals(-812, Ocv.toMicroAmps(-812, deviceReportsMicroAmps = true))
        assertEquals(0.077, ChargeSpec.inputWatts(Sample(0, 90, 4, 2, 300, 4000,
            currentUa = Ocv.toMicroAmps(19_250, true)))!!, 1e-9)
        // Even before that is known, 19 250 cannot be mA (19 A), so it is not scaled.
        assertEquals(19_250, Ocv.toMicroAmps(19_250)); assertEquals(12_000_000, Ocv.toMicroAmps(12_000))
        assertEquals(0, Ocv.toMicroAmps(Int.MAX_VALUE, true))
    }

    @Test fun dailyWithNoDataIsEmpty() {
        val day = LocalDate.of(2026, 11, 10)
        val out = Stats.daily(emptyList(), null, zone, day, day)
        assertEquals(0, out[0].usage); assertNull(out[0].minSoc)
    }

    @Test fun csvAndFormatting() {
        val ts = Stats.dayStart(LocalDate.of(2026, 11, 11), zone) + 16 * 60 * min + 8 * min
        assertEquals("2026-11-11T16:08:00+09:00", Format.iso(ts, zone))
        val cols = Format.sampleCsv(Sample(ts, 64, Plug.AC, 2, 315, 4120, currentUa = -812_000,
            chargeUah = 3_100_000, health = 2, maxChargeUa = 3_000_000, maxChargeUv = 9_000_000, cycles = 210,
            ocv = 4180, ocvSrc = Ocv.SRC_IR), zone).split(",")
        assertEquals(Format.SAMPLE_HEADER.split(",").size, cols.size)
        assertEquals(listOf("$ts", "2026-11-11T16:08:00+09:00", "64", "ac", "charging", "good", "31.5", "4120",
            "-812.0", "3100.0", "4180", "ir_model", "3000.0", "9000.0", "27.0", "210", "0"), cols)
        assertEquals("7시간 30분", Format.duration(450 * min))
        assertEquals("−69", Format.signed(-69))
        val sess = Session(7, SessionType.CHARGE, ts, 18, ts + 450 * min, 100, Plug.AC, ts + 400 * min, 18, 100, 390)
        assertEquals(Format.SESSION_HEADER.split(",").size, Format.sessionCsv(sess, zone).split(",").size)
        assertEquals(10.9, sess.ratePerHour(0)!!, 0.05)
    }

    // ---- statistics filters ----

    @Test fun dayTypeSplitsWeekdaysAndWeekend() {
        val sun = LocalDate.of(2026, 11, 1)          // a Sunday
        assertEquals(java.time.DayOfWeek.SUNDAY, sun.dayOfWeek)
        val week = (0L..6L).map { sun.plusDays(it) } // Sun..Sat
        assertEquals(listOf(sun, sun.plusDays(6)), week.filter { DayType.WEEKEND.matches(it) })
        assertEquals((1L..5L).map { sun.plusDays(it) }, week.filter { DayType.WEEKDAY.matches(it) })
        assertEquals(week, week.filter { DayType.ALL.matches(it) })
        assertEquals(DayType.ALL, DayType.of(null)); assertEquals(DayType.WEEKEND, DayType.of("WEEKEND"))
    }

    @Test fun statsPeriodStart() {
        val today = LocalDate.of(2026, 11, 11)
        val first = LocalDate.of(2026, 3, 5)
        assertEquals(today.minusDays(6), StatsPeriod.D7.start(today, first))
        assertEquals(today.minusDays(29), StatsPeriod.D30.start(today, first))
        assertEquals(today.minusDays(89), StatsPeriod.D90.start(today, null))
        assertEquals(first, StatsPeriod.ALL.start(today, first))
        assertEquals(today, StatsPeriod.ALL.start(today, null))                 // nothing recorded
        assertEquals(today, StatsPeriod.ALL.start(today, today.plusDays(2)))    // clock set back
        assertEquals(StatsPeriod.D30, StatsPeriod.of("?")); assertEquals(StatsPeriod.ALL, StatsPeriod.of("ALL"))
        assertEquals(listOf("7일", "30일", "90일", "전체"), StatsPeriod.values().map { it.label })
    }

    @Test fun filterKeepsMatchingDaysAndSessions() {
        val sun = LocalDate.of(2026, 11, 1)
        val days = (0L..6L).map { DayStat(sun.plusDays(it), 10, 0, 50, 60) }
        assertEquals(days, StatsFilter.days(days, DayType.ALL))
        assertEquals(listOf(sun, sun.plusDays(6)), StatsFilter.days(days, DayType.WEEKEND).map { it.day })
        assertEquals(5, StatsFilter.days(days, DayType.WEEKDAY).size)
        assertEquals(listOf(false, true, true, true, true, true, false),
            StatsFilter.mask(days, DayType.WEEKDAY).toList())
        fun charge(id: Long, dayIdx: Long, hour: Long): Session {
            val t = Stats.dayStart(sun.plusDays(dayIdx), zone) + hour * 60 * min
            return Session(id, SessionType.CHARGE, t, 20, t + 600 * min, 90, 1, t, 20, 90, 300)
        }
        // Friday 23:00 → Saturday morning counts as a weekday charge: the start day decides.
        val sessions = listOf(charge(1, 0, 10), charge(2, 5, 23), charge(3, 6, 12), charge(4, 3, 8))
        assertEquals(listOf(1L, 3L), StatsFilter.sessions(sessions, DayType.WEEKEND, zone).map { it.id })
        assertEquals(listOf(2L, 4L), StatsFilter.sessions(sessions, DayType.WEEKDAY, zone).map { it.id })
        assertEquals(sessions, StatsFilter.sessions(sessions, DayType.ALL, zone))
    }

    @Test fun summaryHonoursDayTypeFilter() {
        val sun = LocalDate.of(2026, 11, 1)
        // Sun 1st (install) .. Wed 11th (today); weekend days use much more.
        val usage = listOf(5, 50, 60, 70, 80, 90, 150, 170, 40, 30, 7)
        val period = usage.mapIndexed { i, u -> DayStat(sun.plusDays(i.toLong()), u, 0, 100 - u / 2, 100) }
        val installTs = Stats.dayStart(sun, zone) + 600 * min
        val today = sun.plusDays(10)
        fun charge(dayIdx: Long, startSoc: Int, endSoc: Int, minutes: Long): Session {
            val t = Stats.dayStart(sun.plusDays(dayIdx), zone) + 20 * 60 * min
            return Session(0, SessionType.CHARGE, t, startSoc, t + minutes * min, endSoc, 1, t, startSoc, endSoc, 300)
        }
        val sessions = listOf(
            charge(2, 30, 90, 60), charge(4, 40, 100, 90),            // weekdays
            charge(6, 10, 100, 120), charge(7, 20, 80, 100), charge(7, 60, 100, 40),  // Sat, Sun, Sun
        )
        val all = Summaries.of(period, sessions, installTs, today, zone)
        assertEquals(9, all.days.size)
        assertEquals(5, all.chargeCount)

        val wd = Summaries.of(period, sessions, installTs, today, zone, DayType.WEEKDAY)
        assertEquals(listOf(50, 60, 70, 80, 90, 40, 30), wd.days.map { it.usage })   // Mon–Fri, Mon, Tue
        assertTrue(wd.days.all { DayType.WEEKDAY.matches(it.day) })
        assertEquals(60.0, wd.avgUsage!!, 1e-9)
        assertEquals(60, wd.medianUsage)
        assertEquals(2.0 / 7, wd.chargesPerDay!!, 1e-9)
        assertEquals(2, wd.chargeCount)
        assertEquals(35.0, wd.avgChargeStart!!, 1e-9)
        assertEquals(75.0, wd.avgChargeMinutes!!, 1e-9)
        assertEquals(sun.plusDays(5), wd.lowest!!.day)        // Friday, not the heavier Sunday

        val we = Summaries.of(period, sessions, installTs, today, zone, DayType.WEEKEND)
        assertTrue(we.completeDaysOnly)
        assertEquals(listOf(sun.plusDays(6), sun.plusDays(7)), we.days.map { it.day })  // install Sunday excluded
        assertEquals(160.0, we.avgUsage!!, 1e-9)
        assertEquals(1.5, we.chargesPerDay!!, 1e-9)
        assertEquals(3, we.chargeCount)
        assertEquals(30.0, we.avgChargeStart!!, 1e-9)
        assertEquals(sun.plusDays(7), we.lowest!!.day)

        // No matching day at all: empty summary, no crash.
        val none = Summaries.of(period.subList(1, 6), sessions.take(2), installTs, sun.plusDays(5), zone, DayType.WEEKEND)
        assertTrue(none.days.isEmpty()); assertNull(none.avgUsage); assertNull(none.chargesPerDay)
        assertNull(none.lowest); assertEquals(0, none.chargeCount)
    }

    @Test fun dailyBuilderMatchesListForm() {
        val day = LocalDate.of(2026, 11, 10)
        val t0 = Stats.dayStart(day, zone)
        val samples = (0 until 400).map { i ->
            Sample(t0 + i * 17L * min, 50 + ((i * 37) % 51) - 25, 0, 3, 300, 4000)
        }
        val base = Sample(t0 - 5 * min, 64, 0, 3, 300, 4000)
        val expected = Stats.daily(samples, base, zone, day, day.plusDays(6))
        val b = DailyBuilder(zone, day, day.plusDays(6), base.level)
        samples.forEach { b.add(it.ts, it.level) }
        assertEquals(expected, b.build())
        // A sub-range computed on its own (with its own baseline) gives the same days.
        val from = day.plusDays(2)
        val cut = Stats.dayStart(from, zone)
        val tail = Stats.daily(samples.filter { it.ts >= cut }, samples.last { it.ts < cut }, zone, from, day.plusDays(6))
        assertEquals(expected.subList(2, 7), tail)
    }

    @Test fun themeModeResolution() {
        assertTrue(ThemeMode.SYSTEM.isDark(true)); assertTrue(!ThemeMode.SYSTEM.isDark(false))
        assertTrue(!ThemeMode.LIGHT.isDark(true)); assertTrue(ThemeMode.DARK.isDark(false))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.of(null)); assertEquals(ThemeMode.DARK, ThemeMode.of("DARK"))
    }
}
