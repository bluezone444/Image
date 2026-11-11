package dev.chargelog.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import dev.chargelog.core.ThemeMode

/**
 * Design tokens for the current light/dark mode. Text always uses the ink tokens; the three
 * metric hues (SOC, temperature, voltage) only color marks. The metric hues are the first three
 * slots of the validated reference palette (all-pairs CVD and normal-vision checks pass in both
 * modes); light-mode SOC is under 3:1 on white, so every chart carries its values as text.
 */
class Palette(context: Context) {
    /** Follows the in-app setting (system / light / dark), not only the system mode. */
    val dark = Theme.isDark(context)

    // Surfaces
    val bg = if (dark) 0xFF0C1015.toInt() else 0xFFF2F4F7.toInt()
    val card = if (dark) 0xFF151A21.toInt() else 0xFFFFFFFF.toInt()
    val line = if (dark) 0xFF2F3742.toInt() else 0xFFD0D5DD.toInt()
    val grid = if (dark) 0xFF262D37.toInt() else 0xFFE4E7EC.toInt()

    // Text
    val ink = if (dark) 0xFFE9EDF2.toInt() else 0xFF101828.toInt()
    // Secondary ≈10.5:1 and muted ≥5.4:1 (light) / 6.9:1 (dark) on cards: readable small text.
    val ink2 = if (dark) 0xFFC3CAD5.toInt() else 0xFF344054.toInt()
    val muted = if (dark) 0xFF9AA4B2.toInt() else 0xFF5D6B82.toInt()

    // Metrics
    val soc = if (dark) 0xFF199E70.toInt() else 0xFF1BAF7A.toInt()
    val temp = if (dark) 0xFFD95926.toInt() else 0xFFEB6834.toInt()
    val volt = if (dark) 0xFF3987E5.toInt() else 0xFF2A78D6.toInt()

    /** SOC is the primary metric; kept under the old names used across the charts. */
    val accent get() = soc
    val accentDim = if (dark) 0xFF1B3A2F.toInt() else 0xFFD5F2E6.toInt()
    val accentWash = if (dark) 0x2E199E70 else 0x241BAF7A
    /** Marks that are shown for context but not counted (days outside the statistics filter). */
    val faint = if (dark) 0xFF3A4452.toInt() else 0xFFC7CDD6.toInt()

    // Status
    val danger = if (dark) 0xFFF97066.toInt() else 0xFFD92D20.toInt()
    val warnBg = if (dark) 0xFF2B2113.toInt() else 0xFFFFF6E5.toInt()
    val warnLine = if (dark) 0xFF5C4317.toInt() else 0xFFF5D9A3.toInt()
}

/** The in-app appearance setting and how it is applied to an activity's window. */
object Theme {
    private const val KEY = "theme_mode"

    fun mode(context: Context): ThemeMode =
        ThemeMode.of(context.getSharedPreferences("ui", Context.MODE_PRIVATE).getString(KEY, null))

    fun setMode(context: Context, mode: ThemeMode) {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
    }

    fun systemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun isDark(context: Context): Boolean = mode(context).isDark(systemDark(context))

    /**
     * Call before super.onCreate(): picks the platform theme for the chosen mode so dialogs,
     * toasts' anchors, scroll edge effects and text selection match the app's own colors.
     */
    fun applyTo(activity: Activity) {
        activity.setTheme(
            if (isDark(activity)) android.R.style.Theme_DeviceDefault_NoActionBar
            else android.R.style.Theme_DeviceDefault_Light_NoActionBar
        )
    }

    /** Window background, status/navigation bar colors and dark-or-light bar icons. */
    fun paintWindow(activity: Activity, p: Palette, navColor: Int = p.bg) {
        val w = activity.window
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        w.setBackgroundDrawable(ColorDrawable(p.bg))
        @Suppress("DEPRECATION")
        run { w.statusBarColor = p.bg; w.navigationBarColor = navColor }
        if (Build.VERSION.SDK_INT >= 28) @Suppress("DEPRECATION") run { w.navigationBarDividerColor = Color.TRANSPARENT }
        if (Build.VERSION.SDK_INT >= 29) { w.isStatusBarContrastEnforced = false; w.isNavigationBarContrastEnforced = false }
        val decor = w.decorView
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            decor.windowInsetsController?.setSystemBarsAppearance(if (p.dark) 0 else light, light)
        }
        // Still honoured on every version, and the only way before Android 11.
        @Suppress("DEPRECATION")
        run {
            val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            decor.systemUiVisibility =
                if (p.dark) decor.systemUiVisibility and light.inv() else decor.systemUiVisibility or light
        }
    }
}

/** Medium weight sans with tabular figures, for numbers that update in place. */
val NUM_FACE: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = Math.round(dp(v))
fun Context.sp(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

fun Context.label(
    text: CharSequence, size: Float, color: Int, bold: Boolean = false,
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    includeFontPadding = false
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

/** Large figure: medium weight, tabular digits. */
fun Context.number(text: CharSequence, size: Float, color: Int): TextView =
    label(text, size, color).apply {
        typeface = NUM_FACE
        fontFeatureSettings = "tnum"
    }

/** Small caption above a value. */
fun Context.caption(text: CharSequence, p: Palette): TextView =
    label(text, 13f, p.muted).apply { letterSpacing = 0.01f }

fun Context.roundRect(color: Int, radiusDp: Float, strokeColor: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp)
        if (strokeColor != null) setStroke(Math.max(1, dpi(1f)), strokeColor)
    }

fun Context.column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

/** Flat surface with a hairline border. */
fun Context.card(p: Palette, padDp: Float = 16f): LinearLayout = column().apply {
    background = roundRect(p.card, 18f, p.line)
    val pad = dpi(padDp)
    setPadding(pad, pad, pad, pad)
}

fun lp(w: Int, h: Int, weight: Float = 0f, top: Int = 0, left: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(w, h, weight).apply { topMargin = top; leftMargin = left }

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Section heading with optional trailing text on the right. */
fun Context.sectionTitle(p: Palette, text: String, trailing: CharSequence? = null): LinearLayout = row().apply {
    // The title keeps its natural width; a long trailing note wraps instead of squeezing it.
    if (trailing == null) addView(label(text, 16f, p.ink, bold = true), lp(0, WRAP, 1f))
    else {
        addView(label(text, 16f, p.ink, bold = true), lp(WRAP, WRAP))
        addView(label(trailing, 13f, p.muted).apply { gravity = Gravity.END }, lp(0, WRAP, 1f, left = dpi(12f)))
    }
}

/** "label ........ value" line used inside cards. */
fun Context.kv(p: Palette, key: String, value: String): LinearLayout =
    if (value.length > 14) {
        // A long value would squeeze the key to nothing in one row: put it on its own line.
        column().apply {
            addView(label(key, 15f, p.ink2))
            addView(number(value, 15f, p.ink), lp(MATCH, WRAP, top = dpi(4f)))
            setPadding(0, dpi(8f), 0, dpi(8f))
        }
    } else row().apply {
        addView(label(key, 15f, p.ink2), lp(0, WRAP, 1f))
        addView(number(value, 15f, p.ink), lp(WRAP, WRAP, left = dpi(12f)))
        setPadding(0, dpi(8f), 0, dpi(8f))
    }

/** Stat tile: caption, large figure, optional muted note under it. */
fun Context.tile(p: Palette, caption: String, value: String, note: String? = null, size: Float = 22f): LinearLayout =
    column().apply {
        addView(caption(caption, p))
        // One line that shrinks to fit: "100%" must not wrap in a narrow column or at a large
        // system font size.
        addView(number(value, size, p.ink).apply {
            maxLines = 1
            gravity = Gravity.BOTTOM or Gravity.START
            setAutoSizeTextTypeUniformWithConfiguration(10, Math.round(size), 1, TypedValue.COMPLEX_UNIT_SP)
        }, lp(MATCH, Math.round(sp(size) * 1.22f), top = dpi(5f)))
        if (note != null) addView(label(note, 12f, p.muted), lp(WRAP, WRAP, top = dpi(5f)))
    }

/** Tiles laid out in equal columns separated by hairlines. */
fun Context.tileRow(p: Palette, tiles: List<View>): LinearLayout = row().apply {
    gravity = Gravity.TOP
    tiles.forEachIndexed { i, t ->
        if (i > 0) addView(View(context).apply { setBackgroundColor(p.line) },
            LinearLayout.LayoutParams(Math.max(1, dpi(1f)), MATCH).apply { leftMargin = dpi(10f); rightMargin = dpi(10f) })
        addView(t, lp(0, WRAP, 1f))
    }
}

/** Pill with a colored dot; the dot carries the meaning's color, the text stays ink. */
fun Context.chip(p: Palette, text: String, dotColor: Int): LinearLayout = row().apply {
    background = roundRect(p.bg, 20f, p.line)
    setPadding(dpi(10f), dpi(5f), dpi(12f), dpi(5f))
    addView(View(context).apply { background = roundRect(dotColor, 4f) }, lp(dpi(8f), dpi(8f)))
    addView(label(text, 14f, p.ink, bold = true), lp(WRAP, WRAP, left = dpi(7f)))
}

/** Small legend entry: colored mark + muted text. */
fun Context.legend(p: Palette, text: String, color: Int, dashed: Boolean = false): LinearLayout = row().apply {
    val mark = if (dashed) {
        object : View(context) {
            val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = dp(1.5f) }
            override fun onDraw(c: Canvas) {
                var x = 0f
                while (x < width) { c.drawLine(x, height / 2f, minOf(x + dp(3f), width.toFloat()), height / 2f, pt); x += dp(6f) }
            }
        }
    } else View(context).apply { background = roundRect(color, 2f) }
    addView(mark, lp(dpi(14f), if (dashed) dpi(8f) else dpi(9f)))
    addView(label(text, 12f, p.ink2), lp(WRAP, WRAP, left = dpi(6f)))
}

/**
 * Segmented control (e.g. 7 / 30 / 90 days). With [stretch] the options share the full width
 * equally; otherwise each is as wide as its label.
 */
fun Context.segmented(
    p: Palette, options: List<String>, selected: Int, stretch: Boolean = false, onPick: (Int) -> Unit,
): LinearLayout =
    row().apply {
        background = roundRect(p.bg, 12f, p.line)
        setPadding(dpi(3f), dpi(3f), dpi(3f), dpi(3f))
        val padH = dpi(if (stretch) 4f else 12f)
        options.forEachIndexed { i, o ->
            val on = i == selected
            addView(label(o, 14f, if (on) p.ink else p.ink2, bold = on).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (on) background = roundRect(p.card, 9f, p.line)
                setPadding(padH, dpi(9f), padH, dpi(9f))
                setOnClickListener { if (!on) onPick(i) }
            }, if (stretch) lp(0, WRAP, 1f) else lp(WRAP, WRAP))
        }
    }

/** One choice in a single-select list: radio mark, title and an optional explanation. */
fun Context.optionRow(p: Palette, title: String, note: String?, on: Boolean, onPick: () -> Unit): LinearLayout =
    row().apply {
        setPadding(dpi(2f), dpi(11f), dpi(2f), dpi(11f))
        addView(object : View(context) {
            val pt = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                val r = minOf(width, height) / 2f
                pt.style = Paint.Style.STROKE; pt.strokeWidth = dp(2f); pt.color = if (on) p.ink else p.muted
                c.drawCircle(width / 2f, height / 2f, r - dp(1.5f), pt)
                if (on) { pt.style = Paint.Style.FILL; c.drawCircle(width / 2f, height / 2f, r - dp(5.5f), pt) }
            }
        }, lp(dpi(20f), dpi(20f)))
        addView(column().apply {
            addView(label(title, 15f, p.ink, bold = on))
            if (note != null) addView(label(note, 13f, p.muted), lp(MATCH, WRAP, top = dpi(3f)))
        }, lp(0, WRAP, 1f, left = dpi(12f)))
        setOnClickListener { if (!on) onPick() }
    }

/** Circular SOC gauge with the value in the middle. */
class RingGauge(context: Context, private val p: Palette) : View(context) {
    var level = 0
        set(v) { field = v.coerceIn(0, 100); invalidate() }
    var caption = "SOC"
        set(v) { field = v; invalidate() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = NUM_FACE; textAlign = Paint.Align.CENTER }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val oval = RectF()

    override fun onDraw(c: Canvas) {
        val sw = context.dp(9f)
        val size = minOf(width, height).toFloat()
        val l = (width - size) / 2 + sw / 2
        val t = (height - size) / 2 + sw / 2
        oval.set(l, t, l + size - sw, t + size - sw)
        track.strokeWidth = sw; track.color = p.accentDim
        arc.strokeWidth = sw; arc.color = p.soc
        c.drawArc(oval, 0f, 360f, false, track)
        if (level > 0) c.drawArc(oval, -90f, 360f * level / 100f, false, arc)

        big.color = p.ink
        big.textSize = size * 0.27f
        val cy = height / 2f
        c.drawText("$level", width / 2f - size * 0.04f, cy + big.textSize * 0.30f, big)
        small.color = p.ink2
        small.textSize = size * 0.12f
        val numW = big.measureText("$level")
        c.drawText("%", width / 2f - size * 0.04f + numW / 2 + small.textSize * 0.55f, cy + big.textSize * 0.30f, small)
        small.color = p.muted
        small.textSize = size * 0.095f
        c.drawText(caption, width / 2f, cy + big.textSize * 0.30f + small.textSize * 1.7f, small)
    }
}

/** Axis-free line of recent values, with a soft fill and the latest point marked. */
class Sparkline(
    context: Context,
    private val p: Palette,
    private val color: Int,
    private val fromZero: Boolean = false,
) : View(context) {
    private var values: List<Pair<Long, Int>> = emptyList()
    private var start = 0L
    private var end = 1L
    private var minSpan = 1
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val area = Path()

    fun set(values: List<Pair<Long, Int>>, start: Long, end: Long, minSpan: Int) {
        this.values = values; this.start = start; this.end = maxOf(end, start + 1); this.minSpan = minSpan
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val real = values.filter { it.second != GAP }
        if (real.isEmpty()) return
        val pad = context.dp(4f)
        var lo = if (fromZero) 0 else real.minOf { it.second }
        var hi = real.maxOf { it.second }
        if (hi - lo < minSpan) {
            if (fromZero) hi = lo + minSpan
            else { val mid = (hi + lo) / 2; lo = mid - minSpan / 2; hi = lo + minSpan }
        }
        val w = width - 2 * pad
        val h = height - 2 * pad
        fun x(ts: Long) = pad + w * (ts - start).coerceIn(0, end - start) / (end - start).toFloat()
        fun y(v: Int) = pad + h - h * (v - lo) / (hi - lo).toFloat()

        path.reset(); area.reset()
        var open = false
        var runEnd = 0f
        for ((ts, v) in values) {
            if (v == GAP) {
                if (open) { area.lineTo(runEnd, height.toFloat()); area.close(); open = false }
                continue
            }
            if (!open) { path.moveTo(x(ts), y(v)); area.moveTo(x(ts), height.toFloat()); area.lineTo(x(ts), y(v)); open = true }
            else { path.lineTo(x(ts), y(v)); area.lineTo(x(ts), y(v)) }
            runEnd = x(ts)
        }
        if (open) { area.lineTo(runEnd, height.toFloat()); area.close() }
        val lastPt = values.last()
        fill.shader = LinearGradient(0f, pad, 0f, height.toFloat(),
            (color and 0x00FFFFFF) or 0x38000000, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawPath(area, fill)
        line.color = color; line.strokeWidth = context.dp(2f)
        c.drawPath(path, line)
        if (lastPt.second == GAP) return
        val lastX = x(lastPt.first)
        val ly = y(lastPt.second)
        dot.color = p.card; c.drawCircle(lastX, ly, context.dp(4f), dot)
        dot.color = color; c.drawCircle(lastX, ly, context.dp(2.75f), dot)
    }

    private companion object {
        const val GAP = dev.chargelog.core.Drain.BREAK
    }
}
