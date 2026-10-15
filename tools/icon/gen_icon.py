#!/usr/bin/env python3
"""사진측량 앱 아이콘(원본 도형): 테라코타 바탕 + 흰 와이어 메시 언덕(3D) + 작은 검은/흰 AprilTag 네모 + 흰 카메라 점.
사진 기준점(먹색 바탕·흰 표적), 발굴 측량 앱과 구분되게 바탕색과 모양을 다르게 했다. 108×108 adaptive."""
import os
from PIL import Image, ImageDraw
ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
RES = os.path.join(ROOT, "src/main/res")
BG = "#C6613F"; WHITE = "#FFFFFF"; INK = "#141413"
# 메시: 4×3 칸 사다리꼴(원근) 격자 + 가운데 볼록
def grid():
    rows = []
    for j in range(4):
        t = j / 3.0
        y = 40 + t * 34
        half = 18 + t * 16
        bump = [0, -6, -9, -4][j]
        pts = []
        for i in range(5):
            s = i / 4.0
            x = 54 - half + s * 2 * half
            b = bump * (1 - abs(s - 0.5) * 2)
            pts.append((round(x, 1), round(y + b, 1)))
        rows.append(pts)
    return rows
R = grid()
LINES = []
for row in R:
    LINES += [(row[i], row[i + 1]) for i in range(4)]
for i in range(5):
    LINES += [(R[j][i], R[j + 1][i]) for j in range(3)]
TAG = (60, 30, 74, 44)  # 오른쪽 위 작은 표적
def vec(fg_line=WHITE, tag_outer=INK, tag_in=WHITE, mono=False):
    d = "".join(f"M{a[0]},{a[1]}L{b[0]},{b[1]}" for a, b in LINES)
    x0, y0, x1, y1 = TAG
    s = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 자동 생성: tools/icon/gen_icon.py (원본 도형) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:strokeColor="{fg_line}" android:strokeWidth="2.6" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{d}" />
    <path android:fillColor="{fg_line if mono else WHITE}" android:pathData="M{x0-2},{y0-2}H{x1+2}V{y1+2}H{x0-2}Z" />
    <path android:fillColor="{tag_outer}" android:pathData="M{x0},{y0}H{x1}V{y1}H{x0}Z" />
    <path android:fillColor="{tag_in}" android:pathData="M{x0+3},{y0+3}H{x0+7}V{y0+7}H{x0+3}Z M{x0+7},{y0+7}H{x1-3}V{y1-3}H{x0+7}Z" />
</vector>
'''
    return s
def write(p, s):
    os.makedirs(os.path.dirname(p), exist_ok=True); open(p, "w", encoding="utf-8").write(s)
def raster(size, rnd=False):
    S = size * 4; k = S / 108 * 1.15; off = (S - 108 * k) / 2
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    pad = 0.06 * S
    if rnd: d.ellipse((pad, pad, S - pad, S - pad), fill=BG)
    else: d.rounded_rectangle((pad, pad, S - pad, S - pad), radius=0.2 * S, fill=BG)
    T = lambda p: (off + p[0] * k, off + p[1] * k)
    for a, b in LINES: d.line([T(a), T(b)], fill=WHITE, width=max(2, int(2.6 * k)))
    x0, y0, x1, y1 = TAG
    d.rectangle([T((x0 - 2, y0 - 2)), T((x1 + 2, y1 + 2))], fill=WHITE)
    d.rectangle([T((x0, y0)), T((x1, y1))], fill=INK)
    d.rectangle([T((x0 + 3, y0 + 3)), T((x0 + 7, y0 + 7))], fill=WHITE)
    d.rectangle([T((x0 + 7, y0 + 7)), T((x1 - 3, y1 - 3))], fill=WHITE)
    return im.resize((size, size), Image.LANCZOS)
write(f"{RES}/drawable/ic_launcher_foreground.xml", vec())
write(f"{RES}/drawable/ic_launcher_monochrome.xml", vec(fg_line="#000000", tag_outer="#000000", tag_in="#00000000", mono=True))
write(f"{RES}/drawable/ic_launcher_background.xml", f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="{BG}" android:pathData="M0,0H108V108H0Z" />
</vector>
''')
ad = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''
write(f"{RES}/mipmap-anydpi-v26/ic_launcher.xml", ad); write(f"{RES}/mipmap-anydpi-v26/ic_launcher_round.xml", ad)
for name, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    os.makedirs(f"{RES}/mipmap-{name}", exist_ok=True)
    raster(px).save(f"{RES}/mipmap-{name}/ic_launcher.png"); raster(px, True).save(f"{RES}/mipmap-{name}/ic_launcher_round.png")
raster(512).save(os.path.join(ROOT, "tools/icon/preview_512.png"))
