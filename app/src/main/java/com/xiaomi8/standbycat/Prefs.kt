package com.xiaomi8.standbycat

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "standbycat"

    const val DEFAULT_COLOR_A = 0xFFFF5FAE.toInt() // 粉
    const val DEFAULT_COLOR_B = 0xFF8B3BFF.toInt() // 紫

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 表盘索引：0 数字钟 / 1 计时器 / 2 模拟指针 */
    /** 默认落在数字钟页（页面顺序：0 倒计时 / 1 数字钟 / 2 计时器 / 3 表盘） */
    fun getFace(ctx: Context): Int = sp(ctx).getInt("face", 1)
    fun setFace(ctx: Context, v: Int) = sp(ctx).edit().putInt("face", v).apply()

    fun getColorA(ctx: Context): Int = sp(ctx).getInt("colorA", DEFAULT_COLOR_A)
    fun setColorA(ctx: Context, v: Int) = sp(ctx).edit().putInt("colorA", v).apply()

    fun getColorB(ctx: Context): Int = sp(ctx).getInt("colorB", DEFAULT_COLOR_B)
    fun setColorB(ctx: Context, v: Int) = sp(ctx).edit().putInt("colorB", v).apply()

    fun isNight(ctx: Context): Boolean = sp(ctx).getBoolean("night", false)
    fun setNight(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("night", v).apply()

    /** 按键/长按的触感反馈，默认打开 */
    fun isLite(ctx: Context): Boolean = sp(ctx).getBoolean("lite", false)
    fun setLite(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("lite", v).apply()

    fun isHaptics(ctx: Context): Boolean = sp(ctx).getBoolean("haptics", true)
    fun setHaptics(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("haptics", v).apply()
}
