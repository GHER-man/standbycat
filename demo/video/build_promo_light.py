#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
静夜时钟 · 苹果产品宣传片风格（亮调 / 快切 / 约 60 秒）
- 亮灰渐变舞台 + 圆角产品卡片 + 大黑字标题（Apple 产品片语汇）
- 快速硬切为主，配少量滑入/推进/缩放入场
- 音效全部 numpy 合成（轻快的点击/pop/铃声），无配乐
"""
import io, os, subprocess, sys, math
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.dirname(os.path.abspath(__file__))
W, H, FPS = 1920, 864, 30          # 20:9，与素材同比例
FF = subprocess.run([sys.executable, '-c', 'import imageio_ffmpeg;print(imageio_ffmpeg.get_ffmpeg_exe())'],
                    capture_output=True, text=True).stdout.strip()
FB = 'C:/Windows/Fonts/msyhbd.ttc'
FR = 'C:/Windows/Fonts/msyh.ttc'
def F(sz, bold=True): return ImageFont.truetype(FB if bold else FR, sz)

# ---------------- 舞台 ----------------
def make_stage():
    """亮色舞台：上浅下深一点点的中性灰，四角轻微压暗"""
    img = Image.new('RGB', (W, H))
    top, bot = (246, 246, 249), (230, 231, 237)
    d = ImageDraw.Draw(img)
    for y in range(H):
        k = y / (H - 1)
        d.line([(0, y), (W, y)], fill=(
            int(top[0] + (bot[0] - top[0]) * k),
            int(top[1] + (bot[1] - top[1]) * k),
            int(top[2] + (bot[2] - top[2]) * k)))
    v = Image.new('L', (W, H), 0)
    dv = ImageDraw.Draw(v)
    dv.ellipse([-W * 0.30, -H * 0.55, W * 1.30, H * 1.55], fill=255)
    v = v.filter(ImageFilter.GaussianBlur(200))
    dark = Image.new('RGB', (W, H), (206, 208, 216))
    return Image.composite(img, dark, v)

STAGE = make_stage()

def load_fit(path, bw, bh):
    im = Image.open(path).convert('RGB')
    k = min(bw / im.width, bh / im.height)
    return im.resize((int(im.width * k + .5), int(im.height * k + .5)), Image.LANCZOS)

def shot(path, bw, bh):
    """把截图铺到给定框（同比例，无变形），返回该尺寸的整图"""
    im = load_fit(path, bw, bh)
    canvas = Image.new('RGB', (bw, bh), (10, 10, 12))
    canvas.paste(im, ((bw - im.width) // 2, (bh - im.height) // 2))
    return canvas

def rounded(im, r):
    m = Image.new('L', im.size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, im.size[0] - 1, im.size[1] - 1], r, fill=255)
    out = im.convert('RGBA'); out.putalpha(m); return out

def card(im, r=26, shadow=34, sh_alpha=70):
    pad = shadow * 2
    base = Image.new('RGBA', (im.width + pad * 2, im.height + pad * 2), (0, 0, 0, 0))
    sh = Image.new('RGBA', base.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle(
        [pad, pad + shadow * 0.55, pad + im.width, pad + im.height + shadow * 0.55],
        r, fill=(60, 62, 74, sh_alpha))
    sh = sh.filter(ImageFilter.GaussianBlur(shadow * 0.8))
    base.alpha_composite(sh)
    base.alpha_composite(rounded(im, r), (pad, pad))
    return base

# ---------------- 素材 ----------------
print('准备素材…')
CARD_W, CARD_H = 1160, 522
CLOCK = shot(f'{ROOT}/ui_clock.png', CARD_W, CARD_H)
CD = shot(f'{ROOT}/ui_countdown.png', CARD_W, CARD_H)
TIMER = shot(f'{ROOT}/ui_timer.png', CARD_W, CARD_H)
ANALOG = shot(f'{ROOT}/ui_analog.png', CARD_W, CARD_H)
PICKER = shot(f'{ROOT}/ui_picker.png', CARD_W, CARD_H)
CAL = shot(f'{ROOT}/ui_calendar.png', CARD_W, CARD_H)
NCLOCK = shot(f'{ROOT}/night_clock.png', CARD_W, CARD_H)
NCD = shot(f'{ROOT}/night_countdown.png', CARD_W, CARD_H)
GLASS_CLOSE = CLOCK.crop((110, 130, 1420, 600))

def frames_of(name, fps=15, size=(1160, 522)):
    outdir = f'{ROOT}/frames_{name}'
    if not os.path.isdir(outdir) or len(os.listdir(outdir)) < 3:
        os.makedirs(outdir, exist_ok=True)
        subprocess.run([FF, '-y', '-i', f'{ROOT}/rec_{name}.mp4', '-vf',
                        f'fps={fps},scale={size[0]}:{size[1]}:flags=lanczos',
                        f'{outdir}/%04d.jpg'], capture_output=True)
    return [Image.open(f'{outdir}/{f}').convert('RGB') for f in sorted(os.listdir(outdir))]

ROLL = frames_of('roll')
WHEEL = frames_of('wheel')
SWIPE = frames_of('swipe')
print('  帧数', len(ROLL), len(WHEEL), len(SWIPE))

# ---------------- 排版工具 ----------------
INK = (17, 17, 20)
GREY = (116, 118, 128)
F_BIG = F(96)
F_H = F(58)
F_SUB = F(30, False)
F_KICK = F(22)

def ease_out(t): return 1 - (1 - t) ** 3
def ease_io(t): return t * t * (3 - 2 * t)

def text_center(d, y, s, font, fill=INK, alpha=1.0):
    if alpha <= 0.01: return
    w = d.textlength(s, font=font)
    d.text(((W - w) / 2, y), s, font=font, fill=(fill[0], fill[1], fill[2], int(255 * alpha)))

def text_left(d, x, y, s, font, fill=INK, alpha=1.0):
    if alpha <= 0.01: return
    d.text((x, y), s, font=font, fill=(fill[0], fill[1], fill[2], int(255 * alpha)))

def base(x=None):
    f = STAGE.copy().convert('RGBA')
    return f

def put_card(dst, img, cx, cy, r=26, scale=1.0, alpha=1.0, shadow=34, sh_alpha=70):
    im = img
    if scale != 1.0:
        im = im.resize((int(im.width * scale), int(im.height * scale)), Image.LANCZOS)
    # 注意：传入的 img 若已经是 card 结果，这里会再包一层投影（视觉上可接受，等于加深阴影）
    c = card(im, r, shadow, sh_alpha)
    if alpha < 0.999:
        a = c.getchannel('A').point(lambda p: int(p * alpha))
        c.putalpha(a)
    dst.alpha_composite(c, (int(cx - c.width / 2), int(cy - c.height / 2)))

# ---------------- 分镜 ----------------
def s_title(dst, t, T):
    d = ImageDraw.Draw(dst)
    a1 = max(0.0, min(1.0, (t - 0.05) * 4))
    a2 = max(0.0, min(1.0, (t - 0.18) * 3.4))
    y = 74 - (1 - ease_out(a1)) * 26
    text_center(d, y, '静夜时钟', F_BIG, INK, a1)
    text_center(d, y + 116, '一盏安静、会呼吸的床头钟', F_SUB, GREY, a2)
    # 卡片从下缓升
    k = ease_out(max(0.0, min(1.0, (t - 0.10) * 2.2)))
    put_card(dst, card(CLOCK), W / 2, H / 2 + 118 + (1 - k) * 60, alpha=k)

def s_glass(dst, t, T):
    d = ImageDraw.Draw(dst)
    a = max(0.0, min(1.0, (t - 0.02) * 5))
    text_left(d, 96, 64, '液态玻璃', F_H, INK, a)
    text_left(d, 96, 140, '磨砂、柔光、内阴影 —— 大而不刺眼', F_SUB, GREY, a)
    # 特写卡片缓慢推近
    s = 1.0 + 0.05 * ease_io(t)
    im = GLASS_CLOSE.resize((int(GLASS_CLOSE.width * s * 1.05),
                             int(GLASS_CLOSE.height * s * 1.05)), Image.LANCZOS)
    put_card(dst, card(im), W / 2, H / 2 + 116, r=20, scale=1.0, shadow=22)

def s_roll(dst, t, T):
    idx = min(len(ROLL) - 1, int(t * (len(ROLL) - 1)))
    d = ImageDraw.Draw(dst)
    a = max(0.0, min(1.0, (t - 0.02) * 5))
    text_left(d, 96, 64, '换一位数字，像被轻轻拉出来', F_H, INK, a)
    text_left(d, 96, 140, '旧的向上淡出 · 新的自下而上 · 落位压一下', F_SUB, GREY, a)
    put_card(dst, card(ROLL[idx]), W / 2, H / 2 + 118, shadow=22)

def s_pages(dst, t, T):
    d = ImageDraw.Draw(dst)
    a = max(0.0, min(1.0, (t - 0.02) * 5))
    text_center(d, 56, '四个页面，上下滑动就够了', F_H, INK, a)
    items = [(CD, '倒计时'), (CLOCK, '数字钟'), (TIMER, '计时器'), (ANALOG, '模拟指针')]
    cw, ch = 420, 189
    gap = 26
    total = cw * 4 + gap * 3 + 40 * 4      # 含投影留白
    x = (W - total) / 2 + 20
    for i, (im, name) in enumerate(items):
        aa = max(0.0, min(1.0, (t - 0.08 - 0.05 * i) * 5))
        if aa <= 0.01: continue
        cx = x + i * (cw + gap + 40) + cw / 2
        cy = H / 2 + 96 + (1 - ease_out(aa)) * 24
        put_card(dst, card(im.resize((cw, ch), Image.LANCZOS), 14, 20),
                 cx, cy, r=14, alpha=aa, shadow=0)
        d.text((cx - cw / 2, cy - ch / 2 - 34), name, font=F(21), fill=(118, 120, 130, int(255 * aa)))

def s_countdown(dst, t, T):
    idx = min(len(WHEEL) - 1, int(t * (len(WHEEL) - 1)))
    d = ImageDraw.Draw(dst)
    a = max(0.0, min(1.0, (t - 0.02) * 5))
    text_left(d, 96, 64, '在数字上滑动，就能设定时长', F_H, INK, a)
    text_left(d, 96, 140, '上滑增加 · 下滑减少 · 松手带惯性', F_SUB, GREY, a)
    put_card(dst, card(WHEEL[idx]), W / 2, H / 2 + 118, shadow=22)
    if t > 0.62:
        k = max(0.0, min(1.0, (t - 0.62) * 3))
        text_left(d, 96, 196, '结束 → 像闹钟一样持续震动', F_SUB, (200, 92, 92), k)

def s_timer(dst, t, T):
    d = ImageDraw.Draw(dst)
    a = max(0.0, min(1.0, (t - 0.02) * 5))
    text_left(d, 96, 64, '秒表 · 毫秒级', F_H, INK, a)
    text_left(d, 96, 140, '开始 / 暂停 / 计次 / 复位', F_SUB, GREY, a)
    put_card(dst, card(TIMER), W / 2, H / 2 + 118, shadow=22)

def s_night(dst, t, T):
    d = ImageDraw.Draw(dst)
    a = max(0.0, min(1.0, (t - 0.02) * 5))
    text_left(d, 96, 64, '双击，切到夜间模式', F_H, INK, a)
    text_left(d, 96, 140, '整屏只剩时与分，全部转为暗红', F_SUB, GREY, a)
    im = CLOCK
    if t > 0.35:
        k = ease_io(min(1.0, (t - 0.35) / 0.45))
        im = Image.blend(CLOCK, NCLOCK, k)
    put_card(dst, card(im), W / 2, H / 2 + 118, shadow=26, sh_alpha=90)

def s_custom(dst, t, T):
    d = ImageDraw.Draw(dst)
    a = max(0.0, min(1.0, (t - 0.02) * 5))
    text_center(d, 52, '长按换色，也能一键简化', F_H, INK, a)
    a1 = max(0.0, min(1.0, (t - 0.08) * 5))
    a2 = max(0.0, min(1.0, (t - 0.16) * 5))
    put_card(dst, card(PICKER.resize((560, 252), Image.LANCZOS), 16, 22),
             W / 2 - 310, H / 2 + 112, r=16, alpha=a1, shadow=0)
    put_card(dst, card(CAL.resize((560, 252), Image.LANCZOS), 16, 22),
             W / 2 + 310, H / 2 + 112, r=16, alpha=a2, shadow=0)
    if t > 0.42:
        k = max(0.0, min(1.0, (t - 0.42) * 3))
        text_center(d, H - 86, '色相 · 饱和度 · 亮度 · 夜间模式 · 简化动效', F(24, False), GREY, k)

def s_end(dst, t, T):
    d = ImageDraw.Draw(dst)
    a = max(0.0, min(1.0, (t - 0.06) * 3.6))
    text_center(d, H / 2 - 96, '静夜时钟', F_BIG, INK, a)
    text_center(d, H / 2 + 26, 'StandByCat · v7.3', F_SUB, GREY, a)
    text_center(d, H / 2 + 84, '全屏常亮 · 液态玻璃 · 倒计时与秒表', F(22, False), (140, 142, 152), a)

PLAN = [
    (s_title,     6.6, 'hit'),
    (s_glass,     7.4, 'shimmer'),
    (s_roll,      8.0, 'ticks'),
    (s_pages,     7.0, 'quad'),
    (s_countdown,10.0, 'wheel'),
    (s_timer,     6.0, 'click'),
    (s_night,     8.0, 'whoosh'),
    (s_custom,    7.0, 'click2'),
    (s_end,       5.0, 'chime'),
]
TRANS = [
    None,
    ('pushUp',    0.42),
    ('dissolve',  0.30),
    ('popUp',     0.40),
    ('pushLeft',  0.38),
    ('whip',      0.30),
    ('dissolveSlow', 0.55),
    ('slideLeft', 0.38),
    ('fade',      0.60),
]

starts, t0 = [], 0.0
for i, (fn, dur, sfx) in enumerate(PLAN):
    if i > 0:
        t0 += PLAN[i - 1][1] - TRANS[i][1]
    starts.append(t0)
TOTAL = starts[-1] + PLAN[-1][1]
print(f'总时长 {TOTAL:.1f}s')

# ---------------- 音效（更轻快） ----------------
SR = 44100
def env(n, tau): return np.exp(-np.arange(n) / (SR * tau))

def s_pop(dur=0.10, f=880, amp=0.34):
    n = int(SR * dur); t = np.arange(n) / SR
    body = np.sin(2 * np.pi * f * t * (1 + 0.8 * t / dur)) * env(n, 0.022)
    click = np.random.RandomState(3).randn(n) * env(n, 0.004) * 0.6
    return (body + click) * amp

def s_click(dur=0.03, f=2600, amp=0.26):
    n = int(SR * dur); t = np.arange(n) / SR
    return (np.sin(2 * np.pi * f * t) * env(n, 0.005)
            + np.random.RandomState(1).randn(n) * env(n, 0.0025) * 0.5) * amp

def s_shimmer(dur=1.2, amp=0.13):
    n = int(SR * dur); t = np.arange(n) / SR
    out = np.zeros(n)
    for f0 in (1318, 1760, 2637):
        out += np.sin(2 * np.pi * f0 * t) * env(n, 0.35) * 0.22
    return out * amp

def s_whoosh(dur=0.55, amp=0.16):
    n = int(SR * dur)
    noise = np.random.RandomState(7).randn(n)
    out = np.zeros(n); y = 0.0
    for i in range(n):
        k = 0.05 + 0.3 * (i / n)
        y += k * (noise[i] - y); out[i] = y
    return out * (np.sin(np.linspace(0, np.pi, n)) ** 1.4) * amp

def s_chime(dur=1.4, amp=0.18):
    n = int(SR * dur); t = np.arange(n) / SR
    out = np.zeros(n)
    for f0, a in ((1046.5, 1.0), (1568, 0.55), (2093, 0.3)):
        out += np.sin(2 * np.pi * f0 * t) * env(n, 0.4) * a
    return out * amp

def s_buzz(dur=2.0, amp=0.24):
    n = int(SR * dur); t = np.arange(n) / SR
    carrier = np.sin(2 * np.pi * 150 * t) * 0.6 + np.sin(2 * np.pi * 300 * t) * 0.3
    gate = ((t % 0.72) < 0.42).astype(float)
    return carrier * gate * amp * env(n, 1.3)

def s_riser(dur=0.6, amp=0.10):
    n = int(SR * dur); t = np.arange(n) / SR
    noise = np.random.RandomState(11).randn(n) * 0.3
    return (noise + np.sin(2 * np.pi * (400 + 1100 * (t / dur)) * t) * 0.35) * (np.linspace(0, 1, n) ** 2) * amp

track = np.zeros(int(SR * (TOTAL + 2)))
def add(sig, at, g=1.0):
    i = int(at * SR); j = min(len(track), i + len(sig))
    if i < len(track): track[i:j] += sig[:j - i] * g

for i, (fn, dur, sfx) in enumerate(PLAN):
    st = starts[i]
    if sfx == 'hit':
        add(s_pop(0.16, 520, 0.36), st + 0.12)
    elif sfx == 'shimmer':
        add(s_shimmer(), st + 0.05, 0.9)
    elif sfx == 'ticks':
        for k in range(7):
            add(s_click(0.03, 2200 + 90 * k, 0.18), st + 0.18 + k * 0.30)
    elif sfx == 'quad':
        for k in range(4):
            add(s_pop(0.09, 760 + 120 * k, 0.26), st + 0.06 + k * 0.12)
    elif sfx == 'wheel':
        for k in range(16):
            add(s_click(0.026, 1800 + 45 * k, 0.17), st + 0.25 + k * 0.20)
        add(s_buzz(), st + 6.0, 0.9)
    elif sfx == 'click':
        add(s_pop(0.09, 900, 0.26), st + 0.10)
        add(s_click(0.03, 2600, 0.16), st + 0.22)
    elif sfx == 'whoosh':
        add(s_whoosh(0.6, 0.20), st + 0.05)
    elif sfx == 'click2':
        for k in range(3):
            add(s_click(0.03, 2400 + 180 * k, 0.17), st + 0.06 + k * 0.14)
    elif sfx == 'chime':
        add(s_chime(), st + 0.06, 1.0)

for i, tr in enumerate(TRANS):
    if not tr: continue
    kind, d = tr
    at = starts[i]
    if kind in ('whip', 'popUp', 'slideLeft', 'pushUp', 'pushLeft'):
        add(s_riser(d + 0.1, 0.09 if kind == 'whip' else 0.055), at - 0.04)
    elif kind == 'fade':
        add(s_pop(0.14, 460, 0.24), at)

peak = np.max(np.abs(track)) or 1.0
track = track / peak * 0.82
fd = int(SR * 0.3)
track[:fd] *= np.linspace(0, 1, fd)
track[-fd:] *= np.linspace(1, 0, fd)
import wave
with wave.open(f'{ROOT}/sfx.wav', 'wb') as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes((track * 32767).astype('<i2').tobytes())
print('音效轨', round(len(track) / SR, 1), 's')

# ---------------- 转场 ----------------
def frame_of(i, local):
    d = base()
    PLAN[i][0](d, local, PLAN[i][1])
    return d

def tr_dissolve(A, B, p, slow=False):
    k = ease_io(p) if not slow else (p * p * (3 - 2 * p))
    return Image.blend(A, B, k)

def tr_push_up(A, B, p):
    e = ease_io(p) * H
    out = A.copy()
    out.paste(A, (0, int(-e))); out.paste(B, (0, int(H - e)))
    return out

def tr_slide_left(A, B, p):
    e = ease_io(p) * W
    out = Image.new('RGBA', (W, H), STAGE.getpixel((5, 5)) + (255,))
    out.paste(A, (int(-e), 0)); out.paste(B, (int(W - e), 0))
    return out

def tr_push_left(A, B, p):
    return tr_slide_left(A, B, p)

def tr_pop_up(A, B, p):
    """B 从小放大落位（轻快），叠在 A 上"""
    k = ease_out(p)
    s = 0.92 + 0.08 * k
    b2 = B.resize((int(W * s), int(H * s)), Image.LANCZOS)
    out = A.copy()
    out.alpha_composite(b2.convert('RGBA'), (int((W - b2.width) / 2), int((H - b2.height) / 2)))
    return out

def tr_whip(A, B, p):
    e = ease_out(min(1, p * 1.4))
    blur = max(0.5, 18 * (1 - e))
    off = int((1 - e) * W * 0.5)
    out = Image.new('RGBA', (W, H), STAGE.getpixel((5, 5)) + (255,))
    out.paste(A.filter(ImageFilter.GaussianBlur(blur)), (-off, 0))
    out.paste(B.filter(ImageFilter.GaussianBlur(blur * 0.8)), (W - off, 0))
    return out

def tr_fade(A, B, p):
    if p < 0.5:
        return Image.blend(A, Image.new('RGB', (W, H), (246, 246, 249)), ease_io(p * 2))
    return Image.blend(Image.new('RGB', (W, H), (246, 246, 249)), B, ease_io((p - 0.5) * 2))

TRF = {'dissolve': tr_dissolve,
       'dissolveSlow': lambda A, B, p: tr_dissolve(A, B, p, True),
       'pushUp': tr_push_up, 'slideLeft': tr_slide_left, 'pushLeft': tr_push_left,
       'popUp': tr_pop_up, 'whip': tr_whip, 'fade': tr_fade}

# ---------------- 渲染 ----------------
print('渲染画面…')
n_total = int(TOTAL * FPS)
cmd = [FF, '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{W}x{H}', '-r', str(FPS), '-i', '-',
       '-i', f'{ROOT}/sfx.wav',
       '-c:v', 'libx264', '-preset', 'medium', '-crf', '18', '-pix_fmt', 'yuv420p',
       '-c:a', 'aac', '-b:a', '192k', '-shortest', f'{ROOT}/../../静夜时钟-宣传片-轻快版.mp4']
proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def scene_at(t):
    cur = 0
    for i, st in enumerate(starts):
        if t >= st - 1e-6: cur = i
    if cur + 1 < len(starts):
        nxt = cur + 1
        kind, d = TRANS[nxt]
        if t >= starts[nxt]:
            return ('blend', cur, nxt, t - starts[cur], t - starts[nxt], kind,
                    min(1.0, max(0.0, (t - starts[nxt]) / d)))
    return ('single', cur, t - starts[cur], None, None, None, None)

n = 0
for f in range(n_total):
    t = f / FPS
    info = scene_at(t)
    if info[0] == 'single':
        i, lt = info[1], info[2]
        fr = frame_of(i, min(1.0, lt / PLAN[i][1]))
    else:
        _, i, j, la, lb, kind, p = info
        A = frame_of(i, min(1.0, la / PLAN[i][1]))
        B = frame_of(j, min(1.0, lb / PLAN[j][1]))
        fr = TRF[kind](A, B, p)
    rgb = fr.convert('RGB')
    if t < 0.5:
        rgb = Image.blend(Image.new('RGB', (W, H), (246, 246, 249)), rgb, t / 0.5)
    if TOTAL - t < 0.5:
        rgb = Image.blend(Image.new('RGB', (W, H), (246, 246, 249)), rgb, max(0.0, (TOTAL - t) / 0.5))
    proc.stdin.write(rgb.tobytes())
    n += 1
    if n % 150 == 0: print(f'  {n}/{n_total}')
proc.stdin.close(); proc.wait()
print('完成: 静夜时钟-宣传片-轻快版.mp4')
