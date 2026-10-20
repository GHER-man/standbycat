package com.xiaomi8.standbycat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * 计时器页（从主页面向上滑进入）：
 *  - 正中超大字显示 时:分:秒，字体 / 渐变 / 冒号圆点与主页面数字钟完全一致，
 *    字号同样按「墨迹盒」自适应，能塞多大就多大；
 *  - 毫秒由右上角的秒数面板负责（内容切到毫秒），保持与第一页同一块面板。
 */
class TimerPageView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var colorA = Prefs.DEFAULT_COLOR_A
        set(v) { field = v; invalidate() }
    var colorB = Prefs.DEFAULT_COLOR_B
        set(v) { field = v; invalidate() }
    var nightMode = false
        set(v) { field = v; invalidate() }

    /** 形如 "00:12:34"；只在内容变化时才重绘（秒级） */
    var timeText = "00:00:00"
        set(v) { if (field != v) { field = v; invalidate() } }

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = loadTypeface(ctx)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val digitBox = Rect()

    // ---- 与主页面数字钟一致：逐位卷动换字 + 落地 Q 弹 ----
    private val shown = arrayOfNulls<String>(6)
    private val rollFrom = arrayOfNulls<String>(6)
    private val rollStartAt = LongArray(6)
    private val squash = Array(6) { FloatArray(2) }
    private var rollActive = false

    /** 数字墨迹高度 / textSize */
    private var capRatio = 0.70f

    init {
        text.textSize = 100f
        text.getTextBounds(DIGITS, 0, DIGITS.length, digitBox)
        val r = (digitBox.bottom - digitBox.top) / 100f
        if (r > 0.05f) capRatio = r
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return

        // 用「视图自身中心」而不是屏幕坐标：
        // 本页是静态页（没有自驱动重绘），滑动过程中若用屏幕坐标定位，
        // 画在滑动中途的那一帧会按当时的屏幕偏移渲染，滑完又不再重绘，
        // 内容就会整体偏到屏幕外。用视图中心则与页面位置无关，永远正确。
        val cx = width / 2f
        val cy = height / 2f

        val cA = if (nightMode) BigClockView.NIGHT_TEXT else colorA
        val cB = if (nightMode) BigClockView.NIGHT_TEXT_DIM else colorB

        val parts = timeText.split(":")
        if (parts.size != 3) return
        val hh = parts[0]
        val mm = parts[1]
        val ss = parts[2]

        // ---- 先按高度定字号，再按宽度回缩（与 BigClockView 同一套算法）----
        // 等宽数字：每格宽 = 0~9 里最宽的数字，三组各占两格。
        // 这样 11:11:11 与 88:88:88 占宽完全一致，计时跳字时整块不会忽宽忽窄。
        var inkH = height * TARGET_INK
        text.textSize = inkH / capRatio
        var pitch = Digits.pitch(text)
        var dotR = inkH * DOT_R
        var gap = inkH * GAP
        var totalW = pitch * 6 + (dotR * 2 + gap * 2) * 2

        val maxW = width * MAX_W
        if (totalW > maxW) {
            val s = maxW / totalW
            inkH *= s
            text.textSize = inkH / capRatio
            pitch = Digits.pitch(text)
            dotR = inkH * DOT_R
            gap = inkH * GAP
            totalW = pitch * 6 + (dotR * 2 + gap * 2) * 2
        }

        val colonW = dotR * 2 + gap * 2
        val x0 = cx - totalW / 2f

        // 垂直居中：以全数字墨迹盒为准
        text.getTextBounds(DIGITS, 0, DIGITS.length, digitBox)
        val gTop = digitBox.top.toFloat()
        val gBottom = digitBox.bottom.toFloat()
        val baseline = cy + (gBottom - gTop) / 2f - gBottom
        val inkTop = baseline + gTop
        val inkBottom = baseline + gBottom

        // 半透明立体玻璃：与主页面同一套平滑渐变（无可见分界线）
        val key = "$x0|$inkTop|$inkBottom|$cA|$cB"
        var shader = shaderCache
        if (shader == null || shaderKey != key) {
            shader = Digits.glassShader(x0, inkTop, inkBottom, cA, cB)
            shaderCache = shader
            shaderKey = key
        }
        text.shader = shader
        dotPaint.shader = shader

        // 换字检测（6 位）
        gCurCA = cA
        gCurCB = cB
        gInkTopF = inkTop
        gInkHF = inkBottom - inkTop
        gBaseOff = baseline - inkTop
        val six = (hh.takeLast(2).padStart(2, '0') + mm.takeLast(2).padStart(2, '0') +
                ss.takeLast(2).padStart(2, '0')).map { it.toString() }
        updateRoll(six)
        stepSquash()

        // ---- 画 时 : 分 : 秒（数字固定格宽，格内居中）----
        var x = x0
        for (i in 0..1) drawCell(canvas, i, x + i * pitch, baseline, pitch, inkTop, inkBottom)
        x += pitch * 2
        drawColon(canvas, x + gap + dotR, baseline + gTop, gBottom - gTop, dotR)
        x += colonW
        for (i in 0..1) drawCell(canvas, 2 + i, x + i * pitch, baseline, pitch, inkTop, inkBottom)
        x += pitch * 2
        drawColon(canvas, x + gap + dotR, baseline + gTop, gBottom - gTop, dotR)
        x += colonW
        for (i in 0..1) drawCell(canvas, 4 + i, x + i * pitch, baseline, pitch, inkTop, inkBottom)

        // 卷动期间自驱动重绘（本页平时是静态的，不主动刷帧）
        if (rollActive) postInvalidateDelayed(16L)
    }

    /** 页面滑入时补一次重绘（本页没有自驱动动画，只在内容变化时重绘，零空转） */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, vis: Int) {
        super.onVisibilityChanged(changedView, vis)
        if (vis == VISIBLE) invalidate()
    }

    /** 冒号：两个圆点（与主时钟一致） */
    private fun mixColor(a: Int, b: Int, t: Float): Int {
        val u = 1f - t
        return Color.argb(
            (Color.alpha(a) * u + Color.alpha(b) * t).toInt(),
            (Color.red(a) * u + Color.red(b) * t).toInt(),
            (Color.green(a) * u + Color.green(b) * t).toInt(),
            (Color.blue(a) * u + Color.blue(b) * t).toInt()
        )
    }

    /** 与主页面数字钟相同的非线性卷动曲线（先蓄力、后过冲回弹） */
    private fun easeRoll(t: Float): Float {
        val c1 = 0.62f
        val c2 = c1 * 1.525f
        val u = t * 2f
        return if (u < 1f) {
            (u * u * ((c2 + 1f) * u - c2)) / 2f
        } else {
            val v = u - 2f
            ((v * v * ((c2 + 1f) * v + c2)) + 2f) / 2f
        }
    }

    private fun updateRoll(next: List<String>) {
        if (Lite.on) {
            for (i in 0..5) { if (i < next.size) shown[i] = next[i]; rollFrom[i] = null }
            rollActive = false
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        for (i in 0..5) {
            if (i >= next.size) break
            val cur = shown[i]
            if (cur == null) {
                shown[i] = next[i]
                continue
            }
            if (cur != next[i]) {
                rollFrom[i] = cur
                shown[i] = next[i]
                rollStartAt[i] = now
                squash[i][1] -= 0.9f        // 落地压一下
            }
        }
        rollActive = (0..5).any {
            rollFrom[it] != null &&
                android.os.SystemClock.elapsedRealtime() - rollStartAt[it] < ROLL_MS
        }
    }

    /** 果冻弹簧积分（只用于换字落地的那一下） */
    private fun stepSquash() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastStepAt == 0L) { lastStepAt = now; return }
        var dt = (now - lastStepAt) / 1000f
        lastStepAt = now
        if (dt <= 0f) return
        if (dt > 0.05f) dt = 0.05f
        var any = false
        for (i in 0..5) {
            val q = squash[i]
            q[1] += (-80f * q[0] - 6.5f * q[1]) * dt
            q[0] += q[1] * dt
            if (kotlin.math.abs(q[0]) > 0.004f || kotlin.math.abs(q[1]) > 0.05f) {
                any = true
            } else {
                q[0] = 0f; q[1] = 0f
            }
        }
        if (any) rollActive = true
    }

    private var lastStepAt = 0L

    private var gCurCA = 0
    private var gCurCB = 0
    private var gInkTopF = 0f
    private var gInkHF = 0f
    private var gBaseOff = 0f
    // 冒号/渐变画笔的 shader 缓存：几何与配色不变就复用，避免每帧 new 一个多段渐变
    private var shaderCache: android.graphics.LinearGradient? = null
    private var shaderKey = ""


    private fun drawGlassDigit(
        canvas: Canvas, ch: String, cellX: Float, baseline: Float, pitch: Float, alpha: Float
    ) {
        GlassDigits.draw(
            canvas, ch, cellX, gInkTopF, pitch, gInkHF,
            text.typeface, text.textSize, gBaseOff, gCurCA, gCurCB, alpha * GLASS_ALPHA
        )
    }

    private fun drawCell(
        canvas: Canvas, i: Int, cellX: Float, baseline: Float, pitch: Float,
        inkTop: Float, inkBottom: Float
    ) {
        val cur = shown[i] ?: return
        val from = rollFrom[i]
        val elapsed = android.os.SystemClock.elapsedRealtime() - rollStartAt[i]
        val sq = squash[i][0]
        val h = (inkBottom - inkTop) * 1.02f
        canvas.save()
        // 落地 Q 弹：以基线为支点非等比缩放
        if (sq > 0.002f || sq < -0.002f) {
            canvas.translate(0f, -sq * 10f)
            canvas.scale(1f - sq * 0.55f, 1f + sq, cellX + pitch / 2f, baseline)
        }
        if (from == null || elapsed >= ROLL_MS) {
            rollFrom[i] = null
            drawGlassDigit(canvas, cur, cellX, baseline, pitch, 1f)
            canvas.restore()
            return
        }
        val t = (elapsed / ROLL_MS.toFloat()).coerceIn(0f, 1f)
        val e = easeRoll(t)
        canvas.save()
        canvas.clipRect(
            cellX - pitch * 0.05f, inkTop - h * 0.85f,
            cellX + pitch * 1.05f, inkBottom + h * 0.85f
        )
        canvas.save()
        canvas.translate(0f, -h * e)
        val fadeOut = 1f - t
        drawGlassDigit(canvas, from, cellX, baseline, pitch, fadeOut * fadeOut * fadeOut)
        canvas.restore()
        canvas.save()
        canvas.translate(0f, h * (1f - e))
        // 新数字刚进场时几乎透明，靠落位才完全显示 —— 与淡出对称，边缘不会有硬切线
        val fadeIn = ((e - 0.18f) / 0.55f).coerceIn(0f, 1f)
        drawGlassDigit(canvas, cur, cellX, baseline, pitch, fadeIn)
        canvas.restore()
        canvas.restore()
        canvas.restore()
    }

    private fun drawColon(canvas: Canvas, dotX: Float, top: Float, h: Float, dotR: Float) {
        dotPaint.alpha = 255
        canvas.drawCircle(dotX, top + h * 0.26f, dotR, dotPaint)
        canvas.drawCircle(dotX, top + h * 0.71f, dotR, dotPaint)
    }


    companion object {
        private const val DIGITS = "0123456789"
        /** 墨迹高度上限（屏高比例） */
        private const val TARGET_INK = 0.55f
        /** 宽度上限（屏高比例之外再用屏宽兜一层） */
        private const val MAX_W = 0.90f
        private const val DOT_R = 0.110f
        private const val GAP = 0.105f
        /** 计时器每秒都跳字，卷动不能太长，否则一直在动 */
        private const val ROLL_MS = 380L
        private const val GLASS_ALPHA = 0.90f

        private fun loadTypeface(ctx: Context): Typeface = try {
            ctx.resources.getFont(R.font.fredoka_bold)
        } catch (e: Exception) {
            Typeface.create("sans-serif-black", Typeface.BOLD)
        }
    }
}
