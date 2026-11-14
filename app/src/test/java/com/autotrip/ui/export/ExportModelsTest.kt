package com.autotrip.ui.export

import com.autotrip.domain.model.AutoTripError
import com.autotrip.domain.model.AutoTripResult
import com.autotrip.domain.model.BusinessProfile
import com.autotrip.domain.model.BusinessType
import com.autotrip.domain.model.LogbookExport
import com.autotrip.domain.model.MileageSummary
import com.autotrip.domain.model.TaxPeriod
import com.autotrip.domain.model.ValueSource
import com.autotrip.domain.model.VehicleInfo
import com.autotrip.domain.repository.ReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

class ExportModelsTest {

    private val period = TaxPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

    private fun summary(ratio: Double? = 0.8565, pending: Int = 0, incomplete: Int = 0) = MileageSummary(
        period = period, totalMeters = 793_300, commuteMeters = 197_300, generalBusinessMeters = 482_200,
        businessMeters = 679_500, nonBusinessMeters = 93_000, unclassifiedMeters = 20_800,
        businessUseRatio = ratio, incompleteTripCount = incomplete, pendingPurposeTripCount = pending,
    )

    @Test
    fun `요약은 A가 계산한 값을 그대로 표시 형식으로만 바꾼다`() {
        val ui = ExportMapper.summary(summary(), BusinessType.CORPORATION)
        assertEquals("사업연도", ui.periodLabel)
        assertEquals("2026.01.01 ~ 2026.12.31", ui.periodText)
        assertEquals("793.3", ui.totalKm)
        assertEquals("679.5", ui.businessKm)
        assertEquals("197.3", ui.commuteKm)
        assertEquals("482.2", ui.generalBusinessKm)
        assertEquals("85.7%", ui.ratioText)
        assertTrue(ui.warnings.isEmpty())
    }

    @Test
    fun `개인사업자는 과세기간, 비율이 없으면 대시`() {
        val ui = ExportMapper.summary(summary(ratio = null), BusinessType.SOLE_PROPRIETOR)
        assertEquals("과세기간", ui.periodLabel)
        assertEquals("-", ui.ratioText)
    }

    @Test
    fun `미입력·계기판 실패 운행은 경고한다`() {
        val warnings = ExportMapper.warnings(summary(pending = 2, incomplete = 1))
        assertEquals(2, warnings.size)
        assertTrue(warnings[0].startsWith("목적 미입력 운행 2건"))
        assertTrue(warnings[1].startsWith("계기판 수집 실패 운행 1건"))
        assertTrue(warnings.none { it.endsWith(".") })
    }

    @Test
    fun `연 단위 과세기간`() {
        assertEquals(TaxPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)), ExportMapper.yearPeriod(2025))
    }

    // ── PDF 만들기 ───────────────────────────────────────

    private class StubReportRepository(private val result: AutoTripResult<LogbookExport>) : ReportRepository {
        override fun observeSummary(period: TaxPeriod): Flow<MileageSummary> = flowOf()
        override suspend fun buildLogbookExport(period: TaxPeriod) = result
    }

    private fun export() = LogbookExport(
        business = BusinessProfile(BusinessType.CORPORATION, "(주)오토트립", "123-45-67890"),
        vehicle = VehicleInfo(1, "아이오닉 5", ValueSource.USER, "12가 3456"),
        period = period, rows = emptyList(), summary = summary(), generatedAt = Instant.EPOCH,
    )

    @Test
    fun `출력 데이터를 PDF로 만든다`() = runBlocking {
        val file = File("logbook.pdf")
        val outcome = ExportActions(StubReportRepository(AutoTripResult.Success(export()))) { file }.export(period)
        assertEquals(ExportActions.Outcome.Created(file, "운행 0건"), outcome)
    }

    @Test
    fun `사업자·차량 정보가 없으면 설정으로 안내`() = runBlocking {
        val outcome = ExportActions(StubReportRepository(AutoTripResult.Failure(AutoTripError.ProfileIncomplete))) { File("x") }
            .export(period)
        assertEquals(ExportActions.Outcome.NeedsProfile, outcome)
    }

    @Test
    fun `PDF 쓰기 실패는 오류 문구로`() = runBlocking {
        val outcome = ExportActions(StubReportRepository(AutoTripResult.Success(export()))) { throw java.io.IOException("disk") }
            .export(period)
        assertEquals(ExportActions.Outcome.Failed("PDF를 만들지 못했어요"), outcome)
    }
}
