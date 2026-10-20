package com.xiaomi8.standbycat

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator

/**
 * 界面上按钮的液态玻璃质感 + 按下回弹。
 *
 * 玻璃按钮配方（与数字同一套语言，但更克制，避免按钮太抢眼）：
 *  - 体：竖直平滑渐变（顶部提亮一档 → 主色 → 辅色），整体按 [tint] 控制浓度
 *  - 顶部高光：上 45% 由白 12% 渐隐（模拟光从上方打在玻璃上）
 *  - 边缘：一圈细高光（混白 60%，alpha 随 tint 提高），不用描边避免生硬
 *  - 圆角：按控件高度的一半为上限，保证是"胶囊"或"圆角矩形"的自然过渡
 *
 * 回弹：按下 scale 0.94/0.96（90ms 减速），松手 220ms 带过冲回到 1（easeOutBack）。
 * 用 ViewPropertyAnimator 走渲染线程属性，不触发布局，不会引起重排。
 */
object GlassUi {

    /** 按下时缩到多少 */
    private const val PRESS_SCALE_X = 0.94f
    private const val PRESS_SCALE_Y = 0.96f
    private const val DOWN_MS = 90L
    private const val UP_MS = 220L

    private val backOut = Interpolator { t ->
        val u = t - 1f
        val c1 = 0.55f
        1f + (c1 + 1f) * u * u * u + c1 * u * u
    }

    /** 按下：轻微收缩 */
    fun pressDown(v: View) {
        if (Lite.on) return
        v.animate()
            .scaleX(PRESS_SCALE_X).scaleY(PRESS_SCALE_Y)
            .setDuration(DOWN_MS).setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** 松开：平滑回弹（带轻微过冲） */
    fun pressUp(v: View) {
        if (Lite.on) return
        v.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(UP_MS).setInterpolator(backOut)
            .start()
    }

    /** 给按钮装上按下回弹（不消费事件，点击逻辑照常） */
    fun pressFx(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> pressDown(view)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressUp(view)
            }
            false
        }
    }

    /**
     * 圆角矩形的液态玻璃背景。
     * @param tint 浓度：0.14 左右是普通按钮，0.85 适合"主按钮"（填充主题色）
     */
    fun background(
        cA: Int, cB: Int, radiusDp: Float, night: Boolean,
        tint: Float = 0.16f, dens: Float = 2.75f
    ): Drawable = GlassBg(cA, cB, radiusDp, night, tint, dens)

    private class GlassBg(
        val cA: Int, val cB: Int, val radiusDp: Float, val night: Boolean,
        val tint: Float, val dens: Float
    ) : Drawable() {

        private val body = Paint(Paint.ANTI_ALIAS_FLAG)
        private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val r = minOf(radiusDp * dens, b.height() / 2f)
            rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())

            // 体：竖直平滑渐变（多段采样，避免分层）。
            // 夜间模式必须换成暗红家族，不能再用白天的主题色，否则按键还是彩色的。
            body.shader = bodyShader(
                b.top.toFloat(), b.bottom.toFloat(),
                if (night) tint * 0.75f else tint,
                if (night) 0xFFB03030.toInt() else cA,
                if (night) 0xFF6B1010.toInt() else cB,
                night
            )
            canvas.drawRoundRect(rect, r, r, body)

            // 顶部高光（简化模式下不画）
            if (Lite.on) return
            gloss.shader = LinearGradient(
                b.top.toFloat(), b.top.toFloat(), b.top.toFloat(), b.top + b.height() * 0.55f,
                intArrayOf(Color.argb(46, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, r, r, gloss)

            // 不再画任何描边/边框：玻璃的轮廓完全靠"体色渐变 + 顶部高光"自然收边，
            // 这样才不会有"框住"的感觉（用户反馈那一圈线还是像边框）。
            // 顶部高光稍微加强一点，补回少了边框后的轮廓感。
        }

        private fun bodyShader(
            top: Float, bottom: Float, a: Float, accA: Int, accB: Int, night: Boolean
        ): LinearGradient {
            val base = if (night) 0xFF14090A.toInt() else 0xFF120E16.toInt()
            val hi = Digits.mix(base, accA, a * 1.15f)
            val mid = Digits.mix(base, accB, a * 0.85f)
            val lo = Digits.mix(base, accA, a * 0.55f)
            val n = 16
            val colors = IntArray(n)
            val pos = FloatArray(n)
            for (k in 0 until n) {
                val t = k / (n - 1f)
                pos[k] = t
                colors[k] = when {
                    t < 0.55f -> Digits.mix(hi, mid, smooth(t / 0.55f))
                    else -> Digits.mix(mid, lo, smooth((t - 0.55f) / 0.45f))
                }
            }
            return LinearGradient(0f, top, 0f, bottom, colors, pos, Shader.TileMode.CLAMP)
        }

        private fun smooth(t: Float): Float {
            val u = t.coerceIn(0f, 1f)
            return u * u * (3f - 2f * u)
        }

        override fun setAlpha(alpha: Int) {
            body.alpha = alpha
            gloss.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(cf: android.graphics.ColorFilter?) {
            body.colorFilter = cf
            gloss.colorFilter = cf
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }
}
