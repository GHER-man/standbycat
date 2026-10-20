package com.xiaomi8.standbycat

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 液态玻璃数字渲染器（iOS Liquid Glass 风格）。
 *
 * ## 为什么用位图缓存
 * 这套质感由 6 层叠出来（模糊光晕 / 体色渐变 / 底内阴影 / 顶部高光 / 边缘细高光 / 磨砂噪点），
 * 每帧现算在 60fps 下必然掉帧。这里把「某个字、某个字号、某套配色」渲染一次，缓成 Bitmap，
 * 之后每帧只是 drawBitmap（还能用 paint.alpha 做淡入淡出），性能反而比原来的渐变文字更省。
 *
 * ## 分层配方（关键参数都集中在下面常量里）
 * 1. 外发光：BlurMaskFilter(0.06×h)，主题色 10% —— 玻璃"吸住"背景的柔光
 * 2. 体色：竖直 4 段渐变（顶部提亮 30% → 主色 → 辅色 → 底部压暗 45%）
 * 3. 底部内阴影：底 34% 高度内由透明渐变到黑 42%（SRC_ATOP，只落在字形里）
 * 4. 顶部镜面高光：顶 16% 高度内由白 34% 渐隐（SRC_ATOP）
 * 5. 斜向反光：左上 35° 的窄亮带，白色 8%（模拟窗光反射）
 * 6. 磨砂噪点：64×64 噪声平铺，SRC_ATOP，alpha 7%（把"塑料感"变成"磨砂玻璃"）
 * 7. 边缘细高光：整字减去缩小 1.6% 的整字得到的一圈细环（不描边，所以不会出现字形内部穿线）
 *
 * 缓存分辨率取 0.5 倍再放大：磨砂/高光本来就需要柔化，视觉无损失，内存省 4 倍。
 */
object GlassDigits {

    private const val SCALE = 0.5f                 // 缓存分辨率系数
    // —— 液态玻璃的关键不在"厚"，而在"亮而透"：所有颜色都往白里提，绝不用黑色压暗，
    // 否则立刻变成亚克力/磨砂塑料的灰蒙感。
    private const val GLOW = 0.05f                 // 外发光半径（相对字高）
    private const val GLOW_ALPHA = 0.07f           // 太强会糊成雾，反而像塑料
    private const val BODY_ALPHA = 0.92f
    private const val INNER_SHADOW = 0.14f         // 只留底部很窄一条接触阴影
    private const val INNER_SHADOW_ALPHA = 0.13f   // 黑色只给 13%，只做"贴地"提示
    private const val SPEC = 0.24f                 // 顶部镜面占比
    private const val SPEC_ALPHA = 0.46f           // 高光更亮更锐 → 玻璃光泽
    private const val SHEEN_ALPHA = 0.11f
    private const val FROST_ALPHA = 0.030f         // 磨砂降到 3.5%：要通透，不要磨砂玻璃
    private const val RIM_INSET = 0.0075f         // 边缘高光更细（≈4px）
    private const val RIM_ALPHA = 0.52f

    /**
     * 缓存 key。**必须是 data class**：否则没有 equals/hashCode，每次绘制都会被认为是新 key，
     * 缓存会无限增长 —— 之前就是这样每帧重建位图，几分钟后 OutOfMemoryError 闪退，
     * 顺带把"数字跳动卡顿"也解释通了（每帧都在新建 Bitmap）。
     */
    private data class Key(val ch: Char, val w: Int, val h: Int, val a: Int, val b: Int, val lite: Boolean)

    private val cache = HashMap<Key, Bitmap>()
    /** 复用画笔：原来每帧每位 new 一个 Paint，滚动时 4 个/帧 → 现在零分配 */
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var noise: Bitmap? = null
    private val dstRect = RectF()

    /** 命中缓存就复用；否则把这一位数字渲染成玻璃位图 */
    private fun bitmapOf(
        ch: Char, typeface: android.graphics.Typeface, textSize: Float,
        cellW: Int, inkH: Int, baselineInCell: Float, cA: Int, cB: Int
    ): Bitmap {
        val w = max(4, (cellW * SCALE).toInt())
        val h = max(4, (inkH * SCALE).toInt())
        val lite = Lite.on
        val key = Key(ch, w, h, cA, cB, lite)
        cache[key]?.let { return it }
        // 兜底：换主题/改字号会换 key，条目太多时整体清空（10 个数字 × 少数几套配置本就够用）
        if (cache.size > 60) cache.clear()

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val ts = textSize * SCALE
        val baseY = baselineInCell * SCALE
        val cx = w / 2f
        val text = ch.toString()

        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            this.textSize = ts
            textAlign = Paint.Align.CENTER
            style = Paint.Style.FILL
        }
        val hh = h.toFloat()

        // 1) 外发光：玻璃边缘吸收的背景柔光
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            this.textSize = ts
            textAlign = Paint.Align.CENTER
            color = Digits.mix(cA, Color.WHITE, 0.30f)
            alpha = (GLOW_ALPHA * 255).toInt()
            maskFilter = BlurMaskFilter(hh * GLOW, BlurMaskFilter.Blur.NORMAL)
        }
        if (!lite) c.drawText(text, cx, baseY, glow)

        // 2) 体色：24 段 smoothstep 采样 —— 折点处导数连续，不会出现分层线；
        //    而且整体提亮（顶部混白 58%、底部混白 10%），看起来才"透"而不是"灰"。
        glyph.shader = bodyShader(cA, cB, hh)
        glyph.alpha = (BODY_ALPHA * 255).toInt()
        c.drawText(text, cx, baseY, glyph)

        if (lite) {
            // 简化模式：只留体色渐变，其余玻璃分层全部跳过
            cache[key] = bmp
            return bmp
        }

        // 3) 底部内阴影（只落在字形内部）
        val atop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }
        // 从 45% 高度开始极缓地压一点点暗（斜率从 0 起，不会有起点坎）
        atop.shader = LinearGradient(
            0f, hh * 0.45f, 0f, hh,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT,
                Color.argb((INNER_SHADOW_ALPHA * 255).toInt(), 0, 0, 0)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, hh * 0.45f, w.toFloat(), hh, atop)

        // 4/5) 顶部镜面与斜向反光已经并进体色曲线（见 bodyShader），这里不再单独叠加，
        //      避免各自「画到某高度就结束」产生分层坎。

        // 5.5) 色彩抖动：8bit 渐变本身会有等值带（Mach band），
        //      叠一层极弱的 RGB 抖动噪声，把相邻等值带的边界打碎 → 肉眼看不到分层。
        atop.shader = android.graphics.BitmapShader(
            ditherTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT
        )
        atop.alpha = 56
        c.drawRect(0f, 0f, w.toFloat(), hh, atop)
        atop.alpha = 255

        // 6) 磨砂噪点
        atop.shader = android.graphics.BitmapShader(
            noiseTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT
        )
        atop.alpha = (FROST_ALPHA * 255).toInt()
        c.drawRect(0f, 0f, w.toFloat(), hh, atop)
        atop.alpha = 255
        atop.xfermode = null
        atop.shader = null

        // 7) 边缘细高光：整字 − 缩小的整字，只在字形轮廓上留一圈（不会穿线）
        val rimAlpha = (RIM_ALPHA * 255).toInt()
        val layer = c.saveLayer(0f, 0f, w.toFloat(), hh, null)
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            this.textSize = ts
            textAlign = Paint.Align.CENTER
            color = Digits.mix(cA, Color.WHITE, 0.80f)
            alpha = rimAlpha
        }
        c.drawText(text, cx, baseY, rimPaint)
        val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            this.textSize = ts
            textAlign = Paint.Align.CENTER
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        val k = 1f - RIM_INSET
        c.save()
        c.scale(k, k, cx, baseY)
        c.drawText(text, cx, baseY, cut)
        c.restore()
        c.restoreToCount(layer)

        cache[key] = bmp
        return bmp
    }

    /**
     * 体色曲线：把「顶部镜面高光」直接融进颜色本身，而不是再叠一层半透明亮带。
     * 叠层的问题：每一层都是「画到某个高度就结束」，结束处斜率突变 → 视觉上就是一道分层坎。
     * 这里用 4 个锚点 + 48 段 smoothstep 采样，全曲线 C1 连续：
     *   0.03 镜面（几乎白）→ 0.22 亮体 → 0.62 中体 → 1.0 底部（提亮，不压黑）
     */
    private fun bodyShader(cA: Int, cB: Int, h: Float): LinearGradient {
        // 关键：高光不能"白得太狠"。深色主题下（夜间暗红）高光与体色对比一大，
        // 即使 smoothstep 也会形成明显的亮度带（Mach band）。所以把提亮幅度收窄、过渡拉长。
        val spec = Digits.mix(cA, Color.WHITE, 0.58f)
        val hi = Digits.mix(cA, Color.WHITE, 0.22f)
        val mid = Digits.mix(cA, cB, 0.55f)
        val lo = Digits.mix(cB, Color.WHITE, 0.10f)
        val n = 64
        val colors = IntArray(n)
        val pos = FloatArray(n)
        for (k in 0 until n) {
            val t = k / (n - 1f)
            pos[k] = t
            colors[k] = when {
                t < 0.34f -> Digits.mix(spec, hi, smooth(t / 0.34f))
                t < 0.70f -> Digits.mix(hi, mid, smooth((t - 0.34f) / 0.36f))
                else -> Digits.mix(mid, lo, smooth((t - 0.70f) / 0.30f))
            }
        }
        return LinearGradient(0f, 0f, 0f, h, colors, pos, Shader.TileMode.CLAMP)
    }

    private fun smooth(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    private var dither: Bitmap? = null

    /** 64×64 中灰抖动图（RGB 围绕 128 抖动），配合 SRC_ATOP + 低 alpha 做色彩抖动 */
    private fun ditherTile(): Bitmap {
        dither?.let { return it }
        val s2 = 64
        val b = Bitmap.createBitmap(s2, s2, Bitmap.Config.ARGB_8888)
        val px = IntArray(s2 * s2)
        val rnd = Random(424242)
        for (i in px.indices) {
            val v = 128 + rnd.nextInt(-8, 9)
            px[i] = Color.argb(255, v, v, v)
        }
        b.setPixels(px, 0, s2, 0, 0, s2, s2)
        dither = b
        return b
    }

    /** 64×64 灰度噪点，平铺用；只生成一次 */
    private fun noiseTile(): Bitmap {
        noise?.let { return it }
        val s = 64
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ALPHA_8)
        val px = ByteArray(s * s)
        val rnd = Random(20261019)
        for (i in px.indices) px[i] = (rnd.nextInt(0, 256)).toByte()
        b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
        noise = b
        return b
    }

    /**
     * 画一位数字（玻璃质感）。
     * [cellX] 是这一格的左边界，[inkTop] 是数字墨迹的上沿；内部按位图尺寸铺满整格。
     */
    fun draw(
        canvas: Canvas, ch: String, cellX: Float, inkTop: Float, pitch: Float, inkH: Float,
        typeface: android.graphics.Typeface, textSize: Float, baselineOffset: Float,
        cA: Int, cB: Int, alpha: Float
    ) {
        if (alpha <= 0.01f || ch.isEmpty()) return
        val bmp = bitmapOf(
            ch[0], typeface, textSize, pitch.toInt(), inkH.toInt(), baselineOffset, cA, cB
        )
        drawPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        dstRect.set(cellX, inkTop, cellX + pitch, inkTop + inkH)
        canvas.drawBitmap(bmp, null as Rect?, dstRect, drawPaint)
    }

    /** 配色/字号变化时清缓存（夜间模式切换等） */
    fun clear() {
        cache.clear()
    }
}
