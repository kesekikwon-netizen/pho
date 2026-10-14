#!/usr/bin/env python3
"""목업(왼쪽) · Robolectric 렌더(오른쪽) 나란히 → build-reports/<ver>/compare/<g>.png"""
import sys, os
from PIL import Image, ImageDraw, ImageFont
ver = sys.argv[1] if len(sys.argv) > 1 else "0.1.8"
M = "/workspace/design-program/mockups-v2"
R = f"build-reports/{ver}/render"; O = f"build-reports/{ver}/compare"
os.makedirs(O, exist_ok=True)
font = ImageFont.truetype("/usr/share/fonts/truetype/sand-box/custom/Pretendard/Pretendard-SemiBold.otf", 28)
pairs = [(f, f.replace("-folded", "").replace(".png", "")) for f in sorted(os.listdir(M)) if f.startswith("g") and f.endswith(".png")]
for mock, key in pairs:
    rend = os.path.join(R, key + ".png")
    if not os.path.exists(rend): print("no render for", key); continue
    a = Image.open(os.path.join(M, mock)).convert("RGB"); b = Image.open(rend).convert("RGB")
    H = 1400
    a = a.resize((int(a.width * H / a.height), H)); b = b.resize((int(b.width * H / b.height), H))
    W = a.width + b.width + 60
    img = Image.new("RGB", (W, H + 70), "#FAF9F5"); d = ImageDraw.Draw(img)
    img.paste(a, (20, 60)); img.paste(b, (a.width + 40, 60))
    d.text((20, 15), f"목업 {mock}", fill="#141413", font=font); d.text((a.width + 40, 15), f"0.1.8 렌더 {key}.png", fill="#141413", font=font)
    img.save(os.path.join(O, key + ".png")); print("ok", key)
