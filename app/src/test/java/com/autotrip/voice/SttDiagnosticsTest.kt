package com.autotrip.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SttDiagnosticsTest {

    @Test
    fun `로그에는 숫자를 가리고 30자까지만 남긴다`() {
        assertEquals("\"회의\"(len=2)", maskForLog("회의"))
        assertEquals("\"###-####\"(len=8)", maskForLog("010-1234"))
        assertEquals("null", maskForLog(null))
        val long = "가".repeat(40)
        assertEquals("\"${"가".repeat(30)}…\"(len=40)", maskForLog(long))
    }
}
