package com.autotrip.ui.permission

/*
 * 앱이 사용자에게 받는 런타임 권한과 표시 규칙 (Android 의존 없음, 단위 테스트 대상).
 * - 권한이 없어도 앱은 동작한다. 해당 기능만 대체 경로로 간다(버튼 입력, 수동 운행, 위치 "-").
 * - 위치는 앱을 쓰는 동안(전경)만. BACKGROUND_LOCATION은 요청하지 않는다 (A 7단계 범위와 같게).
 */

private const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
private const val CAR_SPEED = "android.car.permission.CAR_SPEED"
private const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
private const val COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"

enum class AppPermission(
    /** 요청할 Android 권한 이름들 */
    val androidNames: List<String>,
    /** 허용 여부를 판단하는 권한 (A의 코드가 실제로 확인하는 것) */
    val requiredName: String,
    val title: String,
    /** 무엇에 쓰는지 */
    val purpose: String,
    /** 꺼져 있으면 어떻게 되는지 */
    val deniedEffect: String,
    /** 시뮬레이션(시연) 모드에서도 필요한지 */
    val neededInDemo: Boolean,
) {
    MICROPHONE(
        listOf(RECORD_AUDIO), RECORD_AUDIO, "마이크",
        "운행 목적을 음성으로 답해요",
        "목적은 정차 중 화면 버튼으로 입력해요",
        neededInDemo = true,
    ),
    VEHICLE_SPEED(
        listOf(CAR_SPEED), CAR_SPEED, "차량 속도",
        "출발을 감지해 운행을 자동으로 기록해요",
        "출발 전에 홈의 '수동으로 운행 시작'을 눌러 주세요",
        neededInDemo = false,
    ),
    LOCATION(
        // 정밀 위치를 고르지 않아도 대략 위치는 고를 수 있게 둘 다 요청한다. 기록에는 정밀 위치가 필요하다.
        listOf(FINE_LOCATION, COARSE_LOCATION), FINE_LOCATION, "위치",
        "출발·도착 위치를 운행기록부에 남겨요 (앱을 쓰는 동안만)",
        "출발지·도착지가 '-'로 남아요",
        neededInDemo = false,
    ),
}

data class PermissionRowUi(
    val permission: AppPermission,
    val granted: Boolean,
    val stateText: String,        // "허용됨" / "꺼짐"
    val detailText: String,       // 허용됨이면 용도, 꺼짐이면 영향
)

object PermissionRules {
    const val GRANTED = "허용됨"
    const val DENIED = "꺼짐"
    const val BLOCKED_HINT = "허용 창이 뜨지 않으면 차량 설정 > 앱 > 차곡차곡 > 권한에서 켜 주세요"

    /** 이 실행 모드에서 다루는 권한 (시연 모드는 Fake 데이터라 속도·위치를 쓰지 않는다) */
    fun relevant(isDemo: Boolean): List<AppPermission> =
        AppPermission.values().filter { !isDemo || it.neededInDemo }

    /**
     * 처음 실행할 때 요청할 Android 권한 이름.
     * 앱이 선언하지 않은 권한([declared]에 없음)은 요청해도 창이 뜨지 않고 바로 거부되므로 뺀다.
     */
    fun startupRequest(isDemo: Boolean, granted: Set<AppPermission>, declared: Set<String>): List<String> =
        relevant(isDemo)
            .filterNot { it in granted }
            .flatMap { it.androidNames }
            .filter { it in declared }
            .distinct()

    fun rows(isDemo: Boolean, granted: Set<AppPermission>, declared: Set<String>): List<PermissionRowUi> =
        relevant(isDemo)
            .filter { it.requiredName in declared }   // 이 빌드가 쓰지 않는 권한은 보여 주지 않는다
            .map { p ->
                val ok = p in granted
                PermissionRowUi(p, ok, if (ok) GRANTED else DENIED, if (ok) p.purpose else p.deniedEffect)
            }
}
