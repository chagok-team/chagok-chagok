# 자동 종료 신호 조사 — 8단계

2026-11-14, Vehicle 2.3.2 / API 34 / AutoTrip 앱 UID / PLEOS 에뮬레이터.

| 신호 | API/권한 | 실제 확인 | 판단 |
|---|---|---|---|
| 표준 기어 | CarPropertyManager / GEAR_SELECTION, CAR_POWERTRAIN | P/D/P 조회·콜백, 두 세션 | 주차 보조. 단독 종료 불가 |
| PLEOS 기어 | 기존 Vehicle.getGear(), CAR_DRIVE | PARK/DRIVE/PARK 콜백 재확인 | 표준 기어와 별도 vendor API, 단독 종료 불가 |
| 주차 브레이크 | PARKING_BRAKE_ON Boolean, CAR_POWERTRAIN | READ 설정·false 초기값/콜백. 측정값은 약 19분 이전 | 실제 ON/OFF 전환 미검증. 브레이크 페달과 다름 |
| 시동 | IGNITION_STATE, CAR_POWERTRAIN | 설정 없음, getProperty 미지원 예외 | 이 기기에서 종료 근거로 사용 불가 |
| 전원 정책 | 공개 CarPowerManager.getCurrentPowerPolicy/addPowerPolicyListener, READ_CAR_POWER_POLICY | 앱에 권한 미선언, 호출·정책 전환 미검증 | 전원 정책 자체도 시동 OFF와 동일하지 않음 |
| SDK 연결 종료 | Car lifecycle, Vehicle release | 소유 세션 해제·새 연결 가능 | 서비스 장애·클라이언트 종료도 가능, 시동 OFF 증거 아님 |
| 속도/계기판 | PERF_VEHICLE_SPEED / 기존 PLEOS Odometer | 정상 값·변화 재확인 | 신호 대기/정체와 운행 종료 구분 못 함 |
| Navi 도착/경로 종료 | 기존 NaviHelper 3.0.5 | 기존 목적지 이벤트 연동만 검증 | 차량 정차/시동 OFF로 해석하지 않음 |

CAR_POWERTRAIN은 현재 Debug 진단용 선언이다. Release에 종료 감지용 권한을 새로 추가하지 않았다. 새 SDK 메서드나 시동 신호를 만들어 사용하지 않는다.

설치 Vehicle AAR/기존 진단 코드와 [VehiclePropertyIds](https://developer.android.com/reference/android/car/VehiclePropertyIds), [공개 CarPowerManager](https://developer.android.com/reference/android/car/hardware/power/CarPowerManager)를 대조했다. AP_POWER_STATE_REQ/REPORT 등 내부 속성을 직접 사용하는 우회는 하지 않는다. DrivingMode는 이동 여부가 아니며 Brake는 주차 브레이크로 치환하지 않는다.

## 결론

실제 종료 생산자, 신선한 주차/시동 전환, 오탐 방지, 저장·수동 종료 충돌을 모두 검증하지 못했다. 자동 종료는 기본 비활성 유지. 실제 차량에서 ONCHANGE 상태의 지속 유효성, 시동 OFF 시 앱 생존 시간, 주차 후 재출발, 일시 정차, 권한·서비스 단절을 검증한 후 별도 승인으로 활성화한다.
