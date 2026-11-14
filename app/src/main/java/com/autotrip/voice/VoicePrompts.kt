package com.autotrip.voice

import com.autotrip.domain.model.TripPurpose

/** 도착 질문 흐름에서 TTS로 읽는 문장 */
object VoicePrompts {
    const val QUESTION = "이번 운행 목적은 무엇인가요?"
    const val RETRY_NOT_UNDERSTOOD = "잘 알아듣지 못했어요. 거래처 방문, 회의, 출퇴근처럼 말씀해 주세요."
    const val RETRY_AMBIGUOUS = "목적을 하나만 말씀해 주세요."
    const val FALLBACK_TO_BUTTON = "정차한 뒤 화면에서 운행 목적을 선택해 주세요."

    /** 예: "거래처 방문으로 기록할게요." / "회의로 기록할게요." */
    fun confirm(purpose: TripPurpose): String = "${withEuro(purpose.spokenLabel)} 기록할게요."

    // ── 계약 v1.1 흐름용 (내비 목적지 설정 시 질문, 거래처 제안, 도착 확인) ──

    /** 목적지가 등록된 거래처일 때: "현대오토에버 판교 방문으로 기록할까요?" */
    fun suggestClient(clientName: String): String = "$clientName 방문으로 기록할까요?"

    const val RETRY_YES_NO = "네 또는 아니요로 말씀해 주세요."

    /** 같은 목적지로 간 적이 있을 때: "지난번처럼 회의로 기록할까요?" */
    fun suggestPrevious(purpose: TripPurpose): String = "지난번처럼 ${withEuro(purpose.spokenLabel)} 기록할까요?"

    /** 도착했을 때 확인만 한다: "거래처 방문으로 기록했어요." */
    fun recorded(purpose: TripPurpose): String = "${withEuro(purpose.spokenLabel)} 기록했어요."

    /** 받침이 있으면(ㄹ 제외) "으로", 없으면 "로"를 붙인다. */
    internal fun withEuro(word: String): String {
        val last = word.lastOrNull() ?: return word
        if (last !in '가'..'힣') return word + "로"
        val jongseong = (last - '가') % 28
        return if (jongseong == 0 || jongseong == RIEUL_JONGSEONG) word + "로" else word + "으로"
    }

    private const val RIEUL_JONGSEONG = 8
}

/**
 * 음성 안내와 화면(버튼·목록·상세)에 쓰는 목적 이름 — 제출 기획서의 7가지 분류 명칭과 같게.
 * (2026-11-14 팀 결정: PERSONAL은 "사적 사용". enum·저장 값은 그대로라 기존 데이터와 호환된다.
 *  PDF ⑩ 비고 문구는 A의 LogbookRow.remark가 만든다.)
 */
val TripPurpose.spokenLabel: String
    get() = when (this) {
        TripPurpose.CLIENT_VISIT -> "거래처 방문"
        TripPurpose.SITE_VISIT -> "사업장 방문"
        TripPurpose.MEETING -> "회의"
        TripPurpose.PROMOTION -> "판촉"
        TripPurpose.TRAINING -> "교육"
        TripPurpose.COMMUTE -> "출퇴근"
        TripPurpose.PERSONAL -> "사적 사용"
    }
