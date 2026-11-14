package com.autotrip.ui.settings

import com.autotrip.domain.model.BusinessType

/**
 * 설정 화면 입력 검사 (Android 의존 없음, 단위 테스트 대상).
 * 결과 문구는 화면 규칙에 따라 끝에 마침표를 찍지 않는다.
 */
sealed interface Checked<out T> {
    data class Ok<T>(val value: T) : Checked<T>
    data class Invalid(val message: String) : Checked<Nothing>
}

object SettingsRules {

    /** 숫자 10자리를 "123-45-67890" 형식으로. 하이픈·공백은 무시한다. (계약: 이 형식으로 저장) */
    fun registrationNumber(input: String): Checked<String> {
        val digits = input.filter { it.isDigit() }
        if (input.any { !it.isDigit() && it != '-' && !it.isWhitespace() }) return Checked.Invalid("사업자등록번호는 숫자만 입력해 주세요")
        if (digits.length != 10) return Checked.Invalid("사업자등록번호는 숫자 10자리예요")
        return Checked.Ok("${digits.substring(0, 3)}-${digits.substring(3, 5)}-${digits.substring(5)}")
    }

    fun businessName(input: String, type: BusinessType): Checked<String> {
        val name = input.trim()
        val label = nameLabel(type)
        return if (name.isEmpty()) Checked.Invalid("${label}을 입력해 주세요") else Checked.Ok(name)
    }

    /** 법인은 "법인명", 개인사업자는 "상호명" (국세청 서식 문구와 같게) */
    fun nameLabel(type: BusinessType): String = when (type) {
        BusinessType.CORPORATION -> "법인명"
        BusinessType.SOLE_PROPRIETOR -> "상호명"
    }

    /**
     * 자동차등록번호. 공백을 정리하고 형식을 확인한다.
     * 허용: "12가 3456", "123가 4567", "서울12가3456"(지역명 포함 구형)
     */
    fun plateNumber(input: String): Checked<String> {
        val compact = input.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return Checked.Invalid("자동차등록번호를 입력해 주세요")
        val match = PLATE.matchEntire(compact)
            ?: return Checked.Invalid("자동차등록번호 형식을 확인해 주세요 (예: 12가 3456)")
        val (region, number, hangul, serial) = match.destructured
        return Checked.Ok("$region$number$hangul $serial")
    }

    /** 차종은 선택. 비우면 null → CarInfo SDK 값을 쓴다 (계약 5장 saveVehicle) */
    fun model(input: String): String? = input.trim().ifEmpty { null }

    fun user(department: String, name: String): Checked<Pair<String, String>> {
        val dept = department.trim()
        val person = name.trim()
        return when {
            dept.isEmpty() -> Checked.Invalid("부서를 입력해 주세요")
            person.isEmpty() -> Checked.Invalid("성명을 입력해 주세요")
            else -> Checked.Ok(dept to person)
        }
    }

    /**
     * 거래처 이름. 공백을 하나로 정리하고, 두 글자 이상이어야 한다(한 글자는 목적지 이름에 엉뚱하게 걸림).
     * 이미 있는 거래처와 공백·대소문자만 다르면 중복으로 본다.
     */
    fun clientName(input: String, existing: List<String>): Checked<String> {
        val name = input.trim().replace(Regex("""\s+"""), " ")
        val key = { s: String -> s.filter { it.isLetterOrDigit() }.lowercase() }
        return when {
            name.isEmpty() -> Checked.Invalid("거래처 이름을 입력해 주세요")
            key(name).length < 2 -> Checked.Invalid("거래처 이름은 두 글자 이상 입력해 주세요")
            name.length > CLIENT_NAME_MAX -> Checked.Invalid("거래처 이름은 ${CLIENT_NAME_MAX}자까지 입력할 수 있어요")
            existing.any { key(it) == key(name) } -> Checked.Invalid("이미 등록된 거래처예요")
            else -> Checked.Ok(name)
        }
    }

    const val CLIENT_NAME_MAX = 30

    private val PLATE = Regex("""^([가-힣]{2})?(\d{2,3})([가-힣])(\d{4})$""")
}
