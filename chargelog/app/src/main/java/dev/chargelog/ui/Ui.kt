package dev.chargelog.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Colors for the current light/dark mode. Text always uses the ink tokens. */
class Palette(context: Context) {
    val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val bg = if (dark) 0xFF101211.toInt() else 0xFFF4F5F4.toInt()
    val card = if (dark) 0xFF1B1E1C.toInt() else 0xFFFFFFFF.toInt()
    val ink = if (dark) 0xFFECEEEC.toInt() else 0xFF1A1C1B.toInt()
    val ink2 = if (dark) 0xFFA4ABA7.toInt() else 0xFF5F6562.toInt()
    val muted = if (dark) 0xFF7C837F.toInt() else 0xFF8A908C.toInt()
    val grid = if (dark) 0xFF2C302E.toInt() else 0xFFE3E6E4.toInt()
    val accent = if (dark) 0xFF2FCF85.toInt() else 0xFF12B76A.toInt()
    val accentDim = if (dark) 0xFF1E6B49.toInt() else 0xFF9FE3C3.toInt()
    val accentWash = if (dark) 0x332FCF85 else 0x2612B76A
    val danger = if (dark) 0xFFF0806F.toInt() else 0xFFC0392B.toInt()
}

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
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

fun Context.roundRect(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp)
}

fun Context.column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.card(p: Palette): LinearLayout = column().apply {
    background = roundRect(p.card, 22f)
    val pad = dpi(18f)
    setPadding(pad, pad, pad, pad)
}

fun lp(w: Int, h: Int, weight: Float = 0f, top: Int = 0, left: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(w, h, weight).apply { topMargin = top; leftMargin = left }

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** "label ........ value" line used inside cards. */
fun Context.kv(p: Palette, key: String, value: String): LinearLayout = row().apply {
    addView(label(key, 14f, p.ink2), lp(0, WRAP, 1f))
    addView(label(value, 15f, p.ink, bold = true))
    setPadding(0, dpi(5f), 0, dpi(5f))
}

/** Small stat tile: muted caption above a large number. */
fun Context.tile(p: Palette, caption: String, value: String): LinearLayout = column().apply {
    addView(label(caption, 12f, p.ink2))
    addView(label(value, 20f, p.ink, bold = true))
}

/** Horizontal SOC meter. */
class LevelBar(context: Context, private val p: Palette) : View(context) {
    var level = 0
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), context.dpi(14f))
    }

    override fun onDraw(c: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = p.accentDim
        c.drawRoundRect(rect, r, r, paint)
        val w = width * level.coerceIn(0, 100) / 100f
        if (w > 0f) {
            c.save()
            c.clipRect(0f, 0f, w, height.toFloat())
            paint.color = p.accent
            c.drawRoundRect(rect, r, r, paint)
            c.restore()
        }
    }
}
