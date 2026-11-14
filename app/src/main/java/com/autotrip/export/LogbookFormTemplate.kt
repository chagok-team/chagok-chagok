package com.autotrip.export

import com.autotrip.domain.model.BusinessType

/**
 * 국세청 「업무용승용차 운행기록부」 별지 서식의 문구 모음.
 * 서식 문구는 코드 곳곳에 흩어 두지 않고 여기 한 곳에서만 고친다 (고시 개정 대비).
 *
 * - 개인사업자: 국세청고시 제2025-5호 별지 서식 원본(<2022.4.1. 개정>)과 대조함
 * - 법인: 국세청고시 제2025-8호(2025.4.1., 제2022-9호 폐지) 별지 서식 원본(<2016.4.1. 제정>)과 대조함
 *   (국가법령정보센터 고시 PDF, 2026-11-13 확인: 사업연도·법인명·사업자등록번호, ⑪⑫ "사업연도" 문구, ⑬업무사용비율(⑫/⑪))
 */
data class LogbookFormTemplate(
    /** 맨 위 왼쪽 서식 표시 */
    val formTag: String,
    /** 상단 왼쪽 기간 항목: 법인 "사업연도", 개인사업자 "과세기간" */
    val periodLabel: String,
    /** 상단 오른쪽 이름 항목: 법인 "법인명", 개인사업자 "상호명" */
    val nameLabel: String,
    val registrationLabel: String = "사업자등록번호",
    val title: String = "업무용승용차 운행기록부",
    val basicInfoSection: String = "1. 기본정보",
    val modelLabel: String = "①차 종",
    val plateLabel: String = "②자동차등록번호",
    val usageSection: String = "2. 업무용 사용비율 계산",
    /** ⑪ ⑫ ⑬ 합계 칸 머리글 */
    val totalLabel: String,
    val businessLabel: String,
    val ratioLabel: String = "⑬업무사용비율(⑫/⑪)",
) {
    companion object {
        val SOLE_PROPRIETOR = LogbookFormTemplate(
            formTag = "【업무용승용차 운행기록부에 관한 별지 서식】<2022.4.1. 개정>",
            periodLabel = "과세기간",
            nameLabel = "상호명",
            totalLabel = "⑪과세기간 총주행 거리(㎞)",
            businessLabel = "⑫과세기간 업무용 사용거리(㎞)",
        )

        val CORPORATION = LogbookFormTemplate(
            formTag = "【업무용승용차 운행기록부에 관한 별지 서식】<2016.4.1. 제정>",
            periodLabel = "사업연도",
            nameLabel = "법인명",
            totalLabel = "⑪사업연도 총주행 거리(㎞)",
            businessLabel = "⑫사업연도 업무용 사용거리(㎞)",
        )

        fun of(type: BusinessType): LogbookFormTemplate = when (type) {
            BusinessType.CORPORATION -> CORPORATION
            BusinessType.SOLE_PROPRIETOR -> SOLE_PROPRIETOR
        }
    }
}
