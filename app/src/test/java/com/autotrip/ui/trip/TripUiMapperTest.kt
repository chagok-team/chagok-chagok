package com.autotrip.ui.trip

import com.autotrip.domain.model.EditableField
import com.autotrip.domain.model.GeoPoint
import com.autotrip.domain.model.InputMethod
import com.autotrip.domain.model.OdometerCaptureStatus
import com.autotrip.domain.model.OdometerReading
import com.autotrip.domain.model.PurposeStatus
import com.autotrip.domain.model.TaxPeriod
import com.autotrip.domain.model.Trip
import com.autotrip.domain.model.TripEditHistory
import com.autotrip.domain.model.TripPlace
import com.autotrip.domain.model.TripPurpose
import com.autotrip.domain.model.TripSource
import com.autotrip.domain.model.TripUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TripUiMapperTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val users = listOf(TripUser(1, "영업팀", "김현대"), TripUser(2, "관리팀", "박제네"))
    private val period = TaxPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

    private fun reading(meters: Long?, status: OdometerCaptureStatus = OdometerCaptureStatus.CAPTURED) =
        OdometerReading(meters, status, null)

    private fun trip(
        id: Long = 1,
        ended: Boolean = true,
        start: OdometerReading = reading(45_275_800),
        end: OdometerReading = reading(45_293_200),
        purpose: TripPurpose? = TripPurpose.CLIENT_VISIT,
        status: PurposeStatus = PurposeStatus.CONFIRMED,
        source: TripSource = TripSource.AUTO,
        userId: Long? = 1,
        destination: String? = "현대오토에버 판교",
    ) = Trip(
        id = id, vehicleId = 1, source = source,
        startedAt = Instant.parse("2026-11-12T00:12:00Z"),          // 09:12 KST
        endedAt = if (ended) Instant.parse("2026-11-12T00:47:00Z") else null,
        odometerStart = start, odometerEnd = end,
        startPlace = TripPlace(null, "본사"), endPlace = TripPlace(null, "판교역 인근"), destinationName = destination,
        userId = userId, purpose = purpose, purposeStatus = status,
        purposeInputMethod = if (status == PurposeStatus.CONFIRMED) InputMethod.VOICE else null,
        purposeInputTime = if (status == PurposeStatus.CONFIRMED) Instant.parse("2026-11-12T00:48:00Z") else null,
        voiceRawText = if (status == PurposeStatus.CONFIRMED) "거래처 방문이요" else null,
        memo = null,
    )

    // ── 목록 ────────────────────────────────────────────

    @Test
    fun `정상 운행 행`() {
        val row = TripUiMapper.row(trip(), users.associateBy { it.id }, seoul)
        assertEquals("11.12(목)", row.dateText)
        assertEquals("09:12 – 09:47", row.timeText)
        assertEquals("거래처 방문", row.purposeText)
        assertEquals("현대오토에버 판교", row.placeText)
        assertEquals("영업팀 김현대", row.userText)
        assertEquals("17.4", row.drivenKmText)
        assertTrue(row.badges.isEmpty())
    }

    @Test
    fun `목적 미입력 운행은 표시하고 건수를 센다`() {
        val pending = trip(id = 2, purpose = null, status = PurposeStatus.PENDING, userId = null)
        val ui = TripUiMapper.list(listOf(trip(), pending), users, period, seoul)
        assertEquals(1, ui.pendingCount)
        assertEquals("목적 미입력", ui.rows[1].purposeText)
        assertEquals("-", ui.rows[1].userText)
        assertEquals(listOf(TripBadge.PURPOSE_PENDING), ui.rows[1].badges)
        assertEquals("2026.01.01 ~ 2026.12.31", ui.periodText)
    }

    @Test
    fun `주행 중 운행은 미입력으로 세지 않는다`() {
        val driving = trip(ended = false, purpose = null, status = PurposeStatus.PENDING, end = reading(null, OdometerCaptureStatus.NOT_YET_CAPTURED))
        val ui = TripUiMapper.list(listOf(driving), users, period, seoul)
        assertEquals(0, ui.pendingCount)
        assertEquals(listOf(TripBadge.DRIVING), ui.rows[0].badges)
        assertEquals("주행 중", ui.rows[0].purposeText)
        assertEquals("09:12 – ", ui.rows[0].timeText)
    }

    @Test
    fun `계기판 실패 운행은 거리 대신 대시`() {
        val failed = trip(start = reading(null, OdometerCaptureStatus.FAILED), source = TripSource.MANUAL)
        val row = TripUiMapper.row(failed, emptyMap(), seoul)
        assertEquals("-", row.drivenKmText)
        assertEquals(listOf(TripBadge.ODOMETER_MISSING, TripBadge.MANUAL), row.badges)
    }

    @Test
    fun `계기판을 지원하지 않는 차량은 수집 실패가 아니라 미지원`() {
        val unsupported = trip(
            start = reading(null, OdometerCaptureStatus.UNSUPPORTED),
            end = reading(null, OdometerCaptureStatus.UNSUPPORTED),
        )
        assertEquals(listOf(TripBadge.ODOMETER_UNSUPPORTED), TripUiMapper.row(unsupported, emptyMap(), seoul).badges)
        assertEquals("계기판 미지원", TripBadge.ODOMETER_UNSUPPORTED.label)

        // 하나라도 실제로 읽기에 실패했으면 수집 실패가 먼저
        val mixed = trip(start = reading(null, OdometerCaptureStatus.UNSUPPORTED), end = reading(null, OdometerCaptureStatus.FAILED))
        assertEquals(listOf(TripBadge.ODOMETER_MISSING), TripUiMapper.row(mixed, emptyMap(), seoul).badges)

        // 주행 중이면 도착 값(아직 안 읽음)은 보지 않는다
        val driving = trip(ended = false, start = reading(1_000), end = reading(null, OdometerCaptureStatus.NOT_YET_CAPTURED))
        assertEquals(listOf(TripBadge.DRIVING), TripUiMapper.row(driving, emptyMap(), seoul).badges)
    }

    @Test
    fun `목적지 이름이 없으면 도착 위치`() {
        assertEquals("판교역 인근", TripUiMapper.row(trip(destination = null), emptyMap(), seoul).placeText)
    }

    @Test
    fun `위치 이름이 없고 좌표만 있으면 좌표를 보여 준다`() {
        val gps = trip(destination = null).copy(
            startPlace = TripPlace(GeoPoint(37.566535, 126.977969), null),
            endPlace = TripPlace(GeoPoint(37.402056, 127.108212), "  "),
        )
        assertEquals("37.4021, 127.1082", TripUiMapper.row(gps, emptyMap(), seoul).placeText)
        val record = TripUiMapper.detail(gps, users, emptyList(), seoul).recordFields.associate { it.label to it.value }
        assertEquals("37.5665, 126.9780", record["출발지"])
        assertEquals("37.4021, 127.1082", record["도착지"])
    }

    @Test
    fun `내비 목적지 이름이 좌표보다 먼저, 아무것도 없으면 대시`() {
        val named = trip(destination = "판교 거래처").copy(endPlace = TripPlace(GeoPoint(37.4, 127.1), null))
        assertEquals("판교 거래처", TripUiMapper.row(named, emptyMap(), seoul).placeText)

        val none = trip(destination = null).copy(startPlace = TripPlace(null, null), endPlace = null)
        assertEquals(null, TripUiMapper.row(none, emptyMap(), seoul).placeText)
        val record = TripUiMapper.detail(none, users, emptyList(), seoul).recordFields.associate { it.label to it.value }
        assertEquals("-", record["출발지"])
        assertEquals("-", record["도착지"])
    }

    @Test
    fun `남반구·서반구 좌표도 부호 그대로`() {
        assertEquals("-33.8688, -151.2093", TripUiMapper.coordinateText(GeoPoint(-33.86882, -151.20929)))
    }

    // ── 상세 ────────────────────────────────────────────

    @Test
    fun `상세는 원본 값과 입력 값을 나눠 보여준다`() {
        val ui = TripUiMapper.detail(trip(), users, emptyList(), seoul)
        val record = ui.recordFields.associate { it.label to it.value }
        assertEquals("45,275.8 km", record["⑤ 주행 전 계기판"])
        assertEquals("45,293.2 km", record["⑥ 주행 후 계기판"])
        assertEquals("17.4 km", record["⑦ 주행거리"])
        assertEquals("11.12 09:12", record["출발"])
        val input = ui.inputFields.associate { it.label to it.value }
        assertEquals("음성", input["입력 방식"])
        assertEquals("11.12 09:48", input["입력 시각"])
        assertEquals("“거래처 방문이요”", input["음성 원문"])
        assertEquals("-", input["메모"])
    }

    @Test
    fun `계기판 상태별 문구`() {
        assertEquals("수집 실패", TripUiMapper.odometerText(reading(null, OdometerCaptureStatus.FAILED)))
        assertEquals("지원하지 않는 차량", TripUiMapper.odometerText(reading(null, OdometerCaptureStatus.UNSUPPORTED)))
        assertEquals("-", TripUiMapper.odometerText(reading(null, OdometerCaptureStatus.NOT_YET_CAPTURED)))
    }

    @Test
    fun `수정 이력 값을 사람이 읽는 문구로 바꾸고 오래된 순으로 정렬한다`() {
        val history = listOf(
            TripEditHistory(2, 1, EditableField.USER, "1", "2", Instant.parse("2026-11-12T05:10:00Z")),
            TripEditHistory(1, 1, EditableField.PURPOSE, "MEETING", "CLIENT_VISIT", Instant.parse("2026-11-12T05:00:00Z")),
            TripEditHistory(3, 1, EditableField.MEMO, null, "계약서 전달", Instant.parse("2026-11-12T05:20:00Z")),
        )
        val ui = TripUiMapper.detail(trip(), users, history, seoul).history
        assertEquals(listOf("목적", "차량이용자", "메모"), ui.map { it.fieldText })
        assertEquals("회의 → 거래처 방문", ui[0].changeText)
        assertEquals("영업팀 김현대 → 관리팀 박제네", ui[1].changeText)
        assertEquals("(없음) → 계약서 전달", ui[2].changeText)
        assertEquals("11.12 14:00", ui[0].whenText)
    }
}
