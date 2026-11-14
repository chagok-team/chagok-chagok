// 출처: docs/INTERFACES.md v1.0 2장 공통 결과·오류 타입 — 문서의 코드를 그대로 옮김.
// 클래스명·필드명·시그니처는 A·B 합의 없이 바꾸지 않는다 (바꿀 때는 문서 버전을 올리고 변경 이력에 남긴다).
package com.autotrip.domain.model

sealed interface AutoTripResult<out T> {
    data class Success<T>(val value: T) : AutoTripResult<T>
    data class Failure(val error: AutoTripError) : AutoTripResult<Nothing>
}

sealed interface AutoTripError {
    data class TripNotFound(val tripId: Long) : AutoTripError
    /** 이미 목적이 확정된 운행에 confirmPurpose를 호출함 → updatePurpose를 써야 한다 */
    data class PurposeAlreadyConfirmed(val tripId: Long) : AutoTripError
    /** 목적이 확정되지 않은 운행에 updatePurpose를 호출함 */
    data class PurposeNotConfirmed(val tripId: Long) : AutoTripError
    /** 운행이 아직 끝나지 않았는데 목적을 확정하려 함, 주행 중 수동 시작 등 */
    data class InvalidState(val message: String) : AutoTripError
    /** 사업자 정보나 차량 정보가 없어 운행기록부를 출력할 수 없음 */
    data object ProfileIncomplete : AutoTripError
    data class Storage(val cause: Throwable) : AutoTripError
    /** sdk 예: "Vehicle.Odometer", "FusedLocation" */
    data class Sdk(val sdk: String, val message: String) : AutoTripError
}
