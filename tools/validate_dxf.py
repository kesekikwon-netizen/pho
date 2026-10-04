"""앱 DxfWriter 출력(build/dxf-samples/*.dxf)을 ezdxf(엄격 로드·audit)와 GDAL/OGR(fiona)로 검증.
사용: /workspace/tools/pv/bin/python tools/validate_dxf.py [폴더]   종료코드 0 = 전부 통과"""
import sys, glob, os
from collections import Counter
import ezdxf
from ezdxf import recover
import fiona

d = sys.argv[1] if len(sys.argv) > 1 else "build/dxf-samples"
fails = 0
for fn in sorted(glob.glob(os.path.join(d, "*.dxf"))):
    print("==", os.path.basename(fn))
    ok = True
    try:
        doc = ezdxf.readfile(fn)
        print("  ezdxf strict load: OK", doc.dxfversion, "codepage", doc.header.get("$DWGCODEPAGE"), "insunits", doc.header.get("$INSUNITS"))
    except Exception as e:
        print("  ezdxf strict load: FAIL", e); fails += 1; continue
    _, aud = recover.readfile(fn)
    print(f"  audit errors={len(aud.errors)} fixes={len(aud.fixes)}")
    ok &= len(aud.errors) == 0
    msp = doc.modelspace()
    c = Counter(e.dxftype() for e in msp)
    print("  entities", dict(c), "layers", sorted(l.dxf.name for l in doc.layers))
    pts = [e for e in msp if e.dxftype() == "POINT"]
    if pts: print("  first POINT", tuple(round(v, 4) for v in pts[0].dxf.location))
    names = [ezdxf.decode_dxf_unicode(e.dxf.text) for e in msp if e.dxftype() == "TEXT" and e.dxf.layer == "PT_NO"]
    print("  PT_NO texts", names)
    ok &= c["POINT"] == 4 and "경계점 3" in names
    try:
        with fiona.open(fn) as src:
            feats = list(src)
            g = Counter(f["geometry"]["type"] for f in feats if f["geometry"])
            gk = [f["properties"].get("Text") for f in feats if f["properties"].get("Text") and "경계점" in str(f["properties"].get("Text"))]
            print("  GDAL/OGR:", dict(g), "한글 Text:", gk)
            ok &= g.get("Point", 0) >= 4 and bool(gk)
    except Exception as e:
        print("  GDAL/OGR FAIL", e); ok = False
    print("  RESULT", "PASS" if ok else "FAIL")
    fails += 0 if ok else 1
sys.exit(1 if fails else 0)
