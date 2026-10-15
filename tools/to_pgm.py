#!/usr/bin/env python3
"""단위시험 입력: 표적 PNG·합성 JPG → 8비트 밝기 PGM(P5). JVM 단위시험(android.jar)에는 javax.imageio 가 없어서."""
import glob, os
from PIL import Image
R = os.path.join(os.path.dirname(__file__), "..")
out = os.path.join(R, "testdata/gray"); os.makedirs(out, exist_ok=True)
for f in glob.glob(os.path.join(R, "targets/*AprilTags_*.png")) + glob.glob(os.path.join(R, "testdata/synth/*.jpg")):
    im = Image.open(f).convert("L")
    n = os.path.basename(f).split("--")[-1].rsplit(".", 1)[0]
    im.save(os.path.join(out, n + ".pgm"))
print("ok")
