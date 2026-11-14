// 출처: docs/INTERFACES.md v1.1 5장 Repository 인터페이스 — 문서의 코드를 그대로 옮김.
// 클래스명·필드명·시그니처는 A·B 합의 없이 바꾸지 않는다 (바꿀 때는 문서 버전을 올리고 변경 이력에 남긴다).
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
