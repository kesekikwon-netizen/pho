#!/usr/bin/env python3
"""WP-11: 아이콘 B안(모종삽+십자 기준점) -> Android 리소스 생성기.
입력: design-system/mockups/v2/icon/final/ (SVG 도형 좌표를 그대로 옮겨 적음, 출처 launcher/icon_B_fg.svg, small/small_mark_ink.svg)
출력: src/main/res/{drawable-v26,mipmap-anydpi-v26,mipmap-*,drawable,drawable-nodpi}
실행: PYTHONPATH=/tmp/shp python3 tools/icon/gen_icon_resources.py   (shapely 필요: 십자선이 삽 윤곽에 가려지는 구간을 미리 계산)
"""
import math, os, sys
from shapely.geometry import LineString, Polygon, Point
from shapely.ops import unary_union
from shapely import affinity
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(ROOT, "src/main/res")
SRC = os.path.abspath(os.path.join(ROOT, "../../survey-app/design-system/mockups/v2/icon/final")) \
    if False else "/workspace/survey-app/design-system/mockups/v2/icon/final"

CX, CY = 380.0, 650.0   # 십자 기준점(1024 캔버스 좌표)
ROT = 45.0


def w(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def bez(p0, p1, p2, p3, n=24):
    out = []
    for i in range(n + 1):
        t = i / n
        a = (1 - t) ** 3; b = 3 * (1 - t) ** 2 * t; c = 3 * (1 - t) * t * t; d = t ** 3
        out.append((a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1]))
    return out


def quad(p0, p1, p2, n=10):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]) for t in [i / n for i in range(n + 1)]]


def blade_poly(v):
    """모종삽 날(국소 좌표, 팁이 원점). v=(날 폭 계수 변형) 단순판은 약간 다른 곡선"""
    if v == "full":
        a, b, c, d = (0, 0), (30, -40), (76, -100), (76, -170)
        pts = bez(a, b, c, d) + quad((76, -170), (76, -202), (48, -206))
        pts += [(-48, -206)] + quad((-48, -206), (-76, -202), (-76, -170))
        pts += bez((-76, -170), (-76, -100), (-30, -40), (0, 0))
    else:
        pts = bez((0, 0), (34, -44), (72, -100), (72, -168)) + quad((72, -168), (72, -198), (46, -200))
        pts += [(-46, -200)] + quad((-46, -200), (-72, -198), (-72, -168))
        pts += bez((-72, -168), (-72, -100), (-34, -44), (0, 0))
    return Polygon(pts).buffer(0)


def to_global(geom):
    g = affinity.rotate(geom, ROT, origin=(0, 0))   # shapely: 양의 각 = 반시계(y 위). SVG(y 아래)는 시계 -> 부호 반전 필요
    return g


def svg_rot(geom):
    # SVG rotate(45) (y 아래 좌표계, 시계 방향) == shapely에서 y축이 뒤집혀 있으므로 같은 수식: x' = x cos - y sin, y' = x sin + y cos
    c, s = math.cos(math.radians(ROT)), math.sin(math.radians(ROT))
    return affinity.affine_transform(geom, [c, -s, s, c, CX, CY])


def trowel_shapes(v):
    blade = blade_poly(v)
    if v == "full":
        neck = Polygon([(-20, -200), (20, -200), (20, -262), (-20, -262)])
        handle = Polygon([(-27, -290), (27, -290), (27, -448)] + quad((27, -448), (27, -474), (0, -474)) +
                         quad((0, -474), (-27, -474), (-27, -448)))
    else:
        neck = Polygon([(-14, -200), (14, -200), (14, -254), (-14, -254)])
        handle = Polygon([(-26, -254), (26, -254), (26, -436)] + quad((26, -436), (26, -462), (0, -462)) +
                         quad((0, -462), (-26, -462), (-26, -436)))
    return [svg_rot(s) for s in (blade, neck, handle)]


def pd_poly(poly, prec=1):
    def ring(coords):
        pts = list(coords)[:-1]
        s = "M%.*f,%.*f" % (prec, pts[0][0], prec, pts[0][1])
        for x, y in pts[1:]:
            s += "L%.*f,%.*f" % (prec, x, prec, y)
        return s + "Z"
    geoms = poly.geoms if hasattr(poly, "geoms") else [poly]
    out = ""
    for g in geoms:
        if g.is_empty:
            continue
        out += ring(g.exterior.coords)
        for i in g.interiors:
            out += ring(i.coords)
    return out


def cross_ink(stroke, circle_r, knock, extra_arm=0):
    """십자선+원을 삽 주변(knock)만큼 비워서 채움 도형으로 반환"""
    lines = unary_union([LineString([(258, CY), (502, CY)]), LineString([(CX, 528), (CX, 772)])]).buffer(stroke / 2, cap_style=1, join_style=1)
    ring = Point(CX, CY).buffer(circle_r + stroke / 2, 64).difference(Point(CX, CY).buffer(circle_r - stroke / 2, 64))
    ink = unary_union([lines, ring])
    trowel = unary_union(trowel_shapes("small")).buffer(knock, join_style=1)
    return ink.difference(trowel).simplify(0.25)


# --- 삽 윤곽 stroke 경로(국소 좌표, 그룹 transform 사용) ---
BLADE_FULL = "M0,0C30,-40 76,-100 76,-170Q76,-202 48,-206H-48Q-76,-202 -76,-170C-76,-100 -30,-40 0,0Z"
BLADE_SMALL = "M0,0C34,-44 72,-100 72,-168Q72,-198 46,-200H-46Q-72,-198 -72,-168C-72,-100 -34,-44 0,0Z"
HANDLE_FULL = "M-27,-290H27V-448Q27,-474 0,-474Q-27,-474 -27,-448Z"
HANDLE_SMALL = "M-26,-254H26V-436Q26,-462 0,-462Q-26,-462 -26,-436Z"

HEAD = ('<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- 자동 생성: tools/icon/gen_icon_resources.py (원본 design-system/mockups/v2/icon/final) -->\n')


def vec(name_w, name_h, vw, vh, body, extra=""):
    return (HEAD + '<vector xmlns:android="http://schemas.android.com/apk/res/android"%s\n'
            '    android:width="%sdp" android:height="%sdp"\n'
            '    android:viewportWidth="%s" android:viewportHeight="%s">\n%s</vector>\n') % (extra, name_w, name_h, vw, vh, body)


def path(d, fill=None, stroke=None, sw=None, alpha=None, cap=True, ind="    "):
    a = ind + "<path\n"
    a += ind + '    android:pathData="%s"' % d
    if fill: a += '\n' + ind + '    android:fillColor="%s"' % fill
    if stroke:
        a += '\n' + ind + '    android:strokeColor="%s"\n' % stroke + ind + '    android:strokeWidth="%s"' % sw
        a += '\n' + ind + '    android:strokeLineCap="round" android:strokeLineJoin="round"'
        if alpha is not None: a += '\n' + ind + '    android:strokeAlpha="%s"' % alpha
    return a + " />\n"


def group_trowel(inner, tx=0.0, ty=0.0):
    return ('    <group android:translateX="%s" android:translateY="%s" android:pivotX="0" android:pivotY="0" android:rotation="45">\n%s    </group>\n'
            % (CX + tx, CY + ty, inner))


def small_mark_body(color, sw, tx=0, ty=0):
    ink = cross_ink(sw, 70, sw)
    body = '    <group android:translateX="%s" android:translateY="%s">\n' % (tx, ty)
    body += path(pd_poly(ink), fill=color, ind="        ")
    inner = path(BLADE_SMALL, stroke=color, sw=sw, ind="        ")
    inner += path("M-14,-200V-254M14,-200V-254", stroke=color, sw=sw, ind="        ")
    inner += path(HANDLE_SMALL, stroke=color, sw=sw, ind="        ")
    body += ('        <group android:translateX="%s" android:translateY="%s" android:pivotX="0" android:pivotY="0" android:rotation="45">\n%s        </group>\n'
             % (CX, CY, inner))
    body += "    </group>\n"
    return body


def main():
    INK, TERRA, TERRA2, CREAM, CARD, DOT = "#141413", "#D97757", "#C6613F", "#FAF9F5", "#F0EEE6", "#141413"
    # 1) 적응형 전경(컬러) 108dp, 1024 viewport
    b = ""
    b += path("M318,650a62,62 0 1,0 124,0a62,62 0 1,0 -124,0Z", fill=CREAM)
    b += path("M380,650V588A62,62 0 0 1 442,650Z", fill=TERRA2)
    b += path("M380,650V712A62,62 0 0 1 318,650Z", fill=TERRA2)
    b += path("M318,650a62,62 0 1,0 124,0a62,62 0 1,0 -124,0Z", stroke=INK, sw=9)
    b += path("M258,650H502M380,528V772", stroke=INK, sw=9)
    g = path(BLADE_FULL, fill=CARD, stroke=INK, sw=13, ind="        ")
    g += path("M0,-30V-170", stroke=INK, sw=8, alpha=0.55, ind="        ")
    g += path("M-13,-206V-262M13,-206V-262", stroke=INK, sw=13, ind="        ")
    g += path("M-19,-262H19V-290H-19Z", fill=INK, ind="        ")
    g += path(HANDLE_FULL, fill=TERRA, stroke=INK, sw=13, ind="        ")
    g += path("M-12,-318V-440", stroke="#FFFFFF", sw=9, alpha=0.35, ind="        ")
    b += ('    <group android:translateX="%s" android:translateY="%s" android:pivotX="0" android:pivotY="0" android:rotation="45">\n%s    </group>\n' % (CX, CY, g))
    w(RES + "/drawable-v26/ic_launcher_foreground.xml", vec(108, 108, 1024, 1024, b))

    # 2) 적응형 배경: 아이보리 그라데이션 + 옅은 128 격자 + 상단 하이라이트 (그라데이션은 API 24+ 이므로 v26 폴더)
    bg = ('    <path android:pathData="M0,0H1024V1024H0Z">\n        <aapt:attr name="android:fillColor">\n'
          '            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="0" android:endY="1024">\n'
          '                <item android:offset="0" android:color="#FFFDFCF9" />\n                <item android:offset="1" android:color="#FFEFEDE4" />\n'
          '            </gradient>\n        </aapt:attr>\n    </path>\n')
    grid = "".join("M%d,0V1024M0,%dH1024" % (i * 128, i * 128) for i in range(0, 9))
    bg += path(grid, stroke="#E8E6DC", sw=3, alpha=0.7)
    bg += ('    <path android:pathData="M-220,140a620,420 0 1,0 1240,0a620,420 0 1,0 -1240,0Z">\n        <aapt:attr name="android:fillColor">\n'
           '            <gradient android:type="linear" android:startX="0" android:startY="-280" android:endX="0" android:endY="560">\n'
           '                <item android:offset="0" android:color="#8CFFFFFF" />\n                <item android:offset="1" android:color="#00FFFFFF" />\n'
           '            </gradient>\n        </aapt:attr>\n    </path>\n')
    w(RES + "/drawable-v26/ic_launcher_background.xml",
      vec(108, 108, 1024, 1024, bg, extra='\n    xmlns:aapt="http://schemas.android.com/aapt"'))

    # 3) 모노크롬(Android 13 테마 아이콘): 단순판 도형을 직접 제작, 한 색(알파만 사용). 안전영역(반경 313) 안인지 아래에서 검사
    mono_sw = 36
    w(RES + "/drawable-v26/ic_launcher_monochrome.xml", vec(108, 108, 1024, 1024, small_mark_body("#FF000000", mono_sw)))
    # 안전영역 검사 (stroke 바깥 가장자리 포함)
    ink = cross_ink(mono_sw, 70, mono_sw)
    tro = unary_union(trowel_shapes("small")).buffer(mono_sw / 2)
    allg = unary_union([ink, tro])
    far = max(math.hypot(x - 512, y - 512) for x, y in allg.exterior.coords) if allg.geom_type == "Polygon" else \
        max(math.hypot(x - 512, y - 512) for g in allg.geoms for x, y in g.exterior.coords)
    print("monochrome 최대 반경(안전영역 313):", round(far, 1))
    fg_far = max(math.hypot(x - 512, y - 512) for s in trowel_shapes("full") for x, y in s.exterior.coords) + 6.5
    print("컬러 전경 삽 최대 반경(선 두께 포함):", round(fg_far, 1))
    assert far < 313 and fg_far < 313

    # 4) mipmap-anydpi-v26
    for n in ("ic_launcher", "ic_launcher_round"):
        w(RES + "/mipmap-anydpi-v26/%s.xml" % n,
          HEAD + '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
          '    <background android:drawable="@drawable/ic_launcher_background" />\n'
          '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
          '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>\n')

    # 5) 단순판 벡터 (24~48dp 용): 원본 small_mark_*.svg viewBox 232.5 277.5 520 520
    for nm, col in (("ink", INK), ("terracotta", TERRA2)):
        w(RES + "/drawable/ic_small_mark_%s.xml" % nm,
          vec(48, 48, 520, 520, small_mark_body(col, 30, tx=-232.5, ty=-277.5)))
    # 6) 앱 내 로고 마크 (벡터, 홈/서랍 상단): 전경과 같은 도형을 단색 라인 + 십자 knock-out. 원본 logo_B_mark_*.svg viewBox 212 212 600 600
    for nm, col in (("terracotta", TERRA2), ("ink", INK)):
        w(RES + "/drawable/ic_logo_mark_%s.xml" % nm,
          vec(96, 96, 600, 600, small_mark_body(col, 14, tx=-212, ty=-212)))

    # 7) 레거시 비트맵 (API 21~25): 원본(72dp 표시영역)을 둥근 사각/원으로 마스크
    orig = Image.open(SRC + "/launcher/icon_B_original_1024.png").convert("RGBA")
    dens = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for d, px in dens.items():
        os.makedirs(RES + "/mipmap-" + d, exist_ok=True)
        S = px * 4
        im = orig.resize((S, S), Image.LANCZOS)
        m = Image.new("L", (S, S), 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.18), fill=255)
        sq = Image.new("RGBA", (S, S), (0, 0, 0, 0)); sq.paste(im, (0, 0), m)
        sq.resize((px, px), Image.LANCZOS).save(RES + "/mipmap-%s/ic_launcher.png" % d, optimize=True)
        m2 = Image.new("L", (S, S), 0); ImageDraw.Draw(m2).ellipse([0, 0, S - 1, S - 1], fill=255)
        rd = Image.new("RGBA", (S, S), (0, 0, 0, 0)); rd.paste(im, (0, 0), m2)
        rd.resize((px, px), Image.LANCZOS).save(RES + "/mipmap-%s/ic_launcher_round.png" % d, optimize=True)
    # 구버전 임시 아이콘 제거
    for p in (RES + "/mipmap-mdpi/ic_launcher.xml", RES + "/drawable/ic_launcher_fg.xml"):
        if os.path.exists(p): os.remove(p)

    # 8) 가로 로고(글자 윤곽선 포함 PNG) 앱 내용
    for nm in ("terracotta", "ink"):
        im = Image.open(SRC + "/logo/logo_B_horizontal_%s.png" % nm)
        im.save(RES + "/drawable-nodpi/logo_horizontal_%s.png" % nm, optimize=True) if os.makedirs(RES + "/drawable-nodpi", exist_ok=True) is None else None


if __name__ == "__main__":
    main()
