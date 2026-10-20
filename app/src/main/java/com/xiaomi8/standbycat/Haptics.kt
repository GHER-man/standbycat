package com.xiaomi8.standbycat

import android.app.Dialog
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/**
 * 触感反馈。
 *
 * 直接用 Vibrator，而不是 View.performHapticFeedback：后者受「系统触感反馈」开关和 ROM 实现影响，
 * 在 MIUI 上经常静默（开关关掉就什么都没有）。这里按需求保证「按下去就有反馈」。
 *
 * 两档手感：
 *  - [tap]   按键轻点，短促微弱，像键盘触感
 *  - [press] 长按/需要明确确认的动作，稍长且更重
 */
object Haptics {
    private const val TAP_MS = 12L
    private const val TAP_AMP = 78
    private const val LONG_MS = 26L
    private const val LONG_AMP = 170

    /**
     * 倒计时结束的「闹钟」震动：一直循环，直到 [alarmStop] 被调用。
     * 节奏是「响 420ms、停 300ms」反复，像闹钟一样持续而有节奏。
     * 注意：不受「按键震动」开关影响 —— 那是按键反馈的开关，闹钟属于功能性提醒。
     */
    fun alarmStart(ctx: Context) {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            val timings = longArrayOf(0L, 420L, 300L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(timings, 0))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(timings, 0)
            }
        }
    }

    fun alarmStop(ctx: Context) {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        runCatching { v.cancel() }
    }

    private fun fire(ctx: Context, ms: Long, amp: Int) {
        if (!Prefs.isHaptics(ctx)) return          // 用户可在取色面板里关掉
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, amp.coerceIn(1, 255)))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        }
    }

    /** 按键：轻点一下 */
    fun tap(ctx: Context) = fire(ctx, TAP_MS, TAP_AMP)

    /** 长按：更重一档 */
    fun press(ctx: Context) = fire(ctx, LONG_MS, LONG_AMP)
}

/**
 * 按下（ACTION_DOWN）即给一次轻点反馈。
 * 只做反馈、返回 false 不消费事件，因此原有的 click / longClick / 拖动全部照常工作；
 * 控件处于 disabled 状态时（例如没开始计时时的「计次」）不给反馈，免得误以为生效了。
 */
/**
 * 按键的「触感 + 按下回弹」。
 *
 * 注意：这里装的是 OnTouchListener，会覆盖之前装的监听 —— 所以回弹必须和触感写在**同一个**监听里，
 * 否则像之前那样先装回弹再调 hapticTap()，回弹就被顶掉了（表现就是"只有倒计时页的键有回弹"）。
 */
fun View.hapticTap(): View {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (v.isEnabled) {
                    Haptics.tap(v.context)
                    GlassUi.pressDown(v)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 抬起再给一次：全站按键统一都是"两下"（按下一下、松手一下）
                if (v.isEnabled) Haptics.tap(v.context)
                GlassUi.pressUp(v)
            }
        }
        false
    }
    return this
}

/** 给系统弹窗（AlertDialog / DatePickerDialog）自带的按钮补上反馈。 */
fun Dialog.hapticButtons() {
    val decor = window?.decorView as? ViewGroup ?: return
    intArrayOf(android.R.id.button1, android.R.id.button2, android.R.id.button3).forEach { id ->
        decor.findViewById<View>(id)?.hapticTap()
    }
}
