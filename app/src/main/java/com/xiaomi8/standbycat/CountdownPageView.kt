package com.xiaomi8.standbycat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 倒计时页：中间大字 MM:SS。
 *
 * - 数字质感 / 换字动效与主页面数字钟完全一致（玻璃渐变 + 逐位卷动 + 落地 Q 弹）；
 * - 倒计时结束时，从右往左逐位「散开」：数字化成一串小泡泡向上飘、陆续"啪"地炸开，
 *   每位炸开时通过 [onDigitBurst] 通知 Activity 点一下震动，做到震动与爆炸同步。
 */
class CountdownPageView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var colorA = Prefs.DEFAULT_COLOR_A
        set(v) { field = v; invalidate() }
    var colorB = Prefs.DEFAULT_COLOR_B
        set(v) { field = v; invalidate() }
    var nightMode = false
        set(v) { field = v; invalidate() }

    /** 剩余毫秒；由 Activity 每秒推送 */
    var remainMs = 5 * 60_000L
        set(v) {
            field = v.coerceAtLeast(0L)
            syncText()
        }

    /** 正在倒计时（只影响冒号呼吸节奏） */
    var running = false
        set(v) { field = v; invalidate() }


    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = loadTypeface(ctx)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = Rect()

    private var capRatio = 0.70f

    // 绘制时缓存的排版几何，供「在大数字上滑动」命中判定用
    private var zMinL = 0f
    private var zMinR = 0f
    private var zSecL = 0f
    private var zSecR = 0f
    private var zTop = 0f
    private var zBottom = 0f

    private var gCA = 0
    private var gCB = 0
    private var gTopY = 0f
    private var gHF = 0f
    private var gBaseOff2 = 0f
    // 冒号/渐变画笔的 shader 缓存：几何与配色不变就复用，避免每帧 new 一个多段渐变
    private var shaderCache: android.graphics.LinearGradient? = null
    private var shaderKey = ""


    // 逐位卷动状态
    private val shown = arrayOfNulls<String>(4)
    private val rollFrom = arrayOfNulls<String>(4)
    private val rollStartAt = LongArray(4)
    private val squash = FloatArray(4)
    private val squashV = FloatArray(4)
    private var rollActive = false
    private var rollDir = 1                 // +1 往上滚（数值变大），-1 往下滚（数值变小）
    private var lastText = ""
    private var lastStep = 0L

    init {
        text.textSize = 100f
        text.getTextBounds(DIGITS, 0, DIGITS.length, box)
        val r = (box.bottom - box.top) / 100f
        if (r > 0.05f) capRatio = r
    }

    /** 文字 = MM:SS */
    private fun label(): String {
        val total = (remainMs + 999) / 1000            // 向上取整，最后一秒不会提前显示 00:00
        val m = (total / 60).coerceAtMost(99)
        val s = total % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun syncText() {
        val s = label().replace(":", "")
        if (Lite.on) {
            // 简化模式：直接换字，不卷动
            val n = s.map { it.toString() }
            for (i in 0..3) { if (i < n.size) shown[i] = n[i]; rollFrom[i] = null }
            lastText = s
            rollActive = false
            invalidate()
            return
        }
        // 数字变大 → 整条数字往上走；变小 → 往下走（跟着手指方向）
        rollDir = if (s >= lastText) 1 else -1
        lastText = s
        val next = s.map { it.toString() }
        val now = android.os.SystemClock.elapsedRealtime()
        var changed = false
        for (i in 0..3) {
            if (i >= next.size) break
            val cur = shown[i]
            if (cur == null) { shown[i] = next[i]; continue }
            if (cur != next[i]) {
                changed = true
                rollFrom[i] = cur
                shown[i] = next[i]
                rollStartAt[i] = now
                squashV[i] -= 0.7f
            }
        }
        rollActive = (0..3).any {
            rollFrom[it] != null && android.os.SystemClock.elapsedRealtime() - rollStartAt[it] < ROLL_MS
        }
        // 只有真的换了数字才重绘：否则每 200ms 一次空重绘纯属浪费
        if (changed) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val cA = if (nightMode) BigClockView.NIGHT_TEXT else colorA
        val cB = if (nightMode) BigClockView.NIGHT_TEXT_DIM else colorB

        // ---- 字号：按高度定，再按宽度回缩 ----
        var inkH = height * 0.40f        // 比主页面时钟明显小一档，视觉上区分开
        text.textSize = inkH / capRatio
        var pitch = Digits.pitch(text)
        var dotR = inkH * 0.11f
        var gap = inkH * 0.105f
        var totalW = pitch * 4 + dotR * 2 + gap * 2
        val maxW = width * 0.66f
        if (totalW > maxW) {
            val k = maxW / totalW
            inkH *= k
            text.textSize = inkH / capRatio
            pitch = Digits.pitch(text)
            dotR = inkH * 0.11f
            gap = inkH * 0.105f
            totalW = pitch * 4 + dotR * 2 + gap * 2
        }

        text.getTextBounds(DIGITS, 0, DIGITS.length, box)
        val gTop = box.top.toFloat()
        val gBottom = box.bottom.toFloat()
        val baseline = cy + (gBottom - gTop) / 2f - gBottom
        val inkTop = baseline + gTop
        val inkBottom = baseline + gBottom
        val x0 = cx - totalW / 2f
        val colonW = dotR * 2 + gap * 2

        val key = "$x0|$inkTop|$inkBottom|$cA|$cB"
        var shader = shaderCache
        if (shader == null || shaderKey != key) {
            shader = Digits.glassShader(x0, inkTop, inkBottom, cA, cB)
            shaderCache = shader
            shaderKey = key
        }
        text.shader = shader

        // 缓存几何：分钟两位 / 秒钟两位各自的横向范围，用于滑动命中
        zMinL = x0
        zMinR = x0 + pitch * 2
        zSecL = x0 + pitch * 2 + colonW
        zSecR = zSecL + pitch * 2
        zTop = inkTop
        zBottom = inkBottom
        gCA = cA
        gCB = cB
        gTopY = inkTop
        gHF = inkBottom - inkTop
        gBaseOff2 = baseline - inkTop

        stepSquash()

        val now = android.os.SystemClock.elapsedRealtime()
        val cellX = FloatArray(4)
        cellX[0] = x0
        cellX[1] = x0 + pitch
        cellX[2] = x0 + pitch * 2 + colonW
        cellX[3] = x0 + pitch * 3 + colonW

        // ---- 四位数字 ----
        for (i in 0..3) {
            val ch = shown[i] ?: "0"
            val sx = cellX[i]
            val alpha = 1f
            if (alpha <= 0.01f) continue
            canvas.save()
            // 落地 Q 弹
            val sq = squash[i]
            if (abs(sq) > 0.002f) {
                canvas.translate(0f, -sq * 10f)
                canvas.scale(1f - sq * 0.55f, 1f + sq, sx + pitch / 2f, baseline)
            }
            drawRollingDigit(canvas, i, ch, sx, baseline, pitch, inkTop, inkBottom, alpha, now)
            canvas.restore()
        }

        // ---- 冒号（两圆点，运行中呼吸）----
        val dotX = x0 + pitch * 2 + gap + dotR
        dotPaint.shader = text.shader
        val ph = if (running) ((now % 2000L) / 2000f) else 0f
        dotPaint.alpha = (120 + 110 * abs(0.5f - ph) * 2f).toInt()
        val hh = inkBottom - inkTop
        canvas.drawCircle(dotX, inkTop + hh * 0.26f, dotR, dotPaint)
        canvas.drawCircle(dotX, inkTop + hh * 0.71f, dotR, dotPaint)

        if (rollActive) postInvalidateDelayed(16L)
    }

    /**
     * 触点落在哪个数字区：0 = 分钟两位，1 = 秒钟两位，-1 = 不在数字上。
     */
    fun zoneAt(x: Float, y: Float): Int {
        if (zBottom <= zTop) return -1
        val padY = (zBottom - zTop) * 0.18f
        if (y < zTop - padY || y > zBottom + padY) return -1
        val padX = 20f
        return when {
            x >= zMinL - padX && x <= zMinR + padX -> 0
            x >= zSecL - padX && x <= zSecR + padX -> 1
            else -> -1
        }
    }

    private fun drawRollingDigit(
        canvas: Canvas, i: Int, ch: String, sx: Float, baseline: Float, pitch: Float,
        inkTop: Float, inkBottom: Float, alpha: Float, now: Long
    ) {
        val from = rollFrom[i]
        val elapsed = now - rollStartAt[i]
        if (from == null || elapsed >= ROLL_MS) {
            rollFrom[i] = null
            drawGlass(canvas, ch, sx, baseline, pitch, alpha)
            return
        }
        val t = (elapsed / ROLL_MS.toFloat()).coerceIn(0f, 1f)
        val e = easeRoll(t)
        val h = (inkBottom - inkTop) * 1.02f
        canvas.save()
        // 裁剪范围放大到数字上下各 0.85 个字高：滚动的笔画还没碰到边界就已经淡出，
        // 不会再出现「被格子框住」的横切线。
        canvas.clipRect(sx - pitch * 0.05f, inkTop - h * 0.85f, sx + pitch * 1.05f, inkBottom + h * 0.85f)
        canvas.save()
        canvas.translate(0f, -h * e * rollDir)          // 旧数字沿滚动方向离开
        val fadeOut = (1f - t)
        drawGlass(canvas, from, sx, baseline, pitch, alpha * fadeOut * fadeOut * fadeOut)
        canvas.restore()
        canvas.save()
        canvas.translate(0f, h * (1f - e) * -rollDir)   // 新数字从反方向进来
        // 新数字刚进场时也几乎透明，靠近落位才完全显示 → 边缘不会有硬切线
        val fadeIn = ((e - 0.18f) / 0.55f).coerceIn(0f, 1f)
        drawGlass(canvas, ch, sx, baseline, pitch, alpha * fadeIn)
        canvas.restore()
        canvas.restore()
    }

    private fun drawGlass(canvas: Canvas, ch: String, sx: Float, baseline: Float, pitch: Float, alpha: Float) {
        if (alpha <= 0.01f) return
        GlassDigits.draw(
            canvas, ch, sx, gTopY, pitch, gHF,
            text.typeface, text.textSize, gBaseOff2, gCA, gCB, alpha * 0.94f
        )
    }

    private fun easeRoll(t: Float): Float {
        val c1 = 0.62f
        val c2 = c1 * 1.525f
        val u = t * 2f
        return if (u < 1f) (u * u * ((c2 + 1f) * u - c2)) / 2f
        else {
            val v = u - 2f
            ((v * v * ((c2 + 1f) * v + c2)) + 2f) / 2f
        }
    }

    private fun stepSquash() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastStep == 0L) { lastStep = now; return }
        var dt = (now - lastStep) / 1000f
        lastStep = now
        if (dt <= 0f) return
        if (dt > 0.05f) dt = 0.05f
        for (i in 0..3) {
            squashV[i] += (-80f * squash[i] - 6.5f * squashV[i]) * dt
            squash[i] += squashV[i] * dt
            if (abs(squash[i]) < 0.004f && abs(squashV[i]) < 0.05f) {
                squash[i] = 0f; squashV[i] = 0f
            }
        }
    }

    private companion object {
        private const val DIGITS = "0123456789"
        private const val ROLL_MS = 380L

        private fun loadTypeface(ctx: Context): android.graphics.Typeface = try {
            ctx.resources.getFont(R.font.fredoka_bold)
        } catch (e: Exception) {
            android.graphics.Typeface.DEFAULT_BOLD
        }
    }
}
