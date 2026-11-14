package com.autotrip.voice

/**
 * 음성 입출력 오류. PLEOS·Android 표준 구현 어느 쪽이든 이 타입으로 바꿔서 돌려준다.
 *
 * PLEOS SDK의 ai.pleos.playground.tts/stt.constant.ErrorType 대응 (구현체에서 변환):
 * - PERMISSION_DENIED, UNAUTHENTICATED          → [PermissionDenied]
 * - UNAVAILABLE_LANGUAGE                         → [LanguageUnavailable]
 * - MIC_ERROR                                    → [Microphone]
 * - AUDIO_ERROR                                  → [AudioOutput]
 * - NETWORK_ERROR, SERVER_INTERNAL_ERROR         → [Network]
 * - TIMEOUT                                      → [Timeout]
 * - ON_DEVICE_ENGINE_ERROR, NOT_SUPPORTED_API,
 *   INVALID_REQUEST_ERROR, UNKNOWN               → [Engine]
 */
sealed interface SpeechError {
    /** 권한 없음 (Manifest의 TTS_SERVICE·STT_SERVICE·RECORD_AUDIO, 런타임 마이크 권한) */
    data object PermissionDenied : SpeechError

    /** 한국어(ko-KR)를 쓸 수 없음 */
    data object LanguageUnavailable : SpeechError

    /** 마이크 입력 실패 */
    data object Microphone : SpeechError

    /** 음성 출력 실패 */
    data object AudioOutput : SpeechError

    /** 네트워크·서버 오류 (ON_DEVICE 모드에서는 보통 발생하지 않음) */
    data object Network : SpeechError

    /** 응답 시간 초과 */
    data object Timeout : SpeechError

    /** 엔진 실패, 미지원 API, 알 수 없는 오류. [detail]은 로그용 원본 오류 이름 */
    data class Engine(val detail: String) : SpeechError
}
