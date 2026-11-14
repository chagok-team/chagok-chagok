package com.autotrip.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 조용한 모드 켜짐 여부 (동승자가 있을 때 음성 질문·안내를 끈다).
 *
 * 앱 화면 설정이라 공통 계약(domain)에 두지 않고 B 쪽에서 기기에 저장한다.
 * 저장 방법은 [persist]로 받아서 이 클래스는 Android 의존 없이 테스트할 수 있다.
 */
class QuietModeSetting(initial: Boolean, private val persist: (Boolean) -> Unit = {}) {
    private val _enabled = MutableStateFlow(initial)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(value: Boolean) {
        if (_enabled.value == value) return
        _enabled.value = value
        persist(value)
    }
}
