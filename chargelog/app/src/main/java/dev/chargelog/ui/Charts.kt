package dev.chargelog.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import dev.chargelog.core.DayStat
import dev.chargelog.core.Format
import dev.chargelog.core.Sample
import dev.chargelog.core.Session
import dev.chargelog.core.SessionType
import java.time.ZoneId
import java.util.Locale

/** SOC over one day (0–24h). Charging periods are shaded; touch shows the value at that time. */
class SocChartView(context: Context, private val p: Palette) : View(context) {
    private var points: List<Sample> = emptyList()
    private var sessions: List<Session> = emptyList()
    private var start = 0L
    private var end = 1L
    private var now = 0L
    private var zone: ZoneId = ZoneId.systemDefault()
    private var touchX = -1f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.sp(12f) }
    private val path = Path()

    /** @param points readings in order; the first may predate [start] (carried-in SOC). */
    fun set(points: List<Sample>, sessions: List<Session>, start: Long, end: Long, now: Long, zone: ZoneId) {
        this.points = points; this.sessions = sessions
        this.start = start; this.end = end; this.now = now; this.zone = zone
        touchX = -1f
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        // Fills the height it is given (Home stretches it); otherwise a fixed default.
        val height = if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY) MeasureSpec.getSize(h) else context.dpi(170f)
        setMeasuredDimension(MeasureSpec.getSize(w), height)
    }

    private fun plotW() = width - context.dp(38f)
    private fun x(ts: Long) = plotW() * (ts - start).coerceIn(0, end - start) / (end - start).toFloat()

    override fun onDraw(c: Canvas) {
        val top = context.dp(22f)
        val bottom = height - context.dp(22f)
        val plotW = plotW()
        val last = minOf(now, end)
        fun y(level: Int) = bottom - (bottom - top) * level / 100f

        // Charging periods.
        paint.style = Paint.Style.FILL
        paint.color = p.accentWash
        for (s in sessions) {
            if (s.type != SessionType.CHARGE) continue
            val a = x(s.startTs); val b = x(minOf(s.endTs ?: last, last))
            if (b > a) c.drawRect(a, top, b, bottom, paint)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(1f)
        text.typeface = Typeface.DEFAULT
        text.color = p.muted
        text.textAlign = Paint.Align.LEFT
        for (v in intArrayOf(0, 50, 100)) {
            paint.color = if (v == 0) p.muted else p.grid
            c.drawLine(0f, y(v), plotW, y(v), paint)
            c.drawText(v.toString(), plotW + context.dp(8f), y(v) + text.textSize / 3, text)
        }
        for (h in intArrayOf(0, 6, 12, 18, 24)) {
            val px = plotW * h / 24f
            text.textAlign = when (h) { 0 -> Paint.Align.LEFT; 24 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            c.drawText("${h}시", px, bottom + context.dp(17f), text)
        }

        if (points.isEmpty() || last <= start) return
        path.reset()
        var started = false
        for (s in points) {
            val px = x(s.ts); val py = y(s.level)
            if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
        }
        path.lineTo(x(last), y(points.last().level))
        paint.color = p.accent
        paint.strokeWidth = context.dp(2f)
        paint.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, paint)

        if (touchX >= 0f) {
            val ts = (start + (end - start) * (touchX / plotW).toDouble()).toLong().coerceIn(start, last)
            val level = levelAt(ts) ?: return
            val px = x(ts)
            paint.color = p.ink2
            paint.strokeWidth = context.dp(1f)
            c.drawLine(px, top, px, bottom, paint)
            paint.style = Paint.Style.FILL
            paint.color = p.card
            c.drawCircle(px, y(level), context.dp(6f), paint)
            paint.color = p.accent
            c.drawCircle(px, y(level), context.dp(4f), paint)
            text.color = p.ink
            text.typeface = Typeface.DEFAULT_BOLD
            val tip = "${Format.time(ts, zone)} · $level%"
            val half = text.measureText(tip) / 2
            text.textAlign = Paint.Align.CENTER
            c.drawText(tip, px.coerceIn(half, maxOf(half, width - half)), top - context.dp(8f), text)
        }
    }

    /** SOC at [ts], interpolated between the surrounding readings. */
    private fun levelAt(ts: Long): Int? {
        var prev: Sample? = null
        for (s in points) {
            if (s.ts > ts) {
                val a = prev ?: return s.level
                val f = (ts - a.ts).toDouble() / (s.ts - a.ts)
                return Math.round(a.level + (s.level - a.level) * f).toInt()
            }
            prev = s
        }
        return prev?.level
    }

    private var downX = 0f
    private var downY = 0f
    private var scrubbing = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; scrubbing = false
                touchX = e.x.coerceIn(0f, plotW())
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                // Horizontal drag scrubs the chart; vertical drag is left to the page scroll.
                val dx = Math.abs(e.x - downX); val dy = Math.abs(e.y - downY)
                if (!scrubbing && dx > context.dp(8f) && dx > dy) {
                    scrubbing = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (scrubbing) {
                    touchX = e.x.coerceIn(0f, plotW())
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/**
 * Shared frame for the dashboard's per-day charts: one column per day, a value axis on the
 * right, a few date labels along the bottom, and a touch tooltip for the column under the finger.
 */
abstract class DailyChart(context: Context, protected val p: Palette) : View(context) {
    var days: List<DayStat> = emptyList()
        set(v) { field = v; touched = -1; invalidate() }
    /** Persistently selected column (drawn highlighted); -1 for none. */
    var selected = -1
        set(v) { field = v; invalidate() }
    var onSelect: ((Int) -> Unit)? = null

    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.sp(12f) }
    protected val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(context.dp(4f), context.dp(4f)), 0f)
    }
    protected val path = Path()
    protected val rect = RectF()
    private var touched = -1
    private var downX = 0f
    private var downY = 0f
    private var scrubbing = false

    protected val top get() = context.dp(28f)
    protected val bottom get() = height - context.dp(26f)
    protected val plotW get() = width - context.dp(38f)
    protected val col get() = plotW / maxOf(days.size, 1)
    protected fun cx(i: Int) = col * i + col / 2

    abstract fun maxValue(): Int
    abstract fun drawColumn(c: Canvas, i: Int, d: DayStat, y: (Int) -> Float, highlight: Boolean)
    abstract fun tooltip(d: DayStat): String?
    open fun drawOverlay(c: Canvas, y: (Int) -> Float) {}

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(170f))
    }

    override fun onDraw(c: Canvas) {
        if (days.isEmpty()) return
        val max = maxValue()
        val y = { v: Int -> bottom - (bottom - top) * v / max.toFloat() }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(1f)
        text.typeface = Typeface.DEFAULT
        text.color = p.muted
        text.textAlign = Paint.Align.LEFT
        for (v in intArrayOf(0, max / 2, max)) {
            paint.color = if (v == 0) p.muted else p.grid
            c.drawLine(0f, y(v), plotW, y(v), paint)
            c.drawText(v.toString(), plotW + context.dp(8f), y(v) + text.textSize / 3, text)
        }

        // About five date labels regardless of the period length.
        val step = maxOf(1, Math.round(days.size / 5f))
        val fmt = java.time.format.DateTimeFormatter.ofPattern("M/d")
        text.textAlign = Paint.Align.CENTER
        var i = days.size - 1
        while (i >= 0) {
            val label = fmt.format(days[i].day)
            val half = text.measureText(label) / 2
            c.drawText(label, cx(i).coerceIn(half, plotW - half), bottom + context.dp(18f), text)
            i -= step
        }

        paint.style = Paint.Style.FILL
        days.forEachIndexed { idx, d -> drawColumn(c, idx, d, y, idx == touched || idx == selected) }
        drawOverlay(c, y)

        if (touched in days.indices) {
            val tip = tooltip(days[touched]) ?: return
            text.color = p.ink
            text.typeface = Typeface.DEFAULT_BOLD
            text.textAlign = Paint.Align.CENTER
            val half = text.measureText(tip) / 2
            c.drawText(tip, cx(touched).coerceIn(half, maxOf(half, width - half)), top - context.dp(12f), text)
        }
    }

    /** Rounded-top bar from [bottomY] up to [topY]. */
    protected fun bar(c: Canvas, x: Float, topY: Float, bottomY: Float, color: Int, roundBottom: Boolean = false) {
        val w = maxOf(context.dp(1f), minOf(context.dp(14f), col * 0.6f))
        val r = minOf(context.dp(4f), w / 2)
        rect.set(x - w / 2, topY, x + w / 2, bottomY)
        path.reset()
        val b = if (roundBottom) r else 0f
        path.addRoundRect(rect, floatArrayOf(r, r, r, r, b, b, b, b), Path.Direction.CW)
        paint.color = color
        c.drawPath(path, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (days.isEmpty()) return false
        fun pick() {
            touched = (e.x / col).toInt().coerceIn(0, days.size - 1)
            invalidate()
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; scrubbing = false; pick() }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(e.x - downX); val dy = Math.abs(e.y - downY)
                if (!scrubbing && dx > context.dp(8f) && dx > dy) {
                    scrubbing = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (scrubbing) pick()
            }
            MotionEvent.ACTION_UP -> {
                if (touched in days.indices) onSelect?.invoke(touched)
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    protected fun dateLabel(d: DayStat): String =
        java.time.format.DateTimeFormatter.ofPattern("M/d (E)", Locale.KOREAN).format(d.day)
}

/** Daily SOC used, with the period's typical (average) day as a dashed line. */
class UsageChartView(context: Context, p: Palette) : DailyChart(context, p) {
    var average: Double? = null
        set(v) { field = v; invalidate() }

    override fun maxValue(): Int {
        val top = maxOf(days.maxOfOrNull { it.usage } ?: 0, average?.toInt() ?: 0, 100)
        return (top + 49) / 50 * 50
    }

    override fun drawColumn(c: Canvas, i: Int, d: DayStat, y: (Int) -> Float, highlight: Boolean) {
        if (d.usage <= 0) return
        val t = minOf(y(d.usage), bottom - context.dp(2f))
        bar(c, cx(i), t, bottom, if (highlight) p.ink else p.accent)
    }

    override fun drawOverlay(c: Canvas, y: (Int) -> Float) {
        val a = average ?: return
        val ay = bottom - (bottom - top) * (a / maxValue()).toFloat()
        dash.color = p.ink2
        path.reset(); path.moveTo(0f, ay); path.lineTo(plotW, ay)
        c.drawPath(path, dash)
    }

    override fun tooltip(d: DayStat): String? =
        if (d.minSoc == null) "${dateLabel(d)} · 기록 없음" else "${dateLabel(d)} · 사용 ${d.usage}%"
}

/** Each day's SOC range as a floating bar from that day's minimum to its maximum. */
class RangeChartView(context: Context, p: Palette) : DailyChart(context, p) {
    var avgMin: Double? = null
    var avgMax: Double? = null

    override fun maxValue() = 100

    override fun drawColumn(c: Canvas, i: Int, d: DayStat, y: (Int) -> Float, highlight: Boolean) {
        val mn = d.minSoc ?: return
        val mx = d.maxSoc ?: return
        // Keep a visible stub when min == max.
        val t = minOf(y(mx), y(mn) - context.dp(3f))
        bar(c, cx(i), t, y(mn), if (highlight) p.ink else p.accent, roundBottom = true)
    }

    override fun drawOverlay(c: Canvas, y: (Int) -> Float) {
        dash.color = p.ink2
        for (v in listOfNotNull(avgMin, avgMax)) {
            val ly = bottom - (bottom - top) * (v / 100.0).toFloat()
            path.reset(); path.moveTo(0f, ly); path.lineTo(plotW, ly)
            c.drawPath(path, dash)
        }
    }

    override fun tooltip(d: DayStat): String? =
        if (d.minSoc == null) "${dateLabel(d)} · 기록 없음"
        else "${dateLabel(d)} · 최저 ${d.minSoc}% · 최고 ${d.maxSoc}%"
}

/**
 * Compact single-series line over one day (0–24 h), e.g. battery temperature or voltage.
 * The value scale fits the data; a touch shows the value at that time.
 */
class MiniLineChart(
    context: Context,
    private val p: Palette,
    private val color: Int,
    private val format: (Int) -> String,
    /** Smallest visible span of the value axis, so flat data does not look noisy. */
    private val minSpan: Int,
) : View(context) {
    private var pts: List<Pair<Long, Int>> = emptyList()
    private var start = 0L
    private var end = 1L
    private var now = 0L
    private var zone: ZoneId = ZoneId.systemDefault()
    private var touchX = -1f
    private var downX = 0f
    private var downY = 0f
    private var scrubbing = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.sp(11f) }
    private val path = Path()

    fun set(points: List<Pair<Long, Int>>, start: Long, end: Long, now: Long, zone: ZoneId) {
        pts = points; this.start = start; this.end = end; this.now = now; this.zone = zone
        touchX = -1f
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(84f))
    }

    private fun plotW() = width - context.dp(52f)
    private fun x(ts: Long) = plotW() * (ts - start).coerceIn(0, end - start) / (end - start).toFloat()

    override fun onDraw(c: Canvas) {
        if (pts.isEmpty()) return
        val top = context.dp(18f)
        val bottom = height - context.dp(6f)
        var lo = pts.minOf { it.second }
        var hi = pts.maxOf { it.second }
        if (hi - lo < minSpan) { val mid = (hi + lo) / 2; lo = mid - minSpan / 2; hi = lo + minSpan }
        fun y(v: Int) = bottom - (bottom - top) * (v - lo) / (hi - lo).toFloat()
        val plotW = plotW()
        val last = minOf(now, end)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(1f)
        paint.color = p.grid
        text.typeface = Typeface.DEFAULT
        text.color = p.muted
        text.textAlign = Paint.Align.LEFT
        for (v in intArrayOf(lo, hi)) {
            c.drawLine(0f, y(v), plotW, y(v), paint)
            c.drawText(format(v), plotW + context.dp(6f), y(v) + text.textSize / 3, text)
        }

        path.reset()
        pts.forEachIndexed { i, (ts, v) -> if (i == 0) path.moveTo(x(ts), y(v)) else path.lineTo(x(ts), y(v)) }
        if (last > pts.last().first) path.lineTo(x(last), y(pts.last().second))
        paint.color = color
        paint.strokeWidth = context.dp(2f)
        paint.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, paint)

        if (touchX >= 0f) {
            val ts = (start + (end - start) * (touchX / plotW).toDouble()).toLong().coerceIn(start, last)
            val v = pts.lastOrNull { it.first <= ts }?.second ?: pts.first().second
            val px = x(ts)
            paint.color = p.ink2
            paint.strokeWidth = context.dp(1f)
            c.drawLine(px, top, px, bottom, paint)
            paint.style = Paint.Style.FILL
            paint.color = p.card
            c.drawCircle(px, y(v), context.dp(5f), paint)
            paint.color = color
            c.drawCircle(px, y(v), context.dp(3.5f), paint)
            text.color = p.ink
            text.typeface = Typeface.DEFAULT_BOLD
            text.textAlign = Paint.Align.CENTER
            val tip = "${Format.time(ts, zone)} · ${format(v)}"
            val half = text.measureText(tip) / 2
            c.drawText(tip, px.coerceIn(half, maxOf(half, width - half)), top - context.dp(6f), text)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; scrubbing = false
                touchX = e.x.coerceIn(0f, plotW()); invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(e.x - downX); val dy = Math.abs(e.y - downY)
                if (!scrubbing && dx > context.dp(8f) && dx > dy) {
                    scrubbing = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (scrubbing) { touchX = e.x.coerceIn(0f, plotW()); invalidate() }
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
