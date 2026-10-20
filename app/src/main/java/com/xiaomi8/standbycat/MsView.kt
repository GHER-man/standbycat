package com.xiaomi8.standbycat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * 右上角「毫秒」数字。故意做成自绘控件而不是 TextView：
 * 毫秒要 30fps 刷新，而 TextView.setText 每次都 requestLayout，
 * 一秒 30 次整棵视图树布局会拖慢主线程、把点击事件挤成一批。
 * 自绘只 invalidate()，不动布局。
 */
class MsView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private var ms = 0

    var nightMode = false
        set(v) { field = v; invalidate() }
    var accent = Prefs.DEFAULT_COLOR_A
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = try {
            ctx.resources.getFont(R.font.fredoka_bold)
        } catch (e: Exception) {
            Typeface.create("sans-serif-black", Typeface.BOLD)
        }
    }
    private val buf = CharArray(3)

    fun setMs(v: Int) {
        if (v != ms) {
            ms = v
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        p.textSize = 32f * resources.displayMetrics.scaledDensity
        p.color = if (nightMode) 0xFF6B1010.toInt() else accent
        var v = ms
        buf[2] = ('0' + v % 10); v /= 10
        buf[1] = ('0' + v % 10); v /= 10
        buf[0] = ('0' + v % 10)
        // 等宽数字：111 不会比 000 窄，毫秒滚动时整串不抖
        val pitch = Digits.pitch(p)
        val total = Digits.width(p, String(buf), pitch)
        val fm = p.fontMetrics
        val baseline = height / 2f + (fm.descent - fm.ascent) / 2f - fm.descent
        Digits.draw(canvas, p, String(buf), (width - total) / 2f, baseline, pitch)
    }
}
