package com.xiaomi8.standbycat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.time.LocalDate
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 无边框「放射刻度」表盘：
 *  - 60 根刻度从屏幕边缘向内生长（没有外圈圆环，刻度贴边，像太阳光芒）
 *  - 只有 12 / 3 / 6 / 9 四个细体数字
 *  - 圆头胶囊指针（白色）+ 细秒针 + 白色环形轴心
 *  - 轴心左右一行信息：左＝闹钟图标 + 时间，右＝日期 + 星期（点它进日历）
 *
 * 所有尺寸以「屏幕高度」为基准换算（S = 屏高/1000），换机型不用重调。
 */
class ClockView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var colorA = Prefs.DEFAULT_COLOR_A
        set(v) { field = v; invalidate() }
    var nightMode = false
        set(v) { field = v; invalidate() }

    /** 中心信息行被点击 */
    var onDateClick: (() -> Unit)? = null

    /**
     * 自驱动重绘总开关。由 Activity 控制：只有「本页是当前页」且「没有在滑动/归位动画中」才跑，
     * 滑动期间把帧预算让给翻页动画。
     */
    var animateEnabled = true
        set(v) {
            if (field != v) {
                field = v
                if (v) invalidate()
            }
        }

    // 系统「强制深色模式」会逐条绘制图元分析：大面积浅色会被当成背景压暗
    // （实测某些机型上指针会被压成近黑），而带 shader 的图元不会被判成浅色背景。
    // 这里用「两端同色」的纯色 shader 来画指针等大面积填色，视觉完全一致但不受影响。
    private var flatShaderColor = Int.MIN_VALUE
    private var flatShaderCache: android.graphics.Shader? = null

    private fun flat(c: Int): android.graphics.Shader {
        if (flatShaderCache == null || flatShaderColor != c) {
            flatShaderColor = c
            flatShaderCache = android.graphics.LinearGradient(
                0f, 0f, 0f, 1f, c, c, android.graphics.Shader.TileMode.CLAMP
            )
        }
        return flatShaderCache!!
    }

    /**
     * 用纯色 shader 填色（对抗系统强制深色）。
     * 顺便把 paint.color 也置成黑：若分析器不看 shader 只读 color 字段，读到的也是「深色」，
     * 不会把这块大面积图形判成浅色背景去压暗。
     */
    private fun setFill(p: Paint, c: Int) {
        p.color = Color.BLACK
        p.shader = flat(c)
    }

    /** 恢复常规纯色（文字/细线不需要处理） */
    private fun setSolid(p: Paint, c: Int) {
        p.shader = null
        p.color = c
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeJoin = Paint.Join.ROUND
    }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val infoPaintL = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val handPath = Path()
    private val clickL = RectF()
    private val clickR = RectF()
    private val locTmp = IntArray(2)
    private val dmTmp = android.util.DisplayMetrics()

    private fun main(): Int = if (nightMode) 0xFFB03030.toInt() else Color.parseColor("#F5F5F5")

    /**
     * 时针 / 分针的颜色：**纯白**，且固定不随任何模式切换而改色。
     * 说明：系统「深色模式」的配色转换会保留白色、却会改动彩色与大面积浅色，
     * 纯白是最稳的选择（轴上手机实测：白字不受影响，主题色会被改掉）。
     */
    private fun handColor(): Int = if (nightMode) 0xFFB03030.toInt() else 0xFFFFFFFF.toInt()
    private fun tickColor(): Int = if (nightMode) 0xFF5A1A1A.toInt() else Color.parseColor("#A8A8A8")
    private fun accent(): Int = if (nightMode) 0xFF6B1010.toInt() else colorA

    private fun dim(c: Int, alpha: Int) =
        Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))

    private fun realScreen(): FloatArray {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dmTmp)
        return floatArrayOf(dmTmp.widthPixels.toFloat(), dmTmp.heightPixels.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return

        // 以真实屏幕中心为原点，避免刘海/系统栏造成的偏移
        val scr = realScreen()
        val scrW = scr[0]
        val scrH = scr[1]
        getLocationOnScreen(locTmp)
        val cx = scrW / 2f - locTmp[0]
        val cy = scrH / 2f - locTmp[1]

        val main = main()
        val accent = accent()
        val S = scrH / 1000f                  // 统一比例尺

        // ---------- 1. 放射刻度 ----------
        val insetX = 62f * S
        val insetY = 62f * S
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeWidth = 8.4f * S
        // 必须同时清掉 shader：stroke 是复用画笔，指针那一遍会留下 shader，
        // 只设 color 的话刻度会被指针的颜色盖过去（v3.6 的 bug）。
        setSolid(stroke, tickColor())

        val numR = 0.72f                                    // 数字环 = 椭圆半径 × 0.72
        val numTextSize = 152f * S
        numPaint.textSize = numTextSize
        // 3 点 / 9 点的长刻度：内端让开数字
        val longInner = numR * (scrW / 2f) + numTextSize * 0.30f + 52f * S

        for (i in 0 until 60) {
            val th = Math.toRadians((i * 6).toDouble())
            val ux = sin(th).toFloat()
            val uy = -cos(th).toFloat()
            val rOut = rayExit(cx, cy, ux, uy, width.toFloat(), height.toFloat()) + 2f
            var rIn = rayRect(cx, cy, ux, uy, insetX, insetY, width.toFloat(), height.toFloat())
            if (i == 15 || i == 45) rIn = min(rIn, longInner)
            canvas.drawLine(
                cx + ux * rIn, cy + uy * rIn,
                cx + ux * rOut, cy + uy * rOut, stroke
            )
        }

        // ---------- 2. 四个数字 ----------
        numPaint.color = dim(main, 235)
        val numCx = numR * (scrW / 2f)
        val numCy = numR * (scrH / 2f)
        val base = numTextSize * 0.355f
        canvas.drawText("12", cx, cy - numCy + base, numPaint)
        canvas.drawText("6", cx, cy + numCy + base, numPaint)
        canvas.drawText("3", cx + numCx, cy + base, numPaint)
        canvas.drawText("9", cx - numCx, cy + base, numPaint)

        // ---------- 3. 中心信息行 ----------
        drawCenterInfo(canvas, cx, cy, S, numCx, numTextSize, main, accent)

        // ---------- 4. 指针 ----------
        val cal = Calendar.getInstance()
        val sec = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f
        val minF = cal.get(Calendar.MINUTE) + sec / 60f
        val hrF = (cal.get(Calendar.HOUR) % 12) + minF / 60f
        val B = scrH / 2f                                     // 纵向半轴 = 指针基准

        // 时针 / 分针：纯白（不随深色模式改色）；秒针保留主题色，是这块表盘的辨识点
        drawHand(canvas, cx, cy, hrF / 12f * 360f, B * 0.55f, B * 0.082f, B * 0.060f, handColor())
        drawHand(canvas, cx, cy, minF / 60f * 360f, B * 0.92f, B * 0.072f, B * 0.110f, handColor())
        drawHand(canvas, cx, cy, sec / 60f * 360f, B * 0.90f, B * 0.013f, B * 0.140f, accent)

        // ---------- 5. 轴心 ----------
        setFill(fill, main)
        canvas.drawCircle(cx, cy, B * 0.048f, fill)
        setSolid(fill, Color.BLACK)
        canvas.drawCircle(cx, cy, B * 0.030f, fill)
        setFill(fill, accent)
        canvas.drawCircle(cx, cy, B * 0.021f, fill)

        // 秒针是平滑扫秒：20fps 时针尖每帧只移动 2~3px，已经看不出台阶；
        // 60fps 对这块表盘没有意义，纯耗电。
        scheduleRedraw(50L)
    }

    /** 只在「真正显示在屏幕上」时才排下一帧 */
    private fun scheduleRedraw(delayMs: Long) {
        if (!animateEnabled || !isShown) return
        postInvalidateDelayed(delayMs)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, vis: Int) {
        super.onVisibilityChanged(changedView, vis)
        if (vis == VISIBLE) invalidate()
    }

    override fun onWindowVisibilityChanged(vis: Int) {
        super.onWindowVisibilityChanged(vis)
        if (vis == VISIBLE) invalidate()
    }

    // ---------------- 中心信息行 ----------------

    /**
     * 中心信息行：字号不能只按屏高算——窄屏（4:3 平板）上数字环更靠近中心，
     * 信息行会撞到 9 / 3 两个数字。这里用「数字环内侧的可用半宽」反推上限：
     * 整行总跨度 ≈ gapHalf(2.9×ts) + 左组(图标1.05 + 间隙0.42 + 时间≈2.75) ≈ 7.12×ts
     */
    private fun drawCenterInfo(
        canvas: Canvas, cx: Float, cy: Float, S: Float,
        numCx: Float, numTextSize: Float, main: Int, accent: Int
    ) {
        val d = LocalDate.now()
        val wkNames = listOf("一", "二", "三", "四", "五", "六", "日")
        val now = Calendar.getInstance()
        val hhmm = String.format(
            "%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)
        )
        val day = d.dayOfMonth.toString()
        val wk = "周" + wkNames[d.dayOfWeek.value - 1]

        val avail = numCx - numTextSize * 0.32f - 26f * S   // 避开 9 / 3 数字后剩下的半宽
        val ts = minOf(86f * S, avail / 7.12f).coerceAtLeast(22f * S)
        infoPaint.textSize = ts
        infoPaintL.textSize = ts
        val rowH = ts * 0.85f
        val gapHalf = ts * 2.9f                   // 中间给指针/轴心留出的半宽
        val iconSize = ts * 1.05f
        val iconGap = ts * 0.42f
        val textBase = cy + ts * 0.35f

        // 左：闹钟图标 + 时间（时间右对齐到 cx-gapHalf）
        val timeRight = cx - gapHalf
        val timeLeft = timeRight - infoPaint.measureText(hhmm)
        infoPaint.color = main
        canvas.drawText(hhmm, timeRight, textBase, infoPaint)
        val iconCx = timeLeft - iconGap - iconSize / 2f
        drawAlarmIcon(canvas, iconCx, cy, iconSize, accent)

        // 右：日期（白）+ 星期（主题色），用左对齐画刷
        val rightLeft = cx + gapHalf
        infoPaintL.color = main
        canvas.drawText(day, rightLeft, textBase, infoPaintL)
        val dayW = infoPaintL.measureText(day)
        val wkLeft = rightLeft + dayW + ts * 0.34f
        infoPaintL.color = accent
        canvas.drawText(wk, wkLeft, textBase, infoPaintL)
        val wkRight = wkLeft + infoPaintL.measureText(wk)

        clickL.set(iconCx - iconSize, cy - rowH, timeRight, cy + rowH)
        clickR.set(rightLeft, cy - rowH, wkRight, cy + rowH)
    }

    /**
     * 小闹钟图标：圆头粗线的经典闹钟 —— 表盘 + 两只铃铛 + 两只小脚 + 10:10 指针。
     * 10:10 是钟表图标的经典角度（像在微笑），比正 12:00 更精神、更像成品图标。
     * 全部用圆头短线段画，小尺寸下也不会糊成一团。
     */
    private fun drawAlarmIcon(canvas: Canvas, x: Float, y: Float, size: Float, color: Int) {
        val r = size * 0.33f
        val oy = y + size * 0.035f                    // 视觉重心略微下移
        setSolid(iconPaint, color)
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeCap = Paint.Cap.ROUND
        iconPaint.strokeJoin = Paint.Join.ROUND

        // 表盘
        iconPaint.strokeWidth = size * 0.10f
        canvas.drawCircle(x, oy, r, iconPaint)

        // 两只铃铛（左上 / 右上，45° 方向）
        iconPaint.strokeWidth = size * 0.14f
        val inD = r * 0.58f
        val outD = r * 1.26f
        for (sg in intArrayOf(-1, 1)) {
            canvas.drawLine(
                x + sg * inD * 0.707f, oy - inD * 0.707f,
                x + sg * outD * 0.707f, oy - outD * 0.707f, iconPaint
            )
        }

        // 两只小脚
        iconPaint.strokeWidth = size * 0.11f
        for (sg in intArrayOf(-1, 1)) {
            canvas.drawLine(
                x + sg * r * 0.60f, oy + r * 0.62f,
                x + sg * r * 0.94f, oy + r * 1.00f, iconPaint
            )
        }

        // 指针：10:10
        iconPaint.strokeWidth = size * 0.095f
        val hLen = r * 0.52f
        val mLen = r * 0.68f
        canvas.drawLine(x, oy, x - hLen * 0.866f, oy - hLen * 0.5f, iconPaint)   // 指向 10 点
        canvas.drawLine(x, oy, x + mLen * 0.866f, oy - mLen * 0.5f, iconPaint)   // 指向 2 点

        // 轴心小点
        iconPaint.style = Paint.Style.FILL
        canvas.drawCircle(x, oy, size * 0.055f, iconPaint)
        iconPaint.style = Paint.Style.STROKE          // 复位，避免影响后续绘制
    }

    // ---------------- 指针（锥形圆头胶囊） ----------------

    private fun drawHand(
        c: Canvas, cx: Float, cy: Float, deg: Float,
        len: Float, width: Float, tail: Float, color: Int
    ) {
        val ang = Math.toRadians((deg - 90).toDouble())
        val ux = cos(ang).toFloat()
        val uy = sin(ang).toFloat()
        val nx = -uy
        val ny = ux
        val w0 = width * 0.5f            // 根部半宽
        val w1 = width * 0.34f           // 尖端半宽
        // 主杆：根部宽、尖端略收的四边形
        handPath.reset()
        handPath.moveTo(cx + nx * w0, cy + ny * w0)
        handPath.lineTo(cx + ux * (len - width * 0.5f) + nx * w1, cy + uy * (len - width * 0.5f) + ny * w1)
        handPath.lineTo(cx + ux * (len - width * 0.5f) - nx * w1, cy + uy * (len - width * 0.5f) - ny * w1)
        handPath.lineTo(cx - nx * w0, cy - ny * w0)
        handPath.close()
        setFill(fill, color)
        c.drawPath(handPath, fill)
        // 尖端圆头
        c.drawCircle(cx + ux * (len - width * 0.5f), cy + uy * (len - width * 0.5f), width * 0.34f, fill)
        // 根部圆头
        c.drawCircle(cx, cy, width * 0.5f, fill)
        // 尾部配重
        if (tail > 0f) {
            setFill(stroke, color)
            stroke.strokeCap = Paint.Cap.ROUND
            stroke.strokeWidth = width * 0.72f
            c.drawLine(cx, cy, cx - ux * tail, cy - uy * tail, stroke)
            c.drawCircle(cx - ux * tail, cy - uy * tail, width * 0.36f, fill)
        }
    }

    // ---------------- 射线与屏幕 / 内缩矩形的交点 ----------------

    private fun rayExit(cx: Float, cy: Float, ux: Float, uy: Float, w: Float, h: Float): Float {
        var t = Float.MAX_VALUE
        if (ux > 1e-6f) t = min(t, (w - cx) / ux)
        if (ux < -1e-6f) t = min(t, -cx / ux)
        if (uy > 1e-6f) t = min(t, (h - cy) / uy)
        if (uy < -1e-6f) t = min(t, -cy / uy)
        return t
    }

    private fun rayRect(
        cx: Float, cy: Float, ux: Float, uy: Float,
        inX: Float, inY: Float, w: Float, h: Float
    ): Float {
        var t = Float.MAX_VALUE
        if (ux > 1e-6f) t = min(t, ((w - inX) - cx) / ux)
        if (ux < -1e-6f) t = min(t, (inX - cx) / ux)
        if (uy > 1e-6f) t = min(t, ((h - inY) - cy) / uy)
        if (uy < -1e-6f) t = min(t, (inY - cy) / uy)
        return t
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                return clickL.contains(e.x, e.y) || clickR.contains(e.x, e.y)
            MotionEvent.ACTION_UP -> {
                if (clickL.contains(e.x, e.y) || clickR.contains(e.x, e.y)) {
                    onDateClick?.invoke()
                    return true
                }
            }
        }
        return false
    }
}
