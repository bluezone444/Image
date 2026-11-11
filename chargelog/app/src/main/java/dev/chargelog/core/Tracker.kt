package dev.chargelog.core

/** Persistence the tracker needs; implemented by the SQLite layer and by a fake in tests. */
interface Store {
    fun lastSample(): Sample?
    fun insertSample(s: Sample)
    /** Newest sessions first. */
    fun latestSessions(limit: Int): List<Session>
    fun insertSession(s: Session): Long
    fun updateSession(s: Session)
    fun deleteSession(id: Long)
}

/**
 * Turns raw battery readings into deduplicated samples and charge/discharge sessions.
 * Feeding the same reading twice is harmless.
 */
class Tracker(private val store: Store, private val mergeWindowMs: Long = 2 * 60_000L) {

    /** @return true when the reading changed stored state (new sample). */
    fun onReading(r: Sample): Boolean {
        val last = store.lastSample()
        if (last != null && r.ts < last.ts) return false // clock went backwards; ignore
        val changed = last == null || last.level != r.level ||
            last.plugged != r.plugged || last.status != r.status
        if (changed) {
            // Primary key is ts: never write two samples on the same millisecond.
            store.insertSample(if (last != null && r.ts == last.ts) r.copy(ts = r.ts + 1) else r)
        }
        updateSessions(r)
        return changed
    }

    private fun updateSessions(r: Sample) {
        val recent = store.latestSessions(2)
        val open = recent.firstOrNull()?.takeIf { it.open }
        val type = if (r.charging) SessionType.CHARGE else SessionType.DISCHARGE

        if (open == null) {
            store.insertSession(newSession(type, r))
            return
        }
        if (open.type == type) {
            val updated = extend(open, r)
            if (updated != open) store.updateSession(updated)
            return
        }

        // Plug state flipped. A very short blip (wireless pad wobble, cable re-seat) is
        // folded back into the session before it instead of splitting the history.
        val prev = recent.getOrNull(1)
        val blip = r.ts - open.startTs < mergeWindowMs
        if (blip && prev != null && prev.type == type && prev.endTs == open.startTs) {
            store.deleteSession(open.id)
            store.updateSession(extend(prev.copy(endTs = null), r))
        } else {
            store.updateSession(extend(open, r, track = false).copy(endTs = r.ts))
            store.insertSession(newSession(type, r))
        }
    }

    private fun newSession(type: SessionType, r: Sample) = Session(
        type = type, startTs = r.ts, startSoc = r.level, endSoc = r.level,
        plug = r.plugged, peakTs = r.ts, minSoc = r.level, maxSoc = r.level, maxTemp = r.temp,
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
        )
    }
}
