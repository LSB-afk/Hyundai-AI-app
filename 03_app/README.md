# JARI — AI 좌석·창문 에이전트 (Pleos Connect)

> 말 한마디로 내 자리를 맞춰주는 차량용 AI 에이전트. "허리가 아파", "조수석 좀 눕혀줘", "너무 졸려".

![home](../02_기획서/화면시안/landscape_home.png)

## 핵심 기능
- **음성 에이전트**: Pleos STT(ON_DEVICE) → 의도 해석 → Vehicle SDK 실행 → Pleos TTS 응답
- **좌석 제어**: 4개 좌석 × 앞뒤·등받이·높이·허리 지지 + 열선·통풍 (0~3단)
- **창문 제어**: 측면 4개 + 선루프, 환기 모드
- **주행 안전 가드**: 기어가 P가 아니면 운전석 자세를 크게 바꾸는 명령 차단 (음성·터치 공통)
  - 예) 주행 중 "졸려" → 눕히는 대신 창문 20% + 통풍 3단 + 휴게소 안내
- **상황 프리셋**: 휴식 / 운전 자세 / 승하차 / 초기화
- 시트 점유 센서로 "모두" 명령 시 탑승한 좌석만 조절
- **EV 충전**: 가까운 충전소 지도·목록·상세, "충전소 찾아줘" → 빈 충전기 중 최단거리로 NaviHelper 길안내
  - 충전 세션: 실시간 SoC, 충전 한도 설정, 충전량·요금
  - **충전 중 휴식**: 충전이 시작되면 시트를 휴식 모드로, 끝나면 운전 자세로 자동 복귀
  - 충전소 데이터는 데모용 고정값 (`Charging.kt`) — 공공 충전소 API로 교체 예정

![charging](../02_기획서/화면시안/landscape_charging.png)

## 실내 3D 시뮬레이션 (`ui/Cabin3D.kt`)
- 의존성 없는 lowpoly 3D 렌더러 (원근 투영 + 램버트 셰이딩 + 깊이 정렬)
- 드래그 회전, 시점 프리셋(3D / 위 / 측면). 측면은 도어를 걷어낸 컷어웨이
- 시트 앞뒤·높이·등받이(힌지 회전)·허리 지지, 창문 유리 하강, 열선·통풍, 탑승자가 실시간 반영
- 파트 구분은 Pleos glTF 네이밍(`Seat_F_L_Top/Low`)을 따름 → Filament + glTF 모델 확보 시 렌더 계층만 교체

## 화면 구성 (Pleos 멀티 윈도우)
- **portrait** (기본, window 1개 ≈ 841×1210): 음성 에이전트 → 실내 시각화 → 핵심 제어. 내비와 나란히 사용
- **landscape** (window 2개 ≈ 1682×1210): 실내 탑뷰·시트 측면 뷰 + 확장 제어
- 주행 중(기어 ≠ P): 키보드 입력 숨김, 시트 화면 조작 잠금, 음성만 허용 (Pleos 주행 규제 `NO_KEYBOARD`·`NO_SETUP` 준수)
- 최소 글자 18px (Pleos 디자인 가이드)

![portrait](../02_기획서/화면시안/portrait_home.png)

## 사용 SDK / 권한
| SDK | API | 권한 |
|---|---|---|
| Vehicle 2.3.2 | `Seat.moveSeat{ForeAft,BackrestAngle,Height,LumbarForeAft}`, `setHvacSeat{Temperature,Ventilation}`, `getCurrentSeatOccupancy` | `CAR_SEATS`, `CONTROL_CAR_SEATS` |
| Vehicle 2.3.2 | `Window.setWindowPosition`, `checkWindowPositionCapabilities` | `CAR_WINDOWS`, `CONTROL_CAR_WINDOWS` |
| Vehicle 2.3.2 | `Gear.registerGearSelection` | `CAR_DRIVE` |
| Vehicle 2.3.2 | `EvBattery.getEvBatteryStateOfCharge`, `registerEvBatteryStateOfCharge` / `setEvChargeSwitch`, `setEvChargePercentLimit` | `CAR_ENERGY` / `CONTROL_CAR_ENERGY` |
| NaviHelper 3.0.5 | `requestRoute(RouteInfo)` | `NAVI_ROUTE` |
| SpeechToText 3.3.1 | `request / stop`, `ResultListener` (ON_DEVICE) | `STT_SERVICE` |
| TextToSpeech 3.3.1 | `speak` (ON_DEVICE) | `TTS_SERVICE` |

## 실행 방법
1. Android Studio + Pleos Connect Emulator v2.1.0 (API 34) 준비 — `SETUP.md`
2. `./gradlew installDebug` 또는 `apk/` 의 release APK 설치
3. 런처에서 **JARI** 실행. 마이크 버튼 또는 추천 문장/입력창 사용
- Vehicle Service가 없는 환경에서는 자동으로 **시뮬레이션 모드** (우상단 칩 표시). 이때 `P 정차` 칩을 눌러 주행/정차 전환 가능

## 테스트 환경
- JDK 17, AGP 8.8.2, Kotlin 2.0.21, compileSdk 35, minSdk 28
- 단위 테스트(에이전트 규칙·안전 가드): `./gradlew testDebugUnitTest`
- 화면 스냅샷 재생성: `./gradlew recordPaparazziDebug` → `app/src/test/snapshots/images`

## APK
`apk/팀명_JARI_release.apk` (제출 시 팀명으로 변경)

## 구조
```
app/src/main/java/com/jari/agent/
  Agent.kt          발화 → 제어 계획 + 안전 가드 (순수 Kotlin, 테스트 대상)
  CarController.kt  Pleos Vehicle SDK 브리지 + 시뮬레이션
  Charging.kt       충전소 데모 데이터
  Voice.kt          Pleos STT/TTS 래퍼
  MainActivity.kt   연결
  ui/               Compose 화면 (App, Cabin3D, ChargeScreens, Theme)
```
