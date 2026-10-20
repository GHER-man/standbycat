package com.xiaomi8.standbycat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil
import kotlin.math.max

/**
 * 数字排布工具。
 *
 * Fredoka 的数字是**比例宽**的（"11:11.1" 只有 "00:00.0" 的 72% 宽），
 * 直接按字宽画，秒表/时钟每跳一个数字整串就会变宽变窄，看起来一直在抖。
 * 这里统一改成「等宽数字」：每个数字占一格，格宽取 0~9 里最宽的那个，
 * 数字在本格里居中绘制 —— 整串宽度恒定，跳字时位置纹丝不动。
 */
object Digits {

    /**
     * 平滑的竖直「玻璃」渐变。
     * 之前只给 4 个色标（高光 / 主色 / 辅色 / 暗部），每两个色标之间是直线插值，
     * 折点处一阶导数突变，视觉上就是一条横贯数字的颜色分界线。
     * 这里改成对 16 个色标采样 smoothstep 混合曲线，转折处导数连续 → 过渡自然、看不出分界线。
     */
    fun glassShader(
        x: Float, top: Float, bottom: Float, cA: Int, cB: Int
    ): android.graphics.LinearGradient {
        val hi = mix(cA, android.graphics.Color.WHITE, 0.46f)
        val lo = mix(cB, android.graphics.Color.BLACK, 0.42f)
        val n = 16
        val colors = IntArray(n)
        val pos = FloatArray(n)
        for (k in 0 until n) {
            val t = k / (n - 1f)
            pos[k] = t
            colors[k] = when {
                t < 0.46f -> mix(hi, cA, smooth(t / 0.46f))
                t < 0.76f -> mix(cA, cB, smooth((t - 0.46f) / 0.30f))
                else -> mix(cB, lo, smooth((t - 0.76f) / 0.24f))
            }
        }
        return android.graphics.LinearGradient(x, top, x, bottom, colors, pos,
            android.graphics.Shader.TileMode.CLAMP)
    }

    /** smoothstep：起点/终点导数为 0，接缝处不会有折线感 */
    private fun smooth(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    /** t=0 取 a，t=1 取 b */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val u = 1f - k
        return android.graphics.Color.argb(
            (android.graphics.Color.alpha(a) * u + android.graphics.Color.alpha(b) * k).toInt(),
            (android.graphics.Color.red(a) * u + android.graphics.Color.red(b) * k).toInt(),
            (android.graphics.Color.green(a) * u + android.graphics.Color.green(b) * k).toInt(),
            (android.graphics.Color.blue(a) * u + android.graphics.Color.blue(b) * k).toInt()
        )
    }

    /** 0~9 里最宽数字的步进，作为每格的固定宽度 */
    fun pitch(p: Paint): Float {
        var m = 0f
        for (c in '0'..'9') m = max(m, p.measureText(c.toString()))
        return m
    }

    /** 按等宽规则算出整串占宽（数字=N 格，其余字符用自身字宽） */
    fun width(p: Paint, s: String, pitch: Float): Float {
        var w = 0f
        for (c in s) w += if (c in '0'..'9') pitch else p.measureText(c.toString())
        return w
    }

    /**
     * 从左到右逐字符绘制：数字居中于各自格内，其余字符（: . -）按自身宽度。
     * startX 为整串左边界，baseline 为基线。
     */
    fun draw(canvas: Canvas, p: Paint, s: String, startX: Float, baseline: Float, pitch: Float) {
        var x = startX
        for (c in s) {
            val one = c.toString()
            if (c in '0'..'9') {
                p.textAlign = Paint.Align.CENTER
                canvas.drawText(one, x + pitch / 2f, baseline, p)
                x += pitch
            } else {
                p.textAlign = Paint.Align.LEFT
                canvas.drawText(one, x, baseline, p)
                x += p.measureText(one)
            }
        }
    }
}

/**
 * 等宽数字文本控件。替代 TextView 的数字显示：
 *  - 固定步进 → 内容变化时宽度不变（不会被比例字体搞得忽宽忽窄）
 *  - 只 invalidate()，不 requestLayout → 高频刷新（毫秒/秒表）不触发整树布局
 *
 * 颜色/字号由外部设置（跟随主题色与夜间模式）。
 */
class DigitsView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    /** 字色，画面时读取；改了会立即重绘 */
    var color: Int = 0xFFF5F5F5.toInt()
        set(v) { if (field != v) { field = v; invalidate() } }

    /** 字号（sp） */
    var textSizeSp: Float = 26f
        set(v) { if (field != v) { field = v; invalidate() } }

    private var content = ""

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = try {
            ctx.resources.getFont(R.font.fredoka_bold)
        } catch (e: Exception) {
            Typeface.create("sans-serif-black", Typeface.BOLD)
        }
    }

    /** 内容没变就不重绘 */
    fun setContent(s: String) {
        if (s != content) {
            content = s
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // wrap_content 时按当前字号/内容算尺寸（自定义 View 默认会量成 0，必须自己算）
        p.textSize = textSizeSp * resources.displayMetrics.scaledDensity
        val pitch = Digits.pitch(p)
        val wantW = ceil(Digits.width(p, content, pitch)) + paddingLeft + paddingRight
        val fm = p.fontMetrics
        val wantH = ceil(fm.descent - fm.ascent) + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(wantW.toInt(), widthMeasureSpec),
            resolveSize(wantH.toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (content.isEmpty() || width == 0 || height == 0) return
        p.textSize = textSizeSp * resources.displayMetrics.scaledDensity
        p.color = color

        val pitch = Digits.pitch(p)
        val total = Digits.width(p, content, pitch)
        val fm = p.fontMetrics
        // 以字体上下沿做垂直居中
        val baseline = height / 2f + (fm.descent - fm.ascent) / 2f - fm.descent
        Digits.draw(canvas, p, content, (width - total) / 2f, baseline, pitch)
    }
}
