package kr.archsurvey.ui

/** 왼쪽 서랍 메뉴 구성 (목업 s17 기준). 순수 데이터라 JVM 에서 검증한다. */
enum class MenuAction {
    /** 서랍을 닫고 측량+지도 화면으로 */
    GO_SURVEY,
    OPEN_ANTENNA,
    /** 수신기 선택·블루투스·VRS 입력 화면 */
    OPEN_CONNECTION,
    /** 작업 내보내기(ZIP) 화면 */
    OPEN_EXPORT,
    /** 데이터 백업·복구 화면 */
    OPEN_BACKUP,
    /** 아직 연결되지 않은 메뉴 — 다음 라운드 */
    NOT_YET,
    /** 개발용: 가짜 GNSS 다음 구간으로 */
    DEV_SKIP_SEGMENT,
    /** 폴 쪽 좌우 바꾸기 */
    TOGGLE_POLE_SIDE,
}

data class MenuItem(val id: String, val label: String, val action: MenuAction, val enabled: Boolean = true, val note: String? = null)
data class MenuSection(val title: String, val items: List<MenuItem>)

object DrawerMenu {
    val sections: List<MenuSection> = listOf(
        MenuSection("즐겨찾기", listOf(
            MenuItem("fav_points", "점 목록", MenuAction.NOT_YET),
            MenuItem("fav_goto", "찾아가기", MenuAction.NOT_YET),
            MenuItem("fav_calc", "계산기", MenuAction.NOT_YET),
        )),
        MenuSection("작업 관리", listOf(
            MenuItem("job_new", "새 작업 만들기", MenuAction.NOT_YET),
            MenuItem("job_open", "작업 열기 · 삭제", MenuAction.NOT_YET),
            MenuItem("job_export", "작업 내보내기", MenuAction.OPEN_EXPORT),
            MenuItem("job_info", "작업 정보", MenuAction.NOT_YET),
            MenuItem("job_points", "점 목록", MenuAction.NOT_YET),
            MenuItem("job_backup", "데이터 백업 · 복구", MenuAction.OPEN_BACKUP),
        )),
        MenuSection("측량", listOf(
            MenuItem("survey", "측량 · 지도 화면", MenuAction.GO_SURVEY),
            MenuItem("connection", "수신기 연결 · VRS", MenuAction.OPEN_CONNECTION),
            MenuItem("antenna", "안테나 높이", MenuAction.OPEN_ANTENNA),
            MenuItem("goto", "점 찾아가기", MenuAction.NOT_YET),
            MenuItem("calc", "계산기", MenuAction.NOT_YET),
            MenuItem("settings", "설정", MenuAction.NOT_YET),
        )),
        MenuSection("AI · 점검", listOf(
            MenuItem("ai", "AI 어시스턴트", MenuAction.NOT_YET, enabled = false, note = "준비 중"),
            MenuItem("diag", "진단", MenuAction.NOT_YET, enabled = false, note = "준비 중"),
            // 설정의 "AI 연동" 스위치 자리 — 꺼짐 고정, 전송 코드 없음 (ai/StatusSnapshot.kt AiLinkSetting)
            MenuItem("ai_toggle", "AI 연동", MenuAction.NOT_YET, enabled = false, note = "꺼짐 · 준비 중"),
        )),
        MenuSection("개발용 · 가짜 GNSS", listOf(
            MenuItem("dev_skip", "가짜 신호: 다음 구간으로", MenuAction.DEV_SKIP_SEGMENT),
            MenuItem("dev_pole", "폴 쪽 좌우 바꾸기", MenuAction.TOGGLE_POLE_SIDE),
        )),
    )
    val allItems: List<MenuItem> get() = sections.flatMap { it.items }
}
