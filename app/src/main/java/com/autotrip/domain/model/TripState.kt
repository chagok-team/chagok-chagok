// 출처: docs/INTERFACES.md v1.0 4장 운행 상태 계약 — 문서의 코드를 그대로 옮김.
// 클래스명·필드명·시그니처는 A·B 합의 없이 바꾸지 않는다 (바꿀 때는 문서 버전을 올리고 변경 이력에 남긴다).
package com.autotrip.domain.model

import java.time.Instant

sealed interface TripState {
    data object Idle : TripState
    data class Driving(val tripId: Long, val source: TripSource, val startedAt: Instant) : TripState
    data class Arrived(val tripId: Long, val source: TripSource, val endedAt: Instant) : TripState
}

data class DetectionAvailability(
    val autoDetectionSupported: Boolean,   // 자동 감지에 필요한 신호를 하나 이상 쓸 수 있는지
    val odometerSupported: Boolean,
    val locationSupported: Boolean,
    val navigationSupported: Boolean,
)
