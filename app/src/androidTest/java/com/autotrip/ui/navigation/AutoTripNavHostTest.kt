package com.autotrip.ui.navigation

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autotrip.domain.model.AutoTripError
import com.autotrip.domain.model.AutoTripResult
import com.autotrip.domain.model.BusinessProfile
import com.autotrip.domain.model.BusinessType
import com.autotrip.domain.model.Client
import com.autotrip.domain.model.DetectionAvailability
import com.autotrip.domain.model.GeoPoint
import com.autotrip.domain.model.InputMethod
import com.autotrip.domain.model.LogbookExport
import com.autotrip.domain.model.MileageSummary
import com.autotrip.domain.model.NavigationDestination
import com.autotrip.domain.model.OdometerCaptureStatus
import com.autotrip.domain.model.OdometerReading
import com.autotrip.domain.model.PurposeStatus
import com.autotrip.domain.model.TaxPeriod
import com.autotrip.domain.model.Trip
import com.autotrip.domain.model.TripEditHistory
import com.autotrip.domain.model.TripPurpose
import com.autotrip.domain.model.TripSource
import com.autotrip.domain.model.TripState
import com.autotrip.domain.model.TripUser
import com.autotrip.domain.model.ValueSource
import com.autotrip.domain.model.VehicleInfo
import com.autotrip.domain.repository.ProfileRepository
import com.autotrip.domain.repository.ReportRepository
import com.autotrip.domain.repository.TripRepository
import com.autotrip.domain.repository.TripTracker
import com.autotrip.ui.theme.AutoTripTheme
import com.autotrip.voice.ListenResult
import com.autotrip.voice.SpeakResult
import com.autotrip.voice.SpeechError
import com.autotrip.voice.SpeechInput
import com.autotrip.voice.SpeechOutput
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * 화면 연결 확인용 통합 테스트. A의 Fake가 main에 오기 전까지 쓰는 이 테스트 전용 대역(메모리 저장)을 쓴다.
 * 실제 계약 규칙을 모두 흉내 내지는 않는다 — 화면이 서로 이어지고 목적 질문이 덮이는지만 본다.
 */
@RunWith(AndroidJUnit4::class)
class AutoTripNavHostTest {

    @get:Rule
    val compose = createComposeRule()

    private val outDir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "screens")
    private val t0 = Instant.parse("2026-11-13T00:10:00Z")
    private val period = TaxPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

    // ── 이 테스트 전용 대역 ───────────────────────────────

    private class TestTracker : TripTracker {
        override val state = MutableStateFlow<TripState>(TripState.Idle)
        override val availability: StateFlow<DetectionAvailability> = MutableStateFlow(DetectionAvailability(true, true, true, true))
        override val destinationEvents = MutableSharedFlow<NavigationDestination>(extraBufferCapacity = 4)
        override suspend fun startManualTrip(): AutoTripResult<Long> = AutoTripResult.Success(9)
        override suspend fun endManualTrip(): AutoTripResult<Long> = AutoTripResult.Success(9)
    }

    private inner class TestTrips : TripRepository {
        val trips = MutableStateFlow(
            listOf(
                trip(2, TripPurpose.CLIENT_VISIT, "현대오토에버 판교"),
                trip(1, null, "판교 테크노밸리"),
            ),
        )
        val planned = mutableListOf<Triple<TripPurpose, InputMethod, String?>>()
        override fun observeTrips(period: TaxPeriod): Flow<List<Trip>> = trips
        override fun observeTrip(tripId: Long): Flow<Trip?> = trips.map { list -> list.firstOrNull { it.id == tripId } }
        override fun observePendingPurposeTrips(): Flow<List<Trip>> =
            trips.map { list -> list.filter { it.purposeStatus == PurposeStatus.PENDING } }
        override fun observeEditHistory(tripId: Long): Flow<List<TripEditHistory>> = flowOf(emptyList())
        override suspend fun confirmPurpose(tripId: Long, purpose: TripPurpose, method: InputMethod, userId: Long?, voiceRawText: String?) =
            AutoTripResult.Success(Unit)
        override suspend fun updatePurpose(tripId: Long, purpose: TripPurpose) = AutoTripResult.Success(Unit)
        override suspend fun updateUser(tripId: Long, userId: Long?) = AutoTripResult.Success(Unit)
        override suspend fun updateMemo(tripId: Long, memo: String?) = AutoTripResult.Success(Unit)
        override suspend fun setPlannedPurpose(purpose: TripPurpose, method: InputMethod, voiceRawText: String?): AutoTripResult<Unit> {
            planned += Triple(purpose, method, voiceRawText)
            return AutoTripResult.Success(Unit)
        }
    }

    private class TestProfile : ProfileRepository {
        override fun observeBusinessProfile(): Flow<BusinessProfile?> =
            flowOf(BusinessProfile(BusinessType.CORPORATION, "(주)오토트립 시연", "123-45-67890"))
        override fun observeVehicle(): Flow<VehicleInfo?> = flowOf(VehicleInfo(1, "아이오닉 5", ValueSource.SDK, "12가 3456"))
        override fun observeUsers(): Flow<List<TripUser>> = flowOf(listOf(TripUser(1, "영업팀", "김현대")))
        override fun observeDefaultUserId(): Flow<Long?> = flowOf(1L)
        override fun observeClients(): Flow<List<Client>> = flowOf(listOf(Client(1, "현대오토에버 판교", null)))
        override suspend fun saveBusinessProfile(profile: BusinessProfile) = AutoTripResult.Success(Unit)
        override suspend fun saveVehicle(model: String?, plateNumber: String): AutoTripResult<VehicleInfo> =
            AutoTripResult.Failure(AutoTripError.InvalidState("test"))
        override suspend fun addUser(department: String, name: String) = AutoTripResult.Success(TripUser(2, department, name))
        override suspend fun setDefaultUserId(userId: Long?) = AutoTripResult.Success(Unit)
        override suspend fun addClient(name: String, point: GeoPoint?) = AutoTripResult.Success(Client(2, name, point))
        override suspend fun removeClient(clientId: Long) = AutoTripResult.Success(Unit)
    }

    private inner class TestReport : ReportRepository {
        override fun observeSummary(period: TaxPeriod): Flow<MileageSummary> = flowOf(
            MileageSummary(period, 36_200, 0, 17_400, 17_400, 0, 18_800, 0.4807, 0, 1),
        )
        override suspend fun buildLogbookExport(period: TaxPeriod): AutoTripResult<LogbookExport> =
            AutoTripResult.Failure(AutoTripError.ProfileIncomplete)
    }

    /** 말한 문장을 모으고, 듣기는 미리 정한 답을 차례로 돌려준다 (없으면 응답 없음) */
    private class ScriptedSpeech(answers: List<String>) : SpeechOutput, SpeechInput {
        val spoken = mutableListOf<String>()
        private val queue = ArrayDeque(answers)
        override suspend fun prepare(): SpeechError? = null
        override suspend fun speak(text: String): SpeakResult { spoken += text; return SpeakResult.Done }
        override suspend fun listen(onPartial: (String) -> Unit, onReady: () -> Unit): ListenResult {
            onReady()
            return queue.removeFirstOrNull()?.let { ListenResult.Recognized(it) } ?: awaitCancellation()
        }
        override fun stop() {}
        override fun release() {}
    }

    private fun trip(id: Long, purpose: TripPurpose?, dest: String) = Trip(
        id = id, vehicleId = 1, source = TripSource.AUTO,
        startedAt = t0.minusSeconds(id * 7200), endedAt = t0.minusSeconds(id * 7200 - 1800),
        odometerStart = OdometerReading(45_250_100 + id * 20_000, OdometerCaptureStatus.CAPTURED, null),
        odometerEnd = OdometerReading(45_268_900 + id * 20_000, OdometerCaptureStatus.CAPTURED, null),
        startPlace = null, endPlace = null, destinationName = dest,
        userId = 1, purpose = purpose, purposeStatus = if (purpose == null) PurposeStatus.PENDING else PurposeStatus.CONFIRMED,
        purposeInputMethod = purpose?.let { InputMethod.VOICE }, purposeInputTime = null, voiceRawText = null, memo = null,
    )

    private fun setUp(
        speech: ScriptedSpeech = ScriptedSpeech(emptyList()),
        dark: Boolean = false,
        demoLog: MutableList<String>? = null,
    ): Triple<TestTracker, TestTrips, ScriptedSpeech> {
        val tracker = TestTracker()
        val trips = TestTrips()
        val demo = demoLog?.let { log ->
            DemoActions(
                drive = { log += "drive" },
                arrive = { log += "arrive" },
                setDestination = { name ->
                    log += "dest:$name"
                    tracker.destinationEvents.tryEmit(NavigationDestination(name, null, t0))
                },
                failNextOdometer = { log += "fail" },
            )
        }
        compose.setContent {
            AutoTripTheme(darkTheme = dark) {
                AutoTripNavHost(
                    tracker, trips, TestProfile(), TestReport(),
                    isDemo = true, speechOutput = { speech }, speechInput = { speech },
                    debugContent = demo?.let { d -> { DemoControls(d) } },
                )
            }
        }
        return Triple(tracker, trips, speech)
    }

    @Test
    fun 시연_버튼으로_목적지를_찍으면_거래처_제안이_나온다() {
        val log = mutableListOf<String>()
        val (_, trips, speech) = setUp(ScriptedSpeech(listOf("네")), demoLog = log)
        capture("nav-home-demo")
        compose.onNodeWithText("주행 5km").performClick()
        compose.onNodeWithText("도착").performClick()
        compose.onNodeWithText("목적지: 현대오토에버 판교캠퍼스").performClick()
        compose.waitUntil(5_000) { trips.planned.isNotEmpty() }
        assertEquals(listOf("drive", "arrive", "dest:현대오토에버 판교캠퍼스"), log)
        assertEquals("현대오토에버 판교 방문으로 기록할까요?", speech.spoken.first())
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        outDir.mkdirs()
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun 홈에서_각_화면으로_가고_홈으로_돌아온다() {
        setUp()
        compose.onNodeWithText("시뮬레이션 데이터 · 실제 차량에서 수집한 기록이 아니에요").assertIsDisplayed()
        compose.onNodeWithText("목적 미입력 1건").assertIsDisplayed()
        capture("nav-home")

        compose.onNodeWithText("운행 기록").performClick()
        compose.onNodeWithText("현대오토에버 판교  ·  영업팀 김현대").performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithText("← 기록 목록").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("← 기록 목록").performClick()
        compose.onNodeWithText("← 홈").performClick()

        compose.onNodeWithText("목적 입력").performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithText("목적 미입력 1건").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("← 홈").performClick()

        compose.onNodeWithText("운행기록부 출력").performClick()
        compose.onNodeWithText("← 홈").performClick()

        compose.onNodeWithText("설정").performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithText("자주 가는 거래처").fetchSemanticsNodes().isNotEmpty() }
        capture("nav-settings")
        compose.onNodeWithText("← 홈").performClick()
        compose.onNodeWithText("⑬ 업무사용비율").assertIsDisplayed()
    }

    @Test
    fun 목적지를_찍으면_거래처_제안이_화면_위에_뜨고_네로_저장된다() {
        val (tracker, trips, speech) = setUp(ScriptedSpeech(listOf("네")))
        runBlocking { tracker.destinationEvents.emit(NavigationDestination("현대오토에버 판교캠퍼스", null, t0)) }
        compose.waitUntil(5_000) { trips.planned.isNotEmpty() }
        assertEquals(Triple(TripPurpose.CLIENT_VISIT, InputMethod.VOICE, "네"), trips.planned.single())
        assertEquals("현대오토에버 판교 방문으로 기록할까요?", speech.spoken.first())
        compose.waitUntil(3_000) { compose.onAllNodesWithText("거래처 방문 기록 완료").fetchSemanticsNodes().isNotEmpty() }
        capture("nav-prompt-completed")
    }

    @Test
    fun 야간_화면을_모두_찍는다() {
        setUp(dark = true)
        capture("night-home")
        compose.onNodeWithText("운행 기록").performClick()
        capture("night-trips")
        compose.onNodeWithText("현대오토에버 판교  ·  영업팀 김현대").performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithText("← 기록 목록").fetchSemanticsNodes().isNotEmpty() }
        capture("night-detail")
        compose.onNodeWithText("← 기록 목록").performClick()
        compose.onNodeWithText("← 홈").performClick()
        compose.onNodeWithText("목적 입력").performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithText("목적 미입력 1건").fetchSemanticsNodes().isNotEmpty() }
        capture("night-pending")
        compose.onNodeWithText("← 홈").performClick()
        compose.onNodeWithText("운행기록부 출력").performClick()
        capture("night-export")
        compose.onNodeWithText("← 홈").performClick()
        compose.onNodeWithText("설정").performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithText("자주 가는 거래처").fetchSemanticsNodes().isNotEmpty() }
        capture("night-settings")
    }
}