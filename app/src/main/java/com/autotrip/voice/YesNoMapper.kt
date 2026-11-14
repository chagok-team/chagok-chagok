package com.autotrip.voice

/** [YesNoMapper.map]의 결과 */
sealed interface YesNoResult {
    data object Yes : YesNoResult
    data object No : YesNoResult
    /** 둘 다 아니거나 둘 다 나옴 → 한 번 더 묻거나 버튼으로 */
    data object Unclear : YesNoResult
}

/**
 * 거래처 제안("○○ 방문으로 기록할까요?")에 대한 "네·아니요" 답을 분류한다 (계약 v1.1 흐름용).
 *
 * - 부분 문자열로 찾으면 "네비", "어디"처럼 엉뚱한 낱말에 걸리므로 낱말 단위로 본다.
 * - 짧은 답(네, 응, 예)은 낱말이 정확히 같을 때만, 활용형(맞아요, 아니요)은 앞부분으로 본다.
 * - 긍정과 부정이 함께 나오면 불확실로 본다. ("네 아니요")
 *
 * Android 의존이 없는 순수 Kotlin 클래스다.
 */
class YesNoMapper {

    fun map(rawText: String?): YesNoResult {
        val words = rawText.orEmpty()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.lowercase() }
            .filter { it.isNotEmpty() }
        val yes = words.any(::isYes)
        val no = words.any(::isNo)
        return when {
            yes && !no -> YesNoResult.Yes
            no && !yes -> YesNoResult.No
            else -> YesNoResult.Unclear
        }
    }

    private fun isYes(word: String) = word in YES_EXACT || YES_PREFIX.any(word::startsWith)
    private fun isNo(word: String) = word in NO_EXACT || NO_PREFIX.any(word::startsWith)

    companion object {
        // "내": 음성 인식이 "네"를 "내"로 받아 적는 경우가 있어 짧은 대답 하나일 때는 긍정으로 본다
        private val YES_EXACT = setOf(
            "네", "넵", "넹", "네네", "내", "예", "예예", "응", "응응", "어", "웅", "ok", "오케이", "yes",
        )
        private val YES_PREFIX = listOf("맞아", "맞습니", "맞어", "좋아", "좋습니", "그래", "그렇", "기록해")
        private val NO_EXACT = setOf("노", "no", "놉")
        private val NO_PREFIX = listOf("아니", "아냐", "아뇨", "아닌", "싫어", "틀려", "틀렸", "다른")
    }
}
