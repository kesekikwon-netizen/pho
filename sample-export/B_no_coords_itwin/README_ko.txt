사진측량 앱 → iTwin Capture Modeler (Flex) 넣는 순서

들어 있는 것: photos\ (원본 사진) · block.xmlz (사진+표적 표시) · block.xml (같은 내용) · gcp_template.txt

1. zip 을 PC 에 풀기 (폴더째 그대로. photos 폴더 위치를 바꾸지 마세요).
2. iTwin Capture Modeler → New project → 이름 → OK.
3. 프로젝트 화면 → Import blocks → block.xmlz 선택 → 열기.
   (Flex 화면에 Import blocks 가 안 보이면: 프로젝트 탭 오른쪽 위 ⋯/More → Import blocks)
4. 좌표가 아직 없습니다. 둘 중 하나:
   (권장) 앱 → 작업 → 「좌표 입력」 에서 표적마다 동E·북N·높이를 쓰고 다시 「iTwin으로 보내기」.
   (PC) gcp_template.txt 의 0.000 을 채운 뒤 Surveys → Import → 파일 선택 → 구분자 쉼표, 열 = 이름, X(동E), Y(북N), Z(높이), 좌표계 EPSG:5187.
        이름은 block 안 표적 이름(Target #N)과 글자까지 같아야 사진 표시가 붙습니다.
5. Submit aerotriangulation → Use control points for adjustment → 실행.

표적 3개 이상, 표적마다 사진 2장 이상 표시가 있어야 좌표가 맞춰집니다.
