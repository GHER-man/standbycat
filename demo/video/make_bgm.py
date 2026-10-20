#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
静夜时钟 · 宣传片配乐（原创合成，35 秒）
风格：Apple 产品片那种"轻快但不吵"的底噪 —— 温暖 Pad + 木琴感拨弦 + 轻脉冲
全部用 numpy 生成，无任何采样素材，版权完全自有。
"""
import numpy as np, wave, sys

SR = 44100
DUR = 35.2
N = int(SR * DUR)
BPM = 96.0
BEAT = 60.0 / BPM
BAR = BEAT * 4              # 2.5s
RNG = np.random.RandomState(20261020)

# 和弦进行：Am → F → C → G（循环），每小节一个
CH = [
    (57, [60, 64, 69]),     # Am: A3 + C4 E4 A4
    (53, [57, 60, 65]),     # F : F3 + A3 C4 F4
    (48, [55, 60, 64]),     # C : C3 + G3 C4 E4
    (55, [59, 62, 67]),     # G : G3 + B3 D4 G4
]

def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12.0)

def env_adsr(n, a=0.02, d=0.1, s=0.7, r=0.25):
    """简易 ADSR，返回 0..1 包络"""
    a_n, d_n, r_n = int(a * SR), int(d * SR), int(r * SR)
    s_n = max(0, n - a_n - d_n - r_n)
    e = np.concatenate([
        np.linspace(0, 1, max(1, a_n)),
        np.linspace(1, s, max(1, d_n)),
        np.full(s_n, s),
        np.linspace(s, 0, max(1, r_n)),
    ])
    if len(e) < n:
        e = np.pad(e, (0, n - len(e)))
    return e[:n]

def pad_note(f, dur, amp):
    n = int(SR * dur)
    t = np.arange(n) / SR
    # 三个略微失谐的正弦 + 二次谐波，声音更暖
    sig = (np.sin(2 * np.pi * f * t) * 1.0
           + np.sin(2 * np.pi * f * 1.003 * t) * 0.6
           + np.sin(2 * np.pi * f * 0.997 * t) * 0.6
           + np.sin(2 * np.pi * f * 2 * t) * 0.12)
    return sig * env_adsr(n, a=0.35, d=0.4, s=0.55, r=0.6) * amp

def pluck_note(f, dur, amp):
    """木琴/拨弦感：快速衰减 + 二三次谐波"""
    n = int(SR * dur)
    t = np.arange(n) / SR
    env = np.exp(-t * 7.0) * (1 - np.exp(-t * 400))
    sig = (np.sin(2 * np.pi * f * t) * 1.0
           + np.sin(2 * np.pi * f * 2 * t) * 0.28
           + np.sin(2 * np.pi * f * 3 * t) * 0.09)
    return sig * env * amp

def bass_note(f, dur, amp):
    n = int(SR * dur)
    t = np.arange(n) / SR
    sig = np.sin(2 * np.pi * f * t) + np.sin(2 * np.pi * f * 2 * t) * 0.08
    return sig * env_adsr(n, a=0.01, d=0.25, s=0.55, r=0.2) * amp

def hat(dur=0.05, amp=0.05):
    n = int(SR * dur)
    noise = RNG.randn(n)
    # 一阶高通：只留清脆部分
    hp = np.zeros(n); prev = 0.0
    for i in range(n):
        prev = 0.72 * (prev + noise[i] - (noise[i - 1] if i else 0))
        hp[i] = prev
    return hp * np.exp(-np.arange(n) / (SR * 0.012)) * amp

L = np.zeros(N)
R = np.zeros(N)

def put(sig, at, gain=1.0, pan=0.0):
    i = int(at * SR)
    j = min(N, i + len(sig))
    if i >= N:
        return
    seg = sig[:j - i] * gain
    # 等功率声像
    gl = np.cos((pan + 1) * np.pi / 4)
    gr = np.sin((pan + 1) * np.pi / 4)
    L[i:j] += seg * gl
    R[i:j] += seg * gr

# ---- 编曲 ----
bars = int(np.ceil(DUR / BAR))
arp_pattern = [0, 1, 2, 1, 0, 1, 2, 1]      # 每小节 8 个八分音符
for b in range(bars):
    at = b * BAR
    root, tones = CH[b % len(CH)]
    tail = 1.6
    # Pad（整小节铺底，左右轻微展开）
    for k, m in enumerate(tones):
        put(pad_note(hz(m), BAR + tail, 0.10), at, 0.9, pan=-0.25 + 0.25 * k)
    # 低音：小节头 + 第三拍
    put(bass_note(hz(root - 12), BEAT * 1.8, 0.16), at, 0.9)
    put(bass_note(hz(root - 12), BEAT * 1.4, 0.10), at + BEAT * 2, 0.9)
    # 拨弦琶音
    for k, idx in enumerate(arp_pattern):
        m = tones[idx % len(tones)] + 12
        t0 = at + k * BEAT / 2
        put(pluck_note(hz(m), 0.9, 0.085 if k % 2 else 0.11), t0,
            1.0, pan=0.35 if k % 2 else -0.35)
    # 极轻的节拍点
    for k in range(4):
        if (b + k) % 2 == 0:
            put(hat(), at + k * BEAT, 0.55, pan=0.15)

# ---- 段落包络：0-3s 淡入，中段稳定，30s 后收尾 ----
t = np.arange(N) / SR
sect = np.ones(N)
sect *= np.clip(t / 3.0, 0, 1) ** 1.2                      # 开头渐入
sect *= np.clip((DUR - t) / 2.2, 0, 1) ** 1.4              # 结尾收
# 第 27-30 秒轻微提起，最后落回（做一个小高潮再收）
sect *= 1.0 + 0.16 * np.exp(-((t - 27.5) ** 2) / (2 * 2.4 ** 2))

L *= sect
R *= sect

# ---- 母带：轻度饱和 + 峰值归一 ----
mix = np.stack([L, R], axis=1)
mix = np.tanh(mix * 1.15) / 1.15
peak = np.max(np.abs(mix)) or 1.0
mix = mix / peak * 0.72          # 留 ~3dB 余量

pcm = (np.clip(mix, -1, 1) * 32767).astype('<i2')
out = sys.argv[1] if len(sys.argv) > 1 else 'bgm.wav'
with wave.open(out, 'wb') as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes(pcm.tobytes())
print('配乐已生成:', out, round(len(pcm) / SR, 1), 's, 立体声')
