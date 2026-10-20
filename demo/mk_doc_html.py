#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把 `静夜时钟-功能说明书-v7.3.txt` 转成排版好的 HTML。
规则（纯文本 → 结构）：
  - 第 1 行 = 标题；含「版本」的行 = 副标题
  - 全 = / 全 - 的行 = 分隔线
  - `一、二、…` = 章节标题        `【…】` = 小节标题
  - `·` / `-` 开头 = 列表项        其余 = 正文段落
用法：python mk_doc_html.py [输入.txt] [输出.html]
"""
import io, re, sys, html, os

SRC = sys.argv[1] if len(sys.argv) > 1 else '静夜时钟-功能说明书-v7.3.txt'
DST = sys.argv[2] if len(sys.argv) > 2 else '静夜时钟-功能说明书-v7.3.html'
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = SRC if os.path.isabs(SRC) else os.path.join(HERE, SRC)
DST = DST if os.path.isabs(DST) else os.path.join(HERE, DST)

lines = io.open(SRC, encoding='utf-8').read().splitlines()

title = lines[0].strip()
sub = next((l.strip() for l in lines[1:6] if '版本' in l), '')

out, in_ul = [], False


def close_ul():
    global in_ul
    if in_ul:
        out.append('</ul>')
        in_ul = False


for raw in lines[1:]:
    line = raw.rstrip()
    t = line.strip()
    if not t:
        close_ul()
        continue
    if set(t) <= {'=', '-'}:            # 分隔线：跳过（不渲染成裸文本）
        close_ul()
        continue
    if t == sub:                         # 副标题单独处理
        continue
    if re.match(r'^[一二三四五六七八九十]+、', t):
        close_ul()
        out.append(f'<h2>{html.escape(t)}</h2>')
        continue
    if t.startswith('【') and t.endswith('】'):
        close_ul()
        out.append(f'<h3>{html.escape(t[1:-1])}</h3>')
        continue
    if t.startswith('·') or t.startswith('-'):
        if not in_ul:
            out.append('<ul>')
            in_ul = True
        out.append(f'<li>{html.escape(t[1:].strip())}</li>')
        continue
    close_ul()
    out.append(f'<p>{html.escape(t)}</p>')
close_ul()

doc = f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{html.escape(title)}</title>
<style>
  :root {{ color-scheme: dark; }}
  * {{ box-sizing: border-box; }}
  body {{
    margin: 0; padding: 48px 20px 72px;
    background: radial-gradient(1200px 600px at 50% -10%, #1c1f2e 0%, #0b0c11 62%, #06070a 100%);
    color: #e9e9ef; font: 15.5px/1.9 -apple-system, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
  }}
  .wrap {{ max-width: 820px; margin: 0 auto; }}
  header {{ text-align: center; padding: 8px 0 30px; border-bottom: 1px solid rgba(255,255,255,.07); }}
  h1 {{ margin: 0 0 12px; font-size: 27px; letter-spacing: .05em;
       background: linear-gradient(180deg,#fff 30%,#9aa4ff); -webkit-background-clip: text;
       background-clip: text; color: transparent; }}
  .badge {{ display: inline-block; padding: 5px 15px; border-radius: 999px; font-size: 12.5px;
           color: #cfd3ff; background: rgba(255,255,255,.075); }}
  h2 {{ margin: 42px 0 12px; font-size: 18.5px; font-weight: 700; color: #fff;
       padding-left: 13px; border-left: 3px solid #7c8cff; }}
  h3 {{ margin: 20px 0 6px; font-size: 15px; font-weight: 600; color: #b9c0ff; }}
  p {{ margin: 9px 0; color: #d7d9e1; }}
  ul {{ margin: 8px 0 12px; padding-left: 22px; }}
  li {{ margin: 6px 0; color: #d2d5df; }}
  li::marker {{ color: #7c8cff; }}
  footer {{ margin-top: 56px; padding-top: 18px; border-top: 1px solid rgba(255,255,255,.07);
           text-align: center; color: #6a6f7d; font-size: 12.5px; }}
</style></head><body><div class="wrap">
<header>
  <h1>{html.escape(title)}</h1>
  <span class="badge">{html.escape(sub)}</span>
</header>
{chr(10).join(out)}
<footer>静夜时钟 StandByCat · {html.escape(sub)}</footer>
</div></body></html>"""

io.open(DST, 'w', encoding='utf-8').write(doc)
print('已生成:', os.path.basename(DST), f'({len(doc)} 字节)')
print('  章节数 h2 =', doc.count('<h2>'), ' 列表项 li =', doc.count('<li>'))
print('  残留分隔线 =', '====' in doc or '----' in doc.replace('<!--', ''))
