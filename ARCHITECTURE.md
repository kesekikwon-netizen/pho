# 패키지 구조 (2026-10-04, WP-0)

`kr.archsurvey` 아래. 화살표는 허용 의존 방향(아래가 위를 몰라야 함).

| 패키지 | 역할 | Android 의존 |
|---|---|---|
| `core` | NMEA 파서·평균·TM 투영·안테나 계산·**상태 판정(StatusJudge)** | 없음(JVM 테스트 가능) |
| `device` | 모델 프로필, 재연결 정책, **`GnssSource` 인터페이스**, `FakeGnssSource`(시나리오) | 없음 |
| `bt` | 블루투스 RFCOMM 전송·오류 분류 | 있음 |
| `ntrip` | NTRIP 클라이언트(NGII VRS) | 없음(소켓) |
| `engine` | 실제 수신기 연결 엔진·포그라운드 서비스 | 있음 |
| `data` | Room DB, 설정, 안테나 DB 로더 | 있음 |
| `map` | `MapSurface` 모델, 타일 수학(V-World URL) — 지도 그림은 `ui`의 Canvas 구현 | 일부 |
| `ui` | Compose 화면(상태 띠·지도·컨트롤 바·서랍), 테마 | 있음 |
| `export` | CSV·GeoJSON(·DXF는 WP-7) | 일부 |
| `photo` | 사진 저장·썸네일 | 있음 |
| `feedback` | 진동·소리 | 있음 |
| `ai` | (WP-8 예정) 읽기 전용 StatusSnapshot — 전송 코드 없음 | 없음 |

- `legacy/` : 컴파일 안 되는 이전 UI. `legacy/README.md` 참고.
- 상태: **실기기(R10·SP60·VRS·폰) 시험 없음.** 빌드·단위 테스트 통과는 로직 증거일 뿐이다.
