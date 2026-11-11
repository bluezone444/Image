package dev.chargelog.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import dev.chargelog.core.DayStat
import dev.chargelog.core.Format
import dev.chargelog.core.Sample
import dev.chargelog.core.Session
import dev.chargelog.core.SessionType
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------- shared pieces

/** Gap marker inside a line series (e.g. charging stretches in the discharge-current line). */
private const val GAP = dev.chargelog.core.Drain.BREAK

/**
 * Horizontal zoom for a chart: a two-finger pinch scales the x axis around the fingers and
 * follows them (pan); double tap, or tapping the zoom pill, resets. The viewport is kept per
 * [key] so it survives the screen being rebuilt for a new battery reading.
 */
class XZoom(private val view: View, private val minSpan: Double) {
    var lo = 0.0; private set
    var hi = 1.0; private set
    var v0 = 0.0; private set
    var v1 = 1.0; private set
    var key: String? = null
    /** Plot width in px, set by the chart on every draw. */
    var plotW = 1f

    val span get() = v1 - v0
    val zoomed get() = span < (hi - lo) * 0.995
    val factor get() = (hi - lo) / span

    /** True from the first second finger until every finger is lifted. */
    var pinching = false; private set
    private var prevFocus = 0f

    fun setDomain(lo: Double, hi: Double) {
        this.lo = lo
        this.hi = maxOf(hi, lo + 1e-6)
        val s = key?.let { saved[it] }
        if (s != null && s[0] == lo && s[1] == this.hi) { v0 = s[2]; v1 = s[3] } else { v0 = lo; v1 = this.hi }
    }

    fun x(v: Double): Float = ((v - v0) / span * plotW).toFloat()
    fun value(x: Float): Double = v0 + x / plotW * span

    fun reset() {
        v0 = lo; v1 = hi
        save()
        view.invalidate()
    }

    private fun save() { key?.let { saved[it] = doubleArrayOf(lo, hi, v0, v1) } }

    private val scale = ScaleGestureDetector(view.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { prevFocus = d.focusX; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            // Horizontal finger distance drives the x axis only.
            val f = if (d.previousSpanX > 8f && d.currentSpanX > 8f) d.currentSpanX / d.previousSpanX else d.scaleFactor
            val full = hi - lo
            val newSpan = (span / f).coerceIn(minOf(minSpan, full), full)
            val anchor = value(prevFocus.coerceIn(0f, plotW))
            val start = (anchor - d.focusX.coerceIn(0f, plotW) / plotW * newSpan).coerceIn(lo, hi - newSpan)
            v0 = start; v1 = start + newSpan
            prevFocus = d.focusX
            save()
            view.invalidate()
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    private val taps = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!zoomed) return false
            reset()
            return true
        }
    })

    /** Feed every touch event. Returns true while a pinch owns the gesture. */
    fun onTouch(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) pinching = false
        scale.onTouchEvent(e)
        taps.onTouchEvent(e)
        if (e.pointerCount > 1 || scale.isInProgress) {
            pinching = true
            view.parent?.requestDisallowInterceptTouchEvent(true)
        }
        return pinching
    }

    companion object {
        private val saved = HashMap<String, DoubleArray>()
    }
}

/** Paints and small drawing helpers shared by every chart. */
class ChartInk(private val context: Context, private val p: Palette) {
    fun dp(v: Float) = context.dp(v)

    val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.sp(10.5f); color = p.muted }
    val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.sp(12f); typeface = NUM_FACE; color = p.ink }
    val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.sp(10.5f); color = p.ink2 }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    val dash = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
    val estDash = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    private val r = RectF()

    /** Horizontal guide: solid hairline for the baseline, dashed for the rest. */
    fun guide(c: Canvas, y: Float, x0: Float, x1: Float, baseline: Boolean) {
        stroke.strokeWidth = dp(1f)
        stroke.color = if (baseline) p.line else p.grid
        stroke.pathEffect = if (baseline) null else dash
        c.drawLine(x0, y, x1, y, stroke)
        stroke.pathEffect = null
    }

    /** Rounded label (card fill, hairline border) centered on [cx] and kept inside [0, maxX]. */
    fun bubble(c: Canvas, text: String, cx: Float, top: Float, maxX: Float, paint: Paint = value): RectF {
        val padH = dp(8f)
        val h = paint.textSize + dp(10f)
        val w = paint.measureText(text) + 2 * padH
        val l = (cx - w / 2).coerceIn(0f, maxOf(0f, maxX - w))
        r.set(l, top, l + w, top + h)
        fill.shader = null
        fill.color = p.card
        c.drawRoundRect(r, h / 2, h / 2, fill)
        stroke.strokeWidth = dp(1f); stroke.color = p.line; stroke.pathEffect = null
        c.drawRoundRect(r, h / 2, h / 2, stroke)
        paint.textAlign = Paint.Align.LEFT
        c.drawText(text, l + padH, top + h / 2 + paint.textSize * 0.36f, paint)
        return RectF(r)
    }

    /** "×2.3 원래대로" pill at the top left while zoomed; returns its rect for tap handling. */
    fun zoomPill(c: Canvas, zoom: XZoom): RectF? {
        if (!zoom.zoomed) return null
        val t = String.format(Locale.US, "×%.1f  원래대로", zoom.factor)
        val w = small.measureText(t) + dp(16f)
        return bubble(c, t, w / 2, dp(1f), zoom.plotW, small)
    }

    /** Point marker with a surface-colored halo. */
    fun dot(c: Canvas, x: Float, y: Float, color: Int, radius: Float = dp(4f)) {
        fill.shader = null
        fill.color = p.card; c.drawCircle(x, y, radius + dp(2f), fill)
        fill.color = color; c.drawCircle(x, y, radius, fill)
    }

    /** Vertical gradient from the series color to transparent under a line. */
    fun areaShader(color: Int, top: Float, bottom: Float, alpha: Int = 0x3A): Shader =
        LinearGradient(0f, top, 0f, bottom, (color and 0x00FFFFFF) or (alpha shl 24),
            color and 0x00FFFFFF, Shader.TileMode.CLAMP)
}

/** Time ticks for a viewport inside one day: step adapts to the visible span. */
private fun timeTicks(dayStart: Long, v0: Double, v1: Double): Pair<Long, List<Long>> {
    val spanMin = (v1 - v0) / 60_000.0
    val step = listOf(15, 30, 60, 120, 180, 360).first { spanMin / it <= 6 || it == 360 } * 60_000L
    val first = dayStart + Math.ceil((v0 - dayStart) / step).toLong() * step
    val out = ArrayList<Long>()
    var t = first
    while (t <= v1 + 1) { out.add(t); t += step }
    return step to out
}

private fun tickLabel(t: Long, dayStart: Long, step: Long, zone: ZoneId): String {
    if (t - dayStart >= 24 * 3_600_000L) return "24시"
    return if (step < 3_600_000L) Format.time(t, zone)
    else "${java.time.Instant.ofEpochMilli(t).atZone(zone).hour}시"
}

/** Common one-finger behaviour: show the value under the finger; vertical drags scroll the page. */
private class Scrub(private val view: View) {
    var x = -1f
    private var downX = 0f
    private var downY = 0f
    private var horizontal = false

    fun onTouch(e: MotionEvent, plotW: Float) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; horizontal = false
                x = e.x.coerceIn(0f, plotW)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(e.x - downX); val dy = Math.abs(e.y - downY)
                if (!horizontal && dx > view.context.dp(8f) && dx > dy) {
                    horizontal = true
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (horizontal) x = e.x.coerceIn(0f, plotW)
            }
        }
        view.invalidate()
    }
}

// ---------------------------------------------------------------- SOC over a day

/** SOC over one day (0–24 h). Charging periods are shaded; estimated stretches are dashed. */
class SocChartView(context: Context, private val p: Palette) : View(context) {
    private val ink = ChartInk(context, p)
    private val zoom = XZoom(this, 30 * 60_000.0)
    private val scrub = Scrub(this)
    var zoomKey: String?
        get() = zoom.key
        set(v) { zoom.key = v }

    private var points: List<Sample> = emptyList()
    private var sessions: List<Session> = emptyList()
    private var start = 0L
    private var end = 1L
    private var now = 0L
    private var zone: ZoneId = ZoneId.systemDefault()
    private var pill: RectF? = null

    private val line = Path()
    private val estLine = Path()
    private val area = Path()

    /** @param points readings in order; the first may predate [start] (carried-in SOC). */
    fun set(points: List<Sample>, sessions: List<Session>, start: Long, end: Long, now: Long, zone: ZoneId) {
        this.points = points; this.sessions = sessions
        this.start = start; this.end = end; this.now = now; this.zone = zone
        zoom.setDomain(start.toDouble(), end.toDouble())
        scrub.x = -1f
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val height = if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY) MeasureSpec.getSize(h) else context.dpi(180f)
        setMeasuredDimension(MeasureSpec.getSize(w), height)
    }

    private fun plotW() = width - ink.dp(32f)

    override fun onDraw(c: Canvas) {
        val plotW = plotW()
        zoom.plotW = plotW
        val top = ink.dp(30f)
        val bottom = height - ink.dp(20f)
        val last = minOf(now, end)
        fun x(ts: Long) = zoom.x(ts.toDouble())
        fun y(level: Int) = bottom - (bottom - top) * level / 100f

        // Guides and the value axis.
        for (v in intArrayOf(0, 25, 50, 75, 100)) ink.guide(c, y(v), 0f, plotW, v == 0)
        ink.axis.textAlign = Paint.Align.LEFT
        for (v in intArrayOf(0, 50, 100)) c.drawText("$v", plotW + ink.dp(6f), y(v) + ink.axis.textSize / 3, ink.axis)

        c.save()
        c.clipRect(0f, 0f, plotW, height.toFloat())

        // Charging periods.
        ink.fill.shader = null
        ink.fill.color = p.accentWash
        for (s in sessions) {
            if (s.type != SessionType.CHARGE) continue
            val a = x(maxOf(s.startTs, start)); val b = x(minOf(s.endTs ?: last, last))
            if (b > a) c.drawRect(a, top, b, bottom, ink.fill)
        }

        // Time axis.
        val (step, ticks) = timeTicks(start, zoom.v0, zoom.v1)
        var lastLabelRight = -1f
        for (t in ticks) {
            val label = tickLabel(t, start, step, zone)
            val w = ink.axis.measureText(label)
            val px = x(t).coerceIn(w / 2, plotW - w / 2)
            if (px - w / 2 < lastLabelRight + ink.dp(6f)) continue
            ink.axis.textAlign = Paint.Align.CENTER
            c.drawText(label, px, bottom + ink.dp(15f), ink.axis)
            lastLabelRight = px + w / 2
        }

        if (points.isNotEmpty() && last > start) {
            line.reset(); estLine.reset(); area.reset()
            val firstX = x(maxOf(points[0].ts, start))
            area.moveTo(firstX, bottom)
            area.lineTo(firstX, y(points[0].level))
            for (i in 1 until points.size) {
                val prev = points[i - 1]; val s = points[i]
                val target = if (s.est || prev.est) estLine else line
                target.moveTo(x(prev.ts), y(prev.level))
                target.lineTo(x(s.ts), y(s.level))
                area.lineTo(x(s.ts), y(s.level))
            }
            val tail = points.last()
            (if (tail.est) estLine else line).apply { moveTo(x(tail.ts), y(tail.level)); lineTo(x(last), y(tail.level)) }
            area.lineTo(x(last), y(tail.level))
            area.lineTo(x(last), bottom)
            area.close()

            ink.fill.shader = ink.areaShader(p.soc, top, bottom)
            c.drawPath(area, ink.fill)
            ink.fill.shader = null

            ink.stroke.strokeWidth = ink.dp(2.25f)
            ink.stroke.color = p.soc
            ink.stroke.pathEffect = null
            c.drawPath(line, ink.stroke)
            ink.stroke.color = p.ink2
            ink.stroke.strokeWidth = ink.dp(1.75f)
            ink.stroke.pathEffect = ink.estDash
            c.drawPath(estLine, ink.stroke)
            ink.stroke.pathEffect = null

            // Latest value, while the day is still running.
            if (now in start until end && scrub.x < 0f) {
                val lx = x(last); val ly = y(tail.level)
                if (lx in 0f..plotW) {
                    ink.dot(c, lx, ly, p.soc)
                    ink.bubble(c, "${tail.level}%", lx - ink.dp(18f), maxOf(0f, ly - ink.dp(34f)), plotW)
                }
            }
        }
        c.restore()

        pill = if (scrub.x < 0f) ink.zoomPill(c, zoom) else null

        if (scrub.x >= 0f && points.isNotEmpty()) {
            val ts = zoom.value(scrub.x).toLong().coerceIn(start, maxOf(start, last))
            val level = levelAt(ts) ?: return
            val px = x(ts)
            ink.stroke.color = p.ink2; ink.stroke.strokeWidth = ink.dp(1f); ink.stroke.pathEffect = ink.dash
            c.drawLine(px, top, px, bottom, ink.stroke)
            ink.stroke.pathEffect = null
            ink.dot(c, px, y(level), p.soc)
            ink.bubble(c, "${Format.time(ts, zone)}  $level%", px, ink.dp(1f), width.toFloat())
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
        if (zoom.onTouch(e)) { scrub.x = -1f; invalidate(); return true }
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            if (pill?.contains(e.x, e.y) == true) { zoom.reset(); scrub.x = -1f; return true }
            performClick()
            return true
        }
        scrub.onTouch(e, plotW())
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

// ---------------------------------------------------------------- per-day charts

/**
 * Shared frame for the per-day charts: one column per day, value axis on the right, adaptive
 * date labels, pinch zoom over days, a tooltip for the column under the finger and tap to select.
 */
abstract class DailyChart(context: Context, protected val p: Palette) : View(context) {
    protected val ink = ChartInk(context, p)
    private val zoom = XZoom(this, 3.0)
    private val scrub = Scrub(this)
    var zoomKey: String?
        get() = zoom.key
        set(v) { zoom.key = v }

    var days: List<DayStat> = emptyList()
        set(v) { field = v; scrub.x = -1f; zoom.setDomain(0.0, v.size.toDouble()); invalidate() }
    /** Persistently selected column (drawn in ink); -1 for none. */
    var selected = -1
        set(v) { field = v; invalidate() }
    var onSelect: ((Int) -> Unit)? = null

    private var pill: RectF? = null
    protected val path = Path()
    protected val rect = RectF()
    protected val today: LocalDate = LocalDate.now()

    protected val top get() = ink.dp(30f)
    protected val bottom get() = height - ink.dp(22f)
    protected val plotW get() = width - ink.dp(32f)
    protected val col get() = plotW / zoom.span.toFloat()
    protected fun cx(i: Int) = zoom.x(i + 0.5)

    abstract fun maxValue(): Int
    abstract fun drawColumn(c: Canvas, i: Int, d: DayStat, y: (Int) -> Float, highlight: Boolean)
    abstract fun tooltip(d: DayStat): String?
    open fun drawOverlay(c: Canvas, y: (Int) -> Float) {}

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(180f))
    }

    private fun indexAt(x: Float): Int = Math.floor(zoom.value(x)).toInt().coerceIn(0, days.size - 1)

    override fun onDraw(c: Canvas) {
        if (days.isEmpty()) return
        zoom.plotW = plotW
        val max = maxValue()
        val y = { v: Int -> bottom - (bottom - top) * v / max.toFloat() }

        for (v in intArrayOf(0, max / 2, max)) ink.guide(c, y(v), 0f, plotW, v == 0)
        ink.axis.textAlign = Paint.Align.LEFT
        for (v in intArrayOf(0, max / 2, max)) c.drawText("$v", plotW + ink.dp(6f), y(v) + ink.axis.textSize / 3, ink.axis)

        c.save()
        c.clipRect(0f, 0f, plotW, height.toFloat())
        val first = Math.max(0, Math.floor(zoom.v0).toInt() - 1)
        val lastIdx = Math.min(days.size - 1, Math.ceil(zoom.v1).toInt())

        // About five date labels across the visible range, anchored on the newest day.
        val every = Math.max(1, Math.round(zoom.span / 5).toInt())
        val fmt = DateTimeFormatter.ofPattern(if (zoom.span <= 10) "M/d (E)" else "M/d", Locale.KOREAN)
        ink.axis.textAlign = Paint.Align.CENTER
        for (i in first..lastIdx) {
            if ((days.size - 1 - i) % every != 0) continue
            val label = fmt.format(days[i].day)
            val half = ink.axis.measureText(label) / 2
            c.drawText(label, cx(i).coerceIn(half, plotW - half), bottom + ink.dp(15f), ink.axis)
        }
        val touched = if (scrub.x >= 0f) indexAt(scrub.x) else -1
        for (i in first..lastIdx) drawColumn(c, i, days[i], y, i == touched || i == selected)
        drawOverlay(c, y)
        c.restore()

        pill = if (scrub.x < 0f) ink.zoomPill(c, zoom) else null
        if (touched >= 0) {
            val tip = tooltip(days[touched]) ?: return
            ink.bubble(c, tip, cx(touched), ink.dp(1f), width.toFloat())
        }
    }

    /** Rounded-top bar from [bottomY] up to [topY]. */
    protected fun bar(c: Canvas, x: Float, topY: Float, bottomY: Float, color: Int, roundBottom: Boolean = false) {
        val w = maxOf(ink.dp(2f), minOf(ink.dp(16f), col * 0.58f))
        val r = minOf(ink.dp(5f), w / 2)
        rect.set(x - w / 2, topY, x + w / 2, bottomY)
        path.reset()
        val b = if (roundBottom) r else 0f
        path.addRoundRect(rect, floatArrayOf(r, r, r, r, b, b, b, b), Path.Direction.CW)
        ink.fill.shader = null
        ink.fill.color = color
        c.drawPath(path, ink.fill)
    }

    /** Dashed reference line with a small label pill at its left end. */
    protected fun reference(c: Canvas, yPos: Float, label: String) {
        ink.stroke.color = p.ink2; ink.stroke.strokeWidth = ink.dp(1.25f); ink.stroke.pathEffect = ink.dash
        c.drawLine(0f, yPos, plotW, yPos, ink.stroke)
        ink.stroke.pathEffect = null
        val h = ink.small.textSize + ink.dp(10f)
        ink.bubble(c, label, ink.small.measureText(label) / 2 + ink.dp(12f), yPos - h / 2, plotW, ink.small)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (days.isEmpty()) return false
        if (zoom.onTouch(e)) { scrub.x = -1f; invalidate(); return true }
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            if (pill?.contains(e.x, e.y) == true) { zoom.reset(); scrub.x = -1f; return true }
            if (scrub.x >= 0f) onSelect?.invoke(indexAt(scrub.x))
            performClick()
            return true
        }
        scrub.onTouch(e, plotW)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    protected fun dateLabel(d: DayStat): String =
        DateTimeFormatter.ofPattern("M/d (E)", Locale.KOREAN).format(d.day)
}

/** Daily SOC used, with the period's average day as a labeled dashed line. */
class UsageChartView(context: Context, p: Palette) : DailyChart(context, p) {
    var average: Double? = null
        set(v) { field = v; invalidate() }

    override fun maxValue(): Int {
        val top = maxOf(days.maxOfOrNull { it.usage } ?: 0, average?.toInt() ?: 0, 100)
        return (top + 49) / 50 * 50
    }

    override fun drawColumn(c: Canvas, i: Int, d: DayStat, y: (Int) -> Float, highlight: Boolean) {
        if (d.usage <= 0) return
        val t = minOf(y(d.usage), bottom - ink.dp(2f))
        // Today is still running: drawn lighter so it does not read as a full day.
        val color = when {
            highlight -> p.ink
            d.day == today -> p.accentDim
            else -> p.soc
        }
        bar(c, cx(i), t, bottom, color)
    }

    override fun drawOverlay(c: Canvas, y: (Int) -> Float) {
        val a = average ?: return
        reference(c, bottom - (bottom - top) * (a / maxValue()).toFloat(), "평균 ${Math.round(a)}%")
    }

    override fun tooltip(d: DayStat): String? =
        if (d.minSoc == null) "${dateLabel(d)}  기록 없음"
        else "${dateLabel(d)}  사용 ${d.usage}%" + if (d.day == today) " (진행 중)" else ""
}

/** Each day's SOC range as a floating bar from that day's minimum to its maximum. */
class RangeChartView(context: Context, p: Palette) : DailyChart(context, p) {
    var avgMin: Double? = null
    var avgMax: Double? = null

    override fun maxValue() = 100

    override fun drawColumn(c: Canvas, i: Int, d: DayStat, y: (Int) -> Float, highlight: Boolean) {
        val mn = d.minSoc ?: return
        val mx = d.maxSoc ?: return
        val t = minOf(y(mx), y(mn) - ink.dp(3f))
        val color = if (highlight) p.ink else p.soc
        bar(c, cx(i), t, y(mn), color, roundBottom = true)
    }

    override fun drawOverlay(c: Canvas, y: (Int) -> Float) {
        avgMax?.let { reference(c, bottom - (bottom - top) * (it / 100.0).toFloat(), "평균 최고 ${Math.round(it)}%") }
        avgMin?.let { reference(c, bottom - (bottom - top) * (it / 100.0).toFloat(), "평균 최저 ${Math.round(it)}%") }
    }

    override fun tooltip(d: DayStat): String? =
        if (d.minSoc == null) "${dateLabel(d)}  기록 없음"
        else "${dateLabel(d)}  ${d.minSoc}–${d.maxSoc}%"
}

// ---------------------------------------------------------------- compact day line

/**
 * Compact single-series line over one day (e.g. battery temperature or voltage). The value
 * scale fits the data; pinch to zoom the time axis, touch for the value at that time.
 */
class MiniLineChart(
    context: Context,
    private val p: Palette,
    private val color: Int,
    private val format: (Int) -> String,
    /** Smallest visible span of the value axis, so flat data does not look noisy. */
    private val minSpan: Int,
    /** Start the value axis at 0 (magnitudes such as current). */
    private val fromZero: Boolean = false,
    /** Tooltip text where the series has a gap ([dev.chargelog.core.Drain.BREAK]). */
    private val gapLabel: String = "-",
) : View(context) {
    private val ink = ChartInk(context, p)
    private val zoom = XZoom(this, 30 * 60_000.0)
    private val scrub = Scrub(this)
    var zoomKey: String?
        get() = zoom.key
        set(v) { zoom.key = v }

    private var pts: List<Pair<Long, Int>> = emptyList()
    private var start = 0L
    private var end = 1L
    private var now = 0L
    private var zone: ZoneId = ZoneId.systemDefault()
    private var pill: RectF? = null
    private val line = Path()
    private val area = Path()

    fun set(points: List<Pair<Long, Int>>, start: Long, end: Long, now: Long, zone: ZoneId) {
        pts = points; this.start = start; this.end = end; this.now = now; this.zone = zone
        zoom.setDomain(start.toDouble(), end.toDouble())
        scrub.x = -1f
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(96f))
    }

    private fun plotW() = width - ink.dp(50f)

    override fun onDraw(c: Canvas) {
        val values = pts.filter { it.second != GAP }
        if (values.isEmpty()) return
        val plotW = plotW()
        zoom.plotW = plotW
        val top = ink.dp(28f)
        val bottom = height - ink.dp(18f)
        var lo = if (fromZero) 0 else values.minOf { it.second }
        var hi = values.maxOf { it.second }
        if (hi - lo < minSpan) {
            if (fromZero) hi = lo + minSpan
            else { val mid = (hi + lo) / 2; lo = mid - minSpan / 2; hi = lo + minSpan }
        }
        fun x(ts: Long) = zoom.x(ts.toDouble())
        fun y(v: Int) = bottom - (bottom - top) * (v - lo) / (hi - lo).toFloat()
        val last = minOf(now, end)

        ink.guide(c, y(lo), 0f, plotW, true)
        ink.guide(c, y(hi), 0f, plotW, false)
        ink.axis.textAlign = Paint.Align.LEFT
        c.drawText(format(hi), plotW + ink.dp(6f), y(hi) + ink.axis.textSize / 3, ink.axis)
        c.drawText(format(lo), plotW + ink.dp(6f), y(lo) + ink.axis.textSize / 3, ink.axis)

        c.save()
        c.clipRect(0f, 0f, plotW, height.toFloat())
        val (step, ticks) = timeTicks(start, zoom.v0, zoom.v1)
        var lastRight = -1f
        ink.axis.textAlign = Paint.Align.CENTER
        for (t in ticks) {
            val label = tickLabel(t, start, step, zone)
            val w = ink.axis.measureText(label)
            val px = x(t).coerceIn(w / 2, plotW - w / 2)
            if (px - w / 2 < lastRight + ink.dp(6f)) continue
            c.drawText(label, px, bottom + ink.dp(14f), ink.axis)
            lastRight = px + w / 2
        }

        line.reset(); area.reset()
        // Each run between gaps is its own stroke and filled area.
        var open = false
        var runEndX = 0f
        for ((ts, v) in pts) {
            if (v == GAP) {
                if (open) { area.lineTo(runEndX, bottom); area.close(); open = false }
                continue
            }
            val px = x(ts); val py = y(v)
            if (!open) { line.moveTo(px, py); area.moveTo(px, bottom); area.lineTo(px, py); open = true }
            else { line.lineTo(px, py); area.lineTo(px, py) }
            runEndX = px
        }
        if (open) {
            // A continuous series (no gap markers) is held flat up to now.
            if (pts.none { it.second == GAP }) {
                val endX = x(maxOf(last, pts.last().first))
                line.lineTo(endX, y(pts.last().second)); area.lineTo(endX, y(pts.last().second)); runEndX = endX
            }
            area.lineTo(runEndX, bottom); area.close()
        }
        ink.fill.shader = ink.areaShader(color, top, bottom, 0x30)
        c.drawPath(area, ink.fill)
        ink.fill.shader = null
        ink.stroke.color = color; ink.stroke.strokeWidth = ink.dp(2f); ink.stroke.pathEffect = null
        c.drawPath(line, ink.stroke)
        c.restore()

        pill = if (scrub.x < 0f) ink.zoomPill(c, zoom) else null
        if (scrub.x >= 0f) {
            val ts = zoom.value(scrub.x).toLong().coerceIn(start, maxOf(start, last))
            val at = pts.lastOrNull { it.first <= ts }
            val v = at?.second ?: GAP
            val px = x(ts)
            ink.stroke.color = p.ink2; ink.stroke.strokeWidth = ink.dp(1f); ink.stroke.pathEffect = ink.dash
            c.drawLine(px, top, px, bottom, ink.stroke)
            ink.stroke.pathEffect = null
            if (v == GAP) {
                ink.bubble(c, "${Format.time(ts, zone)}  $gapLabel", px, ink.dp(1f), width.toFloat())
            } else {
                ink.dot(c, px, y(v), color, ink.dp(3.5f))
                ink.bubble(c, "${Format.time(ts, zone)}  ${format(v)}", px, ink.dp(1f), width.toFloat())
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (zoom.onTouch(e)) { scrub.x = -1f; invalidate(); return true }
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            if (pill?.contains(e.x, e.y) == true) { zoom.reset(); scrub.x = -1f; return true }
            performClick()
            return true
        }
        scrub.onTouch(e, plotW())
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
