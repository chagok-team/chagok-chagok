package com.autotrip.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autotrip.data.local.AutoTripDatabase
import com.autotrip.domain.model.*
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class RoomTripRepositoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: AutoTripDatabase
    private lateinit var repository: RoomTripRepository
    private val databaseName = "autotrip_room_instrumented_test.db"
    private val start = Instant.parse("2026-11-13T01:00:00Z")
    private val end = Instant.parse("2026-11-13T01:20:00Z")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
        openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(
            context,
            AutoTripDatabase::class.java,
            databaseName
        ).build()

        repository = RoomTripRepository(
            database = database,
            clock = Clock.system(ZoneId.of("Asia/Seoul"))
        )
    }

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private fun captured(meters: Long, at: Instant) =
        OdometerReading(meters, OdometerCaptureStatus.CAPTURED, at)

    private fun failed() = OdometerReading(null, OdometerCaptureStatus.FAILED, null)

    private suspend fun createTrip(startReading: OdometerReading = captured(100_000, start)): Long {
        val result = repository.beginTrip(
            vehicleId = 1L,
            source = TripSource.MANUAL,
            startedAt = start,
            odometerStart = startReading,
            destinationName = "테스트 목적지",
        )
        assertTrue("beginTrip: $result", result is AutoTripResult.Success)
        return (result as AutoTripResult.Success).value.id
    }

    private suspend fun finish(id: Long, reading: OdometerReading = captured(110_000, end)) {
        val result = repository.finishTrip(id, end, reading)
        assertTrue("finishTrip: $result", result is AutoTripResult.Success)
    }

    private fun period() = TaxPeriod(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 14))

    @Test
    fun finishedTripSurvivesDatabaseReopen() = runBlocking {
        val id = createTrip()
        finish(id)
        reopenDatabase()

        val trip = repository.observeTrip(id).first()
        assertNotNull("재접속 후 운행 기록이 null입니다.", trip)
        assertEquals(start, trip!!.startedAt)
        assertEquals(end, trip.endedAt)
        assertEquals(10_000L, trip.drivenMeters)
        assertEquals("테스트 목적지", trip.destinationName)
        val trips = repository.observeTrips(period()).first()

        val raw = database.tripDao().findTrip(id)
        assertNotNull(raw)

        val zone = java.time.ZoneId.systemDefault()
        val rangeStart = period().start
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val rangeEnd = period().endInclusive.plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

        assertTrue(
            "stored=${raw!!.startedAtEpochMillis}, " +
                    "rangeStart=$rangeStart, " +
                    "rangeEnd=$rangeEnd, " +
                    "zone=$zone, " +
                    "ids=${trips.map { it.id }}",
            trips.any { it.id == id }
        )
    }

    @Test
    fun confirmedPurposeAndEditHistorySurviveDatabaseReopen() = runBlocking {
        val id = createTrip()
        finish(id)
        val confirm = repository.confirmPurpose(id, TripPurpose.MEETING, InputMethod.VOICE, null, "회의")
        assertTrue("confirmPurpose: $confirm", confirm is AutoTripResult.Success)
        val original = repository.observeTrip(id).first()!!
        assertNotNull(original.purposeInputTime)

        val edit = repository.updatePurpose(id, TripPurpose.CLIENT_VISIT)
        assertTrue("updatePurpose: $edit", edit is AutoTripResult.Success)
        reopenDatabase()

        val restored = repository.observeTrip(id).first()!!
        assertEquals(TripPurpose.CLIENT_VISIT, restored.purpose)
        assertEquals(PurposeStatus.CONFIRMED, restored.purposeStatus)
        assertEquals(InputMethod.VOICE, restored.purposeInputMethod)
        assertEquals(original.purposeInputTime, restored.purposeInputTime)
        assertEquals("회의", restored.voiceRawText)
        val history = repository.observeEditHistory(id).first()
        assertEquals(1, history.size)
        assertEquals(EditableField.PURPOSE, history.single().field)
        assertEquals("MEETING", history.single().oldValue)
        assertEquals("CLIENT_VISIT", history.single().newValue)
    }

    @Test
    fun editingSameValueDoesNotAddHistory() = runBlocking {
        val id = createTrip()
        finish(id)
        assertTrue(repository.updateMemo(id, "메모") is AutoTripResult.Success)
        assertTrue(repository.updateMemo(id, "메모") is AutoTripResult.Success)
        assertEquals(1, repository.observeEditHistory(id).first().size)
    }

    @Test
    fun failedOdometerRemainsNullAfterReopen() = runBlocking {
        val id = createTrip(failed())
        finish(id, failed())
        reopenDatabase()

        val trip = repository.observeTrip(id).first()!!
        assertEquals(OdometerCaptureStatus.FAILED, trip.odometerStart.status)
        assertEquals(OdometerCaptureStatus.FAILED, trip.odometerEnd.status)
        assertNull(trip.odometerStart.meters)
        assertNull(trip.odometerEnd.meters)
        assertNull(trip.drivenMeters)
    }

    @Test
    fun unfinishedTripCanBeFoundAfterReopen() = runBlocking {
        val id = createTrip()
        reopenDatabase()

        val active = repository.activeTrip()
        assertNotNull(active)
        assertEquals(id, active!!.id)
        assertNull(active.endedAt)
        assertEquals(OdometerCaptureStatus.NOT_YET_CAPTURED, active.odometerEnd.status)
    }
}
