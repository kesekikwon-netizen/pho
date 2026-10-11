package kr.archsurvey.ui

/**
 * 앱 종료(0.1.31 T27 → 0.1.32 두 단계). 홈에서 뒤로 · 서랍 「앱 종료」:
 *  0.1.36: 1단계 이름을 「수신기 연결 끊기」로 바꿈 — 예전 「기기 종료」는 수신기(R10) 전원이 꺼지는 것처럼 읽혀
 *  현장에서 「기기 종료를 눌러도 안 꺼진다」로 보고됨(앱은 블루투스·VRS 만 끊고, 전원 끄기 명령은 확인된 것이 없어 보내지 않음).
 *  1) 수신기가 연결(또는 연결 중)이면 「수신기 연결 끊기」 창 — 예 = 보정(VRS)·블루투스를 끊고 연결 서비스(알림)를 멈춤. 취소 = 그대로.
 *  2) 이어서 「프로그램 종료」 창 — 예 = 앱 작업을 완전히 닫음(MainActivity.finishAndRemoveTask + 프로세스 종료). 취소 = 앱에 남음(연결은 끊긴 채).
 *  수신기가 없으면 1)을 건너뛰고 2)만.
 * 기기 종료는 **저장된 마지막 수신기·보정 설정을 지우지 않는다** — 다음 실행 때 같은 수신기로 자동 연결.
 * 수신기 **전원 끄기 명령은 보내지 않는다** — R10·SP60 원격 전원 끄기는 학습 자료(bluetooth-spec·research·deep-research)에 확인된 명령이 없다.
 */
object AppExit {
    const val TITLE_DEVICE = "수신기 연결 끊기"
    const val BODY_DEVICE = "수신기 연결을 끊을까요? 블루투스·VRS 연결을 끊고 알림을 지워요.\n앱은 수신기 전원을 끌 수 없어요 — 수신기 전원 버튼을 길게 눌러 끄세요. 다음에 켜면 같은 수신기로 자동 연결해요."
    const val OK_DEVICE = "연결 끊기"
    /** 끊은 뒤 알림: 전원은 수신기에서 */
    const val DONE_DEVICE = "수신기 연결을 끊었어요 · 수신기 전원은 수신기 버튼으로 꺼 주세요"
    const val TITLE_APP = "프로그램 종료"
    const val BODY_APP = "앱을 종료할까요? 측점은 이미 저장돼 있어요."
    const val OK_APP = "종료"
    const val OK = "예"
    const val CANCEL = "취소"
    const val MENU_LABEL = "앱 종료"

    /** 1단계 「수신기 연결 끊기」 */
    fun deviceStep(onOk: () -> Unit) = ConfirmRequest(TITLE_DEVICE, BODY_DEVICE, OK_DEVICE, CANCEL, onOk = onOk)
    /** 2단계 「프로그램 종료」 */
    fun appStep(onOk: () -> Unit) = ConfirmRequest(TITLE_APP, BODY_APP, OK_APP, CANCEL, onOk = onOk)
}
