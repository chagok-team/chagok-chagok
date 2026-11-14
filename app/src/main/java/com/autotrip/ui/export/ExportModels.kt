package com.autotrip.ui.export

import com.autotrip.domain.model.AutoTripError
import com.autotrip.domain.model.AutoTripResult
import com.autotrip.domain.model.BusinessType
import com.autotrip.domain.model.LogbookExport
import com.autotrip.domain.model.MileageSummary
import com.autotrip.domain.model.TaxPeriod
import com.autotrip.domain.repository.ReportRepository
import com.autotrip.ui.format.DisplayFormat
import com.autotrip.ui.toUserMessage
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/*
 * 출력 화면 표시 모델과 동작 (Android 의존 없음, 단위 테스트 대상).
 * 집계·비율은 A가 계산한 MileageSummary 값을 그대로 보여준다 (B가 다시 계산하지 않는다).
 */

data class ExportSummaryUi(
    val periodLabel: String,       // 법인 "사업연도", 개인사업자 "과세기간"
    val periodText: String,        // "2026.01.01 ~ 2026.12.31"
    val totalKm: String,           // ⑪
    val businessKm: String,        // ⑫
    val commuteKm: String,         // ⑧ 합
    val generalBusinessKm: String, // ⑨ 합
    val ratioText: String,         // ⑬ "85.7%" / "-"
    val warnings: List<String>,    // 출력 전에 알려야 할 것
)

object ExportMapper {
    private val PERIOD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")

    fun periodLabel(type: BusinessType?): String = when (type) {
        BusinessType.CORPORATION -> "사업연도"
        BusinessType.SOLE_PROPRIETOR, null -> "과세기간"
    }

    fun summary(summary: MileageSummary, businessType: BusinessType?): ExportSummaryUi = ExportSummaryUi(
        periodLabel = periodLabel(businessType),
        periodText = "${summary.period.start.format(PERIOD)} ~ ${summary.period.endInclusive.format(PERIOD)}",
        totalKm = DisplayFormat.kmOrDash(summary.totalMeters),
        businessKm = DisplayFormat.kmOrDash(summary.businessMeters),
        commuteKm = DisplayFormat.kmOrDash(summary.commuteMeters),
        generalBusinessKm = DisplayFormat.kmOrDash(summary.generalBusinessMeters),
        ratioText = DisplayFormat.percentOrDash(summary.businessUseRatio),
        warnings = warnings(summary),
    )

    /** 목적 미입력·계기판 수집 실패 운행이 있으면 출력 전에 알린다 (CLAUDE.md 4장 세무 표현) */
    fun warnings(summary: MileageSummary): List<String> = buildList {
        if (summary.pendingPurposeTripCount > 0) {
            add("목적 미입력 운행 ${summary.pendingPurposeTripCount}건은 업무용 사용거리(⑫)에 들어가지 않아요")
        }
        if (summary.incompleteTripCount > 0) {
            add("계기판 수집 실패 운행 ${summary.incompleteTripCount}건은 총주행거리(⑪)에서 빠져요")
        }
    }

    /** 연 단위 과세기간 (기본값 1/1 ~ 12/31, INTERFACES 10장 #8) */
    fun yearPeriod(year: Int): TaxPeriod = TaxPeriod(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
}

/**
 * PDF 만들기. buildLogbookExport → [writePdf] → 결과.
 * @param writePdf 실제 앱에서는 LogbookPdfWriter(isDemo).writeToDirectory(export, filesDir/exports)
 */
class ExportActions(
    private val reportRepository: ReportRepository,
    private val writePdf: (LogbookExport) -> File,
) {
    sealed interface Outcome {
        data class Created(val file: File, val pageNote: String) : Outcome
        /** 사업자·차량 정보가 없음 → 설정 화면으로 안내 */
        data object NeedsProfile : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun export(period: TaxPeriod): Outcome =
        when (val result = reportRepository.buildLogbookExport(period)) {
            is AutoTripResult.Failure -> when (result.error) {
                AutoTripError.ProfileIncomplete -> Outcome.NeedsProfile
                else -> Outcome.Failed(result.error.toUserMessage())
            }
            is AutoTripResult.Success -> try {
                val export = result.value
                Outcome.Created(writePdf(export), "운행 ${export.rows.size}건")
            } catch (e: Exception) {
                Outcome.Failed("PDF를 만들지 못했어요")
            }
        }
}
