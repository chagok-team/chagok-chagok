package com.autotrip.export

import com.autotrip.domain.model.LogbookRow
import com.autotrip.domain.model.MileageSummary
import com.autotrip.domain.model.RowFlag
import com.autotrip.domain.model.TaxPeriod
import com.autotrip.domain.model.TripPurpose
import com.autotrip.domain.model.TripSource
import com.autotrip.export.LogbookPdfLayout.FIRST_PAGE_BODY_HEIGHT
import com.autotrip.export.LogbookPdfLayout.OTHER_PAGE_BODY_HEIGHT
import com.autotrip.export.LogbookPdfLayout.ROW_HEIGHT
import com.autotrip.export.LogbookPdfLayout.ROW_HEIGHT_TWO_LINES
import com.autotrip.export.LogbookPdfLayout.SUMMARY_RESERVED_HEIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class LogbookPdfLayoutTest {

    private val period = TaxPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

    private fun summary(ratio: Double? = 0.875, pending: Int = 0, incomplete: Int = 0) = MileageSummary(
        period = period,
        totalMeters = 1_234_560,
        commuteMeters = 200_000,
        generalBusinessMeters = 880_000,
        businessMeters = 1_080_000,
        nonBusinessMeters = 154_560,
        unclassifiedMeters = 0,
        businessUseRatio = ratio,
        incompleteTripCount = incomplete,
        pendingPurposeTripCount = pending,
    )

    // ── 열 구성 ─────────────────────────────────────────

    @Test
    fun `열 너비 합은 여백을 뺀 페이지 폭과 같다`() {
        val total = LogbookPdfLayout.Column.values().sumOf { it.width.toDouble() }
        assertEquals(LogbookPdfLayout.PAGE_WIDTH - 2 * LogbookPdfLayout.MARGIN.toDouble(), total, 0.01)
    }

    @Test
    fun `머리글 칸은 세 줄 격자의 모든 자리를 겹치지 않고 한 번씩 채운다`() {
        val rows = LogbookPdfLayout.HEADER_ROW_HEIGHTS.size
        val covered = mutableMapOf<Pair<Int, LogbookPdfLayout.Column>, String>()
        LogbookPdfLayout.HEADER_CELLS.forEach { cell ->
            for (r in cell.row until cell.row + cell.rowSpan) {
                for (c in LogbookPdfLayout.Column.values().filter { it.ordinal in cell.first.ordinal..cell.last.ordinal }) {
                    val previous = covered.put(r to c, cell.label)
                    assertNull("겹침: ($r, $c) ${cell.label} / $previous", previous)
                }
            }
        }
        assertEquals(rows * LogbookPdfLayout.Column.values().size, covered.size)
    }

    @Test
    fun `머리글은 원본 서식의 묶음 구조를 따른다`() {
        val byLabel = LogbookPdfLayout.HEADER_CELLS.associateBy { it.label }
        val operation = byLabel.getValue("운 행   내 역")
        assertEquals(LogbookPdfLayout.Column.ODOMETER_BEFORE to LogbookPdfLayout.Column.REMARK, operation.first to operation.last)
        val business = byLabel.getValue("업무용 사용거리(㎞)")
        assertEquals(LogbookPdfLayout.Column.COMMUTE to LogbookPdfLayout.Column.GENERAL_BUSINESS, business.first to business.last)
        assertEquals(3, byLabel.getValue("③사용\n일자\n(요일)").rowSpan)
    }

    // ── 서식 문구 템플릿 ────────────────────────────────

    @Test
    fun `사업자 유형에 따라 서식 문구가 바뀐다`() {
        val sole = LogbookFormTemplate.of(com.autotrip.domain.model.BusinessType.SOLE_PROPRIETOR)
        assertEquals("과세기간", sole.periodLabel)
        assertEquals("상호명", sole.nameLabel)
        assertEquals("⑪과세기간 총주행 거리(㎞)", sole.totalLabel)
        assertTrue(sole.formTag.endsWith("<2022.4.1. 개정>"))

        val corp = LogbookFormTemplate.of(com.autotrip.domain.model.BusinessType.CORPORATION)
        assertEquals("사업연도", corp.periodLabel)
        assertEquals("법인명", corp.nameLabel)
        assertEquals("⑫사업연도 업무용 사용거리(㎞)", corp.businessLabel)
    }

    // ── 행 문자열 ───────────────────────────────────────

    @Test
    fun `정상 운행 행은 서식 순서대로 채운다`() {
        val row = LogbookRow(
            tripId = 1, date = LocalDate.of(2026, 11, 12), dayOfWeek = DayOfWeek.THURSDAY,
            userDepartment = "영업팀", userName = "김현대",
            odometerBeforeMeters = 45_231_449, odometerAfterMeters = 45_263_749, drivenMeters = 32_300,
            commuteMeters = null, generalBusinessMeters = 32_300,
            remark = "거래처 방문 · 현대오토에버", purpose = TripPurpose.CLIENT_VISIT,
            source = TripSource.AUTO, flags = emptySet(),
        )
        assertEquals(
            listOf("11.12(목)", "영업팀", "김현대", "45,231.4", "45,263.7", "32.3", "", "32.3", "거래처 방문 · 현대오토에버"),
            LogbookPdfLayout.cells(row),
        )
    }

    @Test
    fun `계기판 실패 행은 거리 칸을 빈칸으로 둔다`() {
        val row = LogbookRow(
            tripId = 2, date = LocalDate.of(2026, 11, 13), dayOfWeek = DayOfWeek.FRIDAY,
            userDepartment = null, userName = null,
            odometerBeforeMeters = null, odometerAfterMeters = 45_300_000, drivenMeters = null,
            commuteMeters = null, generalBusinessMeters = null,
            remark = "[목적 미입력] [계기판 수집 실패]", purpose = null,
            source = TripSource.MANUAL, flags = setOf(RowFlag.ODOMETER_MISSING, RowFlag.PURPOSE_PENDING),
        )
        val cells = LogbookPdfLayout.cells(row)
        assertEquals(
            listOf("11.13(금)", "", "", "", "45,300.0", "", "", "", "[목적 미입력] [계기판 수집 실패]"),
            cells,
        )
        assertTrue("0으로 채우지 않는다", cells.none { it == "0.0" })
    }

    @Test
    fun `합계 칸과 비율이 없을 때`() {
        assertEquals(Triple("1,234.6", "1,080.0", "87.5%"), LogbookPdfLayout.summaryCells(summary()))
        assertEquals("", LogbookPdfLayout.summaryCells(summary(ratio = null)).third)
    }

    @Test
    fun `미입력·계기판 실패 건수 안내`() {
        assertNull(LogbookPdfLayout.warningLine(summary()))
        assertEquals(
            "※ 목적 미입력 운행 2건, 계기판 수집 실패 운행 1건(총주행거리에서 제외)이 포함되어 있습니다.",
            LogbookPdfLayout.warningLine(summary(pending = 2, incomplete = 1)),
        )
        assertEquals("※ 목적 미입력 운행 3건이 포함되어 있습니다.", LogbookPdfLayout.warningLine(summary(pending = 3)))
    }

    // ── 페이지 나누기 ───────────────────────────────────

    @Test
    fun `운행이 없어도 한 페이지를 만든다`() {
        assertEquals(listOf(IntRange.EMPTY), LogbookPdfLayout.paginate(0))
    }

    private val firstPageRows = (FIRST_PAGE_BODY_HEIGHT / ROW_HEIGHT).toInt()
    private val reservedRows = (SUMMARY_RESERVED_HEIGHT / ROW_HEIGHT).toInt()

    @Test
    fun `합계 공간이 남으면 한 페이지에 끝낸다`() {
        val rows = firstPageRows - reservedRows
        assertEquals(listOf(0 until rows), LogbookPdfLayout.paginate(rows))
    }

    @Test
    fun `합계 공간이 모자라면 합계용 페이지를 추가한다`() {
        val rows = firstPageRows - reservedRows + 1
        assertEquals(listOf(0 until rows, IntRange.EMPTY), LogbookPdfLayout.paginate(rows))
    }

    @Test
    fun `여러 페이지로 나누고 모든 행을 한 번씩만 담는다`() {
        val rowCount = 100
        val pages = LogbookPdfLayout.paginate(rowCount)
        assertEquals((0 until rowCount).toList(), pages.flatMap { it.toList() })
        assertEquals(firstPageRows, pages.first().count())
    }

    @Test
    fun `두 줄 행이 섞이면 높이 합으로 나눈다`() {
        // 2줄 행 높이 28 × 12 = 336 > 첫 페이지 288 → 첫 페이지에는 10행(280)만 들어간다
        val heights = List(12) { ROW_HEIGHT_TWO_LINES }
        val pages = LogbookPdfLayout.paginate(heights)
        assertEquals(0 until 10, pages.first())
        assertEquals((0 until 12).toList(), pages.flatMap { it.toList() })
        pages.forEachIndexed { i, range ->
            val capacity = if (i == 0) FIRST_PAGE_BODY_HEIGHT else OTHER_PAGE_BODY_HEIGHT
            assertTrue("페이지 ${i + 1} 높이 초과", range.sumOf { heights[it].toDouble() } <= capacity)
        }
        val lastUsed = pages.last().sumOf { heights[it].toDouble() }
        val lastCapacity = if (pages.size == 1) FIRST_PAGE_BODY_HEIGHT else OTHER_PAGE_BODY_HEIGHT
        assertTrue("마지막 페이지에 합계 공간", lastUsed <= lastCapacity - SUMMARY_RESERVED_HEIGHT)
    }

    // ── 비고 줄 나누기 (글자당 10pt, 한 줄 최대 100pt = 10글자) ──

    private val tenPerChar: (String) -> Float = { it.length * 10f }
    private fun wrap(text: String) = LogbookPdfLayout.wrapRemark(text, 100f, tenPerChar)

    @Test
    fun `짧은 비고는 한 줄`() {
        assertEquals(listOf("거래처 방문 · 대리점"), LogbookPdfLayout.wrapRemark("거래처 방문 · 대리점", 200f, tenPerChar))
        assertEquals(LogbookPdfLayout.ROW_HEIGHT, LogbookPdfLayout.rowHeight(1))
    }

    @Test
    fun `긴 비고는 공백에서 끊어 두 줄로 나눈다`() {
        assertEquals(listOf("거래처 방문 ·", "현대오토에버 판교"), wrap("거래처 방문 · 현대오토에버 판교"))
        assertEquals(LogbookPdfLayout.ROW_HEIGHT_TWO_LINES, LogbookPdfLayout.rowHeight(2))
    }

    @Test
    fun `두 줄로도 넘치면 둘째 줄 끝을 말줄임한다`() {
        val lines = wrap("거래처 방문 · 현대오토에버 판교 사옥 · 신규 계약 미팅 후 납품 일정 협의 [수정됨]")
        assertEquals(2, lines.size)
        assertTrue(lines[1].endsWith(LogbookPdfLayout.ELLIPSIS))
        assertTrue(lines.all { tenPerChar(it) <= 100f })
    }

    @Test
    fun `공백이 없으면 글자 단위로 끊는다`() {
        assertEquals(listOf("가나다라마바사아자차", "카타파하"), wrap("가나다라마바사아자차카타파하"))
    }

    @Test
    fun `빈 비고는 줄이 없다`() {
        assertEquals(emptyList<String>(), wrap("   "))
    }
}
