"""生成《静夜时钟 StandByCat 使用说明书》（单文件 HTML，截图 base64 内嵌，可直接分享）。"""
import base64
import io
import os

from PIL import Image

APP = "E:/workbuddy/xiaommi8/StandByCat"
DEMO = os.path.join(APP, "demo")
OUT = os.path.join(APP, "静夜时钟-使用说明书.html")

VERSION = "v7.3"


def img(name, max_w=1000, quality=84):
    """读图 → 缩放 → base64，控制单文件体积"""
    im = Image.open(os.path.join(DEMO, name)).convert("RGB")
    if im.width > max_w:
        im = im.resize((max_w, int(im.height * max_w / im.width)), Image.LANCZOS)
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=quality, optimize=True)
    return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()


SECTIONS = [
    ("sec-install", "一、安装与启动", [
        ("p", "静夜时钟是一款横屏床头钟：插上电、放在床头，整夜显示时间，不熄灭、不打扰。"),
        ("h3", "安装"),
        ("ul", [
            "把 <code>静夜时钟-v3.5.apk</code> 传到手机，点击安装（首次需在系统里允许“安装未知来源应用”）。",
            "小米 / MIUI 机型如果提示 “Install canceled by user”，请到 <b>设置 → 更多设置 → 开发者选项</b> 打开 <b>USB 安装</b>。",
        ]),
        ("h3", "启动"),
        ("ul", [
            "点桌面图标启动，界面自动横屏；也可直接横放手机，系统会自行转屏。",
            "<b>插上充电器会自动点亮时钟</b>（并弹一条通知，点通知也能进入），适合当床头钟用。",
            "运行时保持常亮（<code>KEEP_SCREEN_ON</code>），不会自动熄屏。",
        ]),
        ("img", ("15-桌面图标·升级前后对比.png", "桌面图标：左侧为旧版（v2.9 猫咪），右侧为现版本（夜色渐变表盘）")),
    ]),

    ("sec-pages", "二、三个表盘页（上下滑动切换）", [
        ("p", "界面由三页组成，<b>在屏幕上竖直滑动</b>即可切换，底部小圆点指示当前是第几页。"
              "翻页用「快起慢收 + 落位轻微回弹」的非线性曲线，时长按剩余距离缩放；"
              "翻页时悬浮件（秒数 / 计时器 / 计次 / 日期）随滑动淡出，落位再淡入。"),
        ("table", (["页", "内容", "说明"], [
            ["① 倒计时", "大号「分:秒」", "在倒计时上面；数字上上下滑动设时长，结束后闹钟式震动"],
            ["② 数字钟", "中间大号「时:分」", "主页面，日常待机看的就是它"],
            ["③ 计时器", "大号「时:分:秒」+ 毫秒", "秒表，支持计次"],
            ["④ 模拟指针", "放射刻度表盘 + 中心信息行", "传统表盘样式，中心显示时间与日期"],
        ])),
        ("p", "<b>单击数字</b>：点一下中间任一位数字，那一位会单独像果冻一样压扁再回弹，"
              "两三下后停住，其它三位完全不动（双击切夜间模式不受影响）。"),
        ("p", "数字是<b>半透明立体玻璃</b>质感：顶部有一道反光、底部收暗，像一块厚玻璃。"
              "每当换数字，旧数字向上被「拉走」、新数字从下方拉出来，动作非线性（先蓄力、末尾轻微回弹），"
              "落地还会压一下；相邻的数字会被轻轻撞一下，越近越明显、越远越轻。"
              "计时器页用的是同一套效果。"),
        ("p", "<b>摇一摇</b>：晃一下手机，中间时分四位数字会各自轻微摆动再慢慢停住 —— "
              "四位互不牵连，越靠右惯性越小、甩得越多，像四块各自泡在浓液体里；约 2 秒停稳。"),
        ("p", "大字时间带有<b>防烧屏机制</b>：每隔几分钟整块数字会自动轻微移动一点"
              "（幅度约 ±13dp，2.5 秒平滑过渡），避免 OLED 屏长期显示同一画面留下残影，"
              "肉眼几乎察觉不到位置变化。"),
        ("img", ("16-主页（v5.2·玻璃质感）.jpg", "数字钟页：中间大字时间，右上秒数、右下计时器、左下日期")),
        ("img", ("18-模拟表盘（v3.7·白指针+新闹钟图标）.jpg", "模拟指针页：放射刻度、12/3/6/9 数字、中心信息行（点它可进日历）")),
    ]),

    ("sec-timer", "三、计时器怎么用", [
        ("table", (["按键", "作用", "可用状态"], [
            ["开始 / 暂停 / 继续", "同一个主键，按状态自动换字", "始终可用"],
            ["计次", "记一次分段用时（序号 / 累计 / 间隔）", "仅计时中"],
            ["复位", "清零并回到空闲，同时清空计次记录", "非空闲时"],
        ])),
        ("p", "左下角显示最近 3 条计次记录，<b>上下滑动</b>可以翻看历史；右侧有滚动条指示位置。"),
        ("img", ("17-计时器页（v3.3）.jpg", "计时器页：左上当前时间、右上毫秒、左下计次记录、右下三键")),
        ("img", ("11-计次记录·滑动翻看.png", "计次记录：最新一条用主题色高亮")),
        ("img", ("8-计时器按键状态流转.png", "按键状态流转：开始 → 暂停 → 继续，复位回到起点")),
    ]),

    ("sec-calendar", "四、日历页（农历 · 节气 · 节假日 · 倒数日 · 日程）", [
        ("h3", "怎么进入"),
        ("ul", [
            "数字钟页：<b>点左下角那行日期文字</b>。",
            "模拟指针页：<b>点表盘中心的时间/日期行</b>。",
            "返回：点左上角 <b>←</b>，或用系统返回手势。",
        ]),
        ("h3", "左半屏：月历"),
        ("ul", [
            "每格上行是公历日，下行<b>优先显示节日/节气</b>，没有则显示农历日（初一显示农历月名）。",
            "带 <b>休</b> 的日期是法定放假，调休上班日也会如实标注；周末与休息日使用主题色的浅色调。",
            "今天 = 主题色实心圆；选中的日期 = 主题色浅色底，<b>没有描边线条</b>。",
            "顶部 <b>‹ ›</b> 换月，<b>今天</b> 一键回到当月。",
        ]),
        ("h3", "右半屏：选中日期的详情"),
        ("ul", [
            "大号日期 + 星期、农历干支生肖、“就是今天 / N 天后 / N 天前”。",
            "<b>倒数日</b>：点右上角 <b>＋</b> 输入名称 → 选日期 → 确定；<b>长按条目可删除</b>。",
            "<b>当天日程</b>：自动读取系统日历（需授权），日程变动会实时刷新，不用手动刷。",
        ]),
        ("img", ("13-日历·完整农历节气与法定假日.jpg", "日历页：左月历、右详情。农历、节气、节日、法定假期俱全")),
        ("img", ("14-倒数日与系统日历同步.jpg", "右侧详情：农历干支、倒数日（可加/长按删）、系统日历当天日程")),
    ]),

    ("sec-color", "五、换颜色（长按取色面板）", [
        ("p", "<b>在任意表盘页长按屏幕</b>（约 0.5 秒）即可打开取色面板，整套配色跟着走：时钟、按钮、日历、倒数日强调色全部统一。"),
        ("table", (["控件", "作用"], [
            ["色相 / 饱和度 / 亮度", "三根粗滑杆，拖动即时预览；圆头是胶囊内的黑圆"],
            ["预置色块", "12 个常用颜色，点一下直接套用"],
            ["顶部预览条", "显示主色 → 渐变辅色的效果"],
            ["夜间模式", "开关，效果与双击屏幕一致"],
            ["按键震动", "开关触感反馈（默认开）"],
            ["恢复默认颜色", "回到出厂配色（粉 → 紫）"],
            ["完成", "保存并关闭"],
        ])),
        ("img", ("4-长按取色器.jpg", "取色面板：三根粗胶囊滑杆（黑圆旋钮）、预置色块、两个开关跟随主题色")),
    ]),

    ("sec-night", "六、夜间模式", [
        ("p", "两种开法：<b>双击屏幕任意空白处</b>，或在取色面板里打开「夜间模式」开关。"),
        ("ul", [
            "开启后文字转为<b>暗红</b>，整屏几乎全黑，夜里不刺眼。"
            "注意：夜间模式<b>不会强制改屏幕亮度</b>，亮度完全跟随系统。",
            "<b>主页面只保留中间的大字时:分</b>，右上秒数、右下计时器、左下日期等全部隐藏，画面干净、也避免误触。",
            "反过来再双击一次即可回到日间模式。",
            "日历页在夜间模式下同样转为暗红配色。",
        ]),
        ("img", ("5-夜间模式·数字钟.jpg", "夜间模式的主页面：只剩中间的时:分")),
        ("img", ("20-日历·夜间模式配色.jpg", "日历页在夜间模式下整体转暗红")),
    ]),

    ("sec-haptic", "七、震动反馈", [
        ("p", "所有按键、长按、双击都有触感反馈，两档手感："),
        ("table", (["动作", "手感"], [
            ["点按按键 / 拖动滑杆", "短促轻点（12ms）"],
            ["长按取色、长按删除、双击切夜间", "更重一档（26ms）"],
            ["被禁用的按键（如未计时时的“计次”）", "不震，避免误以为生效"],
        ])),
        ("p", "不想要震动：长按 → 取色面板 → 关掉「按键震动」。"),
    ]),

    ("sec-perm", "八、权限与隐私", [
        ("table", (["权限", "用途"], [
            ["读取日历", "在日历页显示当天的系统日程；不授权则只显示“当天没有日程”"],
            ["震动", "按键/长按/双击的触感反馈"],
            ["唤醒锁 / 屏幕常亮", "整夜显示不熄屏"],
            ["随系统时间 / 网络", "取当前时间与日期"],
        ])),
        ("p", "全部数据都在本机：主题色、倒数日等只存在应用自己的设置里，<b>不联网、不上传</b>。"),
    ]),

    ("sec-faq", "九、常见问题", [
        ("dl", [
            ("数字看起来有空隙、不像等宽？",
             "这是刻意的等宽排版：所有数字占同一格宽，跳字时位置不会来回抖。"),
            ("日历页没有显示我的日程？",
             "首次进入会申请“读取日历”权限，允许后会自动出现；也可以在日历页右侧点“授权读取系统日历”。"),
            ("夜里太亮 / 太暗？",
             "开夜间模式（双击屏幕）。它会把界面转暗红并大幅降低亮度。"),
            ("颜色调乱了想恢复？",
             "长按屏幕 → 取色面板 → 「恢复默认颜色」→ 完成。"),
            ("魅族 / Flyme 手机开深色模式后指针看不见？",
             "Flyme 的深色模式会把它判定为“大面积亮色”的内容整体翻暗（白指针会被翻黑），"
             "这是系统的智能反色，与应用无关。把本应用加入 Flyme 的跳过名单即可："
             "「深色模式 → 应用」里排除本应用，或用 adb 把它追加到 "
             "<code>flyme_night_mode_black_list</code>。加入后深色模式下本应用完全按自身配色显示。"),
            ("整夜显示会不会烧屏？",
             "主页面的大字时间内置了防烧屏机制：每隔几分钟自动轻微位移一次，"
             "避免同一像素长期发光。加上夜间模式会降低亮度，进一步减少残留风险。"),
            ("怎么退出全屏 / 回桌面？",
             "从屏幕边缘做返回/主页手势即可；应用不会自己退出，方便当床头钟常亮。"),
            ("装到别的手机上会不会变形？",
             "布局按比例自适应，已在小屏 1280×720、平板 2048×1536、超宽 2520×1080 上实测无重叠溢出。"),
        ]),
        ("img", ("9-多设备适配验证.png", "多设备适配：同一套布局在三种比例 / 分辨率下的实际渲染")),
    ]),

    ("sec-shortcut", "十、快捷操作速查", [
        ("table", (["操作", "结果"], [
            ["单击某一位数字", "那一位单独 Q 弹一下（果冻感，其它位不动）"],
            ["摇一摇手机", "数字钟的时分四位数字各自轻微摆动（像泡在浓液体里）"],
            ["上下滑动", "切换表盘页（数字钟 / 计时器 / 模拟指针）"],
            ["长按屏幕", "打开取色面板（配色 · 夜间模式 · 按键震动）"],
            ["双击屏幕", "切换夜间模式"],
            ["点左下日期 / 表盘中心日期行", "进入日历"],
            ["日历页点 ＋ / 长按条目", "添加 / 删除倒数日"],
            ["日历页点 ‹ › / 今天", "换月 / 回到当月"],
        ])),
    ]),
]


def tag_html(t):
    if t == "p":
        return "<p>{}</p>"
    if t == "h3":
        return "<h3>{}</h3>"
    if t == "ul":
        return "<ul>{}</ul>"
    return ""


def render_block(kind, payload, img_no):
    if kind == "p":
        return f"<p>{payload}</p>"
    if kind == "h3":
        return f"<h3>{payload}</h3>"
    if kind == "ul":
        return "<ul>" + "".join(f"<li>{x}</li>" for x in payload) + "</ul>"
    if kind == "img":
        name, cap = payload
        src = img(name)
        return (f'<figure><img src="{src}" alt="{cap}"/>'
                f'<figcaption>图 {img_no}　{cap}</figcaption></figure>')
    if kind == "table":
        head, rows = payload
        th = "".join(f"<th>{h}</th>" for h in head)
        tr = "".join("<tr>" + "".join(f"<td>{c}</td>" for c in r) + "</tr>" for r in rows)
        return f"<table><thead><tr>{th}</tr></thead><tbody>{tr}</tbody></table>"
    if kind == "dl":
        return "<dl>" + "".join(f"<dt>{q}</dt><dd>{a}</dd>" for q, a in payload) + "</dl>"
    return ""


def main():
    toc = "".join(f'<li><a href="#{sid}">{title}</a></li>' for sid, title, _ in SECTIONS)
    body = []
    img_no = 1
    for sid, title, blocks in SECTIONS:
        body.append(f'<section id="{sid}"><h2>{title}</h2>')
        for kind, payload in blocks:
            if kind == "img":
                body.append(render_block(kind, payload, img_no))
                img_no += 1
            else:
                body.append(render_block(kind, payload, 0))
        body.append("</section>")

    html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>静夜时钟 StandByCat 使用说明书</title>
<style>
  :root {{
    --bg:#0D0914; --card:#171021; --line:#2A2036;
    --txt:#EFEAF7; --dim:#A79CBB; --acc:#FF5FAE; --acc2:#8B3BFF;
  }}
  * {{ box-sizing:border-box; }}
  body {{
    margin:0; background:var(--bg); color:var(--txt);
    font-family:"Microsoft YaHei","PingFang SC","Hiragino Sans GB",system-ui,sans-serif;
    line-height:1.85; font-size:16px;
  }}
  .wrap {{ max-width:960px; margin:0 auto; padding:56px 28px 80px; }}
  header {{ padding:44px 40px; border-radius:28px; margin-bottom:44px;
    background:linear-gradient(135deg, rgba(255,95,174,.20), rgba(139,59,255,.16) 55%, rgba(23,16,33,1)); 
    border:1px solid var(--line); }}
  header h1 {{ margin:0 0 10px; font-size:38px; letter-spacing:2px;
    background:linear-gradient(100deg,#FF7BB4,#C458D8 45%,#8B3BFF);
    -webkit-background-clip:text; background-clip:text; color:transparent; }}
  header .sub {{ color:var(--dim); font-size:15px; letter-spacing:1px; }}
  header .meta {{ margin-top:18px; color:var(--dim); font-size:14px; }}
  header .meta b {{ color:var(--txt); }}
  nav {{ background:var(--card); border:1px solid var(--line); border-radius:22px;
    padding:22px 30px; margin-bottom:44px; }}
  nav h2 {{ margin:0 0 10px; font-size:17px; color:var(--acc); letter-spacing:1px; }}
  nav ol {{ margin:0; padding-left:22px; columns:2; column-gap:40px; }}
  nav a {{ color:var(--txt); text-decoration:none; }}
  nav a:hover {{ color:var(--acc); }}
  section {{ margin-bottom:56px; }}
  h2 {{ font-size:25px; margin:0 0 18px; padding-left:16px; position:relative; letter-spacing:1px; }}
  h2::before {{ content:""; position:absolute; left:0; top:6px; bottom:6px; width:5px;
    border-radius:3px; background:linear-gradient(#FF7BB4,#8B3BFF); }}
  h3 {{ font-size:18px; margin:26px 0 10px; color:var(--acc); }}
  p {{ margin:12px 0; }}
  ul {{ margin:10px 0; padding-left:24px; }}
  li {{ margin:7px 0; }}
  code {{ background:#241a33; padding:2px 7px; border-radius:6px; font-size:14px; color:#E9D9FF; }}
  table {{ width:100%; border-collapse:collapse; margin:16px 0; background:var(--card);
    border-radius:14px; overflow:hidden; font-size:15px; }}
  th, td {{ padding:12px 16px; text-align:left; border-bottom:1px solid var(--line); vertical-align:top; }}
  th {{ background:#20172e; color:var(--acc); font-weight:700; white-space:nowrap; }}
  tr:last-child td {{ border-bottom:none; }}
  figure {{ margin:22px 0; }}
  figure img {{ width:100%; display:block; border-radius:16px; border:1px solid var(--line); }}
  figcaption {{ color:var(--dim); font-size:13.5px; margin-top:9px; text-align:center; }}
  dl {{ margin:14px 0; }}
  dt {{ font-weight:700; margin-top:16px; }}
  dt::before {{ content:"Q　"; color:var(--acc); }}
  dd {{ margin:5px 0 0 26px; color:var(--dim); }}
  footer {{ border-top:1px solid var(--line); padding-top:22px; color:var(--dim); font-size:14px; }}
</style>
</head>
<body>
<div class="wrap">
  <header>
    <h1>静夜时钟 StandByCat</h1>
    <div class="sub">横屏床头钟 · 使用说明书</div>
    <div class="meta">适用版本 <b>{VERSION}</b>　·　设备：Android 8.0 及以上（实测小米 8 / Android 10）　·　包名 <b>com.xiaomi8.standbycat</b></div>
  </header>

  <nav>
    <h2>目录</h2>
    <ol>{toc}</ol>
  </nav>

  {''.join(body)}

  <footer>
    <p>静夜时钟 StandByCat · 使用说明书（{VERSION}）　—　本手册随版本更新，截图取自实机。</p>
  </footer>
</div>
</body>
</html>
"""
    with io.open(OUT, "w", encoding="utf-8") as f:
        f.write(html)
    print("已生成:", OUT, f"{os.path.getsize(OUT)/1024:.0f} KB")


main()
