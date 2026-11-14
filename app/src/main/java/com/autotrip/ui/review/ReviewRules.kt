package com.autotrip.ui.review

import com.autotrip.domain.model.OdometerCaptureStatus
import com.autotrip.domain.model.PurposeCategory
import com.autotrip.domain.model.PurposeStatus
import com.autotrip.domain.model.Trip
import com.autotrip.domain.model.TripUser
import com.autotrip.ui.format.DisplayFormat
import com.autotrip.ui.trip.TripRowUi
import com.autotrip.ui.trip.TripUiMapper
import java.time.Duration
import java.time.ZoneId
import kotlin.math.abs

/*
 * 재무·총무 담당자용 "확인이 필요한 기록" (제안서 5.2⑤).
 * 앱은 기록이 맞는지 판정하지 않고, 확인이 필요한 사유만 보여 준다. 집계(⑪⑫⑬)는 다시 계산하지 않는다.
 * Android 의존 없음, 단위 테스트 대상.
 */

enum class ReviewReason(val label: String) {
    PURPOSE_PENDING("목적 미입력"),
    MISSING_DESTINATION("업무 목적인데 행선지 없음"),
    ODOMETER_GAP("앞 운행과 계기판 거리가 이어지지 않음"),
    LATE_INPUT("운행 7일 뒤에 목적 입력"),
}

data class ReviewItemUi(
    val row: TripRowUi,
    val reasons: List<ReviewReason>,
    /** 사유별 설명 (예: "앞 운행 도착 45,268.9 km → 이번 출발 45,300.0 km") */
    val details: List<String>,
)

object ReviewRules {
    /** 기본 7일 (제안서 기준) */
    val LATE_INPUT_THRESHOLD: Duration = Duration.ofDays(7)

    /** 계기판 값 반올림·수집 시점 차이를 감안한 허용 오차 (0.1 km) */
    const val ODOMETER_GAP_TOLERANCE_METERS = 100L

    /**
     * @param trips 한 과세기간의 운행 (순서 무관)
     * @return 사유가 하나라도 있는 운행만, 최근 운행부터
     */
    fun review(trips: List<Trip>, users: List<TripUser>, zone: ZoneId): List<ReviewItemUi> {
        val userById = users.associateBy { it.id }
        val ended = trips.filter { it.endedAt != null }.sortedBy { it.startedAt }
        val gapDetails = odometerGaps(ended)

        return ended.asReversed().mapNotNull { trip ->
            val reasons = mutableListOf<ReviewReason>()
            val details = mutableListOf<String>()

            if (trip.purposeStatus == PurposeStatus.PENDING) {
                reasons += ReviewReason.PURPOSE_PENDING
            }
            if (isBusinessWithoutDestination(trip)) {
                reasons += ReviewReason.MISSING_DESTINATION
            }
            gapDetails[trip.id]?.let {
                reasons += ReviewReason.ODOMETER_GAP
                details += it
            }
            lateInputDays(trip)?.let { days ->
                reasons += ReviewReason.LATE_INPUT
                details += "운행 종료 ${days}일 뒤 입력"
            }
            if (reasons.isEmpty()) null
            else ReviewItemUi(TripUiMapper.row(trip, userById, zone), reasons, details)
        }
    }

    private fun isBusinessWithoutDestination(trip: Trip): Boolean {
        val purpose = trip.purpose ?: return false
        if (trip.purposeStatus != PurposeStatus.CONFIRMED) return false
        if (purpose.category == PurposeCategory.NON_BUSINESS) return false
        return trip.destinationName.isNullOrBlank() && trip.endPlace?.label.isNullOrBlank()
    }

    /** 앞 운행의 주행 후 거리와 다음 운행의 주행 전 거리가 허용 오차보다 차이 나는 운행 → 설명 */
    private fun odometerGaps(endedAscending: List<Trip>): Map<Long, String> =
        endedAscending.zipWithNext().mapNotNull { (prev, next) ->
            val before = prev.odometerEnd.takeIf { it.status == OdometerCaptureStatus.CAPTURED }?.meters
            val after = next.odometerStart.takeIf { it.status == OdometerCaptureStatus.CAPTURED }?.meters
            if (before == null || after == null || abs(after - before) <= ODOMETER_GAP_TOLERANCE_METERS) null
            else next.id to "앞 운행 도착 ${DisplayFormat.km(before)} km → 이번 출발 ${DisplayFormat.km(after)} km"
        }.toMap()

    private fun lateInputDays(trip: Trip): Long? {
        val ended = trip.endedAt ?: return null
        val input = trip.purposeInputTime ?: return null
        val gap = Duration.between(ended, input)
        return if (gap > LATE_INPUT_THRESHOLD) gap.toDays() else null
    }
}
