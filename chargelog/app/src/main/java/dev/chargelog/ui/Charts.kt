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
import java.time.format.TextStyle
import java.util.Locale

/** Daily usage bars for one week with the previous week's average as a dashed line. */
class BarChartView(context: Context, private val p: Palette) : View(context) {
    var days: List<DayStat> = emptyList()
    var average: Int? = null
    var selected: Int = -1
    var onSelect: ((Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.sp(12f) }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(context.dp(4f), context.dp(4f)), 0f)
    }
    private val path = Path()
    private val rect = RectF()

    fun set(days: List<DayStat>, average: Int?, selected: Int) {
        this.days = days; this.average = average; this.selected = selected
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(220f))
    }

    private fun yMax(): Int {
        val top = maxOf(days.maxOfOrNull { it.usage } ?: 0, average ?: 0, 100)
        return (top + 49) / 50 * 50
    }

    override fun onDraw(c: Canvas) {
        if (days.isEmpty()) return
        val axisW = context.dp(38f)
        val top = context.dp(22f)
        val bottom = height - context.dp(40f)
        val plotW = width - axisW
        val max = yMax()
        fun y(v: Int) = bottom - (bottom - top) * v / max.toFloat()

        // Recessive grid: top, middle, baseline.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(1f)
        text.color = p.muted
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        for (v in intArrayOf(0, max / 2, max)) {
            paint.color = if (v == 0) p.muted else p.grid
            c.drawLine(0f, y(v), plotW, y(v), paint)
            c.drawText(v.toString(), plotW + context.dp(8f), y(v) + text.textSize / 3, text)
        }

        val col = plotW / days.size
        val barW = minOf(context.dp(16f), col * 0.5f)
        val r = context.dp(4f)
        paint.style = Paint.Style.FILL
        days.forEachIndexed { i, d ->
            val cx = col * i + col / 2
            if (d.usage > 0) {
                val t = minOf(y(d.usage), bottom - context.dp(2f))
                rect.set(cx - barW / 2, t, cx + barW / 2, bottom)
                path.reset()
                path.addRoundRect(rect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
                paint.color = if (i == selected || selected < 0) p.accent else p.accentDim
                c.drawPath(path, paint)
                if (i == selected) {
                    text.color = p.ink
                    text.textAlign = Paint.Align.CENTER
                    text.typeface = Typeface.DEFAULT_BOLD
                    c.drawText("${d.usage}%", cx, t - context.dp(6f), text)
                }
            }
            // Weekday label; the selected day sits on a filled chip.
            val ly = bottom + context.dp(24f)
            val name = d.day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.KOREAN)
            text.textAlign = Paint.Align.CENTER
            if (i == selected) {
                paint.color = p.ink
                c.drawCircle(cx, ly - text.textSize / 3, context.dp(13f), paint)
                text.color = p.card
                text.typeface = Typeface.DEFAULT_BOLD
            } else {
                text.color = p.ink2
                text.typeface = Typeface.DEFAULT
            }
            c.drawText(name, cx, ly, text)
        }

        average?.let { a ->
            dash.color = p.ink2
            path.reset()
            path.moveTo(0f, y(a)); path.lineTo(plotW, y(a))
            c.drawPath(path, dash)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (days.isEmpty()) return false
        if (e.action == MotionEvent.ACTION_UP) {
            val col = (width - context.dp(38f)) / days.size
            val i = (e.x / col).toInt().coerceIn(0, days.size - 1)
            performClick()
            onSelect?.invoke(i)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

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
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(200f))
    }

    private fun plotW() = width - context.dp(38f)
    private fun x(ts: Long) = plotW() * (ts - start).coerceIn(0, end - start) / (end - start).toFloat()

    override fun onDraw(c: Canvas) {
        val top = context.dp(24f)
        val bottom = height - context.dp(24f)
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

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touchX = e.x.coerceIn(0f, plotW())
                invalidate()
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
