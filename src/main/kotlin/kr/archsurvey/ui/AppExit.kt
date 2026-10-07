package kr.archsurvey.ui

/**
 * 앱 종료(0.1.31 T27 → 0.1.32 두 단계). 홈에서 뒤로 · 서랍 「앱 종료」:
 *  1) 수신기가 연결(또는 연결 중)이면 「기기 종료」 창 — 예 = 보정(VRS)·블루투스를 끊고 연결 서비스(알림)를 멈춤. 취소 = 그대로.
 *  2) 이어서 「프로그램 종료」 창 — 예 = 앱 작업을 완전히 닫음(MainActivity.finishAndRemoveTask + 프로세스 종료). 취소 = 앱에 남음(연결은 끊긴 채).
 *  수신기가 없으면 1)을 건너뛰고 2)만.
 * 기기 종료는 **저장된 마지막 수신기·보정 설정을 지우지 않는다** — 다음 실행 때 같은 수신기로 자동 연결.
 * 수신기 **전원 끄기 명령은 보내지 않는다** — R10·SP60 원격 전원 끄기는 학습 자료(bluetooth-spec·research·deep-research)에 확인된 명령이 없다.
 */
object AppExit {
    const val TITLE_DEVICE = "기기 종료"
    const val BODY_DEVICE = "수신기 연결을 끊을까요? 블루투스·VRS 연결을 끊고 알림을 지워요. 수신기 전원은 수신기 버튼으로 꺼 주세요. 다음에 켜면 같은 수신기로 자동 연결해요."
    const val TITLE_APP = "프로그램 종료"
    const val BODY_APP = "앱을 종료할까요? 측점은 이미 저장돼 있어요."
    const val OK = "예"
    const val CANCEL = "취소"
    const val MENU_LABEL = "앱 종료"

    /** 1단계 「기기 종료」 */
    fun deviceStep(onOk: () -> Unit) = ConfirmRequest(TITLE_DEVICE, BODY_DEVICE, OK, CANCEL, onOk = onOk)
    /** 2단계 「프로그램 종료」 */
    fun appStep(onOk: () -> Unit) = ConfirmRequest(TITLE_APP, BODY_APP, OK, CANCEL, onOk = onOk)
}
