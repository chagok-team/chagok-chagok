package com.autotrip.voice

/** [SpeechOutput.speak]의 결과 */
sealed interface SpeakResult {
    /** 끝까지 읽음 */
    data object Done : SpeakResult

    /** [SpeechOutput.stop]으로 중단됨 */
    data object Stopped : SpeakResult

    data class Failed(val error: SpeechError) : SpeakResult
}

/**
 * 음성 출력(TTS). UI·도착 질문 흐름은 이 인터페이스만 쓰고 SDK 타입은 모른다.
 *
 * 구현체: PLEOS TextToSpeech(Mode.ON_DEVICE, Locale.KOREA) 우선.
 * 동작하지 않으면 같은 인터페이스로 Android 표준 TextToSpeech 구현을 붙인다(교체 여부는 사용자가 결정).
 *
 * 생명주기: [prepare] → [speak]… → [release]. release 이후에는 새 객체를 만든다.
 */
interface SpeechOutput {
    /**
     * SDK 초기화와 한국어 설정. 성공해야 [speak]를 쓸 수 있다. 여러 번 호출해도 한 번만 초기화한다.
     * @return 성공이면 null, 실패면 원인
     */
    suspend fun prepare(): SpeechError?

    /**
     * [text]를 읽고, 다 읽거나 중단되거나 실패할 때까지 일시 중단한다.
     * 호출한 코루틴이 취소되면 읽기를 멈춘다.
     */
    suspend fun speak(text: String): SpeakResult

    /** 읽는 중이면 멈춘다. 진행 중인 [speak]는 [SpeakResult.Stopped]를 돌려준다. */
    fun stop()

    /** 리스너 해제 후 SDK를 해제한다. */
    fun release()
}
