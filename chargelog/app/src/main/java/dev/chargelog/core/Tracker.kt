package dev.chargelog.core

/** Persistence the tracker needs; implemented by the SQLite layer and by a fake in tests. */
interface Store {
    fun lastSample(): Sample?
    fun insertSample(s: Sample)
    /** Newest sessions first. */
    fun latestSessions(limit: Int): List<Session>
    fun insertSession(s: Session): Long
    fun updateSession(s: Session)
}

/**
 * Turns raw battery readings into deduplicated samples and charge/discharge sessions.
 * Feeding the same reading twice is harmless. Sessions are only ever appended or
 * extended; a recorded session is never deleted, however short it was.
 */
class Tracker(private val store: Store) {

    /**
     * Records a reading. When it shows that readings were missed since the previous one, the gap
     * is first filled with estimated readings (see [GapFiller]).
     *
     * @return true when the reading changed stored state (new sample).
     */
    fun onReading(reading: Sample): Boolean {
        val prev = store.lastSample()
        if (prev != null && !reading.est) {
            val r = if (reading.ts < prev.ts) reading.copy(ts = prev.ts) else reading
            // Cheap check first; the rate lookup only runs when a gap is actually present.
            if (GapFiller.isGap(prev, r)) {
                val estimates = GapFiller.fill(prev, r, GapFiller.rates(store.latestSessions(30), r.ts))
                for (e in estimates) record(e)
            }
        }
        return record(reading)
    }

    private fun record(reading: Sample): Boolean {
        val last = store.lastSample()
        // If the wall clock moved backwards (manual change, network time correction), keep
        // recording at the last known time instead of dropping readings until it catches up.
        val r = if (last != null && reading.ts < last.ts) reading.copy(ts = last.ts) else reading
        // Temperature moves without SOC changing (heavy use, charging); keep 1 °C steps too.
        val changed = last == null || last.level != r.level ||
            last.plugged != r.plugged || last.status != r.status ||
            Math.abs(last.temp - r.temp) >= TEMP_STEP
        if (changed) {
            // Primary key is ts: never write two samples on the same millisecond.
            store.insertSample(if (last != null && r.ts <= last.ts) r.copy(ts = last.ts + 1) else r)
        }
        updateSessions(r)
        return changed
    }

    private fun updateSessions(r: Sample) {
        val open = store.latestSessions(1).firstOrNull()?.takeIf { it.open }
        val type = if (r.charging) SessionType.CHARGE else SessionType.DISCHARGE

        when {
            open == null -> store.insertSession(newSession(type, r))
            open.type == type -> {
                val updated = extend(open, r)
                if (updated != open) store.updateSession(updated)
            }
            else -> {
                // Plug state flipped: close the running session and start the next one.
                store.updateSession(extend(open, r, track = false).copy(endTs = r.ts))
                store.insertSession(newSession(type, r))
            }
        }
    }

    private fun newSession(type: SessionType, r: Sample) = Session(
        type = type, startTs = r.ts, startSoc = r.level, endSoc = r.level,
        plug = r.plugged, peakTs = r.ts, minSoc = r.level, maxSoc = r.level, maxTemp = r.temp,
        est = r.est,
    )

    private fun extend(s: Session, r: Sample, track: Boolean = true): Session {
        val newPeak = r.level > s.maxSoc
        return s.copy(
            endSoc = r.level,
            plug = if (track && r.plugged != Plug.NONE) r.plugged else s.plug,
            peakTs = if (newPeak) r.ts else s.peakTs,
            minSoc = minOf(s.minSoc, r.level),
            maxSoc = maxOf(s.maxSoc, r.level),
            maxTemp = maxOf(s.maxTemp, r.temp),
            est = s.est || r.est,
        )
    }

    companion object {
        /** Temperature change (0.1 °C units) that is worth a new sample on its own. */
        const val TEMP_STEP = 10
    }
}
