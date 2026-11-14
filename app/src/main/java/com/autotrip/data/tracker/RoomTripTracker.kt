
package com.autotrip.data.tracker

import com.autotrip.data.repository.RoomTripRepository
import com.autotrip.domain.model.*
import com.autotrip.domain.repository.TripTracker
import com.autotrip.data.vehicle.OdometerSource
import com.autotrip.data.vehicle.UnsupportedOdometer
import com.autotrip.data.vehicle.missing
import com.autotrip.data.navigation.NavigationSource
import com.autotrip.data.navigation.NoNavigation
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.autotrip.data.location.TripLocationSource
import com.autotrip.data.location.NoTripLocation
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Room 기반 수동/자동 운행 추적기.
 *
 * 운행 시작·수동 종료 때 계기판을 읽는다. 조회 실패도 상태와 함께 저장한다.
 */
class RoomTripTracker internal constructor(
    private val repository: ManualTripStore,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val vehicleId: Long = 1L,
    private val odometer: OdometerSource = UnsupportedOdometer,
    private val navigation: NavigationSource = NoNavigation,
    private val location: TripLocationSource = NoTripLocation,
) : TripTracker {

    constructor(
        repository: RoomTripRepository,
        scope: CoroutineScope,
        clock: Clock = Clock.systemDefaultZone(),
        vehicleId: Long = 1L,
        odometer: OdometerSource = UnsupportedOdometer,
        navigation: NavigationSource = NoNavigation,
        location: TripLocationSource = NoTripLocation,
    ) : this(RoomManualTripStore(repository), scope, clock, vehicleId, odometer, navigation, location)

    private val mutex = Mutex()

    private val mutableState =
        MutableStateFlow<TripState>(TripState.Idle)

    override val state: StateFlow<TripState> =
        mutableState.asStateFlow()

    private val mutableAvailability =
        MutableStateFlow(
            DetectionAvailability(
                autoDetectionSupported = false,
                odometerSupported = false,
                locationSupported = false,
                navigationSupported = false,
            )
        )

    override val availability: StateFlow<DetectionAvailability> =
        mutableAvailability.asStateFlow()

    internal fun setAutoDetectionAvailable(available: Boolean) {
        mutableAvailability.update { it.copy(autoDetectionSupported = available) }
    }

    init {
        scope.launch {
            location.available.collect { supported ->
                mutableAvailability.update { it.copy(locationSupported = supported) }
            }
        }
        scope.launch {
            odometer.supported.collect { supported ->
                mutableAvailability.update { it.copy(odometerSupported = supported) }
            }
        }
        scope.launch {
            navigation.available.collect { supported ->
                mutableAvailability.update { it.copy(navigationSupported = supported) }
            }
        }
    }

    override val destinationEvents: Flow<NavigationDestination> =
        navigation.events

    /**
     * 앱 시작 시 DB에 남아 있는 미종료 운행을 복원한다.
     * start/end 호출과 동일한 Mutex로 직렬화한다.
     */
    suspend fun restoreActiveTrip() = mutex.withLock {
        val active = repository.activeTrip()
        val finished = if (active == null) repository.latestFinishedTrip() else null

        mutableState.value = if (active != null) {
            TripState.Driving(
                tripId = active.id,
                source = active.source,
                startedAt = active.startedAt,
            )
        } else if (finished != null) {
            TripState.Arrived(finished.id, finished.source, finished.endedAt!!)
        } else {
            TripState.Idle
        }
        if (active != null) runCatching { location.startJourney(active.id, restored = true) }
    }

    override suspend fun startManualTrip(): AutoTripResult<Long> =
        startTrip(TripSource.MANUAL)

    suspend fun startAutoTrip(): AutoTripResult<Long> =
        startTrip(TripSource.AUTO)

    private suspend fun startTrip(
        source: TripSource,
    ): AutoTripResult<Long> = storageSafe { mutex.withLock {

        // 메모리 상태와 DB 상태를 모두 확인한다.
        if (
            mutableState.value is TripState.Driving ||
            repository.activeTrip() != null
        ) {
            return@withLock AutoTripResult.Failure(
                AutoTripError.InvalidState(
                    "Trip already in progress"
                )
            )
        }

        val now = clock.instant()

        val reading = captureOdometer()
        val place = captureLocation()
        currentCoroutineContext().ensureActive()
        // Once persistence begins, finish publishing its result before releasing the mutex.
        // stop/restart must not leave a committed trip hidden behind an Idle memory state.
        withContext(NonCancellable) {
            val result = repository.beginLocatedTrip(
                vehicleId = vehicleId,
                source = source,
                startedAt = now,
                odometerStart = reading,
                place = place,
            )

            when (result) {
                is AutoTripResult.Success -> {
                    val trip = result.value

                    mutableState.value = TripState.Driving(
                        tripId = trip.id,
                        source = trip.source,
                        startedAt = trip.startedAt,
                    )
                    runCatching { location.startJourney(trip.id) }

                    AutoTripResult.Success(trip.id)
                }

                is AutoTripResult.Failure -> result
            }
        }
    } }

    override suspend fun endManualTrip(): AutoTripResult<Long> = finishTrip(null)

    internal suspend fun finishAutoTrip(expectedTripId: Long): AutoTripResult<Long> = finishTrip(expectedTripId)

    private suspend fun finishTrip(expectedTripId: Long?): AutoTripResult<Long> =
        storageSafe { mutex.withLock {

            // 앱 재시작 후 아직 복원되지 않은 경우도 DB에서 확인한다.
            val active = repository.activeTrip()
                ?: return@withLock AutoTripResult.Failure(
                    AutoTripError.InvalidState("No active trip")
                )

            val now = clock.instant()

            if (expectedTripId != null && (active.id != expectedTripId || active.source != TripSource.AUTO)) {
                return@withLock AutoTripResult.Failure(AutoTripError.InvalidState("Auto end target changed"))
            }
            val reading = captureOdometer(active.odometerStart.meters)
            val place = captureLocation()
            currentCoroutineContext().ensureActive()

            withContext(NonCancellable) {
                val result = repository.finishLocatedTrip(
                    tripId = active.id,
                    endedAt = now,
                    odometerEnd = reading,
                    place = place,
                )

                when (result) {
                    is AutoTripResult.Success -> {
                        mutableState.value = TripState.Arrived(
                            tripId = active.id,
                            source = active.source,
                            endedAt = now,
                        )
                        runCatching { location.endJourney(active.id) }

                        AutoTripResult.Success(active.id)
                    }

                    is AutoTripResult.Failure -> result
                }
            }
        } }

    private suspend fun <T> storageSafe(block: suspend () -> AutoTripResult<T>): AutoTripResult<T> = try {
        block()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { AutoTripResult.Failure(AutoTripError.Storage(error)) }

    private suspend fun captureLocation(): TripPlace? = try {
        withTimeoutOrNull(2_000) { location.capture() }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private suspend fun captureOdometer(minimumMeters: Long? = null): OdometerReading =
        try {
            odometer.capture(minimumMeters)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            missing(OdometerCaptureStatus.FAILED)
        }
}

/** Small persistence boundary for exercising tracker failure handling in JVM tests. */
internal interface ManualTripStore {
    suspend fun activeTrip(): Trip?
    suspend fun latestFinishedTrip(): Trip? = null
    suspend fun beginTrip(vehicleId: Long, source: TripSource, startedAt: Instant,
        odometerStart: OdometerReading): AutoTripResult<Trip>
    suspend fun finishTrip(tripId: Long, endedAt: Instant,
        odometerEnd: OdometerReading): AutoTripResult<Trip>
    suspend fun beginLocatedTrip(vehicleId: Long, source: TripSource, startedAt: Instant,
        odometerStart: OdometerReading, place: TripPlace?): AutoTripResult<Trip> =
        beginTrip(vehicleId, source, startedAt, odometerStart)
    suspend fun finishLocatedTrip(tripId: Long, endedAt: Instant,
        odometerEnd: OdometerReading, place: TripPlace?): AutoTripResult<Trip> = finishTrip(tripId, endedAt, odometerEnd)
}

private class RoomManualTripStore(private val repository: RoomTripRepository) : ManualTripStore {
    override suspend fun latestFinishedTrip() = repository.latestFinishedTrip()
    override suspend fun beginLocatedTrip(vehicleId: Long, source: TripSource, startedAt: Instant,
        odometerStart: OdometerReading, place: TripPlace?) =
        repository.beginTrip(vehicleId, source, startedAt, odometerStart, startPlace = place)
    override suspend fun finishLocatedTrip(tripId: Long, endedAt: Instant,
        odometerEnd: OdometerReading, place: TripPlace?) = repository.finishTrip(tripId, endedAt, odometerEnd, endPlace = place)
    override suspend fun activeTrip() = repository.activeTrip()
    override suspend fun beginTrip(vehicleId: Long, source: TripSource, startedAt: Instant,
        odometerStart: OdometerReading) = repository.beginTrip(vehicleId, source, startedAt, odometerStart)
    override suspend fun finishTrip(tripId: Long, endedAt: Instant,
        odometerEnd: OdometerReading) = repository.finishTrip(tripId, endedAt, odometerEnd)
}
