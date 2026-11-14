package com.autotrip.voice

import com.autotrip.domain.model.InputMethod
import com.autotrip.domain.model.TripPurpose

/**
 * 목적 질문 흐름이 운행기록·거래처에 접근하는 창구.
 *
 * A의 Repository를 직접 쓰지 않고 이 인터페이스를 거친다. 흐름을 단위 테스트하기 쉽게 하려는 것이다.
 * 실제 구현은 [TripRepositoryPurposeGateway] 하나다.
 */
interface PurposePromptGateway {
    suspend fun isPurposePending(tripId: Long): Boolean

    /** 확정된 목적. 미입력이거나 운행이 없으면 null (도착 안내용) */
    suspend fun confirmedPurpose(tripId: Long): TripPurpose?

    /** 등록 거래처 이름 (거래처 제안용) */
    suspend fun clientNames(): List<String>

    /**
     * 같은 목적지로 간 가장 최근 운행에서 확정된 목적 (이전 방문 목적 제안용). 없으면 null.
     * 목적지 이름은 공백·문장부호·대소문자를 무시하고 같으면 같은 곳으로 본다.
     */
    suspend fun lastPurposeFor(destinationName: String): TripPurpose?

    /** 끝난 운행에 최초 확정 (confirmPurpose) */
    suspend fun confirmPurpose(
        tripId: Long,
        purpose: TripPurpose,
        method: InputMethod,
        voiceRawText: String?,
    ): ConfirmOutcome

    /** 출발 시 목적 (setPlannedPurpose, v1.1): 주행 중이면 그 운행에, 정차 중이면 다음 운행용으로 보관 */
    suspend fun setPlannedPurpose(
        purpose: TripPurpose,
        method: InputMethod,
        voiceRawText: String?,
    ): ConfirmOutcome
}

sealed interface ConfirmOutcome {
    data object Saved : ConfirmOutcome

    /** 다른 경로(목록 화면 등)에서 이미 확정됨 */
    data object AlreadyConfirmed : ConfirmOutcome

    /** 저장 실패. [reason]은 사용자에게 보여줄 문구 */
    data class Failed(val reason: String) : ConfirmOutcome
}
