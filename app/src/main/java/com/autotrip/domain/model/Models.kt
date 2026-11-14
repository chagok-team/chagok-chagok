// 출처: docs/INTERFACES.md v1.1 3장 데이터 모델 — 문서의 코드를 그대로 옮김.
// 클래스명·필드명·시그니처는 A·B 합의 없이 바꾸지 않는다 (바꿀 때는 문서 버전을 올리고 변경 이력에 남긴다).
package com.autotrip.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

// ── 사업자·차량·사용자·과세기간 ─────────────────────────

enum class BusinessType { CORPORATION, SOLE_PROPRIETOR }

data class BusinessProfile(
    val type: BusinessType,
    val name: String,                // 상호명(법인명)
    val registrationNumber: String,  // 사업자등록번호, "123-45-67890" 형식으로 저장
)

enum class ValueSource { SDK, USER }

data class VehicleInfo(
    val id: Long,
    val model: String,               // ① 차종
    val modelSource: ValueSource,    // CarInfo SDK에서 읽었는지, 사용자가 입력했는지
    val plateNumber: String,         // ② 자동차등록번호 (항상 사용자 입력)
)

data class TripUser(
    val id: Long,
    val department: String,          // ④ 부서
    val name: String,                // ④ 성명
)

/** 양 끝 날짜 포함. 기본값은 해당 연도 1/1 ~ 12/31 */
data class TaxPeriod(
    val start: LocalDate,
    val endInclusive: LocalDate,
)

// ── 계기판 거리 ─────────────────────────────────────────

enum class OdometerCaptureStatus {
    CAPTURED,          // SDK에서 정상 수집
    FAILED,            // SDK 호출 실패 (오류, 타임아웃)
    UNSUPPORTED,       // Capability 확인 결과 미지원
    NOT_YET_CAPTURED,  // 아직 수집 시점이 오지 않음 (주행 중인 운행의 도착값)
}

/** 불변 조건: status == CAPTURED 이면 meters != null, 그 외에는 meters == null */
data class OdometerReading(
    val meters: Long?,
    val status: OdometerCaptureStatus,
    val capturedAt: Instant?,
)

// ── 운행 ───────────────────────────────────────────────

enum class TripSource { AUTO, MANUAL }   // 운행 시작·종료를 판단한 방식

enum class PurposeCategory { COMMUTE, GENERAL_BUSINESS, NON_BUSINESS }

enum class TripPurpose(val category: PurposeCategory) {
    CLIENT_VISIT(PurposeCategory.GENERAL_BUSINESS),  // 거래처·대리점 방문
    SITE_VISIT(PurposeCategory.GENERAL_BUSINESS),    // 제조·판매시설 등 사업장 방문, 현장 점검
    MEETING(PurposeCategory.GENERAL_BUSINESS),       // 회의 참석
    PROMOTION(PurposeCategory.GENERAL_BUSINESS),     // 판촉 활동
    TRAINING(PurposeCategory.GENERAL_BUSINESS),      // 업무 관련 교육·훈련
    COMMUTE(PurposeCategory.COMMUTE),                // 출퇴근 (현행법상 업무용, ⑧에 별도 집계)
    PERSONAL(PurposeCategory.NON_BUSINESS),          // 사적 사용
}

enum class PurposeStatus { PENDING, CONFIRMED }

enum class InputMethod { VOICE, BUTTON }   // 최초 확정 시 입력 방식

data class GeoPoint(val latitude: Double, val longitude: Double)

/** label: 주소 또는 POI 이름. 둘 다 없으면 TripPlace 자체를 null로 둔다 */
data class TripPlace(val point: GeoPoint?, val label: String?)

data class Trip(
    val id: Long,
    val vehicleId: Long,
    val source: TripSource,

    // 원본 (수정 불가)
    val startedAt: Instant,
    val endedAt: Instant?,                 // 주행 중이면 null
    val odometerStart: OdometerReading,
    val odometerEnd: OdometerReading,
    val startPlace: TripPlace?,
    val endPlace: TripPlace?,
    val destinationName: String?,          // NaviHelper 목적지 이름 (내비를 쓰지 않았으면 null)

    // 사용자 입력 (지정된 메서드로만 수정)
    val userId: Long?,                     // ④ 차량이용자
    val purpose: TripPurpose?,
    val purposeStatus: PurposeStatus,
    val purposeInputMethod: InputMethod?,  // 최초 확정 시 방식, 이후 불변
    val purposeInputTime: Instant?,        // 최초 확정 시각, 이후 불변
    val voiceRawText: String?,             // STT 인식 원문 (음성 입력이었을 때)
    val memo: String?,
) {
    /** ⑦ 주행거리. 출발·도착 계기판 값이 모두 있어야 계산할 수 있다 */
    val drivenMeters: Long?
        get() {
            val s = odometerStart.meters ?: return null
            val e = odometerEnd.meters ?: return null
            return if (e >= s) e - s else null
        }
}

// ── 수정 이력 ──────────────────────────────────────────

enum class EditableField { PURPOSE, USER, MEMO }

/**
 * 값 인코딩 규칙
 * - PURPOSE: TripPurpose.name
 * - USER: userId.toString()
 * - MEMO: 원문
 * - 값이 없으면 null
 */
data class TripEditHistory(
    val id: Long,
    val tripId: Long,
    val field: EditableField,
    val oldValue: String?,
    val newValue: String?,
    val editedAt: Instant,
)

// ── [v1.1] 내비 목적지·거래처 ──────────────────────────────

/** 내비 목적지 설정 이벤트. A가 NaviHelper(onDestinationInfo·onDestinationChanged)에서 만든다. */
data class NavigationDestination(
    val name: String?,        // 목적지 이름 (POI·주소). 없으면 null
    val point: GeoPoint?,
    val setAt: Instant,
)

/** 등록 거래처 (목적 제안용) */
data class Client(
    val id: Long,
    val name: String,         // 예: "현대오토에버 판교"
    val point: GeoPoint?,     // 이번 단계에서는 선택. B는 이름으로 비교한다
)
