package com.autotrip.ui.home

import com.autotrip.domain.model.AutoTripResult
import com.autotrip.domain.model.BusinessProfile
import com.autotrip.domain.model.DetectionAvailability
import com.autotrip.domain.model.MileageSummary
import com.autotrip.domain.model.TaxPeriod
import com.autotrip.domain.model.TripSource
import com.autotrip.domain.model.TripState
import com.autotrip.domain.model.VehicleInfo
import com.autotrip.domain.repository.ProfileRepository
import com.autotrip.domain.repository.ReportRepository
import com.autotrip.domain.repository.TripRepository
import com.autotrip.domain.repository.TripTracker
import com.autotrip.ui.export.ExportMapper
import com.autotrip.ui.review.reviewFlow
import com.autotrip.ui.format.DisplayFormat
import com.autotrip.ui.toUserMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * 메인 대시보드 표시 모델 (Android 의존 없음, 단위 테스트 대상).
 * - 운행 상태는 크게, 올해 ⑪ ⑫ ⑬은 A가 계산한 MileageSummary 값 그대로.
 * - 목적 미입력 n건은 observePendingPurposeTrips() 기준 (INTERFACES 4장 재시작 후 안내).
 */

enum class ManualAction(val label: String) { START("수동으로 운행 시작"), END("운행 종료") }

data class HomeUi(
    val statusTitle: String,          // "대기 중" / "주행 중" / "도착"
    val statusDetail: String,         // "09:12 출발 · 수동 기록"
    val driving: Boolean,
    val periodLabel: String,          // "과세기간" / "사업연도"
    val periodText: String,
    val ratioText: String,            // ⑬ "85.7%" / "-"
    val totalKm: String,              // ⑪
    val businessKm: String,           // ⑫
    val pendingCount: Int,
    val profileMissing: Boolean,      // 사업자·차량 정보가 없으면 설정 안내
    val manualAction: ManualAction?,
    /** ⑬ 막대 표시용 (A가 계산한 businessUseRatio 그대로, 0.0~1.0). 없으면 막대를 그리지 않는다 */
    val ratio: Float? = null,
    /** 확인이 필요한 기록 건수 (재무·총무 담당자 안내용) */
    val reviewCount: Int = 0,
)

object HomeMapper {
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun map(
        state: TripState,
        availability: DetectionAvailability,
        summary: MileageSummary,
        business: BusinessProfile?,
        vehicle: VehicleInfo?,
        pendingCount: Int,
        zone: ZoneId,
        reviewCount: Int = 0,
    ): HomeUi {
        val numbers = ExportMapper.summary(summary, business?.type)
        val (title, detail) = status(state, availability, zone)
        return HomeUi(
            statusTitle = title,
            statusDetail = detail,
            driving = state is TripState.Driving,
            periodLabel = numbers.periodLabel,
            periodText = numbers.periodText,
            ratioText = numbers.ratioText,
            totalKm = numbers.totalKm,
            businessKm = numbers.businessKm,
            pendingCount = pendingCount,
            profileMissing = business == null || vehicle == null,
            manualAction = if (state is TripState.Driving) ManualAction.END else ManualAction.START,
            ratio = summary.businessUseRatio?.toFloat()?.coerceIn(0f, 1f),
            reviewCount = reviewCount,
        )
    }

    fun status(state: TripState, availability: DetectionAvailability, zone: ZoneId): Pair<String, String> = when (state) {
        TripState.Idle -> "대기 중" to
            if (availability.autoDetectionSupported) "출발하면 자동으로 기록해요" else "자동 감지를 쓸 수 없어요. 출발 전에 수동으로 시작해 주세요"
        is TripState.Driving -> "주행 중" to
            "${state.startedAt.time(zone)} 출발" + if (state.source == TripSource.MANUAL) " · 수동 기록" else ""
        is TripState.Arrived -> "도착" to "${state.endedAt.time(zone)} 운행을 마쳤어요"
    }

    private fun Instant.time(zone: ZoneId): String = atZone(zone).format(TIME)
}

fun homeFlow(
    tracker: TripTracker,
    tripRepository: TripRepository,
    profileRepository: ProfileRepository,
    reportRepository: ReportRepository,
    period: TaxPeriod,
    zone: ZoneId,
): Flow<HomeUi> {
    val profile = combine(profileRepository.observeBusinessProfile(), profileRepository.observeVehicle(), ::Pair)
    val counts = combine(
        tripRepository.observePendingPurposeTrips(),
        reviewFlow(tripRepository, profileRepository, period, zone),
    ) { pending, review -> pending.size to review.size }
    return combine(
        tracker.state,
        tracker.availability,
        reportRepository.observeSummary(period),
        profile,
        counts,
    ) { state, availability, summary, (business, vehicle), (pendingCount, reviewCount) ->
        HomeMapper.map(state, availability, summary, business, vehicle, pendingCount, zone, reviewCount)
    }
}

/** 수동 시작·종료. 성공하면 안내 문구, 실패하면 오류 문구. */
class HomeActions(private val tracker: TripTracker) {
    suspend fun run(action: ManualAction): String = when (action) {
        ManualAction.START -> tracker.startManualTrip().message("운행 기록을 시작했어요")
        ManualAction.END -> tracker.endManualTrip().message("운행 기록을 마쳤어요")
    }

    private fun AutoTripResult<Long>.message(done: String): String = when (this) {
        is AutoTripResult.Success -> done
        is AutoTripResult.Failure -> error.toUserMessage()
    }
}

/** 표시용: "-"가 아닌 km 값 뒤에만 단위를 붙인다 */
internal fun kmWithUnit(km: String): String = if (km == DisplayFormat.DASH) km else "$km km"
