package com.autotrip.voice

/** [SpeechInput.listen]의 결과 */
sealed interface ListenResult {
    /** 최종 인식 문장. 이 원문을 그대로 voiceRawText로 저장한다. */
    data class Recognized(val text: String) : ListenResult

    /** 인식은 끝났지만 말소리가 없었음(빈 결과) */
    data object NoSpeech : ListenResult

    /** [SpeechInput.stop]으로 중단됨 */
    data object Stopped : ListenResult

    data class Failed(val error: SpeechError) : ListenResult
}

/**
 * 음성 입력(STT). UI·도착 질문 흐름은 이 인터페이스만 쓰고 SDK 타입은 모른다.
 *
 * 구현체: PLEOS SpeechToText(Mode.ON_DEVICE, Locale.KOREA) 우선.
 * 동작하지 않으면 같은 인터페이스로 Android 표준 SpeechRecognizer 구현을 붙인다(교체 여부는 사용자가 결정).
 *
 * 생명주기: [prepare] → [listen]… → [release]. release 이후에는 새 객체를 만든다.
 * 런타임 마이크 권한(RECORD_AUDIO) 요청은 UI가 [listen] 전에 처리한다.
 */
interface SpeechInput {
    /**
     * SDK 초기화, 결과 리스너 등록, 한국어 설정. 성공해야 [listen]을 쓸 수 있다.
     * @return 성공이면 null, 실패면 원인
     */
    suspend fun prepare(): SpeechError?

    /**
     * 마이크를 열고 한 번 인식한다. 최종 결과가 나오거나 실패할 때까지 일시 중단한다.
     * 시간 제한은 호출하는 쪽이 withTimeout으로 건다. 코루틴이 취소되면 인식을 멈춘다.
     *
     * @param onPartial 중간 인식 결과(화면에 "듣는 중…" 표시용). 최종 결과 판단에는 쓰지 않는다.
     * @param onReady 엔진이 실제로 들을 준비가 됐을 때 한 번 (PLEOS onReady). 이 전에 말하면 놓칠 수 있어서
     *   화면은 이때 "듣고 있어요"로 바꾼다. (에뮬레이터 실측: request() 후 0.3~3.4초)
     */
    suspend fun listen(onPartial: (String) -> Unit = {}, onReady: () -> Unit = {}): ListenResult

    /** 인식 중이면 멈추고 마이크를 닫는다. 진행 중인 [listen]은 [ListenResult.Stopped]를 돌려준다. */
    fun stop()

    /** 리스너 해제 후 SDK를 해제한다. */
    fun release()
}
