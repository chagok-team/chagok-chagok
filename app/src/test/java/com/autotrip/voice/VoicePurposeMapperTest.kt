package com.autotrip.voice

import com.autotrip.domain.model.TripPurpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePurposeMapperTest {

    private val mapper = VoicePurposeMapper()

    private fun matched(purpose: TripPurpose) = PurposeMappingResult.Matched(purpose)

    // ── 키워드 표 ────────────────────────────────────────

    @Test
    fun `기본 키워드 표는 모든 목적을 포함한다`() {
        assertEquals(TripPurpose.values().toSet(), VoicePurposeMapper.DEFAULT_KEYWORDS.keys)
    }

    @Test
    fun `키워드 표의 모든 키워드는 단독으로 자기 목적에 매칭된다`() {
        VoicePurposeMapper.DEFAULT_KEYWORDS.forEach { (purpose, words) ->
            words.forEach { word ->
                assertEquals("키워드 '$word'", matched(purpose), mapper.map(word))
            }
        }
    }

    // ── 실제 발화 예시 ───────────────────────────────────

    @Test
    fun `문장 속 키워드로 목적을 분류한다`() {
        assertEquals(matched(TripPurpose.CLIENT_VISIT), mapper.map("거래처 방문이요"))
        assertEquals(matched(TripPurpose.CLIENT_VISIT), mapper.map("납품하러 왔어"))
        assertEquals(matched(TripPurpose.SITE_VISIT), mapper.map("공장 설비 점검"))
        assertEquals(matched(TripPurpose.MEETING), mapper.map("본사 회의 참석"))
        assertEquals(matched(TripPurpose.PROMOTION), mapper.map("신제품 홍보 행사"))
        assertEquals(matched(TripPurpose.TRAINING), mapper.map("안전 교육 받으러"))
        assertEquals(matched(TripPurpose.PERSONAL), mapper.map("개인 용무"))
        // 화면·기획서 명칭 "사적 사용"으로 말해도 같은 목적
        assertEquals(matched(TripPurpose.PERSONAL), mapper.map("사적 사용"))
        assertEquals(matched(TripPurpose.COMMUTE), mapper.map("출퇴근"))
    }

    @Test
    fun `출근과 퇴근은 같은 목적이라 불확실이 아니다`() {
        assertEquals(matched(TripPurpose.COMMUTE), mapper.map("출근"))
        assertEquals(matched(TripPurpose.COMMUTE), mapper.map("퇴근길이야"))
        assertEquals(matched(TripPurpose.COMMUTE), mapper.map("출근했다가 퇴근"))
    }

    @Test
    fun `같은 목적의 키워드가 여러 개 나와도 한 목적으로 본다`() {
        assertEquals(matched(TripPurpose.SITE_VISIT), mapper.map("지점 현장 점검"))
    }

    // ── 정규화 ──────────────────────────────────────────

    @Test
    fun `공백과 문장부호를 무시한다`() {
        assertEquals(matched(TripPurpose.PERSONAL), mapper.map("개인용무"))
        assertEquals(matched(TripPurpose.MEETING), mapper.map("회 의 요."))
        assertEquals(matched(TripPurpose.TRAINING), mapper.map("세미나!!"))
        assertEquals(matched(TripPurpose.CLIENT_VISIT), mapper.map("  고객사,  방문  "))
    }

    @Test
    fun `영문 키워드는 대소문자를 무시한다`() {
        val custom = VoicePurposeMapper(mapOf(TripPurpose.MEETING to listOf("Meeting")))
        assertEquals(matched(TripPurpose.MEETING), custom.map("client MEETING"))
    }

    // ── 불확실 ──────────────────────────────────────────

    @Test
    fun `서로 다른 목적의 키워드가 함께 나오면 불확실이다`() {
        val result = mapper.map("거래처 미팅")
        assertEquals(
            PurposeMappingResult.Ambiguous(setOf(TripPurpose.CLIENT_VISIT, TripPurpose.MEETING)),
            result,
        )
    }

    @Test
    fun `부정 표현도 키워드가 둘이면 불확실로 넘긴다`() {
        val result = mapper.map("회의 아니고 거래처")
        assertTrue(result is PurposeMappingResult.Ambiguous)
    }

    // ── 매칭 실패 ───────────────────────────────────────

    @Test
    fun `빈 입력은 매칭 실패다`() {
        assertEquals(PurposeMappingResult.NoMatch, mapper.map(null))
        assertEquals(PurposeMappingResult.NoMatch, mapper.map(""))
        assertEquals(PurposeMappingResult.NoMatch, mapper.map("   "))
        assertEquals(PurposeMappingResult.NoMatch, mapper.map("...?"))
    }

    @Test
    fun `키워드가 없으면 매칭 실패다`() {
        assertEquals(PurposeMappingResult.NoMatch, mapper.map("음 잘 모르겠어"))
    }

    @Test
    fun `일반업무는 목적이 아니므로 매칭 실패다`() {
        // OTHER_BUSINESS 같은 포괄 목적은 두지 않는다 (INTERFACES.md 10장 #10)
        assertEquals(PurposeMappingResult.NoMatch, mapper.map("일반 업무"))
    }
}
