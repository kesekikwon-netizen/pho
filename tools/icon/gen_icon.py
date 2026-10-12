#!/usr/bin/env python3
"""사진 기준점 앱 아이콘 — 직접 그린 원본 도형(먹색 바탕 + 굵은 흰 네모 표적 + 체크 2칸 + 테라코타 십자 눈금).
같은 기하로 adaptive vector(foreground/background/monochrome), 알림 아이콘, 구형 mipmap PNG, 미리보기 PNG 를 만든다.
좌표계: 108×108 (adaptive icon dp). 안전 영역 지름 66 → 중심 54 에서 반경 33 안."""
import os, sys
from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
RES = os.path.join(ROOT, "src/main/res")
BG = "#16181B"; WHITE = "#FFFFFF"; INK = "#16181B"; ACC = "#E2734A"

# 기하(108 기준)
FRAME = (33, 33, 75, 75); FRAME_W = 7          # 흰 네모 테두리(바깥 33..75)
CELL = [(43, 43, 54, 54), (54, 54, 65, 65)]    # 흰 체크 2칸
TICKS = [((54, 23), (54, 31)), ((54, 77), (54, 85)), ((23, 54), (31, 54)), ((77, 54), (85, 54))]
TICK_W = 6

def vec_fg(color_frame=WHITE, color_cell=WHITE, color_tick=ACC):
    x0, y0, x1, y1 = FRAME; w = FRAME_W
    outer = f"M{x0},{y0}H{x1}V{y1}H{x0}Z"; inner = f"M{x0+w},{y0+w}V{y1-w}H{x1-w}V{y0+w}Z"
    cells = "".join(f"M{a},{b}H{c}V{d}H{a}Z" for a, b, c, d in CELL)
    ticks = "".join(f"M{p[0]},{p[1]}L{q[0]},{q[1]}" for p, q in TICKS)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 자동 생성: tools/icon/gen_icon.py (원본 도형, 외부 그림·로고 없음) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="{color_frame}" android:pathData="{outer}{inner}" />
    <path android:fillColor="{color_cell}" android:pathData="{cells}" />
    <path android:strokeColor="{color_tick}" android:strokeWidth="{TICK_W}" android:strokeLineCap="square" android:pathData="{ticks}" />
</vector>
'''

def write(p, s):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8").write(s)

def raster(size, round_mask=False, legacy=True):
    S = 4 * size; k = S / 108.0
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    if legacy:
        # 구형 런처: 둥근 네모(또는 원) 바탕 안에 같은 그림을 조금 키워(여백 줄임) 넣는다
        pad = 0.06 * S
        if round_mask: d.ellipse((pad, pad, S - pad, S - pad), fill=BG)
        else: d.rounded_rectangle((pad, pad, S - pad, S - pad), radius=0.2 * S, fill=BG)
        k = S / 108.0 * 1.12; off = (S - 108 * k) / 2
    else:
        d.rectangle((0, 0, S, S), fill=BG); off = 0
    T = lambda v: off + v * k
    x0, y0, x1, y1 = FRAME
    d.rectangle((T(x0), T(y0), T(x1), T(y1)), fill=WHITE)
    d.rectangle((T(x0 + FRAME_W), T(y0 + FRAME_W), T(x1 - FRAME_W), T(y1 - FRAME_W)), fill=BG)
    for a, b, c, e in CELL: d.rectangle((T(a), T(b), T(c), T(e)), fill=WHITE)
    for p, q in TICKS:
        hw = TICK_W / 2
        if p[0] == q[0]: d.rectangle((T(p[0] - hw), T(min(p[1], q[1]) - hw), T(p[0] + hw), T(max(p[1], q[1]) + hw)), fill=ACC)
        else: d.rectangle((T(min(p[0], q[0]) - hw), T(p[1] - hw), T(max(p[0], q[0]) + hw), T(p[1] + hw)), fill=ACC)
    return im.resize((size, size), Image.LANCZOS)

def main(preview_dir):
    write(f"{RES}/drawable/ic_launcher_foreground.xml", vec_fg())
    write(f"{RES}/drawable/ic_launcher_monochrome.xml", vec_fg("#FFFFFFFF", "#FFFFFFFF", "#FFFFFFFF"))
    write(f"{RES}/drawable/ic_launcher_background.xml", f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="{BG}" android:pathData="M0,0H108V108H0Z" />
</vector>
''')
    adaptive = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''
    write(f"{RES}/mipmap-anydpi-v26/ic_launcher.xml", adaptive)
    write(f"{RES}/mipmap-anydpi-v26/ic_launcher_round.xml", adaptive)
    # 알림 아이콘(흰색 하나, 24dp): 네모 테두리 + 체크 2칸
    write(f"{RES}/drawable/ic_stat_survey.xml", '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M3,3H21V21H3ZM6,6V18H18V6Z" />
    <path android:fillColor="#FFFFFFFF" android:pathData="M8,8H12V12H8ZM12,12H16V16H12Z" />
</vector>
''')
    for q, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        os.makedirs(f"{RES}/mipmap-{q}", exist_ok=True)
        raster(px).save(f"{RES}/mipmap-{q}/ic_launcher.png")
        raster(px, round_mask=True).save(f"{RES}/mipmap-{q}/ic_launcher_round.png")
    if preview_dir:
        os.makedirs(preview_dir, exist_ok=True)
        raster(512, legacy=False).save(f"{preview_dir}/icon-512-full-bleed.png")
        raster(512).save(f"{preview_dir}/icon-512-legacy.png")
        raster(512, round_mask=True).save(f"{preview_dir}/icon-512-round.png")

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else None)
