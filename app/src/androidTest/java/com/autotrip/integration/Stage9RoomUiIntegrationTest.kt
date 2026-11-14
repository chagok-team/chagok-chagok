package com.autotrip.integration

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autotrip.data.local.AutoTripDatabase
import com.autotrip.data.location.TripLocationSource
import com.autotrip.data.repository.RoomProfileRepository
import com.autotrip.data.repository.RoomTripRepository
import com.autotrip.data.tracker.RoomTripTracker
import com.autotrip.data.vehicle.OdometerSource
import com.autotrip.domain.model.*
import com.autotrip.fake.FakeReportRepository
import com.autotrip.ui.home.*
import com.autotrip.ui.theme.AutoTripTheme
import com.autotrip.ui.trip.*
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real in-memory Room and A tracker -> B UI. Injected readings; not a Vehicle/GPS/Release E2E. */
@RunWith(AndroidJUnit4::class)
class Stage9RoomUiIntegrationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun autoTripManualFinishAndCoordinatesReachRoomScreens() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AutoTripDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val models = ViewModelStore()
        val clock = Clock.systemUTC()
        val startPlace = TripPlace(GeoPoint(37.566535, 126.977969), null)
        val endPlace = TripPlace(GeoPoint(37.402056, 127.108212), null)
        var captures = 0
        var places = 0
        val odometer = object : OdometerSource {
            override val supported = MutableStateFlow(true)
            override suspend fun capture(minimumMeters: Long?) = OdometerReading(
                if (++captures == 1) 100_000L else 101_000L,
                OdometerCaptureStatus.CAPTURED, clock.instant())
        }
        val location = object : TripLocationSource {
            override val available = MutableStateFlow(true)
            override suspend fun capture() = if (++places == 1) startPlace else endPlace
        }
        val repository = RoomTripRepository(database, clock)
        val profiles = RoomProfileRepository(database)
        val tracker = RoomTripTracker(repository, scope, clock, odometer = odometer, location = location)
        val report = FakeReportRepository(repository, profiles, clock) // Same report implementation as RoomAppContainer.
        val vm = ViewModelProvider(models, HomeViewModel.factory(tracker, repository, profiles, report))[HomeViewModel::class.java]
        val period = currentYearPeriod()
        val records = repository.observeTrips(period)
        val users = profiles.observeUsers()
        var page by mutableStateOf(0)
        var visible by mutableStateOf(true)
        try {
            runBlocking { tracker.restoreActiveTrip() }
            compose.setContent {
                if (visible) {
                    val home by vm.state.collectAsState()
                    val message by vm.message.collectAsState()
                    val trips by records.collectAsState(emptyList())
                    val drivers by users.collectAsState(emptyList())
                    AutoTripTheme {
                        when (page) {
                            0 -> HomeScreen(home, message, true, vm::runManual,
                                HomeNavigation(onOpenTrips = { page = 1 }))
                            1 -> TripListScreen(TripUiMapper.list(trips, drivers, period, ZoneId.systemDefault()),
                                true, onTripClick = { page = 2 })
                            else -> trips.singleOrNull()?.let { trip ->
                                TripDetailScreen(TripDetailState.Content(
                                    TripUiMapper.detail(trip, drivers, emptyList(), ZoneId.systemDefault())),
                                    true, onBack = { page = 1 })
                            }
                        }
                    }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("대기 중").fetchSemanticsNodes().isNotEmpty() }
            val id = runBlocking { (tracker.startAutoTrip() as AutoTripResult.Success).value }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("주행 중").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(TripSource.AUTO, (tracker.state.value as TripState.Driving).source)
            compose.onNodeWithText("운행 종료").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("도착").fetchSemanticsNodes().isNotEmpty() }
            val stored = runBlocking { repository.observeTrip(id).first()!! }
            assertEquals(TripSource.AUTO, stored.source)
            assertNotNull(stored.endedAt)
            assertEquals(1_000L, stored.drivenMeters)
            assertEquals(startPlace, stored.startPlace)
            assertEquals(endPlace, stored.endPlace)
            assertNull(runBlocking { repository.activeTrip() })
            compose.onNodeWithText("운행 기록").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("37.4021, 127.1082").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("37.4021, 127.1082").performClick()
            compose.onNodeWithText("37.5665, 126.9780").assertExists()
            compose.onNodeWithText("자동").assertExists()
        } finally {
            compose.runOnIdle { visible = false; models.clear() }
            compose.waitForIdle()
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            database.close()
        }
    }
}
