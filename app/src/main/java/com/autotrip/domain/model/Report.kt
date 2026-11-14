// 출처: docs/INTERFACES.md v1.0 6장 집계·PDF 출력 계약 — 문서의 코드를 그대로 옮김.
// 클래스명·필드명·시그니처는 A·B 합의 없이 바꾸지 않는다 (바꿀 때는 문서 버전을 올리고 변경 이력에 남긴다).
package com.autotrip.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

data class MileageSummary(
    val period: TaxPeriod,
    val totalMeters: Long,            // ⑪ 거리가 확인된 모든 운행의 ⑦ 합 (목적 미입력 포함 — 잠정)
    val commuteMeters: Long,          // ⑧ 합
    val generalBusinessMeters: Long,  // ⑨ 합
    val businessMeters: Long,         // ⑫ = ⑧ + ⑨
    val nonBusinessMeters: Long,      // PERSONAL 합
    val unclassifiedMeters: Long,     // 목적 미입력 운행의 거리 합 (⑪에는 포함, ⑫에는 미포함)
    val businessUseRatio: Double?,    // ⑬ = ⑫ / ⑪, 범위 0.0~1.0, ⑪이 0이면 null
    val incompleteTripCount: Int,     // 계기판 값이 없어 ⑦을 계산할 수 없는 운행 수 (⑪에서 제외)
    val pendingPurposeTripCount: Int,
)

enum class RowFlag { ODOMETER_MISSING, PURPOSE_PENDING, MANUAL_TRIP, EDITED }

data class LogbookRow(
    val tripId: Long,
    val date: LocalDate,               // ③ 출발 시각 기준, 기기 시간대
    val dayOfWeek: DayOfWeek,
    val userDepartment: String?,       // ④
    val userName: String?,
    val odometerBeforeMeters: Long?,   // ⑤ null이면 빈칸으로 출력
    val odometerAfterMeters: Long?,    // ⑥
    val drivenMeters: Long?,           // ⑦
    val commuteMeters: Long?,          // ⑧ COMMUTE면 ⑦, 아니면 null
    val generalBusinessMeters: Long?,  // ⑨ GENERAL_BUSINESS면 ⑦, 아니면 null
    val remark: String,                // ⑩ A가 조합한 최종 문자열 (아래 규칙)
    val purpose: TripPurpose?,
    val source: TripSource,
    val flags: Set<RowFlag>,
)

data class LogbookExport(
    val business: BusinessProfile,
    val vehicle: VehicleInfo,
    val period: TaxPeriod,
    val rows: List<LogbookRow>,        // 운행 1건 = 1행, startedAt 오름차순
    val summary: MileageSummary,       // ⑪ ⑫ ⑬
    val generatedAt: Instant,
)
