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
}

data class MenuItem(val id: String, val label: String, val action: MenuAction, val enabled: Boolean = true, val note: String? = null)
data class MenuSection(val title: String, val items: List<MenuItem>)

/**
 * SmartTopo 2024 서랍(즐겨찾기 / 작업관리 / 일반측량)을 따른 구성. 우리 고유 기능(발굴·위치 종류·배경 레이어·장비)은
 * 같은 구조 안의 해당 그룹에 넣었다. 로그인·도로·정지·드론·토탈스테이션 등은 SmartTopo 항목이지만 범위 밖이라 없다.
 */
object DrawerMenu {
    val sections: List<MenuSection> = listOf(
        MenuSection("즐겨찾기", listOf(
            MenuItem("fav_points", "점 목록", MenuAction.OPEN_TOOL_POINTS),
            MenuItem("fav_goto", "찾아가기", MenuAction.OPEN_TOOL_GOTO),
            MenuItem("fav_calc", "계산기", MenuAction.OPEN_TOOL_CALC),
        )),
        MenuSection("작업 관리", listOf(
            MenuItem("job_new", "새 작업 만들기", MenuAction.OPEN_JOB_NEW),
            MenuItem("job_open", "작업 열기 · 삭제", MenuAction.OPEN_JOB_LIST),
            MenuItem("job_export", "작업 내보내기", MenuAction.OPEN_EXPORT),
            MenuItem("job_info", "작업 정보 확인", MenuAction.OPEN_JOB_INFO),
            MenuItem("job_points", "점 목록", MenuAction.OPEN_TOOL_POINTS),
            MenuItem("job_backup", "데이터 백업 · 복구", MenuAction.OPEN_BACKUP),
        )),
        MenuSection("일반 측량", listOf(
            MenuItem("survey", "측량", MenuAction.GO_SURVEY),
            MenuItem("goto", "점 찾아가기", MenuAction.OPEN_TOOL_GOTO),
            MenuItem("types", "위치 종류", MenuAction.OPEN_TYPES),
            MenuItem("areas", "발굴 구역 · 그리드", MenuAction.OPEN_AREAS),
            MenuItem("calc", "계산기", MenuAction.OPEN_TOOL_CALC),
            MenuItem("bg", "배경 지도 · 레이어", MenuAction.OPEN_BG),
        )),
        MenuSection("환경 설정", listOf(
            MenuItem("connection", "장비 연결 · VRS", MenuAction.OPEN_CONNECTION),
            MenuItem("antenna", "안테나 높이", MenuAction.OPEN_ANTENNA),
            MenuItem("settings", "설정 (좌표계 등)", MenuAction.OPEN_TOOL_SETTINGS),
        )),
    )
    val allItems: List<MenuItem> get() = sections.flatMap { it.items }
}
