package com.autotrip.voice

import com.autotrip.domain.model.AutoTripError
import com.autotrip.domain.model.AutoTripResult
import com.autotrip.domain.model.InputMethod
import com.autotrip.domain.model.PurposeStatus
import com.autotrip.domain.model.TaxPeriod
import com.autotrip.domain.model.TripPurpose
import java.time.Clock
import java.time.LocalDate
import com.autotrip.domain.repository.ProfileRepository
import com.autotrip.domain.repository.TripRepository
import com.autotrip.ui.toUserMessage
import kotlinx.coroutines.flow.first

/**
 * 목적 질문 흐름([PurposePromptCoordinator])을 실제 Repository에 연결한다.
 *
 * - 목적 미입력 여부·확정 목적: observeTrip(tripId)의 현재 값 (운행이 없으면 묻지도 안내하지도 않음)
 * - 저장: userId를 넘기지 않으므로 기본 차량이용자가 적용된다 (INTERFACES 5장)
 */
class TripRepositoryPurposeGateway(
    private val tripRepository: TripRepository,
    private val profileRepository: ProfileRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : PurposePromptGateway {

    override suspend fun isPurposePending(tripId: Long): Boolean =
        tripRepository.observeTrip(tripId).first()?.purposeStatus == PurposeStatus.PENDING

    override suspend fun confirmedPurpose(tripId: Long): TripPurpose? =
        tripRepository.observeTrip(tripId).first()
            ?.takeIf { it.purposeStatus == PurposeStatus.CONFIRMED }
            ?.purpose

    override suspend fun clientNames(): List<String> =
        profileRepository.observeClients().first().map { it.name }

    /** 최근 1년 운행(startedAt 내림차순) 중 같은 목적지로 끝난, 목적이 확정된 첫 운행 */
    override suspend fun lastPurposeFor(destinationName: String): TripPurpose? {
        val today = LocalDate.now(clock)
        val trips = tripRepository.observeTrips(TaxPeriod(today.minusYears(1), today)).first()
        return trips
            .asSequence()
            .filter { it.endedAt != null && it.purposeStatus == PurposeStatus.CONFIRMED && it.purpose != null }
            .sortedByDescending { it.startedAt }
            .firstOrNull { PurposePromptCoordinator.sameDestination(it.destinationName, destinationName) }
            ?.purpose
    }

    override suspend fun confirmPurpose(
        tripId: Long,
        purpose: TripPurpose,
        method: InputMethod,
        voiceRawText: String?,
    ): ConfirmOutcome =
        tripRepository.confirmPurpose(tripId, purpose, method, userId = null, voiceRawText = voiceRawText).toOutcome()

    override suspend fun setPlannedPurpose(
        purpose: TripPurpose,
        method: InputMethod,
        voiceRawText: String?,
    ): ConfirmOutcome =
        tripRepository.setPlannedPurpose(purpose, method, voiceRawText).toOutcome()

    private fun AutoTripResult<Unit>.toOutcome(): ConfirmOutcome = when (this) {
        is AutoTripResult.Success -> ConfirmOutcome.Saved
        is AutoTripResult.Failure -> when (error) {
            is AutoTripError.PurposeAlreadyConfirmed -> ConfirmOutcome.AlreadyConfirmed
            else -> ConfirmOutcome.Failed(error.toUserMessage())
        }
    }
}
