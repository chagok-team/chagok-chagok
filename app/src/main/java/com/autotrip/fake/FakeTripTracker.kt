package com.autotrip.fake

import com.autotrip.domain.model.*
import com.autotrip.domain.repository.TripTracker
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Demo-only tracker. No actual vehicle SDK calls are made. */
class FakeTripTracker(
    private val repository: FakeTripRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val vehicleId: Long = 1L,
    initialOdometerMeters: Long = 100_000L,
) : TripTracker {
    private val mutableState = MutableStateFlow<TripState>(TripState.Idle)
    override val state: StateFlow<TripState> = mutableState.asStateFlow()

    private val mutableAvailability = MutableStateFlow(
        DetectionAvailability(
            autoDetectionSupported = false,
            odometerSupported = true,
            locationSupported = false,
            navigationSupported = false,
        )
    )
    override val availability: StateFlow<DetectionAvailability> = mutableAvailability.asStateFlow()

    private val destinationStream = MutableSharedFlow<NavigationDestination>(
        replay = 0,
        extraBufferCapacity = 16,
    )
    override val destinationEvents: Flow<NavigationDestination> = destinationStream.asSharedFlow()

    private var odometerMeters = initialOdometerMeters
    private var failOnStart = false
    private var failOnEnd = false
    private var destination: NavigationDestination? = null

    init {
        require(initialOdometerMeters >= 0L)
        // The repository may already contain an unfinished trip (e.g. a recreated demo screen).
        repository.activeTrip()?.let {
            mutableState.value = TripState.Driving(it.id, it.source, it.startedAt)
        }
    }

    @Synchronized
    fun simulateDrive(meters: Long) {
        require(meters >= 0L) { "Distance must not be negative" }

        if (mutableState.value !is TripState.Driving) {
            check(repository.activeTrip() == null) {
                "An active trip already exists"
            }

            val now = clock.instant()
            val result = repository.beginTrip(
                vehicleId = vehicleId,
                source = TripSource.AUTO,
                startedAt = now,
                odometerStart = captureOdometer(now, isStart = true),
                destinationName = destination?.name,
            )

            when (result) {
                is AutoTripResult.Success -> {
                    val trip = result.value
                    mutableState.value = TripState.Driving(
                        trip.id,
                        trip.source,
                        trip.startedAt,
                    )
                }
                is AutoTripResult.Failure -> {
                    error("Failed to start simulated trip: ${result.error}")
                }
            }
        }

        odometerMeters = Math.addExact(odometerMeters, meters)
    }


    /** A fake arrival ends the current trip, just like the manual end button. */

    /**
     * Simulates arrival for the current demo trip.
     * Does nothing when no trip is active.
     */
    @Synchronized
    fun simulateArrive() {
        val driving = mutableState.value as? TripState.Driving ?: return
        val now = clock.instant()

        when (repository.finishTrip(
            tripId = driving.tripId,
            endedAt = now,
            odometerEnd = captureOdometer(now, isStart = false),
        )) {
            is AutoTripResult.Success -> {
                failOnStart = false
                failOnEnd = false

                mutableState.value = TripState.Arrived(
                    driving.tripId,
                    driving.source,
                    now,
                )
            }
            is AutoTripResult.Failure -> {
                // Keep Driving so the operation can be retried.
            }
        }
    }


    @Synchronized
    fun setOdometerFailure(onStart: Boolean, onEnd: Boolean) {
        failOnStart = onStart
        failOnEnd = onEnd
    }

    @Synchronized
    fun setOdometerFailure(enabled: Boolean) {
        setOdometerFailure(enabled, enabled)
    }


    /** Emitted only while a collector is active; previous destinations are never replayed. */
    @Synchronized
    fun simulateSetDestination(name: String?, point: GeoPoint? = null): Boolean {
        val event = NavigationDestination(name, point, clock.instant())
        destination = event
        return destinationStream.tryEmit(event)
    }

    override suspend fun startManualTrip(): AutoTripResult<Long> = synchronized(this) {
        if (mutableState.value is TripState.Driving || repository.activeTrip() != null) {
            return@synchronized AutoTripResult.Failure(AutoTripError.InvalidState("Trip already in progress"))
        }
        val now = clock.instant()
        when (val result = repository.beginTrip(
            vehicleId = vehicleId,
            source = TripSource.MANUAL,
            startedAt = now,
            odometerStart = captureOdometer(now, isStart = true),
            destinationName = destination?.name,
        )) {
            is AutoTripResult.Success -> {
                val trip = result.value
                mutableState.value = TripState.Driving(trip.id, trip.source, trip.startedAt)
                AutoTripResult.Success(trip.id)
            }
            is AutoTripResult.Failure -> result
        }
    }

    override suspend fun endManualTrip(): AutoTripResult<Long> = synchronized(this) {
        val driving = mutableState.value as? TripState.Driving
            ?: return@synchronized AutoTripResult.Failure(AutoTripError.InvalidState("No active trip"))
        val now = clock.instant()
        when (val result = repository.finishTrip(
            tripId = driving.tripId,
            endedAt = now,
            odometerEnd = captureOdometer(now, isStart = false),
        )) {
            is AutoTripResult.Success -> {
                failOnStart = false
                failOnEnd = false
                mutableState.value = TripState.Arrived(driving.tripId, driving.source, now)
                AutoTripResult.Success(driving.tripId)
            }
            is AutoTripResult.Failure -> result
        }
    }


    private fun captureOdometer(
        at: Instant,
        isStart: Boolean,
    ): OdometerReading {
        val failed = if (isStart) failOnStart else failOnEnd

        return if (failed) {
            OdometerReading(null, OdometerCaptureStatus.FAILED, null)
        } else {
            OdometerReading(odometerMeters, OdometerCaptureStatus.CAPTURED, at)
        }
    }

}
