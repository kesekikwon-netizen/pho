package kr.archsurvey.ui

/** 왼쪽 서랍 메뉴 구성 (목업 s17 기준). 순수 데이터라 JVM 에서 검증한다. */
enum class MenuAction {
    /** 서랍을 닫고 측량+지도 화면으로 */
    GO_SURVEY,
    /** 배경 지도·레이어 관리 시트 */
    OPEN_BG,
    OPEN_AREAS,
    OPEN_TYPES,
    OPEN_ANTENNA,
    /** 수신기 선택·블루투스·VRS 입력 화면 */
    OPEN_CONNECTION,
    /** 작업 내보내기(ZIP) 화면 */
    OPEN_EXPORT,
    /** 데이터 백업·복구 화면 */
    OPEN_BACKUP,
    /** 도구 화면(점 목록·찾아가기·계산기·설정·작업 관리) */
    OPEN_TOOL_POINTS, OPEN_TOOL_GOTO, OPEN_TOOL_CALC, OPEN_TOOL_SETTINGS, OPEN_JOB_NEW, OPEN_JOB_LIST, OPEN_JOB_INFO,
    /** 0.1.38 측량한 것(유구별) · 지도에 보이기 시트 */
    OPEN_FEATURES, OPEN_LAYERS,
}

data class MenuItem(val id: String, val label: String, val action: MenuAction, val enabled: Boolean = true, val note: String? = null)
data class MenuSection(val title: String, val items: List<MenuItem>)

/**
 * 0.1.38 메뉴 하나로 정리: 예전에는 「메뉴」 시트(7칸) → 「전체 메뉴」 서랍(16칸, 즐겨찾기 포함) 두 단계였고
 * 같은 화면으로 가는 길이 여러 개였다(점 목록=측량한 것, 안테나 높이=설정 폴 높이, 작업 정보=홈 「작업」…).
 * 이제 머리줄 ≡ 하나 = 이 서랍. 작업 · 측량 · 장비/설정 세 묶음, 10칸.
 */
object DrawerMenu {
    val sections: List<MenuSection> = listOf(
        MenuSection("작업", listOf(
            MenuItem("job_new", "+ 새 작업", MenuAction.OPEN_JOB_NEW),
            MenuItem("job_open", "작업 열기 · 삭제", MenuAction.OPEN_JOB_LIST),
            MenuItem("job_export", "내보내기 · 저녁 도면", MenuAction.OPEN_EXPORT),
            MenuItem("job_backup", "백업 · 복구", MenuAction.OPEN_BACKUP),
        )),
        MenuSection("측량", listOf(
            MenuItem("features", "측량한 것", MenuAction.OPEN_FEATURES),
            MenuItem("areas", "구역 · 그리드", MenuAction.OPEN_AREAS),
            MenuItem("layers", "지도에 보이기", MenuAction.OPEN_LAYERS),
            MenuItem("goto", "점 찾아가기", MenuAction.OPEN_TOOL_GOTO),
        )),
        MenuSection("장비 · 설정", listOf(
            MenuItem("connection", "수신기 연결 · VRS", MenuAction.OPEN_CONNECTION),
            MenuItem("settings", "설정 · 구글 드라이브", MenuAction.OPEN_TOOL_SETTINGS),
        )),
    )
    val allItems: List<MenuItem> get() = sections.flatMap { it.items }
}
