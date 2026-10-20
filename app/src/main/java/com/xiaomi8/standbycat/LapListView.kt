package com.xiaomi8.standbycat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.Scroller
import kotlin.math.abs
import kotlin.math.max

/**
 * 计次记录列表（计时器页左下角）：
 * 每行显示「序号 / 计次时间 / 与上一次的间隔」，固定显示 3 行，可上下拖动翻看历史。
 * 最新一条在最上面。拖出边界有回弹，松手带惯性。
 *
 * 这个控件自己消费竖直拖动，所以它的区域不会触发整页上下翻页。
 */
class LapListView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    data class Lap(val idx: Int, val totalMs: Long, val deltaMs: Long)

    private val laps = ArrayList<Lap>()      // 索引 0 = 最新

    var nightMode = false
        set(v) { field = v; invalidate() }
    var accent = Prefs.DEFAULT_COLOR_A
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = try {
            ctx.resources.getFont(R.font.fredoka_bold)
        } catch (e: Exception) {
            Typeface.create("sans-serif-black", Typeface.BOLD)
        }
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val scroller = Scroller(ctx)
    private val vt = VelocityTracker.obtain()
    private var scrollY = 0f
    private var lastY = 0f
    private var dragging = false

    private fun main(): Int = if (nightMode) 0xFFB03030.toInt() else Color.parseColor("#DADADA")
    private fun dimMain(): Int = if (nightMode) 0xFF6B2A2A.toInt() else Color.parseColor("#7A7A7A")
    private fun acc(): Int = if (nightMode) 0xFF6B1010.toInt() else accent

    // ---------------- 数据 ----------------

    fun addLap(l: Lap) {
        laps.add(0, l)
        scrollY = 0f
        scroller.forceFinished(true)
        invalidate()
    }

    fun clearLaps() {
        if (laps.isEmpty()) return
        laps.clear()
        scrollY = 0f
        scroller.forceFinished(true)
        invalidate()
    }

    val lapCount: Int get() = laps.size

    // ---------------- 绘制 ----------------

    private fun rowH() = height / 3f

    private fun maxScroll(): Float = max(0f, laps.size * rowH() - height)

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val rh = rowH()
        val ts = rh * 0.36f
        p.textSize = ts

        if (laps.isEmpty()) {
            hint.textSize = rh * 0.30f
            hint.color = dimMain()
            canvas.drawText("按「计次」开始记录", width / 2f, height / 2f + hint.textSize * 0.35f, hint)
            return
        }

        val pad = width * 0.062f
        val xIdx = pad
        val xTotal = width * 0.30f
        val xDelta = width - pad
        val base = ts * 0.35f

        scrollY = scrollY.coerceIn(0f, maxScroll())
        val first = (scrollY / rh).toInt()
        val off = scrollY - first * rh

        var i = first
        while (i < laps.size && i <= first + 3) {
            val top = (i - first) * rh - off
            if (top > height) break
            val cy = top + rh / 2f
            val l = laps[i]
            val newest = (i == 0)

            p.color = if (newest) acc() else main()
            p.textAlign = Paint.Align.LEFT
            canvas.drawText("#" + String.format("%02d", l.idx), xIdx, cy + base, p)
            canvas.drawText(fmtTotal(l.totalMs), xTotal, cy + base, p)

            p.textAlign = Paint.Align.RIGHT
            p.color = if (newest) acc() else dimMain()
            canvas.drawText(fmtDelta(l.deltaMs), xDelta, cy + base, p)
            ++i
        }

        // 右侧滚动指示条
        if (maxScroll() > 1f) {
            val trackH = height * 0.82f
            val top = height * 0.09f
            val thumbH = max(trackH * 0.25f, trackH * height / (laps.size * rh))
            val ty = top + (trackH - thumbH) * (scrollY / maxScroll())
            bar.color = dimMain()
            bar.alpha = 120
            canvas.drawRoundRect(
                width - pad * 0.55f, ty, width - pad * 0.20f, ty + thumbH,
                pad * 0.18f, pad * 0.18f, bar
            )
            bar.alpha = 255
        }
    }

    private fun fmtTotal(ms: Long): String {
        val t = ms / 1000
        return String.format("%02d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60)
    }

    private fun fmtDelta(ms: Long): String {
        val tenth = ms / 100
        return String.format("%02d:%02d.%d", (tenth / 600) % 100, (tenth / 10) % 60, tenth % 10)
    }

    // ---------------- 拖动 / 惯性 ----------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (laps.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastY = e.y
                dragging = true
                scroller.forceFinished(true)
                vt.clear()
                vt.addMovement(e)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                vt.addMovement(e)
                val dy = e.y - lastY
                lastY = e.y
                scrollY = (scrollY - dy).coerceIn(0f, maxScroll())
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                vt.addMovement(e)
                vt.computeCurrentVelocity(1000)
                val vy = -vt.yVelocity
                if (abs(vy) > 120f) {
                    scroller.fling(0, scrollY.toInt(), 0, vy.toInt(), 0, 0, 0, maxScroll().toInt())
                    postInvalidateOnAnimation()
                }
                return true
            }
        }
        return false
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat()
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        vt.recycle()
    }
}
