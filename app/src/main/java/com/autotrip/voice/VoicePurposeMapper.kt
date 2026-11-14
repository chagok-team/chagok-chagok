package com.autotrip.voice

import com.autotrip.domain.model.TripPurpose

/** [VoicePurposeMapper.map]의 분류 결과 */
sealed interface PurposeMappingResult {
    /** 목적 하나로 분류됨 → TTS로 확인 안내 후 confirmPurpose(…, InputMethod.VOICE, …) */
    data class Matched(val purpose: TripPurpose) : PurposeMappingResult

    /** 두 개 이상의 목적 키워드가 함께 나옴 → 불확실로 보고 재질문 또는 버튼 선택 */
    data class Ambiguous(val candidates: Set<TripPurpose>) : PurposeMappingResult

    /** 아는 키워드가 없음(인식 실패 포함) → 재질문 또는 버튼 선택 */
    data object NoMatch : PurposeMappingResult
}

/**
 * STT 인식 원문을 운행 목적으로 분류한다.
 *
 * - 원문에 키워드가 포함돼 있으면 해당 목적으로 본다.
 * - 공백·문장부호와 대소문자는 무시한다. ("개인 용무" == "개인용무", "회의요." == "회의요")
 * - 서로 다른 목적의 키워드가 함께 나오면 [PurposeMappingResult.Ambiguous]로 돌려준다.
 *
 * Android 의존이 없는 순수 Kotlin 클래스다. 음성 원문은 호출하는 쪽이 voiceRawText로 따로 보존한다.
 */
class VoicePurposeMapper(
    keywords: Map<TripPurpose, List<String>> = DEFAULT_KEYWORDS,
) {
    private val normalizedKeywords: Map<TripPurpose, List<String>> =
        keywords.mapValues { (_, words) -> words.map(::normalize).filter { it.isNotEmpty() } }

    fun map(rawText: String?): PurposeMappingResult {
        val text = normalize(rawText.orEmpty())
        if (text.isEmpty()) return PurposeMappingResult.NoMatch

        val hits = normalizedKeywords
            .filterValues { words -> words.any { text.contains(it) } }
            .keys

        return when (hits.size) {
            0 -> PurposeMappingResult.NoMatch
            1 -> PurposeMappingResult.Matched(hits.single())
            else -> PurposeMappingResult.Ambiguous(hits)
        }
    }

    companion object {
        /** CLAUDE.md 4장 「VoicePurposeMapper 키워드 (초기 버전)」 */
        val DEFAULT_KEYWORDS: Map<TripPurpose, List<String>> = mapOf(
            TripPurpose.CLIENT_VISIT to listOf("거래처", "고객사", "대리점", "납품"),
            TripPurpose.SITE_VISIT to listOf("현장", "공장", "지점", "사업장", "점검"),
            TripPurpose.MEETING to listOf("회의", "미팅"),
            TripPurpose.PROMOTION to listOf("판촉", "영업", "홍보"),
            TripPurpose.TRAINING to listOf("교육", "세미나", "연수"),
            TripPurpose.COMMUTE to listOf("출근", "퇴근"),
            TripPurpose.PERSONAL to listOf("개인", "사적", "개인 용무"),
        )

        /** 글자와 숫자만 남기고 소문자로 바꾼다. 공백·문장부호는 지운다. */
        internal fun normalize(text: String): String =
            text.filter { it.isLetterOrDigit() }.lowercase()
    }
}
