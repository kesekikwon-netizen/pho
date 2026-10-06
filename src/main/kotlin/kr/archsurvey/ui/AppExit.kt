package kr.archsurvey.ui

/**
 * 0.1.31 T27 앱 종료: 홈에서 뒤로 · 서랍 「앱 종료」 → 확인 창 → 예 = 보정(VRS)·블루투스 연결을 끊고 연결 서비스(알림)를 멈춘 뒤
 * 앱 작업을 완전히 닫는다(MainActivity.finishAndRemoveTask).
 * 수신기 **전원 끄기 명령은 보내지 않는다** — R10·SP60 원격 전원 끄기는 학습 자료(bluetooth-spec·research·deep-research)에 확인된 명령이 없다.
 */
object AppExit {
    const val TITLE_CONNECTED = "수신기 연결을 끊고 종료할까요?"
    const val TITLE_IDLE = "앱을 종료할까요?"
    const val BODY_CONNECTED = "블루투스·VRS 연결을 끊고 앱을 완전히 닫아요. 측점은 이미 저장돼 있어요. 수신기 전원은 수신기 버튼으로 꺼 주세요."
    const val BODY_IDLE = "앱을 완전히 닫아요. 측점은 이미 저장돼 있어요."
    const val OK = "예"
    const val CANCEL = "취소"
    const val MENU_LABEL = "앱 종료"

    fun request(connected: Boolean, onOk: () -> Unit) = ConfirmRequest(
        if (connected) TITLE_CONNECTED else TITLE_IDLE,
        if (connected) BODY_CONNECTED else BODY_IDLE,
        OK, CANCEL, onOk = onOk,
    )
}
