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
    override fun deleteSession(id: Long) { sessions.removeAll { it.id == id } }
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

    @Test fun mergesShortChargerBlip() {
        val st = FakeStore(); val t = Tracker(st)
        t.onReading(r(0, 40, Plug.WIRELESS))
        t.onReading(r(30, 60, Plug.WIRELESS))
        t.onReading(r(31, 60))                    // pad wobble
        t.onReading(r(32, 60, Plug.WIRELESS))     // back within 2 min
        t.onReading(r(60, 80, Plug.WIRELESS))
        assertEquals(1, st.sessions.size)
        val c = st.sessions[0]
        assertTrue(c.open); assertEquals(40, c.startSoc); assertEquals(80, c.endSoc)
    }

    @Test fun longUnplugIsNotMerged() {
        val st = FakeStore(); val t = Tracker(st)
        t.onReading(r(0, 40, Plug.AC))
        t.onReading(r(30, 60))
        t.onReading(r(40, 58, Plug.AC))
        assertEquals(3, st.sessions.size)
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

    @Test fun dailyWithNoDataIsEmpty() {
        val day = LocalDate.of(2026, 11, 10)
        val out = Stats.daily(emptyList(), null, zone, day, day)
        assertEquals(0, out[0].usage); assertNull(out[0].minSoc)
    }

    @Test fun csvAndFormatting() {
        val ts = Stats.dayStart(LocalDate.of(2026, 11, 11), zone) + 16 * 60 * min + 8 * min
        assertEquals("2026-11-11T16:08:00+09:00", Format.iso(ts, zone))
        assertEquals(
            "$ts,2026-11-11T16:08:00+09:00,64,ac,charging,31.5,4120",
            Format.sampleCsv(Sample(ts, 64, Plug.AC, 2, 315, 4120), zone)
        )
        assertEquals("7시간 30분", Format.duration(450 * min))
        assertEquals("−69", Format.signed(-69))
        val sess = Session(7, SessionType.CHARGE, ts, 18, ts + 450 * min, 100, Plug.AC, ts + 400 * min, 18, 100, 390)
        assertEquals(Format.SESSION_HEADER.split(",").size, Format.sessionCsv(sess, zone).split(",").size)
        assertEquals(10.9, sess.ratePerHour(0)!!, 0.05)
    }
}
