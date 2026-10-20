package com.xiaomi8.standbycat

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.DatePicker
import android.widget.EditText
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.nlf.calendar.Solar
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Calendar
import java.util.Locale

/**
 * 日历页：
 *  - 左：月历（公历 + 农历 + 节气 + 节日 + 法定节假日/调休标记）
 *  - 右：选中日期详情（农历干支生肖、节日、倒数日、系统日历日程）
 *
 * 农历与节假日数据来自 cn.6tail:lunar（MIT），含每年官方公布的调休安排。
 * 系统日程读取 CalendarProvider 并注册 ContentObserver，日程有变动会自动刷新。
 */
class CalendarActivity : Activity() {

    private lateinit var grid: GridView
    private lateinit var monthTitle: TextView
    private lateinit var bigDay: TextView
    private lateinit var monthLine: TextView
    private lateinit var lunarLine: TextView
    private lateinit var festivalLine: TextView
    private lateinit var relLine: TextView
    private lateinit var detailList: LinearLayout

    private var showMonth = YearMonth.now()
    private var selected: LocalDate = LocalDate.now()
    private val today: LocalDate = LocalDate.now()

    // ---------------- 配色：全部由主题色推导，页面里不再写死粉/其他色 ----------------
    /** 主色：跟随长按取色面板里选的时钟颜色；夜间模式统一走暗红（与主页面一致） */
    private var accent = Prefs.DEFAULT_COLOR_A
    /** 周末 / 法定休息日：主色混白得出的柔和色，保证「改主题色后不再残留粉色」 */
    private var accentSoft = 0xFFFF9EC0.toInt()
    private var onAccent = Color.BLACK               // 主色底上的文字
    private var textHead = 0xFFF5F5F5.toInt()        // 月份大标题 / 大号日期
    private var textBody = 0xFFCFCFCF.toInt()
    private var textMain = 0xFFEDEDED.toInt()        // 月历里的普通日期
    private var textDim = 0xFF3C3C40.toInt()         // 非本月的日期
    private var textSub = 0xFF8A8A8A.toInt()         // 农历 / 次要信息
    private var textLabel = 0xFFB9B9B9.toInt()       // 小节标题
    private var textFaint = 0xFF6E6E6E.toInt()       // 提示语
    private var chipText = 0xFFE8E8E8.toInt()        // 胶囊按钮文字
    private var btnText = 0xFFE8E8E8.toInt()         // 返回 / 换月 / 今天

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(v: Int, sh: Int) = (v shr sh) and 0xFF
        return Color.rgb(
            (ch(a, 16) * (1 - t) + ch(b, 16) * t).toInt(),
            (ch(a, 8) * (1 - t) + ch(b, 8) * t).toInt(),
            (ch(a, 0) * (1 - t) + ch(b, 0) * t).toInt()
        )
    }

    /**
     * 按当前主题（取色面板的颜色 + 夜间模式）刷新整页配色。
     * 在 onCreate 和每次 onResume 都跑一遍：改完主题色再回到日历页立刻生效。
     */
    private fun applyTheme() {
        val night = Prefs.isNight(this)
        val base = Prefs.getColorA(this)
        accent = if (night) 0xFFB03030.toInt() else base
        accentSoft = if (night) 0xFF8A3A3A.toInt() else blend(base, Color.WHITE, 0.42f)
        onAccent = if (night) 0xFFFFDADA.toInt() else Color.BLACK

        textHead = if (night) 0xFFB03030.toInt() else 0xFFF5F5F5.toInt()
        textBody = if (night) 0xFF9A4444.toInt() else 0xFFCFCFCF.toInt()
        textMain = if (night) 0xFF9A4444.toInt() else 0xFFEDEDED.toInt()
        textDim = if (night) 0xFF3E1414.toInt() else 0xFF3C3C40.toInt()
        textSub = if (night) 0xFF7A3434.toInt() else 0xFF8A8A8A.toInt()
        textLabel = if (night) 0xFF8A3A3A.toInt() else 0xFFB9B9B9.toInt()
        textFaint = if (night) 0xFF6B2828.toInt() else 0xFF6E6E6E.toInt()
        chipText = if (night) 0xFFB03030.toInt() else 0xFFE8E8E8.toInt()
        btnText = if (night) 0xFF8A3A3A.toInt() else 0xFFE8E8E8.toInt()

        // 布局里写死的颜色在这里统一覆盖
        findViewById<TextView>(R.id.monthTitle).setTextColor(textHead)
        bigDay.setTextColor(accent)
        monthLine.setTextColor(textBody)
        lunarLine.setTextColor(textSub)
        festivalLine.setTextColor(accent)
        relLine.setTextColor(accent)
        listOf(R.id.btnBack, R.id.btnPrev, R.id.btnNext, R.id.btnToday).forEach {
            findViewById<TextView>(it).setTextColor(btnText)
        }
        val weekdayRow = findViewById<LinearLayout>(R.id.weekdayRow)
        for (i in 0 until weekdayRow.childCount) {
            (weekdayRow.getChildAt(i) as TextView).setTextColor(
                if (i == 0 || i == 6) accentSoft else textSub
            )
        }
    }

    // ---------------- 系统日历 ----------------
    private data class CalEvent(val title: String, val timeText: String)
    private val eventsByDay = HashMap<LocalDate, MutableList<CalEvent>>()
    private var observer: ContentObserver? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---------------- 倒数日 ----------------
    private data class Countdown(val name: String, val date: LocalDate)
    private val cdPrefs by lazy { getSharedPreferences("countdowns", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.decorView.isForceDarkAllowed = false
        }
        setContentView(R.layout.activity_calendar)

        monthTitle = findViewById(R.id.monthTitle)
        grid = findViewById(R.id.calendarGrid)
        bigDay = findViewById(R.id.bigDay)
        monthLine = findViewById(R.id.monthLine)
        lunarLine = findViewById(R.id.lunarLine)
        festivalLine = findViewById(R.id.festivalLine)
        relLine = findViewById(R.id.relLine)
        detailList = findViewById(R.id.detailList)

        // 导航键：液态玻璃 + 按下回弹
        for (id in intArrayOf(R.id.btnBack, R.id.btnPrev, R.id.btnNext, R.id.btnToday)) {
            findViewById<View>(id)?.let {
                GlassUi.pressFx(it)
                (it as? TextView)?.background =
                    GlassUi.background(
                        Prefs.getColorA(this), Prefs.getColorB(this), 18f, false, 0.22f,
                        resources.displayMetrics.density
                    )
            }
        }
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnBack).hapticTap()

        val weekdayRow = findViewById<LinearLayout>(R.id.weekdayRow)
        val names = listOf("日", "一", "二", "三", "四", "五", "六")
        names.forEachIndexed { i, n ->
            val tv = TextView(this).apply {
                text = n
                gravity = Gravity.CENTER
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (i == 0 || i == 6) accentSoft else textSub)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            weekdayRow.addView(tv)
        }

        grid.adapter = object : BaseAdapter() {
            override fun getCount() = 42
            override fun getItem(position: Int) = cellDate(position)
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return makeCell(position, convertView)
            }
        }

        findViewById<TextView>(R.id.btnPrev).setOnClickListener {
            showMonth = showMonth.minusMonths(1); loadEvents(); refresh()
        }
        findViewById<TextView>(R.id.btnNext).setOnClickListener {
            showMonth = showMonth.plusMonths(1); loadEvents(); refresh()
        }
        findViewById<TextView>(R.id.btnToday).setOnClickListener {
            showMonth = YearMonth.now(); selected = today; loadEvents(); refresh()
        }
        // 上/下月、今天：按下即给反馈
        listOf(R.id.btnPrev, R.id.btnNext, R.id.btnToday).forEach {
            findViewById<View>(it).hapticTap()
        }

        applyTheme()
        refresh()

        // 刘海补偿
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            // 关闭整棵树的强制深色（本应用是纯黑自绘 UI，不接受系统配色转换）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            fun off(v: View) {
                v.isForceDarkAllowed = false
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) off(v.getChildAt(i))
            }
            off(window.decorView)
        }

        val root = findViewById<View>(R.id.calendarRoot)
            val base = (26 * resources.displayMetrics.density).toInt()
            root.setOnApplyWindowInsetsListener { v, insets ->
                val cut = insets.displayCutout
                val l = base + (cut?.safeInsetLeft ?: 0)
                val r = base + (cut?.safeInsetRight ?: 0)
                if (v.paddingLeft != l || v.paddingRight != r) {
                    v.setPadding(l, v.paddingTop, r, v.paddingBottom)
                }
                insets
            }
        }

        // 系统日历：申请权限 + 注册观察者（日程有变动自动刷新）
        if (hasCalPermission()) {
            loadEvents()
            refresh()
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQ_CAL)
        }
        observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                mainHandler.post {
                    loadEvents()
                    (grid.adapter as BaseAdapter).notifyDataSetChanged()
                    renderDetail()
                }
            }
        }
        contentResolver.registerContentObserver(
            CalendarContract.Instances.CONTENT_URI, true, observer!!
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        observer?.let { contentResolver.unregisterContentObserver(it) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAL) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadEvents()
            }
            renderDetail()
        }
    }

    private fun hasCalPermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    // ---------------- 农历 / 节日 / 节假日 ----------------

    private fun lunarOf(d: LocalDate) = Solar.fromYmd(d.year, d.monthValue, d.dayOfMonth).lunar

    /** 格子第二行：优先节日/节气，否则农历日 */
    private fun dayFoot(d: LocalDate): Pair<String, Boolean> {
        val solar = Solar.fromYmd(d.year, d.monthValue, d.dayOfMonth)
        val lunar = solar.lunar
        val fests = ArrayList<String>()
        fests.addAll(solar.festivals)
        fests.addAll(lunar.festivals)
        val term = lunar.jieQi
        if (term.isNotBlank()) fests.add(term)
        if (fests.isNotEmpty()) return fests.joinToString("·") to true
        // 初一显示农历月名，其余显示农历日
        val foot = if (lunar.day == 1)
            (if (lunar.month < 0) "闰" else "") + lunar.monthInChinese + "月"
        else lunar.dayInChinese
        return foot to false
    }

    /** 法定节假日：true=休，false=调休上班，null=普通 */
    private fun holidayState(d: LocalDate): Boolean? {
        val h = com.nlf.calendar.util.HolidayUtil.getHoliday(d.year, d.monthValue, d.dayOfMonth) ?: return null
        return !h.isWork
    }

    private fun restDay(d: LocalDate): Boolean {
        if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) return true
        return holidayState(d) == true
    }

    // ---------------- 月历 ----------------

    private fun cellDate(position: Int): LocalDate {
        val first = showMonth.atDay(1)
        val offset = first.dayOfWeek.value % 7 // 周日开头
        return first.plusDays((position - offset).toLong())
    }

    private fun makeCell(position: Int, convertView: View?): View {
        val d = cellDate(position)
        val density = resources.displayMetrics.density
        val cellH = ((grid.height - 5 * 2 * density) / 6f).toInt().coerceAtLeast((44 * density).toInt())

        val cell = (convertView as? LinearLayout) ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val t1 = TextView(this@CalendarActivity).apply { gravity = Gravity.CENTER }
            val t2 = TextView(this@CalendarActivity).apply {
                gravity = Gravity.CENTER
                textSize = 9.5f
            }
            addView(t1); addView(t2)
        }
        val t1 = cell.getChildAt(0) as TextView
        val t2 = cell.getChildAt(1) as TextView
        cell.layoutParams = android.widget.AbsListView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, cellH
        )

        val isThisMonth = d.month == showMonth.month
        val isRest = restDay(d)
        val (foot, isFest) = dayFoot(d)

        t1.text = d.dayOfMonth.toString()
        t1.textSize = 16f
        t1.setTypeface(
            Typeface.create("sans-serif", if (d == today) Typeface.BOLD else Typeface.NORMAL)
        )
        t1.setTextColor(
            when {
                d == today -> onAccent
                d == selected -> accent
                !isThisMonth -> textDim
                isRest || isFest -> accentSoft
                else -> textMain
            }
        )

        t2.text = foot
        t2.setTextColor(
            when {
                !isThisMonth -> textDim
                isFest -> accent
                d == selected -> accent
                else -> textSub
            }
        )

        cell.background = when {
            // 今天：实心主色（用同色渐变而非纯色，避免被系统强制深色压暗）
            d == today -> GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(accent, accent)
            ).apply { shape = GradientDrawable.OVAL }
            // 选中日期：半透明主色填充，不用描边圈
            d == selected -> GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf((accent and 0x00FFFFFF) or (0x3D shl 24),
                           (accent and 0x00FFFFFF) or (0x3D shl 24))
            ).apply { shape = GradientDrawable.OVAL }
            else -> null
        }

        GlassUi.pressFx(cell)
        cell.setOnClickListener {
            selected = d
            (grid.adapter as BaseAdapter).notifyDataSetChanged()
            renderDetail()
        }
        cell.hapticTap()
        return cell
    }

    private fun refresh() {
        monthTitle.text = "${showMonth.year}年${showMonth.monthValue}月"
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        renderDetail()
    }

    // ---------------- 右侧详情 ----------------

    private fun renderDetail() {
        val d = selected
        val weekIdx = d.get(WeekFields.of(Locale.CHINA).weekOfWeekBasedYear())
        val diff = ChronoUnit.DAYS.between(today, d)
        val rel = when {
            diff == 0L -> "就是今天"
            diff > 0 -> "${diff} 天后"
            else -> "${-diff} 天前"
        }
        val weekNames = listOf("一", "二", "三", "四", "五", "六", "日")
        bigDay.text = d.dayOfMonth.toString()
        monthLine.text = "${d.year}年${d.monthValue}月 · 星期${weekNames[d.dayOfWeek.value - 1]}" +
            if (restDay(d)) "  ·  休" else ""

        val solar = Solar.fromYmd(d.year, d.monthValue, d.dayOfMonth)
        val lunar = solar.lunar
        val monCn = lunar.monthInChinese.let {
            if (lunar.month < 0 && !it.startsWith("闰")) "闰$it" else it
        }
        lunarLine.text = "农历${monCn}月${lunar.dayInChinese} · ${lunar.yearInGanZhi}(${lunar.yearShengXiao})年"

        val fests = ArrayList<String>()
        fests.addAll(solar.festivals)
        fests.addAll(lunar.festivals)
        val term = lunar.jieQi
        if (term.isNotBlank()) fests.add(term)
        holidayState(d)?.let { fests.add(if (it) "放假" else "调休上班") }

        if (fests.isEmpty()) {
            festivalLine.visibility = View.GONE
        } else {
            festivalLine.visibility = View.VISIBLE
            festivalLine.text = fests.joinToString(" · ")
        }
        relLine.text = rel

        renderDetailList(d)
    }

    /** 右下：倒数日 + 系统日程 */
    private fun renderDetailList(d: LocalDate) {
        detailList.removeAllViews()
        val dDip = resources.displayMetrics.density

        fun header(title: String, extra: View? = null): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (10 * dDip).toInt(), 0, (6 * dDip).toInt())
            }
            val tv = TextView(this).apply {
                text = title
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textLabel)
            }
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            extra?.let { row.addView(it) }
            return row
        }

        fun chip(text: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(chipText)
                // 统一液态玻璃底 + 按下回弹：所有 chip 都走这里，保证手感一致
                background = GlassUi.background(
                    Prefs.getColorA(this@CalendarActivity), Prefs.getColorB(this@CalendarActivity),
                    12f, false, 0.18f, dDip
                )
                GlassUi.pressFx(this)
                setOnClickListener { onClick() }
                hapticTap()
            }
        }

        // ---------- 倒数日 ----------
        val cds = loadCountdowns().sortedBy { it.date }
        val addBtn = chip("＋") { showAddCountdownDialog() }
        addBtn.layoutParams = LinearLayout.LayoutParams((30 * dDip).toInt(), (30 * dDip).toInt())
        detailList.addView(header("倒数日", addBtn))

        if (cds.isEmpty()) {
            detailList.addView(
                smallNote("还没有倒数日，点右上角「＋」添加")
            )
        } else {
            for (c in cds) {
                val n = ChronoUnit.DAYS.between(today, c.date)
                val text = when {
                    n == 0L -> "今天是「${c.name}」"
                    n > 0 -> "「${c.name}」还有 $n 天"
                    else -> "「${c.name}」已过 ${-n} 天"
                }
                val row = TextView(this).apply {
                    this.text = text
                    textSize = 14.5f
                    setTextColor(
                        if (n in 0..7) accent else textBody
                    )
                    setPadding((8 * dDip).toInt(), (5 * dDip).toInt(), (8 * dDip).toInt(), (5 * dDip).toInt())
                    background = GradientDrawable().apply {
                        cornerRadius = 10f * dDip
                        setColor(0x14FFFFFF)
                    }
                    setOnLongClickListener {
                        Haptics.press(this@CalendarActivity)   // 长按删除：重一档
                        removeCountdown(c)
                        true
                    }
                    hapticTap()
                }
                detailList.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * dDip).toInt() })
            }
        }

        // ---------- 系统日程 ----------
        val evs = eventsByDay[d] ?: emptyList()
        detailList.addView(TextView(this).apply {
            text = "当天日程"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textLabel)
            setPadding(0, (16 * dDip).toInt(), 0, (6 * dDip).toInt())
        })

        if (!hasCalPermission()) {
            val btn = chip("授权读取系统日历") {
                requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQ_CAL)
            }
            btn.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (34 * dDip).toInt()
            )
            detailList.addView(btn)
            detailList.addView(smallNote("授权后会自动同步系统日历当天的日程"))
        } else if (evs.isEmpty()) {
            detailList.addView(smallNote("当天没有日程"))
        } else {
            for (ev in evs) {
                val row = TextView(this).apply {
                    text = ev.timeText + "  " + ev.title
                    textSize = 14.5f
                    setTextColor(textBody)
                    setPadding((8 * dDip).toInt(), (5 * dDip).toInt(), (8 * dDip).toInt(), (5 * dDip).toInt())
                    background = GradientDrawable().apply {
                        cornerRadius = 10f * dDip
                        setColor(0x14FFFFFF)
                    }
                }
                detailList.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * dDip).toInt() })
            }
        }
    }

    private fun smallNote(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(textFaint)
        setPadding((8 * resources.displayMetrics.density).toInt(), (4 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    // ---------------- 倒数日存取 ----------------

    private fun loadCountdowns(): MutableList<Countdown> {
        val raw = cdPrefs.getString("list", "") ?: ""
        val out = ArrayList<Countdown>()
        raw.split("\n").forEach { line ->
            if (line.isBlank()) return@forEach
            val p = line.split("|")
            if (p.size >= 2) {
                runCatching {
                    out.add(Countdown(p[0], LocalDate.parse(p[1], DateTimeFormatter.ISO_LOCAL_DATE)))
                }
            }
        }
        return out
    }

    private fun saveCountdowns(list: List<Countdown>) {
        cdPrefs.edit().putString(
            "list", list.joinToString("\n") { "${it.name}|${it.date.format(DateTimeFormatter.ISO_LOCAL_DATE)}" }
        ).apply()
    }

    private fun addCountdown(name: String, date: LocalDate) {
        val list = loadCountdowns()
        list.add(Countdown(name.ifBlank { "倒数日" }, date))
        saveCountdowns(list)
        Haptics.press(this)          // 添加成功：给一次明确确认
        renderDetail()
        Toast.makeText(this, "已添加倒数日", Toast.LENGTH_SHORT).show()
    }

    private fun removeCountdown(c: Countdown) {
        val list = loadCountdowns().filterNot { it.name == c.name && it.date == c.date }
        saveCountdowns(list)
        renderDetail()
        Toast.makeText(this, "已删除「${c.name}」", Toast.LENGTH_SHORT).show()
    }

    private fun showAddCountdownDialog() {
        val input = EditText(this).apply {
            hint = "事项名称，例如 生日 / 考试 / 出发"
            setSingleLine(true)
            textSize = 15f
            val pad = (14 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        wrap.addView(input)

        val dlg = AlertDialog.Builder(this)
            .setTitle("添加倒数日")
            .setView(wrap)
            .setPositiveButton("选日期") { _, _ ->
                val name = input.text.toString().trim()
                val init = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 30) }
                val picker = DatePickerDialog(
                    this,
                    { _: DatePicker, y: Int, m: Int, day: Int ->
                        addCountdown(name, LocalDate.of(y, m + 1, day))
                    },
                    init.get(Calendar.YEAR), init.get(Calendar.MONTH), init.get(Calendar.DAY_OF_MONTH)
                )
                picker.show()
                picker.hapticButtons()
            }
            .setNegativeButton("取消", null)
            .create()
        dlg.show()
        dlg.hapticButtons()
    }

    // ---------------- 系统日历 ----------------

    private fun loadEvents() {
        eventsByDay.clear()
        if (!hasCalPermission()) return

        val first = showMonth.atDay(1)
        val last = showMonth.plusMonths(1).atDay(1)
        val cal = Calendar.getInstance()
        cal.set(first.year, first.monthValue - 1, first.dayOfMonth, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val begin = cal.timeInMillis
        cal.set(last.year, last.monthValue - 1, last.dayOfMonth, 0, 0, 0)
        val end = cal.timeInMillis

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, begin)
        ContentUris.appendId(builder, end)

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY
        )
        try {
            contentResolver.query(
                builder.build(), projection, null, null,
                CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0) ?: "(无标题)"
                    val beginMs = c.getLong(1)
                    val allDay = c.getInt(2) == 1
                    val c2 = Calendar.getInstance().apply { timeInMillis = beginMs }
                    val day = LocalDate.of(
                        c2.get(Calendar.YEAR), c2.get(Calendar.MONTH) + 1, c2.get(Calendar.DAY_OF_MONTH)
                    )
                    val timeText = if (allDay) "全天"
                    else String.format(
                        "%02d:%02d", c2.get(Calendar.HOUR_OF_DAY), c2.get(Calendar.MINUTE)
                    )
                    eventsByDay.getOrPut(day) { ArrayList() }.add(CalEvent(title, timeText))
                }
            }
        } catch (e: Exception) {
            // 读不到系统日历就当作没有日程，不影响其它功能
        }
    }

    // ---------------- 沉浸式全屏 ----------------

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

    override fun onResume() {
        super.onResume()
        applyImmersive()
        // 主题色/夜间模式可能在主页面改过，回到日历页时刷新一遍
        applyTheme()
        (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
        renderDetail()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersive()
    }

    companion object {
        private const val REQ_CAL = 100
    }
}
