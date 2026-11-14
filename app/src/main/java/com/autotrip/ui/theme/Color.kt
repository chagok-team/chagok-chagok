package com.autotrip.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * 현대자동차 CI 전용색 (hyundai.com/kr/ko/info/ci/ci-color, 2026-11-13 확인).
 * 색만 맞추고 로고·전용 글꼴(Hyundai Sans)은 쓰지 않는다.
 */
object HyundaiCi {
    /** 주 강조색: 주요 버튼, 선택, 핵심 숫자 */
    val Blue = Color(0xFF002C5F)
    /** 살아 있는 상태에만: 주행 중, 듣는 중, 진행 막대 */
    val ActiveBlue = Color(0xFF00AAD2)
    /** 야간 강조색 */
    val SkyBlue = Color(0xFFAACAE6)
    /** 구분선·막대 바탕·작은 표시 */
    val Sand = Color(0xFFE4DCD3)
    /** 주간 화면 바탕 */
    val LightSand = Color(0xFFF6F3F2)
    /** 오류 */
    val ActiveRed = Color(0xFFE63312)
}

// 글자·야간 표면: 회색 한 계열 (값 출처: unslop references/tokens.md, CC BY 4.0, Damir Sokolovsky)
object Graphite {
    // 주간 글자
    val LightTextPrimary = Color(0xFF171717)
    val LightTextSecondary = Color(0xFF525252)
    val LightTextMuted = Color(0xFF737373)

    // 야간
    val DarkBackground = Color(0xFF141415)
    val DarkCard = Color(0xFF1E1E20)
    val DarkRaised = Color(0xFF2A2A2D)
    val DarkTextPrimary = Color(0xFFEDEDED)
    val DarkTextSecondary = Color(0xFFA1A1A6)
    val DarkTextMuted = Color(0xFF85858B)
}

/** 야간 오류색 (Active Red는 어두운 바탕에서 너무 진해 조금 밝힌다) */
val NegativeDark = Color(0xFFFF6B4F)
