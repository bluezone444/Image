package dev.chargelog.core

/** Display smoothing for small line charts; summaries keep using the raw values. */
object Smooth {
    /**
     * Centered moving average over [windowMs] for each point, computed within each run between
     * [Drain.BREAK] markers (markers are kept in place). Points are (timestamp, value), ascending.
     */
    fun movingAverage(points: List<Pair<Long, Int>>, windowMs: Long): List<Pair<Long, Int>> {
        if (windowMs <= 0 || points.size < 3) return points
        val out = ArrayList<Pair<Long, Int>>(points.size)
        var i = 0
        while (i < points.size) {
            if (points[i].second == Drain.BREAK) { out.add(points[i]); i++; continue }
            var j = i
            while (j < points.size && points[j].second != Drain.BREAK) j++
            val run = points.subList(i, j)
            val half = windowMs / 2
            var lo = 0; var hi = 0; var sum = 0L
            for (k in run.indices) {
                val t = run[k].first
                while (hi < run.size && run[hi].first <= t + half) { sum += run[hi].second; hi++ }
                while (run[lo].first < t - half) { sum -= run[lo].second; lo++ }
                out.add(t to Math.round(sum.toDouble() / (hi - lo)).toInt())
            }
            i = j
        }
        return out
    }
}
