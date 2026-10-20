#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
静夜时钟 · 苹果风格宣传片（约 90 秒）
- 素材：手机截图 + 2400x1080 录屏
- 画面：纯黑、克制运镜（缓慢推近）、少量交叉溶解
- 音效：全部用 numpy 合成（点击/低频 hit/扫风/铃声/震动），无配乐
"""
import io, os, re, subprocess, sys, math, random
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.dirname(os.path.abspath(__file__))
W, H, FPS = 1920, 864, 30   # 与素材 2400x1080 同比例，零裁切零变形
FF = subprocess.run([sys.executable, '-c', 'import imageio_ffmpeg;print(imageio_ffmpeg.get_ffmpeg_exe())'],
                    capture_output=True, text=True).stdout.strip()
FB = 'C:/Windows/Fonts/msyhbd.ttc'   # 微软雅黑 Bold
FR = 'C:/Windows/Fonts/msyh.ttc'

def F(sz, bold=True):
    return ImageFont.truetype(FB if bold else FR, sz)

# ---------------- 素材准备 ----------------
def load_cover(path, box_w=W, box_h=H):
    """等比缩放到画框内，居中放在纯黑底上（不裁切 UI）"""
    im = Image.open(path).convert('RGB')
    k = min(box_w / im.width, box_h / im.height)
    nw, nh = int(im.width * k + .5), int(im.height * k + .5)
    im = im.resize((nw, nh), Image.LANCZOS)
    canvas = Image.new('RGB', (box_w, box_h), (0, 0, 0))
    canvas.paste(im, ((box_w - nw) // 2, (box_h - nh) // 2))
    return canvas

def load_fit(path, box_w, box_h):
    im = Image.open(path).convert('RGB')
    k = min(box_w / im.width, box_h / im.height)
    return im.resize((int(im.width * k + .5), int(im.height * k + .5)), Image.LANCZOS)

def rounded(im, r):
    mask = Image.new('L', im.size, 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, im.size[0] - 1, im.size[1] - 1], r, fill=255)
    out = im.convert('RGBA')
    out.putalpha(mask)
    return out

def card(im, r=28, shadow=28):
    """给图片加圆角 + 柔和投影，返回 RGBA（带外扩）"""
    pad = shadow * 2
    base = Image.new('RGBA', (im.width + pad * 2, im.height + pad * 2), (0, 0, 0, 0))
    sh = Image.new('RGBA', base.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(sh)
    d.rounded_rectangle([pad, pad + shadow // 2, pad + im.width, pad + im.height + shadow // 2],
                        r, fill=(0, 0, 0, 150))
    sh = sh.filter(ImageFilter.GaussianBlur(shadow * 0.7))
    base.alpha_composite(sh)
    base.alpha_composite(rounded(im, r), (pad, pad))
    return base

print('准备素材…')
CLOCK = load_cover(f'{ROOT}/ui_clock.png')
CD = load_cover(f'{ROOT}/ui_countdown.png')
TIMER = load_cover(f'{ROOT}/ui_timer.png')
ANALOG = load_cover(f'{ROOT}/ui_analog.png')
PICKER = load_cover(f'{ROOT}/ui_picker.png')
CAL = load_cover(f'{ROOT}/ui_calendar.png')
NCLOCK = load_cover(f'{ROOT}/night_clock.png')
NCD = load_cover(f'{ROOT}/night_countdown.png')

# 数字特写（从截图里裁出来再放大，用于玻璃质感那一幕）
GLASS_CLOSE = CLOCK.crop((150, 210, 1770, 900)).resize((1860, 793), Image.LANCZOS)

# 录屏拆帧
def frames_of(name, fps=15, size=(1600, 720)):
    outdir = f'{ROOT}/frames_{name}'
    if not os.path.isdir(outdir) or len(os.listdir(outdir)) < 3:
        os.makedirs(outdir, exist_ok=True)
        subprocess.run([FF, '-y', '-i', f'{ROOT}/rec_{name}.mp4', '-vf',
                        f'fps={fps},scale={size[0]}:{size[1]}:flags=lanczos',
                        f'{outdir}/%04d.jpg'], capture_output=True)
    files = sorted(os.listdir(outdir))
    return [Image.open(f'{outdir}/{f}').convert('RGB') for f in files]

print('拆录屏帧…')
ROLL = frames_of('roll', 15, (1500, 675))
WHEEL = frames_of('wheel', 15, (1500, 675))
SWIPE = frames_of('swipe', 15, (1500, 675))
print(f'  帧数 roll={len(ROLL)} wheel={len(WHEEL)} swipe={len(SWIPE)}')

# ---------------- 通用绘制 ----------------
def ease_out(t):
    return 1 - (1 - t) ** 3

def ease_io(t):
    return t * t * (3 - 2 * t)

def text_center(d, y, s, font, fill=(255, 255, 255), alpha=1.0, shadow=True):
    if alpha <= 0.01:
        return
    w = d.textlength(s, font=font)
    x = (W - w) / 2
    if shadow:
        d.text((x + 2, y + 3), s, font=font, fill=(0, 0, 0, int(160 * alpha)))
    c = fill if len(fill) == 4 else (fill[0], fill[1], fill[2], int(255 * alpha))
    d.text((x, y), s, font=font, fill=(c[0], c[1], c[2], int(c[3] * alpha)))

def vignette(base, strength=90):
    v = Image.new('L', (W, H), 0)
    dv = ImageDraw.Draw(v)
    dv.ellipse([-W * 0.25, -H * 0.45, W * 1.25, H * 1.45], fill=255)
    v = v.filter(ImageFilter.GaussianBlur(180))
    dark = Image.new('RGB', (W, H), (0, 0, 0))
    return Image.composite(base, dark, v.point(lambda p: 255 - int((255 - p) * strength / 255)))

def paste_center(dst, src, cx, cy, scale=1.0, alpha=1.0):
    if scale != 1.0:
        src = src.resize((max(2, int(src.width * scale)), max(2, int(src.height * scale))), Image.LANCZOS)
    if alpha < 0.999:
        src = src.copy()
        if src.mode == 'RGBA':
            a = src.getchannel('A').point(lambda p: int(p * alpha))
            src.putalpha(a)
        else:
            src = src.convert('RGBA')
            src.putalpha(int(255 * alpha))
    dst.alpha_composite(src, (int(cx - src.width / 2), int(cy - src.height / 2)))

def top_scrim(dst, h=260, strength=190):
    """顶部压暗渐变，给文案留可读的背景"""
    ov = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    for i in range(h):
        a = int(strength * (1 - i / h) ** 1.6)
        d.line([(0, i), (W, i)], fill=(0, 0, 0, a))
    dst.alpha_composite(ov)

def bg_black():
    return Image.new('RGBA', (W, H), (0, 0, 0, 255))

def put_full(dst, im, alpha=1.0, scale=1.0, cx=W / 2, cy=H / 2):
    if scale != 1.0:
        nw, nh = int(W * scale), int(H * scale)
        im2 = im.resize((nw, nh), Image.LANCZOS)
        dst.paste(im2, (int(cx - nw / 2), int(cy - nh / 2)))
    else:
        dst.paste(im, (0, 0))
    if alpha < 0.999:
        black = Image.new('RGB', (W, H), (0, 0, 0))
        dst.paste(Image.blend(black, dst.convert('RGB'), alpha).convert('RGBA'), (0, 0))

# 文案样式
F_TITLE = F(78)
F_SUB = F(34, False)
F_H = F(46)
F_CAP = F(26, False)
F_TINY = F(20, False)

# ---------------- 分镜 ----------------
# 每个场景: (时长秒, 绘制函数(dst:RGBA, t:0..1, T:秒))
SCENES = []

def scene_title(dst, t, T):
    put_full(dst, CLOCK, alpha=min(1, t * 3) * 0.32, scale=1.06 - 0.06 * ease_out(t))
    dst.paste(vignette(dst.convert('RGB'), 110).convert('RGBA'), (0, 0))
    top_scrim(dst, 520, 120)
    a = max(0.0, min(1.0, (t - 0.15) * 2.2))
    text_center(ImageDraw.Draw(dst), H / 2 - 90, '静夜时钟', F(108), alpha=a)
    text_center(ImageDraw.Draw(dst), H / 2 + 40, '一盏安静的床头钟', F_SUB, (200, 205, 225), a * 0.9)

def scene_glass(dst, t, T):
    s = 1.0 + 0.06 * ease_io(t)
    k = W * s / GLASS_CLOSE.width          # 等比，不拉不压
    im = GLASS_CLOSE.resize((int(GLASS_CLOSE.width * k), int(GLASS_CLOSE.height * k)), Image.LANCZOS)
    y = (H - im.height) / 2
    dst.paste(im, (int((W - im.width) / 2), int(y)))
    dst.paste(vignette(dst.convert('RGB'), 120).convert('RGBA'), (0, 0))
    d = ImageDraw.Draw(dst)
    # 顶部文案（分三段淡入）
    lines = ['液态玻璃数字', '磨砂 · 柔光 · 内阴影', '大而不刺眼，夜里看着很安静']
    for i, s2 in enumerate(lines):
        a = max(0.0, min(1.0, (t - 0.10 - i * 0.10) * 3))
        if i == 0:
            text_center(d, 96, s2, F_H, alpha=a)
        else:
            text_center(d, 96 + 66 + (i - 1) * 44, s2, F_CAP, (168, 174, 198), a * 0.95)

def scene_roll(dst, t, T):
    # 慢放：整段素材（约 5 秒）铺满 10 秒
    idx = min(len(ROLL) - 1, int(t * (len(ROLL) - 1)))
    fr = ROLL[idx]
    dst.paste(fr.resize((W, H), Image.LANCZOS), (0, 0))
    dst.paste(vignette(dst.convert('RGB'), 70).convert('RGBA'), (0, 0))
    top_scrim(dst, 230, 200)
    d = ImageDraw.Draw(dst)
    a = max(0.0, min(1.0, (t - 0.05) * 4)) * max(0.0, min(1.0, (1 - t) * 4))
    text_center(d, 74, '换一位数字，像被轻轻拉出来', F_H, alpha=a)
    text_center(d, 140, '旧的向上淡出 · 新的自下而上 · 落位压一下', F_CAP, (168, 174, 198), a * 0.9)

def scene_pages(dst, t, T):
    items = [(CD, '①', '倒计时'), (CLOCK, '②', '数字钟'), (TIMER, '③', '计时器'), (ANALOG, '④', '模拟指针')]
    tw, th = 760, 342          # 每格缩略图（20:9）
    gap = 28
    total_w = tw * 2 + gap
    total_h = th * 2 + gap
    x0 = (W - total_w) / 2
    y0 = H / 2 - total_h / 2 + 34
    d = ImageDraw.Draw(dst)
    for i, (im, num, name) in enumerate(items):
        a = max(0.0, min(1.0, (t - 0.05 * i) * 3.4))
        if a <= 0.01:
            continue
        col, row = i % 2, i // 2
        cx = x0 + col * (tw + gap)
        cy = y0 + row * (th + gap)
        yoff = (1 - ease_out(min(1, a))) * 30
        thumb = im.resize((tw, th), Image.LANCZOS)
        thumb = rounded(thumb, 18)
        if a < 0.999:
            thumb = thumb.copy()
            thumb.putalpha(thumb.getchannel('A').point(lambda p: int(p * a)))
        dst.alpha_composite(thumb, (int(cx), int(cy + yoff)))
        d.text((cx + 14, cy - 30 + yoff), f'{num} {name}', font=F(22), fill=(150, 156, 180, int(255 * a)))
    a = max(0.0, min(1.0, (t - 0.30) * 3))
    top_scrim(dst, 210, 190)
    d = ImageDraw.Draw(dst)
    text_center(d, 44, '四个页面，上下滑动就够了', F_H, alpha=a)
    text_center(d, 106, '倒计时 · 数字钟 · 计时器 · 模拟指针', F_CAP, (168, 174, 198), a * 0.9)

def scene_countdown(dst, t, T):
    idx = min(len(WHEEL) - 1, int(t * (len(WHEEL) - 1)))
    fr = WHEEL[idx]
    dst.paste(fr.resize((W, H), Image.LANCZOS), (0, 0))
    dst.paste(vignette(dst.convert('RGB'), 70).convert('RGBA'), (0, 0))
    top_scrim(dst, 230, 200)
    d = ImageDraw.Draw(dst)
    a = max(0.0, min(1.0, (t - 0.05) * 4)) * max(0.0, min(1.0, (1 - t) * 5))
    text_center(d, 70, '直接在数字上滑动设定时长', F_H, alpha=a)
    text_center(d, 134, '上滑增加 · 下滑减少 · 松手带惯性', F_CAP, (168, 174, 198), a * 0.9)
    # 结束提示：脉冲圈 + 文案
    if t > 0.55:
        k = min(1.0, (t - 0.55) * 3)
        r = 26 + 10 * math.sin((t - 0.55) * 18)
        cx, cy = W / 2, H - 120
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(255, 120, 120, int(220 * k)), width=2)
        text_center(d, H - 150, '倒计时结束 · 像闹钟一样持续震动', F_CAP, (240, 170, 170), k)

def scene_timer(dst, t, T):
    put_full(dst, TIMER, alpha=1.0, scale=1.02 + 0.04 * ease_io(t))
    dst.paste(vignette(dst.convert('RGB'), 80).convert('RGBA'), (0, 0))
    top_scrim(dst, 230, 200)
    d = ImageDraw.Draw(dst)
    a = max(0.0, min(1.0, (t - 0.05) * 4)) * max(0.0, min(1.0, (1 - t) * 5))
    text_center(d, 70, '秒表 · 毫秒级', F_H, alpha=a)
    text_center(d, 134, '开始 / 暂停 / 计次 / 复位，按键都有回弹', F_CAP, (168, 174, 198), a * 0.9)

def scene_night(dst, t, T):
    k = ease_io(min(1.0, t / 0.55))          # 前半段做昼夜过渡
    a1, a2 = 1 - k, k
    dst.paste(CLOCK, (0, 0))
    if a2 > 0.002:
        dst.paste(Image.blend(CLOCK, NCLOCK, a2), (0, 0))
    dst.paste(vignette(dst.convert('RGB'), 100).convert('RGBA'), (0, 0))
    top_scrim(dst, 230, 210)
    d = ImageDraw.Draw(dst)
    a = max(0.0, min(1.0, (t - 0.42) * 3.5))
    text_center(d, 70, '双击，切到夜间模式', F_H, alpha=a)
    text_center(d, 134, '全屏只剩时与分 · 所有元素转为暗红', F_CAP, (200, 150, 150), a * 0.95)

def scene_custom(dst, t, T):
    put_full(dst, PICKER, alpha=1.0, scale=1.01 + 0.02 * ease_io(t))
    dst.paste(vignette(dst.convert('RGB'), 80).convert('RGBA'), (0, 0))
    top_scrim(dst, 200, 210)
    d = ImageDraw.Draw(dst)
    a = max(0.0, min(1.0, (t - 0.05) * 4)) * max(0.0, min(1.0, (1 - t) * 5))
    text_center(d, 34, '长按，换成你喜欢的颜色', F_H, alpha=a)
    text_center(d, 104, '色相 / 饱和度 / 亮度 · 夜间模式 · 简化动效 · 按键震动', F_CAP, (168, 174, 198), a * 0.9)

def scene_calendar(dst, t, T):
    put_full(dst, CAL, alpha=1.0, scale=1.01 + 0.03 * ease_io(t))
    dst.paste(vignette(dst.convert('RGB'), 80).convert('RGBA'), (0, 0))
    top_scrim(dst, 200, 210)
    d = ImageDraw.Draw(dst)
    a = max(0.0, min(1.0, (t - 0.05) * 4)) * max(0.0, min(1.0, (1 - t) * 5))
    text_center(d, 34, '日历 · 农历 · 节气 · 倒数日', F_H, alpha=a)
    text_center(d, 104, '点日期就进来看，右半屏是当天的详情', F_CAP, (168, 174, 198), a * 0.9)

def scene_end(dst, t, T):
    d = ImageDraw.Draw(dst)
    a = max(0.0, min(1.0, (t - 0.12) * 2.4))
    text_center(d, H / 2 - 96, '静夜时钟', F(104), alpha=a)
    text_center(d, H / 2 + 34, 'StandByCat · v7.3', F(28, False), (150, 156, 180), a * 0.95)
    text_center(d, H / 2 + 96, '全屏常亮 · 液态玻璃 · 倒计时与秒表', F_CAP, (120, 126, 146), a * 0.8)

# 时长（秒）
PLAN = [
    (scene_title,     7.0, 'hit'),
    (scene_glass,    11.0, 'shimmer'),
    (scene_roll,     11.0, 'ticks'),
    (scene_pages,    10.0, 'quad'),
    (scene_countdown,14.0, 'wheel'),
    (scene_timer,     8.0, 'click'),
    (scene_night,    11.0, 'whoosh'),
    (scene_custom,    9.0, 'click2'),
    (scene_calendar,  7.0, 'page'),
    (scene_end,       6.5, 'chime'),
]

# 每一幕「进入」时的转场（与内容节奏匹配），第一幕为 None
TRANS = [
    None,
    ('zoom',        0.85),   # 标题 → 玻璃特写：顺着画面往数字里推进
    ('dissolve',    0.60),   # 同为数字特写：柔和叠化
    ('slideUp',     0.55),   # → 四页：像 App 自己的上下翻页
    ('pushLeft',    0.55),   # → 倒计时：横向推开
    ('whip',        0.42),   # → 秒表：快节奏甩镜
    ('dissolveSlow',1.00),   # → 夜间：缓慢昼夜交替
    ('streak',      0.55),   # → 换色：一道光扫过
    ('slideUp',     0.55),   # → 日历：再次上下翻页
    ('fade',        0.90),   # → 片尾：退回黑场
]

# 时间线：后一幕提前 trans 秒进入（两幕重叠做转场）
starts = []
t0 = 0.0
for i, (fn, dur, sfx) in enumerate(PLAN):
    if i > 0:
        t0 += PLAN[i - 1][1] - TRANS[i][1]
    starts.append(t0)
TOTAL = starts[-1] + PLAN[-1][1]
print(f'总时长 {TOTAL:.1f}s（转场重叠 {sum(t[1] for t in TRANS if t):.2f}s）')

# ---------------- 音效合成 ----------------
SR = 44100
def env_exp(n, tau):
    return np.exp(-np.arange(n) / (SR * tau))

def snd_click(dur=0.035, f=2400, amp=0.30):
    n = int(SR * dur); t = np.arange(n) / SR
    sig = np.sin(2 * np.pi * f * t) * env_exp(n, 0.006)
    noise = np.random.RandomState(1).randn(n) * env_exp(n, 0.003) * 0.5
    return (sig + noise) * amp

def snd_hit(dur=1.2, amp=0.42):
    n = int(SR * dur); t = np.arange(n) / SR
    return (np.sin(2 * np.pi * 55 * t) * env_exp(n, 0.28)
            + np.sin(2 * np.pi * 110 * t) * env_exp(n, 0.12) * 0.4) * amp

def snd_shimmer(dur=1.6, amp=0.16):
    n = int(SR * dur); t = np.arange(n) / SR
    out = np.zeros(n)
    for f0 in (880, 1174, 1568, 2093):
        out += np.sin(2 * np.pi * f0 * t) * env_exp(n, 0.45) * 0.25
    return out * amp

def snd_whoosh(dur=0.75, amp=0.20):
    n = int(SR * dur)
    noise = np.random.RandomState(7).randn(n)
    out = np.zeros(n); y = 0.0
    for i in range(n):
        k = 0.02 + 0.25 * (i / n)
        y += k * (noise[i] - y)
        out[i] = y
    return out * (np.sin(np.linspace(0, np.pi, n)) ** 1.5) * amp

def snd_chime(dur=1.8, amp=0.22):
    n = int(SR * dur); t = np.arange(n) / SR
    out = np.zeros(n)
    for f0, a in ((1046.5, 1.0), (1568, 0.6), (2093, 0.35)):
        out += np.sin(2 * np.pi * f0 * t) * env_exp(n, 0.5) * a
    return out * amp

def snd_buzz(dur=2.4, amp=0.30):
    n = int(SR * dur); t = np.arange(n) / SR
    carrier = np.sin(2 * np.pi * 150 * t) * 0.6 + np.sin(2 * np.pi * 300 * t) * 0.3
    gate = ((t % 0.72) < 0.42).astype(float)
    return carrier * gate * amp * env_exp(n, 1.6)

def snd_riser(dur=0.8, amp=0.14):
    """转场用的上扫音：噪声 + 上行正弦"""
    n = int(SR * dur); t = np.arange(n) / SR
    noise = np.random.RandomState(11).randn(n) * 0.35
    sweep = np.sin(2 * np.pi * (300 + 900 * (t / dur)) * t)
    return (noise + sweep * 0.4) * (np.linspace(0, 1, n) ** 2) * amp

print('合成音效…')
track = np.zeros(int(SR * (TOTAL + 2)))
def add(sig, at, gain=1.0):
    i = int(at * SR); j = min(len(track), i + len(sig))
    if i < len(track):
        track[i:j] += sig[:j - i] * gain

for i, (fn, dur, sfx) in enumerate(PLAN):
    st = starts[i]
    if sfx == 'hit':
        add(snd_hit(), st + 0.15, 0.95)
    elif sfx == 'shimmer':
        add(snd_shimmer(), st + 0.10, 0.8)
        add(snd_whoosh(0.6, 0.12), st, 0.7)
    elif sfx == 'ticks':
        for k in range(9):
            add(snd_click(0.05, 1800 + 55 * k, 0.16), st + 0.35 + k * 0.42, 1.0)
    elif sfx == 'quad':
        for k in range(4):
            add(snd_click(0.05, 2000, 0.22), st + 0.08 + k * 0.18, 1.0)
    elif sfx == 'wheel':
        add(snd_whoosh(0.7, 0.14), st, 0.8)
        for k in range(14):
            add(snd_click(0.035, 1500 + 40 * k, 0.20), st + 0.5 + k * 0.30, 1.0)
        add(snd_buzz(), st + 8.2, 0.9)
        add(snd_chime(1.2, 0.10), st + 8.2, 0.7)
    elif sfx == 'click':
        add(snd_click(0.04, 2200, 0.26), st + 0.12, 1.0)
        add(snd_click(0.04, 2400, 0.20), st + 0.26, 1.0)
    elif sfx == 'whoosh':
        add(snd_whoosh(0.9, 0.26), st + 0.1, 1.0)
        add(snd_hit(0.9, 0.22), st + 0.55, 0.9)
    elif sfx == 'click2':
        for k in range(3):
            add(snd_click(0.04, 1900 + 200 * k, 0.20), st + 0.10 + k * 0.22, 1.0)
    elif sfx == 'page':
        add(snd_whoosh(0.8, 0.18), st + 0.05, 1.0)
    elif sfx == 'chime':
        add(snd_chime(), st + 0.10, 1.0)
        add(snd_click(0.04, 2600, 0.14), st + 0.9, 1.0)

# 转场音效：甩镜/推进/光带各配一个上扫，纯叠化不加音
for i, tr in enumerate(TRANS):
    if not tr:
        continue
    kind, d = tr
    at = starts[i]
    if kind in ('whip', 'zoom', 'streak'):
        add(snd_riser(d + 0.15, 0.16 if kind == 'whip' else 0.11), at - 0.05, 1.0)
    elif kind in ('pushLeft', 'slideUp'):
        add(snd_whoosh(d + 0.1, 0.09), at - 0.03, 1.0)
    elif kind == 'fade':
        add(snd_hit(1.4, 0.16), at, 0.8)

peak = np.max(np.abs(track)) or 1.0
track = track / peak * 0.85
fade = int(SR * 0.35)
track[:fade] *= np.linspace(0, 1, fade)
track[-fade:] *= np.linspace(1, 0, fade)
import wave
pcm = (track * 32767).astype('<i2')
with wave.open(f'{ROOT}/sfx.wav', 'wb') as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes(pcm.tobytes())
print('音效轨完成', round(len(track) / SR, 1), 's')

# ---------------- 转场 ----------------
def frame_of(i, local):
    d = bg_black()
    PLAN[i][0](d, local, PLAN[i][1])
    return d

def tr_dissolve(A, B, p, slow=False):
    k = ease_io(p) if not slow else (p * p * (3 - 2 * p)) ** 1.2
    return Image.blend(A, B, k)

def tr_slide_up(A, B, p):
    e = ease_io(p) * H
    out = A.copy()
    out.paste(A, (0, int(-e)))
    out.paste(B, (0, int(H - e)))
    return out

def tr_push_left(A, B, p):
    e = ease_io(p) * W
    out = Image.new('RGBA', (W, H), (0, 0, 0, 255))
    out.paste(A, (int(-e), 0))
    out.paste(B, (int(W - e), 0))
    return out

def tr_zoom(A, B, p):
    e = ease_io(p)
    # B 从 1.35 倍回落到 1.0（推进定住），A 轻微放大约淡出
    sb = 1.35 - 0.35 * e
    b2 = B.resize((int(W * sb), int(H * sb)), Image.LANCZOS)
    out = bg_black()
    out.paste(b2, (int((W - b2.width) / 2), int((H - b2.height) / 2)))
    a2 = A.resize((int(W * (1 + 0.10 * e)), int(H * (1 + 0.10 * e))), Image.LANCZOS)
    a2 = a2.crop(((a2.width - W) // 2, (a2.height - H) // 2,
                  (a2.width - W) // 2 + W, (a2.height - H) // 2 + H))
    return Image.blend(out, a2, 1 - e)

def tr_whip(A, B, p):
    e = ease_out(min(1, p * 1.35))
    blur = max(0.6, 26 * (1 - e))
    off = int((1 - e) * W * 0.55)
    a2 = A.filter(ImageFilter.GaussianBlur(blur))
    b2 = B.filter(ImageFilter.GaussianBlur(blur * 0.8))
    out = Image.new('RGBA', (W, H), (0, 0, 0, 255))
    out.paste(a2, (-off, 0))
    out.paste(b2, (W - off, 0))
    return out

def tr_streak(A, B, p):
    base = Image.blend(A, B, ease_io(p))
    # 一道斜向高光扫过
    x = -W * 0.4 + (W * 1.8) * ease_io(p)
    ov = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    bw = 220
    for k in range(bw):
        a = int(90 * (1 - abs(k - bw / 2) / (bw / 2)) ** 2)
        d.line([(x + k, 0), (x + k - 160, H)], fill=(255, 255, 255, a))
    base = base.convert('RGBA')
    base.alpha_composite(ov)
    return base

def tr_fade(A, B, p):
    if p < 0.5:
        return Image.blend(A, Image.new('RGB', (W, H), (0, 0, 0)), ease_io(p * 2))
    return Image.blend(Image.new('RGB', (W, H), (0, 0, 0)), B, ease_io((p - 0.5) * 2))

TRF = {'dissolve': tr_dissolve, 'dissolveSlow': lambda A, B, p: tr_dissolve(A, B, p, True),
       'slideUp': tr_slide_up, 'pushLeft': tr_push_left, 'zoom': tr_zoom,
       'whip': tr_whip, 'streak': tr_streak, 'fade': tr_fade}

# ---------------- 渲染 ----------------
print('渲染画面…')
n_total = int(TOTAL * FPS)
cmd = [FF, '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{W}x{H}', '-r', str(FPS), '-i', '-',
       '-i', f'{ROOT}/sfx.wav',
       '-c:v', 'libx264', '-preset', 'medium', '-crf', '18', '-pix_fmt', 'yuv420p',
       '-c:a', 'aac', '-b:a', '192k', '-shortest', f'{ROOT}/../../静夜时钟-宣传片.mp4']
proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def scene_at(t):
    """返回 (i, local_t) 或 (i, i+1, localA, localB, kind, p)"""
    cur = 0
    for i, st in enumerate(starts):
        if t >= st - 1e-6:
            cur = i
    # 是否落在 cur 与 cur+1 的重叠窗口
    if cur + 1 < len(starts):
        nxt = cur + 1
        kind, d = TRANS[nxt]
        if t >= starts[nxt]:
            p = (t - starts[nxt]) / d
            return ('blend', cur, nxt, t - starts[cur], t - starts[nxt], kind, min(1.0, max(0.0, p)))
    return ('single', cur, t - starts[cur], None, None, None, None)

n = 0
for f in range(n_total):
    t = f / FPS
    info = scene_at(t)
    if info[0] == 'single':
        i, lt = info[1], info[2]
        frame = frame_of(i, min(1.0, lt / PLAN[i][1]))
    else:
        _, i, j, ltA, ltB, kind, p = info
        A = frame_of(i, min(1.0, ltA / PLAN[i][1]))
        B = frame_of(j, min(1.0, ltB / PLAN[j][1]))
        frame = TRF[kind](A, B, p)
    rgb = frame.convert('RGB')
    # 全片首尾各 0.6s 淡入淡出
    if t < 0.6:
        rgb = Image.blend(Image.new('RGB', (W, H), (0, 0, 0)), rgb, t / 0.6)
    if TOTAL - t < 0.6:
        rgb = Image.blend(Image.new('RGB', (W, H), (0, 0, 0)), rgb, max(0.0, (TOTAL - t) / 0.6))
    proc.stdin.write(rgb.tobytes())
    n += 1
    if n % 150 == 0:
        print(f'  {n}/{n_total}')
proc.stdin.close()
proc.wait()
print('完成: 静夜时钟-宣传片.mp4')
