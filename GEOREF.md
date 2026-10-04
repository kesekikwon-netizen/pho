# WP-10 업로드 배경 정합 로직 (UI 없음) — 구현 메모

패키지 `kr.archsurvey.georef` (화면 연결은 s34c/s34d/s34e UI 작업에서). 정답지 = pyproj 3.8.0(PROJ). 샘플·오라클 생성: `plan/samples/georef/make_samples.py`, `make_oracle.py`
(테스트 리소스 `src/test/resources/georef/`). 실제 현장 도면·실기기 시험은 하지 않았다.

| 파일 | 역할 |
|---|---|
| `GeoTiffReader` | 첫 IFD 태그만 읽음(화소 안 읽음). 클래식/BigTIFF, 엔디언 둘 다. PixelScale+Tiepoint, ModelTransformation, GeoKey(1024/1025/2048/3072/3076), PixelIsPoint 반 화소 보정(GDAL 기본과 동일) |
| `WorldFile` | 6줄 파서, 중심→모서리 반 화소 보정, 동반 파일명 규칙(.tfw/.jgw/.pgw/.wld) |
| `PrjParser` | WKT1/ESRI/WKT2 최소 파서 → ① AUTHORITY/ID ② 투영 이름 ③ TM 파라미터 매칭. 실패 시 후보/사유 |
| `DxfScan` | ASCII DXF 좌표 범위(ENTITIES만), 1~99% 분위 범위(원점 이상점 대응), $INSUNITS/$DWGCODEPAGE, 바이너리 거부 |
| `Crs` | 좌표계 목록 + Krüger TM(n^4) + WebMercator |
| `Align` | 자동 정합 판정(ALIGNED_AUTO/USER_CRS/NEEDS_CRS/NEEDS_MANUAL/MISMATCH), 후보 순위, 한국 영역·작업 영역 거리 검사, 4모서리 근사 오차 |
| `Affine` | 2점 닮음 / 3점 정확 아핀 / 4점+ 최소제곱, 점별 잔차·RMS, 조건(가로세로비) 검사·거부, 경고 |
| `BgLayer` | 저장 모델(JSON 직렬화, 파일 저장소) — Room 연결은 WP-6 스키마와 충돌을 피하려고 보류 |

## 결정: TM 5179 / 5181 / 5183 / 5174
- **5179, 5181, 5183 구현**(전부 GRS80 TM, 파라미터만 다름; PROJ 값과 테스트로 대조). 5181/5183 은 5186/5187 과 북 좌표만 100 km 다르다(FN 500000 vs 600000).
- **5174(구 한국측지계)는 변환하지 않음**: 후보에만 올리고 `supported=false`. 측지계 이동 근거를 확인하지 못했고, 같은 E/N 을 측지계 무시하고 해석하면 PROJ 대비 약 311 m 어긋남(`crs5174_shift.json`) → 수동 정렬 권장.
- 기존 `core/TmProjection`(Redfearn, 5185~5188)은 건드리지 않음. 새 엔진과 오라클 비교 수치는 `build-reports/georef/tm_oracle_report.txt`.

## 임시값 (현장 도면으로 정해야 함)
작업 반경 20 km · 한국 영역 상자(경도 123.5~132.0, 위도 32.0~39.0) · 로컬 좌표 판정(절댓값 모두 10만 미만) · 가로세로비 거부 0.05/경고 0.2 · 최소 기준점 간격 5(도면 단위) · 잔차 경고 1 m · 4모서리 근사 오차 경고 1 화소 · 기본 투명도 50%.

## 미검증
실제 현장 GeoTIFF/수치지형도 DXF/CAD 도면 · INSERT(블록) 전개 · 압축/타일 구조 변형 대용량 GeoTIFF · 안드로이드 기기에서의 속도/메모리(JVM 박스 수치만) ·
NGII 도엽 DXF 의 실제 좌표계 · 수동 정렬 정확도(사용자 클릭 정확도 의존) · 지도에 실제 표시(MapLibre 연결은 WP-9 결과 후).
