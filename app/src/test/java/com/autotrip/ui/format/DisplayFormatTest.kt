package com.autotrip.ui.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class DisplayFormatTest {

    @Test
    fun `미터를 km 소수 첫째 자리로 반올림한다`() {
        assertEquals("12.3", DisplayFormat.km(12_340))
        assertEquals("12.4", DisplayFormat.km(12_350))   // 반올림(HALF_UP)
        assertEquals("12.4", DisplayFormat.km(12_449))
        assertEquals("0.0", DisplayFormat.km(0))
        assertEquals("0.1", DisplayFormat.km(50))
    }

    @Test
    fun `큰 값은 천 단위 쉼표를 넣는다`() {
        assertEquals("45,231.4", DisplayFormat.km(45_231_449))
        assertEquals("1,000.0", DisplayFormat.km(1_000_000))
    }

    @Test
    fun `값이 없으면 0으로 채우지 않는다`() {
        assertNull(DisplayFormat.km(null))
        assertEquals("-", DisplayFormat.kmOrDash(null))
        assertEquals("", DisplayFormat.kmOrBlank(null))
    }

    @Test
    fun `비율을 퍼센트 소수 첫째 자리로 반올림한다`() {
        assertEquals("87.7%", DisplayFormat.percent(0.8765))
        assertEquals("100.0%", DisplayFormat.percent(1.0))
        assertEquals("0.0%", DisplayFormat.percent(0.0))
        assertEquals("33.3%", DisplayFormat.percent(1.0 / 3))
        assertEquals("0.1%", DisplayFormat.percent(0.0005))   // 0.05% → 0.1% (HALF_UP)
    }

    @Test
    fun `비율이 없으면 대시`() {
        assertNull(DisplayFormat.percent(null))
        assertEquals("-", DisplayFormat.percentOrDash(null))
    }

    @Test
    fun `날짜에 한글 요일을 붙인다`() {
        assertEquals("2026-11-12(목)", DisplayFormat.dateWithDay(LocalDate.of(2026, 11, 12)))
        assertEquals("2026-11-15(일)", DisplayFormat.dateWithDay(LocalDate.of(2026, 11, 15), DayOfWeek.SUNDAY))
    }

    @Test
    fun `운행기록부 사용일자는 월·일과 요일만`() {
        assertEquals("11.09(월)", DisplayFormat.monthDayWithDay(LocalDate.of(2026, 11, 9)))
        assertEquals("01.01(목)", DisplayFormat.monthDayWithDay(LocalDate.of(2026, 1, 1)))
    }
}
