package com.xiaomi8.standbycat

/**
 * 「简化动效与玻璃质感」的全局开关（省电 / 低配模式）。
 *
 * 打开后：
 *  - 数字不再做玻璃分层（外发光、磨砂、抖动、边缘高光全跳过），只保留体色渐变；
 *  - 换字不再卷动、摇晃/果冻等装饰性动效全部关闭，数字直接切换；
 *  - 按键按下不再有缩放回弹（震动反馈保留）。
 * 由设置面板的开关控制，值存在 Prefs 里，Activity 启动时同步到这个对象。
 */
object Lite {
    @Volatile
    var on: Boolean = false
}
