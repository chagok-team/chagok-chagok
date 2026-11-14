package com.autotrip.ui.export

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfPreviewTest {

    @Test
    fun `쪽 표시는 1부터 센다`() {
        assertEquals("1 / 3쪽", pageLabel(0, 3))
        assertEquals("3 / 3쪽", pageLabel(2, 3))
        assertEquals("", pageLabel(0, 0))
    }
}
