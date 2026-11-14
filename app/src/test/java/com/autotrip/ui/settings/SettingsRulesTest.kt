package com.autotrip.ui.settings

import com.autotrip.domain.model.BusinessType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRulesTest {

    @Test
    fun `거래처 이름 검사`() {
        assertEquals(Checked.Ok("현대오토에버 판교"), SettingsRules.clientName(" 현대오토에버   판교 ", emptyList()))
        assertEquals(Checked.Invalid("거래처 이름을 입력해 주세요"), SettingsRules.clientName("  ", emptyList()))
        assertEquals(Checked.Invalid("거래처 이름은 두 글자 이상 입력해 주세요"), SettingsRules.clientName("현 .", emptyList()))
        assertEquals(Checked.Invalid("이미 등록된 거래처예요"), SettingsRules.clientName("기아본사", listOf("기아 본사")))
        assertTrue(SettingsRules.clientName("가".repeat(31), emptyList()) is Checked.Invalid)
    }

    @Test
    fun `사업자등록번호는 숫자 10자리를 하이픈 형식으로 맞춘다`() {
        assertEquals(Checked.Ok("123-45-67890"), SettingsRules.registrationNumber("1234567890"))
        assertEquals(Checked.Ok("123-45-67890"), SettingsRules.registrationNumber("123-45-67890"))
        assertEquals(Checked.Ok("123-45-67890"), SettingsRules.registrationNumber(" 123 45 67890 "))
    }

    @Test
    fun `사업자등록번호 오류`() {
        assertEquals(Checked.Invalid("사업자등록번호는 숫자 10자리예요"), SettingsRules.registrationNumber("12345"))
        assertEquals(Checked.Invalid("사업자등록번호는 숫자만 입력해 주세요"), SettingsRules.registrationNumber("123-45-6789A"))
    }

    @Test
    fun `상호명 라벨은 사업자 유형을 따른다`() {
        assertEquals("법인명", SettingsRules.nameLabel(BusinessType.CORPORATION))
        assertEquals("상호명", SettingsRules.nameLabel(BusinessType.SOLE_PROPRIETOR))
        assertEquals(Checked.Invalid("법인명을 입력해 주세요"), SettingsRules.businessName("  ", BusinessType.CORPORATION))
        assertEquals(Checked.Ok("(주)오토트립"), SettingsRules.businessName(" (주)오토트립 ", BusinessType.CORPORATION))
    }

    @Test
    fun `자동차등록번호 형식을 정리한다`() {
        assertEquals(Checked.Ok("12가 3456"), SettingsRules.plateNumber("12가3456"))
        assertEquals(Checked.Ok("123가 4567"), SettingsRules.plateNumber(" 123 가 4567 "))
        assertEquals(Checked.Ok("서울12가 3456"), SettingsRules.plateNumber("서울 12가 3456"))
    }

    @Test
    fun `자동차등록번호 오류`() {
        assertEquals(Checked.Invalid("자동차등록번호를 입력해 주세요"), SettingsRules.plateNumber(" "))
        assertTrue(SettingsRules.plateNumber("ABC1234") is Checked.Invalid)
        assertTrue(SettingsRules.plateNumber("12가345") is Checked.Invalid)
    }

    @Test
    fun `차종을 비우면 차량 정보에서 읽도록 null`() {
        assertNull(SettingsRules.model("   "))
        assertEquals("아이오닉 5", SettingsRules.model(" 아이오닉 5 "))
    }

    @Test
    fun `차량이용자는 부서와 성명이 모두 필요하다`() {
        assertEquals(Checked.Ok("영업팀" to "김현대"), SettingsRules.user(" 영업팀 ", "김현대"))
        assertEquals(Checked.Invalid("부서를 입력해 주세요"), SettingsRules.user("", "김현대"))
        assertEquals(Checked.Invalid("성명을 입력해 주세요"), SettingsRules.user("영업팀", " "))
    }

    @Test
    fun `모든 오류 문구는 마침표로 끝나지 않는다`() {
        listOf(
            SettingsRules.registrationNumber("1"), SettingsRules.registrationNumber("x"),
            SettingsRules.plateNumber(""), SettingsRules.plateNumber("x"),
            SettingsRules.user("", ""), SettingsRules.user("a", ""),
            SettingsRules.businessName("", BusinessType.SOLE_PROPRIETOR),
        ).filterIsInstance<Checked.Invalid>().forEach { assertTrue(it.message, !it.message.endsWith(".")) }
    }
}
