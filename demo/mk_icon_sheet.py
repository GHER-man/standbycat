from PIL import Image, ImageDraw, ImageFont

FONT = "C:/Windows/Fonts/msyh.ttc"
FONTB = "C:/Windows/Fonts/msyhbd.ttc"


def f(size, bold=False):
    try:
        return ImageFont.truetype(FONTB if bold else FONT, size)
    except Exception:
        return ImageFont.load_default()


def find_new_cell(im):
    px = im.load()
    minx, maxx, miny, maxy = 9999, 0, 9999, 0
    for y in range(1450, 1900):
        for x in range(30, 400):
            r, g, b = px[x, y]
            if r > 200 and b > 180 and g < 130:  # 粉紫环
                minx, maxx = min(minx, x), max(maxx, x)
                miny, maxy = min(miny, y), max(maxy, y)
    return ((minx + maxx) // 2, (miny + maxy) // 2)


def find_old_cell(im):
    px = im.load()
    minx, maxx, miny, maxy = 9999, 0, 9999, 0
    for y in range(1580, 1810):
        for x in range(40, 300):
            r, g, b = px[x, y]
            if r > 240 and g > 228 and b > 200 and r - b > 15:  # 米白底
                minx, maxx = min(minx, x), max(maxx, x)
                miny, maxy = min(miny, y), max(maxy, y)
    return ((minx + maxx) // 2, (miny + maxy) // 2)


PAD = 168  # 单边留白：把图标连同周围壁纸一起框进来
new_c = find_new_cell(Image.open("home3.png").convert("RGB"))
old_c = find_old_cell(Image.open("home_old.png").convert("RGB"))
old = Image.open("home_old.png").convert("RGB").crop(
    (old_c[0] - PAD, old_c[1] - PAD, old_c[0] + PAD, old_c[1] + PAD)
)
new = Image.open("home3.png").convert("RGB").crop(
    (new_c[0] - PAD, new_c[1] - PAD, new_c[0] + PAD, new_c[1] + PAD)
)

BG = (13, 9, 20)
CARD = (27, 19, 39)
ACC = (255, 95, 174)
TXT = (242, 238, 250)
DIM = (152, 142, 174)

W, H = 1180, 880
im = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(im)

d.text((52, 40), "桌面图标 · 升级前后对比", font=f(40, True), fill=TXT)
d.text((52, 100), "静夜时钟 StandByCat   v2.9  →  v3.0", font=f(24), fill=DIM)

IS = 300
y = 168
for x, src, cap, accent in ((52, old, "升级前 v2.9", DIM), (640, new, "升级后 v3.0", ACC)):
    d.rounded_rectangle([x - 16, y - 16, x + IS + 16, y + IS + 16], radius=38, fill=CARD)
    pic = src.resize((IS, IS), Image.LANCZOS)
    mask = Image.new("L", (IS * 4, IS * 4), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, IS * 4 - 1, IS * 4 - 1], radius=int(IS * 4 * 0.20), fill=255)
    mask = mask.resize((IS, IS), Image.LANCZOS)
    im.paste(pic, (x, y), mask)
    d.text((x + IS // 2, y + IS + 58), cap, font=f(30, True), fill=accent, anchor="mm")

items = [
    "深紫夜空径向渐变底，替代原来的米白纯色底",
    "粉 → 紫线性渐变表圈，环上加法式玻璃高光弧",
    "内盘单独压暗成形，做出立体纵深感",
    "10:10 圆头指针 + 粉色轴心点缀",
    "弯月 + 双星，点题「静夜」气质（月牙用奇偶填充重画，修掉了此前整块渲染不出的问题）",
    "自适应图标 108dp 安全区内构图，任意遮罩不裁切",
    "全程矢量绘制，各分辨率下边缘锐利不糊",
]
by = y + IS + 112
d.text((52, by), "本次图标重做的要点", font=f(26, True), fill=TXT)
by += 46
for it in items:
    d.ellipse([56, by + 11, 64, by + 19], fill=ACC)
    d.text((74, by), it, font=f(22), fill=(226, 220, 238))
    by += 35

im.save("15-桌面图标·升级前后对比.png")
print("saved", im.size, "centers", old_c, new_c)
