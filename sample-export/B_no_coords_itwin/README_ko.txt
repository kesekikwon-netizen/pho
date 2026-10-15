사진측량 앱 → iTwin Capture Modeler 넘기기 (PC)

폴더: photos\ (원본 사진, 손대지 않음) · block.xmlz · block.xml(같은 내용, 압축 안 함) · gcp_template.txt

1. iTwin Capture Modeler → 새 프로젝트 → Import blocks → block.xmlz 선택 (안 되면 block.xml).
   사진과 표적 위치(Target #N, 사진마다 표시)가 함께 들어옵니다. 표적을 사진에서 다시 찍을 필요가 없습니다.
2. 좌표 넣기: gcp_template.txt 의 0.000 자리를 R10·토탈스테이션 값(동 E, 북 N, 높이)으로 채우거나,
   사진 기준점 앱에서 내보낸 TXT(같은 이름 형식)를 그대로 씁니다.
   Surveys → Import → TXT, 좌표계 EPSG:5187(현장에 맞게) 선택.
3. Aerotriangulation → Reconstruction. 표적 3개 이상, 표적마다 사진 2장 이상 표시가 있어야 좌표가 맞춰집니다.
