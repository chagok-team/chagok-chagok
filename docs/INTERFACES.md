# AutoTrip 공통 계약 (docs/INTERFACES.md) — v1.1

> **상태: v1.1 초안 (2026-11-13, B 작성 · A 리뷰 대기).** v1.0(2026-11-12 확정)에 "출발 시 목적 입력"을 **추가**한다. v1.0의 클래스·필드·메서드는 하나도 바꾸지 않는다. 바뀐 곳은 `[v1.1]`로 표시했다.
> 이 문서와 `domain/` 패키지의 클래스명·필드명·메서드명은 한쪽이 혼자 바꾸지 않는다. 바꿀 때는 먼저 상호 협의하고, 버전을 올리고(v1.1 …), 변경 이력에 남긴다.
> 계약 코드의 완료 조건: A가 이 문서의 Kotlin 코드와 Fake 구현을 프로젝트에 넣고 **빌드가 성공**하는 것.

**표기 규칙**

| 표기 | 의미 |
|---|---|
| **[확정]** | 프로젝트 내부 설계. 이 문서 그대로 구현한다. |
| **[SDK 검증 필요]** | PLEOS 공식 문서에서 이름은 확인했지만, 시그니처·단위·동작은 실제 SDK로 확인해야 한다. |
| **[잠정]** | 계산·표시 규칙으로 일단 적용하되, 세무팀 확인 후 바뀔 수 있는 항목. 바뀌어도 계약 형태(타입·시그니처)는 그대로다. |

---

## 0. 원칙 [확정]

1. **UI는 `domain/`의 모델과 인터페이스만 사용한다.** Room 엔티티, DAO, PLEOS SDK 객체를 `ui/`에서 import하지 않는다.
2. **SDK가 수집한 원본 값은 수정할 수 없다.** 출발·도착 시각, 계기판 거리, 위치가 여기에 해당한다. 수정할 수 있는 값은 목적, 차량이용자, 메모 세 가지뿐이다.
3. **수집에 실패하면 값을 비워 둔다.** 0이나 추정값을 저장하지 않고, 실패했다는 상태를 명시적으로 기록한다.
4. **모든 수정은 이력을 남긴다.** 운행기록을 수정하는 일과 이력을 저장하는 일은 하나의 Room 트랜잭션으로 처리한다(A 책임).
5. **집계와 세무 계산은 A가 한다.** B는 받은 값을 표시하고 출력만 한다. B가 비율이나 합계를 다시 계산하지 않는다.
6. **거리는 내부적으로 미터 단위 `Long`으로 다룬다.** 실수 합산 오차를 막기 위해서다. km 변환과 반올림은 표시 시점에만 한다(6장).
7. **Fake·수동 데이터를 SDK 자동 수집 데이터처럼 보이게 하지 않는다.** 수동 운행은 `TripSource.MANUAL`로 구분하고, Fake 모드는 화면 배너와 PDF 워터마크로 표시한다(7장).
8. **세무 적합성이나 법적 요건 충족을 보장하는 표현을 쓰지 않는다.** 앱·PDF·README에는 "국세청 고시 서식 양식에 맞춰 출력"까지만 쓰고, "세무 판단은 전문가 확인이 필요하다"는 안내를 함께 넣는다.

---

## 1. 대상 서식 [확정, 단 고시 버전은 세무팀 확인 — 잠정]

| 구분 | 내용 |
|---|---|
| 기준 서식 | 국세청 고시 「업무용승용차 운행기록부」 별지 서식 (서식 하단 표기 `<2022.4.1. 개정>`) |
| 기본 대상 | **법인** (`BusinessType.CORPORATION`). 개인사업자도 서식 항목이 같아서 같은 모델로 지원할 수 있다. |
| 근거 고시 | 법인: 국세청 고시 제2022-9호(논문 인용)<br>개인사업자: 국세청 고시 제2025-5호(제2022-8호를 대체) |
| 남은 확인 | 법인용 고시가 2022-9호 이후 개정됐는지 세무팀이 확인한다. |

**서식 항목과 이 계약의 대응**

| 서식 항목 | 계약 필드 |
|---|---|
| 상단: 과세기간 | `TaxPeriod` |
| 상단: 상호명 | `BusinessProfile.name` |
| 상단: 사업자등록번호 | `BusinessProfile.registrationNumber` |
| ① 차종 | `VehicleInfo.model` |
| ② 자동차등록번호 | `VehicleInfo.plateNumber` |
| ③ 사용일자(요일) | `LogbookRow.date`, `LogbookRow.dayOfWeek` |
| ④ 사용자(부서·성명) | `LogbookRow.userDepartment`, `LogbookRow.userName` (운전자가 아니라 차량이용자) |
| ⑤ 주행 전 계기판의 거리 | `LogbookRow.odometerBeforeMeters` |
| ⑥ 주행 후 계기판의 거리 | `LogbookRow.odometerAfterMeters` |
| ⑦ 주행거리 | `LogbookRow.drivenMeters` |
| ⑧ 출퇴근용 | `LogbookRow.commuteMeters` |
| ⑨ 일반업무용 | `LogbookRow.generalBusinessMeters` |
| ⑩ 비고 | `LogbookRow.remark` |
| ⑪ 과세기간 총주행거리 | `MileageSummary.totalMeters` |
| ⑫ 과세기간 업무용 사용거리 | `MileageSummary.businessMeters` |
| ⑬ 업무사용비율 (⑫/⑪) | `MileageSummary.businessUseRatio` |

---

## 2. 공통 결과·오류 타입 [확정]

```kotlin
package com.autotrip.domain.model

sealed interface AutoTripResult<out T> {
    data class Success<T>(val value: T) : AutoTripResult<T>
    data class Failure(val error: AutoTripError) : AutoTripResult<Nothing>
}

sealed interface AutoTripError {
    data class TripNotFound(val tripId: Long) : AutoTripError
    /** 이미 목적이 확정된 운행에 confirmPurpose를 호출함 → updatePurpose를 써야 한다 */
    data class PurposeAlreadyConfirmed(val tripId: Long) : AutoTripError
    /** 목적이 확정되지 않은 운행에 updatePurpose를 호출함 */
    data class PurposeNotConfirmed(val tripId: Long) : AutoTripError
    /** 운행이 아직 끝나지 않았는데 목적을 확정하려 함, 주행 중 수동 시작 등 */
    data class InvalidState(val message: String) : AutoTripError
    /** 사업자 정보나 차량 정보가 없어 운행기록부를 출력할 수 없음 */
    data object ProfileIncomplete : AutoTripError
    data class Storage(val cause: Throwable) : AutoTripError
    /** sdk 예: "Vehicle.Odometer", "FusedLocation" */
    data class Sdk(val sdk: String, val message: String) : AutoTripError
}
```

- Repository 메서드는 예외를 던지지 않고 `AutoTripResult`를 반환한다.
- `Flow`는 오류가 나도 종료하지 않는다. 오류는 로그로 남기고 마지막 정상 값을 유지한다.

---

## 3. 데이터 모델 [확정]

```kotlin
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
```

**[v1.1] 출발 전에 받은 목적의 `Trip` 필드 값**
- `purposeInputTime` = `setPlannedPurpose`를 부른 시각이다. 그래서 `startedAt`보다 이를 수 있다.
- `purposeInputMethod`·`voiceRawText`는 `confirmPurpose`와 같은 규칙이다. 거래처 제안에 "네"라고 답했으면 `VOICE`, 원문은 "네"처럼 실제 답이다.
- 그 밖의 필드(`userId` 기본 사용자 적용 등)는 `confirmPurpose`와 같다.

---

## 4. 운행 상태 계약 [확정]

```kotlin
package com.autotrip.domain.model

import java.time.Instant

sealed interface TripState {
    data object Idle : TripState
    data class Driving(val tripId: Long, val source: TripSource, val startedAt: Instant) : TripState
    data class Arrived(val tripId: Long, val source: TripSource, val endedAt: Instant) : TripState
}

data class DetectionAvailability(
    val autoDetectionSupported: Boolean,   // 자동 감지에 필요한 신호를 하나 이상 쓸 수 있는지
    val odometerSupported: Boolean,
    val locationSupported: Boolean,
    val navigationSupported: Boolean,
)
```

**상태 전이 규칙 (A 책임)**

```
Idle ──(자동 감지 또는 startManualTrip)──▶ Driving(tripId)
Driving(tripId) ──(자동 감지 또는 endManualTrip)──▶ Arrived(tripId)
Arrived(tripId) ──(다음 운행 시작)──▶ Driving(newTripId)
```

1. Trip 행은 `Driving`으로 바뀔 때 **한 번만** 생성한다. 같은 운행 중에 들어오는 중복 신호는 무시한다.
2. `Arrived(tripId)`로의 전환은 운행마다 **한 번만** 일어난다(운행 종료 이벤트 중복 방지는 A 책임). 도착 정보(endedAt, odometerEnd, endPlace)가 DB에 저장된 **다음에** 전환한다.
3. `Arrived`는 다음 운행이 시작될 때까지 유지된다. 목적 확정 여부와는 관계없다. 목적 상태는 `PurposeStatus`로만 관리한다.
4. **앱 재시작 시 상태 복원:**
   - DB에 끝나지 않은 운행(`endedAt == null`)이 있으면 `Driving(tripId)`로 복원하고 그 운행을 이어서 기록한다.
   - 없으면 가장 최근에 끝난 운행으로 `Arrived(tripId)`를 복원하고, 운행 기록이 없으면 `Idle`로 시작한다.

**[v1.1] 출발 질문 정책 (B 책임)** — v1.0의 "도착 질문 정책"을 대신한다.

목적은 **운전자가 내비에 목적지를 설정할 때** 음성으로 묻는다. B는 아래 조건을 모두 만족할 때만 TTS 질문을 시작한다.

1. **이번 프로세스 실행 중에** `tripTracker.destinationEvents`에서 이벤트를 받았다. (재시작 후 지난 이벤트를 다시 받지 않는다. 아래 5장 참고)
2. 지금 `Driving(tripId)`이면 그 운행의 `purposeStatus`가 `PENDING`이다. 이미 확정된 운행이면 묻지 않는다.
3. 지금 `Idle`·`Arrived`이면, 이번 프로세스에서 30분 안에 **같은 목적지로** 출발 목적을 물은 적이 없다.
   - 출발 전에 **다른 목적지로 바꾸면 다시 묻는다.** 새 답은 `setPlannedPurpose`로 보내 기존 보관 목적을 대체한다. (목적과 목적지가 어긋나지 않게)
   - 주행 중에 목적지를 바꾸면 다시 묻지 않는다(조건 2). 목적이 달라졌으면 정차 후 수정 화면에서 고치고, 수정 이력이 남는다.

질문 흐름:
- 목적지 이름이 등록 거래처(`Client.name`)와 같으면 "○○ 방문으로 기록할까요?"라고 먼저 제안한다. **자동으로 확정하지 않고** "네"면 `CLIENT_VISIT`으로, "아니요"면 일반 질문으로 넘어간다.
- 그 밖에는 "이번 운행 목적은 무엇인가요?" → STT → `VoicePurposeMapper` → TTS 확인 → `setPlannedPurpose(…)`.
- 인식 실패·불확실은 v1.0과 같다(재질문 1회 → 정차 중 버튼 선택, `InputMethod.BUTTON`).

**[v1.1] 도착 안내 (B 책임):** 도착할 때는 목적을 다시 묻지 않는다. `Driving(tripId) → Arrived(tripId)` 전환을 이번 실행 중에 관찰했고 그 운행이 `CONFIRMED`이면 "○○으로 기록했어요"라고 안내만 한다.

**[v1.1] 내비 없이 출발한 운행:** 목적 미입력(`PENDING`)으로 두고, 아래 규칙대로 나중에 입력한다.

**재시작 후 목적 미입력 운행:** 자동으로 다시 묻지 않는다. `observePendingPurposeTrips()`로 대시보드에 "목적 미입력 n건"을 보여주고, 사용자가 정차 중에 직접 입력한다. 시간이 지난 운행을 갑자기 음성으로 물으면 운전 중일 수 있어서 위험하기 때문이다.

---

## 5. Repository 인터페이스 [확정]

```kotlin
package com.autotrip.domain.repository

import com.autotrip.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 운행 감지 (A 구현) */
interface TripTracker {
    val state: StateFlow<TripState>
    val availability: StateFlow<DetectionAvailability>

    /** 수동 시작. 버튼을 누른 시점에 SDK로 계기판·위치를 읽는다. 읽지 못하면 실패 상태로 기록한다.
     *  이미 Driving이면 InvalidState를 반환한다. */
    suspend fun startManualTrip(): AutoTripResult<Long>

    /** 수동 종료. Driving이 아니면 InvalidState를 반환한다. */
    suspend fun endManualTrip(): AutoTripResult<Long>

    /**
     * v1.1: 내비 목적지가 설정·변경될 때마다 하나씩 보낸다. 다시 물을지는 B가 정한다.
     * - 지난 이벤트를 다시 보내지 않는다(replay 0). 앱 재시작 후 예전 목적지로 질문하지 않기 위해서다.
     * - NaviHelper가 이 이벤트를 지원하지 않으면 실제 모드에서는 아무것도 보내지 않는다(Fake로 시연).
     */
    val destinationEvents: Flow<NavigationDestination>
}

/** 운행기록 조회·수정 (A 구현) */
interface TripRepository {
    fun observeTrips(period: TaxPeriod): Flow<List<Trip>>         // startedAt 내림차순
    fun observeTrip(tripId: Long): Flow<Trip?>
    fun observePendingPurposeTrips(): Flow<List<Trip>>            // 종료됐지만 목적이 PENDING인 운행
    fun observeEditHistory(tripId: Long): Flow<List<TripEditHistory>>  // editedAt 오름차순

    /**
     * 최초 목적 확정. PENDING이고 endedAt != null일 때만 가능하다.
     * - purposeInputTime = 호출 시각, purposeInputMethod = method (이후 변경 불가)
     * - userId가 null이면 기본 사용자(ProfileRepository)를 적용한다
     * - 최초 확정은 "수정"이 아니므로 TripEditHistory에 남기지 않는다
     */
    suspend fun confirmPurpose(
        tripId: Long,
        purpose: TripPurpose,
        method: InputMethod,
        userId: Long?,
        voiceRawText: String?,
    ): AutoTripResult<Unit>

    /** 확정 이후의 수정. 값이 바뀔 때만 이력을 남긴다(같은 값이면 아무것도 하지 않음). 트랜잭션 처리. */
    suspend fun updatePurpose(tripId: Long, purpose: TripPurpose): AutoTripResult<Unit>
    suspend fun updateUser(tripId: Long, userId: Long?): AutoTripResult<Unit>
    suspend fun updateMemo(tripId: Long, memo: String?): AutoTripResult<Unit>

    /**
     * v1.1: 출발 시 목적 확정. 내비 목적지를 설정할 때 받은 목적을 넘긴다.
     * - 지금 Driving이고 그 운행이 PENDING이면 → 그 운행에 바로 확정한다 (confirmPurpose와 같은 규칙, endedAt 조건만 없음).
     *   그 운행이 이미 CONFIRMED이면 PurposeAlreadyConfirmed를 반환한다 (목적지 변경으로 바꾸지 않는다).
     * - 아직 운행이 없으면(Idle·Arrived) → "다음 운행 목적"으로 보관했다가, 다음 Driving이 시작될 때 그 운행에 적용한다.
     *   - 보관한 목적은 30분 안에 운행이 시작되지 않으면 버린다 (상수로 빼서 조절).
     *   - 보관 중에 다시 부르면 새 목적이 기존 보관 목적을 대체한다.
     * - purposeInputTime = 이 메서드를 부른 시각
     * - 최초 확정이므로 TripEditHistory에 남기지 않는다
     * - 목적지는 받지 않는다. Trip.destinationName은 A가 NaviHelper 원본 값으로만 채운다.
     */
    suspend fun setPlannedPurpose(
        purpose: TripPurpose,
        method: InputMethod,
        voiceRawText: String?,
    ): AutoTripResult<Unit>

    // 삭제 메서드는 두지 않는다.
}

/** 사업자·차량·사용자 정보 (A 구현) */
interface ProfileRepository {
    fun observeBusinessProfile(): Flow<BusinessProfile?>
    fun observeVehicle(): Flow<VehicleInfo?>        // v1.0은 차량 1대 (차량 내장 앱)
    fun observeUsers(): Flow<List<TripUser>>
    fun observeDefaultUserId(): Flow<Long?>

    suspend fun saveBusinessProfile(profile: BusinessProfile): AutoTripResult<Unit>
    /** model이 null이면 CarInfo SDK 값을 쓰고, SDK도 실패하면 오류를 반환한다 */
    suspend fun saveVehicle(model: String?, plateNumber: String): AutoTripResult<VehicleInfo>
    suspend fun addUser(department: String, name: String): AutoTripResult<TripUser>
    suspend fun setDefaultUserId(userId: Long?): AutoTripResult<Unit>

    // [v1.1] 거래처 (목적 제안용)
    fun observeClients(): Flow<List<Client>>                         // 이름 오름차순
    suspend fun addClient(name: String, point: GeoPoint?): AutoTripResult<Client>
    /** 거래처 목록에서만 지운다. 이미 기록된 운행에는 영향이 없다 */
    suspend fun removeClient(clientId: Long): AutoTripResult<Unit>
}

/** 집계·출력 데이터 (A 구현) */
interface ReportRepository {
    fun observeSummary(period: TaxPeriod): Flow<MileageSummary>
    /** 사업자·차량 정보가 없으면 ProfileIncomplete를 반환한다 */
    suspend fun buildLogbookExport(period: TaxPeriod): AutoTripResult<LogbookExport>
}
```

**스레드 규칙:** 모든 `suspend` 함수와 `Flow`는 메인 스레드에서 호출해도 안전해야 한다(main-safe). IO 디스패처 전환은 A가 구현 내부에서 한다.

---

## 6. 집계·PDF 출력 계약 [확정]

```kotlin
package com.autotrip.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

data class MileageSummary(
    val period: TaxPeriod,
    val totalMeters: Long,            // ⑪ 거리가 확인된 모든 운행의 ⑦ 합 (목적 미입력 포함 — 잠정)
    val commuteMeters: Long,          // ⑧ 합
    val generalBusinessMeters: Long,  // ⑨ 합
    val businessMeters: Long,         // ⑫ = ⑧ + ⑨
    val nonBusinessMeters: Long,      // PERSONAL 합
    val unclassifiedMeters: Long,     // 목적 미입력 운행의 거리 합 (⑪에는 포함, ⑫에는 미포함)
    val businessUseRatio: Double?,    // ⑬ = ⑫ / ⑪, 범위 0.0~1.0, ⑪이 0이면 null
    val incompleteTripCount: Int,     // 계기판 값이 없어 ⑦을 계산할 수 없는 운행 수 (⑪에서 제외)
    val pendingPurposeTripCount: Int,
)

enum class RowFlag { ODOMETER_MISSING, PURPOSE_PENDING, MANUAL_TRIP, EDITED }

data class LogbookRow(
    val tripId: Long,
    val date: LocalDate,               // ③ 출발 시각 기준, 기기 시간대
    val dayOfWeek: DayOfWeek,
    val userDepartment: String?,       // ④
    val userName: String?,
    val odometerBeforeMeters: Long?,   // ⑤ null이면 빈칸으로 출력
    val odometerAfterMeters: Long?,    // ⑥
    val drivenMeters: Long?,           // ⑦
    val commuteMeters: Long?,          // ⑧ COMMUTE면 ⑦, 아니면 null
    val generalBusinessMeters: Long?,  // ⑨ GENERAL_BUSINESS면 ⑦, 아니면 null
    val remark: String,                // ⑩ A가 조합한 최종 문자열 (아래 규칙)
    val purpose: TripPurpose?,
    val source: TripSource,
    val flags: Set<RowFlag>,
)

data class LogbookExport(
    val business: BusinessProfile,
    val vehicle: VehicleInfo,
    val period: TaxPeriod,
    val rows: List<LogbookRow>,        // 운행 1건 = 1행, startedAt 오름차순
    val summary: MileageSummary,       // ⑪ ⑫ ⑬
    val generatedAt: Instant,
)
```

**⑩ 비고 조합 규칙 (A):**
`"{목적 한글명} · {행선지}" + (메모 있으면 " · {메모}") + (플래그별 표기)`

| 플래그 | 비고에 붙는 표기 |
|---|---|
| `MANUAL_TRIP` | `[수동기록]` |
| `ODOMETER_MISSING` | `[계기판 수집 실패]` |
| `PURPOSE_PENDING` | `[목적 미입력]` |
| `EDITED` | `[수정됨]` |

**표시 규칙 (B):**
- 거리는 `meters / 1000.0`을 km로 바꿔 **소수 첫째 자리 반올림**한다.
- 비율은 **% 소수 첫째 자리 반올림**한다.
- 합계(⑪⑫)는 A가 미터 단위로 합산한 값을 변환한다. 그래서 행별 반올림 값을 더한 것과 0.1km 정도 차이가 날 수 있으며, 이 차이는 허용한다. [잠정]

**PDF 고정 문구 (B):** 하단에 "본 운행기록부는 국세청 고시 서식 양식에 맞춰 작성되었습니다. 세무 판단은 전문가 확인이 필요합니다."를 넣는다. 목적 미입력 운행이나 계기판 수집 실패 운행이 있으면 그 건수도 함께 표기한다.

---

## 7. A와 B의 연결 방식 [확정]

```kotlin
package com.autotrip.di   // A 소유

import com.autotrip.domain.repository.ProfileRepository
import com.autotrip.domain.repository.ReportRepository
import com.autotrip.domain.repository.TripRepository
import com.autotrip.domain.repository.TripTracker

interface AppContainer {
    val tripTracker: TripTracker
    val tripRepository: TripRepository
    val profileRepository: ProfileRepository
    val reportRepository: ReportRepository
}
```

- `AutoTripApplication`(A 소유)이 `container: AppContainer`를 가진다.
  - `BuildConfig.USE_FAKE_DATA`가 `true`면 Fake 구현을, `false`면 실제 구현을 주입한다.
- B는 ViewModel Factory에서 `(application as AutoTripApplication).container`로 꺼내 쓴다.
- **Fake 구현: A 전담**
  - 위치: `com.autotrip.fake` 패키지
  - 구성: `FakeTripTracker`, `FakeTripRepository`, `FakeProfileRepository`, `FakeReportRepository`, `FakeAppContainer`
  - Fake도 이 문서의 규칙(상태 전이, 중복 방지, 수정 이력, 오류 반환, 집계 규칙)을 실제 구현과 똑같이 지킨다.
  - B가 추가 테스트 데이터나 시뮬레이션 기능이 필요하면 A에게 요청한다. A의 Fake가 `main`에 올라오기 전까지 B는 자기 브랜치에서만 쓰는 임시 Fake를 둘 수 있지만, 그 코드는 `main`에 머지하지 않는다.

```kotlin
package com.autotrip.fake   // A 소유

// TripTracker 인터페이스 외에 Fake 전용으로 공개하는 시뮬레이션 기능 [확정]
class FakeTripTracker /* : TripTracker */ {
    /** Idle 또는 Arrived 상태에서 호출하면 새 운행을 Driving으로 시작하고, 계기판 값을 distanceMeters만큼 늘린다.
     *  이미 Driving이면 진행 중인 운행의 계기판 값만 늘린다. */
    fun simulateDrive(distanceMeters: Long) { /* A 구현 */ }

    /** Driving → Arrived 전환. Driving이 아니면 아무것도 하지 않는다. */
    fun simulateArrive() { /* A 구현 */ }

    /** 다음 운행의 계기판 수집 실패를 흉내 낸다. onStart면 출발값, onEnd면 도착값을 FAILED로 기록한다. */
    fun setOdometerFailure(onStart: Boolean, onEnd: Boolean) { /* A 구현 */ }

    /** v1.1: destinationEvents로 NavigationDestination(name, point = null, setAt = 지금)을 하나 보낸다. */
    fun simulateSetDestination(name: String) { /* A 구현 */ }
}
```

  - **[v1.1] 샘플 거래처 2~3개** (예: "현대오토에버 판교", "현대모비스 의왕연구소")를 넣는다. 시연에서 `simulateSetDestination`에 같은 이름을 넘기면 거래처 제안이 나온다.

  - B는 디버그·시연 화면에서 `container.tripTracker as? FakeTripTracker`로 이 기능에 접근한다. 실제 모드에서는 null이므로 해당 버튼을 숨긴다.
  - **샘플 데이터 (약 10건):** 아래 유형을 각각 1건 이상 포함한다.
    - 목적 확정: 일반업무, 출퇴근, 사적 사용
    - 목적 미입력
    - 수정 이력이 있는 운행
    - 계기판 수집 실패 운행
    - 수동 기록 운행
  - 샘플에는 사업자 정보, 차량 정보, 차량이용자 2명 이상도 함께 넣는다. PDF를 바로 출력할 수 있게 하기 위해서다.
- **Fake 모드 표시 (B):** `BuildConfig.USE_FAKE_DATA = true`이면 앱 상단에 "시뮬레이션 데이터" 배너를, PDF에 "시연용" 워터마크를 표시한다. 에뮬레이터 Virtual Console을 거쳐 실제 SDK로 받은 값은 Fake가 아니므로 표시하지 않는다.
- **B 내부 전용 (계약 아님):** `VoicePurposeMapper`, `SpeechInput`·`SpeechOutput` 래퍼, 화면 상태 클래스
  - B는 음성 원문을 `voiceRawText`로, 최종 선택 목적을 `purpose`로 구분해서 `confirmPurpose`에 넘긴다.

---

## 8. 예외·실패 처리 규칙 [확정]

| 상황 | 처리 | 담당 |
|---|---|---|
| 출발 시 계기판 값 읽기 실패 | `odometerStart = OdometerReading(null, FAILED, now)`로 저장하고 운행은 계속 기록 | A |
| Odometer 미지원 | `UNSUPPORTED`, `availability.odometerSupported = false` | A |
| 도착 시 계기판 값 읽기 실패 | `odometerEnd`를 `FAILED`로 저장. 이 운행은 ⑦ = null, `incompleteTripCount`에 포함, PDF 행에 `ODOMETER_MISSING` | A |
| 계기판 값이 거꾸로 감소 | `drivenMeters = null`로 처리하고 원본은 그대로 보존 | A |
| 위치·내비 실패 | `TripPlace`, `destinationName`을 null로 두고 운행은 정상 기록 | A |
| STT 인식 실패 또는 분류 불확실 | 재질문 1회 → 그래도 실패하면 정차 중 버튼 선택 화면 | B |
| [v1.1] 내비 없이 출발 | 목적 미입력(PENDING) 유지, 나중에 미입력 목록에서 입력 | A·B |
| [v1.1] 출발 전 목적지 변경 | 다시 묻고, 새 답으로 보관 목적을 대체 (`setPlannedPurpose` 다시 호출) | B |
| [v1.1] 운행 중 목적지 변경 | 다시 묻지 않음. 확정된 목적은 바꾸지 않음 (`setPlannedPurpose`가 `PurposeAlreadyConfirmed` 반환). 정차 후 수정 화면에서 고침 | A·B |
| [v1.1] 목적을 받아 두고 30분 안에 출발하지 않음 | 보관 목적을 버림. 다음 운행은 PENDING으로 시작 | A |
| [v1.1] 거래처 제안에 "아니요" 또는 불확실 | 일반 목적 질문으로 넘어감. 자동 확정하지 않음 | B |
| [v1.1] NaviHelper 목적지 이벤트 미지원 | 실제 모드에서는 질문이 나오지 않고 모든 운행이 PENDING → 미입력 목록으로 입력. 시연은 Fake 이벤트 | A·B |
| 목적 미입력 상태로 다음 운행 시작 | 기존 운행은 PENDING 유지, 대시보드에 미입력 건수 표시 | A·B |
| 주행 중 앱 재시작 | 끝나지 않은 운행을 `Driving(tripId)`로 복원하고 이어서 기록 | A |
| 재시작 후 목적 미입력 운행 | 자동 음성 질문 없음, 미입력 목록으로 안내 | B |
| 계기판 값을 사용자가 직접 입력하려 함 | v1.0에서는 지원하지 않음 (입력 UI를 두지 않음) | A·B |
| 사업자·차량 정보 없이 PDF 요청 | `ProfileIncomplete` → B는 설정 화면으로 안내 | A·B |
| 목적 미입력이나 계기판 실패 운행이 있는 상태에서 PDF 요청 | 출력은 허용하되, 출력 전에 경고하고 PDF 하단에 건수를 표기 | B |

---

## 9. PLEOS SDK — 공식 문서 확인 범위 [SDK 검증 필요]

이 표는 **A와 B 내부 구현에서만** 쓴다. 공통 계약(1~8장)은 SDK 타입에 의존하지 않는다.

| SDK (버전) | 문서에서 확인한 것 | 실제 SDK로 검증할 것 | 담당 |
|---|---|---|---|
| Vehicle 2.3.2 · Odometer | `getCurrentDistanceDriven`, `registerDistanceDriven`, `unregisterDistanceDriven`, `getTripCurrentDrivingDistance`, `getTripCurrentDrivingTime`, 각 `check...Capabilities`. 권한 `pleos.car.permission.CAR_DRIVE`. 거리 단위를 조회하는 기능이 있음 | 콜백 형태, **반환 단위(km 또는 m) → 미터로 변환**, 갱신 주기, 에뮬레이터 Virtual Console 연동 | A |
| Vehicle · CarInfo | 도메인 존재(제조사, 연식 등). 권한 `CAR_INFO` | 메서드 이름, 차종 문자열 제공 여부 | A |
| Vehicle · Brake | `getCurrentBrakePedalStatus`, `registerBrakePedalStatus` | 주차 브레이크 API 존재 여부(문서에서 미확인) | A |
| NaviHelper 3.0.5 | `initialize`, `addListener`, `getDestinationInfo` → `onDestinationInfo`, `onDestinationArrived`, `onDestinationChanged`, `release` | `DestinationInfo` 필드, 권한, 내비 미사용 시 동작. **[v1.1] 목적지 "설정" 시점에 콜백이 오는지** (안 오면 Fake 이벤트로 먼저 개발) | A |
| FusedLocation 1.1.3 | `isSupported`, `initialize`, `getLastKnownLocation`, `registerFusedLocationCallback`, `addProximityAlert`, `release`. 권한 `FUSED_LOCATION`. 결과 타입 `LocationData` | Gradle 좌표, SDK 신청 상태("준비 중"), 에뮬레이터 위치 시뮬레이션 | A |
| TextToSpeech 3.3.1 | `TextToSpeech(context, Mode)`, `initialize`, `setLocale`, `speak`, `stop`, `release`. `Mode.ON_DEVICE`는 앱 등록 불필요 | 한국어 지원, 에뮬레이터 동작, SDK 신청 상태("준비 중") | B |
| SpeechToText 3.3.1 | `SpeechToText(context, Mode)`, `initialize`, `addListener`, `setLocale`, `request`, `stop`, `release`, `ResultListener` | 한국어 인식, 에뮬레이터 마이크, SDK 신청 상태("준비 중") | B |
| Android Car API | `CarUxRestrictionsManager.registerListener`, `onUxRestrictionsChanged`, `isRequiresDistractionOptimization` | — (Android 표준) | A: 감지 신호로 사용<br>B: UI 제한 |

- 시동, 기어 API는 문서에서 확인되지 않았다. 자동 감지는 주행 제한 상태, 계기판 변화, 내비 도착 이벤트를 조합하고, 수동 시작·종료를 항상 함께 제공한다.
- 문서 기준: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/

---

## 10. 결정 사항 요약

| # | 항목 | 결정 | 상태 |
|---|---|---|---|
| 1 | 대상 사업자 유형 | 법인 기본, 개인사업자는 같은 모델로 지원 | 잠정 (세무팀 확인) |
| 2 | 근거 고시 버전 | 현행 서식 양식 기준. 법인용 고시가 제2022-9호 이후 개정됐는지 세무팀이 확인 | 잠정 |
| 3 | 계기판 수집 실패 운행 | 기록은 유지하고, 거리는 집계에서 제외하고, PDF에는 빈칸과 `[계기판 수집 실패]` 표기 | 확정 |
| 4 | 계기판 값 직접 입력 | v1.0에서 지원하지 않음 | 확정 |
| 5 | 같은 날 같은 사용자의 여러 운행 | 운행 1건 = 1행으로 출력 | 확정 |
| 6 | 목적 미입력 운행의 거리 | ⑪에는 포함하고 ⑫에서는 제외 | 잠정 |
| 7 | 반올림 차이 | 0.1km 정도의 차이 허용 | 잠정 |
| 8 | 과세기간 기본값 | 1/1 ~ 12/31, 설정에서 변경 가능 | 확정 |
| 9 | 차량 수 | v1.0은 1대 | 확정 |
| 10 | 기타 업무 목적 | `OTHER_BUSINESS` 없음 | 확정 |
| 11 | ~~도착 질문~~ → [v1.1] 출발 질문 | 내비 목적지 설정 이벤트를 이번 실행 중에 받았을 때만 질문. 도착 때는 기록 결과만 안내. 재시작 후에는 미입력 목록으로 안내 | v1.1 초안 |
| 12 | 재시작 시 상태 | 끝나지 않은 운행이 있으면 `Driving(tripId)`로 복원 | 확정 |
| 13 | Fake 구현 | A 전담, 시뮬레이션 기능과 샘플 데이터 포함 | 확정 |
| 14 | Fake 모드 표시 | 화면 배너와 PDF 워터마크 | 확정 |
| 15 | 세무 표현 | 적합성이나 법적 요건 충족을 보장하는 표현 금지 | 확정 |
| 16 | Kotlin 버전 | `data object` 사용을 위해 1.9 이상 | 확정 |
| 17 | [v1.1] 보관 목적 유효 시간 | 30분, 새 목적이 오면 대체 | v1.1 초안 |
| 18 | [v1.1] 거래처 일치 | 자동 확정하지 않고 네/아니요로 확정 | v1.1 초안 |
| 19 | [v1.1] 목적지 변경 | 출발 전에 다른 목적지로 바꾸면 다시 묻고 보관 목적을 대체. 주행 중에는 묻지 않고 확정된 목적도 바꾸지 않음(정차 후 수정) | v1.1 초안 |
| 20 | [v1.1] 내비 없이 출발 | 목적 미입력으로 두고 나중에 입력 | v1.1 초안 |
| 21 | 톨게이트 정산·주유·충전 지출 | v1.1 범위 밖. 공통 계약은 별도 버전에서 논의 | 보류 |

"잠정" 항목은 계산·표시 규칙이다. 세무팀 확인 후 바뀌어도 A의 구현 내부만 고치면 되고, 계약 형태는 바뀌지 않는다.

---

## 변경 이력

| 버전 | 날짜 | 내용 |
|---|---|---|
| v1.0-draft | 2026-11-11 | 최초 초안 (A 피드백 반영) |
| v1.0 | 2026-11-12 | A·B 합의로 확정. AppContainer import 추가, Fake를 A 전담으로 변경하고 시뮬레이션 기능 명시, 도착 질문 정책과 재시작 복원 규칙 추가, 계기판 직접 입력 불허, Fake 모드 표시, 세무 표현 원칙 추가 |
| **v1.1 초안** | 2026-11-13 | 목적을 내비 목적지 설정 시 받도록 변경(세무팀·팀 결정, A 조건부 동의). 추가만: `NavigationDestination`, `Client`, `TripTracker.destinationEvents`, `TripRepository.setPlannedPurpose`, `ProfileRepository` 거래처 3개 메서드, `FakeTripTracker.simulateSetDestination`, 샘플 거래처. 4장 도착 질문 정책 → 출발 질문 정책·도착 안내로 교체. 예외 규칙 5개, 결정 17~21 추가. A 리뷰 반영: `setPlannedPurpose`에서 `destinationName` 인자 제거(목적지는 A가 NaviHelper 원본으로만 관리), 거래처 삭제는 기존 운행에 영향 없음 확인, 머지는 A 구현과 함께. 출발 전 목적지 변경 시 다시 묻기(목적·목적지 불일치 방지) |
