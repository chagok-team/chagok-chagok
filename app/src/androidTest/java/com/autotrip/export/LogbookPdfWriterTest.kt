package com.autotrip.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autotrip.domain.model.BusinessProfile
import com.autotrip.domain.model.BusinessType
import com.autotrip.domain.model.LogbookExport
import com.autotrip.domain.model.LogbookRow
import com.autotrip.domain.model.MileageSummary
import com.autotrip.domain.model.RowFlag
import com.autotrip.domain.model.TaxPeriod
import com.autotrip.domain.model.TripPurpose
import com.autotrip.domain.model.TripSource
import com.autotrip.domain.model.ValueSource
import com.autotrip.domain.model.VehicleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.autotrip.ui.export.PdfPreviewScreen
import com.autotrip.ui.theme.AutoTripTheme
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * 에뮬레이터에서 실제 PDF를 만들어 본다.
 * 결과 파일: /data/data/com.autotrip/files/exports/logbook_20260101_20261231.pdf
 * 꺼내기: adb shell run-as com.autotrip cat files/exports/logbook_20260101_20261231.pdf > sample.pdf
 */
@RunWith(AndroidJUnit4::class)
class LogbookPdfWriterTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 샘플_운행기록부_PDF를_만든다_법인() {
        val export = sampleExport(rowCount = 25, type = BusinessType.CORPORATION)
        val file = LogbookPdfWriter(isDemo = true).writeToDirectory(export, File(context.filesDir, "exports"))
        assertTrue(file.length() > 0)
        assertTrue(renderPages(file, "corp") >= 2)
    }

    @Test
    fun 샘플_운행기록부_PDF를_만든다_개인사업자() {
        // 합계 두 줄까지 첫 페이지에 들어가는 분량
        val export = sampleExport(rowCount = 8, type = BusinessType.SOLE_PROPRIETOR)
        val file = LogbookPdfWriter(isDemo = false).writeToDirectory(export, File(context.filesDir, "exports-sole"))
        assertEquals(1, renderPages(file, "sole"))
    }

    /** 눈으로 확인할 수 있게 페이지를 PNG로 남긴다 (files/exports/<prefix>-page-N.png). 페이지 수를 돌려준다. */
    private fun renderPages(file: File, prefix: String): Int {
        val outDir = File(context.filesDir, "exports").apply { mkdirs() }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        File(outDir, "$prefix-page-${i + 1}.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                }
                return renderer.pageCount
            }
        }
    }

    @Test
    fun 운행이_없어도_한_페이지를_만든다() {
        val export = sampleExport(rowCount = 0)
        val dir = File(context.cacheDir, "exports-empty")
        val file = LogbookPdfWriter(isDemo = false).writeToDirectory(export, dir)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer -> assertEquals(1, renderer.pageCount) }
        }
    }

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 미리보기는_쪽마다_흰_바탕에_내용을_그린다() {
        val file = LogbookPdfWriter(isDemo = true).writeToDirectory(sampleExport(rowCount = 25), File(context.cacheDir, "exports-preview"))
        PdfPageRenderer(file).use { renderer ->
            assertTrue(renderer.pageCount >= 2)
            val bitmap = renderer.render(1, 1000)
            assertEquals(1000, bitmap.width)
            assertTrue("A4 가로 비율", bitmap.height in 690..720)
            // 흰 바탕 위에 글자·선이 그려졌는지 (흰색이 아닌 점이 있어야 한다)
            val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            assertTrue(pixels.any { it != Color.WHITE })
            assertEquals(Color.WHITE, bitmap.getPixel(2, 2))
        }
    }

    @Test
    fun 미리보기_화면에서_쪽을_넘기고_닫는다() {
        val file = LogbookPdfWriter(isDemo = true).writeToDirectory(sampleExport(rowCount = 25), File(context.cacheDir, "exports-preview-ui"))
        val n = PdfPageRenderer(file).use { it.pageCount }
        var closed = false
        compose.setContent {
            AutoTripTheme(darkTheme = false) { PdfPreviewScreen(file, onClose = { closed = true }) }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("운행기록부 1 / ${n}쪽").fetchSemanticsNodes().isNotEmpty() }
        capture("pdf-preview-page1")
        compose.onNodeWithText("다음 쪽 ›").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("운행기록부 2 / ${n}쪽").fetchSemanticsNodes().isNotEmpty() }
        capture("pdf-preview-page2")
        compose.onNodeWithText("닫기").performClick()
        assertTrue(closed)
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(context.filesDir, "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun sampleExport(rowCount: Int, type: BusinessType = BusinessType.CORPORATION): LogbookExport {
        val period = TaxPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
        var odometer = 45_231_449L
        val rows = (0 until rowCount).map { i ->
            val date = LocalDate.of(2026, 11, 2).plusDays(i / 2L)
            val driven = 12_300L + i * 1_700L
            val before = odometer
            val after = odometer + driven
            odometer = after + 800
            when (i) {
                3 -> row(i, date, "영업팀", "김현대", before, after, driven, null, TripPurpose.CLIENT_VISIT,
                    "거래처 방문 · 현대오토에버 판교 사옥 · 신규 계약 미팅 후 납품 일정 협의 [수정됨]",
                    setOf(RowFlag.EDITED))
                5 -> row(i, date, null, null, before, after, driven, null, null,
                    "행선지 미확인 [목적 미입력]", setOf(RowFlag.PURPOSE_PENDING))
                7 -> row(i, date, "영업팀", "이기아", null, after, null, null, TripPurpose.MEETING,
                    "회의 · 본사 [계기판 수집 실패]", setOf(RowFlag.ODOMETER_MISSING))
                9 -> row(i, date, "관리팀", "박제네", before, after, driven, null, TripPurpose.SITE_VISIT,
                    "사업장 방문 · 아산공장 [수동기록]", setOf(RowFlag.MANUAL_TRIP), TripSource.MANUAL)
                11 -> row(i, date, "영업팀", "김현대", before, after, driven, null, TripPurpose.CLIENT_VISIT,
                    "거래처 방문 · 현대모비스 의왕연구소 · 신규 부품 견적 협의", emptySet())
                else -> if (i % 4 == 0) {
                    row(i, date, "영업팀", "김현대", before, after, driven, driven, TripPurpose.COMMUTE, "출퇴근 · 자택", emptySet())
                } else if (i % 6 == 1) {
                    row(i, date, "영업팀", "김현대", before, after, driven, null, TripPurpose.PERSONAL, "개인 용무", emptySet())
                } else {
                    row(i, date, "영업팀", "김현대", before, after, driven, null, TripPurpose.CLIENT_VISIT, "거래처 방문 · 대리점", emptySet())
                }
            }
        }
        val total = rows.mapNotNull { it.drivenMeters }.sum()
        val commute = rows.mapNotNull { it.commuteMeters }.sum()
        val general = rows.mapNotNull { it.generalBusinessMeters }.sum()
        return LogbookExport(
            business = if (type == BusinessType.CORPORATION) {
                BusinessProfile(type, "(주)오토트립 시연", "123-45-67890")
            } else {
                BusinessProfile(type, "오토트립 상사", "234-56-78901")
            },
            vehicle = VehicleInfo(1, "아이오닉 5", ValueSource.USER, "12가 3456"),
            period = period,
            rows = rows,
            summary = MileageSummary(
                period = period,
                totalMeters = total,
                commuteMeters = commute,
                generalBusinessMeters = general,
                businessMeters = commute + general,
                nonBusinessMeters = rows.filter { it.purpose == TripPurpose.PERSONAL }.mapNotNull { it.drivenMeters }.sum(),
                unclassifiedMeters = rows.filter { it.purpose == null }.mapNotNull { it.drivenMeters }.sum(),
                businessUseRatio = if (total == 0L) null else (commute + general).toDouble() / total,
                incompleteTripCount = rows.count { RowFlag.ODOMETER_MISSING in it.flags },
                pendingPurposeTripCount = rows.count { RowFlag.PURPOSE_PENDING in it.flags },
            ),
            generatedAt = Instant.parse("2026-11-12T07:00:00Z"),
        )
    }

    private fun row(
        i: Int, date: LocalDate, dept: String?, name: String?,
        before: Long?, after: Long?, driven: Long?, commute: Long?,
        purpose: TripPurpose?, remark: String, flags: Set<RowFlag>, source: TripSource = TripSource.AUTO,
    ) = LogbookRow(
        tripId = i.toLong(), date = date, dayOfWeek = date.dayOfWeek,
        userDepartment = dept, userName = name,
        odometerBeforeMeters = before, odometerAfterMeters = after, drivenMeters = driven,
        commuteMeters = commute,
        generalBusinessMeters = if (purpose?.category == com.autotrip.domain.model.PurposeCategory.GENERAL_BUSINESS) driven else null,
        remark = remark, purpose = purpose, source = source, flags = flags,
    )
}
