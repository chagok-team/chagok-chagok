package com.autotrip.export

import com.autotrip.domain.model.LogbookRow
import com.autotrip.domain.model.MileageSummary
import com.autotrip.ui.format.DisplayFormat

/**
 * 운행기록부 PDF의 배치 규칙 (Android 의존 없음, 단위 테스트 대상).
 *
 * 국세청 고시 「업무용승용차 운행기록부」 별지 서식(<2022.4.1. 개정>)의 배치를 따른다. A4 가로(842 x 595pt).
 * - 맨 위: 서식 표시 → 상단 줄(왼쪽 기간, 가운데 제목, 오른쪽 상호·사업자등록번호). 상단 줄은 페이지마다 반복
 * - 첫 페이지: 1. 기본정보(①②, 페이지 왼쪽 절반)
 * - 2. 업무용 사용비율 계산: 세 줄 머리글을 페이지마다 반복, 마지막 페이지 표 맨 아래에 ⑪⑫⑬ 합계 두 줄
 *
 * ⑩ 비고는 최대 2줄까지 나눠 쓴다. 2줄짜리 행만 높이가 커지고, 그래도 넘치면 2줄째 끝을 "…"로 줄인다.
 */
object LogbookPdfLayout {
    const val PAGE_WIDTH = 842
    const val PAGE_HEIGHT = 595
    const val MARGIN = 36f
    const val CELL_PADDING = 4f
    const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2

    /** 비고가 1줄인 행 / 2줄인 행의 높이 */
    const val ROW_HEIGHT = 18f
    const val ROW_HEIGHT_TWO_LINES = 28f
    const val MAX_REMARK_LINES = 2
    const val ELLIPSIS = "…"

    /** 표 본문에 쓸 수 있는 높이(pt) */
    const val FIRST_PAGE_BODY_HEIGHT = 16 * ROW_HEIGHT    // 기본정보가 있는 첫 페이지
    const val OTHER_PAGE_BODY_HEIGHT = 20 * ROW_HEIGHT
    /** 마지막 페이지에서 ⑪⑫⑬ 합계 두 줄과 안내 문구를 위해 비워 둘 높이 */
    const val SUMMARY_RESERVED_HEIGHT = 5 * ROW_HEIGHT

    const val NOTICE = "본 운행기록부는 국세청 고시 서식 양식에 맞춰 작성되었습니다. 세무 판단은 전문가 확인이 필요합니다."
    const val DEMO_WATERMARK = "시연용"

    /** 표 열. 너비 합 = 842 - 36×2 = 770. ⑩ 비고는 운행 목적·행선지를 적으므로 원본보다 넓게 잡았다. */
    enum class Column(val width: Float, val alignRight: Boolean) {
        DATE(60f, false),
        DEPARTMENT(60f, false),
        NAME(55f, false),
        ODOMETER_BEFORE(85f, true),
        ODOMETER_AFTER(85f, true),
        DRIVEN(65f, true),
        COMMUTE(72f, true),
        GENERAL_BUSINESS(72f, true),
        REMARK(216f, false),
    }

    /** 비고 글자가 들어갈 수 있는 폭 */
    val REMARK_TEXT_WIDTH: Float = Column.REMARK.width - CELL_PADDING * 2

    fun columnX(column: Column): Float =
        MARGIN + Column.values().takeWhile { it != column }.sumOf { it.width.toDouble() }.toFloat()

    /** [first]부터 [last]까지 열의 너비 합 */
    fun spanWidth(first: Column, last: Column): Float =
        Column.values().filter { it.ordinal in first.ordinal..last.ordinal }.sumOf { it.width.toDouble() }.toFloat()

    // ── 표 머리글 (원본 서식의 병합 구조) ─────────────────────

    /** 머리글 칸. [row]는 0~2, [rowSpan]은 아래로 합치는 줄 수. 줄바꿈은 "\n" */
    data class HeaderCell(val label: String, val first: Column, val last: Column, val row: Int, val rowSpan: Int)

    val HEADER_ROW_HEIGHTS = listOf(18f, 22f, 18f)

    val HEADER_CELLS: List<HeaderCell> = listOf(
        HeaderCell("③사용\n일자\n(요일)", Column.DATE, Column.DATE, row = 0, rowSpan = 3),
        HeaderCell("④사용자", Column.DEPARTMENT, Column.NAME, row = 0, rowSpan = 1),
        HeaderCell("운 행   내 역", Column.ODOMETER_BEFORE, Column.REMARK, row = 0, rowSpan = 1),
        HeaderCell("부서", Column.DEPARTMENT, Column.DEPARTMENT, row = 1, rowSpan = 2),
        HeaderCell("성명", Column.NAME, Column.NAME, row = 1, rowSpan = 2),
        HeaderCell("⑤주행 전\n계기판의 거리(㎞)", Column.ODOMETER_BEFORE, Column.ODOMETER_BEFORE, row = 1, rowSpan = 2),
        HeaderCell("⑥주행 후\n계기판의 거리(㎞)", Column.ODOMETER_AFTER, Column.ODOMETER_AFTER, row = 1, rowSpan = 2),
        HeaderCell("⑦주행거리(㎞)", Column.DRIVEN, Column.DRIVEN, row = 1, rowSpan = 2),
        HeaderCell("업무용 사용거리(㎞)", Column.COMMUTE, Column.GENERAL_BUSINESS, row = 1, rowSpan = 1),
        HeaderCell("⑧출·퇴근용(㎞)", Column.COMMUTE, Column.COMMUTE, row = 2, rowSpan = 1),
        HeaderCell("⑨일반 업무용(㎞)", Column.GENERAL_BUSINESS, Column.GENERAL_BUSINESS, row = 2, rowSpan = 1),
        HeaderCell("⑩비 고", Column.REMARK, Column.REMARK, row = 1, rowSpan = 2),
    )

    /** ⑪⑫⑬ 합계: 첫 줄 머리글, 둘째 줄 값. ③과 ④ 아래는 회색 음영 칸 */
    val SUMMARY_ROW_HEIGHTS = listOf(20f, 24f)

    // ── 칸 문자열 ───────────────────────────────────────

    /** 한 행의 칸별 문자열. 값이 없으면 빈칸(0이나 추정값으로 채우지 않는다). */
    fun cells(row: LogbookRow): List<String> = Column.values().map { column ->
        when (column) {
            Column.DATE -> DisplayFormat.monthDayWithDay(row.date, row.dayOfWeek)
            Column.DEPARTMENT -> row.userDepartment.orEmpty()
            Column.NAME -> row.userName.orEmpty()
            Column.ODOMETER_BEFORE -> DisplayFormat.kmOrBlank(row.odometerBeforeMeters)
            Column.ODOMETER_AFTER -> DisplayFormat.kmOrBlank(row.odometerAfterMeters)
            Column.DRIVEN -> DisplayFormat.kmOrBlank(row.drivenMeters)
            Column.COMMUTE -> DisplayFormat.kmOrBlank(row.commuteMeters)
            Column.GENERAL_BUSINESS -> DisplayFormat.kmOrBlank(row.generalBusinessMeters)
            Column.REMARK -> row.remark   // A가 조합한 문자열을 그대로 출력
        }
    }

    /**
     * 비고를 [maxWidth] 폭에 맞춰 최대 [MAX_REMARK_LINES]줄로 나눈다.
     * - 가능하면 공백에서 끊는다(공백이 줄 앞쪽 절반 안에만 있으면 글자 단위로 끊는다).
     * - 마지막 줄도 넘치면 끝을 "…"로 줄인다.
     * @param measure 문자열 폭을 재는 함수 (PDF에서는 Paint.measureText)
     */
    fun wrapRemark(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
        val lines = mutableListOf<String>()
        var rest = text.trim()
        while (rest.isNotEmpty() && lines.size < MAX_REMARK_LINES) {
            if (lines.size == MAX_REMARK_LINES - 1 || measure(rest) <= maxWidth) {
                lines += ellipsize(rest, maxWidth, measure)
                break
            }
            val fit = fittingLength(rest, maxWidth, measure)
            val space = rest.lastIndexOf(' ', startIndex = fit)
            val cut = if (space > fit / 2) space else fit
            lines += rest.substring(0, cut).trimEnd()
            rest = rest.substring(cut).trimStart()
        }
        return lines
    }

    fun rowHeight(remarkLineCount: Int): Float =
        if (remarkLineCount >= 2) ROW_HEIGHT_TWO_LINES else ROW_HEIGHT

    /** ⑪ ⑫ ⑬ 칸 문자열. 비율이 없으면(⑪ = 0) 빈칸. */
    fun summaryCells(summary: MileageSummary): Triple<String, String, String> = Triple(
        DisplayFormat.kmOrBlank(summary.totalMeters),
        DisplayFormat.kmOrBlank(summary.businessMeters),
        DisplayFormat.percent(summary.businessUseRatio).orEmpty(),
    )

    /** 목적 미입력·계기판 수집 실패 운행이 있으면 표 아래에 적을 문구, 없으면 null */
    fun warningLine(summary: MileageSummary): String? {
        val parts = buildList {
            if (summary.pendingPurposeTripCount > 0) add("목적 미입력 운행 ${summary.pendingPurposeTripCount}건")
            if (summary.incompleteTripCount > 0) add("계기판 수집 실패 운행 ${summary.incompleteTripCount}건(총주행거리에서 제외)")
        }
        return if (parts.isEmpty()) null else "※ " + parts.joinToString(", ") + "이 포함되어 있습니다."
    }

    /** 모든 행이 1줄 높이일 때의 페이지 나누기 */
    fun paginate(rowCount: Int): List<IntRange> = paginate(List(rowCount) { ROW_HEIGHT })

    /**
     * 행 높이를 기준으로 페이지를 나눈다. 결과의 각 원소는 그 페이지에 들어갈 행 인덱스 범위다.
     * - 운행이 없어도 1페이지는 만든다(빈 표 + 합계).
     * - 마지막 페이지에는 합계를 위한 높이([SUMMARY_RESERVED_HEIGHT])가 남아야 한다. 모자라면 합계용 페이지를 하나 더 만든다.
     */
    fun paginate(rowHeights: List<Float>): List<IntRange> {
        val pages = mutableListOf<IntRange>()
        var start = 0
        var lastUsed = 0f
        while (start < rowHeights.size) {
            val capacity = if (pages.isEmpty()) FIRST_PAGE_BODY_HEIGHT else OTHER_PAGE_BODY_HEIGHT
            var end = start
            var used = 0f
            while (end < rowHeights.size && used + rowHeights[end] <= capacity) {
                used += rowHeights[end]
                end++
            }
            if (end == start) {   // 한 행이 페이지보다 높은 경우는 없지만, 무한 반복을 막는다
                used = rowHeights[end]
                end++
            }
            pages += start until end
            lastUsed = used
            start = end
        }
        if (pages.isEmpty()) return listOf(IntRange.EMPTY)

        val lastCapacity = if (pages.size == 1) FIRST_PAGE_BODY_HEIGHT else OTHER_PAGE_BODY_HEIGHT
        if (lastUsed > lastCapacity - SUMMARY_RESERVED_HEIGHT) pages += IntRange.EMPTY
        return pages
    }

    /** [maxWidth]에 들어가는 가장 긴 앞부분의 글자 수 (최소 1) */
    private fun fittingLength(text: String, maxWidth: Float, measure: (String) -> Float): Int {
        var length = text.length
        while (length > 1 && measure(text.substring(0, length)) > maxWidth) length--
        return length
    }

    private fun ellipsize(text: String, maxWidth: Float, measure: (String) -> Float): String {
        if (measure(text) <= maxWidth) return text
        var length = text.length
        while (length > 0 && measure(text.substring(0, length).trimEnd() + ELLIPSIS) > maxWidth) length--
        return text.substring(0, length).trimEnd() + ELLIPSIS
    }
}
