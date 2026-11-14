package com.autotrip.voice

import com.autotrip.domain.model.TripPurpose
import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePromptsTest {

    @Test
    fun `받침 여부에 따라 으로와 로를 고른다`() {
        assertEquals("방문으로", VoicePrompts.withEuro("방문"))   // ㄴ 받침
        assertEquals("회의로", VoicePrompts.withEuro("회의"))     // 받침 없음
        assertEquals("서울로", VoicePrompts.withEuro("서울"))     // ㄹ 받침은 "로"
        assertEquals("ABC로", VoicePrompts.withEuro("ABC"))
    }

    @Test
    fun `모든 목적의 확인 안내 문장`() {
        val expected = mapOf(
            TripPurpose.CLIENT_VISIT to "거래처 방문으로 기록할게요.",
            TripPurpose.SITE_VISIT to "사업장 방문으로 기록할게요.",
            TripPurpose.MEETING to "회의로 기록할게요.",
            TripPurpose.PROMOTION to "판촉으로 기록할게요.",
            TripPurpose.TRAINING to "교육으로 기록할게요.",
            TripPurpose.COMMUTE to "출퇴근으로 기록할게요.",
            TripPurpose.PERSONAL to "사적 사용으로 기록할게요.",
        )
        TripPurpose.values().forEach { assertEquals(expected[it], VoicePrompts.confirm(it)) }
    }
}
