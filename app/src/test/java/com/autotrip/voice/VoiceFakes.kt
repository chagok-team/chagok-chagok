package com.autotrip.voice

import com.autotrip.domain.model.InputMethod
import com.autotrip.domain.model.TripPurpose
import kotlinx.coroutines.awaitCancellation

/** 읽은 문장을 기록하고 바로 Done을 돌려주는 TTS */
class FakeSpeechOutput(
    private val prepareError: SpeechError? = null,
) : SpeechOutput {
    val spoken = mutableListOf<String>()
    var stopCount = 0

    override suspend fun prepare(): SpeechError? = prepareError
    override suspend fun speak(text: String): SpeakResult {
        spoken += text
        return SpeakResult.Done
    }
    override fun stop() { stopCount++ }
    override fun release() {}
}

/**
 * 미리 정한 결과를 차례로 돌려주는 STT.
 * 결과가 null이거나 바닥나면 취소될 때까지 응답하지 않는다(무응답·시간 초과 흉내).
 */
class FakeSpeechInput(
    results: List<ListenResult?> = emptyList(),
    private val prepareError: SpeechError? = null,
    private val partials: List<String> = emptyList(),
) : SpeechInput {
    private val queue = ArrayDeque(results)
    var listenCount = 0
    var stopCount = 0

    override suspend fun prepare(): SpeechError? = prepareError
    /** false면 준비 알림(onReady)을 보내지 않는다 (엔진이 준비되지 않은 상태 흉내) */
    var sendReady = true

    override suspend fun listen(onPartial: (String) -> Unit, onReady: () -> Unit): ListenResult {
        listenCount++
        if (sendReady) onReady()
        partials.forEach(onPartial)
        return (if (queue.isEmpty()) null else queue.removeFirst()) ?: awaitCancellation()
    }
    override fun stop() { stopCount++ }
    override fun release() {}
}

class FakeGateway(
    pendingTripIds: Set<Long> = setOf(1L),
    private val outcomes: MutableList<ConfirmOutcome> = mutableListOf(),
    private val clients: List<String> = emptyList(),
    private val confirmed: Map<Long, TripPurpose> = emptyMap(),
    /** 목적지 이름 → 지난번 목적 (이름은 정확히 같을 때만, 정규화 규칙은 실제 구현 테스트에서 본다) */
    private val previous: Map<String, TripPurpose> = emptyMap(),
) : PurposePromptGateway {
    private val pending = pendingTripIds.toMutableSet()

    /** tripId가 null이면 setPlannedPurpose 호출 */
    data class Call(
        val tripId: Long?,
        val purpose: TripPurpose,
        val method: InputMethod,
        val voiceRawText: String?,
    )
    val calls = mutableListOf<Call>()

    override suspend fun isPurposePending(tripId: Long) = tripId in pending
    override suspend fun confirmedPurpose(tripId: Long): TripPurpose? = confirmed[tripId]
    override suspend fun clientNames(): List<String> = clients
    override suspend fun lastPurposeFor(destinationName: String): TripPurpose? = previous[destinationName]

    override suspend fun confirmPurpose(tripId: Long, purpose: TripPurpose, method: InputMethod, voiceRawText: String?): ConfirmOutcome {
        calls += Call(tripId, purpose, method, voiceRawText)
        val outcome = outcomes.removeFirstOrNull() ?: ConfirmOutcome.Saved
        if (outcome == ConfirmOutcome.Saved) pending -= tripId
        return outcome
    }

    override suspend fun setPlannedPurpose(
        purpose: TripPurpose,
        method: InputMethod,
        voiceRawText: String?,
    ): ConfirmOutcome {
        calls += Call(null, purpose, method, voiceRawText)
        return outcomes.removeFirstOrNull() ?: ConfirmOutcome.Saved
    }
}
