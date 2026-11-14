package com.autotrip.ui.purpose

import com.autotrip.domain.model.TripPurpose
import com.autotrip.voice.ButtonReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalPromptContentTest {

    @Test
    fun `후보 목적을 앞에 두고 나머지는 원래 순서를 지킨다`() {
        val ordered = orderedPurposes(setOf(TripPurpose.MEETING, TripPurpose.CLIENT_VISIT))
        assertEquals(listOf(TripPurpose.CLIENT_VISIT, TripPurpose.MEETING), ordered.take(2))
        assertEquals(TripPurpose.values().size, ordered.size)
        assertEquals(
            TripPurpose.values().filterNot { it == TripPurpose.MEETING || it == TripPurpose.CLIENT_VISIT },
            ordered.drop(2),
        )
    }

    @Test
    fun `후보가 없으면 원래 순서`() {
        assertEquals(TripPurpose.values().toList(), orderedPurposes(emptySet()))
    }

    @Test
    fun `모든 이유에 안내 문구가 있다`() {
        ButtonReason.values().forEach { assertTrue(reasonText(it).isNotBlank()) }
    }

    @Test
    fun `화면 문구는 마침표로 끝나지 않는다`() {
        val texts = listOf(
            PromptCopy.ASKING_AGAIN, PromptCopy.LISTENING, PromptCopy.LISTENING_HINT, PromptCopy.SELECT_TITLE, PromptCopy.SECTION,
            PromptCopy.SELECT_WHILE_DRIVING, PromptCopy.LATER, PromptCopy.SAVE_FAILED,
            PromptCopy.SAVE_FAILED_WHILE_DRIVING, PromptCopy.BY_VOICE, PromptCopy.BY_BUTTON,
        ) + ButtonReason.values().map(::reasonText) +
            TripPurpose.values().flatMap { listOf(PromptCopy.confirming(it), PromptCopy.completed(it)) }
        texts.forEach { assertTrue("'$it'", !it.endsWith(".") && !it.endsWith("!")) }
    }

    @Test
    fun `확인 문구는 받침에 맞는 조사를 쓴다`() {
        assertEquals("거래처 방문으로 기록할게요", PromptCopy.confirming(TripPurpose.CLIENT_VISIT))
        assertEquals("회의로 기록할게요", PromptCopy.confirming(TripPurpose.MEETING))
    }
}
