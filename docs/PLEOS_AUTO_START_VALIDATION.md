# 7-1 PLEOS 자동 시작 실제 검증

2026-11-14, feature/vehicle-sdk-integration, 시작 HEAD 51250fad543e6a2c5c498f25d6df479b040fd94c.

## 환경과 방법

- emulator-5554, Pleos_Connect_v2.1.0_API_34, API 34, boot_completed=1, AAOS 사용자 10.
- 앱 UID 1010189, CAR_SPEED granted=true / USER_SET. 강제 권한 부여 없음.
- Debug USE_FAKE_DATA=true 유지. 계측 테스트가 실제 VehicleSpeedSource, AutoTripCoordinator, RoomTripTracker, RoomTripRepository와 고유 이름의 실제 Room DB를 직접 생성한다.
- Odometer는 기존 pleosOdometer를 사용한다. 속도/계기판에 Fake 입력을 주입하지 않는다. 앱 autotrip.db는 사용/삭제하지 않는다.
- Virtual Console 0km/h 9초 유지 → 18km/h. 기본 정책 정차 5초·주행 3초에 따라 AUTO 생성. 이후 force-stop → 재계측으로 별도 프로세스에서 활성 운행 복원 → Console 0km/h, 계기판 1km 증가 → 수동 종료.
- 실행한 호스트 스크립트: build/stage7-run-auto.ps1. 매 실행은 새 진단 DB를 만든다. 종료 시 Console 0km/h/Park, heartbeat·ADB 8088 forward 해제. 누적 거리는 감소시키지 않았다.

## 명령과 실제 결과

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument --user 10 -w -r -e class 'com.autotrip.data.tracker.PleosAutoStartTest#captureAutoTrip' com.autotrip.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am force-stop --user 10 com.autotrip
adb -s emulator-5554 shell am instrument --user 10 -w -r -e class 'com.autotrip.data.tracker.PleosAutoStartTest#restoreAndManuallyFinishAfterProcessRestart' com.autotrip.test/androidx.test.runner.AndroidJUnitRunner
```

- APK/계측 빌드 성공. 실제 계측 2개 통과, 실패 0개, 건너뜀 0개.
- 09:08:11Z 실제 속도 구독 시작, 09:08:29Z AUTO id=1 / startMeters=0 저장.
- 09:08:33Z 프로세스 재시작 후 같은 id=1 활성 운행 복원.
- 09:08:35Z 수동 종료: endMeters=1000, drivenMeters=1000, DB 활성 운행 없음 확인.
- 첫 호스트 실행은 로그 파일 생성 전 cat 오류로 중단됐다. 로그 생성 대기를 보완한 뒤 위 두 시험을 실제 통과했다.

원본: build/stage7-auto-capture.log, stage7-auto-restore.log, stage7-auto-record.txt, stage7-console-before.json, stage7-console-after.json.

최종 프로덕션 수정 후 09:40Z 동일 두 시험을 재실행하여 다시 통과했다. 새 별도 DB의 AUTO id=1, startMeters=1000 → endMeters=2000, drivenMeters=1000, force-stop 후 같은 활성 운행 복원 확인. 원본 build/stage7-auto-final-run.log. Console 최종 Park/0km/h, 누적 거리 2km를 유지했다.

## 검증의 한계

실제 Room·속도·Odometer·프로세스 재시작 검증이며, Release Application/RoomAppContainer 전체 실행 검증은 아니다. Release 권한 요청 UI, 실차, 백그라운드/부팅 자동 재개, 실제 Binder 장애, NaviHelper/TTS/STT 종단간 동작은 이번 시험에 포함하지 않았다. 자동 종료/GPS는 이 시험에 포함하지 않는다.
