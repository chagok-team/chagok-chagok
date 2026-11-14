package com.autotrip.ui.trip

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import com.autotrip.ui.theme.AutoTripTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 기록 목록·상세 화면을 샘플 데이터로 그려 PNG로 남긴다 (앱 files/screens 폴더). */
@RunWith(AndroidJUnit4::class)
class TripScreensScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneId.of("Asia/Seoul")
    private val users = listOf(TripUser(1, "영업팀", "김현대"), TripUser(2, "관리팀", "박제네"))
    private val period = TaxPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

    private fun reading(m: Long?, s: OdometerCaptureStatus = OdometerCaptureStatus.CAPTURED) = OdometerReading(m, s, null)

    private fun trip(
        id: Long, start: String, end: String?, odoStart: OdometerReading, odoEnd: OdometerReading,
        purpose: TripPurpose?, dest: String?, userId: Long?, source: TripSource = TripSource.AUTO,
    ) = Trip(
        id = id, vehicleId = 1, source = source,
        startedAt = Instant.parse(start), endedAt = end?.let(Instant::parse),
        odometerStart = odoStart, odometerEnd = odoEnd,
        startPlace = TripPlace(null, "본사"), endPlace = dest?.let { TripPlace(null, it) }, destinationName = dest,
        userId = userId, purpose = purpose,
        purposeStatus = if (purpose == null) PurposeStatus.PENDING else PurposeStatus.CONFIRMED,
        purposeInputMethod = purpose?.let { InputMethod.VOICE },
        purposeInputTime = end?.takeIf { purpose != null }?.let { Instant.parse(it).plusSeconds(40) },
        voiceRawText = purpose?.let { "거래처 방문이요" }, memo = if (id == 5L) "계약서 전달" else null,
    )

    private val trips = listOf(
        trip(6, "2026-11-12T08:05:00Z", null, reading(45_412_300), reading(null, OdometerCaptureStatus.NOT_YET_CAPTURED), null, null, 1),
        trip(5, "2026-11-12T05:00:00Z", "2026-11-12T05:32:00Z", reading(45_275_800), reading(45_293_200), TripPurpose.CLIENT_VISIT, "현대오토에버 판교", 1),
        trip(4, "2026-11-12T02:10:00Z", "2026-11-12T02:41:00Z", reading(45_250_100), reading(45_268_900), null, "판교 테크노밸리", null),
        trip(3, "2026-11-11T09:30:00Z", "2026-11-11T10:05:00Z", reading(null, OdometerCaptureStatus.FAILED), reading(45_249_000), TripPurpose.MEETING, "본사", 2),
        trip(2, "2026-11-11T00:20:00Z", "2026-11-11T00:58:00Z", reading(45_198_300), reading(45_221_700), TripPurpose.SITE_VISIT, "아산공장", 2, TripSource.MANUAL),
        trip(1, "2026-11-10T23:30:00Z", "2026-11-11T00:05:00Z", reading(45_180_000), reading(45_198_000), TripPurpose.COMMUTE, "본사", 1),
    )

    private val outDir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "screens")

    private fun capture(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent(content)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun list() = capture("trip-list") {
        AutoTripTheme(darkTheme = false) {
            TripListScreen(TripUiMapper.list(trips, users, period, zone), browsingAllowed = true, onTripClick = {})
        }
    }

    @Test fun listWhileDriving() = capture("trip-list-driving") {
        AutoTripTheme(darkTheme = false) {
            TripListScreen(TripUiMapper.list(trips, users, period, zone), browsingAllowed = false, onTripClick = {})
        }
    }

    @Test fun detail() = capture("trip-detail") {
        val history = listOf(
            TripEditHistory(1, 5, EditableField.PURPOSE, "MEETING", "CLIENT_VISIT", Instant.parse("2026-11-12T06:00:00Z")),
            TripEditHistory(2, 5, EditableField.MEMO, null, "계약서 전달", Instant.parse("2026-11-12T06:01:00Z")),
        )
        val ui = TripUiMapper.detail(trips.first { it.id == 5L }, users, history, zone)
        AutoTripTheme(darkTheme = false) { TripDetailScreen(TripDetailState.Content(ui), browsingAllowed = true, onBack = {}) }
    }

    /** 위치 이름(주소) 없이 좌표만 있을 때 (실제 차량 GPS 연동 대비) */
    @Test fun detailWithCoordinates() = capture("trip-detail-gps") {
        val gps = trips.first { it.id == 4L }.copy(
            destinationName = null,
            startPlace = TripPlace(GeoPoint(37.566535, 126.977969), null),
            endPlace = TripPlace(GeoPoint(37.402056, 127.108212), null),
        )
        val ui = TripUiMapper.detail(gps, users, emptyList(), zone)
        AutoTripTheme(darkTheme = false) { TripDetailScreen(TripDetailState.Content(ui), browsingAllowed = true, onBack = {}) }
    }

    private fun editCapture(name: String, tripId: Long, target: EditTarget?) = capture(name) {
        val ui = TripUiMapper.detail(trips.first { it.id == tripId }, users, emptyList(), zone)
        AutoTripTheme(darkTheme = false) {
            TripDetailScreen(TripDetailState.Content(ui), browsingAllowed = true, onBack = {}, editing = target, message = if (target == null) "메모를 저장했어요" else null)
        }
    }

    @Test fun detailPending() = editCapture("trip-detail-pending", 4, null)
    @Test fun editPurpose() = editCapture("trip-edit-purpose", 5, EditTarget.PURPOSE)
    @Test fun editUser() = editCapture("trip-edit-user", 5, EditTarget.USER)
    @Test fun editMemo() = editCapture("trip-edit-memo", 5, EditTarget.MEMO)

    @Test
    fun 수정_버튼과_선택이_전달된다() {
        var target: EditTarget? = null
        var user: Long? = -1
        val ui = TripUiMapper.detail(trips.first { it.id == 5L }, users, emptyList(), zone)
        compose.setContent {
            AutoTripTheme(darkTheme = false) {
                var editing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<EditTarget?>(null) }
                TripDetailScreen(
                    TripDetailState.Content(ui), browsingAllowed = true, onBack = {}, editing = editing,
                    handlers = TripEditHandlers(onEdit = { target = it; editing = it }, onUser = { user = it }),
                )
            }
        }
        compose.onAllNodesWithText("수정")[1].performClick()
        assertEquals(EditTarget.USER, target)
        compose.onNodeWithText("관리팀 박제네").performClick()
        assertEquals(2L, user)
    }

    @Test
    fun 행을_누르면_그_운행_id가_전달된다() {
        var clicked: Long? = null
        compose.setContent {
            AutoTripTheme(darkTheme = false) {
                TripListScreen(TripUiMapper.list(trips, users, period, zone), browsingAllowed = true, onTripClick = { clicked = it })
            }
        }
        compose.onNodeWithText("현대오토에버 판교  ·  영업팀 김현대").performClick()
        assertEquals(5L, clicked)
    }
}
