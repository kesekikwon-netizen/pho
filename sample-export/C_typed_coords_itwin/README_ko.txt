사진측량 앱 → iTwin Capture Modeler (Flex) 넣는 순서

들어 있는 것: photos\ (원본 사진) · block.xmlz (사진+표적 표시+좌표) · block.xml (같은 내용) · gcp_template.txt

1. zip 을 PC 에 풀기 (폴더째 그대로. photos 폴더 위치를 바꾸지 마세요).
2. iTwin Capture Modeler → New project → 이름 → OK.
3. 프로젝트 화면 → Import blocks → block.xmlz 선택 → 열기.
   (Flex 화면에 Import blocks 가 안 보이면: 프로젝트 탭 오른쪽 위 ⋯/More → Import blocks)
4. 새 블록 → Surveys 탭 → Control points 목록 확인:
   · 좌표계 Korea 2000 / East Belt 2010 (EPSG:5187) · 이름 옆 좌표(동E=X, 북N=Y, 높이=Z)와 사진 표시 수(2장 이상)가 보이면 정상.
   · TXT 를 따로 불러오지 마세요(같은 점이 두 번 생깁니다).
5. Submit aerotriangulation → Positioning mode = Use control points for adjustment (기준점으로 맞춤) → 실행.
6. 끝나면 AT 보고서에서 기준점 오차(RMS) 확인 → New reconstruction.

표적 3개 이상, 표적마다 사진 2장 이상 표시가 있어야 좌표가 맞춰집니다.
