package kr.archsurvey.gcp.model

/** 세 앱 공통 iTwin 안내문 — /workspace/design-program/shared/iTwin_불러오기.txt 그대로(자동 생성, 고치지 말 것). sha256 d05f81eddb4525c8935e4994dfbfaaaeb26845818c5a9ad3da48ec19fde61894 */
object ItwinSheet {
    const val FILE = "iTwin_불러오기.txt"
    const val SHA256 = "d05f81eddb4525c8935e4994dfbfaaaeb26845818c5a9ad3da48ec19fde61894"
    val TEXT: String = "iTwin Capture Modeler (Flex) 불러오기 — 세 앱 공통 안내 (발굴 측량 · 사진 기준점 · 사진측량)\n\n방법은 하나: 사진측량 zip 이 있으면 「블록 가져오기 → block.xmlz」 하나만.\n좌표계(EPSG)·기준점 이름(Target #N)·좌표는 파일 안에 이미 들어 있습니다. 따로 고르지 마세요.\n\n[사진측량 zip 이 있을 때]\n1. zip 을 PC 에 폴더째 풀기 (photos 폴더 위치를 바꾸지 마세요).\n2. iTwin Capture Modeler → New project → 이름 → OK.\n3. Import blocks → block.xmlz 선택 → 열기.\n4. 새 블록 → Surveys → Control points: Target #1… 좌표와 좌표계가 보이면 정상.\n5. Submit aerotriangulation → 기준점으로 맞춤(Use control points for adjustment) → 실행.\n6. AT 보고서에서 기준점 오차(RMS) 확인 → New reconstruction.\n※ block 을 이미 불러왔으면 기준점 XML·TXT 는 불러오지 마세요 (같은 점이 두 번 생깁니다).\n\n[사진 기준점·발굴 측량 앱만 쓸 때 (사진측량 block 없음)]\n1. iTwin 에서 사진을 넣은 블록을 엽니다.\n2. Surveys → Import surveys → zip 안의 기준점 XML 하나 선택.\n3. 사진마다 Target #N 위치를 찍고(2장 이상) 위 5~6 단계대로 진행.\n\n저장 위치(폰): 내 파일 › Documents › 발굴조사 › <작업 이름>\n"
}
