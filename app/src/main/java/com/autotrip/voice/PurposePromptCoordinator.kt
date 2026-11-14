package com.autotrip.voice

import com.autotrip.domain.model.InputMethod
import com.autotrip.domain.model.NavigationDestination
import com.autotrip.domain.model.TripPurpose
import com.autotrip.domain.model.TripState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

/** 버튼 선택 화면으로 넘어온 이유 (화면 안내 문구용) */
enum class ButtonReason {
    /** 두 번 물었지만 목적을 알아듣지 못함(무응답·시간 초과 포함) */
    NOT_UNDERSTOOD,

    /** 두 번 물었지만 여러 목적이 섞여서 하나로 정하지 못함 */
    AMBIGUOUS,

    /** TTS·STT를 쓸 수 없음(초기화 실패, 마이크·권한 오류) */
    VOICE_UNAVAILABLE,

    /** 조용한 모드: 음성으로 묻지 않고 처음부터 버튼으로 받는다 (동승자가 있을 때) */
    QUIET_MODE,
}

/** 목적 질문 흐름의 화면 상태. UI는 이 값만 보고 그린다. */
sealed interface PurposePromptState {
    data object Idle : PurposePromptState

    /** 목적지가 등록 거래처라서 "○○ 방문으로 기록할까요?"라고 묻는 중 (네·아니요를 기다림) */
    data class SuggestingClient(val clientName: String) : PurposePromptState

    /** 전에 같은 목적지로 갔던 운행의 목적을 "지난번처럼 ○○으로 기록할까요?"라고 제안하는 중 (네·아니요를 기다림) */
    data class SuggestingPrevious(val purpose: TripPurpose) : PurposePromptState

    /** TTS로 목적을 묻는 중 */
    data class Asking(val attempt: Int) : PurposePromptState

    /**
     * STT로 듣는 중. [attempt]가 0이면 제안에 대한 네·아니요. [partialText]는 중간 인식 결과(표시용).
     * [ready]가 false면 마이크는 열었지만 엔진이 아직 준비 전 → 화면은 "잠시만요"(이때 말하면 놓칠 수 있음).
     */
    data class Listening(val attempt: Int, val partialText: String, val ready: Boolean = true) : PurposePromptState

    /** 목적을 알아듣고 TTS로 확인 안내 후 저장하는 중 */
    data class Confirming(val purpose: TripPurpose) : PurposePromptState

    /**
     * 음성으로 정하지 못함 → 정차 중 버튼 선택 화면.
     * 주행 중에는 UI가 이 화면을 띄우지 않는다(CarUxRestrictions). [candidates]가 있으면 그 버튼을 앞에 둔다.
     */
    data class AwaitingButton(val reason: ButtonReason, val candidates: Set<TripPurpose>) : PurposePromptState

    data class Completed(val purpose: TripPurpose, val method: InputMethod) : PurposePromptState

    /** 저장 실패. 버튼으로 다시 고르면 다시 저장한다. */
    data class SaveFailed(val purpose: TripPurpose, val reason: String) : PurposePromptState

    /** 도착: 목적을 다시 묻지 않고 기록 결과만 안내한다 */
    data class ArrivalNotice(val purpose: TripPurpose) : PurposePromptState
}

/**
 * 출발 시 운행 목적을 음성으로 묻는 흐름 (INTERFACES.md v1.1 4장 "출발 질문 정책").
 *
 * 질문 조건
 * 1. 이번 실행 중에 내비 목적지 이벤트를 받았다. (destinationEvents는 지난 이벤트를 다시 보내지 않는다)
 * 2. 주행 중이면 그 운행이 목적 미입력이고, 이번 실행에서 그 운행으로 아직 묻지 않았다.
 * 3. 정차 중(Idle·Arrived)이면 [Config.plannedWindow] 안에 같은 목적지로 물은 적이 없다.
 *    출발 전에 다른 목적지로 바꾸면 다시 묻고(묻는 중이었으면 처음부터), 새 답이 보관 목적을 대체한다.
 *    주행 중에 바꾸면 다시 묻지 않는다(조건 2).
 *
 * 흐름
 * 같은 목적지로 간 적이 있음 → "지난번처럼 ○○으로 기록할까요?" (가장 최근 확정 목적. 거래처보다 먼저)
 * 그렇지 않고 목적지가 등록 거래처 → "○○ 방문으로 기록할까요?"
 *   → 네: 제안한 목적 / 아니요·불확실: 아래 일반 질문 ("아니요, 회의예요"처럼 목적을 같이 말하면 그 목적)
 * 일반 질문(TTS) → 듣기(STT) → 분류 → 하나로 분류되면 확인 안내 후 저장(VOICE)
 *                                → 실패·불확실이면 한 번 더 → 그래도 안 되면 버튼 선택(BUTTON)
 * 저장은 setPlannedPurpose. 단, 질문하던 운행이 이미 끝났으면(버튼을 도착 후에 고름) 그 운행에 confirmPurpose.
 * 도착(Driving → Arrived를 직접 관찰)하면 그 운행의 확정된 목적을 "○○으로 기록했어요"라고 안내만 한다.
 *
 * 조용한 모드 ([quietMode]가 true, 동승자가 있을 때)
 * - TTS·STT를 쓰지 않는다. 거래처 이름·목적을 소리 내어 읽지 않는다.
 * - 정차 중 목적지를 찍으면 바로 버튼 화면을 띄운다. 제안할 목적(지난번 목적·거래처 방문)은 버튼 앞에 둔다.
 * - 고르지 않고 출발하면 화면을 닫고 그 운행은 목적 미입력으로 남는다. 주행 중에는 묻지 않는다.
 * - 도착 안내는 화면에만 보여 준다.
 */
class PurposePromptCoordinator(
    private val scope: CoroutineScope,
    private val tripStates: Flow<TripState>,
    private val destinations: Flow<NavigationDestination>,
    private val gateway: PurposePromptGateway,
    private val speechOutput: SpeechOutput,
    private val speechInput: SpeechInput,
    private val mapper: VoicePurposeMapper = VoicePurposeMapper(),
    private val yesNo: YesNoMapper = YesNoMapper(),
    private val config: Config = Config(),
    private val clock: () -> Instant = Instant::now,
    /** 조용한 모드인지 (질문을 시작할 때마다 읽는다) */
    private val quietMode: () -> Boolean = { false },
    /** 진단 로그 (앱에서는 logcat "AutoTripSTT"로). 순수 Kotlin 유지를 위해 함수로 받는다. */
    private val log: (String) -> Unit = {},
) {
    data class Config(
        /** 한 번 듣는 최대 시간 */
        val listenTimeoutMillis: Long = 8_000,
        /** 음성으로 묻는 최대 횟수 (첫 질문 + 재질문 1회) */
        val maxVoiceAttempts: Int = 2,
        /** 출발 전에 받은 목적의 유효 시간 (A의 보관 시간과 같게) */
        val plannedWindow: Duration = Duration.ofMinutes(30),
    )

    private val _state = MutableStateFlow<PurposePromptState>(PurposePromptState.Idle)
    val state: StateFlow<PurposePromptState> = _state.asStateFlow()

    // 아래 값은 이벤트 수집 코루틴과 그 코루틴이 띄운 작업에서만 바꾼다 (같은 스코프·디스패처)
    private var tripState: TripState? = null
    /** 이번 실행에서 이미 목적을 물은(또는 출발 전에 받은 목적이 붙은) 운행 */
    private val promptedTripIds = mutableSetOf<Long>()
    /** 정차 중에 질문을 시작한 시각. 운행이 시작되면 그 운행에 붙이고 지운다. */
    private var plannedAskedAt: Instant? = null
    /** 정차 중에 마지막으로 물은 목적지 (같은 곳을 다시 찍으면 묻지 않기 위해) */
    private var plannedDestination: String? = null
    /** 지금 질문이 붙어 있는 운행. 정차 중에 시작한 질문이면 출발할 때 정해진다. */
    private var sessionTripId: Long? = null

    private var watchJob: Job? = null
    private var promptJob: Job? = null
    private var noticeJob: Job? = null

    private sealed interface Event {
        data class State(val value: TripState) : Event
        data class Destination(val value: NavigationDestination) : Event
    }

    /** 운행 상태와 목적지 이벤트 구독을 시작한다. 여러 번 호출해도 한 번만 구독한다. */
    fun start() {
        if (watchJob != null) return
        watchJob = scope.launch {
            merge(tripStates.map { Event.State(it) }, destinations.map { Event.Destination(it) }).collect { event ->
                when (event) {
                    is Event.State -> onTripState(event.value)
                    is Event.Destination -> onDestination(event.value)
                }
            }
        }
    }

    /** 버튼 선택 화면(또는 저장 실패 후)에서 목적을 고른다. */
    fun selectPurpose(purpose: TripPurpose) {
        val s = _state.value
        if (s !is PurposePromptState.AwaitingButton && s !is PurposePromptState.SaveFailed) return
        promptJob?.cancel()
        promptJob = scope.launch { save(purpose, InputMethod.BUTTON, voiceRawText = null) }
    }

    /** 사용자가 화면을 닫음. 목적이 없으면 운행은 PENDING으로 남는다. */
    fun dismiss() = cancelPrompt()

    /** 구독과 진행 중인 질문을 모두 멈춘다. (화면·서비스 종료 시) */
    fun stop() {
        cancelPrompt()
        noticeJob?.cancel()
        watchJob?.cancel()
        watchJob = null
    }

    private fun cancelPrompt() {
        promptJob?.cancel()
        promptJob = null
        _state.value = PurposePromptState.Idle
    }

    // ── 이벤트 ──────────────────────────────────────────

    private fun onTripState(current: TripState) {
        val arrivedTripId = observedArrival(tripState, current)
        tripState = current
        if (current is TripState.Driving && plannedAskedAt != null) {
            // 정차 중에 받은(또는 받는 중인) 목적이 이 운행의 목적이다
            promptedTripIds += current.tripId
            sessionTripId = current.tripId
            plannedAskedAt = null
            plannedDestination = null
            // 조용한 모드에서 고르지 않고 출발: 화면을 닫고 목적 미입력으로 남긴다 (주행 중에는 입력받지 않음)
            if ((_state.value as? PurposePromptState.AwaitingButton)?.reason == ButtonReason.QUIET_MODE) cancelPrompt()
        }
        if (arrivedTripId != null) announceArrival(arrivedTripId)
    }

    private fun onDestination(destination: NavigationDestination) {
        when (val current = tripState) {
            is TripState.Driving -> {
                if (promptJob?.isActive == true) return   // 출발 전에 시작한 질문이 아직 진행 중
                if (current.tripId in promptedTripIds) return
                if (quietMode()) return   // 조용한 모드: 주행 중에는 화면으로도 묻지 않는다 (정차 후 미입력 목록에서)
                promptedTripIds += current.tripId
                startSession(current.tripId, destination, checkPending = true)
            }
            else -> {
                val askedAt = plannedAskedAt
                val recentlyAsked = askedAt != null && Duration.between(askedAt, clock()) < config.plannedWindow
                if (recentlyAsked && sameDestination(plannedDestination, destination.name)) return
                plannedAskedAt = clock()
                plannedDestination = destination.name
                startSession(tripId = null, destination, checkPending = false)
            }
        }
    }

    private fun startSession(tripId: Long?, destination: NavigationDestination, checkPending: Boolean) {
        noticeJob?.cancel()
        // 출발 전 목적지를 바꾼 경우: 묻던 질문을 멈추고 새 목적지로 다시 시작한다.
        // 이전 작업이 TTS·STT를 정리(stop)할 때까지 기다려야 새 질문이 끊기지 않는다.
        val previous = promptJob
        previous?.cancel()
        sessionTripId = tripId
        promptJob = scope.launch {
            previous?.join()
            if (checkPending && tripId != null && !gateway.isPurposePending(tripId)) return@launch
            runPrompt(destination.name)
        }
    }

    private fun announceArrival(tripId: Long) {
        if (promptJob?.isActive == true) return   // 아직 묻는 중이면 끼어들지 않는다
        noticeJob?.cancel()
        noticeJob = scope.launch {
            val purpose = gateway.confirmedPurpose(tripId) ?: return@launch   // 미입력이면 안내하지 않음
            _state.value = PurposePromptState.ArrivalNotice(purpose)
            if (quietMode()) return@launch   // 화면에만 보여 주고 소리 내어 읽지 않는다
            if (speechOutput.prepare() == null) speechOutput.speak(VoicePrompts.recorded(purpose))
        }
    }

    // ── 질문 ────────────────────────────────────────────

    private suspend fun runPrompt(destinationName: String?) {
        try {
            if (quietMode()) {
                // 소리 없이 버튼 화면. 제안할 목적이 있으면 그 버튼을 앞에 둔다.
                awaitButton(ButtonReason.QUIET_MODE, quietSuggestion(destinationName), announce = false)
                return
            }
            speechOutput.prepare()?.let { error ->
                // 질문조차 할 수 없으니 바로 버튼으로
                log("TTS 준비 실패 $error → 버튼")
                awaitButton(ButtonReason.VOICE_UNAVAILABLE, emptySet(), announce = false)
                return
            }
            speechInput.prepare()?.let { error ->
                log("STT 준비 실패 $error → 버튼")
                awaitButton(ButtonReason.VOICE_UNAVAILABLE, emptySet(), announce = true)
                return
            }
            if (destinationName != null) {
                // 1순위: 같은 목적지로 갔던 지난 운행의 목적 (실제로 입력한 값이라 거래처 추측보다 정확하다)
                val previous = gateway.lastPurposeFor(destinationName)
                if (previous != null) {
                    if (suggest(previous, PurposePromptState.SuggestingPrevious(previous), VoicePrompts.suggestPrevious(previous))) return
                } else {
                    // 2순위: 등록 거래처
                    val client = matchClient(destinationName, gateway.clientNames())
                    if (client != null &&
                        suggest(TripPurpose.CLIENT_VISIT, PurposePromptState.SuggestingClient(client), VoicePrompts.suggestClient(client))
                    ) return
                }
            }
            askByVoice()
        } catch (e: CancellationException) {
            speechOutput.stop()
            speechInput.stop()
            throw e
        }
    }

    /** 조용한 모드에서 버튼 앞에 둘 목적: 음성 제안과 같은 순서(지난번 목적 → 등록 거래처) */
    private suspend fun quietSuggestion(destinationName: String?): Set<TripPurpose> {
        if (destinationName == null) return emptySet()
        gateway.lastPurposeFor(destinationName)?.let { return setOf(it) }
        return if (matchClient(destinationName, gateway.clientNames()) != null) setOf(TripPurpose.CLIENT_VISIT) else emptySet()
    }

    /**
     * 목적 하나를 제안하고 네·아니요를 듣는다. 자동으로 확정하지 않고 "네"일 때만 그 목적으로 저장한다.
     * @return 이 단계에서 끝났으면 true, 일반 질문으로 넘어가야 하면 false
     */
    private suspend fun suggest(purpose: TripPurpose, state: PurposePromptState, sentence: String): Boolean {
        _state.value = state
        if (speechOutput.speak(sentence) !is SpeakResult.Done) {
            awaitButton(ButtonReason.VOICE_UNAVAILABLE, setOf(purpose), announce = false)
            return true
        }
        val heard = listen(attempt = 0)
        if (heard is ListenResult.Failed && isDeviceError(heard.error)) {
            log("제안 응답: 장치 오류 ${heard.error} → 버튼")
            awaitButton(ButtonReason.VOICE_UNAVAILABLE, setOf(purpose), announce = true)
            return true
        }
        if (heard !is ListenResult.Recognized) {
            log("제안 응답 없음(${heard?.let { it::class.simpleName } ?: "시간 초과"}) → 일반 질문")
            return false
        }
        val yesNoResult = yesNo.map(heard.text)
        log("YesNoMapper 입력=${maskForLog(heard.text)} 결과=$yesNoResult")
        if (yesNoResult == YesNoResult.Yes) {
            confirmByVoice(purpose, heard.text)
            return true
        }
        // "아니요, 회의예요"처럼 목적을 같이 말했으면 그 목적으로
        val mapped = mapper.map(heard.text)
        log("VoicePurposeMapper 입력=${maskForLog(heard.text)} 결과=$mapped")
        if (mapped is PurposeMappingResult.Matched) {
            confirmByVoice(mapped.purpose, heard.text)
            return true
        }
        return false
    }

    private suspend fun askByVoice() {
        var reason = ButtonReason.NOT_UNDERSTOOD
        var candidates = emptySet<TripPurpose>()

        for (attempt in 1..config.maxVoiceAttempts) {
            _state.value = PurposePromptState.Asking(attempt)
            val question = when {
                attempt == 1 -> VoicePrompts.QUESTION
                reason == ButtonReason.AMBIGUOUS -> VoicePrompts.RETRY_AMBIGUOUS
                else -> VoicePrompts.RETRY_NOT_UNDERSTOOD
            }
            if (speechOutput.speak(question) !is SpeakResult.Done) {
                awaitButton(ButtonReason.VOICE_UNAVAILABLE, candidates, announce = false)
                return
            }

            val heard = listen(attempt)
            if (heard !is ListenResult.Recognized) log("질문 ${attempt}회차 응답 없음(${heard?.let { it::class.simpleName } ?: "시간 초과"})")
            when (heard) {
                is ListenResult.Recognized -> when (val mapped = mapper.map(heard.text).also {
                    log("VoicePurposeMapper 입력=${maskForLog(heard.text)} 결과=$it (질문 ${attempt}회차)")
                }) {
                    is PurposeMappingResult.Matched -> {
                        confirmByVoice(mapped.purpose, heard.text)
                        return
                    }
                    is PurposeMappingResult.Ambiguous -> {
                        reason = ButtonReason.AMBIGUOUS
                        candidates = mapped.candidates
                    }
                    PurposeMappingResult.NoMatch -> reason = ButtonReason.NOT_UNDERSTOOD
                }
                null, ListenResult.NoSpeech, ListenResult.Stopped,
                is ListenResult.Failed -> {
                    if (heard is ListenResult.Failed && isDeviceError(heard.error)) {
                        // 마이크·권한·엔진 오류는 다시 물어도 소용없다
                        awaitButton(ButtonReason.VOICE_UNAVAILABLE, candidates, announce = true)
                        return
                    }
                    reason = ButtonReason.NOT_UNDERSTOOD
                }
            }
        }
        awaitButton(reason, candidates, announce = true)
    }

    /** 시간 초과면 마이크를 닫고 null */
    private suspend fun listen(attempt: Int): ListenResult? {
        // 엔진이 준비될 때까지는 "잠시만요", 준비(onReady)되면 "듣고 있어요"
        _state.value = PurposePromptState.Listening(attempt, partialText = "", ready = false)
        val heard = withTimeoutOrNull(config.listenTimeoutMillis) {
            speechInput.listen(
                onPartial = { partial ->
                    _state.update {
                        if (it is PurposePromptState.Listening && it.attempt == attempt) it.copy(partialText = partial, ready = true) else it
                    }
                },
                onReady = {
                    _state.update {
                        if (it is PurposePromptState.Listening && it.attempt == attempt) it.copy(ready = true) else it
                    }
                },
            )
        }
        if (heard == null) {
            log("듣기 시간 초과 ${config.listenTimeoutMillis}ms (attempt=$attempt) → STT stop")
            speechInput.stop()
        }
        return heard
    }

    private fun isDeviceError(error: SpeechError) = error !is SpeechError.Timeout && error !is SpeechError.Network

    private suspend fun confirmByVoice(purpose: TripPurpose, rawText: String) {
        _state.value = PurposePromptState.Confirming(purpose)
        speechOutput.speak(VoicePrompts.confirm(purpose))   // 안내가 실패해도 저장은 한다
        save(purpose, InputMethod.VOICE, voiceRawText = rawText)
    }

    private suspend fun awaitButton(reason: ButtonReason, candidates: Set<TripPurpose>, announce: Boolean) {
        log("버튼 선택으로 전환 reason=$reason candidates=$candidates")
        if (announce) speechOutput.speak(VoicePrompts.FALLBACK_TO_BUTTON)
        _state.value = PurposePromptState.AwaitingButton(reason, candidates)
    }

    private suspend fun save(purpose: TripPurpose, method: InputMethod, voiceRawText: String?) {
        val tripId = sessionTripId
        val current = tripState
        val tripStillDriving = current is TripState.Driving && current.tripId == tripId
        val outcome = if (tripId != null && !tripStillDriving) {
            // 질문하던 운행이 이미 끝남 → 그 운행에 직접 확정
            gateway.confirmPurpose(tripId, purpose, method, voiceRawText)
        } else {
            gateway.setPlannedPurpose(purpose, method, voiceRawText)
        }
        _state.value = when (outcome) {
            ConfirmOutcome.Saved -> PurposePromptState.Completed(purpose, method)
            ConfirmOutcome.AlreadyConfirmed -> PurposePromptState.Idle
            is ConfirmOutcome.Failed -> PurposePromptState.SaveFailed(purpose, outcome.reason)
        }
    }

    companion object {
        /**
         * [previous] → [current]가 "직접 관찰한 도착"이면 그 운행 ID를, 아니면 null을 돌려준다.
         * previous가 null(구독 직후 첫 값)이면 재시작 후 복원된 Arrived일 수 있으므로 도착으로 보지 않는다.
         */
        fun observedArrival(previous: TripState?, current: TripState): Long? =
            if (previous is TripState.Driving && current is TripState.Arrived && previous.tripId == current.tripId) {
                current.tripId
            } else {
                null
            }

        /** 공백·문장부호·대소문자를 무시하고 같은 목적지인지 본다. 둘 다 이름이 없으면 같다고 본다. */
        fun sameDestination(a: String?, b: String?): Boolean =
            a?.let { VoicePurposeMapper.normalize(it) } == b?.let { VoicePurposeMapper.normalize(it) }

        /**
         * 목적지 이름에 등록 거래처 이름이 들어 있으면 그 거래처를 돌려준다. 여러 개면 가장 긴 이름.
         * 공백·문장부호·대소문자는 무시한다. 한 글자 이름은 엉뚱하게 걸리므로 보지 않는다.
         */
        fun matchClient(destinationName: String, clientNames: List<String>): String? {
            val destination = VoicePurposeMapper.normalize(destinationName)
            return clientNames
                .filter { VoicePurposeMapper.normalize(it).length >= 2 && destination.contains(VoicePurposeMapper.normalize(it)) }
                .maxByOrNull { VoicePurposeMapper.normalize(it).length }
        }
    }
}
