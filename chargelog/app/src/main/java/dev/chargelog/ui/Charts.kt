package dev.chargelog.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
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
 * Two-axis zoom for a chart. A two-finger pinch scales the x axis by the horizontal finger
 * spread and the value axis by the vertical spread (a diagonal pinch does both), anchored on
 * the fingers and following them (pan). Double tap, or tapping the zoom pill, resets. The
 * viewport is kept per [key] so it survives the screen being rebuilt for a new reading.
 */
class XZoom(private val view: View, private val minSpan: Double) {
    // x (time or day index)
    var lo = 0.0; private set
    var hi = 1.0; private set
    var v0 = 0.0; private set
    var v1 = 1.0; private set
    // y (value)
    var yLo = 0.0; private set
    var yHi = 1.0; private set
    var w0 = 0.0; private set
    var w1 = 1.0; private set
    private var yMinSpan = 1.0
    private var yInit = false

    var key: String? = null
    /** Plot geometry in px, set by the chart on every draw. */
    var plotW = 1f
    var plotTop = 0f
    var plotBottom = 1f

    val span get() = v1 - v0
    val ySpan get() = w1 - w0
    val xZoomed get() = Math.abs(v0 - b0) > (b1 - b0) * 0.005 || Math.abs(v1 - b1) > (b1 - b0) * 0.005
    val yZoomed get() = ySpan < (yHi - yLo) * 0.995
    val zoomed get() = xZoomed || yZoomed
    val factor get() = (b1 - b0) / span
    val yFactor get() = (yHi - yLo) / ySpan

    /** True from the first second finger until every finger is lifted. */
    var pinching = false; private set
    private var prevFocus = 0f
    private var prevFocusY = 0f
    private val axisMinSpread = view.context.dp(48f)

    /** Starting viewport ("원래대로" returns here); defaults to the whole domain. */
    private var b0 = 0.0
    private var b1 = 1.0

    fun setDomain(lo: Double, hi: Double, base0: Double = lo, base1: Double = hi) {
        this.lo = lo
        this.hi = maxOf(hi, lo + 1e-6)
        b0 = base0.coerceIn(this.lo, this.hi); b1 = base1.coerceIn(b0 + 1e-6, this.hi)
        val s = key?.let { saved[it] }
        if (s != null && s[0] == lo && s[1] == this.hi) { v0 = s[2]; v1 = s[3] } else { v0 = b0; v1 = b1 }
    }

    /** Value-axis range; keeps the current (or saved) vertical zoom while the range is unchanged. */
    fun yDomain(lo: Double, hi: Double, minSpan: Double) {
        val h = maxOf(hi, lo + 1e-6)
        if (yInit && lo == yLo && h == yHi) return
        yLo = lo; yHi = h; yMinSpan = minSpan; yInit = true
        val s = key?.let { saved[it] }
        if (s != null && s.size >= 8 && s[4] == lo && s[5] == h) { w0 = s[6]; w1 = s[7] } else { w0 = lo; w1 = h }
    }

    fun x(v: Double): Float = ((v - v0) / span * plotW).toFloat()
    fun value(x: Float): Double = v0 + x / plotW * span
    fun y(v: Double): Float = plotBottom - ((v - w0) / ySpan * (plotBottom - plotTop)).toFloat()
    private fun yValue(py: Float): Double = w0 + (plotBottom - py) / (plotBottom - plotTop) * ySpan

    fun reset() {
        v0 = b0; v1 = b1; w0 = yLo; w1 = yHi
        save()
        view.invalidate()
    }

    private fun save() { key?.let { saved[it] = doubleArrayOf(lo, hi, v0, v1, yLo, yHi, w0, w1) } }

    private val scale = ScaleGestureDetector(view.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            prevFocus = d.focusX; prevFocusY = d.focusY; return true
        }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            // Each axis follows the finger spread along it, once the fingers are clearly apart
            // on that axis; otherwise it only pans.
            val fx = if (d.previousSpanX > axisMinSpread && d.currentSpanX > axisMinSpread) d.currentSpanX / d.previousSpanX else 1f
            val fy = if (d.previousSpanY > axisMinSpread && d.currentSpanY > axisMinSpread) d.currentSpanY / d.previousSpanY else 1f

            val full = hi - lo
            val newSpan = (span / fx).coerceIn(minOf(minSpan, full), full)
            val anchor = value(prevFocus.coerceIn(0f, plotW))
            val start = (anchor - d.focusX.coerceIn(0f, plotW) / plotW * newSpan).coerceIn(lo, hi - newSpan)
            v0 = start; v1 = start + newSpan

            if (yInit && plotBottom > plotTop) {
                val fullY = yHi - yLo
                val newYSpan = (ySpan / fy).coerceIn(minOf(yMinSpan, fullY), fullY)
                val h = plotBottom - plotTop
                val anchorY = yValue(prevFocusY.coerceIn(plotTop, plotBottom))
                val startY = (anchorY - (plotBottom - d.focusY.coerceIn(plotTop, plotBottom)) / h * newYSpan)
                    .coerceIn(yLo, yHi - newYSpan)
                w0 = startY; w1 = startY + newYSpan
            }
            prevFocus = d.focusX; prevFocusY = d.focusY
            save()
            view.invalidate()
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    // Double tap. Tracked here (not with a GestureDetector) and remembered per [key], because a
    // tap can rebuild the screen (selecting a day), which replaces the view between the two taps.
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var ownTap: DoubleArray? = null
    private val tapSlop = view.context.dp(12f)
    private val doubleTapSlop = view.context.dp(48f)

    private fun trackTap(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downTime = e.eventTime; downX = e.x; downY = e.y; moved = false }
            MotionEvent.ACTION_MOVE ->
                if (Math.abs(e.x - downX) > tapSlop || Math.abs(e.y - downY) > tapSlop) moved = true
            MotionEvent.ACTION_CANCEL -> moved = true
            MotionEvent.ACTION_UP -> {
                if (pinching || moved || e.eventTime - downTime > TAP_MS) return
                val k = key
                val prev = if (k != null) lastTap[k] else ownTap
                val second = prev != null && e.eventTime - prev[0].toLong() in 0..DOUBLE_TAP_MS &&
                    Math.abs(e.x - prev[1]) < doubleTapSlop && Math.abs(e.y - prev[2]) < doubleTapSlop
                val next = if (second) null else doubleArrayOf(e.eventTime.toDouble(), e.x.toDouble(), e.y.toDouble())
                if (k != null) { if (next == null) lastTap.remove(k) else lastTap[k] = next } else ownTap = next
                if (second && zoomed) reset()
            }
        }
    }

    /** Feed every touch event. Returns true while a pinch owns the gesture. */
    fun onTouch(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) pinching = false
        scale.onTouchEvent(e)
        if (e.pointerCount > 1 || scale.isInProgress) {
            pinching = true
            view.parent?.requestDisallowInterceptTouchEvent(true)
        }
        trackTap(e)
        return pinching
    }

    companion object {
        private val saved = HashMap<String, DoubleArray>()
        private val lastTap = HashMap<String, DoubleArray>()
        private const val TAP_MS = 300L
        private const val DOUBLE_TAP_MS = 400L
    }
}

/**
 * Appends one run as a monotone cubic curve (Fritsch–Carlson: smooth, never overshoots the
 * data) to [line], and the same outline closed down to [bottom] to [area].
 */
internal fun smoothRun(line: Path, area: Path, xs: List<Float>, ys: List<Float>, bottom: Float) {
    val n = xs.size
    line.moveTo(xs[0], ys[0]); area.moveTo(xs[0], bottom); area.lineTo(xs[0], ys[0])
    if (n == 1) { area.lineTo(xs[0], bottom); area.close(); return }
    val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / maxOf(xs[it + 1] - xs[it], 1e-3f) }
    val m = FloatArray(n)
    m[0] = d[0]; m[n - 1] = d[n - 2]
    for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
    for (i in 0 until n - 1) {
        if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
        val a = m[i] / d[i]; val b = m[i + 1] / d[i]
        val h = a * a + b * b
        if (h > 9f) { val t = 3f / Math.sqrt(h.toDouble()).toFloat(); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
    }
    for (i in 0 until n - 1) {
        val dx = (xs[i + 1] - xs[i]) / 3f
        val c1x = xs[i] + dx; val c1y = ys[i] + m[i] * dx
        val c2x = xs[i + 1] - dx; val c2y = ys[i + 1] - m[i + 1] * dx
        line.cubicTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1])
        area.cubicTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1])
    }
    area.lineTo(xs[n - 1], bottom); area.close()
}

/** Axis label for a tick: whole numbers without decimals. */
private fun tickText(v: Double): String =
    if (Math.abs(v - Math.round(v)) < 1e-6) Math.round(v).toString() else String.format(Locale.US, "%.1f", v)

/** Round tick values covering [lo, hi] with about [target] intervals. */
fun niceTicks(lo: Double, hi: Double, target: Int = 4): List<Double> {
    if (hi <= lo) return listOf(lo)
    val raw = (hi - lo) / target
    val mag = Math.pow(10.0, Math.floor(Math.log10(raw)))
    val n = raw / mag
    val step = (if (n < 1.5) 1.0 else if (n < 3.0) 2.0 else if (n < 7.0) 5.0 else 10.0) * mag
    val out = ArrayList<Double>()
    var t = Math.ceil(lo / step - 1e-9) * step
    while (t <= hi + step * 1e-6) { out.add(t); t += step }
    return out
}

/** Paints and small drawing helpers shared by every chart. */
class ChartInk(private val context: Context, private val p: Palette) {
    fun dp(v: Float) = context.dp(v)

    val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.sp(12f); color = p.muted }
    val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.sp(13f); typeface = NUM_FACE; color = p.ink }
    val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.sp(12f); color = p.ink2 }
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
        val parts = ArrayList<String>()
        if (zoom.xZoomed) parts.add(String.format(Locale.US, "가로 ×%.1f", zoom.factor))
        if (zoom.yZoomed) parts.add(String.format(Locale.US, "세로 ×%.1f", zoom.yFactor))
        val t = parts.joinToString(" · ") + "  원래대로"
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

/**
 * Common one-finger behaviour: show the value under the finger; vertical drags scroll the page.
 * The tooltip stays for a moment after the finger lifts, then goes away by itself; it is
 * dropped at once when the page takes the gesture over for scrolling.
 */
private class Scrub(private val view: View) {
    var x = -1f
    private var downX = 0f
    private var downY = 0f
    private var horizontal = false
    private val hide = Runnable { x = -1f; view.invalidate() }

    fun clear() { view.removeCallbacks(hide); x = -1f }

    fun onTouch(e: MotionEvent, plotW: Float) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.removeCallbacks(hide)
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
            MotionEvent.ACTION_UP -> { view.removeCallbacks(hide); view.postDelayed(hide, LINGER_MS) }
            MotionEvent.ACTION_CANCEL -> clear()
        }
        view.invalidate()
    }

    private companion object { const val LINGER_MS = 2500L }
}

/** Touch handling shared by every chart: pinch zoom first, then the reset pill, then scrubbing. */
private class ChartTouch(private val view: View, private val zoom: XZoom, private val scrub: Scrub) {
    private var onPill = false

    /**
     * @param pill where the "원래대로" pill was last drawn, if it is showing
     * @param onTap called with the scrub position when a one-finger touch ends
     */
    fun handle(e: MotionEvent, plotW: Float, pill: RectF?, onTap: (Float) -> Unit = {}): Boolean {
        if (zoom.onTouch(e)) { scrub.clear(); onPill = false; view.invalidate(); return true }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Decided on touch-down: the pill is hidden while a tooltip shows, so by the time
                // the finger lifts its rectangle would already be gone.
                val slop = view.context.dp(8f)
                onPill = pill != null && e.x >= pill.left - slop && e.x <= pill.right + slop &&
                    e.y >= pill.top - slop && e.y <= pill.bottom + slop
                if (onPill) return true
            }
            MotionEvent.ACTION_UP -> {
                if (onPill) { onPill = false; zoom.reset(); scrub.clear(); view.invalidate(); return true }
                val at = scrub.x
                scrub.onTouch(e, plotW)
                if (at >= 0f) onTap(at)
                view.performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> onPill = false
            else -> if (onPill) return true
        }
        scrub.onTouch(e, plotW)
        return true
    }
}

// ---------------------------------------------------------------- SOC over a day

/** SOC over one day (0–24 h). Charging periods are shaded; estimated stretches are dashed. */
class SocChartView(context: Context, private val p: Palette) : View(context) {
    private val ink = ChartInk(context, p)
    private val zoom = XZoom(this, 30 * 60_000.0)
    private val scrub = Scrub(this)
    private val touch = ChartTouch(this, zoom, scrub)
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
        scrub.clear()
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val height = if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY) MeasureSpec.getSize(h) else context.dpi(210f)
        setMeasuredDimension(MeasureSpec.getSize(w), height)
    }

    private fun plotW() = width - ink.dp(38f)

    override fun onDraw(c: Canvas) {
        val plotW = plotW()
        zoom.plotW = plotW
        val top = ink.dp(30f)
        val bottom = height - ink.dp(24f)
        val last = minOf(now, end)
        zoom.plotTop = top; zoom.plotBottom = bottom
        zoom.yDomain(0.0, 100.0, 10.0)
        fun x(ts: Long) = zoom.x(ts.toDouble())
        fun y(level: Int) = zoom.y(level.toDouble())

        // Guides and the value axis follow the vertical zoom.
        val yTicks = niceTicks(zoom.w0, zoom.w1, 4)
        for (v in yTicks) ink.guide(c, zoom.y(v), 0f, plotW, v == 0.0)
        ink.axis.textAlign = Paint.Align.LEFT
        for (v in yTicks) c.drawText(tickText(v), plotW + ink.dp(6f), zoom.y(v) + ink.axis.textSize / 3, ink.axis)

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
            c.drawText(label, px, bottom + ink.dp(17f), ink.axis)
            lastLabelRight = px + w / 2
        }

        // Series stay inside the plot area when zoomed vertically.
        c.save()
        c.clipRect(0f, top - ink.dp(2f), plotW, bottom + ink.dp(1f))
        var marker: Pair<Float, Float>? = null
        var markerLevel = 0
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

            ink.stroke.strokeWidth = ink.dp(2.75f)
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
                if (lx in 0f..plotW && ly in top..bottom) { marker = lx to ly; markerLevel = tail.level }
            }
        }
        c.restore()
        marker?.let { (lx, ly) ->
            ink.dot(c, lx, ly, p.soc)
            ink.bubble(c, "$markerLevel%", lx - ink.dp(18f), maxOf(0f, ly - ink.dp(34f)), plotW)
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
            if (y(level) in top..bottom) ink.dot(c, px, y(level), p.soc)
            ink.bubble(c, "${Format.time(ts, zone)}  $level%", px, ink.dp(1f), width.toFloat())
        }
    }

    /** SOC at [ts], interpolated between the surrounding readings. */
    private fun levelAt(ts: Long): Int? {
        var prev: Sample? = null
        for (s in points) {
            if (s.ts > ts) {
                val a = prev ?: return s.level
                if (s.ts <= a.ts) return s.level
                val f = (ts - a.ts).toDouble() / (s.ts - a.ts)
                return Math.round(a.level + (s.level - a.level) * f).toInt()
            }
            prev = s
        }
        return prev?.level
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = touch.handle(e, plotW(), pill)

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
    private val touch = ChartTouch(this, zoom, scrub)
    var zoomKey: String?
        get() = zoom.key
        set(v) { zoom.key = v }

    var days: List<DayStat> = emptyList()
        set(v) { field = v; scrub.clear(); zoom.setDomain(0.0, v.size.toDouble()); invalidate() }
    /** Persistently selected column (drawn in ink); -1 for none. */
    var selected = -1
        set(v) { field = v; invalidate() }
    var onSelect: ((Int) -> Unit)? = null
    /**
     * Per day, whether the statistics filter counts it (same order as [days]); null counts all.
     * Days that are not counted stay on the time axis but are drawn faint.
     */
    var counted: BooleanArray? = null
        set(v) { field = v; invalidate() }
    protected fun isCounted(i: Int): Boolean = counted?.getOrNull(i) ?: true

    private var pill: RectF? = null
    protected val path = Path()
    protected val rect = RectF()
    protected val today: LocalDate = LocalDate.now()

    protected val top get() = ink.dp(30f)
    protected val bottom get() = height - ink.dp(24f)
    protected val plotW get() = width - ink.dp(38f)
    protected val col get() = plotW / zoom.span.toFloat()
    protected fun cx(i: Int) = zoom.x(i + 0.5)
    /** Pixel y of a value under the current vertical zoom. */
    protected fun yOf(v: Double) = zoom.y(v)

    abstract fun maxValue(): Int
    abstract fun drawColumn(c: Canvas, i: Int, d: DayStat, y: (Int) -> Float, highlight: Boolean, counted: Boolean)
    abstract fun tooltip(d: DayStat): String?
    open fun drawOverlay(c: Canvas, y: (Int) -> Float) {}

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(210f))
    }

    private fun indexAt(x: Float): Int = Math.floor(zoom.value(x)).toInt().coerceIn(0, days.size - 1)

    override fun onDraw(c: Canvas) {
        if (days.isEmpty()) return
        zoom.plotW = plotW
        zoom.plotTop = top; zoom.plotBottom = bottom
        val max = maxValue()
        zoom.yDomain(0.0, max.toDouble(), max / 10.0)
        val y = { v: Int -> zoom.y(v.toDouble()) }

        val yTicks = niceTicks(zoom.w0, zoom.w1, 4)
        for (v in yTicks) ink.guide(c, zoom.y(v), 0f, plotW, v == 0.0)
        ink.axis.textAlign = Paint.Align.LEFT
        for (v in yTicks) c.drawText(tickText(v), plotW + ink.dp(6f), zoom.y(v) + ink.axis.textSize / 3, ink.axis)

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
            c.drawText(label, cx(i).coerceIn(half, plotW - half), bottom + ink.dp(17f), ink.axis)
        }
        val touched = if (scrub.x >= 0f) indexAt(scrub.x) else -1
        c.save()
        c.clipRect(0f, top - ink.dp(2f), plotW, bottom)
        for (i in first..lastIdx) drawColumn(c, i, days[i], y, i == touched || i == selected, isCounted(i))
        drawOverlay(c, y)
        c.restore()
        c.restore()

        pill = if (scrub.x < 0f) ink.zoomPill(c, zoom) else null
        if (touched >= 0) {
            val tip = tooltip(days[touched]) ?: return
            val note = if (isCounted(touched) || days[touched].minSoc == null) "" else " · 평균 제외"
            ink.bubble(c, tip + note, cx(touched), ink.dp(1f), width.toFloat())
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
        return touch.handle(e, plotW, pill) { x -> onSelect?.invoke(indexAt(x)) }
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

    override fun drawColumn(c: Canvas, i: Int, d: DayStat, y: (Int) -> Float, highlight: Boolean, counted: Boolean) {
        if (d.usage <= 0) return
        val base = y(0)
        val t = minOf(y(d.usage), base - ink.dp(2f))
        // Today is still running: drawn lighter so it does not read as a full day.
        val color = when {
            highlight -> p.ink
            !counted -> p.faint
            d.day == today -> p.accentDim
            else -> p.soc
        }
        bar(c, cx(i), t, base, color)
    }

    override fun drawOverlay(c: Canvas, y: (Int) -> Float) {
        val a = average ?: return
        reference(c, yOf(a), "평균 ${Math.round(a)}%")
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

    override fun drawColumn(c: Canvas, i: Int, d: DayStat, y: (Int) -> Float, highlight: Boolean, counted: Boolean) {
        val mn = d.minSoc ?: return
        val mx = d.maxSoc ?: return
        val t = minOf(y(mx), y(mn) - ink.dp(3f))
        val color = if (highlight) p.ink else if (counted) p.soc else p.faint
        bar(c, cx(i), t, y(mn), color, roundBottom = true)
    }

    override fun drawOverlay(c: Canvas, y: (Int) -> Float) {
        avgMax?.let { reference(c, yOf(it), "평균 최고 ${Math.round(it)}%") }
        avgMin?.let { reference(c, yOf(it), "평균 최저 ${Math.round(it)}%") }
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
    /** Display moving-average window; 0 draws the raw values. */
    private val smoothMs: Long = 0L,
) : View(context) {
    private val ink = ChartInk(context, p)
    private val zoom = XZoom(this, 30 * 60_000.0)
    private val scrub = Scrub(this)
    private val touch = ChartTouch(this, zoom, scrub)
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
        pts = dev.chargelog.core.Smooth.movingAverage(points, smoothMs)
        this.start = start; this.end = end; this.now = now; this.zone = zone
        // Open framed on the hours that have data (whole hours, at least 3 h); pinch out for the day.
        val real = pts.filter { it.second != GAP }
        if (real.isEmpty()) zoom.setDomain(start.toDouble(), end.toDouble())
        else {
            val h = 3_600_000L
            val from = start + (real.first().first - start) / h * h
            val to = minOf(end, start + ((maxOf(real.last().first, minOf(now, end)) - start + h - 1) / h) * h)
            val b0 = maxOf(start, minOf(from, to - 3 * h))
            val b1 = maxOf(to, b0 + 3 * h).coerceAtMost(end)
            if (b1 - b0 >= (end - start) * 0.75) zoom.setDomain(start.toDouble(), end.toDouble())
            else zoom.setDomain(start.toDouble(), end.toDouble(), b0.toDouble(), b1.toDouble())
        }
        scrub.clear()
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(180f))
    }

    private fun plotW() = width - ink.dp(60f)

    override fun onDraw(c: Canvas) {
        val values = pts.filter { it.second != GAP }
        if (values.isEmpty()) return
        val plotW = plotW()
        zoom.plotW = plotW
        val top = ink.dp(28f)
        val bottom = height - ink.dp(22f)
        var lo = if (fromZero) 0 else values.minOf { it.second }
        var hi = values.maxOf { it.second }
        if (hi - lo < minSpan) {
            if (fromZero) hi = lo + minSpan
            else { val mid = (hi + lo) / 2; lo = mid - minSpan / 2; hi = lo + minSpan }
        }
        zoom.plotTop = top; zoom.plotBottom = bottom
        zoom.yDomain(lo.toDouble(), hi.toDouble(), maxOf(1.0, (hi - lo) / 10.0))
        fun x(ts: Long) = zoom.x(ts.toDouble())
        fun y(v: Int) = zoom.y(v.toDouble())
        val last = minOf(now, end)

        val yTicks = niceTicks(zoom.w0, zoom.w1, 2)
        ink.axis.textAlign = Paint.Align.LEFT
        yTicks.forEachIndexed { i, v ->
            ink.guide(c, zoom.y(v), 0f, plotW, i == 0 && v <= zoom.w0 + 1e-9)
            c.drawText(format(Math.round(v).toInt()), plotW + ink.dp(6f), zoom.y(v) + ink.axis.textSize / 3, ink.axis)
        }

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
            c.drawText(label, px, bottom + ink.dp(17f), ink.axis)
            lastRight = px + w / 2
        }

        c.save()
        c.clipRect(0f, top - ink.dp(2f), plotW, bottom + ink.dp(1f))
        line.reset(); area.reset()
        // Each run between gaps is its own smooth stroke and filled area.
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        fun flush() {
            if (xs.isNotEmpty()) smoothRun(line, area, xs, ys, bottom)
            xs.clear(); ys.clear()
        }
        for ((ts, v) in pts) {
            if (v == GAP) { flush(); continue }
            val px = x(ts)
            if (xs.isNotEmpty() && px <= xs.last()) { ys[ys.size - 1] = y(v); continue }
            xs.add(px); ys.add(y(v))
        }
        // A continuous series (no gap markers) is held flat up to now.
        if (xs.isNotEmpty() && pts.none { it.second == GAP }) {
            val endX = x(maxOf(last, pts.last().first))
            if (endX > xs.last()) { xs.add(endX); ys.add(ys.last()) }
        }
        flush()
        ink.fill.shader = ink.areaShader(color, top, bottom, 0x30)
        c.drawPath(area, ink.fill)
        ink.fill.shader = null
        ink.stroke.color = color; ink.stroke.strokeWidth = ink.dp(2.5f); ink.stroke.pathEffect = null
        c.drawPath(line, ink.stroke)
        c.restore()
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
                if (y(v) in top..bottom) ink.dot(c, px, y(v), color, ink.dp(3.5f))
                ink.bubble(c, "${Format.time(ts, zone)}  ${format(v)}", px, ink.dp(1f), width.toFloat())
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = touch.handle(e, plotW(), pill)

    override fun performClick(): Boolean = super.performClick()
}
