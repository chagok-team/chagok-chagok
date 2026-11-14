package com.autotrip.ui

import com.autotrip.domain.model.AutoTripError

/**
 * Repository 오류를 사용자에게 보여줄 문구로 바꾼다 (CLAUDE.md 3장 4번).
 * 화면 문구 규칙에 따라 끝에 마침표를 찍지 않는다.
 */
fun AutoTripError.toUserMessage(): String = when (this) {
    is AutoTripError.TripNotFound -> "운행 기록을 찾지 못했어요"
    is AutoTripError.PurposeAlreadyConfirmed -> "이미 목적이 입력된 운행이에요"
    is AutoTripError.PurposeNotConfirmed -> "아직 목적이 입력되지 않은 운행이에요"
    is AutoTripError.InvalidState -> "지금은 처리할 수 없어요"
    AutoTripError.ProfileIncomplete -> "설정에서 사업자·차량 정보를 먼저 입력해 주세요"
    is AutoTripError.Storage -> "기록을 저장하지 못했어요"
    is AutoTripError.Sdk -> "차량 정보를 읽지 못했어요"
}
