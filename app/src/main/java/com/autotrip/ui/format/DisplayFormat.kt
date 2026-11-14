package com.autotrip.ui.format

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * 화면과 PDF에 공통으로 쓰는 표시 규칙 (INTERFACES.md 6장 "표시 규칙").
 *
 * - 거리: 미터(Long) → km, 소수 첫째 자리 반올림, 천 단위 쉼표 (예: 45231449 → "45,231.4")
 * - 비율: 0.0~1.0 → %, 소수 첫째 자리 반올림 (예: 0.8765 → "87.7%")
 * - 값이 없으면(null) 0이나 추정값으로 채우지 않는다. 화면은 "-", PDF는 빈칸.
 */
object DisplayFormat {

    const val DASH = "-"

    /** km 문자열. 값이 없으면 null (호출하는 쪽이 "-" 또는 빈칸을 고른다) */
    fun km(meters: Long?): String? {
        if (meters == null) return null
        val km = BigDecimal.valueOf(meters).divide(BigDecimal(1000))
        return kmFormat().format(km.setScale(1, RoundingMode.HALF_UP))
    }

    /** 화면용: 값이 없으면 "-" */
    fun kmOrDash(meters: Long?): String = km(meters) ?: DASH

    /** PDF용: 값이 없으면 빈칸 */
    fun kmOrBlank(meters: Long?): String = km(meters) ?: ""

    /** "87.7%". 값이 없으면 null */
    fun percent(ratio: Double?): String? {
        if (ratio == null || ratio.isNaN()) return null
        val value = BigDecimal.valueOf(ratio).multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_UP)
        return value.toPlainString() + "%"
    }

    fun percentOrDash(ratio: Double?): String = percent(ratio) ?: DASH

    /** "2026-11-12(목)" */
    fun dateWithDay(date: LocalDate, dayOfWeek: DayOfWeek = date.dayOfWeek): String =
        "$date(${koreanDay(dayOfWeek)})"

    /** 운행기록부 ③ 사용일자용 "11.09(월)". 연도는 상단 기간에 있으므로 생략한다. */
    fun monthDayWithDay(date: LocalDate, dayOfWeek: DayOfWeek = date.dayOfWeek): String =
        "%02d.%02d(%s)".format(date.monthValue, date.dayOfMonth, koreanDay(dayOfWeek))

    fun koreanDay(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
        DayOfWeek.MONDAY -> "월"
        DayOfWeek.TUESDAY -> "화"
        DayOfWeek.WEDNESDAY -> "수"
        DayOfWeek.THURSDAY -> "목"
        DayOfWeek.FRIDAY -> "금"
        DayOfWeek.SATURDAY -> "토"
        DayOfWeek.SUNDAY -> "일"
    }

    // DecimalFormat은 스레드 안전하지 않아서 호출마다 만든다.
    private fun kmFormat() = DecimalFormat("#,##0.0", DecimalFormatSymbols(Locale.KOREA)).apply {
        roundingMode = RoundingMode.HALF_UP
    }
}
