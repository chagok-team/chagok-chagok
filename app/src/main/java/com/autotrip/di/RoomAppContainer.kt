
package com.autotrip.di

import android.content.Context
import androidx.room.Room
import com.autotrip.data.local.AutoTripDatabase
import com.autotrip.data.local.Migrations
import com.autotrip.data.repository.RoomProfileRepository
import com.autotrip.data.repository.RoomTripRepository
import com.autotrip.data.tracker.RoomTripTracker
import com.autotrip.data.vehicle.pleosOdometer
import com.autotrip.data.navigation.pleosNavigation
import com.autotrip.domain.repository.*
import com.autotrip.fake.FakeReportRepository
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import android.os.SystemClock
import com.autotrip.data.tracker.AutoTripCoordinator
import com.autotrip.data.vehicle.vehicleSpeedSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import com.autotrip.data.location.androidGpsLocation

class RoomAppContainer(
    context: Context,
    private val clock: Clock =
        Clock.system(ZoneId.of("Asia/Seoul")),
) : AppContainer {

    private val applicationContext = context.applicationContext

    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    private val database = Room.databaseBuilder(
        applicationContext,
        AutoTripDatabase::class.java,
        "autotrip.db"
    )
        .addMigrations(Migrations.MIGRATION_1_2)
        .build()

    // 사업자, 차량, 사용자, 거래처 정보를 Room에 저장한다.
    private val profiles = RoomProfileRepository(
        database = database
    )

    // 운행기록 생성 시 Room에 저장된 기본 운전자를 조회한다.
    private val trips = RoomTripRepository(
        database = database,
        clock = clock,
        defaultUserId = {
            profiles.observeDefaultUserId().first()
        },
    )

    private val tracker = RoomTripTracker(
        repository = trips,
        scope = applicationScope,
        clock = clock,
        vehicleId = 1L,
        odometer = pleosOdometer(applicationContext, clock),
        navigation = pleosNavigation(applicationContext, applicationScope, clock),
        location = androidGpsLocation(applicationContext, applicationScope),
    )

    private val restoration = CompletableDeferred<Unit>()
    private val restorationMutex = Mutex()
    private val autoTripCoordinator = AutoTripCoordinator(
        speedSource = vehicleSpeedSource(applicationContext, applicationScope),
        tracker = tracker,
        scope = applicationScope,
        elapsedNanos = SystemClock::elapsedRealtimeNanos,
    )

    val isRestored: Boolean
        get() = restoration.isCompleted && !restoration.isCancelled

    suspend fun awaitRestoration() {
        restoration.await()
    }

    override val tripRepository: TripRepository = trips

    override val tripTracker: TripTracker = tracker

    override val profileRepository: ProfileRepository = profiles

    override val reportRepository: ReportRepository =
        FakeReportRepository(
            trips = trips,
            profiles = profiles,
            clock = clock,
        )

    /** 앱 초기화 과정에서 호출할 복원 메서드 */
    suspend fun restoreActiveTrip() = restorationMutex.withLock {
        if (restoration.isCompleted) {
            restoration.await()
            return@withLock
        }
        try {
            tracker.restoreActiveTrip()
            autoTripCoordinator.start()
            restoration.complete(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            restoration.completeExceptionally(error)
            throw error
        }
    }
}
