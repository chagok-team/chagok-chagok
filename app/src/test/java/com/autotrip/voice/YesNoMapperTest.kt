package com.autotrip.voice

import com.autotrip.domain.model.TripPurpose
import org.junit.Assert.assertEquals
import org.junit.Test

class YesNoMapperTest {

    private val mapper = YesNoMapper()

    @Test
    fun `긍정 답`() {
        listOf("네", "네.", "넵", "예", "응", "맞아요", "맞습니다", "네 맞아요", "좋아요", "그래", "네 기록해 주세요", "OK", "내", "내.", "응응", "예예")
            .forEach { assertEquals(it, YesNoResult.Yes, mapper.map(it)) }
    }

    @Test
    fun `부정 답`() {
        listOf("아니요", "아니", "아뇨", "아냐", "아닌데요", "아니요 회의예요", "다른 거요", "틀렸어")
            .forEach { assertEquals(it, YesNoResult.No, mapper.map(it)) }
    }

    @Test
    fun `낱말 안에 섞인 글자로는 판단하지 않는다`() {
        listOf("네비게이션", "어디로 가요", "예정", "응급실", "내일", "내비")
            .forEach { assertEquals(it, YesNoResult.Unclear, mapper.map(it)) }
    }

    @Test
    fun `둘 다 나오거나 아무것도 없으면 불확실`() {
        listOf("네 아니요", "", "   ", "거래처 방문")
            .forEach { assertEquals("[$it]", YesNoResult.Unclear, mapper.map(it)) }
        assertEquals(YesNoResult.Unclear, mapper.map(null))
    }

    @Test
    fun `v1_1 안내 문구`() {
        assertEquals("현대오토에버 판교 방문으로 기록할까요?", VoicePrompts.suggestClient("현대오토에버 판교"))
        assertEquals("거래처 방문으로 기록했어요.", VoicePrompts.recorded(TripPurpose.CLIENT_VISIT))
        assertEquals("회의로 기록했어요.", VoicePrompts.recorded(TripPurpose.MEETING))
        assertEquals("출퇴근으로 기록했어요.", VoicePrompts.recorded(TripPurpose.COMMUTE))
        assertEquals("지난번처럼 거래처 방문으로 기록할까요?", VoicePrompts.suggestPrevious(TripPurpose.CLIENT_VISIT))
        assertEquals("지난번처럼 회의로 기록할까요?", VoicePrompts.suggestPrevious(TripPurpose.MEETING))
    }
}
