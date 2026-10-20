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
import java.util.Calendar
import kotlin.math.min

/**
 * 黑底超大数字时钟：HH:MM，圆润艺术字体（Fredoka Bold），渐变色，
 * 冒号画成两个圆点并随秒闪烁。
 *
 * 字号完全按「数字墨迹实际高度」换算，换字体后视觉大小不变，无需手调系数。
 */
class BigClockView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var colorA = Prefs.DEFAULT_COLOR_A
        set(v) { field = v; invalidate() }
    var colorB = Prefs.DEFAULT_COLOR_B
        set(v) { field = v; invalidate() }

    var nightMode = false
        set(v) { field = v; invalidate() }

    /** 是否显示冒号（外部可用于整体隐藏） */
    var showColon = true

    /**
     * 自驱动重绘的总开关。由 Activity 控制：
     * 只有「本页是当前页」且「没有在滑动/归位动画中」才允许跑，
     * 滑动期间把帧预算全部让给翻页动画，避免互相抢帧造成卡顿。
     */
    var animateEnabled = true
        set(v) {
            if (field != v) {
                field = v
                if (v) invalidate()      // 重新打开时点火，否则停了就再也起不来
            }
        }

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = loadTypeface(ctx)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // ---------------- 换数字：从下往上卷动（带 Q 弹）----------------
    private val shownDigit = arrayOfNulls<String>(4)     // 当前显示的数字
    private val rollFrom = arrayOfNulls<String>(4)       // 正在被拉走的旧数字
    private val rollStartAt = LongArray(4)
    private var rollActive = false
    private val bounds = Rect()
    private val locTmp = IntArray(2)
    private val dmTmp = android.util.DisplayMetrics()

    /** 数字墨迹高度 / textSize，用于把「想要的高度」换算成字号（与具体字体无关） */
    private var capRatio = 0.70f

    // ---------------- 防烧屏：整块数字缓慢轻微位移 ----------------
    // OLED 长时间显示同一画面会烧屏。这里每过几分钟把整块数字挪一点点，
    // 位移在 2.5 秒内用 smoothstep 平滑过渡，肉眼几乎察觉不到跳动。
    // ---------------- 摇一摇：数字像泡在浓液体里那样缓慢回摆 ----------------
    // 时组、分组各挂一根「重阻尼弹簧」：低频、粘滞、不弹跳。
    // 两组给不同比例的冲量 → 会相对错开摆动，更像两块漂浮物。
    // 时、分共四位数字，每一位各一根独立弹簧：互不牵连，像四块各自漂浮的东西。
    // 越靠右惯性越小（甩得越多），固有频率也略有差异 → 会慢慢错开相位，不是整块平移。
    private val sway = Array(4) { FloatArray(4) }          // 每位 [x, y, vx, vy]
    private val swayGain = floatArrayOf(0.78f, 0.92f, 1.08f, 1.24f)
    private val swayK = floatArrayOf(6.4f, 7.1f, 6.7f, 7.4f)

    // 单击某一位时的「果冻弹一下」：每位一个压扁/拉长的弹簧 [s, sv]
    // s>0 拉长（变窄）、s<0 压扁（变宽），频率高、阻尼小 → 会 Q 弹两三下
    private val squash = Array(4) { FloatArray(2) }
    private var jellyActive = false

    // onDraw 里缓存下来的排版几何，供点击命中判定使用
    private var gx0 = 0f
    private var gmmX = 0f
    private var gpitch = 0f
    private var gbaseline = 0f
    private var ginkH = 0f

    // 液态玻璃渲染需要的几何/配色（onDraw 里缓存）
    private var gCurCA = 0
    private var gCurCB = 0
    private var gInkTop = 0f
    private var gInkH = 0f
    private var gBaseOffset = 0f
    // 冒号/渐变画笔的 shader 缓存：几何与配色不变就复用，避免每帧 new 一个多段渐变
    private var shaderCache: android.graphics.LinearGradient? = null
    private var shaderKey = ""

    private var swayActive = false
    private var lastSwayAt = 0L

    /** 外部（加速度计）检测到晃动时调用，单位近似 px/s 的冲量 */
    fun nudge(vx: Float, vy: Float) {
        for (i in 0..3) {
            sway[i][2] += vx * swayGain[i]
            sway[i][3] += vy * swayGain[i]
        }
        swayActive = true
        invalidate()
    }

    /**
     * 点击命中判定：返回被点到的数字序号 0..3（时两位 0/1、分两位 2/3），没点到返回 -1。
     * 用 onDraw 缓存的几何算，和实际绘制位置一致。
     */
    fun digitAt(x: Float, y: Float): Int {
        if (gpitch <= 0f || ginkH <= 0f) return -1
        val top = gbaseline - ginkH * 1.12f
        val bottom = gbaseline + ginkH * 0.18f
        if (y < top || y > bottom) return -1
        for (i in 0..3) {
            val cellX = if (i < 2) gx0 + i * gpitch else gmmX + (i - 2) * gpitch
            if (x >= cellX && x <= cellX + gpitch) return i
        }
        return -1
    }

    /** 单击某一位：果冻弹一下（先压扁再回弹），顺带被"弹"得轻微位移 */
    fun tapDigit(i: Int) {
        if (i !in 0..3) return
        squash[i][1] -= 1.15f                // 负向冲量：先压扁（峰值约 20%）
        sway[i][2] += 7f
        sway[i][3] += 5f
        jellyActive = true
        swayActive = true
        invalidate()
    }

    /** 半隐式欧拉积分；dt 上限 50ms，掉帧也不会炸 */
    /** 颜色插值（给玻璃渐变调高光/暗部用） */
    private fun mixColor(a: Int, b: Int, t: Float): Int {
        val u = 1f - t
        return Color.argb(
            (Color.alpha(a) * u + Color.alpha(b) * t).toInt(),
            (Color.red(a) * u + Color.red(b) * t).toInt(),
            (Color.green(a) * u + Color.green(b) * t).toInt(),
            (Color.blue(a) * u + Color.blue(b) * t).toInt()
        )
    }

    /**
     * 卷动用的非线性曲线：先「蓄力」慢起、中段加速、末尾过冲回弹。
     * 标准 easeInOutBack —— 比匀速明显更有「被拉出来」的质感。
     */
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

    /** 换数字检测：哪一位变了，就给那一位开一段卷动 */
    private fun updateRoll(hh: String, mm: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val next = arrayOf(
            hh.substring(0, 1), hh.substring(1, 2),
            mm.substring(0, 1), mm.substring(1, 2)
        )
        if (Lite.on) {
            // 简化模式：直接换字，不卷动
            for (i in 0..3) shownDigit[i] = next[i]
            for (i in 0..3) rollFrom[i] = null
            rollActive = false
            return
        }
        for (i in 0..3) {
            val cur = shownDigit[i]
            if (cur == null) {
                shownDigit[i] = next[i]
                continue
            }
            if (cur != next[i]) {
                rollFrom[i] = cur
                shownDigit[i] = next[i]
                rollStartAt[i] = now
                squash[i][1] -= 0.5f          // 落地瞬间压一下 → 更 Q
                jellyActive = true
                // 连带把相邻数字横向「撞」一下：越近越明显，形成层次感
                for (j in 0..3) {
                    if (j == i) continue
                    val dist = kotlin.math.abs(j - i)
                    val amp = 130f * when (dist) {
                        1 -> 1f
                        2 -> 0.5f
                        else -> 0.22f
                    }
                    sway[j][2] += if (j < i) -amp else amp
                    sway[j][3] += amp * 0.12f
                }
                swayActive = true
            }
        }
        rollActive = (0..3).any {
            rollFrom[it] != null && android.os.SystemClock.elapsedRealtime() - rollStartAt[it] < ROLL_MS
        }
    }

    /** 画一位数字（玻璃质感：半透明填充 + 细亮边） */
    private fun drawGlassDigit(
        canvas: Canvas, ch: String, cellX: Float, baseline: Float, pitch: Float, alpha: Float
    ) {
        // 液态玻璃：走位图缓存，每帧只 drawBitmap
        GlassDigits.draw(
            canvas, ch, cellX, gInkTop, pitch, gInkH,
            text.typeface, text.textSize, gBaseOffset, gCurCA, gCurCB, alpha * GLASS_ALPHA
        )
    }

    /** 画一位数字的「卷动状态」：旧字上移淡出、新字从下方拉出 */
    private fun drawCell(
        canvas: Canvas, i: Int, cellX: Float, baseline: Float, pitch: Float,
        inkTop: Float, inkBottom: Float
    ) {
        val cur = shownDigit[i]
        if (cur == null) return
        val from = rollFrom[i]
        val elapsed = android.os.SystemClock.elapsedRealtime() - rollStartAt[i]
        if (from == null || elapsed >= ROLL_MS) {
            rollFrom[i] = null
            drawGlassDigit(canvas, cur, cellX, baseline, pitch, 1f)
            return
        }
        val t = (elapsed / ROLL_MS.toFloat()).coerceIn(0f, 1f)
        val e = easeRoll(t)
        val h = (inkBottom - inkTop) * 1.02f
        // 只在这一格的高度范围内翻滚，避免压到右上秒数/右下计时器
        canvas.save()
        canvas.clipRect(
            cellX - pitch * 0.05f, inkTop - h * 0.85f,
            cellX + pitch * 1.05f, inkBottom + h * 0.85f
        )
        canvas.save()
        canvas.translate(0f, -h * e)                       // 旧字向上被拉走
        val fadeOut = 1f - t
        drawGlassDigit(canvas, from, cellX, baseline, pitch, fadeOut * fadeOut * fadeOut)
        canvas.restore()
        canvas.save()
        canvas.translate(0f, h * (1f - e))                 // 新字从下方拉出（e 过冲时略高一点）
        // 新数字刚进场时几乎透明，靠落位才完全显示 —— 与淡出对称，边缘不会有硬切线
        val fadeIn = ((e - 0.18f) / 0.55f).coerceIn(0f, 1f)
        drawGlassDigit(canvas, cur, cellX, baseline, pitch, fadeIn)
        canvas.restore()
        canvas.restore()
    }

    /** 以基线为支点做非等比缩放：压扁时变宽、拉长时变窄，并带一点点上弹 */
    private fun applyJelly(canvas: Canvas, i: Int, cx: Float, base: Float) {
        val sq = squash[i][0]
        if (sq > -0.002f && sq < 0.002f) return
        canvas.translate(0f, -sq * 10f)
        canvas.scale(1f - sq * 0.55f, 1f + sq, cx, base)
    }

    private fun stepSway() {
        if (Lite.on) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastSwayAt == 0L) { lastSwayAt = now; return }
        var dt = (now - lastSwayAt) / 1000f
        lastSwayAt = now
        if (dt <= 0f) return
        if (dt > 0.05f) dt = 0.05f
        var amp = 0f
        var vel = 0f
        for (i in 0..3) {
            val v = sway[i]
            val k = swayK[i]
            v[2] += (-k * v[0] - SWAY_C * v[2]) * dt
            v[3] += (-k * v[1] - SWAY_C * v[3]) * dt
            v[0] += v[2] * dt
            v[1] += v[3] * dt
            amp += kotlin.math.abs(v[0]) + kotlin.math.abs(v[1])
            vel += kotlin.math.abs(v[2]) + kotlin.math.abs(v[3])
        }
        swayActive = amp > 0.7f || vel > 4f
        if (!swayActive) sway.forEach { it.fill(0f) }

        // 果冻弹簧
        var jAmp = 0f
        var jVel = 0f
        for (i in 0..3) {
            val q = squash[i]
            q[1] += (-JELLY_K * q[0] - JELLY_C * q[1]) * dt
            q[0] += q[1] * dt
            jAmp += kotlin.math.abs(q[0])
            jVel += kotlin.math.abs(q[1])
        }
        jellyActive = jAmp > 0.004f || jVel > 0.05f
        if (!jellyActive) squash.forEach { it.fill(0f) }
    }

    private var driftFromX = 0f
    private var driftFromY = 0f
    private var driftToX = 0f
    private var driftToY = 0f
    private var driftStartAt = 0L

    /** 设定新的位移目标（相对默认居中位置，单位 px） */
    fun driftTo(dx: Float, dy: Float) {
        driftFromX = driftX()
        driftFromY = driftY()
        driftToX = dx
        driftToY = dy
        driftStartAt = android.os.SystemClock.elapsedRealtime()
        invalidate()
    }

    /** 0→1 的过渡进度（smoothstep，避免突然跳一下） */
    private fun driftProgress(): Float {
        if (driftStartAt == 0L) return 1f
        val t = ((android.os.SystemClock.elapsedRealtime() - driftStartAt).toFloat() / DRIFT_MS)
            .coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun driftActive(): Boolean = driftProgress() < 1f

    private fun driftX(): Float = driftFromX + (driftToX - driftFromX) * driftProgress()
    private fun driftY(): Float = driftFromY + (driftToY - driftFromY) * driftProgress()

    init {
        text.textSize = 100f
        text.getTextBounds(DIGITS, 0, DIGITS.length, bounds)
        val r = (bounds.bottom - bounds.top) / 100f
        if (r > 0.05f) capRatio = r
    }

    /** 真实屏幕高度（含状态栏/导航栏区域，且随屏幕旋转更新） */
    private fun realScreenHeight(): Float {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dmTmp)
        return dmTmp.heightPixels.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return

        val cal = Calendar.getInstance()
        val hh = String.format("%02d", cal.get(Calendar.HOUR_OF_DAY))
        val mm = String.format("%02d", cal.get(Calendar.MINUTE))

        val cA = if (nightMode) NIGHT_TEXT else colorA
        val cB = if (nightMode) NIGHT_TEXT_DIM else colorB

        // ---- 先按高度定字号，再按宽度回缩 ----
        var inkH = height * TARGET_INK
        text.textSize = inkH / capRatio
        // 等宽数字：每个数字固定占一格（0~9 里最宽的宽度），
        // 这样 HH 永远是两格宽，换数字（如 23:08 → 11:11）整块不会忽宽忽窄。
        var pitch = Digits.pitch(text)
        var dotR = inkH * DOT_R
        var gap = inkH * GAP
        val totalW = pitch * 4 + dotR * 2 + gap * 2

        // 宽度上限 0.86：给右上角秒数、右下角计时器让出位置（0.94 时会压在小部件上）
        val maxW = width * 0.86f
        if (totalW > maxW) {
            val s = maxW / totalW
            inkH *= s
            text.textSize = inkH / capRatio
            pitch = Digits.pitch(text)
            dotR = inkH * DOT_R
            gap = inkH * GAP
        }

        // 数字按格排布，整串的绘制盒就是 [x0, x0 + 总宽]，直接按它居中
        // 防烧屏位移（相对居中位置的偏移）
        val offsetX = driftX()
        val offsetY = driftY()
        val x0 = (width - (pitch * 4 + dotR * 2 + gap * 2)) / 2f + offsetX

        // 用字形实际墨水盒（全部数字的并集，保证换数字时基线不抖）算 baseline
        text.getTextBounds(DIGITS, 0, DIGITS.length, bounds)
        val glyphTop = bounds.top.toFloat()
        val glyphBottom = bounds.bottom.toFloat()
        // 以「真实屏幕中心」为基准换算到 view 内坐标。
        // 不能用 resources.displayMetrics.heightPixels（不含状态栏/导航栏区域），
        // 也不能用 getLocationInWindow（不含系统栏偏移），否则横竖屏都会偏移几十像素。
        getLocationOnScreen(locTmp)
        val screenH = realScreenHeight()
        val centerY = screenH / 2f - locTmp[1]
        val baseline = centerY + (glyphBottom - glyphTop) / 2f - glyphBottom + offsetY

        // 缓存排版几何，供点击命中判定使用
        gpitch = pitch
        gx0 = x0
        gmmX = x0 + pitch * 2 + dotR * 2 + gap * 2
        gbaseline = baseline
        ginkH = inkH
        gCurCA = cA
        gCurCB = cB

        val inkTop = baseline + glyphTop
        val inkBottom = baseline + glyphBottom
        gInkTop = inkTop
        gInkH = inkBottom - inkTop
        gBaseOffset = baseline - inkTop

        // ---- 半透明立体玻璃：平滑多段竖直渐变（见 Digits.glassShader）----
        // shader 缓存：只有位置/配色变了才重建（原来每帧都 new 一个 16 段渐变）
        val key = "$x0|$inkTop|$inkBottom|$cA|$cB"
        var shader = shaderCache
        if (shader == null || shaderKey != key) {
            shader = Digits.glassShader(x0, inkTop, inkBottom, cA, cB)
            shaderCache = shader
            shaderKey = key
        }
        text.shader = shader
        dotPaint.shader = shader

        // 说明：原先想用「细描边」做玻璃边缘高光，但 Fredoka 的字形轮廓有重叠，
        // 描边会在字形内部留下穿线，所以改成纯渐变（顶部高光 + 底部收暗）营造体积感。

        stepSway()
        updateRoll(hh, mm)

        // ---- 绘制 HH：两位各自独立摆动（等宽：每个数字占一格，格内居中）----
        for (i in 0..1) {
            val v = sway[i]
            val cellX = x0 + i * pitch
            canvas.save()
            canvas.translate(v[0], v[1])
            // 极小的倾斜，晃动时更像“悬浮的液体数字”
            canvas.rotate((v[0] * 0.05f).coerceIn(-1.6f, 1.6f), cellX + pitch / 2f, baseline)
            applyJelly(canvas, i, cellX + pitch / 2f, baseline)
            drawCell(canvas, i, cellX, baseline, pitch, inkTop, inkBottom)
            canvas.restore()
        }

        // ---- 冒号（两个圆点，半秒闪烁）----
        if (showColon) {
            val dotX = x0 + pitch * 2 + gap + dotR
            val sec = cal.get(Calendar.SECOND)
            val millis = cal.get(Calendar.MILLISECOND)
            val phase = (sec % 2) * 1000 + millis // 0..2000
            val t = if (phase < 1000) phase / 1000f else (2000 - phase) / 1000f
            dotPaint.alpha = (150 + 105 * t).toInt()
            // 两点位于数字墨迹高度的 26% / 71% 处
            val h = inkBottom - inkTop
            canvas.drawCircle(dotX, inkTop + h * 0.26f, dotR, dotPaint)
            canvas.drawCircle(dotX, inkTop + h * 0.71f, dotR, dotPaint)
        }

        // ---- 绘制 MM：同样逐位独立摆动 ----
        val mmX = x0 + pitch * 2 + dotR * 2 + gap * 2
        for (i in 0..1) {
            val v = sway[2 + i]
            val cellX = mmX + i * pitch
            canvas.save()
            canvas.translate(v[0], v[1])
            canvas.rotate((v[0] * 0.05f).coerceIn(-1.6f, 1.6f), cellX + pitch / 2f, baseline)
            applyJelly(canvas, 2 + i, cellX + pitch / 2f, baseline)
            drawCell(canvas, 2 + i, cellX, baseline, pitch, inkTop, inkBottom)
            canvas.restore()
        }

        // 页面每秒只需要「冒号呼吸」这一个动画：
        // 60fps 是纯浪费（一次呼吸 2 秒，20fps 已有 40 级过渡，肉眼无差别）。
        // 晃动时 60fps（要顺滑），防烧屏位移过渡时 25fps，其余回到常规节奏
        scheduleRedraw(
            when {
                swayActive || jellyActive || rollActive -> 16L
                driftActive() -> 40L
                else -> REDRAW_MS
            }
        )
    }

    /** 只在「真正显示在屏幕上」时才排下一帧：页面被滑走、切到后台都会彻底停掉 */
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

    companion object {
        const val NIGHT_TEXT = 0xFF9B1B1B.toInt()      // 暗红
        const val NIGHT_TEXT_DIM = 0xFF6B1010.toInt()

        private const val DIGITS = "0123456789"

        /**
         * 重绘间隔。这里是全 App 最耗电的一页（常驻前台），
         * 而唯一需要动的只有冒号那 2 秒一次的呼吸渐变——
         * 60fps 纯属浪费，10fps 已有 20 级过渡，肉眼完全看不出差别。
         * （分钟跳变也会在这个节奏内被捕捉到。）
         */
        private const val REDRAW_MS = 100L
        private const val DRIFT_MS = 2500f     // 位移过渡时长
        // 摆动弹簧参数：ω≈2.6rad/s（约 0.41Hz，很慢）、阻尼比≈0.35（粘稠，回摆一次就停）
        // 时间常数 ≈ 1.1s，晃一次大约 2 秒才停稳，像泡在浓液体里
        private const val SWAY_K = 6.8f
        private const val SWAY_C = 1.8f
        // 果冻弹簧：ω≈4.5rad/s（约 0.7Hz）、阻尼比≈0.22 → 压扁→回弹→再弹一下，约 1.5 秒
        private const val JELLY_K = 20f
        private const val JELLY_C = 2.0f
        private const val ROLL_MS = 760L       // 换数字的卷动时长（放慢，非线性）
        private const val GLASS_ALPHA = 0.90f  // 数字半透明度

        /**
         * 数字墨迹高度占屏幕高的上限。
         * 宽度系数 0.86 在超宽屏（21:9 以上）会让数字变太高、压到右下角计时器，
         * 所以同时用高度兜一层，任何比例的屏幕都不会撞。
         */
        private const val TARGET_INK = 0.55f
        /** 冒号圆点半径 / 数字墨迹高度 */
        private const val DOT_R = 0.110f
        /** 数字与冒号之间的间隙 / 数字墨迹高度 */
        private const val GAP = 0.105f

        /** 圆润艺术字体；取不到时退回系统黑体，不影响使用 */
        private fun loadTypeface(ctx: Context): Typeface = try {
            ctx.resources.getFont(R.font.fredoka_bold)
        } catch (e: Exception) {
            Typeface.create("sans-serif-black", Typeface.BOLD)
        }
    }
}
