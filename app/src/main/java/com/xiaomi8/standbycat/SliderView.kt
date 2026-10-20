package com.xiaomi8.standbycat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * 取色面板用的滑块。故意不用 SeekBar：
 * 系统 SeekBar 的进度条是细线 + 小圆点，且各 ROM 样式不一（MIUI 还会染色）。
 *
 * 造型照参考图做成「粗胶囊轨道 + 轨道内嵌圆形旋钮」：
 *  - 轨道 = 全圆角胶囊，已填充段跟随主题色，未填充段用一档更亮的黑
 *  - 旋钮 = 轨道内部的黑圆（约为轨道高的 3/4），始终在胶囊内滑动，不会溢出
 *
 * progress 归一化到 0..1，外部按自己的量程换算。
 */
class SliderView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var progress: Float = 0.5f
        set(v) {
            val n = v.coerceIn(0f, 1f)
            if (n != field) { field = n; invalidate() }
        }

    /** 主题色：轨道已填充部分用它 */
    var accent: Int = Prefs.DEFAULT_COLOR_A
        set(v) { if (field != v) { field = v; invalidate() } }

    /** 夜间模式下换成暗红系 */
    var nightMode: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** 拖动中回调（用于实时更新颜色） */
    var onChange: ((Float) -> Unit)? = null

    /** 手指按下时回调（用于给一次触感反馈） */
    var onStart: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val trackH = 24f * density          // 胶囊轨道高度
    private val knobR = 8.5f * density          // 内部黑圆半径（约轨道高的 3/4）

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private fun acc(): Int = if (nightMode) 0xFFB03030.toInt() else accent

    // 色块是又长又亮的实心胶囊，系统「强制深色」会把这种大面积浅色当成背景压暗。
    // 用「两端同色」的纯色 shader 绘制即可规避（视觉完全一致）。
    private var shaderColor = Int.MIN_VALUE
    private var fillShader: android.graphics.Shader? = null

    private fun fillShaderFor(c: Int): android.graphics.Shader {
        if (fillShader == null || shaderColor != c) {
            shaderColor = c
            fillShader = android.graphics.LinearGradient(
                0f, 0f, 0f, 1f, c, c, android.graphics.Shader.TileMode.CLAMP
            )
        }
        return fillShader!!
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wantH = (trackH + 6 * density).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize((140 * density).toInt(), widthMeasureSpec),
            resolveSize(wantH, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val cy = height / 2f
        // 胶囊铺满整幅宽度
        val pillL = paddingLeft.toFloat()
        val pillR = width - paddingRight.toFloat()
        if (pillR <= pillL) return
        // 圆头圆心可移动范围：两端各内缩一个圆头半径（再留 1dp 余量）。
        // 注意不能按 (trackH/2 - knobR) 内缩 —— 胶囊端头是半径 trackH/2 的半圆，
        // 圆心在 pillR - trackH/2 处，圆头最多只能再往外 trackH/2 - knobR，
        // 也就是圆心极限 = pillR - trackH/2 + (trackH/2 - knobR) = pillR - knobR。
        val inset = knobR + 1f * density
        val travelL = pillL + inset
        val travelR = pillR - inset

        track.color = if (nightMode) 0xFF3E1414.toInt() else 0xFF33333A.toInt()
        fill.color = 0xFF000000.toInt()      // 同上：让只看 color 的分析器读到深色
        fill.shader = fillShaderFor(acc())
        knob.color = 0xFF000000.toInt()          // 旋钮：黑圆

        rect.set(pillL, cy - trackH / 2f, pillR, cy + trackH / 2f)
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, track)

        val cx = travelL + (travelR - travelL) * progress
        // 色块（已填充段）要多包住圆头一段，让圆头完全落在色块内部 —— 与参考图一致：
        // 彩色胶囊把旋钮整圈包住，而不是让旋钮骑在填充/未填充的分界线上。
        val fillRight = (cx + knobR + 2f * density).coerceAtMost(pillR)
        rect.set(pillL, cy - trackH / 2f, fillRight, cy + trackH / 2f)
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, fill)

        canvas.drawCircle(cx, cy, knobR, knob)
    }

    private fun update(x: Float) {
        val inset = knobR + 1f * density
        val travelL = paddingLeft + inset
        val travelR = width - paddingRight - inset
        if (travelR <= travelL) return
        val p = ((x - travelL) / (travelR - travelL)).coerceIn(0f, 1f)
        if (p != progress) {
            progress = p
            onChange?.invoke(p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onStart?.invoke()
                update(e.x)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> { update(e.x); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { update(e.x); return true }
        }
        return super.onTouchEvent(e)
    }

    /** 便于外部按量程设置 */
    fun setValue(v: Float, max: Float) { progress = if (max <= 0f) 0f else v / max }

    fun value(max: Float): Float = progress * max(max, 0f)
}
