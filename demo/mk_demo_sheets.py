"""重建 demo 里的信息图：表盘对比 / 计时器状态流转 / 多设备适配验证。
（此前误用通配符清理时被删除，这里从留存的原始素材与实机截图重新合成。）"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = "E:/workbuddy/xiaommi8"
APP = os.path.join(ROOT, "StandByCat")
DEMO = os.path.join(APP, "demo")
FONT = "C:/Windows/Fonts/msyh.ttc"
FONTB = "C:/Windows/Fonts/msyhbd.ttc"


def f(size, bold=False):
    try:
        return ImageFont.truetype(FONTB if bold else FONT, size)
    except Exception:
        return ImageFont.load_default()


BG = (13, 9, 20)
CARD = (26, 18, 38)
ACC = (255, 95, 174)
TXT = (242, 238, 250)
DIM = (152, 142, 174)


def paste_round(base, im, x, y, r=18):
    mask = Image.new("L", (im.width * 2, im.height * 2), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, im.width * 2 - 1, im.height * 2 - 1], radius=r * 2, fill=255)
    mask = mask.resize(im.size, Image.LANCZOS)
    base.paste(im, (x, y), mask)


def fit_h(im, h):
    im = im.convert("RGB")
    return im.resize((max(1, int(im.width * h / im.height)), h), Image.LANCZOS)


def dial_compare():
    W, H = 1360, 700
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    d.text((48, 34), "模拟指针表盘 · 参考图 vs 成品", font=f(38, True), fill=TXT)
    d.text((48, 90), "左侧为参照样式，右侧为应用内实际渲染（放射刻度 + 中心信息行）", font=f(21), fill=DIM)

    ref = Image.open(os.path.join(ROOT, "ref_watch.png"))
    mine = Image.open(os.path.join(DEMO, "analog33.png"))
    box_h = 460
    ref2 = fit_h(ref, box_h)
    mine2 = fit_h(mine, box_h)
    gap = 40
    x0 = (W - (ref2.width + mine2.width + gap)) // 2
    y = 150
    paste_round(im, ref2, x0, y, 20)
    paste_round(im, mine2, x0 + ref2.width + gap, y, 20)
    d.text((x0 + ref2.width // 2, y + box_h + 34), "参考图", font=f(24, True), fill=DIM, anchor="mm")
    d.text((x0 + ref2.width + gap + mine2.width // 2, y + box_h + 34), "成品",
           font=f(24, True), fill=ACC, anchor="mm")
    im.save(os.path.join(DEMO, "0-表盘对比-参考图vs成品.png"))
    print("0 已重建", im.size)


def state_flow():
    W, H = 1500, 780
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    d.text((52, 36), "计时器按键状态流转", font=f(38, True), fill=TXT)
    d.text((52, 92), "主键在「开始 / 暂停 / 继续」之间切换；计次仅计时中可用；复位清空并回到起点",
           font=f(21), fill=DIM)

    states = [
        ("空闲 IDLE", "主键：开始", "计次：灰  复位：灰"),
        ("计时中 RUNNING", "主键：暂停", "计次：可用  复位：可用"),
        ("暂停 PAUSED", "主键：继续", "计次：灰  复位：可用"),
    ]
    bw, bh = 340, 150
    y = 230
    xs = [70, 570, 1070]
    for (title, l1, l2), x in zip(states, xs):
        d.rounded_rectangle([x, y, x + bw, y + bh], radius=26, fill=CARD)
        d.text((x + bw // 2, y + 34), title, font=f(28, True), fill=ACC, anchor="mm")
        d.text((x + bw // 2, y + 82), l1, font=f(24), fill=TXT, anchor="mm")
        d.text((x + bw // 2, y + 118), l2, font=f(20), fill=DIM, anchor="mm")

    def arrow(x1, x2, yy, label, up=True):
        d.line([x1, yy, x2, yy], fill=(120, 110, 140), width=4)
        tip = [(x2, yy), (x2 - 18, yy - 11), (x2 - 18, yy + 11)] if x2 > x1 else \
              [(x2, yy), (x2 + 18, yy - 11), (x2 + 18, yy + 11)]
        d.polygon(tip, fill=(120, 110, 140))
        d.text(((x1 + x2) / 2, yy - 34 if up else yy + 22), label, font=f(21, True),
               fill=(230, 224, 245), anchor="mm")

    arrow(410, 570, 305, "点「开始」", True)
    arrow(910, 1070, 305, "点「暂停」", True)
    arrow(1070, 910, 420, "点「继续」", False)
    d.line([240, 380, 240, 560, 1240, 560, 1240, 380], fill=(120, 110, 140), width=4)
    d.polygon([(1240, 380), (1229, 398), (1251, 398)], fill=(120, 110, 140))
    d.text((740, 592), "点「复位」→ 回到空闲，并清空全部计次记录", font=f(22, True),
           fill=(230, 224, 245), anchor="mm")
    d.text((52, 660), "「计次」在计时中每次记录：序号 / 累计时长 / 与上一次的间隔；左下角显示最近 3 行，可上下滑动翻看。",
           font=f(20), fill=DIM)
    im.save(os.path.join(DEMO, "8-计时器按键状态流转.png"))
    print("8 已重建", im.size)


def devices():
    rows = [
        ("小屏手机\n1280×720 @320dpi", ["sm_1.png", "sm_2.png"]),
        ("平板\n2048×1536 @320dpi", ["tab_1.png", "tab_2.png"]),
        ("超宽屏\n2520×1080 @420dpi", ["wide_1.png", "wide_2.png"]),
    ]
    cols = ["数字钟", "模拟表盘"]
    cap_h = 250
    gapx, gapy = 26, 40
    label_w = 300
    thumbs = []
    maxw = 0
    for name, files in rows:
        row = []
        for fn in files:
            im = Image.open(os.path.join(APP, "shots", fn))
            t = fit_h(im, cap_h)
            row.append(t)
            maxw = max(maxw, t.width)
        thumbs.append((name, row))

    W = max(1240, label_w + maxw * len(cols) + gapx * (len(cols) - 1) + 120)
    H = 200 + (cap_h + gapy) * 3 + 70
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    d.text((52, 36), "多设备适配验证", font=f(38, True), fill=TXT)
    d.text((52, 92), "同一套布局在三种比例 / 分辨率下的实际渲染：字号与间距自适应，控件不重叠、不溢出",
           font=f(21), fill=DIM)

    y0 = 176
    for i, c in enumerate(cols):
        d.text((label_w + i * (maxw + gapx) + maxw // 2, y0 - 34), c,
               font=f(21, True), fill=DIM, anchor="mm")
    y = y0
    for name, row in thumbs:
        d.multiline_text((56, y + cap_h // 2), name, font=f(21, True), fill=ACC,
                         anchor="lm", spacing=8)
        x = label_w
        for t in row:
            paste_round(im, t, x, y, 14)
            x += maxw + gapx
        y += cap_h + gapy
    d.text((52, H - 44), "当前版本实机截图（v3.3）", font=f(20), fill=DIM)
    im.save(os.path.join(DEMO, "9-多设备适配验证.png"))
    print("9 已重建", im.size)


dial_compare()
state_flow()
devices()
