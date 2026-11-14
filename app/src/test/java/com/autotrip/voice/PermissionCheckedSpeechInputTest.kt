package com.autotrip.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionCheckedSpeechInputTest {

    @Test
    fun `권한이 없으면 STT를 열지 않고 권한 오류`() = runBlocking {
        val inner = FakeSpeechInput(listOf(ListenResult.Recognized("회의")))
        val input = PermissionCheckedSpeechInput({ false }, inner)
        assertEquals(SpeechError.PermissionDenied, input.prepare())
        assertEquals(ListenResult.Failed(SpeechError.PermissionDenied), input.listen {})
        assertEquals(0, inner.listenCount)
    }

    @Test
    fun `권한이 있으면 그대로 넘긴다`() = runBlocking {
        val inner = FakeSpeechInput(listOf(ListenResult.Recognized("회의")))
        var granted = true
        val input = PermissionCheckedSpeechInput({ granted }, inner)
        assertNull(input.prepare())
        assertEquals(ListenResult.Recognized("회의"), input.listen {})
        granted = false   // 나중에 권한을 끈 경우
        assertEquals(SpeechError.PermissionDenied, input.prepare())
    }
}
