package com.autotrip.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import com.autotrip.domain.model.LogbookExport
import com.autotrip.export.LogbookPdfLayout.CONTENT_WIDTH
import com.autotrip.export.LogbookPdfLayout.Column
import com.autotrip.export.LogbookPdfLayout.HEADER_CELLS
import com.autotrip.export.LogbookPdfLayout.HEADER_ROW_HEIGHTS
import com.autotrip.export.LogbookPdfLayout.MARGIN
import com.autotrip.export.LogbookPdfLayout.PAGE_HEIGHT
import com.autotrip.export.LogbookPdfLayout.PAGE_WIDTH
import com.autotrip.export.LogbookPdfLayout.ROW_HEIGHT
import com.autotrip.export.LogbookPdfLayout.SUMMARY_ROW_HEIGHTS
import com.autotrip.export.LogbookPdfLayout.columnX
import com.autotrip.export.LogbookPdfLayout.spanWidth
import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * [LogbookExport]를 국세청 「업무용승용차 운행기록부」 별지 서식 배치대로 PDF로 그린다.
 * 배치 규칙은 [LogbookPdfLayout], 서식 문구는 [LogbookFormTemplate]에 있고, 이 클래스는 그리기만 한다.
 *
 * 원본 서식처럼 표의 바깥 왼쪽·오른쪽 세로선은 긋지 않고, 표 위아래는 굵은 선으로 닫는다.
 *
 * @param isDemo true면 모든 페이지에 "시연용" 워터마크를 넣는다 (BuildConfig.USE_FAKE_DATA 값을 넘긴다)
 */
class LogbookPdfWriter(private val isDemo: Boolean) {

    /** 앱 전용 저장소의 [directory]에 PDF를 만들고 그 파일을 돌려준다. */
    fun writeToDirectory(export: LogbookExport, directory: File): File {
        directory.mkdirs()
        val name = "logbook_%s_%s.pdf".format(
            export.period.start.format(FILE_DATE),
            export.period.endInclusive.format(FILE_DATE),
        )
        val file = File(directory, name)
        file.outputStream().use { write(export, it) }
        return file
    }

    fun write(export: LogbookExport, out: OutputStream) {
        val template = LogbookFormTemplate.of(export.business.type)
        val document = PdfDocument()
        try {
            val rows = export.rows.map { row ->
                val cells = LogbookPdfLayout.cells(row)
                val remarkLines = LogbookPdfLayout.wrapRemark(
                    cells[Column.REMARK.ordinal],
                    LogbookPdfLayout.REMARK_TEXT_WIDTH,
                ) { cellPaint.measureText(it) }
                PreparedRow(cells, remarkLines, LogbookPdfLayout.rowHeight(remarkLines.size))
            }
            val pages = LogbookPdfLayout.paginate(rows.map { it.height })
            pages.forEachIndexed { index, rowRange ->
                val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
                val page = document.startPage(info)
                drawPage(page.canvas, export, template, rows, rowRange, index, pages.size)
                document.finishPage(page)
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
    }

    /** 그리기 전에 비고 줄 나누기와 행 높이를 미리 계산해 둔 행 */
    private class PreparedRow(val cells: List<String>, val remarkLines: List<String>, val height: Float)

    private fun drawPage(
        canvas: Canvas,
        export: LogbookExport,
        template: LogbookFormTemplate,
        rows: List<PreparedRow>,
        rowRange: IntRange,
        pageIndex: Int,
        pageCount: Int,
    ) {
        if (isDemo) drawWatermark(canvas)

        canvas.drawText(template.formTag, MARGIN, MARGIN + 8f, tagPaint)
        var y = drawTopBlock(canvas, export, template, MARGIN + 16f)
        if (pageIndex == 0) y = drawBasicInfo(canvas, export, template, y + 10f)

        y = drawSectionTitle(canvas, template.usageSection, y + 12f)
        hLine(canvas, MARGIN, MARGIN + CONTENT_WIDTH, y, thickLinePaint)
        y = drawTableHeader(canvas, y)

        rowRange.forEach { i ->
            drawRow(canvas, rows[i], y)
            y += rows[i].height
        }
        if (export.rows.isEmpty()) {
            gridCell(canvas, MARGIN, y, CONTENT_WIDTH, ROW_HEIGHT)
            drawCellText(canvas, "해당 기간의 운행 기록이 없습니다", MARGIN, y, CONTENT_WIDTH, ROW_HEIGHT, center = true)
            y += ROW_HEIGHT
        }

        if (pageIndex == pageCount - 1) y = drawSummaryRows(canvas, export, template, y)
        hLine(canvas, MARGIN, MARGIN + CONTENT_WIDTH, y, thickLinePaint)

        if (pageIndex == pageCount - 1) {
            LogbookPdfLayout.warningLine(export.summary)?.let { canvas.drawText(it, MARGIN, y + 16f, warningPaint) }
        }
        drawFooter(canvas, export, pageIndex, pageCount)
    }

    // ── 상단 줄: 왼쪽 기간 · 가운데 제목 · 오른쪽 상호와 사업자등록번호 ──

    private fun drawTopBlock(canvas: Canvas, export: LogbookExport, template: LogbookFormTemplate, top: Float): Float {
        val height = 44f
        val periodLabelW = 80f
        val periodValueW = 100f
        val rightLabelW = 90f
        val rightValueW = 150f
        val titleW = CONTENT_WIDTH - periodLabelW - periodValueW - rightLabelW - rightValueW
        val bottom = top + height

        hLine(canvas, MARGIN, MARGIN + CONTENT_WIDTH, top, thickLinePaint)
        hLine(canvas, MARGIN, MARGIN + CONTENT_WIDTH, bottom, linePaint)

        var x = MARGIN
        drawCenteredLines(canvas, listOf(template.periodLabel), x, top, periodLabelW, height, topLabelPaint)
        x += periodLabelW
        vLine(canvas, x, top, bottom)
        drawCenteredLines(
            canvas,
            listOf(export.period.start.dotted(), "~", export.period.endInclusive.dotted()),
            x, top, periodValueW, height, cellPaint,
        )
        x += periodValueW
        vLine(canvas, x, top, bottom)
        drawCenteredLines(canvas, listOf(template.title), x, top, titleW, height, titlePaint)
        x += titleW
        vLine(canvas, x, top, bottom)

        val half = height / 2
        hLine(canvas, x, MARGIN + CONTENT_WIDTH, top + half, linePaint)
        drawCellText(canvas, template.nameLabel, x, top, rightLabelW, half, paint = topLabelPaint)
        drawCellText(canvas, template.registrationLabel, x, top + half, rightLabelW, half, paint = topLabelPaint)
        vLine(canvas, x + rightLabelW, top, bottom)
        drawCellText(canvas, export.business.name, x + rightLabelW, top, rightValueW, half)
        drawCellText(canvas, export.business.registrationNumber, x + rightLabelW, top + half, rightValueW, half)
        return bottom
    }

    // ── 1. 기본정보: 페이지 왼쪽 절반, 가운데 점선 ──

    private fun drawBasicInfo(canvas: Canvas, export: LogbookExport, template: LogbookFormTemplate, top: Float): Float {
        var y = drawSectionTitle(canvas, template.basicInfoSection, top)
        val width = CONTENT_WIDTH / 2
        val half = width / 2
        val headerH = 18f
        val valueH = 22f
        hLine(canvas, MARGIN, MARGIN + width, y, linePaint)
        drawCenteredLines(canvas, listOf(template.modelLabel), MARGIN, y, half, headerH, labelPaint)
        drawCenteredLines(canvas, listOf(template.plateLabel), MARGIN + half, y, half, headerH, labelPaint)
        hLine(canvas, MARGIN, MARGIN + width, y + headerH, linePaint)
        drawCenteredLines(canvas, listOf(export.vehicle.model), MARGIN, y + headerH, half, valueH, cellPaint)
        drawCenteredLines(canvas, listOf(export.vehicle.plateNumber), MARGIN + half, y + headerH, half, valueH, cellPaint)
        hLine(canvas, MARGIN, MARGIN + width, y + headerH + valueH, linePaint)
        canvas.drawLine(MARGIN + half, y, MARGIN + half, y + headerH + valueH, dashedLinePaint)
        y += headerH + valueH
        return y
    }

    private fun drawSectionTitle(canvas: Canvas, text: String, top: Float): Float {
        canvas.drawText(text, MARGIN, top + 10f, sectionPaint)
        return top + 16f
    }

    // ── 2. 표 ──────────────────────────────────────────

    /** 원본 서식의 세 줄 병합 머리글 ([LogbookPdfLayout.HEADER_CELLS]) */
    private fun drawTableHeader(canvas: Canvas, top: Float): Float {
        HEADER_CELLS.forEach { cell ->
            val x = columnX(cell.first)
            val w = spanWidth(cell.first, cell.last)
            val y = top + HEADER_ROW_HEIGHTS.take(cell.row).sum()
            val h = HEADER_ROW_HEIGHTS.subList(cell.row, cell.row + cell.rowSpan).sum()
            gridCell(canvas, x, y, w, h)
            drawCenteredLines(canvas, cell.label.split("\n"), x, y, w, h, labelPaint)
        }
        return top + HEADER_ROW_HEIGHTS.sum()
    }

    private fun drawRow(canvas: Canvas, row: PreparedRow, top: Float) {
        Column.values().forEachIndexed { i, column ->
            val x = columnX(column)
            gridCell(canvas, x, top, column.width, row.height)
            if (column == Column.REMARK) {
                drawRemarkLines(canvas, row.remarkLines, x, top, row.height)
            } else {
                drawCellText(canvas, row.cells[i], x, top, column.width, row.height, alignRight = column.alignRight)
            }
        }
    }

    /** 비고 1~2줄을 칸 세로 가운데에 쓴다. 줄 나누기·말줄임은 [LogbookPdfLayout.wrapRemark]에서 끝났다. */
    private fun drawRemarkLines(canvas: Canvas, lines: List<String>, x: Float, top: Float, height: Float) {
        if (lines.isEmpty()) return
        val lineHeight = cellPaint.fontSpacing
        val blockTop = top + (height - lineHeight * lines.size) / 2
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, x + CELL_PADDING, blockTop + lineHeight * i - cellPaint.ascent(), cellPaint)
        }
    }

    // ── ⑪⑫⑬ 합계: 표 맨 아래 두 줄, ③·④ 아래는 회색 음영 ──

    private fun drawSummaryRows(canvas: Canvas, export: LogbookExport, template: LogbookFormTemplate, top: Float): Float {
        val (labelH, valueH) = SUMMARY_ROW_HEIGHTS
        val height = labelH + valueH
        val (total, business, ratio) = LogbookPdfLayout.summaryCells(export.summary)

        listOf(Column.DATE to Column.DATE, Column.DEPARTMENT to Column.NAME).forEach { (first, last) ->
            val x = columnX(first)
            val w = spanWidth(first, last)
            canvas.drawRect(x, top, x + w, top + height, summaryShade)
            gridCell(canvas, x, top, w, height)
        }
        listOf(
            Triple(Column.ODOMETER_BEFORE to Column.DRIVEN, template.totalLabel, total),
            Triple(Column.COMMUTE to Column.GENERAL_BUSINESS, template.businessLabel, business),
            Triple(Column.REMARK to Column.REMARK, template.ratioLabel, ratio),
        ).forEach { (span, label, value) ->
            val x = columnX(span.first)
            val w = spanWidth(span.first, span.second)
            gridCell(canvas, x, top, w, labelH)
            drawCenteredLines(canvas, listOf(label), x, top, w, labelH, labelPaint)
            gridCell(canvas, x, top + labelH, w, valueH)
            drawCellText(canvas, value, x, top + labelH, w, valueH, center = true, paint = valuePaint)
        }
        return top + height
    }

    private fun drawFooter(canvas: Canvas, export: LogbookExport, pageIndex: Int, pageCount: Int) {
        val y = PAGE_HEIGHT - MARGIN + 12f
        canvas.drawText(LogbookPdfLayout.NOTICE, MARGIN, y, footerPaint)
        val page = "생성 ${GENERATED_AT.format(export.generatedAt)}  ·  ${pageIndex + 1} / $pageCount"
        canvas.drawText(page, PAGE_WIDTH - MARGIN - footerPaint.measureText(page), y, footerPaint)
    }

    private fun drawWatermark(canvas: Canvas) {
        canvas.save()
        canvas.rotate(-30f, PAGE_WIDTH / 2f, PAGE_HEIGHT / 2f)
        canvas.drawText(LogbookPdfLayout.DEMO_WATERMARK, PAGE_WIDTH / 2f, PAGE_HEIGHT / 2f + 40f, watermarkPaint)
        canvas.restore()
    }

    // ── 선과 칸 ────────────────────────────────────────

    /** 표 칸 테두리. 원본 서식처럼 표 바깥 왼쪽·오른쪽 세로선은 긋지 않는다. */
    private fun gridCell(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        hLine(canvas, x, x + w, y, linePaint)
        hLine(canvas, x, x + w, y + h, linePaint)
        if (x > MARGIN + 0.5f) vLine(canvas, x, y, y + h)
        if (x + w < MARGIN + CONTENT_WIDTH - 0.5f) vLine(canvas, x + w, y, y + h)
    }

    private fun hLine(canvas: Canvas, x1: Float, x2: Float, y: Float, paint: Paint) = canvas.drawLine(x1, y, x2, y, paint)
    private fun vLine(canvas: Canvas, x: Float, y1: Float, y2: Float) = canvas.drawLine(x, y1, x, y2, linePaint)

    /** 여러 줄을 칸 가운데에 쓴다 (머리글용) */
    private fun drawCenteredLines(canvas: Canvas, lines: List<String>, x: Float, y: Float, w: Float, h: Float, paint: TextPaint) {
        val lineHeight = paint.fontSpacing
        val blockTop = y + (h - lineHeight * lines.size) / 2
        lines.forEachIndexed { i, line ->
            val fitted = TextUtils.ellipsize(line, paint, w - CELL_PADDING * 2, TextUtils.TruncateAt.END).toString()
            val tx = x + (w - paint.measureText(fitted)) / 2
            canvas.drawText(fitted, tx, blockTop + lineHeight * i - paint.ascent(), paint)
        }
    }

    /** 칸 안에 한 줄로 쓴다. 넘치면 말줄임표(…)로 자른다. */
    private fun drawCellText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        center: Boolean = false,
        alignRight: Boolean = false,
        paint: TextPaint = cellPaint,
    ) {
        if (text.isEmpty()) return
        val fitted = TextUtils.ellipsize(text, paint, w - CELL_PADDING * 2, TextUtils.TruncateAt.END).toString()
        val textWidth = paint.measureText(fitted)
        val tx = when {
            center -> x + (w - textWidth) / 2
            alignRight -> x + w - CELL_PADDING - textWidth
            else -> x + CELL_PADDING
        }
        val ty = y + h / 2 - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(fitted, tx, ty, paint)
    }

    private fun LocalDate.dotted(): String = format(PERIOD_DATE)

    private val tagPaint = textPaint(7.5f)
    private val titlePaint = textPaint(17f, bold = true)
    private val topLabelPaint = textPaint(8.5f)
    private val sectionPaint = textPaint(10f, bold = true)
    private val labelPaint = textPaint(8f)
    // 표의 숫자는 고정폭(tabular)으로 써서 자릿수가 세로로 반듯하게 맞도록 한다
    private val cellPaint = textPaint(8.5f).apply { fontFeatureSettings = TABULAR }
    private val valuePaint = textPaint(11f, bold = true).apply { fontFeatureSettings = TABULAR }
    private val footerPaint = textPaint(7.5f).apply { color = Color.DKGRAY }
    // 미입력·계기판 실패 안내는 오류가 아니라 기록의 일부라서 빨강 대신 진한 회색
    private val warningPaint = textPaint(8.5f, bold = true).apply { color = Color.rgb(64, 64, 64) }
    // 시연용 표시는 보이되 숫자를 가리지 않도록 옅은 회색
    private val watermarkPaint = textPaint(120f, bold = true).apply {
        color = Color.argb(18, 0, 0, 0)
        textAlign = Paint.Align.CENTER
    }
    private val linePaint = strokePaint(0.6f)
    private val thickLinePaint = strokePaint(1.4f)
    private val dashedLinePaint = strokePaint(0.6f).apply { pathEffect = DashPathEffect(floatArrayOf(2f, 2f), 0f) }
    // 원본 서식의 합계 행 회색 음영
    private val summaryShade = Paint().apply {
        style = Paint.Style.FILL
        color = Color.rgb(166, 166, 166)
    }

    private fun strokePaint(width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        color = Color.BLACK
    }

    private fun textPaint(size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = Color.BLACK
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    companion object {
        private const val TABULAR = "tnum"
        private const val CELL_PADDING = LogbookPdfLayout.CELL_PADDING
        private val FILE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val PERIOD_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")

        /** 하단 생성 시각 (기기 시간대) */
        private val GENERATED_AT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}
