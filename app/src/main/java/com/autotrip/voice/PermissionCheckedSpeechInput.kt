package com.autotrip.voice

/**
 * 마이크 런타임 권한(RECORD_AUDIO)이 없으면 STT를 시작하지 않고 [SpeechError.PermissionDenied]를 돌려준다.
 * 질문 흐름은 이 오류를 받으면 음성 대신 정차 중 버튼 선택으로 넘어간다.
 * 권한은 매번 확인한다(사용자가 설정에서 나중에 허용·해제할 수 있으므로).
 */
class PermissionCheckedSpeechInput(
    private val hasPermission: () -> Boolean,
    private val delegate: SpeechInput,
) : SpeechInput {

    override suspend fun prepare(): SpeechError? =
        if (!hasPermission()) SpeechError.PermissionDenied else delegate.prepare()

    override suspend fun listen(onPartial: (String) -> Unit, onReady: () -> Unit): ListenResult =
        if (!hasPermission()) ListenResult.Failed(SpeechError.PermissionDenied) else delegate.listen(onPartial, onReady)

    override fun stop() = delegate.stop()
    override fun release() = delegate.release()
}
