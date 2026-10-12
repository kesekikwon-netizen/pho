#!/usr/bin/env python3
"""0.1.38 앱 아이콘: 어둡고 굵게(짙은 남색 바탕 + 흰 십자 기준점 + 주황 모종삽). PIL 만 씀.
출력: src/main/res/drawable-v26/ic_launcher_{background,foreground,monochrome}.xml, mipmap-*/ic_launcher(_round).png,
      build-reports/0.1.38/icon/*.png (미리보기)
실행: python3 tools/icon/gen_icon_0138.py
"""
import math, os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(ROOT, "src/main/res")
PREV = "/workspace/survey-app/build-reports/0.1.38/icon"

BG_TOP, BG_BOT = "#10304A", "#081624"   # 짙은 남색(밤하늘·먹)
WHITE = "#FFFFFF"
BLADE = "#FF6B2C"                        # 굵은 주황(발굴 흙·트라울)
FERRULE = "#9FB3C8"
CX, CY = 430.0, 594.0                    # 십자 기준점
RING_R, RING_W = 120.0, 44.0
ARM = 190.0
ROT = 45.0

def bez(p0, p1, p2, p3, n=32):
    return [((1-t)**3*p0[0]+3*(1-t)**2*t*p1[0]+3*(1-t)*t*t*p2[0]+t**3*p3[0],
             (1-t)**3*p0[1]+3*(1-t)**2*t*p1[1]+3*(1-t)*t*t*p2[1]+t**3*p3[1]) for t in [i/n for i in range(n+1)]]
def quad(p0, p1, p2, n=16):
    return [((1-t)**2*p0[0]+2*(1-t)*t*p1[0]+t*t*p2[0], (1-t)**2*p0[1]+2*(1-t)*t*p1[1]+t*t*p2[1]) for t in [i/n for i in range(n+1)]]

BLADE_D = "M0,0C40,-50 96,-120 96,-205Q96,-245 60,-250H-60Q-96,-245 -96,-205C-96,-120 -40,-50 0,0Z"
FERRULE_D = "M-16,-248H16V-292H-16Z"
GRIP_D = "M-34,-360Q-34,-392 -2,-392H2Q34,-392 34,-360V-290H-34Z"
def blade_pts():
    return bez((0,0),(40,-50),(96,-120),(96,-205)) + quad((96,-205),(96,-245),(60,-250)) + [(-60,-250)] + \
        quad((-60,-250),(-96,-245),(-96,-205)) + bez((-96,-205),(-96,-120),(-40,-50),(0,0))
def grip_pts():
    return [(-34,-290),(-34,-360)] + quad((-34,-360),(-34,-392),(-2,-392)) + quad((2,-392),(34,-392),(34,-360)) + [(34,-290)]
def ferrule_pts():
    return [(-16,-248),(16,-248),(16,-292),(-16,-292)]
def rot(pts):
    a = math.radians(ROT); c, s = math.cos(a), math.sin(a)
    return [(CX + x*c - y*s, CY + x*s + y*c) for x, y in pts]

def draw(size, mono=False, bg=True, crop=1.0, round_mask=False):
    S = size * 4
    k = S / (1024 * crop); off = 1024 * (1 - crop) / 2
    P = lambda p: ((p[0]-off)*k, (p[1]-off)*k)
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    if bg:
        top = tuple(int(BG_TOP[i:i+2], 16) for i in (1, 3, 5)); bot = tuple(int(BG_BOT[i:i+2], 16) for i in (1, 3, 5))
        for y in range(S):
            t = y / (S-1); d.line([(0, y), (S, y)], fill=tuple(int(top[j]*(1-t)+bot[j]*t) for j in range(3)) + (255,))
    w = WHITE
    # 십자
    for (x0, y0, x1, y1) in [(CX-ARM, CY, CX+ARM, CY), (CX, CY-ARM, CX, CY+ARM)]:
        d.line([P((x0, y0)), P((x1, y1))], fill=w, width=int(RING_W*k))
        for (x, y) in [(x0, y0), (x1, y1)]:
            r = RING_W/2*k; q = P((x, y)); d.ellipse([q[0]-r, q[1]-r, q[0]+r, q[1]+r], fill=w)
    r0 = (RING_R+RING_W/2)*k; r1 = (RING_R-RING_W/2)*k; c = P((CX, CY))
    d.ellipse([c[0]-r0, c[1]-r0, c[0]+r0, c[1]+r0], fill=w)
    d.ellipse([c[0]-r1, c[1]-r1, c[0]+r1, c[1]+r1], fill=(0, 0, 0, 0) if not bg else None)
    if bg:  # 고리 안쪽 바탕 다시 칠하기(그라데이션 근사: 그 높이 색)
        t = c[1]/S; top = tuple(int(BG_TOP[i:i+2], 16) for i in (1, 3, 5)); bot = tuple(int(BG_BOT[i:i+2], 16) for i in (1, 3, 5))
        col = tuple(int(top[j]*(1-t)+bot[j]*t) for j in range(3)) + (255,)
        d.ellipse([c[0]-r1, c[1]-r1, c[0]+r1, c[1]+r1], fill=col)
    else:
        d.ellipse([c[0]-r1, c[1]-r1, c[0]+r1, c[1]+r1], fill=(0, 0, 0, 0))
    # 십자 가운데 다시(고리 안)
    for (x0, y0, x1, y1) in [(CX-RING_R, CY, CX+RING_R, CY), (CX, CY-RING_R, CX, CY+RING_R)]:
        d.line([P((x0, y0)), P((x1, y1))], fill=w, width=int(RING_W*0.6*k))
    d.polygon([P(p) for p in rot(blade_pts())], fill=w if mono else BLADE)
    d.polygon([P(p) for p in rot(ferrule_pts())], fill=w if mono else FERRULE)
    d.polygon([P(p) for p in rot(grip_pts())], fill=w)
    im = im.resize((size, size), Image.LANCZOS)
    if round_mask or crop < 1.0:
        m = Image.new("L", (size*4, size*4), 0); md = ImageDraw.Draw(m)
        if round_mask: md.ellipse([0, 0, size*4-1, size*4-1], fill=255)
        else: md.rounded_rectangle([0, 0, size*4-1, size*4-1], radius=int(size*4*0.18), fill=255)
        m = m.resize((size, size), Image.LANCZOS)
        im.putalpha(Image.fromarray(__import__("numpy").minimum(__import__("numpy").array(im.split()[3]), __import__("numpy").array(m))) if False else m)
    return im

def w(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w", encoding="utf-8").write(text)

HDR = '<?xml version="1.0" encoding="utf-8"?>\n<!-- 자동 생성: tools/icon/gen_icon_0138.py (0.1.38 어둡고 굵은 아이콘) -->\n'
def fg_xml(mono):
    blade = WHITE if mono else BLADE; fer = WHITE if mono else FERRULE
    ring = f'M{CX-RING_R},{CY}a{RING_R},{RING_R} 0 1,0 {2*RING_R},0a{RING_R},{RING_R} 0 1,0 {-2*RING_R},0Z'
    return HDR + f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="1024" android:viewportHeight="1024">
    <path android:pathData="M{CX-ARM},{CY}H{CX+ARM}M{CX},{CY-ARM}V{CY+ARM}"
        android:strokeColor="{WHITE}" android:strokeWidth="{RING_W}" android:strokeLineCap="round" />
    <path android:pathData="{ring}" android:strokeColor="{WHITE}" android:strokeWidth="{RING_W}" />
    <group android:translateX="{CX}" android:translateY="{CY}" android:rotation="{ROT}">
        <path android:pathData="{BLADE_D}" android:fillColor="{blade}" />
        <path android:pathData="{FERRULE_D}" android:fillColor="{fer}" />
        <path android:pathData="{GRIP_D}" android:fillColor="{WHITE}" />
    </group>
</vector>
'''
BG_XML = HDR + f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="1024" android:viewportHeight="1024">
    <path android:pathData="M0,0H1024V1024H0Z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="0" android:endY="1024">
                <item android:offset="0" android:color="#FF{BG_TOP[1:]}" />
                <item android:offset="1" android:color="#FF{BG_BOT[1:]}" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''

if __name__ == "__main__":
    w(os.path.join(RES, "drawable-v26/ic_launcher_background.xml"), BG_XML)
    w(os.path.join(RES, "drawable-v26/ic_launcher_foreground.xml"), fg_xml(False))
    w(os.path.join(RES, "drawable-v26/ic_launcher_monochrome.xml"), fg_xml(True))
    for name, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        draw(px, crop=0.78).save(os.path.join(RES, f"mipmap-{name}/ic_launcher.png"))
        draw(px, crop=0.78, round_mask=True).save(os.path.join(RES, f"mipmap-{name}/ic_launcher_round.png"))
    os.makedirs(PREV, exist_ok=True)
    draw(512, crop=0.78).save(os.path.join(PREV, "ic_launcher_0138_512.png"))
    draw(512, crop=0.78, round_mask=True).save(os.path.join(PREV, "ic_launcher_0138_round_512.png"))
    draw(1024).save(os.path.join(PREV, "ic_launcher_0138_full_1024.png"))
    draw(1024, bg=False).save(os.path.join(PREV, "ic_launcher_0138_fg_1024.png"))  # 전경만(시험 기준)
    print("ok")
