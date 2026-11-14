package com.autotrip.voice

/**
 * STT 진단 로그 공통 (A 요청, 2026-11-14: 실제 발화가 인식되지 않는 원인 조사용).
 * logcat 필터: adb logcat -s AutoTripSTT
 */
const val STT_LOG_TAG = "AutoTripSTT"

/** 인식 문자열을 로그에 남길 때: 숫자(전화번호 등)는 #으로 가리고 30자까지만 */
fun maskForLog(text: String?): String {
    if (text == null) return "null"
    val masked = text.replace(Regex("\\d"), "#")
    val cut = if (masked.length > 30) masked.take(30) + "…" else masked
    return "\"$cut\"(len=${text.length})"
}
