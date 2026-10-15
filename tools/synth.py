#!/usr/bin/env python3
"""합성 시험 자료: 표적 4개(iTwin 「Target #1..4」 그림 그대로)를 깐 4×3 m 발굴면(완만한 경사)을 폰 카메라 16장으로 찍은 사진.
- 좌표: EPSG:5187 비슷한 값(E 245100, N 512300 근처), 표적 검은 네모 한 변 0.20 m
- ARCore 처럼 다른 틀(로컬: x 동, y 위, z 남 → 축 Y 로 37° 회전 + 이동 + 축척 1/1.02)에서 카메라 자세를 적어 둔다.
결과: testdata/synth/IMG_0001..jpg + truth.json"""
import json, math, os, glob
import numpy as np
from PIL import Image, ImageFilter
ROOT = os.path.join(os.path.dirname(__file__), "..")
OUT = os.path.join(ROOT, "testdata/synth"); os.makedirs(OUT, exist_ok=True)
rng = np.random.default_rng(7)
E0, N0, H0 = 245100.0, 512300.0, 57.0
RES = 0.002  # 바닥 그림 2 mm/px
X0, X1, Y0, Y1 = -0.8, 4.8, -0.8, 3.8
W = int((X1 - X0) / RES); H = int((Y1 - Y0) / RES)
slope = (0.03, 0.05)  # z = a x + b y
# 흙 무늬: 여러 크기의 얼룩 + 잡음
def noise(scale):
    s = rng.random((H // scale + 2, W // scale + 2)).astype(np.float32)
    im = Image.fromarray((s * 255).astype(np.uint8)).resize((W, H), Image.BICUBIC)
    return np.asarray(im, np.float32) / 255
tex = 0.35 * noise(200) + 0.3 * noise(40) + 0.2 * noise(8) + 0.15 * rng.random((H, W)).astype(np.float32)
base = np.stack([150 + 70 * tex, 110 + 60 * tex, 75 + 50 * tex], -1)
TAGS = {1: (0.4, 0.4), 2: (3.6, 0.5), 3: (3.5, 2.6), 4: (0.5, 2.5)}
SIZE = 0.20
for no, (cx, cy) in TAGS.items():
    f = glob.glob(os.path.join(ROOT, f"targets/*AprilTags_{no}.png"))[0]
    g = np.asarray(Image.open(f).convert("L"), np.float32)
    crop = g[102 - 100:902 + 100, 102 - 100:902 + 100]  # 검은 네모 800px + 흰 테 1칸(100px)
    side_px = int(round(SIZE * 10 / 8 / RES))  # 흰 테 포함 10칸 = 0.25 m
    t = np.asarray(Image.fromarray(crop.astype(np.uint8)).resize((side_px, side_px), Image.LANCZOS), np.float32)
    # 그림 위 = 북쪽(+y). 바닥 그림 행은 y 가 줄어드는 방향(위 = 북)
    r0 = int(round((Y1 - cy) / RES - side_px / 2)); c0 = int(round((cx - X0) / RES - side_px / 2))
    base[r0:r0 + side_px, c0:c0 + side_px] = t[..., None]
    # 실제로 붙은 자리(화소 격자에 맞춘) 중심을 참값으로
    TAGS[no] = (X0 + (c0 + side_px / 2) * RES, Y1 - (r0 + side_px / 2) * RES)
base = np.clip(base, 0, 255).astype(np.float32)
Wimg, Himg, F = 2000, 1500, 1650.0
CX, CY = Wimg / 2, Himg / 2
def look(C, L, up=(0, 0, 1)):
    z = np.array(L) - C; z /= np.linalg.norm(z)
    x = np.cross(z, up); x /= np.linalg.norm(x)
    y = np.cross(z, x)
    return np.stack([x, y, z], 1)  # 열 = 카메라 x(오른쪽), y(아래), z(앞) 의 세계 방향 = R_cw
def zsurf(x, y): return slope[0] * x + slope[1] * y
cams = []
centre = np.array([2.0, 1.5, 0.0])
for k in range(16):
    ang = 2 * math.pi * k / 16
    rad = 2.6 if k % 2 == 0 else 1.8
    C = np.array([2.0 + rad * math.cos(ang), 1.5 + 0.8 * rad * math.sin(ang), 1.7 + 0.25 * (k % 3)])
    L = centre + np.array([0.5 * math.cos(ang + 1), 0.4 * math.sin(ang + 1), 0])
    cams.append((C, look(C, L)))
# 로컬(ARCore 흉내) 틀
th = math.radians(37); s_loc = 1 / 1.02; T_loc = np.array([0.31, -1.42, 0.77])
A = np.array([[1, 0, 0], [0, 0, 1], [0, -1, 0]], float)  # 세계(동,북,위) → (동, 위, 남)
Ry = np.array([[math.cos(th), 0, math.sin(th)], [0, 1, 0], [-math.sin(th), 0, math.cos(th)]])
M_loc = Ry @ A
def to_local(p): return s_loc * (M_loc @ p) + T_loc
def quat(R):
    t = np.trace(R)
    if t > 0:
        s = math.sqrt(t + 1) * 2; w = s / 4; x = (R[2, 1] - R[1, 2]) / s; y = (R[0, 2] - R[2, 0]) / s; z = (R[1, 0] - R[0, 1]) / s
    else:
        i = int(np.argmax(np.diag(R)))
        if i == 0:
            s = math.sqrt(1 + R[0, 0] - R[1, 1] - R[2, 2]) * 2; w = (R[2, 1] - R[1, 2]) / s; x = s / 4; y = (R[0, 1] + R[1, 0]) / s; z = (R[0, 2] + R[2, 0]) / s
        elif i == 1:
            s = math.sqrt(1 + R[1, 1] - R[0, 0] - R[2, 2]) * 2; w = (R[0, 2] - R[2, 0]) / s; x = (R[0, 1] + R[1, 0]) / s; y = s / 4; z = (R[1, 2] + R[2, 1]) / s
        else:
            s = math.sqrt(1 + R[2, 2] - R[0, 0] - R[1, 1]) * 2; w = (R[1, 0] - R[0, 1]) / s; x = (R[0, 2] + R[2, 0]) / s; y = (R[1, 2] + R[2, 1]) / s; z = s / 4
    return [x, y, z, w]
uu, vv = np.meshgrid(np.arange(Wimg) + 0.5, np.arange(Himg) + 0.5)
truth = {"fx": F, "fy": F, "cx": CX, "cy": CY, "w": Wimg, "h": Himg, "tagSize": SIZE, "gcps": {}, "stills": []}
for no, (x, y) in TAGS.items():
    truth["gcps"][str(no)] = [E0 + x, N0 + y, H0 + zsurf(x, y)]
for k, (C, R) in enumerate(cams):
    d = np.stack([(uu - CX) / F, (vv - CY) / F, np.ones_like(uu)], -1) @ R.T
    a, b = slope
    tpar = (a * C[0] + b * C[1] - C[2]) / (d[..., 2] - a * d[..., 0] - b * d[..., 1])
    X = C[0] + tpar * d[..., 0]; Y = C[1] + tpar * d[..., 1]
    col = (X - X0) / RES - 0.5; row = (Y1 - Y) / RES - 0.5  # 화소 중심 = 정수
    ok = (tpar > 0) & (col >= 0) & (col < W - 1) & (row >= 0) & (row < H - 1)
    c0 = np.clip(col.astype(int), 0, W - 2); r0 = np.clip(row.astype(int), 0, H - 2)
    fc = (col - c0)[..., None]; fr = (row - r0)[..., None]
    img = (base[r0, c0] * (1 - fc) * (1 - fr) + base[r0, c0 + 1] * fc * (1 - fr) + base[r0 + 1, c0] * (1 - fc) * fr + base[r0 + 1, c0 + 1] * fc * fr)
    img[~ok] = (60, 55, 50)
    shade = 0.9 + 0.1 * math.cos(k)
    im = Image.fromarray(np.clip(img * shade + rng.normal(0, 2.5, img.shape), 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.6))
    name = f"IMG_{k + 1:04d}.jpg"; im.save(os.path.join(OUT, name), quality=93)
    marks = {}
    for no, (x, y) in TAGS.items():
        p = np.array([x, y, zsurf(x, y)]); q = R.T @ (p - C)
        if q[2] > 0:
            u = F * q[0] / q[2] + CX; v = F * q[1] / q[2] + CY
            # 화면 속 표적 크기(검은 네모 한 변 px): 가장자리 점 하나를 투영
            p2 = p + np.array([SIZE, 0, 0]); q2 = R.T @ (p2 - C)
            size_px = float(np.hypot(F * q2[0] / q2[2] + CX - u, F * q2[1] / q2[2] + CY - v)) if q2[2] > 0 else 0
            if 30 < u < Wimg - 30 and 30 < v < Himg - 30: marks[str(no)] = [u, v, size_px]
    R_gl = M_loc @ R @ np.diag([1, -1, -1])
    truth["stills"].append({"id": k + 1, "file": name, "pose": list(to_local(C)) + quat(R_gl), "marks": marks})
truth["local"] = {"theta_deg": 37, "scale": s_loc, "T": list(T_loc)}
json.dump(truth, open(os.path.join(OUT, "truth.json"), "w"), indent=1)
print("ok", len(cams), "photos", W, H)
