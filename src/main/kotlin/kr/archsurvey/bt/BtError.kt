package kr.archsurvey.bt

/** 블루투스 오류 코드 (bluetooth-spec.md §3). 화면 하단에 작게 (오류 BT-120) 표시. */
enum class BtError(val code: String, val retry: Boolean, val title: String, val action: String) {
    NO_BLUETOOTH("BT-100", false, "이 폰은 블루투스를 지원하지 않아요", "다른 폰으로 바꾸거나, 수신기와 USB 연결을 알아보세요."),
    BT_OFF("BT-101", true, "블루투스가 꺼져 있어요", "블루투스를 켜면 바로 연결돼요."),
    PERMISSION_NEARBY("BT-102", true, "이 앱이 '근처 기기'를 쓰도록 허용해 주세요", "수신기를 찾고 연결하는 데 꼭 필요해요. 팝업에서 '허용'을 눌러 주세요."),
    PERMISSION_NEARBY_PERMANENT("BT-103", false, "'근처 기기' 권한이 막혀 있어요", "설정 > 앱 > 이 앱 > 권한 > 근처 기기 > 허용으로 바꿔 주세요."),
    AIRPLANE_MODE("BT-106", true, "비행기 모드예요", "비행기 모드를 끄거나, 켜 둔 채 블루투스만 켜세요."),
    NO_DEVICE_SELECTED("BT-110", false, "사용할 수신기를 골라 주세요", "먼저 폰의 블루투스 설정에서 수신기를 등록한 뒤 여기서 선택하세요."),
    NOT_PAIRED("BT-111", false, "고른 수신기가 폰에 등록되어 있지 않아요", "폰 블루투스 설정에서 수신기를 다시 등록해 주세요."),
    CONNECT_TIMEOUT("BT-120", true, "수신기를 찾지 못했어요 (시간 초과)", "수신기 전원과 거리(2m 이내)를 확인하세요. 다른 폰이나 컨트롤러가 붙어 있으면 연결을 끊어 주세요."),
    CONNECT_REFUSED("BT-121", true, "수신기가 연결을 받아주지 않아요", "수신기를 껐다 켠 뒤 다시 해 보세요. 안 되면 폰에서 수신기 등록을 삭제하고 다시 등록하세요."),
    SOCKET_CLOSED("BT-122", true, "연결이 바로 끊겼어요", "수신기에 다른 기기가 연결돼 있을 수 있어요. 그 기기의 블루투스를 끄고 다시 시도하세요."),
    ALL_FAILED("BT-124", true, "여러 방법으로 시도했지만 연결하지 못했어요", "① 폰 블루투스 껐다 켜기 ② 수신기 껐다 켜기 ③ 폰에서 수신기 등록 삭제 후 재등록"),
    NO_DATA("BT-130", false, "연결은 됐는데 위치가 오지 않아요", "수신기 설정에서 블루투스 포트의 NMEA 출력(GGA)을 켜야 해요."),
    BAD_CHECKSUM("BT-131", true, "수신 데이터에 오류가 많아요", "수신기와 너무 멀거나 간섭이 있을 수 있어요. 폰을 수신기 가까이 두세요."),
    NO_GGA("BT-132", false, "위치 정보(GGA)가 오지 않아요", "보정 서버 연결에 꼭 필요한 정보예요. 수신기 NMEA 설정에서 GGA를 켜 주세요."),
    NO_FIX_YET("BT-133", true, "수신기가 아직 위치를 못 잡았어요", "하늘이 트인 곳에서 잠시 기다려 주세요."),
    NOT_NMEA("BT-134", false, "수신기가 알아볼 수 없는 데이터를 보내고 있어요", "수신기의 블루투스 출력 형식이 NMEA인지 확인하세요. 다른 포트(블루투스 2·3)에 설정했을 수도 있어요."),
    LINK_LOST("BT-140", true, "수신기와 연결이 끊겼어요", "자동으로 다시 연결하는 중이에요. 이미 저장한 측점은 그대로예요."),
    WRITE_FAILED("BT-141", true, "보정 데이터를 수신기로 보내지 못했어요", "블루투스 연결이 끊겼어요. 자동으로 다시 연결해요. 이미 저장한 측점은 그대로예요."),
    WRITE_STALLED("BT-142", true, "보정 데이터가 수신기에 전달되지 않고 있어요", "수신기가 데이터를 받지 않아요. 연결을 새로 만듭니다. 계속되면 수신기의 보정 입력 설정을 확인하세요."),
    BACKGROUND_KILLED("BT-150", false, "앱이 백그라운드에서 멈췄을 수 있어요", "설정에서 이 앱의 배터리 사용을 '제한 없음'으로 바꿔 주세요."),
    SP60_NOT_READY("BT-160", true, "(SP60) 수신기가 아직 준비 중이에요", "전원을 켠 뒤 위성을 잡을 때까지 기다린 후 연결하세요. 먼저 연결하면 등록이 풀릴 수 있어요."),
    UNKNOWN("BT-199", true, "알 수 없는 블루투스 오류예요", "블루투스를 껐다 켜고 다시 시도하세요.");

    /** 사용자 조치가 필요해 자동 재시도를 하면 안 되는 오류 */
    val needsUserAction: Boolean get() = !retry || this == BT_OFF || this == PERMISSION_NEARBY || this == AIRPLANE_MODE

    companion object {
        /** 예외 메시지는 제조사별로 다르므로 문자열 매칭은 보조. 사전 점검(권한/꺼짐/등록)을 먼저 적용할 것 */
        fun classify(e: Throwable?): BtError {
            if (e is SecurityException) return PERMISSION_NEARBY
            val m = e?.message?.lowercase().orEmpty()
            return when {
                "service discovery failed" in m -> CONNECT_REFUSED
                "timeout" in m && "read failed" !in m -> CONNECT_TIMEOUT
                "socket might closed" in m || "read ret: -1" in m || "bt socket closed" in m || "broken pipe" in m -> SOCKET_CLOSED
                "host is down" in m || "connection refused" in m -> CONNECT_REFUSED
                "bluetooth is off" in m || ("adapter" in m && "off" in m) -> BT_OFF
                "stalled" in m -> WRITE_STALLED
                else -> UNKNOWN
            }
        }
    }
}

class BtConnectException(val error: BtError, cause: Throwable? = null) :
    java.io.IOException("${error.code} ${error.title}", cause)
