package com.autotrip.fake

import com.autotrip.domain.model.*
import com.autotrip.domain.repository.TripRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory demo repository. All writes are serialized by this object's monitor. */
class FakeTripRepository(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val defaultUserId: () -> Long? = { null },
    initialTrips: List<Trip> = emptyList(),
) : TripRepository {


    private val tripIds = AtomicLong(
        (initialTrips.maxOfOrNull { it.id } ?: 0L) + 1L
    )
    private val historyIds = AtomicLong(1L)

    private val trips = MutableStateFlow(initialTrips.toList())
    private val history =
        MutableStateFlow<List<TripEditHistory>>(emptyList())

    private var planned: PlannedPurpose? = null

    private data class PlannedPurpose(
        val purpose: TripPurpose,
        val method: InputMethod,
        val voiceRawText: String?,
        val enteredAt: Instant,
    )

    override fun observeTrips(period: TaxPeriod): Flow<List<Trip>> = trips.map { items ->
        items.filter { trip ->
            val date = trip.startedAt.atZone(clock.zone).toLocalDate()
            !date.isBefore(period.start) && !date.isAfter(period.endInclusive)
        }.sortedWith(compareByDescending<Trip> { it.startedAt }.thenByDescending { it.id })
    }

    override fun observeTrip(tripId: Long): Flow<Trip?> = trips.map { items -> items.find { it.id == tripId } }

    override fun observePendingPurposeTrips(): Flow<List<Trip>> = trips.map { items ->
        items.filter { it.endedAt != null && it.purposeStatus == PurposeStatus.PENDING }
            .sortedByDescending { it.startedAt }
    }

    override fun observeEditHistory(tripId: Long): Flow<List<TripEditHistory>> = history.map { items ->
        items.filter { it.tripId == tripId }.sortedWith(compareBy<TripEditHistory> { it.editedAt }.thenBy { it.id })
    }

    override suspend fun confirmPurpose(
        tripId: Long, purpose: TripPurpose, method: InputMethod,
        userId: Long?, voiceRawText: String?,
    ): AutoTripResult<Unit> = synchronized(this) {
        val trip = find(tripId) ?: return AutoTripResult.Failure(AutoTripError.TripNotFound(tripId))
        if (trip.purposeStatus == PurposeStatus.CONFIRMED) {
            return AutoTripResult.Failure(AutoTripError.PurposeAlreadyConfirmed(tripId))
        }
        if (trip.endedAt == null) {
            return AutoTripResult.Failure(AutoTripError.InvalidState("Trip is still driving"))
        }
        replace(trip.copy(
            purpose = purpose, purposeStatus = PurposeStatus.CONFIRMED,
            purposeInputMethod = method, purposeInputTime = clock.instant(),
            voiceRawText = voiceRawText.takeIf { method == InputMethod.VOICE },
            userId = userId ?: defaultUserId(),
        ))
        return AutoTripResult.Success(Unit)
    }

    override suspend fun updatePurpose(tripId: Long, purpose: TripPurpose): AutoTripResult<Unit> =
        synchronized(this) { edit(tripId, EditableField.PURPOSE, { it.purpose?.name }, purpose.name) { it.copy(purpose = purpose) } }

    override suspend fun updateUser(tripId: Long, userId: Long?): AutoTripResult<Unit> =
        synchronized(this) { edit(tripId, EditableField.USER, { it.userId?.toString() }, userId?.toString()) { it.copy(userId = userId) } }

    override suspend fun updateMemo(tripId: Long, memo: String?): AutoTripResult<Unit> =
        synchronized(this) { edit(tripId, EditableField.MEMO, { it.memo }, memo) { it.copy(memo = memo) } }

    private fun edit(
        tripId: Long, field: EditableField, old: (Trip) -> String?, new: String?,
        transform: (Trip) -> Trip,
    ): AutoTripResult<Unit> {
        val trip = find(tripId) ?: return AutoTripResult.Failure(AutoTripError.TripNotFound(tripId))
        if (field == EditableField.PURPOSE && trip.purposeStatus != PurposeStatus.CONFIRMED) {
            return AutoTripResult.Failure(AutoTripError.PurposeNotConfirmed(tripId))
        }
        val previous = old(trip)
        if (previous == new) return AutoTripResult.Success(Unit)
        replace(transform(trip))
        history.value = history.value + TripEditHistory(
            id = historyIds.getAndIncrement(), tripId = tripId, field = field,
            oldValue = previous, newValue = new, editedAt = clock.instant(),
        )
        return AutoTripResult.Success(Unit)
    }

    override suspend fun setPlannedPurpose(
        purpose: TripPurpose, method: InputMethod, voiceRawText: String?,
    ): AutoTripResult<Unit> = synchronized(this) {
        val now = clock.instant()
        val driving = trips.value.firstOrNull { it.endedAt == null }
        if (driving != null) {
            if (driving.purposeStatus == PurposeStatus.CONFIRMED) {
                return AutoTripResult.Failure(AutoTripError.PurposeAlreadyConfirmed(driving.id))
            }
            replace(driving.copy(
                purpose = purpose, purposeStatus = PurposeStatus.CONFIRMED,
                purposeInputMethod = method, purposeInputTime = now,
                voiceRawText = voiceRawText.takeIf { method == InputMethod.VOICE },
                userId = driving.userId ?: defaultUserId(),
            ))
        } else {
            planned = PlannedPurpose(purpose, method, voiceRawText, now)
        }
        return AutoTripResult.Success(Unit)
    }

    /** FakeTracker-only API; deliberately not added to TripRepository contract. */
    @Synchronized
    fun beginTrip(
        vehicleId: Long, source: TripSource, startedAt: Instant,
        odometerStart: OdometerReading, startPlace: TripPlace? = null,
        destinationName: String? = null,
    ): AutoTripResult<Trip> {
        if (trips.value.any { it.endedAt == null }) {
            return AutoTripResult.Failure(AutoTripError.InvalidState("A trip is already active"))
        }
        val candidate = planned
        planned = null
        val accepted = candidate?.takeIf {
            !startedAt.isBefore(it.enteredAt) &&
                Duration.between(it.enteredAt, startedAt) <= Duration.ofMinutes(30)
        }
        val trip = Trip(
            id = tripIds.getAndIncrement(), vehicleId = vehicleId, source = source,
            startedAt = startedAt, endedAt = null, odometerStart = odometerStart,
            odometerEnd = OdometerReading(null, OdometerCaptureStatus.NOT_YET_CAPTURED, null),
            startPlace = startPlace, endPlace = null, destinationName = destinationName,
            userId = defaultUserId(), purpose = accepted?.purpose,
            purposeStatus = if (accepted != null) PurposeStatus.CONFIRMED else PurposeStatus.PENDING,
            purposeInputMethod = accepted?.method, purposeInputTime = accepted?.enteredAt,
            voiceRawText = accepted?.voiceRawText?.takeIf { accepted.method == InputMethod.VOICE },
            memo = null,
        )
        trips.value = trips.value + trip
        return AutoTripResult.Success(trip)
    }

    /** FakeTracker-only API; never overwrites original start readings. */
    @Synchronized
    fun finishTrip(
        tripId: Long, endedAt: Instant, odometerEnd: OdometerReading,
        endPlace: TripPlace? = null,
    ): AutoTripResult<Trip> {
        val trip = find(tripId) ?: return AutoTripResult.Failure(AutoTripError.TripNotFound(tripId))
        if (trip.endedAt != null || endedAt.isBefore(trip.startedAt)) {
            return AutoTripResult.Failure(AutoTripError.InvalidState("Invalid trip end"))
        }
        val finished = trip.copy(endedAt = endedAt, odometerEnd = odometerEnd, endPlace = endPlace)
        replace(finished)
        return AutoTripResult.Success(finished)
    }

    @Synchronized
    fun activeTrip(): Trip? = trips.value.firstOrNull { it.endedAt == null }

    @Synchronized
    fun snapshot(): List<Trip> = trips.value.toList()

    private fun find(id: Long): Trip? = trips.value.firstOrNull { it.id == id }
    private fun replace(updated: Trip) {
        trips.value = trips.value.map { if (it.id == updated.id) updated else it }
    }
}
