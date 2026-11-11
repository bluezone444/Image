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
        assertTrue(Format.sampleCsv(s.copy(ocv = 3870), zone).endsWith(",3900,3870,0"))
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

    @Test fun dailyWithNoDataIsEmpty() {
        val day = LocalDate.of(2026, 11, 10)
        val out = Stats.daily(emptyList(), null, zone, day, day)
        assertEquals(0, out[0].usage); assertNull(out[0].minSoc)
    }

    @Test fun csvAndFormatting() {
        val ts = Stats.dayStart(LocalDate.of(2026, 11, 11), zone) + 16 * 60 * min + 8 * min
        assertEquals("2026-11-11T16:08:00+09:00", Format.iso(ts, zone))
        assertEquals(
            "$ts,2026-11-11T16:08:00+09:00,64,ac,charging,31.5,4120,,0",
            Format.sampleCsv(Sample(ts, 64, Plug.AC, 2, 315, 4120), zone)
        )
        assertEquals("7시간 30분", Format.duration(450 * min))
        assertEquals("−69", Format.signed(-69))
        val sess = Session(7, SessionType.CHARGE, ts, 18, ts + 450 * min, 100, Plug.AC, ts + 400 * min, 18, 100, 390)
        assertEquals(Format.SESSION_HEADER.split(",").size, Format.sessionCsv(sess, zone).split(",").size)
        assertEquals(10.9, sess.ratePerHour(0)!!, 0.05)
    }
}
