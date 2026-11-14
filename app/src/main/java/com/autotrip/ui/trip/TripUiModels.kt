package com.autotrip.ui.trip

import com.autotrip.domain.model.EditableField
import com.autotrip.domain.model.GeoPoint
import com.autotrip.domain.model.TripPlace
import java.util.Locale
import com.autotrip.domain.model.InputMethod
import com.autotrip.domain.model.OdometerCaptureStatus
import com.autotrip.domain.model.OdometerReading
import com.autotrip.domain.model.PurposeStatus
import com.autotrip.domain.model.TaxPeriod
import com.autotrip.domain.model.Trip
import com.autotrip.domain.model.TripEditHistory
import com.autotrip.domain.model.TripPurpose
import com.autotrip.domain.model.TripSource
import com.autotrip.domain.model.TripUser
import com.autotrip.ui.format.DisplayFormat
import com.autotrip.ui.purpose.TripSummaryText
import com.autotrip.voice.spokenLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * 기록 목록·상세 화면용 표시 모델과 변환 규칙 (Android 의존 없음, 단위 테스트 대상).
 * - 원본 값(시각, 계기판 거리, 위치)은 읽기 전용으로 보여주기만 한다.
 * - 값이 없으면 0이나 추정값 대신 "-"로 보여준다.
 * - 집계·비율은 다시 계산하지 않는다 (목록에서는 운행별 ⑦만 표시).
 */

/** 운행 표시에 붙는 작은 표시 */
enum class TripBadge(val label: String) {
    PURPOSE_PENDING("목적 미입력"),
    ODOMETER_MISSING("계기판 수집 실패"),
    /** 차량이 계기판 값을 지원하지 않음 (수집을 시도했지만 실패한 것과 구분) */
    ODOMETER_UNSUPPORTED("계기판 미지원"),
    MANUAL("수동 기록"),
    DRIVING("주행 중"),
}

data class TripRowUi(
    val tripId: Long,
    val dateText: String,        // "11.12(목)"
    val timeText: String,        // "09:12 – 09:47", 주행 중이면 "09:12 – "
    val purposeText: String,     // "거래처 방문" / "목적 미입력"
    val placeText: String?,      // 목적지 이름 또는 도착 위치
    val userText: String,        // "영업팀 김현대" / "-"
    val drivenKmText: String,    // "17.4" / "-"
    val badges: List<TripBadge>,
)

data class TripListUi(
    val periodText: String,      // "2026.01.01 ~ 2026.12.31"
    val rows: List<TripRowUi>,
    val pendingCount: Int,
)

/** 수정할 수 있는 값은 이 세 가지뿐이다 (INTERFACES 1장 #2). 시각·계기판·위치는 넣지 않는다. */
enum class EditTarget { PURPOSE, USER, MEMO }

/** [edit]이 있으면 그 값 옆에 수정 버튼을 둔다 */
data class DetailField(val label: String, val value: String, val edit: EditTarget? = null)

data class EditHistoryUi(val whenText: String, val fieldText: String, val changeText: String)

data class UserOptionUi(val userId: Long, val label: String)

/** 수정 화면에 넘길 현재 값. 주행 중인 운행은 수정하지 않으므로 null. */
data class TripEditInfo(
    val purposePending: Boolean,       // true면 최초 입력(confirmPurpose), false면 수정(updatePurpose)
    val purpose: TripPurpose?,
    val userId: Long?,
    val memo: String?,
    val users: List<UserOptionUi>,
    val summary: TripSummaryText,
)

data class TripDetailUi(
    val tripId: Long,
    val title: String,                 // "11.12(목) 운행"
    val badges: List<TripBadge>,
    val recordFields: List<DetailField>, // 차량이 기록한 원본 (읽기 전용)
    val inputFields: List<DetailField>,  // 사람이 입력한 값 (목적·이용자·메모 등)
    val history: List<EditHistoryUi>,    // 오래된 순
    val edit: TripEditInfo? = null,
)

object TripUiMapper {
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM.dd HH:mm")
    private val PERIOD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")
    const val PENDING_TEXT = "목적 미입력"
    const val DRIVING_TEXT = "주행 중"
    private const val NONE = DisplayFormat.DASH

    fun list(trips: List<Trip>, users: List<TripUser>, period: TaxPeriod, zone: ZoneId): TripListUi {
        val userById = users.associateBy { it.id }
        val rows = trips.map { row(it, userById, zone) }
        return TripListUi(
            periodText = "${period.start.format(PERIOD)} ~ ${period.endInclusive.format(PERIOD)}",
            rows = rows,
            pendingCount = trips.count { it.endedAt != null && it.purposeStatus == PurposeStatus.PENDING },
        )
    }

    fun row(trip: Trip, userById: Map<Long, TripUser>, zone: ZoneId): TripRowUi = TripRowUi(
        tripId = trip.id,
        dateText = DisplayFormat.monthDayWithDay(trip.startedAt.localDate(zone)),
        timeText = "${trip.startedAt.time(zone)} – ${trip.endedAt?.time(zone).orEmpty()}",
        // 주행 중인 운행은 아직 목적을 물을 때가 아니므로 "목적 미입력" 대신 "주행 중"
        purposeText = if (trip.endedAt == null) DRIVING_TEXT else trip.purpose?.spokenLabel ?: PENDING_TEXT,
        placeText = arrivalPlaceText(trip),
        userText = userText(trip.userId, userById),
        drivenKmText = DisplayFormat.kmOrDash(trip.drivenMeters),
        badges = badges(trip),
    )

    fun detail(
        trip: Trip,
        users: List<TripUser>,
        history: List<TripEditHistory>,
        zone: ZoneId,
    ): TripDetailUi {
        val userById = users.associateBy { it.id }
        val ended = trip.endedAt != null
        fun editable(target: EditTarget) = target.takeIf { ended }
        return TripDetailUi(
            tripId = trip.id,
            title = "${DisplayFormat.monthDayWithDay(trip.startedAt.localDate(zone))} 운행",
            badges = badges(trip),
            recordFields = listOf(
                DetailField("출발", trip.startedAt.dateTime(zone)),
                DetailField("도착", trip.endedAt?.dateTime(zone) ?: "주행 중"),
                DetailField("⑤ 주행 전 계기판", odometerText(trip.odometerStart)),
                DetailField("⑥ 주행 후 계기판", odometerText(trip.odometerEnd)),
                DetailField("⑦ 주행거리", trip.drivenMeters?.let { "${DisplayFormat.km(it)} km" } ?: NONE),
                DetailField("출발지", placeText(trip.startPlace) ?: NONE),
                DetailField("도착지", arrivalPlaceText(trip) ?: NONE),
                DetailField("기록 방식", if (trip.source == TripSource.AUTO) "자동" else "수동"),
            ),
            inputFields = listOf(
                DetailField("목적", if (ended) trip.purpose?.spokenLabel ?: PENDING_TEXT else DRIVING_TEXT, editable(EditTarget.PURPOSE)),
                DetailField("차량이용자", userText(trip.userId, userById), editable(EditTarget.USER)),
                DetailField("입력 방식", inputMethodText(trip.purposeInputMethod)),
                DetailField("입력 시각", trip.purposeInputTime?.dateTime(zone) ?: NONE),
                DetailField("음성 원문", trip.voiceRawText?.let { "“$it”" } ?: NONE),
                DetailField("메모", trip.memo?.takeIf { it.isNotBlank() } ?: NONE, editable(EditTarget.MEMO)),
            ),
            history = history.sortedBy { it.editedAt }.map { historyRow(it, userById, zone) },
            edit = if (!ended) null else TripEditInfo(
                purposePending = trip.purposeStatus == PurposeStatus.PENDING,
                purpose = trip.purpose,
                userId = trip.userId,
                memo = trip.memo,
                users = users.map { UserOptionUi(it.id, "${it.department} ${it.name}") },
                summary = summary(trip, userById, zone),
            ),
        )
    }

    /** 목적 선택 화면 위쪽에 보여줄 이번 운행 요약 */
    fun summary(trip: Trip, userById: Map<Long, TripUser>, zone: ZoneId): TripSummaryText {
        val r = row(trip, userById, zone)
        val before = DisplayFormat.km(trip.odometerStart.meters)
        val after = DisplayFormat.km(trip.odometerEnd.meters)
        return TripSummaryText(
            drivenKm = DisplayFormat.km(trip.drivenMeters),
            odometerRange = if (before != null && after != null) "$before → $after km" else null,
            arrivedAt = "${r.dateText} ${r.timeText}" + (r.placeText?.let { " · $it" } ?: ""),
        )
    }

    /**
     * 위치 표시: 이름(주소)이 있으면 이름, 없고 좌표만 있으면 "37.4021, 127.1086" (소수 넷째 자리 ≈ 10m).
     * 좌표는 차량이 기록한 원본이라 보여 주기만 한다. 둘 다 없으면 null → 화면은 "-".
     */
    fun placeText(place: TripPlace?): String? =
        place?.label?.trim()?.takeIf { it.isNotEmpty() } ?: place?.point?.let(::coordinateText)

    /** 도착지: 내비 목적지 이름 → 도착 위치 이름 → 도착 위치 좌표 순서 */
    fun arrivalPlaceText(trip: Trip): String? =
        trip.destinationName?.trim()?.takeIf { it.isNotEmpty() } ?: placeText(trip.endPlace)

    fun coordinateText(point: GeoPoint): String =
        String.format(Locale.US, "%.4f, %.4f", point.latitude, point.longitude)

    fun badges(trip: Trip): List<TripBadge> = buildList {
        if (trip.endedAt == null) add(TripBadge.DRIVING)
        else if (trip.purposeStatus == PurposeStatus.PENDING) add(TripBadge.PURPOSE_PENDING)
        // 지원하지 않는 차량이면 "미지원", 지원하는데 읽지 못했으면 "수집 실패" (상세 화면 문구와 같게)
        val readings = listOfNotNull(trip.odometerStart, trip.odometerEnd.takeIf { trip.endedAt != null })
        when {
            readings.any { it.status == OdometerCaptureStatus.FAILED } -> add(TripBadge.ODOMETER_MISSING)
            readings.any { it.status == OdometerCaptureStatus.UNSUPPORTED } -> add(TripBadge.ODOMETER_UNSUPPORTED)
        }
        if (trip.source == TripSource.MANUAL) add(TripBadge.MANUAL)
    }

    fun odometerText(reading: OdometerReading): String = when (reading.status) {
        OdometerCaptureStatus.CAPTURED -> "${DisplayFormat.km(reading.meters)} km"
        OdometerCaptureStatus.FAILED -> "수집 실패"
        OdometerCaptureStatus.UNSUPPORTED -> "지원하지 않는 차량"
        OdometerCaptureStatus.NOT_YET_CAPTURED -> NONE
    }

    /** 수정 이력 값 인코딩(INTERFACES 3장)을 사람이 읽는 문구로: PURPOSE=enum 이름, USER=id, MEMO=원문 */
    fun historyRow(item: TripEditHistory, userById: Map<Long, TripUser>, zone: ZoneId): EditHistoryUi {
        fun decode(value: String?): String = when {
            value == null -> "(없음)"
            item.field == EditableField.PURPOSE ->
                runCatching { TripPurpose.valueOf(value).spokenLabel }.getOrDefault(value)
            item.field == EditableField.USER ->
                value.toLongOrNull()?.let { userText(it, userById) } ?: value
            else -> value
        }
        val fieldText = when (item.field) {
            EditableField.PURPOSE -> "목적"
            EditableField.USER -> "차량이용자"
            EditableField.MEMO -> "메모"
        }
        return EditHistoryUi(
            whenText = item.editedAt.dateTime(zone),
            fieldText = fieldText,
            changeText = "${decode(item.oldValue)} → ${decode(item.newValue)}",
        )
    }

    private fun userText(userId: Long?, userById: Map<Long, TripUser>): String =
        userId?.let(userById::get)?.let { "${it.department} ${it.name}" } ?: NONE

    private fun inputMethodText(method: InputMethod?): String = when (method) {
        InputMethod.VOICE -> "음성"
        InputMethod.BUTTON -> "버튼"
        null -> NONE
    }


    private fun Instant.localDate(zone: ZoneId): LocalDate = atZone(zone).toLocalDate()
    private fun Instant.time(zone: ZoneId): String = atZone(zone).format(TIME)
    private fun Instant.dateTime(zone: ZoneId): String = atZone(zone).format(DATE_TIME)
}
