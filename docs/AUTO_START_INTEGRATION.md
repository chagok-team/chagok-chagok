# 자동 운행 시작 통합 (3-5)

## 실행 경로와 정책

- Debug의 `USE_FAKE_DATA=true`는 유지한다. Debug APK 실행은 실제 통합 검증이 아니다.
- RoomAppContainer는 활성 운행 복원에 성공한 뒤에만 Coordinator를 시작한다. 중복 복원 호출은 직렬화하고 최초 결과를 재사용한다.
- 실행 경로: VehicleSpeedSource → AutoTripCoordinator → AutoTripStartDetector → RoomTripTracker.startAutoTrip() → RoomTripRepository.beginTrip().
- 활성 운행이 없으면 1km/h 이하를 5초 관측한 뒤, 5km/h 이상을 3초 관측해야 시작한다. 종료 후 및 Coordinator 재시작 후에도 정차 확인이 필요하다.
- 비정상/오래된/중복/역행 샘플과 수신 불가 이벤트는 관측의 연속성을 끊는다. 단절은 0m/s로 바꾸지 않는다.
- 수동/자동 시작은 같은 Tracker Mutex와 DB 활성 운행 검사로 직렬화한다. 먼저 저장에 진입한 요청이 우선이며 수동 요청이 항상 선점하는 정책은 아니다.
- 계기판 조회 전후 취소는 중단하되, 실제 저장에 진입한 이후에는 저장 결과와 Driving 상태 반영을 완료하고 Mutex를 해제한다. stop() 이후에도 이미 시작한 저장은 완료될 수 있다.
- Coordinator의 stop()은 취소를 요청한다. 다음 start()는 이전 세션의 저장/리스너 정리까지 기다린다. 이벤트 큐는 32개이며 처리 시점의 freshness를 검사한다.
- 상태 알림은 처리 시점의 최신 StateFlow 값을 조회한다. 빠른 수동 시작/종료로 Driving 관측이 생략되어도 새 Arrived tripId를 확인하면 정차 확인을 다시 요구한다.
- 기존 availability.autoDetectionSupported는 신선한 속도 수신 여부에 따라 갱신하며 수신 불가/구독 종료 때 false로 되돌린다. B팀 UI 계약은 바꾸지 않는다.
- 속도 Flow 정상 종료/예외는 해당 세션의 감지를 끝낸다. 자동 재연결 루프는 추가하지 않았으며 다음 명시적 start()는 정차 확인부터 다시 시작한다.

## 권한 및 실행 범위

Main Manifest에 CAR_SPEED를 선언한다. 선언만으로 런타임 승인이 이루어지지는 않는다. 이번 변경에는 Release 권한 요청 UI를 추가하지 않는다. 권한이 없으면 수동 운행은 유지하고 자동 시작은 하지 않는다.

이 통합은 앱 프로세스 생존 중의 구독이다. Foreground Service, 부팅 자동 실행, 프로세스 종료 중 감지는 구현하지 않았다. Room 복원은 백그라운드 실행 보장이 아니다. 자동 운행 종료도 이번 범위에 없다.

## 안전한 실제 데이터 경로 검증

1. JVM 테스트는 SDK 없이 가상 시간과 Fake로 정책/경쟁 조건을 검증한다. 실제 Room 또는 차량 접근 검증으로 표현하지 않는다.
2. 기기 계측 테스트에서는 `USE_FAKE_DATA`를 바꾸지 않고 테스트가 직접 별도 Room DB와 RoomTripTracker, Coordinator를 생성하도록 한다. 주입한 속도/계기판 값으로 AUTO 저장·수동 종료·DB 재열기를 검증할 수 있다. 앱의 autotrip.db를 사용하거나 지우지 않는다.
3. 실제 SDK 검증은 PLEOS 전용 테스트 기기/스냅샷과 사용자 승인된 CAR_SPEED로 수행한다. 앱 UID의 실제 속도 소스를 사용하고, 테스트 종료 시 scope 취소 및 DB 닫기를 보장한다. Fake SDK 값으로 대체하지 않는다.
4. Release 앱 전체 초기화 경로 검증은 사용자 서명 키로 서명한 Room APK를 별도 에뮬레이터/스냅샷에서 실행한다. 새 빌드 타입, debug Fake 전환, 임의 키 생성은 하지 않는다.
5. Console에서 5초 정차 → 3초 이동 → AUTO 1건을 확인한다. 주행 중 일시 정차로 종료되지 않아야 한다. 수동 종료 뒤 바로 이동해도 추가 생성하지 않고 다시 정차 후에만 생성해야 한다.
6. 활성 운행 중 프로세스 재시작 시 같은 tripId가 복원되는지, 권한 거부/SDK 해제 시 수동 저장이 유지되는지 확인한다. Odometer 시작·종료값과 NaviHelper 이벤트, 기존 B팀 목적 입력 흐름은 별도로 회귀 확인한다.

## 검증 기록

환경: Windows, Gradle 8.2, JDK 17.0.7. 다음 명령으로 성공 확인:

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:compileReleaseKotlin --console=plain
```

- 전체 JVM 테스트 270개 통과, 실패/오류/건너뜀 0개.
- Coordinator 17개, Detector 14개, Room 자동 시작/통합 9개, 기존 Tracker Odometer 4개 포함.
- 수동/자동 동시 시작, 저장 취소 후 상태 반영, 가상 시간 기반 정차/주행/재시도, 즉시 중지/재시작, 빠른 수동 시작/종료, 속도 단절/종료/예외를 검증했다.
- 실제 RoomTripTracker를 사용하는 JVM 통합 테스트도 저장소와 차량은 Fake이다. 실제 Room DB·실제 차량 통과를 뜻하지 않는다.
- 중간 실행에서 테스트 Fake 속성 setter/메서드의 JVM 시그니처 충돌로 테스트 컴파일이 실패했다. 테스트 속성명을 정리한 뒤 관련 테스트 및 전체 회귀를 다시 통과했다. 기존 테스트 삭제/무력화 없음.
- Debug APK는 기존 Fake 시연용이다. Release는 Kotlin 컴파일만 확인했으며 이번에 서명/설치/실제 SDK 통합 실행은 하지 않았다.
- Android 계측 테스트 및 위 PLEOS Console 시나리오는 이번 작업에서 실행하지 않았다.
