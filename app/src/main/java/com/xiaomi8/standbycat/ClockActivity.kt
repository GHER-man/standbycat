package com.xiaomi8.standbycat

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import java.time.LocalDate
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.min

/** 黑底时钟主页：三种表盘上下滑动切换；长按取色；夜间模式。 */
class ClockActivity : Activity() {

    private companion object {
        /** 每个「格」对应的滑动距离：越大越不灵敏（像真实滚轮的刻度） */
        const val MIN_STEP_PX = 92f
        const val SEC_STEP_PX = 64f
        /** 松手后的惯性衰减与最小启动速度 */
        const val INERTIA_DECAY = 0.93f
        const val INERTIA_MIN = 0.015f
    }

    private lateinit var root: FrameLayout
    private lateinit var pageHost: FrameLayout
    private lateinit var bigClock: BigClockView
    private lateinit var analogClock: ClockView
    private lateinit var dateText: TextView
    private lateinit var dotsRow: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var topRightBar: LinearLayout
    private lateinit var secondsPanel: LinearLayout
    private lateinit var secondsText: DigitsView
    private lateinit var secondsUnit: TextView
    private lateinit var timerPage: TimerPageView
    private lateinit var countdownPage: CountdownPageView
    private lateinit var cdPresets: View
    private lateinit var cdHint: View
    private lateinit var cdToggle: TextView

    // ---------------- 倒计时状态 ----------------
    private var cdTotalMs = 5 * 60_000L
    private var cdRemainMs = cdTotalMs
    private var cdRunning = false
    private var cdEndAt = 0L
    private var cdFinished = false            // 已归零、正在响闹钟

    private val cdTick = object : Runnable {
        override fun run() {
            val left = (cdEndAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            cdRemainMs = left
            countdownPage.remainMs = left
            if (left <= 0L) {
                cdRunning = false
                cdFinished = true
                countdownPage.running = false
                countdownPage.remainMs = 0L
                Haptics.alarmStart(this@ClockActivity)   // 闹钟式持续震动，直到点「停止」
                updateCdUi()
                return
            }
            updateCdUi()
            handler.postDelayed(this, 200L)
        }
    }
    private lateinit var timerPanel: LinearLayout
    private lateinit var nowPanel: LinearLayout
    private lateinit var nowText: DigitsView
    private lateinit var timerText: DigitsView
    private lateinit var btnPrimary: TextView
    private lateinit var btnLap: TextView
    private lateinit var btnReset: TextView
    private lateinit var lapList: LapListView
    private lateinit var msPanel: LinearLayout
    private lateinit var msView: MsView

    private var pages: List<View> = emptyList()
    private var index = 0
    private var dragOffset = 0f
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var longFired = false

    /** 取色面板开着时，主题/夜间变化后要重新给滑杆与开关上色 */
    private var pickerRefresh: (() -> Unit)? = null

    private var colorA = Prefs.DEFAULT_COLOR_A
    private var colorB = Prefs.DEFAULT_COLOR_B
    private var night = false

    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        longFired = true
        Haptics.press(this)          // 长按取色：重一档的反馈
        showColorPicker()
    }
    private val tick = object : Runnable {
        override fun run() {
            updateDate()
            updateSeconds()
            updateNowText()
            // 对齐到整秒，避免秒数跳动有延迟
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    // ---------------- 防烧屏：第一页数字钟每隔几分钟轻微位移 ----------------
    // OLED 屏整夜显示同一画面会留下残影。这里让大字时间在一圈预设位置上缓慢游走，
    // 相邻两次不会停在同一处；幅度 ±13dp 左右，肉眼几乎看不出位置变化。
    private val driftSteps = arrayOf(
        0 to 0, 10 to -7, -9 to 8, 13 to 6, -12 to -9, 7 to 12, -6 to -12, 12 to -9, -11 to 5
    )
    private var driftIndex = 0

    private val driftTick = object : Runnable {
        override fun run() {
            applyNextDrift()
            handler.postDelayed(this, driftIntervalMs())
        }
    }

    /** 2~4 分钟随机一次，避免固定节奏 */
    private fun driftIntervalMs(): Long = 120_000L + (0..120_000L).random()

    private fun applyNextDrift() {
        var i = driftIndex
        while (i == driftIndex) i = driftSteps.indices.random()   // 不重复上一处
        driftIndex = i
        val d = resources.displayMetrics.density
        val (x, y) = driftSteps[i]
        bigClock.driftTo(x * d, y * d)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 小米 8 有刘海：横屏时刘海变成侧边一条竖带，ROM 默认把窗口限制在刘海安全区内，
        // 结果窗口比屏幕窄 89px、整块内容右偏。这里让窗口铺满整屏（黑底看不出接缝），
        // 需要避让的元素（右上角按钮）再单独按刘海安全区补偿。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // 关闭「强制深色模式」：否则 MIUI 会擅自把文字颜色提亮（自绘图形不受影响）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.decorView.isForceDarkAllowed = false
        }
        setContentView(R.layout.activity_clock)

        root = findViewById(R.id.clockRoot)
        pageHost = findViewById(R.id.pageHost)
        bigClock = findViewById(R.id.bigClock)
        analogClock = findViewById(R.id.analogClock)
        dateText = findViewById(R.id.dateText)
        dotsRow = findViewById(R.id.dotsRow)
        bottomBar = findViewById(R.id.bottomBar)
        topRightBar = findViewById(R.id.topRightBar)
        secondsPanel = findViewById(R.id.secondsPanel)
        secondsText = findViewById(R.id.secondsText)
        secondsUnit = findViewById(R.id.secondsUnit)
        timerPage = findViewById(R.id.timerPage)
        countdownPage = findViewById(R.id.countdownPage)
        cdPresets = findViewById(R.id.cdPresets)
        cdHint = findViewById(R.id.cdHint)
        cdToggle = findViewById(R.id.cdToggle)
        timerPanel = findViewById(R.id.timerPanel)
        nowPanel = findViewById(R.id.nowPanel)
        nowText = findViewById(R.id.nowText)
        timerText = findViewById(R.id.timerText)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnLap = findViewById(R.id.btnLap)
        btnReset = findViewById(R.id.btnReset)
        lapList = findViewById(R.id.lapList)
        msPanel = findViewById(R.id.msPanel)
        msView = findViewById(R.id.msView)

        // 数字类文字统一用圆体（中文标签由系统字体兜底）
        val rounded = resources.getFont(R.font.fredoka_bold)
        // 三个数字面板用自绘等宽数字控件（比例字体会让宽度忽宽忽窄）
        secondsText.textSizeSp = 32f
        nowText.textSizeSp = 26f
        timerText.textSizeSp = 26f
        btnPrimary.typeface = rounded
        btnLap.typeface = rounded
        btnReset.typeface = rounded
        // 所有按键：触感 + 按下回弹（hapticTap 内部同时装了回弹动画）
        for (id in intArrayOf(
            R.id.btnPrimary, R.id.btnLap, R.id.btnReset,
            R.id.cdPreset5, R.id.cdPreset15, R.id.cdPreset30, R.id.cdPreset60, R.id.cdToggle
        )) {
            findViewById<View>(id)?.hapticTap()
        }
        btnPrimary.setOnClickListener { onPrimaryClick() }
        btnLap.setOnClickListener { onLapClick() }
        btnReset.setOnClickListener { resetTimer() }
        // 三个计时按键 / 夜间模式按键：按下即给触感反馈
        btnPrimary.hapticTap()
        btnLap.hapticTap()
        btnReset.hapticTap()
        renderTimer()

        // ---- 倒计时：快捷时长 / 自定义 / 开始暂停 ----
        findViewById<View>(R.id.cdPreset5).setOnClickListener { setCdPreset(5) }
        findViewById<View>(R.id.cdPreset15).setOnClickListener { setCdPreset(15) }
        findViewById<View>(R.id.cdPreset30).setOnClickListener { setCdPreset(30) }
        findViewById<View>(R.id.cdPreset60).setOnClickListener { setCdPreset(60) }
        cdToggle.setOnClickListener { toggleCd() }
        // 直接在数字上上下滑动调时长：上滑增加、下滑减少；分钟区按分钟、秒钟区按秒
        countdownPage.setOnTouchListener { _, e -> handleCdDrag(e) }
        countdownPage.remainMs = cdRemainMs
        updateCdUi()

        Lite.on = Prefs.isLite(this)

        pages = listOf(
            findViewById<View>(R.id.pageCountdown),   // ① 倒计时（在时钟上面）
            findViewById<View>(R.id.pageDigital),     // ② 数字钟（主页面）
            findViewById<View>(R.id.pageTimer),       // ③ 计时器
            findViewById<View>(R.id.pageAnalog)       // ④ 模拟指针
        )

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 系统栏一旦被系统放出来（旋转、返回手势、弹窗收起…），立刻重新隐藏
        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener { vis ->
            if (vis and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                window.decorView.postDelayed({ applyImmersive() }, 250)
            }
        }

        colorA = Prefs.getColorA(this)
        colorB = Prefs.getColorB(this)
        night = Prefs.isNight(this)
        android.util.Log.i(
            "ClockDbg",
            "onCreate colorA=${Integer.toHexString(colorA)} colorB=${Integer.toHexString(colorB)} night=$night"
        )

        buildDots()
        applyColors()
        setNight(night, save = false)
        updateDate()
        updateSeconds()

        index = Prefs.getFace(this).coerceIn(0, pages.size - 1)
        // 夜间模式只在长按取色面板里切换（主页面不再放按钮）
        root.setOnTouchListener { _, e -> handleTouch(e) }
        // 表盘页的日期就在盘面中心，点它也能进日历
        analogClock.onDateClick = {
            Haptics.tap(this)
            startActivity(Intent(this, CalendarActivity::class.java))
        }
        updateChrome()
        updateAnimFlags()
        applyCutoutInset()
        disableForceDark(window.decorView)

        // 尺寸变化（旋转/系统栏变化）后立即按新高度归位，避免页面错位
        pageHost.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            val hChanged = (b - t) != (ob - ot)
            val wChanged = (r - l) != (or - ol)
            if (hChanged || wChanged) {
                animator?.cancel()
                dragOffset = 0f
                layoutPages(0f)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        scheduleRehide()
        handler.post(tick)
        applyNextDrift()                 // 回到前台先换一处位置
        handler.post(driftTick)
        if (cdRunning) handler.post(cdTick)
        accelSensor?.let {
            sensorMgr.registerListener(
                accelListener, it, android.hardware.SensorManager.SENSOR_DELAY_UI
            )
        }
        if (swState == SwState.RUNNING) handler.post(swTick)
        pageHost.post { layoutPages(0f) }
        android.util.Log.i("ClockDbg", "onResume index=$index faceSave=${Prefs.getFace(this)}")
    }

    /**
     * 旋转/系统弹层收起后，MIUI 会把状态栏再放出来。
     * 重新获得焦点时补一次沉浸标志。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        android.util.Log.i("ClockDbg", "configChanged orientation=${newConfig.orientation}")
        // 旋转后系统栏会重新出现，这里马上补一次，并在稍后再补两次兜底
        applyImmersive()
        scheduleRehide()
        pageHost.post {
            animator?.cancel()
            dragOffset = 0f
            layoutPages(0f)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(swTick)
        handler.removeCallbacks(driftTick)
        handler.removeCallbacks(cdTick)
        Haptics.alarmStop(this)
        handler.removeCallbacks(longPressRunnable)
        sensorMgr.unregisterListener(accelListener)
    }

    // ---------------- 左上角：当前时间（只显示 时:分） ----------------
    private var lastNowText = ""

    private fun updateNowText() {
        val c = Calendar.getInstance()
        val s = String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        if (s != lastNowText) {
            lastNowText = s
            nowText.setContent(s)
        }
    }

    // ---------------- 右上角面板：第一页显示「秒」，计时页显示「毫秒」 ----------------
    private var lastSecText = ""

    private fun updateSeconds() {
        if (index != 1) return                 // 只有数字钟页（索引1）显示秒；计时器页由毫秒刷新
        val s = String.format("%02d", Calendar.getInstance().get(Calendar.SECOND))
        if (s != lastSecText) {
            lastSecText = s
            secondsText.setContent(s)
        }
    }

    /** 按当前页切换右上角面板的含义 */
    private fun refreshSecondsPanel() {
        // 页面顺序：0 倒计时 / 1 数字钟 / 2 计时器 / 3 表盘
        when (index) {
            1 -> {
                secondsUnit.text = "秒"
                lastSecText = ""
                updateSeconds()
            }
            2 -> {
                secondsUnit.text = "毫秒"
                secondsText.setContent(String.format("%03d", elapsedMs() % 1000))
            }
        }
    }

    // ---------------- 小计时器（两键状态机） ----------------

    private enum class SwState { IDLE, RUNNING, PAUSED }

    private var swState = SwState.IDLE
    private var swAccum = 0L                 // 已累计毫秒
    private var swStartAt = 0L               // 本段开始时刻（elapsedRealtime）
    private val swTick = object : Runnable {
        override fun run() {
            renderTimer()
            // 显示精度 0.1 秒时 100ms 刷新足够；只有计时页要看毫秒才提到 ~30fps
            if (swState == SwState.RUNNING) handler.postDelayed(this, timerTickMs())
        }
    }

    private fun elapsedMs(): Long =
        swAccum + if (swState == SwState.RUNNING)
            android.os.SystemClock.elapsedRealtime() - swStartAt else 0L

    private fun fmtTimer(ms: Long): String {
        val tenth = ms / 100
        val s = (tenth / 10) % 60
        val m = (tenth / 600) % 100
        return String.format("%02d:%02d.%d", m, s, tenth % 10)
    }

    /** 计时页大字：时:分:秒 */
    private fun fmtBig(ms: Long): String {
        val t = ms / 1000
        return String.format("%02d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60)
    }

    /** 计时页可见且在跑时才需要毫秒级刷新 */
    private fun timerTickMs(): Long =
        if (index == 2 && swState == SwState.RUNNING) 33L else 100L   // 计时器页才需要毫秒级刷新

    /** 只在状态变化时才重建按钮背景：每帧 new 一个 Drawable 会 requestLayout，
     *  60 次/秒的布局抖动会打断点击序列（表现为「点了没反应」或点错键）。 */
    private var lastUiState: SwState? = null

    private fun renderTimer(force: Boolean = false) {
        val ms = elapsedMs()
        // 计时器页上这个 TextView 是 GONE 的，别白白 setText 触发整棵树布局
        if (timerText.visibility == View.VISIBLE) timerText.setContent(fmtTimer(ms))
        timerPage.timeText = fmtBig(ms)                // 计时页大字，内容变了才重绘
        if (index == 2) msView.setMs((ms % 1000).toInt())   // 自绘，不触发布局
        if (!force && swState == lastUiState) return
        lastUiState = swState

        // 主键：开始 → 暂停 → 继续（复位后回到开始）
        btnPrimary.text = when (swState) {
            SwState.IDLE -> "开始"
            SwState.RUNNING -> "暂停"
            SwState.PAUSED -> "继续"
        }
        val on = swState != SwState.IDLE
        // 液态玻璃背景：亮态浓度高（接近主题色填充），暗态是淡淡的玻璃底
        btnPrimary.background = GlassUi.background(
            colorA, colorB, 16f, night, if (on) 0.88f else 0.18f, resources.displayMetrics.density
        )
        btnPrimary.setTextColor(
            if (on) (if (night) 0xFFFFDCDC.toInt() else 0xFF1A0A12.toInt())
            else 0xFFE8E8E8.toInt()
        )
        // 计次：只有正在计时时可用
        val canLap = swState == SwState.RUNNING
        btnLap.isEnabled = canLap
        btnLap.alpha = if (canLap) 1f else 0.32f
        // 复位：没有计时时压暗
        val canReset = swState != SwState.IDLE
        btnReset.isEnabled = canReset
        btnReset.alpha = if (canReset) 1f else 0.32f
    }

    private fun onPrimaryClick() {
        when (swState) {
            SwState.IDLE, SwState.PAUSED -> {
                swStartAt = android.os.SystemClock.elapsedRealtime()
                swState = SwState.RUNNING
                handler.removeCallbacks(swTick)
                handler.post(swTick)
            }
            SwState.RUNNING -> {
                swAccum += android.os.SystemClock.elapsedRealtime() - swStartAt
                swState = SwState.PAUSED
                handler.removeCallbacks(swTick)
                renderTimer()
            }
        }
    }

    private fun resetTimer() {
        swState = SwState.IDLE
        swAccum = 0L
        lastLapAt = 0L
        lapList.clearLaps()          // 复位后计次数据全部清零
        handler.removeCallbacks(swTick)
        renderTimer(force = true)
    }

    // ---------------- 计次 ----------------

    private var lastLapAt = 0L       // 上一次计次时的累计时长，用来算间隔

    private fun onLapClick() {
        if (swState != SwState.RUNNING) return
        val total = elapsedMs()
        val delta = total - lastLapAt
        lastLapAt = total
        lapList.addLap(LapListView.Lap(lapList.lapCount + 1, total, delta))
    }

    // ---------------- 触摸：上下滑切页 / 长按取色 / 点日期开日历 ----------------

    private fun handleTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                dragOffset = 0f
                dragging = false
                longFired = false
                // 一按下就把相邻页放出来并停掉自绘循环：
                // 显示列表在手指还没移动时就录制好，滑动过程纯平移，不掉帧。
                pressed = true
                updateAnimFlags()
                layoutPages(0f)
                handler.postDelayed(longPressRunnable, 480)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (longFired) return true
                val dy = e.y - downY
                val dx = e.x - downX
                if (abs(dy) > 18 || abs(dx) > 18) handler.removeCallbacks(longPressRunnable)
                if (!dragging && abs(dy) > 10 && abs(dy) > abs(dx)) {
                    dragging = true
                    updateAnimFlags()
                }
                if (dragging) {
                    var off = dy
                    if ((index == 0 && off > 0) || (index == pages.size - 1 && off < 0)) off *= 0.30f
                    dragOffset = off
                    layoutPages(dragOffset)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (dragging) {
                    val threshold = pageHost.height * 0.10f
                    when {
                        dragOffset < -threshold && index < pages.size - 1 -> snapTo(index + 1)
                        dragOffset > threshold && index > 0 -> snapTo(index - 1)
                        else -> snapTo(index)
                    }
                } else if (!longFired && isInDateArea(e.x, e.y)) {
                    Haptics.tap(this)      // 点日期进日历
                    startActivity(Intent(this, CalendarActivity::class.java))
                } else if (!longFired) {
                    onSingleOrDoubleTap(e.x, e.y)
                }
                dragging = false
                longFired = false
                pressed = false
                if (animator?.isRunning != true) {
                    layoutPages(0f)
                    updateAnimFlags()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (dragging) snapTo(index)
                dragging = false
                longFired = false
                pressed = false
                if (animator?.isRunning != true) {
                    layoutPages(0f)
                    updateAnimFlags()
                }
                return true
            }
        }
        return true
    }

    // ---------------- 摇一摇：数字像泡在浓液体里轻微晃动 ----------------

    private val sensorMgr by lazy {
        getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
    }
    private val accelSensor by lazy {
        sensorMgr.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
    }
    private val gravityEst = FloatArray(3)
    private var lastNudgeAt = 0L

    private val accelListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val v = e.values
            var linX = 0f
            var linY = 0f
            var sq = 0f
            // 低通估计重力，残差即线性加速度（手机被摇动时的那部分）
            for (i in 0..2) {
                gravityEst[i] = 0.85f * gravityEst[i] + 0.15f * v[i]
                val lin = v[i] - gravityEst[i]
                if (i == 0) linX = lin
                if (i == 1) linY = lin
                sq += lin * lin
            }
            if (sq < 4.0f * 4.0f) return                 // < 4 m/s² 当作普通移动，不动
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastNudgeAt < 220) return          // 220ms 内只给一次冲量
            lastNudgeAt = now
            val k = 8.5f                                 // 每 m/s² 的冲量系数
            // 惯性：手机往右晃，数字往左甩
            bigClock.nudge(
                (-linX * k).coerceIn(-160f, 160f),
                (-linY * k).coerceIn(-160f, 160f)
            )
        }

        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    // ---------------- 双击切换夜间模式 ----------------

    private val doubleTapMs = 400L    // 双击判定窗口（平台默认 300ms，这里放宽一点更好按）
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    /**
     * 轻点（非拖动、非长按、非日期区域）时记录一次；
     * 与上一次轻点间隔 < 320ms 且位置相近 → 判定双击 → 切换夜间模式。
     */
    private fun onSingleOrDoubleTap(x: Float, y: Float) {
        // 点在某个数字上：那一位单独 Q 弹一下（双击逻辑继续往下走，不影响切夜间）
        val digit = bigClock.digitAt(x, y)
        if (digit >= 0) {
            Haptics.tap(this)
            bigClock.tapDigit(digit)
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val tol = 60 * resources.displayMetrics.density
        if (now - lastTapAt < doubleTapMs && abs(x - lastTapX) < tol && abs(y - lastTapY) < tol) {
            lastTapAt = 0L
            Haptics.press(this)          // 双击给重一档反馈
            setNight(!night)
            return
        }
        lastTapAt = now
        lastTapX = x
        lastTapY = y
    }

    private fun isInDateArea(x: Float, y: Float): Boolean {        if (bottomBar.visibility != View.VISIBLE) return false
        val dl = IntArray(2)
        val rl = IntArray(2)
        dateText.getLocationInWindow(dl)
        root.getLocationInWindow(rl)
        val l = dl[0] - rl[0]
        val t = dl[1] - rl[1]
        val r = Rect(l - 30, t - 30, l + dateText.width + 30, t + dateText.height + 30)
        return r.contains(x.toInt(), y.toInt())
    }

    /** 手指按下中 or 归位动画进行中 */
    private var pressed = false
    private fun interacting(): Boolean = pressed || (animator?.isRunning == true)

    /** 自绘页面的动画循环只在「当前页 + 没有在滑动」时开，滑动期间把帧预算让给翻页动画 */
    private fun updateAnimFlags() {
        val busy = interacting()
        bigClock.animateEnabled = !busy && index == 1
        analogClock.animateEnabled = !busy && index == 3
    }

    /** 叠在页面之上、不随页面移动的悬浮件（翻页时要一起淡出，否则会「闪一下」） */
    private val chromeViews by lazy {
        listOf(bottomBar, dotsRow, secondsPanel, msPanel, timerPanel, nowPanel, lapList)
    }
    private var chromeAlphaNow = 1f

    private fun applyChromeAlpha(a: Float) {
        if (abs(a - chromeAlphaNow) < 0.008f) return
        chromeAlphaNow = a
        for (v in chromeViews) v.alpha = a
    }

    private fun layoutPages(offset: Float) {
        val h = pageHost.height.toFloat()
        if (h <= 0f) return
        // 页面滑动多少，悬浮件就淡出多少：滑到底刚好全透明，落位回到 1。
        // 这些控件是 Activity 里的独立图层，不会跟着页面平移，
        // 不淡出就会和正在移动的页面内容叠在一起，看着像闪一下。
        applyChromeAlpha(1f - (abs(offset) / h).coerceIn(0f, 1f))
        val busy = interacting()
        pages.forEachIndexed { i, p ->
            val ty = (i - index) * h + offset
            p.translationY = ty
            // 静止时屏幕外的页设成 INVISIBLE（不绘制、不遍历，最省）；
            // 手指一按下就先把相邻页放出来（busy），让它们的显示列表在还没移动时录好，
            // 滑动过程就只剩纯平移。
            // 注意：**只在可见性真的变化时才 setVisibility** —— 它会使显示列表失效并触发
            // requestLayout，每个 MOVE 事件都调一次必然掉帧。
            val want =
                if (abs(i - index) <= 1 && (busy || abs(ty) < h - 0.5f)) View.VISIBLE
                else View.INVISIBLE
            if (p.visibility != want) p.visibility = want
        }
    }

    private var animator: ValueAnimator? = null

    /**
     * 切到目标页。index 立即更新，再用 offset 做平滑动画；
     * 这样旋转/尺寸变化时 layoutPages(0f) 总能得到正确结果。
     */
    private fun snapTo(target: Int) {
        val h = pageHost.height.toFloat()
        if (h <= 0f) return
        val oldIndex = index
        if (oldIndex == target && abs(dragOffset) < 1f) {
            updateAnimFlags()
            return
        }

        // 页面位置是 (i - index) * h + offset。为了让「切换 index」前后画面不跳，
        // 起点必须满足 (i-target)*h + startOffset = (i-old)*h + dragOffset，
        // 即 startOffset = dragOffset + (target - old) * h。
        // 之前写成了 (old - target)，每次都会从两屏之外开始滑、还会把中间那页扫过屏幕，看起来就是「闪一下」。
        val startOffset = dragOffset + (target - oldIndex) * h
        index = target
        dragOffset = 0f
        Haptics.tap(this)          // 翻页落位时给一下反馈
        updateDots()
        updateChrome()
        updateAnimFlags()
        Prefs.setFace(this, index)
        layoutPages(startOffset)

        if (abs(startOffset) < 1f) {
            // 已经滑到目标位（整页拖动），不用再补动画
            layoutPages(0f)
            updateAnimFlags()
            return
        }

        animator?.cancel()
        animator = ValueAnimator.ofFloat(startOffset, 0f).apply {
            // 快进慢出；时长按剩余距离缩放：只差一点点就快速吸附，拖得远才多走一会儿
            duration = (320L * (abs(startOffset) / h).coerceIn(0.4f, 1f)).toLong()
            // 非线性回弹曲线（easeOutBack）：起步快、末端轻微越过目标位再回弹站住，
            // 与数字卷动 / 点击果冻是同一套手感，不会像匀速直线那样「咔」地停住。
            interpolator = android.view.animation.Interpolator { t ->
                val u = t - 1f
                val c1 = 0.34f
                1f + (c1 + 1f) * u * u * u + c1 * u * u
            }
            addUpdateListener { layoutPages(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    layoutPages(0f)
                    updateAnimFlags()      // 动画结束再把相邻页的自绘循环放掉
                }
            })
            start()
        }
    }

    // ---------------- 状态应用 ----------------

    private fun updateDate() {
        val d = LocalDate.now()
        val wk = listOf("一", "二", "三", "四", "五", "六", "日")[d.dayOfWeek.value - 1]
        val s = "星期$wk ${d.year}年${d.monthValue}月${d.dayOfMonth}日"
        if (dateText.text != s) dateText.text = s
    }

    private fun buildDots() {
        dotsRow.removeAllViews()
        val d = resources.displayMetrics.density
        repeat(pages.size) {
            val v = View(this)
            val size = (7 * d).toInt()
            val lp = LinearLayout.LayoutParams(size, size)
            lp.leftMargin = (5 * d).toInt()
            lp.rightMargin = (5 * d).toInt()
            v.layoutParams = lp
            v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            dotsRow.addView(v)
        }
        updateDots()
    }

    /**
     * 页面：0 数字钟 / 1 计时器 / 2 模拟指针
     *  - 计时器页和表盘页全屏，隐藏底部日期条与页码点
     *  - 右上角面板：数字钟页显示「秒」，计时器页显示「毫秒」
     *  - 左上角：只有计时器页显示「当前时间 时:分」
     *  - 右下角计时器面板：前两页都显示；计时器页只留按键（时间已在大字里）
     */
    // ---------------- 倒计时 ----------------

    private fun setCdPreset(minutes: Int) {
        cdTotalMs = minutes * 60_000L
        cdRemainMs = cdTotalMs
        cdRunning = false
        cdFinished = false
        Haptics.alarmStop(this)
        handler.removeCallbacks(cdTick)
        countdownPage.running = false
        countdownPage.remainMs = cdRemainMs
        updateCdUi()          // 震动由 hapticTap 统一给（两下），这里不再重复
    }

    private fun adjustCd(delta: Long) {
        if (cdRunning) return
        cdTotalMs = (cdTotalMs + delta).coerceIn(5_000L, 99 * 60_000L + 59_000L)
        cdRemainMs = cdTotalMs
        cdFinished = false
        Haptics.alarmStop(this)
        countdownPage.remainMs = cdRemainMs
        Haptics.tap(this)     // 这是拨轮每格的"嗒"一下，不是按键，保留
        updateCdUi()
    }

    private fun toggleCd() {
        if (cdFinished) {                        // 正在响：停止并复位
            Haptics.alarmStop(this)
            cdFinished = false
            cdRemainMs = cdTotalMs
            countdownPage.remainMs = cdRemainMs
            updateCdUi()
            return
        }
        if (cdRunning) {
            cdRemainMs = (cdEndAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            handler.removeCallbacks(cdTick)
            cdRunning = false
            countdownPage.running = false
            countdownPage.remainMs = cdRemainMs
        } else {
            if (cdRemainMs <= 0L) cdRemainMs = cdTotalMs
            cdEndAt = android.os.SystemClock.elapsedRealtime() + cdRemainMs
            cdRunning = true
            countdownPage.running = true
            handler.post(cdTick)
        }
        updateCdUi()
    }

    // 在数字上滑动的状态（滚轮手感：累积像素 → 按格走，松手带惯性）
    private var cdDragZone = -1
    private var cdDragLastY = 0f
    private var cdDragAcc = 0f
    private var cdDragVel = 0f          // px/ms，上滑为正

    private val cdInertia = object : Runnable {
        override fun run() {
            if (cdDragZone >= 0) return                       // 又按下去了，停
            if (kotlin.math.abs(cdDragVel) < INERTIA_MIN) { cdDragVel = 0f; return }
            cdDragAcc += cdDragVel * 16f
            applyCdSteps()
            cdDragVel *= INERTIA_DECAY
            handler.postDelayed(this, 16L)
        }
    }

    /** 把累积的像素按「格」换算成时长变化 */
    private fun applyCdSteps() {
        val step = if (cdDragZone == 1) SEC_STEP_PX else MIN_STEP_PX
        // 惯性阶段 cdDragZone 已复位，用最近一次的区
        val unit = if (lastDragZone == 1) 1_000L else 60_000L
        val sPx = if (lastDragZone == 1) SEC_STEP_PX else MIN_STEP_PX
        while (cdDragAcc >= sPx) {
            cdDragAcc -= sPx
            adjustCd(unit)
        }
        while (cdDragAcc <= -sPx) {
            cdDragAcc += sPx
            adjustCd(-unit)
        }
    }

    private var lastDragZone = 0

    /** 返回 true 表示消费了这次触摸（页面就不会翻页） */
    private fun handleCdDrag(e: android.view.MotionEvent): Boolean {
        if (cdRunning) return false
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                val z = countdownPage.zoneAt(e.x, e.y)
                if (z < 0) return false
                handler.removeCallbacks(cdInertia)
                cdDragVel = 0f
                cdDragZone = z
                lastDragZone = z
                cdDragLastY = e.y
                cdDragAcc = 0f
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (cdDragZone < 0) return false
                val dy = cdDragLastY - e.y              // 上滑为正
                cdDragLastY = e.y
                cdDragAcc += dy
                // 记录速度（每帧平滑一点，避免抖动被放大）
                cdDragVel = cdDragVel * 0.6f + (dy / 16f) * 0.4f
                applyCdSteps()
                return true
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                val was = cdDragZone >= 0
                cdDragZone = -1
                if (was && kotlin.math.abs(cdDragVel) > 0.05f) {
                    handler.postDelayed(cdInertia, 16L)   // 松手继续滑一会儿
                } else {
                    cdDragVel = 0f
                }
                return was
            }
        }
        return cdDragZone >= 0
    }

    private var lastCdBtn = ""

    private fun updateCdUi() {
        // 只在文案真的变了才 setText：每 200ms 无条件 setText 会触发 requestLayout，
        // 造成周期性掉帧（这就是之前 90/95 分位 25~31ms 尖峰的来源）。
        val label = when {
            cdFinished -> "停止"
            cdRunning -> "暂停"
            cdRemainMs in 1 until cdTotalMs -> "继续"
            else -> "开始"
        }
        if (label != lastCdBtn) {
            lastCdBtn = label
            cdToggle.text = label
        }
        val hintOn = index == 0 && !cdRunning
        val want = if (hintOn) View.VISIBLE else View.GONE
        if (cdHint.visibility != want) cdHint.visibility = want
    }

    private fun updateChrome() {
        // 日期条 / 页码点只属于数字钟页（夜间极简时也隐藏）
        val nightMinimal = night && index == 1
        val isClock = index == 1
        val isCd = index == 0
        val isTimer = index == 2
        bottomBar.visibility = if (isClock && !nightMinimal) View.VISIBLE else View.GONE
        dotsRow.visibility = if (isClock && !nightMinimal) View.VISIBLE else View.GONE
        val withTimerUi = (isClock && !nightMinimal) || isTimer
        secondsPanel.visibility =
            if (isClock && !nightMinimal) View.VISIBLE else View.INVISIBLE
        msPanel.visibility = if (isTimer) View.VISIBLE else View.INVISIBLE
        timerPanel.visibility = if (withTimerUi) View.VISIBLE else View.INVISIBLE
        timerText.visibility = if (isTimer) View.GONE else View.VISIBLE
        nowPanel.visibility = if (isTimer || isCd) View.VISIBLE else View.INVISIBLE
        lapList.visibility = if (isTimer) View.VISIBLE else View.INVISIBLE
        // 倒计时页自己的悬浮控件
        cdPresets.visibility = if (isCd) View.VISIBLE else View.GONE
        cdHint.visibility = if (isCd && !cdRunning) View.VISIBLE else View.GONE
        cdToggle.visibility = if (isCd) View.VISIBLE else View.GONE
        refreshSecondsPanel()
        updateNowText()
        // 在计时器页上，这个小面板的数字是 GONE 的（那边有自己的大字），renderTimer 会跳过它；
        // 切回主页面时必须补一次刷新，否则会停在复位前的旧值。
        if (timerText.visibility == View.VISIBLE) timerText.setContent(fmtTimer(elapsedMs()))
    }

    private fun updateDots() {
        val active = if (night) BigClockView.NIGHT_TEXT else colorA
        for (i in 0 until dotsRow.childCount) {
            val g = dotsRow.getChildAt(i).background as GradientDrawable
            g.setColor(if (i == index) active else 0x4DFFFFFF)
        }
    }

    /** 倒计时页的悬浮控件跟随主题色（夜间转暗红） */
    private fun tintCdControls() {
        // 夜间统一用浅粉：深红玻璃底上才读得清，也和「开始」键一致
        val c = if (night) 0xFFFFDCDC.toInt() else colorA
        for (id in intArrayOf(
            R.id.cdPreset5, R.id.cdPreset15, R.id.cdPreset30, R.id.cdPreset60, R.id.cdToggle
        )) {
            (findViewById<View>(id) as? TextView)?.setTextColor(c)
        }
        (findViewById<View>(R.id.cdHint) as? TextView)?.setTextColor(
            if (night) 0xFF6B1010.toInt() else (c and 0x00FFFFFF) or (0x99 shl 24)
        )
    }

    private fun applyColors() {
        bigClock.colorA = colorA
        bigClock.colorB = colorB
        analogClock.colorA = colorA
        timerPage.colorA = colorA
        timerPage.colorB = colorB
        secondsText.color = if (night) 0xFF6B1010.toInt() else colorA
        nowText.color = if (night) 0xFFB03030.toInt() else 0xFFF5F5F5.toInt()
        lapList.accent = colorA
        lapList.nightMode = night
        msView.accent = colorA
        msView.nightMode = night
        // 各按键的液态玻璃底（跟着主题色重建）
        val r = 16f
        val dens = resources.displayMetrics.density
        // 按键文字：夜间统一浅粉（深红底上可读），白天维持原色
        val btnText = if (night) 0xFFFFDCDC.toInt() else 0xFFE8E8E8.toInt()
        btnLap.setTextColor(btnText)
        btnReset.setTextColor(btnText)
        btnLap.background = GlassUi.background(colorA, colorB, r, night, 0.18f, dens)
        btnReset.background = GlassUi.background(colorA, colorB, r, night, 0.18f, dens)
        for (id in intArrayOf(R.id.cdPreset5, R.id.cdPreset15, R.id.cdPreset30, R.id.cdPreset60)) {
            (findViewById<View>(id) as? TextView)?.background =
                GlassUi.background(colorA, colorB, r, night, 0.16f, dens)
        }
        cdToggle.background = GlassUi.background(colorA, colorB, r, night, 0.20f, dens)

        // 倒计时页：数字与控件都跟随主题色
        countdownPage.colorA = colorA
        countdownPage.colorB = colorB
        countdownPage.nightMode = night
        tintCdControls()
        renderTimer(force = true)
        updateDots()
    }

    private fun setNight(on: Boolean, save: Boolean = true) {
        night = on
        bigClock.nightMode = on
        analogClock.nightMode = on
        timerPage.nightMode = on
        secondsText.color = if (on) 0xFF6B1010.toInt() else colorA
        dateText.setTextColor(if (on) 0xFF8A3A3A.toInt() else 0xFFA8A8A8.toInt())
        timerText.color = if (on) 0xFFB03030.toInt() else 0xFFF5F5F5.toInt()
        nowText.color = if (on) 0xFFB03030.toInt() else 0xFFF5F5F5.toInt()
        lapList.nightMode = on
        countdownPage.nightMode = on
        tintCdControls()
        msView.nightMode = on
        // 夜间模式不再强制改屏幕亮度（-1 = 跟随系统）。
        // 原因：固定压到 6% 时，暗光环境里系统实际只有 2% 反而会被「调亮」；
        // 而亮环境下 6% 又压得过暗。ROM 还不一定给读真实亮度的接口，
        // 所以这里只做「界面转暗红」，亮度交给系统。
        val lp = window.attributes
        lp.screenBrightness = -1f
        window.attributes = lp
        renderTimer(force = true)
        updateDots()
        updateChrome()          // 夜间模式要顺带收起主页面上的其它部件
        applyColors()           // ★ 关键：重建按键的玻璃底色（之前只在启动时调用，
                                //   所以切夜间后按键不变红，杀进程重开才正常）
        pickerRefresh?.invoke() // 面板开着时同步刷新滑杆/开关配色
        if (save) Prefs.setNight(this, on)
    }

    /**
     * 让右上角控件避开刘海：横屏时刘海会落在屏幕左或右侧，
     * 按系统给出的刘海安全区把右上/右下两块整体往内推。
     */
    private fun applyCutoutInset() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return
        val d = resources.displayMetrics.density
        val baseTop = (16 * d).toInt()
        val baseBottom = (20 * d).toInt()
        val baseLeft = (16 * d).toInt()
        root.setOnApplyWindowInsetsListener { _, insets ->
            val cut = insets.displayCutout
            val safeEnd = cut?.safeInsetRight ?: 0
            val safeStart = cut?.safeInsetLeft ?: 0
            setEndMargin(topRightBar, baseTop + safeEnd)
            setEndMargin(timerPanel, baseBottom + safeEnd)
            setStartMargin(nowPanel, baseLeft + safeStart)   // 左上角也要让开刘海
            setStartMargin(lapList, (20 * d).toInt() + safeStart)
            insets
        }
    }

    private fun setEndMargin(v: View, m: Int) {
        val lp = v.layoutParams as FrameLayout.LayoutParams
        if (lp.rightMargin != m) {
            lp.rightMargin = m
            v.layoutParams = lp
        }
    }

    private fun setStartMargin(v: View, m: Int) {
        val lp = v.layoutParams as FrameLayout.LayoutParams
        if (lp.leftMargin != m) {
            lp.leftMargin = m
            v.layoutParams = lp
        }
    }

    /**
     * 夜间模式的目标亮度：上限 6%，但**绝不高于当前实际亮度**（只降不升）。
     * 之前写死 6%：在暗光环境里系统实际亮度只有 2% 时，一开夜间模式反而变亮近 3 倍。
     * API 31+ 直接读 Display.brightnessInfo（真实生效亮度，含自动亮度的结果）；
     * 读不到再退回设置里的亮度值。
     */
    /**
     * 关闭整棵视图树的「强制深色」。系统深色模式会给浅色应用做自动配色转换，
     * 本应用是纯黑自绘 UI，被转换后会出现「指针被压黑、主题色被改色」这类问题。
     * 主题里已声明 android:forceDarkAllowed=false，这里再对每个子 View 兜底设一遍。
     */
    private fun disableForceDark(v: View) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        v.isForceDarkAllowed = false
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) disableForceDark(v.getChildAt(i))
        }
    }

    /** 沉浸式标志位（横屏全屏，隐藏状态栏与导航栏） */
    private val immersiveFlags = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)

    private fun applyImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = immersiveFlags
    }

    /** 旋转/切页后连续补几次，防止 ROM 把系统栏弹回来 */
    private val rehide = Runnable { applyImmersive() }
    private fun scheduleRehide() {
        handler.removeCallbacks(rehide)
        handler.post(rehide)
        handler.postDelayed(rehide, 350)
        handler.postDelayed(rehide, 1100)
    }

    // ---------------- 取色器 ----------------

    private val presets = intArrayOf(
        0xFFFF5FAE.toInt(), 0xFFA855F7.toInt(), 0xFF7C6BFF.toInt(), 0xFF3B82F6.toInt(),
        0xFF22D3EE.toInt(), 0xFF10B981.toInt(), 0xFFA3E635.toInt(), 0xFFFACC15.toInt(),
        0xFFFB923C.toInt(), 0xFFEF4444.toInt(), 0xFFF472B6.toInt(), 0xFFF5F5F5.toInt()
    )

    private fun showColorPicker() {
        val dlg = Dialog(this)
        val dens = resources.displayMetrics.density
        fun dp(v: Int) = (v * dens).toInt()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = 22f * dens
                setColor(0xFF1C1C1E.toInt())
            }
        }

        box.addView(TextView(this).apply {
            text = "时钟颜色"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })

        // 预览条与色块行占满整幅宽度；滑杆在左栏，开关/按钮在右栏
        val columns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(30), 0, 0, 0)
        }
        columns.addView(leftCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f))
        columns.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val preview = View(this)
        box.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(34)
        ).apply { topMargin = dp(10) })

        // ---- HSV 状态与画刷 ----
        val hsv = FloatArray(3)
        Color.colorToHSV(colorA, hsv)
        var hue = hsv[0]
        var sat = hsv[1]
        var value = hsv[2]

        // 三根滑杆用自绘的粗体样式（粗轨道 + 大圆头，跟随主题色）
        val seekH = SliderView(this)
        val seekS = SliderView(this)
        val seekV = SliderView(this)

        fun tintSeek(sb: SliderView) {
            sb.accent = colorA
            sb.nightMode = night
        }

        // 开关配色跟随主题色（否则是 MIUI 默认的橙色）；色值会变，所以放进列表随时刷新
        val themeSwitches = ArrayList<Switch>()

        fun themeSwitch(sw: Switch) {
            val on = if (night) 0xFFB03030.toInt() else colorA
            sw.thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(on, 0xFF9A9A9E.toInt())
            )
            sw.trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf((on and 0x00FFFFFF) or (0x66 shl 24), 0xFF3A3A3E.toInt())
            )
        }

        fun drawPreview() {
            preview.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(colorA, colorB)
            ).apply { cornerRadius = 14f * dens }
        }

        fun applyHsv() {
            colorA = Color.HSVToColor(floatArrayOf(hue, sat, value))
            // 渐变终点：色相左移 55°（粉→紫 那种搭配），稍暗一点
            val endH = (hue - 55f + 360f) % 360f
            colorB = Color.HSVToColor(
                floatArrayOf(endH, min(1f, sat * 1.05f), min(1f, value * 0.90f))
            )
            pickerRefresh?.invoke()
            applyColors()
        }

        fun syncSeek() {
            seekH.setValue(hue.coerceIn(0f, 360f), 360f)
            seekS.setValue((sat * 100f).coerceIn(0f, 100f), 100f)
            seekV.setValue((value * 100f).coerceIn(0f, 100f), 100f)
            listOf(seekH, seekS, seekV).forEach { tintSeek(it) }
        }

        drawPreview()

        // 主题色或夜间模式一变（含面板内直接切夜间），滑杆与开关都要重新上色
        pickerRefresh = {
            listOf(seekH, seekS, seekV).forEach { tintSeek(it) }
            themeSwitches.forEach { themeSwitch(it) }
            drawPreview()
        }

        // ---- 预置色块 ----
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val swatchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEach { c ->
            val sw = View(this)
            val size = dp(38)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.rightMargin = dp(12)
            sw.layoutParams = lp
            sw.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
            }
            val arr = FloatArray(3)
            Color.colorToHSV(c, arr)
            sw.setOnClickListener {
                hue = arr[0]; sat = arr[1]; value = arr[2]
                syncSeek()
                applyHsv()
            }
            sw.hapticTap()
            swatchRow.addView(sw)
        }
        scroll.addView(swatchRow, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        box.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        // ---- 三根滑杆 ----
        data class SliderRow(val label: TextView, val bar: SliderView, val apply: (Float) -> Unit)

        val rows = listOf(
            SliderRow(TextView(this).apply {
                text = "色相"; setTextColor(0xFFB9B9B9.toInt()); textSize = 13f
            }, seekH) { p -> hue = p * 360f },
            SliderRow(TextView(this).apply {
                text = "饱和度"; setTextColor(0xFFB9B9B9.toInt()); textSize = 13f
            }, seekS) { p -> sat = p },
            SliderRow(TextView(this).apply {
                text = "亮度"; setTextColor(0xFFB9B9B9.toInt()); textSize = 13f
            }, seekV) { p -> value = p }
        )

        rows.forEach { (label, bar, setter) ->
            leftCol.addView(label, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
            leftCol.addView(bar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
            bar.onChange = { p ->
                setter(p)
                applyHsv()
            }
            bar.onStart = { Haptics.tap(this) }
        }

        // ---- 夜间模式开关 ----
        val swNight = Switch(this).apply {
            text = "夜间模式"
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 14f
            isChecked = night
            setOnCheckedChangeListener { _, b -> setNight(b) }
            hapticTap()
        }
        themeSwitches.add(swNight)
        themeSwitch(swNight)
        rightCol.addView(swNight, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        rightCol.addView(TextView(this).apply {
            text = "开启后文字变暗红，主页面只留中间时分"
            setTextColor(0xFF7A7A7E.toInt())
            textSize = 11f
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        // ---- 简化动效与玻璃质感 ----
        val swLite = Switch(this).apply {
            text = "简化动效"
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 14f
            isChecked = Prefs.isLite(this@ClockActivity)
            setOnCheckedChangeListener { _, b ->
                Prefs.setLite(this@ClockActivity, b)
                Lite.on = b
                // 立刻重建按键底色与数字缓存，让改动马上生效
                applyColors()
                bigClock.invalidate()
                timerPage.invalidate()
                countdownPage.invalidate()
            }
            hapticTap()
        }
        themeSwitches.add(swLite)
        themeSwitch(swLite)
        rightCol.addView(swLite, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        rightCol.addView(TextView(this).apply {
            text = "关闭玻璃分层、卷动与按键回弹，更省电"
            setTextColor(0xFF7A7A7E.toInt())
            textSize = 11f
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        // ---- 触感反馈开关 ----
        val swHaptic = Switch(this).apply {
            text = "按键震动"
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 14f
            isChecked = Prefs.isHaptics(this@ClockActivity)
            setOnCheckedChangeListener { _, b ->
                Prefs.setHaptics(this@ClockActivity, b)
                // 打开时立刻震一下，方便确认已经生效
                if (b) Haptics.press(this@ClockActivity)
            }
            hapticTap()
        }
        themeSwitches.add(swHaptic)
        themeSwitch(swHaptic)
        rightCol.addView(swHaptic, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // ---- 完成 ----
        rightCol.addView(TextView(this).apply {
            text = "完成"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            background = GlassUi.background(colorA, colorB, 22f, night, 0.45f, dens)
            hapticTap()
            setOnClickListener { dlg.dismiss() }
            hapticTap()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
        ).apply { topMargin = dp(20) })

        box.addView(columns, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        syncSeek()

        // 内容可能比屏幕高（横屏尤其），套一层 ScrollView，避免上下被裁掉
        val scroller = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(box, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        dlg.setContentView(scroller)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.setOnDismissListener {
            pickerRefresh = null
            Prefs.setColorA(this, colorA)
            Prefs.setColorB(this, colorB)
            scheduleRehide()
        }
        dlg.show()
        // 注意：必须在 show() 之后再设尺寸，否则会被 show 重置，导致内容超出屏幕被裁
        dlg.window?.setLayout(
            min((resources.displayMetrics.widthPixels * 0.88f).toInt(),
                (resources.displayMetrics.heightPixels * 2.1f).toInt()),
            (resources.displayMetrics.heightPixels * 0.92f).toInt()
        )
        dlg.hapticButtons()
        // 弹窗也是独立窗口，需要单独隐藏系统栏
        @Suppress("DEPRECATION")
        dlg.window?.decorView?.systemUiVisibility = immersiveFlags
    }
}
